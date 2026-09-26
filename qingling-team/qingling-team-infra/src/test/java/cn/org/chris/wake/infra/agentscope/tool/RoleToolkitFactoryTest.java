package cn.org.chris.wake.infra.agentscope.tool;

import cn.org.chris.wake.domain.event.EventService;
import cn.org.chris.wake.domain.mailbox.MailboxService;
import cn.org.chris.wake.infra.persistence.event.JsonlEventRepository;
import cn.org.chris.wake.infra.persistence.mailbox.FileMailboxRepository;
import cn.org.chris.wake.infra.persistence.workspace.FileWorkspaceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证角色最小工具集、Python 参数名兼容和八个团队工具的端到端行为。
 */
class RoleToolkitFactoryTest {

    /** JUnit 临时 workspace。 */
    @TempDir
    Path workspace;

    /** JSON 编解码器。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 文件共享工作区实现。 */
    private FileWorkspaceRepository workspaceRepository;

    /** 团队邮箱领域服务。 */
    private MailboxService mailboxService;

    /** Manager 事件领域服务。 */
    private EventService eventService;

    /** 待测角色 Toolkit 工厂。 */
    private RoleToolkitFactory factory;

    /**
     * 为每个测试创建隔离的文件 Repository 与工具工厂。
     */
    @BeforeEach
    void setUp() {
        workspaceRepository = new FileWorkspaceRepository(workspace);
        mailboxService = new MailboxService(new FileMailboxRepository(workspace, objectMapper));
        eventService = new EventService(new JsonlEventRepository(workspace, objectMapper));
        ImageAndSearchTools auxiliaryTools = new ImageAndSearchTools(
                workspace, HttpClient.newHttpClient(), "", objectMapper
        );
        factory = new RoleToolkitFactory(
                mailboxService,
                workspaceRepository,
                (role, projectId) -> "job-12345678",
                eventService,
                null,
                auxiliaryTools,
                objectMapper
        );
    }

