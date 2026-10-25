package cn.org.chris.wake.infra.agentscope.tool;

import cn.org.chris.wake.domain.mailbox.MailboxService;
import cn.org.chris.wake.domain.model.MailMessage;
import cn.org.chris.wake.domain.model.MailStatus;
import cn.org.chris.wake.infra.persistence.mailbox.FileMailboxRepository;
import cn.org.chris.wake.infra.persistence.workspace.FileWorkspaceRepository;
import cn.org.chris.wake.infra.agentscope.CompletionProtocolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 coordinated 公共工具绕过 Manager 行政邮件并统一解释 Map/JSON 正文。
 */
class CommonTeamToolsCoordinatedTest {

    /** 测试项目标识。 */
    private static final String PROJECT_ID = "coordinated-common-tools";

    /** JUnit 隔离工作区。 */
    @TempDir
    Path workspaceRoot;

    /** JSON 编解码器。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 真实文件邮箱服务。 */
    private MailboxService mailboxService;

    /** 记录 runtime v2 窄端口调用的测试协调器。 */
    private RecordingCoordinator coordinator;

    /** 与 Gateway 共享、用于断言领取和完成边界的协议注册表。 */
    private CompletionProtocolRegistry completionProtocolRegistry;

    /** 待测 PM 公共工具。 */
    private CommonTeamTools tools;

    /** 创建隔离项目、邮箱和记录型协调器。 */
    @BeforeEach
    void setUp() {
        FileWorkspaceRepository workspaceRepository = new FileWorkspaceRepository(workspaceRoot);
        workspaceRepository.initializeProject(PROJECT_ID);
        mailboxService = new MailboxService(new FileMailboxRepository(workspaceRoot, objectMapper));
        coordinator = new RecordingCoordinator();
        completionProtocolRegistry = new CompletionProtocolRegistry();
        tools = new CommonTeamTools(
                "pm", mailboxService, workspaceRepository,
                (role, projectId) -> "unused-wake", objectMapper, null, coordinator,
                completionProtocolRegistry
        );
    }

    /**
     * 验证领取 assignment 会绑定 execution/dispatch/session，完成后不发送 Manager 邮件且自动关闭 assignment。
     *
     * @throws Exception JSON 响应解析失败
     */
    @Test
    void shouldBindAssignmentAndBypassManagerAdministrativeMail() throws Exception {
        String assignmentId = mailboxService.send(
                PROJECT_ID, "pm", "manager", "task_assign", "产品设计",
                Map.of("execution_id", "work-product", "stage", "PRODUCT_DESIGN")
        );
        RuntimeContext context = RuntimeContext.builder()
                .userId("pm")
                .sessionId("session-product")
                .put("dispatchId", "dispatch-product")
                .build();

        JsonNode inbox = objectMapper.readTree(tools.readInbox(PROJECT_ID, context));
        assertThat(completionProtocolRegistry.pending("pm", "session-product"))
                .get().extracting(CompletionProtocolRegistry.Expectation::projectId)
                .isEqualTo(PROJECT_ID);
        JsonNode completed = objectMapper.readTree(tools.sendMail(
                "manager", "task_done", "产品设计完成",
                Map.of(
                        "execution_id", "work-product",
                        "artifacts", List.of("design/product_spec.md"),
                        "decision", "ACCEPTED",
                        "rationale", "完成"
                ),
                PROJECT_ID,
                context
        ));

        MailMessage assignment = mailboxService.findMessage(PROJECT_ID, "pm", assignmentId).orElseThrow();
        assertThat(inbox.path("count").asInt()).isEqualTo(1);
        assertThat(coordinator.claims).containsExactly(
                "work-product|" + assignmentId + "|dispatch-product|session-product"
        );
        assertThat(completed.path("coordinated").asBoolean()).isTrue();
        assertThat(completed.path("legacy_mail_sent").asBoolean()).isFalse();
        assertThat(assignment.status()).isEqualTo(MailStatus.DONE);
        assertThat(mailboxService.readInbox(PROJECT_ID, "manager")).isEmpty();
        assertThat(completionProtocolRegistry.pending("pm", "session-product")).isEmpty();
    }

