package cn.org.chris.wake.infra.agentscope.tool;

import cn.org.chris.wake.domain.workflow.WorkflowStage;
import cn.org.chris.wake.domain.workflow.WorkflowSignal;
import cn.org.chris.wake.infra.agentscope.ProjectTestExecution;
import cn.org.chris.wake.infra.agentscope.ProjectTestExecutionRegistry;
import cn.org.chris.wake.infra.persistence.workspace.FileWorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 RD 与 QA 不能在缺少真实证据时宣称任务成功。
 */
class TaskCompletionEvidenceGateTest {

    /** JUnit 隔离工作区。 */
    @TempDir
    Path temporaryDirectory;

    /** 真实文件工作区端口。 */
    private FileWorkspaceRepository workspace;

    /** 待测 Java 完成门禁。 */
    private TaskCompletionEvidenceGate gate;

    /** 模拟仅由真实 Test Runner 写入的执行证据账本。 */
    private ProjectTestExecutionRegistry registry;

    /**
     * 初始化项目目录和门禁。
     */
    @BeforeEach
    void setUp() {
        workspace = new FileWorkspaceRepository(temporaryDirectory);
        workspace.initializeProject("gate-project");
        registry = new ProjectTestExecutionRegistry();
        gate = new TaskCompletionEvidenceGate(workspace, registry);
    }