    /**
     * Manager 必须恰有八个团队工具，其他角色恰有五个，并默认拥有中间产物工具。
     */
    @Test
    void shouldExposeEightManagerAndFiveCommonTeamTools() {
        Toolkit manager = factory.create("manager");

        assertThat(manager.getToolNames()).containsAll(RoleToolkitFactory.COMMON_TEAM_TOOL_NAMES)
                .containsAll(RoleToolkitFactory.MANAGER_TEAM_TOOL_NAMES)
                .contains(RoleToolkitFactory.INTERMEDIATE_TOOL_NAME)
                .hasSize(9);
        for (String role : Set.of("pm", "rd", "qa")) {
            Toolkit toolkit = factory.create(role);
            assertThat(toolkit.getToolNames()).containsExactlyInAnyOrderElementsOf(
                    union(RoleToolkitFactory.COMMON_TEAM_TOOL_NAMES, Set.of(
                            RoleToolkitFactory.INTERMEDIATE_TOOL_NAME
                    ))
            );
        }
        assertThatThrownBy(() -> factory.create("human"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 图片和网页搜索只能由对应 Skill 显式开启，未开启时不得进入模型 schema。
     */
    @Test
    void shouldExposeAuxiliaryToolsOnlyForActiveSkills() {
        Toolkit defaultToolkit = factory.create("pm");
        Toolkit imageToolkit = factory.create("pm", Set.of(RoleToolkitFactory.IMAGE_SKILL));
        Toolkit searchToolkit = factory.create("pm", Set.of(RoleToolkitFactory.SEARCH_SKILL));
        Toolkit bothToolkit = factory.create("pm", Set.of(
                RoleToolkitFactory.IMAGE_SKILL, RoleToolkitFactory.SEARCH_SKILL
        ));

        assertThat(defaultToolkit.getToolNames())
                .doesNotContain(RoleToolkitFactory.IMAGE_TOOL_NAME, RoleToolkitFactory.SEARCH_TOOL_NAME);
        assertThat(imageToolkit.getToolNames()).contains(RoleToolkitFactory.IMAGE_TOOL_NAME)
                .doesNotContain(RoleToolkitFactory.SEARCH_TOOL_NAME);
        assertThat(searchToolkit.getToolNames()).contains(RoleToolkitFactory.SEARCH_TOOL_NAME)
                .doesNotContain(RoleToolkitFactory.IMAGE_TOOL_NAME);
        assertThat(bothToolkit.getToolNames())
                .contains(RoleToolkitFactory.IMAGE_TOOL_NAME, RoleToolkitFactory.SEARCH_TOOL_NAME);
    }

    /**
     * AgentScope 生成的工具 schema 必须保留 Python 参数名称、必填项和可选项。
     */
    @Test
    void shouldGeneratePythonCompatibleParameterSchemas() {
        Toolkit toolkit = factory.create("manager", Set.of(RoleToolkitFactory.SEARCH_SKILL));
        ToolSchema sendMail = schema(toolkit, "send_mail");
        ToolSchema sendToHuman = schema(toolkit, "send_to_human");
        ToolSchema search = schema(toolkit, "search_web");

        assertThat(properties(sendMail)).containsOnlyKeys("to", "type", "subject", "content", "project_id");
        assertThat(required(sendMail)).containsExactlyInAnyOrder(
                "to", "type", "subject", "content", "project_id"
        );
        assertThat(properties(sendToHuman)).containsOnlyKeys(
                "routing_key", "message", "kind", "project_id", "checkpoint_id"
        );
        assertThat(required(sendToHuman)).containsExactlyInAnyOrder("routing_key", "message");
        assertThat(properties(search)).containsOnlyKeys("query", "top_k", "recency_filter", "sites");
        assertThat(required(search)).containsExactly("query");
    }

    /**
     * 八个团队工具应复用领域服务完成项目、邮件、共享文件和事件闭环。
     *
     * @throws Exception JSON 结果解析失败
     */
    @Test
    void shouldExecuteTeamWorkflowThroughDomainServices() throws Exception {
        ManagerTeamTools manager = new ManagerTeamTools(
                workspaceRepository, eventService, null, objectMapper
        );
        CommonTeamTools managerCommon = new CommonTeamTools(
                "manager", mailboxService, workspaceRepository,
                (role, projectId) -> "job-12345678", objectMapper
        );
        CommonTeamTools pmCommon = new CommonTeamTools(
                "pm", mailboxService, workspaceRepository,
                (role, projectId) -> "job-12345678", objectMapper
        );

        JsonNode created = json(manager.createProject("pawdiary-001", "Paw Diary", "# needs"));
        JsonNode sent = json(managerCommon.sendMail(
                "pm", "task_assign", "产品设计 (第 1 轮)", Map.of("task", "design"), "pawdiary-001"
        ));
        JsonNode inbox = json(pmCommon.readInbox("pawdiary-001"));
        String messageId = inbox.path("messages").get(0).path("id").asText();
        JsonNode done = json(pmCommon.markDone("pawdiary-001", messageId));
        JsonNode written = json(pmCommon.writeShared(
                "pawdiary-001", "design/product_spec.md", "# spec"
        ));
        JsonNode read = json(pmCommon.readShared("pawdiary-001", "design/product_spec.md"));
        JsonNode event = json(manager.appendEvent(
                "pawdiary-001", "assigned", Map.of("to", "pm", "msg_id", messageId)
        ));
        JsonNode human = json(manager.sendToHuman(
                "p2p:user", "请确认", "info", "pawdiary-001", ""
        ));

        assertThat(created.path("errcode").asInt()).isZero();
        assertThat(sent.path("scheduled_wake").asText()).isEqualTo("job-12345678");
        assertThat(inbox.path("count").asInt()).isEqualTo(1);
        assertThat(done.path("status").asText()).isEqualTo("done");
        assertThat(written.path("errcode").asInt()).isZero();
        assertThat(read.path("content").asText()).isEqualTo("# spec");
        assertThat(event.path("seq").asLong()).isGreaterThan(1L);
        assertThat(human.path("errcode").asInt()).isZero();
        assertThat(eventService.readAll("pawdiary-001"))
                .extracting(value -> value.get("action"))
                .contains("project_created", "assigned", "info_sent");
    }

    /**
     * 工具错误必须转换为 errcode=1，不能把参数异常抛回 AgentScope 执行器。
     *
     * @throws Exception JSON 结果解析失败
     */
    @Test
    void shouldReturnConstructiveJsonErrors() throws Exception {
        CommonTeamTools common = new CommonTeamTools(
                "rd", mailboxService, workspaceRepository,
                (role, projectId) -> "job-12345678", objectMapper
        );
        ManagerTeamTools manager = new ManagerTeamTools(
                workspaceRepository, eventService, null, objectMapper
        );

        JsonNode invalidProject = json(common.readInbox("Bad!ID"));
        JsonNode invalidKind = json(manager.sendToHuman(
                "p2p:user", "message", "unknown", "", ""
        ));

        assertThat(invalidProject.path("errcode").asInt()).isEqualTo(1);
        assertThat(invalidProject.path("errmsg").asText()).contains("invalid project_id");
        assertThat(invalidKind.path("errcode").asInt()).isEqualTo(1);
        assertThat(invalidKind.path("errmsg").asText()).contains("invalid kind");
    }

    /**
     * 从 Toolkit 中按名称查找 schema。
     *
     * @param toolkit AgentScope 工具集
     * @param name 工具名
     * @return 对应 schema
     */
    private static ToolSchema schema(Toolkit toolkit, String name) {
        return toolkit.getToolSchemas().stream()
                .filter(candidate -> candidate.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    /**
     * 提取 schema 的 properties Map。
     *
     * @param schema 工具 schema
     * @return 参数名到参数定义
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(ToolSchema schema) {
        return (Map<String, Object>) schema.getParameters().get("properties");
    }

    /**
     * 提取 schema 的 required 参数名列表。
     *
     * @param schema 工具 schema
     * @return 必填参数名
     */
    @SuppressWarnings("unchecked")
    private static java.util.List<String> required(ToolSchema schema) {
        return (java.util.List<String>) schema.getParameters().get("required");
    }

    /**
     * 合并两个工具名集合。
     *
     * @param first 第一组工具名
     * @param second 第二组工具名
     * @return 不可变并集
     */
    private static Set<String> union(Set<String> first, Set<String> second) {
        java.util.HashSet<String> values = new java.util.HashSet<>(first);
        values.addAll(second);
        return Set.copyOf(values);
    }

    /**
     * 解析工具 JSON 响应。
     *
     * @param value JSON 文本
     * @return JSON 节点
     * @throws Exception 解析失败
     */
    private JsonNode json(String value) throws Exception {
        return objectMapper.readTree(value);
    }
}