    /**
     * 验证 Map 与合法 JSON 字符串在 task_done 边界解析为同义结构化正文。
     *
     * @throws Exception JSON 响应解析失败
     */
    @Test
    void shouldTreatMapAndJsonStringTaskDoneAsEquivalent() throws Exception {
        Map<String, Object> content = Map.of(
                "execution_id", "work-product",
                "artifacts", List.of("design/product_spec.md"),
                "decision", "ACCEPTED",
                "rationale", "完成"
        );

        JsonNode mapResult = objectMapper.readTree(tools.sendMail(
                "manager", "task_done", "完成", content, PROJECT_ID
        ));
        JsonNode stringResult = objectMapper.readTree(tools.sendMail(
                "manager", "task_done", "完成", objectMapper.writeValueAsString(content), PROJECT_ID
        ));

        assertThat(mapResult.path("execution_id").asText()).isEqualTo("work-product");
        assertThat(stringResult.path("execution_id").asText()).isEqualTo("work-product");
        assertThat(coordinator.completions).hasSize(2);
        assertThat(coordinator.completions.get(0)).isEqualTo(coordinator.completions.get(1));
    }

    /** task_done 首次拒绝应登记在当前 session，成功纠错后必须立即清除。 */
    @Test
    void shouldTrackUnresolvedCompletionRejectionUntilSuccessfulRetry() throws Exception {
        CompletionProtocolRegistry registry = new CompletionProtocolRegistry();
        tools = new CommonTeamTools(
                "pm", mailboxService, new FileWorkspaceRepository(workspaceRoot),
                (role, projectId) -> "unused-wake", objectMapper, null, coordinator, registry
        );
        RuntimeContext context = RuntimeContext.builder()
                .userId("pm")
                .sessionId("session-retry")
                .build();
        Map<String, Object> content = Map.of(
                "execution_id", "work-product",
                "artifacts", List.of("design/product_spec.md"),
                "decision", "ACCEPTED",
                "rationale", "完成"
        );
        coordinator.rejectCompletion = true;

        JsonNode rejected = objectMapper.readTree(tools.sendMail(
                "manager", "task_done", "完成", content, PROJECT_ID, context
        ));

        assertThat(rejected.path("errcode").asInt()).isEqualTo(1);
        assertThat(registry.unresolved("pm", "session-retry"))
                .get().extracting(CompletionProtocolRegistry.Rejection::projectId)
                .isEqualTo(PROJECT_ID);

        coordinator.rejectCompletion = false;
        JsonNode accepted = objectMapper.readTree(tools.sendMail(
                "manager", "task_done", "完成", content, PROJECT_ID, context
        ));

        assertThat(accepted.path("errcode").asInt()).isZero();
        assertThat(registry.unresolved("pm", "session-retry")).isEmpty();
    }

    /** 记录工具调用并返回稳定有限结果的 runtime v2 测试协调器。 */
    private static final class RecordingCoordinator implements WorkflowToolCoordinator {

        /** 已绑定 assignment 的有限摘要。 */
        private final List<String> claims = new ArrayList<>();

        /** 已收到的结构化完成正文。 */
        private final List<Map<?, ?>> completions = new ArrayList<>();

        /** 控制测试协调器是否拒绝本次完成提交。 */
        private boolean rejectCompletion;

        /** 测试协调器允许创建 runtime v2 项目。 */
        @Override
        public boolean runtimeV2Enabled() {
            return true;
        }

        /** 返回固定的项目初始化摘要。 */
        @Override
        public Map<String, Object> initializeProject(String projectId, String projectName) {
            return Map.of("coordinated", true);
        }

        /** 测试项目始终由 runtime v2 接管。 */
        @Override
        public boolean coordinated(String projectId) {
            return PROJECT_ID.equals(projectId);
        }

        /** 记录 assignment、dispatch 与 session 绑定。 */
        @Override
        public void claimAndStart(
                String projectId,
                String role,
                String assignmentId,
                Object assignmentContent,
                String dispatchId,
                String agentSessionId
        ) {
            Map<?, ?> content = (Map<?, ?>) assignmentContent;
            claims.add(content.get("execution_id") + "|" + assignmentId + "|"
                    + dispatchId + "|" + agentSessionId);
        }

        /** 记录完成正文并返回稳定有限结果。 */
        @Override
        public Map<String, Object> complete(
                String projectId,
                String role,
                String subject,
                Object content
        ) {
            if (rejectCompletion) {
                throw new IllegalArgumentException("模拟完成协议拒绝");
            }
            completions.add((Map<?, ?>) content);
            return Map.of("execution_id", content instanceof Map<?, ?> values
                    ? values.get("execution_id") : "");
        }

        /** 返回显式 review_round_id 结果。 */
        @Override
        public Map<String, Object> submitReview(String projectId, String reviewer, Object content) {
            Map<?, ?> values = (Map<?, ?>) content;
            return Map.of("review_round_id", values.get("review_round_id"));
        }

        /** 返回固定 checkpoint 迁移摘要。 */
        @Override
        public Map<String, Object> resolveCheckpoint(String projectId, String signal) {
            return Map.of("to_stage", "PRODUCT_DESIGN");
        }
    }
}
