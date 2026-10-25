package cn.org.chris.wake.domain.workflow;

import java.util.Objects;

/**
 * 提供不含 stdout、stderr 或凭据的真实 Test Runner 完成证据。
 *
 * @param executionId Test Runner execution 标识
 * @param projectId 所属项目标识
 * @param role 测试发起角色
 * @param stage 测试启动时冻结的业务阶段
 * @param status 真实执行状态
 * @param exitCode 进程退出码
 * @param pytestCollected pytest 收集数量
 * @param pytestPassed pytest 通过数量
 * @param coveragePercent 覆盖率百分比
 * @param failureType 有限失败类型
 * @param retryable 是否允许 Java 自动恢复一次
 */
public record ProjectTestEvidence(
        /** Test Runner execution 标识。 */ String executionId,
        /** 所属项目标识。 */ String projectId,
        /** 测试发起角色。 */ String role,
        /** 测试启动时冻结的业务阶段。 */ WorkflowStage stage,
        /** 真实执行有限状态。 */ ProjectTestEvidenceStatus status,
        /** 进程退出码，尚无可靠终态时可为空。 */ Integer exitCode,
        /** pytest 收集数量。 */ int pytestCollected,
        /** pytest 通过数量。 */ int pytestPassed,
        /** 覆盖率百分比，无法解析时为负数。 */ double coveragePercent,
        /** 有限失败类型。 */ String failureType,
        /** 是否允许 Java 自动恢复一次。 */ boolean retryable
) {

    /**
     * 校验测试证据身份、计数和状态终态约束。
     */
    public ProjectTestEvidence {
        executionId = requireText(executionId, "executionId");
        projectId = requireText(projectId, "projectId");
        role = requireText(role, "role");
        failureType = requireText(failureType, "failureType");
        Objects.requireNonNull(stage, "stage 不能为空");
        Objects.requireNonNull(status, "status 不能为空");
        if (pytestCollected < 0 || pytestPassed < 0 || pytestPassed > pytestCollected) {
            throw new IllegalArgumentException("pytest 计数不合法");
        }
    }

    /**
     * 要求字符串非空并去除边界空白。
     *
     * @param value 待校验值
     * @param field 字段名称
     * @return 归一化非空字符串
     */
    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.trim();
    }
}