    /**
     * RD 缺少退出码、测试数量或覆盖率任一证据都应被拒绝。
     */
    @Test
    void shouldRejectRdCompletionWithFabricatedOrMismatchedEvidence() {
        ProjectTestExecution success = execution(
                "rd-success",
                ProjectTestExecution.Status.SUCCEEDED,
                0,
                8,
                8,
                91.5D,
                ProjectTestExecution.FailureType.NONE,
                false
        );
        registry.record("gate-project", "rd", WorkflowStage.CODE_IMPLEMENTATION, success);

        assertThatThrownBy(() -> gate.validate(
                "gate-project", WorkflowStage.CODE_IMPLEMENTATION, "rd",
                Map.of(
                        "execution_id", "not-recorded",
                        "status", "SUCCEEDED",
                        "exit_code", 0,
                        "pytest_collected", 8,
                        "pytest_passed", 8,
                        "coverage_percent", 91.5,
                        "failure_type", "NONE",
                        "retryable", false
                )
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不属于当前项目、角色和阶段");

        assertThatThrownBy(() -> gate.validate(
                "gate-project", WorkflowStage.CODE_IMPLEMENTATION, "rd",
                Map.of("evidence", Map.of(
                        "execution_id", "rd-success",
                        "status", "SUCCEEDED",
                        "exit_code", 0,
                        "pytest_collected", 8,
                        "pytest_passed", 8,
                        "coverage_percent", 80,
                        "failure_type", "NONE",
                        "retryable", false
                ))
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("coverage_percent");
    }

    /**
     * RD 提供完整结构化证据时允许成功回报。
     */
    @Test
    void shouldAcceptRdCompletionWithExitCountAndCoverage() {
        ProjectTestExecution success = execution(
                "rd-success",
                ProjectTestExecution.Status.SUCCEEDED,
                0,
                8,
                8,
                91.5D,
                ProjectTestExecution.FailureType.NONE,
                false
        );
        registry.record("gate-project", "rd", WorkflowStage.CODE_IMPLEMENTATION, success);
        workspace.write("gate-project", "rd", "code/main.py", "app = object()");

        assertThatCode(() -> gate.validate(
                "gate-project", WorkflowStage.CODE_IMPLEMENTATION, "rd",
                Map.of("evidence", evidence(success))
        )).doesNotThrowAnyException();
    }

    /** RD 即使测试全绿也必须交付唯一 code/main.py，且不得同时保留 code/app/main.py。 */
    @Test
    void shouldRejectMissingOrDuplicateRdEntrypoint() {
        ProjectTestExecution success = execution(
                "rd-entrypoint",
                ProjectTestExecution.Status.SUCCEEDED,
                0,
                8,
                8,
                91.5D,
                ProjectTestExecution.FailureType.NONE,
                false
        );
        registry.record("gate-project", "rd", WorkflowStage.CODE_IMPLEMENTATION, success);

        assertThatThrownBy(() -> gate.validate(
                "gate-project", WorkflowStage.CODE_IMPLEMENTATION, "rd", evidence(success)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("code/main.py");

        workspace.write("gate-project", "rd", "code/main.py", "app = object()");
        workspace.write("gate-project", "rd", "code/app/main.py", "app = object()");

        assertThatThrownBy(() -> gate.validate(
                "gate-project", WorkflowStage.CODE_IMPLEMENTATION, "rd", evidence(success)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("code/app/main.py");
    }

    /** 测试导入必须以 code 根为唯一模块根，禁止依赖歧义 app 包。 */
    @Test
    void shouldRejectTestsImportingAmbiguousAppPackage() {
        ProjectTestExecution success = execution(
                "rd-import-root",
                ProjectTestExecution.Status.SUCCEEDED,
                0,
                2,
                2,
                88D,
                ProjectTestExecution.FailureType.NONE,
                false
        );
        registry.record("gate-project", "rd", WorkflowStage.CODE_IMPLEMENTATION, success);
        workspace.write("gate-project", "rd", "code/main.py", "app = object()");
        workspace.write(
                "gate-project", "rd", "code/tests/test_main.py", "from app import main\n"
        );

        assertThatThrownBy(() -> gate.validate(
                "gate-project", WorkflowStage.CODE_IMPLEMENTATION, "rd", evidence(success)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("禁止依赖 app 包")
                .hasMessageContaining("code/tests/test_main.py");
    }

    /**
     * QA 必须同时生成非空测试报告和证据矩阵。
     */
    @Test
    void shouldRequireQaReportAndEvidenceMatrix() {
        ProjectTestExecution success = execution(
                "qa-success",
                ProjectTestExecution.Status.SUCCEEDED,
                0,
                6,
                6,
                87D,
                ProjectTestExecution.FailureType.NONE,
                false
        );
        registry.record("gate-project", "qa", WorkflowStage.QA_TEST_EXECUTION, success);
        workspace.write("gate-project", "qa", "qa/test_report.md", "# 测试报告\n全部通过");

        assertThatThrownBy(() -> gate.validate(
                "gate-project", WorkflowStage.QA_TEST_EXECUTION, "qa", evidence(success)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("证据矩阵");

        workspace.write("gate-project", "qa", "qa/evidence_matrix.md", "# 证据矩阵\n| TC-1 | pass |");
        assertThatCode(() -> gate.validate(
                "gate-project", WorkflowStage.QA_TEST_EXECUTION, "qa", evidence(success)
        )).doesNotThrowAnyException();
    }

    /**
     * QA 缺陷信号必须绑定真实 pytest 失败，失败结果不能被当作阶段接受。
     */
    @Test
    void shouldBindQaTransitionSignalToRealExecutionOutcome() {
        ProjectTestExecution failed = execution(
                "qa-failed",
                ProjectTestExecution.Status.TEST_FAILED,
                1,
                6,
                5,
                86D,
                ProjectTestExecution.FailureType.PYTEST_FAILURE,
                false
        );
        registry.record("gate-project", "qa", WorkflowStage.QA_TEST_EXECUTION, failed);
        workspace.write(
                "gate-project",
                "qa",
                "qa/test_report.md",
                "# 测试报告\n## 证据矩阵\n| TC-1 | fail |"
        );

        assertThatCode(() -> gate.validateTransition(
                "gate-project",
                WorkflowStage.QA_TEST_EXECUTION,
                "qa",
                evidence(failed),
                WorkflowSignal.QA_DEFECT_FOUND
        )).doesNotThrowAnyException();
        assertThatThrownBy(() -> gate.validateTransition(
                "gate-project",
                WorkflowStage.QA_TEST_EXECUTION,
                "qa",
                evidence(failed),
                WorkflowSignal.STAGE_ACCEPTED
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("真实测试执行未成功");
    }

    /**
     * 创建不含敏感输出的测试执行 fixture。
     *
     * @param executionId 执行标识
     * @param status 执行终态
     * @param exitCode pytest 退出码
     * @param collected 收集数量
     * @param passed 通过数量
     * @param coverage 覆盖率百分比
     * @param failureType 失败分类
     * @param retryable 是否允许恢复
     * @return 测试执行 fixture
     */
    private static ProjectTestExecution execution(
            String executionId,
            ProjectTestExecution.Status status,
            Integer exitCode,
            int collected,
            int passed,
            double coverage,
            ProjectTestExecution.FailureType failureType,
            boolean retryable
    ) {
        return new ProjectTestExecution(
                executionId,
                status,
                exitCode,
                collected,
                passed,
                coverage,
                failureType,
                retryable,
                "",
                ""
        );
    }

    /**
     * 将真实执行转换为 task_done 必须原样携带的字段。
     *
     * @param execution 已登记真实执行
     * @return 结构化完成证据
     */
    private static Map<String, Object> evidence(ProjectTestExecution execution) {
        return Map.of(
                "execution_id", execution.executionId(),
                "status", execution.status().name(),
                "exit_code", execution.exitCode(),
                "pytest_collected", execution.pytestCollected(),
                "pytest_passed", execution.pytestPassed(),
                "coverage_percent", execution.coveragePercent(),
                "failure_type", execution.failureType().name(),
                "retryable", execution.retryable()
        );
    }
}
