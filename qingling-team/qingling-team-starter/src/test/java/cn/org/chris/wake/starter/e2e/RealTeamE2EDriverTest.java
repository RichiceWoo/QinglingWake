package cn.org.chris.wake.starter.e2e;

import cn.org.chris.wake.app.runner.SerialDispatchRegistry;
import cn.org.chris.wake.domain.model.CronJob;
import cn.org.chris.wake.infra.agentscope.AgentScopeAgentGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /** 同一稳定故障指纹存在多个 RECOVERING execution 时应直接报告恢复预算未收敛。 */
    @Test
    void shouldDescribeRepeatedRecoveringExecutionsDirectly() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode first = objectMapper.readTree("""
                {"action":"tool_execution_failed","payload":{"retryable":true,
                "runtime_status":"RECOVERING","tool":"run_project_tests",
                "execution_id":"exec-a","failure_type":"RESULT_UNAVAILABLE",
                "failure_fingerprint":"run_project_tests:RESULT_UNAVAILABLE",
                "workflow_stage":"CODE_IMPLEMENTATION","owner":"rd"}}
                """);
        JsonNode second = objectMapper.readTree("""
                {"action":"tool_execution_failed","payload":{"retryable":true,
                "runtime_status":"RECOVERING","tool":"run_project_tests",
                "execution_id":"exec-b","failure_type":"RESULT_UNAVAILABLE",
                "failure_fingerprint":"run_project_tests:RESULT_UNAVAILABLE",
                "workflow_stage":"CODE_IMPLEMENTATION","owner":"rd"}}
                """);

        assertThat(RealTeamE2EDriver.recoveringIncidentConflictReason(List.of(first))).isNull();
        assertThat(RealTeamE2EDriver.recoveringIncidentConflictReason(List.of(first, second)))
                .contains("run_project_tests", "RESULT_UNAVAILABLE", "CODE_IMPLEMENTATION",
                        "owner=rd", "exec-a", "exec-b")
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

    /** 失败子任务全部终态后父 dispatch 持续活跃，应快速报告真实任务状态而不是等待文件超时。 */
    @Test
    void shouldDescribeHangingDispatchAfterCancelledChildGraceExpires() {
        Instant activeSince = Instant.parse("2026-10-11T08:00:00Z");
        AgentScopeAgentGateway.ActiveExecutionDiagnostic diagnostic =
                new AgentScopeAgentGateway.ActiveExecutionDiagnostic(
                        "rd",
                        "session-rd",
                        "team:rd",
                        "todo-mvp",
                        activeSince,
                        activeSince,
                        List.of(new AgentScopeAgentGateway.ChildTaskDiagnostic(
                                "task-code", "code_impl", "CANCELLED", true, activeSince,
                                "SEND", "MODEL_REQUESTED", activeSince
                        ))
                );
        Map<String, Instant> observed = new java.util.LinkedHashMap<>();
        Map<String, SerialDispatchRegistry.DispatchSnapshot> dispatch = Map.of(
                "team:rd", new SerialDispatchRegistry.DispatchSnapshot(1, activeSince)
        );

        assertThat(RealTeamE2EDriver.hangingDispatchReason(
                dispatch, List.of(diagnostic), List.of(), observed, activeSince.plusSeconds(1),
                Duration.ofSeconds(20), Duration.ofMinutes(5)
        )).isNull();
        String reason = RealTeamE2EDriver.hangingDispatchReason(
                dispatch, List.of(diagnostic), List.of(), observed, activeSince.plusSeconds(22),
                Duration.ofSeconds(20), Duration.ofMinutes(5)
        );

        assertThat(reason)
                .contains("team:rd", "role=rd", "todo-mvp", "task-code=CANCELLED")
                .doesNotContain("qa/test_plan.md");
    }

    /** 同一子 Agent 的最新 continuation 仍在运行时，旧取消任务不得触发悬挂误报。 */
    @Test
    void shouldIgnoreOldCancelledTaskWhenLatestContinuationIsRunning() {
        Instant activeSince = Instant.parse("2026-10-11T08:00:00Z");
        AgentScopeAgentGateway.ActiveExecutionDiagnostic diagnostic =
                new AgentScopeAgentGateway.ActiveExecutionDiagnostic(
                        "rd", "session-rd", "team:rd", "todo-mvp", activeSince, activeSince,
                        List.of(
                                new AgentScopeAgentGateway.ChildTaskDiagnostic(
                                        "task-1", "code_impl", "CANCELLED", true, activeSince,
                                        "SPAWN", "MODEL_REQUESTED", activeSince
                                ),
                                new AgentScopeAgentGateway.ChildTaskDiagnostic(
                                        "task-2", "code_impl", "RUNNING", false,
                                        activeSince.plusSeconds(1), "SEND", "NONE",
                                        activeSince.plusSeconds(50)
                                )
                        )
                );

        String reason = RealTeamE2EDriver.hangingDispatchReason(
                Map.of("team:rd", new SerialDispatchRegistry.DispatchSnapshot(1, activeSince)),
                List.of(diagnostic),
                List.of(),
                new java.util.LinkedHashMap<>(),
                activeSince.plusSeconds(60),
                Duration.ofSeconds(20),
                Duration.ofMinutes(5)
        );

        assertThat(reason).isNull();
    }

    /** 同一父 session 和 agentId 存在两个未终态任务时应直接报告全部 taskId。 */
    @Test
    void shouldDescribeDuplicateNonTerminalContinuationsDirectly() {
        Instant activeSince = Instant.parse("2026-10-20T14:29:12Z");
        AgentScopeAgentGateway.ActiveExecutionDiagnostic diagnostic =
                new AgentScopeAgentGateway.ActiveExecutionDiagnostic(
                        "rd", "session-rd", "team:rd", "todo-mvp", activeSince, activeSince,
                        List.of(
                                new AgentScopeAgentGateway.ChildTaskDiagnostic(
                                        "task-first", "code_impl", "RUNNING", false,
                                        activeSince, "SPAWN", "NONE", activeSince.plusSeconds(10)
                                ),
                                new AgentScopeAgentGateway.ChildTaskDiagnostic(
                                        "task-second", "code_impl", "RUNNING", false,
                                        activeSince.plusSeconds(1), "SEND", "NONE", activeSince.plusSeconds(11)
                                )
                        )
                );

        String reason = RealTeamE2EDriver.hangingDispatchReason(
                Map.of("team:rd", new SerialDispatchRegistry.DispatchSnapshot(1, activeSince)),
                List.of(diagnostic),
                List.of(),
                new java.util.LinkedHashMap<>(),
                activeSince.plusSeconds(12),
                Duration.ofSeconds(20),
                Duration.ofMinutes(5)
        );

        assertThat(reason)
                .contains("team:rd", "parent_session=session-rd", "code_impl", "task-first", "task-second")
                .doesNotContain("qa/test_plan.md");
    }

    /** RUNNING 子任务真实进度租约过期时应直接报告 taskId，而不是等待 code/main.py 超时。 */
    @Test
    void shouldDescribeRunningChildWithoutProgressImmediately() {
        Instant activeSince = Instant.parse("2026-10-11T08:00:00Z");
        Instant lastProgressAt = activeSince.plusSeconds(30);
        AgentScopeAgentGateway.ActiveExecutionDiagnostic diagnostic =
                new AgentScopeAgentGateway.ActiveExecutionDiagnostic(
                        "rd", "session-rd", "team:rd", "todo-mvp", activeSince, activeSince,
                        List.of(new AgentScopeAgentGateway.ChildTaskDiagnostic(
                                "task-stalled", "code_impl", "RUNNING", false,
                                activeSince.plusSeconds(10), "SEND", "NONE", lastProgressAt
                        ))
                );

        String reason = RealTeamE2EDriver.hangingDispatchReason(
                Map.of("team:rd", new SerialDispatchRegistry.DispatchSnapshot(1, activeSince)),
                List.of(diagnostic),
                List.of(),
                new java.util.LinkedHashMap<>(),
                activeSince.plusSeconds(331),
                Duration.ofSeconds(20),
                Duration.ofMinutes(5)
        );

        assertThat(reason)
                .contains("team:rd", "task-stalled", "code_impl", lastProgressAt.toString())
                .doesNotContain("qa/test_plan.md", "code/main.py");
    }

    /** 子任务成功终态后父回合自身长期无进展时也应直接报告，不再等待下游产物超时。 */
    @Test
    void shouldDescribeParentWithoutProgressAfterChildCompletes() {
        Instant activeSince = Instant.parse("2026-10-11T08:00:00Z");
        Instant childProgressAt = activeSince.plusSeconds(30);
        AgentScopeAgentGateway.ActiveExecutionDiagnostic diagnostic =
                new AgentScopeAgentGateway.ActiveExecutionDiagnostic(
                        "rd", "session-rd", "team:rd", "todo-mvp",
                        activeSince, activeSince.plusSeconds(10),
                        List.of(new AgentScopeAgentGateway.ChildTaskDiagnostic(
                                "task-complete", "code_impl", "COMPLETED", true,
                                activeSince.plusSeconds(5), "SPAWN", "NONE", childProgressAt
                        ))
                );

        String reason = RealTeamE2EDriver.hangingDispatchReason(
                Map.of("team:rd", new SerialDispatchRegistry.DispatchSnapshot(1, activeSince)),
                List.of(diagnostic),
                List.of(),
                new java.util.LinkedHashMap<>(),
                childProgressAt.plusSeconds(301),
                Duration.ofSeconds(20),
                Duration.ofMinutes(5)
        );

        assertThat(reason)
                .contains("team:rd", "parent_last_progress_at", "task-complete=COMPLETED")
                .doesNotContain("qa/test_plan.md");
    }

    /** Driver 悬挂阈值必须从 Gateway 租约派生并严格多出一分钟恢复启动宽限。 */
    @Test
    void shouldDeriveDriverProgressTimeoutFromGatewayLease() {
        Duration gatewayLease = AgentScopeAgentGateway.DEFAULT_CHILD_TASK_STALL_TIMEOUT;

        Duration driverTimeout = RealTeamE2EDriver.runningDispatchProgressTimeout(gatewayLease);

        assertThat(driverTimeout).isEqualTo(gatewayLease.plusSeconds(60)).isGreaterThan(gatewayLease);
    }

    /** Gateway 租约刚到期时 Driver 不得抢跑，只有超过派生阈值才报告父 dispatch 悬挂。 */
    @Test
    void shouldWaitBeyondGatewayLeaseBeforeReportingParentStall() {
        Instant activeSince = Instant.parse("2026-10-24T00:00:00Z");
        Duration gatewayLease = AgentScopeAgentGateway.DEFAULT_CHILD_TASK_STALL_TIMEOUT;
        Duration driverTimeout = RealTeamE2EDriver.runningDispatchProgressTimeout(gatewayLease);
        AgentScopeAgentGateway.ActiveExecutionDiagnostic diagnostic =
                new AgentScopeAgentGateway.ActiveExecutionDiagnostic(
                        "rd", "session-rd", "team:rd", "todo-mvp",
                        activeSince, activeSince, List.of()
                );
        Map<String, SerialDispatchRegistry.DispatchSnapshot> dispatch = Map.of(
                "team:rd", new SerialDispatchRegistry.DispatchSnapshot(1, activeSince)
        );

        String duringRecoveryStartGrace = RealTeamE2EDriver.hangingDispatchReason(
                dispatch, List.of(diagnostic), List.of(), new java.util.LinkedHashMap<>(),
                activeSince.plus(gatewayLease).plusSeconds(1), Duration.ofSeconds(20), driverTimeout
        );
        String afterDriverThreshold = RealTeamE2EDriver.hangingDispatchReason(
                dispatch, List.of(diagnostic), List.of(), new java.util.LinkedHashMap<>(),
                activeSince.plus(driverTimeout).plusSeconds(1), Duration.ofSeconds(20), driverTimeout
        );

        assertThat(duringRecoveryStartGrace).isNull();
        assertThat(afterDriverThreshold)
                .contains(
                        "team:rd",
                        "parent_last_progress_at",
                        "parent_session_progress_at",
                        "parent_progress_source=SESSION_STATE",
                        "terminal_child_tasks=none"
                );
    }

    /** 活跃 team dispatch 丢失 Gateway 回合诊断时也必须在派生阈值后直接报告。 */
    @Test
    void shouldReportActiveTeamDispatchWithoutGatewayDiagnostic() {
        Instant activeSince = Instant.parse("2026-10-24T00:00:00Z");
        Duration timeout = Duration.ofMinutes(5).plusSeconds(30);
        Map<String, SerialDispatchRegistry.DispatchSnapshot> dispatch = Map.of(
                "team:qa", new SerialDispatchRegistry.DispatchSnapshot(1, activeSince)
        );

        String reason = RealTeamE2EDriver.hangingDispatchReason(
                dispatch,
                List.of(),
                List.of(),
                new java.util.LinkedHashMap<>(),
                activeSince.plus(timeout).plusSeconds(1),
                Duration.ofSeconds(20),
                timeout
        );

        assertThat(reason).contains("team:qa", "active_gateway_diagnostic=none");
    }

    /** 首次可恢复 AgentScope incident 存在时继续等待，预算耗尽为 BLOCKED 后才允许直接失败。 */
    @Test
    void shouldAllowRecoveringIncidentBeforeReportingHangingDispatch() throws Exception {
        Instant activeSince = Instant.parse("2026-10-24T00:00:00Z");
        AgentScopeAgentGateway.ActiveExecutionDiagnostic diagnostic =
                new AgentScopeAgentGateway.ActiveExecutionDiagnostic(
                        "rd", "session-rd", "team:rd", "todo-mvp",
                        activeSince, activeSince, List.of()
                );
        Map<String, SerialDispatchRegistry.DispatchSnapshot> dispatch = Map.of(
                "team:rd", new SerialDispatchRegistry.DispatchSnapshot(1, activeSince)
        );
        ObjectMapper mapper = new ObjectMapper();
        JsonNode transition = mapper.readTree("""
                {"seq":3,"action":"workflow_transitioned",
                "payload":{"to_stage":"TECH_DESIGN","assignee":"rd"}}
                """);
        JsonNode recovering = mapper.readTree("""
                {"action":"tool_execution_failed","payload":{"tool":"agentscope_dispatch",
                "execution_id":"agentscope:todo-mvp:3:TECH_DESIGN:rd","owner":"rd",
                "failure_type":"PARENT_STALLED","failure_fingerprint":"PARENT_STALLED",
                "attempt":1,"retryable":true,"runtime_status":"RECOVERING"}}
                """);
        JsonNode blocked = mapper.readTree("""
                {"action":"tool_execution_failed","payload":{"tool":"agentscope_dispatch",
                "execution_id":"agentscope:todo-mvp:3:TECH_DESIGN:rd","owner":"rd",
                "failure_type":"PARENT_STALLED","failure_fingerprint":"PARENT_STALLED",
                "attempt":2,"retryable":false,"runtime_status":"BLOCKED"}}
                """);

        String whileRecovering = RealTeamE2EDriver.hangingDispatchReason(
                dispatch, List.of(diagnostic), List.of(transition, recovering),
                new java.util.LinkedHashMap<>(),
                activeSince.plusSeconds(600), Duration.ofSeconds(20), Duration.ofSeconds(300)
        );
        String afterBudgetExhausted = RealTeamE2EDriver.hangingDispatchReason(
                dispatch, List.of(diagnostic), List.of(transition, recovering, blocked),
                new java.util.LinkedHashMap<>(), activeSince.plusSeconds(600),
                Duration.ofSeconds(20), Duration.ofSeconds(300)
        );

        assertThat(whileRecovering).isNull();
        assertThat(afterBudgetExhausted)
                .contains("team:rd", "parent_last_progress_at", "terminal_child_tasks=none");
    }

    /** 旧阶段或不同故障指纹的 RECOVERING incident 不得给当前父停滞无限续期。 */
    @Test
    void shouldIgnoreRecoveringIncidentFromDifferentExecutionOrFingerprint() throws Exception {
        Instant activeSince = Instant.parse("2026-10-24T00:00:00Z");
        AgentScopeAgentGateway.ActiveExecutionDiagnostic diagnostic =
                new AgentScopeAgentGateway.ActiveExecutionDiagnostic(
                        "rd", "session-rd", "team:rd", "todo-mvp",
                        activeSince, activeSince, List.of()
                );
        Map<String, SerialDispatchRegistry.DispatchSnapshot> dispatch = Map.of(
                "team:rd", new SerialDispatchRegistry.DispatchSnapshot(1, activeSince)
        );
        ObjectMapper mapper = new ObjectMapper();
        JsonNode oldRecovering = mapper.readTree("""
                {"action":"tool_execution_failed","payload":{"tool":"agentscope_dispatch",
                "execution_id":"agentscope:todo-mvp:2:PRODUCT_DESIGN:rd","owner":"rd",
                "failure_type":"PARENT_STALLED","failure_fingerprint":"PARENT_STALLED",
                "attempt":1,"retryable":true,"runtime_status":"RECOVERING"}}
                """);
        JsonNode currentTransition = mapper.readTree("""
                {"seq":3,"action":"workflow_transitioned",
                "payload":{"to_stage":"TECH_DESIGN","assignee":"rd"}}
                """);
        JsonNode wrongFingerprint = mapper.readTree("""
                {"action":"tool_execution_failed","payload":{"tool":"agentscope_dispatch",
                "execution_id":"agentscope:todo-mvp:3:TECH_DESIGN:rd","owner":"rd",
                "failure_type":"CHILD_STALLED","failure_fingerprint":"CHILD_STALLED",
                "attempt":1,"retryable":true,"runtime_status":"RECOVERING"}}
                """);

        String reason = RealTeamE2EDriver.hangingDispatchReason(
                dispatch, List.of(diagnostic),
                List.of(oldRecovering, currentTransition, wrongFingerprint),
                new java.util.LinkedHashMap<>(), activeSince.plusSeconds(600),
                Duration.ofSeconds(20), Duration.ofSeconds(300)
        );

        assertThat(reason)
                .contains("team:rd", "parent_last_progress_at", "terminal_child_tasks=none");
    }

    /** 同阶段同 Owner 的多个开放 task_assign 应立即暴露其脱敏标识和状态。 */
    @Test
    void shouldDescribeDuplicateOpenAssignmentsDirectly() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode first = objectMapper.readTree("""
                {"id":"msg-first","project_id":"todo-mvp","to":"rd","type":"task_assign",
                "status":"in_progress","content":{"workflow_stage":"CODE_IMPLEMENTATION"}}
                """);
        JsonNode second = objectMapper.readTree("""
                {"id":"msg-second","project_id":"todo-mvp","to":"rd","type":"task_assign",
                "status":"unread","content":{"workflow_stage":"CODE_IMPLEMENTATION"}}
                """);

        assertThat(RealTeamE2EDriver.duplicateOpenAssignmentReason(List.of(first))).isNull();
        assertThat(RealTeamE2EDriver.duplicateOpenAssignmentReason(List.of(first, second)))
                .contains("todo-mvp", "CODE_IMPLEMENTATION", "owner=rd",
                        "msg-first=in_progress", "msg-second=unread")
                .doesNotContain("qa/test_plan.md");
    }

    /** 空闲 Cron 已记录 RD dispatch 失败时应报告取消主因，而不是继续等待 QA 文件。 */
    @Test
    void shouldDescribeIdleCronFailureWithOpenAssignmentDirectly() throws Exception {
        Instant observedAt = Instant.parse("2026-10-12T15:56:40Z");
        CronJob failedHeartbeat = failedRdHeartbeat(
                observedAt.toEpochMilli(),
                "AgentScope 子任务异常终止: code_impl=CANCELLED"
        );
        JsonNode assignment = new ObjectMapper().readTree("""
                {"id":"msg-code","project_id":"todo-mvp","from":"manager","to":"rd",
                "type":"task_assign","subject":"代码实现","status":"unread",
                "timestamp":"2026-10-12T15:23:53Z",
                "content":{"workflow_stage":"CODE_IMPLEMENTATION","instruction":"敏感业务正文"}}
                """);
        Map<String, Instant> firstObserved = new java.util.LinkedHashMap<>();
        Map<String, SerialDispatchRegistry.DispatchSnapshot> idleDispatch = Map.of(
                "team:rd", new SerialDispatchRegistry.DispatchSnapshot(0, null)
        );

        assertThat(RealTeamE2EDriver.idleCronFailureReason(
                List.of(failedHeartbeat), List.of(assignment), idleDispatch, List.of(),
                firstObserved, observedAt, Duration.ofSeconds(20)
        )).isNull();
        String reason = RealTeamE2EDriver.idleCronFailureReason(
                List.of(failedHeartbeat), List.of(assignment), idleDispatch, List.of(),
                firstObserved, observedAt.plusSeconds(21), Duration.ofSeconds(20)
        );

        assertThat(reason)
                .contains("heartbeat-rd", "team:rd", "code_impl=CANCELLED", "msg-code",
                        "CODE_IMPLEMENTATION")
                .doesNotContain("敏感业务正文", "qa/test_plan.md");
    }

    /** dispatch、AgentScope 或一次性 wake 仍承载恢复时不得把旧 Cron 错误误判为终态。 */
    @Test
    void shouldDeferCronFailureWhileRecoveryWorkExists() throws Exception {
        Instant now = Instant.parse("2026-10-12T15:57:10Z");
        CronJob failedHeartbeat = failedRdHeartbeat(
                now.minusSeconds(30).toEpochMilli(),
                "AgentScope 子任务异常终止: code_impl=CANCELLED"
        );
        JsonNode assignment = new ObjectMapper().readTree("""
                {"id":"msg-code","to":"rd","type":"task_assign","status":"unread",
                "timestamp":"2026-10-12T15:23:53Z","content":{"workflow_stage":"CODE_IMPLEMENTATION"}}
                """);
        Map<String, SerialDispatchRegistry.DispatchSnapshot> idleDispatch = Map.of(
                "team:rd", new SerialDispatchRegistry.DispatchSnapshot(0, null)
        );
        AgentScopeAgentGateway.ActiveExecutionDiagnostic activeAgent =
                new AgentScopeAgentGateway.ActiveExecutionDiagnostic(
                        "rd", "session-rd", "team:rd", "todo-mvp", now, now, List.of()
                );

        assertThat(RealTeamE2EDriver.idleCronFailureReason(
                List.of(failedHeartbeat), List.of(assignment),
                Map.of("team:rd", new SerialDispatchRegistry.DispatchSnapshot(1, now)),
                List.of(), new java.util.LinkedHashMap<>(), now, Duration.ZERO
        )).isNull();
        assertThat(RealTeamE2EDriver.idleCronFailureReason(
                List.of(failedHeartbeat), List.of(assignment), idleDispatch,
                List.of(activeAgent), new java.util.LinkedHashMap<>(), now, Duration.ZERO
        )).isNull();
        assertThat(RealTeamE2EDriver.idleCronFailureReason(
                List.of(failedHeartbeat, rdRecoveryWake(now.plusSeconds(5).toEpochMilli())),
                List.of(assignment), idleDispatch, List.of(),
                new java.util.LinkedHashMap<>(), now, Duration.ZERO
        )).isNull();
    }

    /** Cron 与开放邮箱证据必须保留路由诊断，同时排除正文并脱敏鉴权信息。 */
    @Test
    void shouldSanitizeCronAndOpenMailboxEvidence() throws Exception {
        CronJob failedHeartbeat = failedRdHeartbeat(
                Instant.parse("2026-10-12T15:56:36Z").toEpochMilli(),
                "Authorization:secret Bearer abc.def"
        );
        JsonNode assignment = new ObjectMapper().readTree("""
                {"id":"msg-code","project_id":"todo-mvp","from":"manager","to":"rd",
                "type":"task_assign","subject":"秘密主题","status":"in_progress",
                "timestamp":"2026-10-12T15:23:53Z","processing_since":"2026-10-12T15:24:00Z",
                "content":{"workflow_stage":"CODE_IMPLEMENTATION","instruction":"秘密正文"}}
                """);

        String cronEvidence = RealTeamE2EDriver.cronJobEvidence(List.of(failedHeartbeat)).toString();
        String mailboxEvidence = RealTeamE2EDriver.openMailboxEvidence(List.of(assignment)).toString();

        assertThat(cronEvidence)
                .contains("heartbeat-rd", "team:rd", "[REDACTED]")
                .doesNotContain("secret", "abc.def");
        assertThat(mailboxEvidence)
                .contains("msg-code", "todo-mvp", "CODE_IMPLEMENTATION", "in_progress")
                .doesNotContain("秘密主题", "秘密正文");
    }

    /** 阶段超时摘要应携带最后的 Cron 和 mailbox 诊断，但忽略无关快照字段。 */
    @Test
    void shouldAttachLastRuntimeSnapshotSummaryToTimeout() {
        String summary = RealTeamE2EDriver.runtimeTimeoutSummary(Map.of(
                "cron_jobs", List.of(Map.of("id", "heartbeat-rd", "last_error", "code_impl=CANCELLED")),
                "mailbox_open", List.of(Map.of("id", "msg-code", "workflow_stage", "CODE_IMPLEMENTATION")),
                "unrelated", "不应出现在超时摘要"
        ));

        assertThat(summary)
                .contains("heartbeat-rd", "code_impl=CANCELLED", "msg-code", "CODE_IMPLEMENTATION")
                .doesNotContain("不应出现在超时摘要");
    }

    /** 业务进展应刷新空闲租约，但绝不能延长等待本身的硬总预算。 */
    @Test
    void shouldResetNoProgressLeaseWithoutExtendingHardDeadline() {
        Instant startedAt = Instant.parse("2026-10-25T08:00:00Z");
        RealTeamE2EDriver.WaitProgressTracker tracker = new RealTeamE2EDriver.WaitProgressTracker(
                startedAt, Duration.ofMinutes(90), Duration.ofMinutes(12), "phase-a"
        );

        assertThat(tracker.noProgressExpired(startedAt.plus(Duration.ofMinutes(11)))).isFalse();
        tracker.observe("phase-b", startedAt.plus(Duration.ofMinutes(11)));
        assertThat(tracker.noProgressExpired(startedAt.plus(Duration.ofMinutes(22)))).isFalse();
        assertThat(tracker.noProgressExpired(startedAt.plus(Duration.ofMinutes(23)))).isTrue();
        assertThat(tracker.totalExpired(startedAt.plus(Duration.ofMinutes(89)))).isFalse();
        assertThat(tracker.totalExpired(startedAt.plus(Duration.ofMinutes(90)))).isTrue();
    }

    /** Cron 心跳和 MCP 轮询变化不得伪造业务进展，事件或 mailbox 变化才允许续租。 */
    @Test
    void shouldExcludePeriodicRuntimeNoiseFromProgressFingerprint() {
        Map<String, Object> first = Map.of(
                "event_count", 5,
                "mailbox_open", List.of(Map.of("id", "msg-review", "status", "in_progress")),
                "cron_jobs", List.of(Map.of("last_run_at_ms", 1L)),
                "mcp_health", List.of(Map.of("failures", 0))
        );
        Map<String, Object> heartbeatOnly = Map.of(
                "event_count", 5,
                "mailbox_open", List.of(Map.of("id", "msg-review", "status", "in_progress")),
                "cron_jobs", List.of(Map.of("last_run_at_ms", 2L)),
                "mcp_health", List.of(Map.of("failures", 1))
        );
        Map<String, Object> realProgress = Map.of(
                "event_count", 6,
                "mailbox_open", List.of(Map.of("id", "msg-review", "status", "done"))
        );

        assertThat(RealTeamE2EDriver.runtimeProgressFingerprint(heartbeatOnly))
                .isEqualTo(RealTeamE2EDriver.runtimeProgressFingerprint(first));
        assertThat(RealTeamE2EDriver.runtimeProgressFingerprint(realProgress))
                .isNotEqualTo(RealTeamE2EDriver.runtimeProgressFingerprint(first));
    }

    /** 超过 watchdog 窗口仍无 dispatch、Agent 或恢复 wake 的 in_progress 邮件应直接报租约悬挂。 */
    @Test
    void shouldDescribeOrphanedMailboxAfterWatchdogRecoveryWindow() throws Exception {
        Instant claimedAt = Instant.parse("2026-10-25T08:00:00Z");
        JsonNode assignment = new ObjectMapper().readTree("""
                {"id":"msg-review","project_id":"todo-mvp","to":"pm","type":"review_request",
                "status":"in_progress","processing_since":"2026-10-25T08:00:00Z"}
                """);
        Map<String, SerialDispatchRegistry.DispatchSnapshot> idleDispatch = Map.of(
                "team:pm", new SerialDispatchRegistry.DispatchSnapshot(0, null)
        );
        Duration recoveryWindow = Duration.ofMinutes(11);

        assertThat(RealTeamE2EDriver.orphanedMailboxReason(
                List.of(assignment), idleDispatch, List.of(), List.of(),
                claimedAt.plus(Duration.ofMinutes(10)), recoveryWindow
        )).isNull();
        assertThat(RealTeamE2EDriver.orphanedMailboxReason(
                List.of(assignment), idleDispatch, List.of(), List.of(),
                claimedAt.plus(Duration.ofMinutes(12)), recoveryWindow
        )).contains("msg-review", "todo-mvp", "owner=pm", "active_dispatch=false", "recovery_wake=false");
    }

    /** 失败证据必须脱敏 API Key、Authorization 与 Bearer，并限制异常长度。 */
    @Test
    void shouldRedactFailureEvidence() {
        String raw = "DASHSCOPE_API_KEY=secret NEWAPI_API_KEY=sk-newapi-secret "
                + "Authorization:token Bearer abc.def standalone=sk-standalone-secret " + "x".repeat(5_000);

        String sanitized = RealTeamE2EDriver.sanitizeEvidenceText(raw);

        assertThat(sanitized).doesNotContain(
                "secret", "token", "abc.def", "sk-newapi-secret", "sk-standalone-secret"
        ).contains("[REDACTED]", "[TRUNCATED]");
        assertThat(sanitized.length()).isLessThan(4_200);
    }

    /** DashScope Provider 只接受 DashScope 主 Key 或兼容 Qwen Key。 */
    @Test
    void shouldValidateDashScopeE2eCredential() {
        RealTeamE2EDriver.requireModelConfiguration(Map.of("QWEN_API_KEY", "test-placeholder"));

        assertThatThrownBy(() -> RealTeamE2EDriver.requireModelConfiguration(Map.of()))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("DASHSCOPE_API_KEY");
    }

    /** NewAPI Provider 必须同时提供独立 SK 与 HTTPS Base URL，不能借用 DashScope Key。 */
    @Test
    void shouldValidateNewApiE2eConfiguration() {
        RealTeamE2EDriver.requireModelConfiguration(Map.of(
                "QINGLING_MODEL_PROVIDER", "newapi",
                "NEWAPI_API_KEY", "sk-xxx",
                "NEWAPI_BASE_URL", "https://newapi.invalid/v1"
        ));

        assertThatThrownBy(() -> RealTeamE2EDriver.requireModelConfiguration(Map.of(
                "QINGLING_MODEL_PROVIDER", "newapi",
                "DASHSCOPE_API_KEY", "dashscope-placeholder"
        )))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("NEWAPI_API_KEY")
                .hasMessageNotContaining("dashscope-placeholder");
    }

    /** Spring 启动失败时也必须保存直接原因且不得泄漏凭据。 */
    @Test
    void shouldSaveSanitizedStartupFailureEvidence() throws Exception {
        Path evidence = projectRoot.resolve("target/e2e-evidence/happy-path");
        IllegalStateException failure = new IllegalStateException(
                "startup wrapper",
                new IllegalArgumentException("model unavailable Authorization:secret Bearer abc.def")
        );

        RealTeamE2EDriver.saveStartupFailureEvidence(
                evidence, "happy-path", Instant.parse("2026-10-10T13:00:00Z"), failure
        );

        JsonNode summary = new ObjectMapper().readTree(evidence.resolve("failure-summary.json").toFile());
        assertThat(summary.path("scenario").asText()).isEqualTo("happy-path");
        assertThat(summary.path("phase").asText()).isEqualTo("startup");
        assertThat(summary.path("outcome").asText()).isEqualTo("FAILED");
        assertThat(summary.path("failure_type").asText()).isEqualTo("IllegalArgumentException");
        assertThat(summary.path("failure_message").asText())
                .contains("model unavailable", "[REDACTED]")
                .doesNotContain("secret", "abc.def");
        assertThat(evidence.resolve("summary.json")).isRegularFile();
    }

    /**
     * 构造持久化最近错误的 RD heartbeat，模拟 Cron 已接收到异常 dispatch 结果。
     *
     * @param lastRunAtMs 最近触发时间，单位毫秒
     * @param lastError 已脱敏前的直接错误摘要
     * @return 可用于 Driver 诊断的重复 heartbeat
     */
    private static CronJob failedRdHeartbeat(long lastRunAtMs, String lastError) {
        return new CronJob(
                "heartbeat-rd",
                "heartbeat-rd",
                true,
                new CronJob.Schedule(CronJob.ScheduleKind.EVERY, null, 600_000L, null, null),
                new CronJob.Payload("team:rd", "__wake__:heartbeat"),
                new CronJob.State(lastRunAtMs + 600_000L, lastRunAtMs, "error", lastError),
                lastRunAtMs - 60_000L,
                lastRunAtMs,
                false
        );
    }

    /**
     * 构造尚未触发的一次性 RD wake，证明 Driver 会等待正式恢复动作。
     *
     * @param atMs 计划触发时间，单位毫秒
     * @return 尚未完成的一次性恢复任务
     */
    private static CronJob rdRecoveryWake(long atMs) {
        return new CronJob(
                "wake-rd-recovery",
                "wake-rd-recovery",
                true,
                new CronJob.Schedule(CronJob.ScheduleKind.AT, atMs, null, null, null),
                new CronJob.Payload("team:rd", "__wake__:new_mail:todo-mvp"),
                new CronJob.State(null, null, null, null),
                atMs - 1_000L,
                atMs - 1_000L,
                true
        );
    }
}
