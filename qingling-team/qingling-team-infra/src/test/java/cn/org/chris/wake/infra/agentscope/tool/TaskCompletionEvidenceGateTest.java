package cn.org.chris.wake.infra.agentscope.tool;

import cn.org.chris.wake.domain.workflow.WorkflowStage;
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

    /**
     * 初始化项目目录和门禁。
     */
    @BeforeEach
    void setUp() {
        workspace = new FileWorkspaceRepository(temporaryDirectory);
        workspace.initializeProject("gate-project");
        gate = new TaskCompletionEvidenceGate(workspace);
    }

    /**
     * RD 缺少退出码、测试数量或覆盖率任一证据都应被拒绝。
     */
    @Test
    void shouldRejectRdCompletionWithoutAllThreeEvidenceFields() {
        assertThatThrownBy(() -> gate.validate(
                "gate-project", WorkflowStage.CODE_IMPLEMENTATION, "rd",
                Map.of("exit_code", 0, "pytest_collected", 8)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("覆盖率");

        assertThatThrownBy(() -> gate.validate(
                "gate-project", WorkflowStage.CODE_IMPLEMENTATION, "rd",
                Map.of("exit_code", 1, "pytest_collected", 8, "coverage_percent", 91)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exit_code=0");
    }

    /**
     * RD 提供完整结构化证据时允许成功回报。
     */
    @Test
    void shouldAcceptRdCompletionWithExitCountAndCoverage() {
        assertThatCode(() -> gate.validate(
                "gate-project", WorkflowStage.CODE_IMPLEMENTATION, "rd",
                Map.of("evidence", Map.of(
                        "exit_code", 0,
                        "pytest_passed", 8,
                        "coverage_percent", 91.5
                ))
        )).doesNotThrowAnyException();
    }

    /**
     * QA 必须同时生成非空测试报告和证据矩阵。
     */
    @Test
    void shouldRequireQaReportAndEvidenceMatrix() {
        workspace.write("gate-project", "qa", "qa/test_report.md", "# 测试报告\n全部通过");

        assertThatThrownBy(() -> gate.validate(
                "gate-project", WorkflowStage.QA_TEST_EXECUTION, "qa", Map.of()
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("证据矩阵");

        workspace.write("gate-project", "qa", "qa/evidence_matrix.md", "# 证据矩阵\n| TC-1 | pass |");
        assertThatCode(() -> gate.validate(
                "gate-project", WorkflowStage.QA_TEST_EXECUTION, "qa", Map.of()
        )).doesNotThrowAnyException();
    }
}
