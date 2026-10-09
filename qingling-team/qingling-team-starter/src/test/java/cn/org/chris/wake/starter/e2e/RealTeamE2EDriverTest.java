package cn.org.chris.wake.starter.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证真实 E2E 隔离目录清理不会破坏 Docker bind mount 的根目录身份。
 */
class RealTeamE2EDriverTest {

    /** JUnit 提供的临时 Maven 项目根目录。 */
    @TempDir
    Path projectRoot;

    /**
     * 清理 workspace 时应删除全部旧内容，同时保留根目录及其文件系统标识。
     *
     * @throws Exception 创建或清理测试目录失败
     */
    @Test
    void shouldPreserveBindMountRootWhileClearingContents() throws Exception {
        Path workspaceRoot = projectRoot.resolve("target/e2e-workspace");
        Path nestedFile = workspaceRoot.resolve("shared/projects/stale/events.jsonl");
        Files.createDirectories(nestedFile.getParent());
        Files.writeString(nestedFile, "stale");
        Object fileKeyBefore = Files.readAttributes(
                workspaceRoot, java.nio.file.attribute.BasicFileAttributes.class
        ).fileKey();

        RealTeamE2EDriver.resetTargetDirectory(projectRoot, workspaceRoot);

        Object fileKeyAfter = Files.readAttributes(
                workspaceRoot, java.nio.file.attribute.BasicFileAttributes.class
        ).fileKey();
        assertThat(workspaceRoot).isDirectory();
        assertThat(fileKeyAfter).isEqualTo(fileKeyBefore);
        try (var children = Files.list(workspaceRoot)) {
            assertThat(children).isEmpty();
        }
    }

    /** 不可重试工具 incident 应返回直接原因，不能退化成下游文件超时。 */
    @Test
    void shouldDescribeTerminalToolIncidentDirectly() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode recoverable = objectMapper.readTree("""
                {"action":"tool_execution_failed","payload":{"retryable":true,"runtime_status":"RECOVERING"}}
                """);
        JsonNode blocked = objectMapper.readTree("""
                {"action":"tool_execution_failed","payload":{"retryable":false,"runtime_status":"BLOCKED",
                "tool":"run_project_tests","execution_id":"exec-2","failure_type":"MCP_TRANSPORT",
                "stage":"CODE_IMPLEMENTATION","owner":"rd"}}
                """);

        assertThat(RealTeamE2EDriver.terminalIncidentReason(List.of(recoverable))).isNull();
        assertThat(RealTeamE2EDriver.terminalIncidentReason(List.of(recoverable, blocked)))
                .contains("run_project_tests", "exec-2", "MCP_TRANSPORT", "CODE_IMPLEMENTATION", "rd")
                .doesNotContain("qa/test_plan.md");
    }

    /** 已完成的当前阶段任务缺少 task_done 时应直接报告协议断点而不是等待下游文件。 */
    @Test
    void shouldDescribeMissingTaskDoneAsWorkflowDeadEnd() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode transition = objectMapper.readTree("""
                {"action":"workflow_transitioned","payload":{"to_stage":"CODE_IMPLEMENTATION",
                "assignee":"rd","subject":"代码实现 (第 1 轮)"}}
                """);
        JsonNode oldCompletion = objectMapper.readTree("""
                {"id":"msg-old","from":"rd","to":"manager","type":"task_done","status":"done",
                "timestamp":"2026-10-08T13:13:21+00:00"}
                """);
        JsonNode assignment = objectMapper.readTree("""
                {"id":"msg-code","from":"manager","to":"rd","type":"task_assign",
                "subject":"代码实现 (第 1 轮)","content":{"workflow_stage":"CODE_IMPLEMENTATION"},
                "status":"done","timestamp":"2026-10-08T13:14:01+00:00"}
                """);

        String reason = RealTeamE2EDriver.workflowProtocolDeadEndReason(
                List.of(transition), List.of(oldCompletion, assignment), Map.of("team:rd", 0)
        );

        assertThat(reason)
                .contains("CODE_IMPLEMENTATION", "owner=rd", "msg-code", "task_done_missing=true")
                .doesNotContain("qa/test_plan.md");
    }

    /** 尚有邮箱或 dispatch 工作时不得把短暂阶段切换窗口误判为完成协议死锁。 */
    @Test
    void shouldWaitWhileWorkflowStillHasRunnableWork() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode transition = objectMapper.readTree("""
                {"action":"workflow_transitioned","payload":{"to_stage":"CODE_IMPLEMENTATION",
                "assignee":"rd","subject":"代码实现 (第 1 轮)"}}
                """);
        JsonNode assignment = objectMapper.readTree("""
                {"id":"msg-code","to":"rd","type":"task_assign","subject":"代码实现 (第 1 轮)",
                "content":{"workflow_stage":"CODE_IMPLEMENTATION"},"status":"done",
                "timestamp":"2026-10-08T13:14:01+00:00"}
                """);
        JsonNode completion = objectMapper.readTree("""
                {"id":"msg-done","from":"rd","to":"manager","type":"task_done","status":"unread",
                "timestamp":"2026-10-08T13:15:01+00:00"}
                """);

        assertThat(RealTeamE2EDriver.workflowProtocolDeadEndReason(
                List.of(transition), List.of(assignment, completion), Map.of("team:manager", 0)
        )).isNull();
        assertThat(RealTeamE2EDriver.workflowProtocolDeadEndReason(
                List.of(transition), List.of(assignment), Map.of("team:rd", 1)
        )).isNull();
    }

    /** 连续完成门禁拒绝且 dispatch 空闲时应直接报告真实协议原因。 */
    @Test
    void shouldDescribeRepeatedCompletionRejectionDirectly() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode transition = objectMapper.readTree("""
                {"ts":"2026-10-09T15:00:00Z","action":"workflow_transitioned",
                "payload":{"to_stage":"CODE_IMPLEMENTATION","assignee":"rd"}}
                """);
        JsonNode first = objectMapper.readTree("""
                {"ts":"2026-10-09T15:01:00Z","action":"completion_rejected",
                "payload":{"role":"rd","workflow_stage":"CODE_IMPLEMENTATION",
                "failure_type":"COMPLETION_EVIDENCE_REJECTED","reason":"content 必须是结构化 JSON"}}
                """);
        JsonNode second = objectMapper.readTree("""
                {"ts":"2026-10-09T15:02:00Z","action":"completion_rejected",
                "payload":{"role":"rd","workflow_stage":"CODE_IMPLEMENTATION",
                "failure_type":"COMPLETION_EVIDENCE_REJECTED","reason":"content 必须是结构化 JSON"}}
                """);

        String reason = RealTeamE2EDriver.completionRejectionReason(
                List.of(transition, first, second), List.of(), Map.of("team:rd", 0)
        );

        assertThat(reason)
                .contains("CODE_IMPLEMENTATION", "owner=rd", "rejection_count=2", "结构化 JSON")
                .doesNotContain("qa/test_plan.md");
    }

    /** 活跃回合或拒绝后的成功 task_done 应阻止完成协议故障误报。 */
    @Test
    void shouldNotReportCompletionRejectionWhileRecoverable() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode transition = objectMapper.readTree("""
                {"ts":"2026-10-09T15:00:00Z","action":"workflow_transitioned",
                "payload":{"to_stage":"CODE_IMPLEMENTATION","assignee":"rd"}}
                """);
        JsonNode first = objectMapper.readTree("""
                {"ts":"2026-10-09T15:01:00Z","action":"completion_rejected",
                "payload":{"role":"rd","workflow_stage":"CODE_IMPLEMENTATION"}}
                """);
        JsonNode second = objectMapper.readTree("""
                {"ts":"2026-10-09T15:02:00Z","action":"completion_rejected",
                "payload":{"role":"rd","workflow_stage":"CODE_IMPLEMENTATION"}}
                """);
        JsonNode completion = objectMapper.readTree("""
                {"timestamp":"2026-10-09T15:03:00Z","type":"task_done","from":"rd","status":"unread"}
                """);

        assertThat(RealTeamE2EDriver.completionRejectionReason(
                List.of(transition, first, second), List.of(), Map.of("team:rd", 1)
        )).isNull();
        assertThat(RealTeamE2EDriver.completionRejectionReason(
                List.of(transition, first, second), List.of(completion), Map.of("team:rd", 0)
        )).isNull();
    }

    /** 失败证据必须脱敏 API Key、Authorization 与 Bearer，并限制异常长度。 */
    @Test
    void shouldRedactFailureEvidence() {
        String raw = "DASHSCOPE_API_KEY=secret Authorization:token Bearer abc.def " + "x".repeat(5_000);

        String sanitized = RealTeamE2EDriver.sanitizeEvidenceText(raw);

        assertThat(sanitized).doesNotContain("secret", "token", "abc.def").contains("[REDACTED]", "[TRUNCATED]");
        assertThat(sanitized.length()).isLessThan(4_200);
    }
}
