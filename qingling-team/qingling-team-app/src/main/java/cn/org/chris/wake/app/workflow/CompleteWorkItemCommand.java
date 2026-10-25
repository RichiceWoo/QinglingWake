package cn.org.chris.wake.app.workflow;

import cn.org.chris.wake.domain.workflow.WorkflowCommandId;

import java.util.List;
import java.util.Objects;

/**
 * 提交最小工作完成引用，禁止模型指定下一阶段、下一 Owner 或 retry attempt。
 *
 * @param commandId 稳定幂等命令标识
 * @param projectId 项目标识
 * @param executionId 当前 WorkExecution 标识
 * @param owner 当前提交角色
 * @param artifacts 当前阶段产物相对路径
 * @param decision 有限完成结论
 * @param rationale 有界结论理由
 * @param selfScore 可选自评分数
 * @param hardConstraints 可选硬约束得分
 * @param impactLevel 影响级别
 * @param testExecutionId 可选真实 Test Runner execution 标识
 * @param advisoryAuditRequested 是否由 Java 策略请求建议审计
 */
public record CompleteWorkItemCommand(
        /** 稳定幂等命令标识。 */ WorkflowCommandId commandId,
        /** 所属项目标识。 */ String projectId,
        /** 当前 WorkExecution 标识。 */ String executionId,
        /** 当前提交角色。 */ String owner,
        /** 当前阶段产物相对路径。 */ List<String> artifacts,
        /** 有限完成结论。 */ WorkCompletionDecision decision,
        /** 有界结论理由。 */ String rationale,
        /** 可选自评分数。 */ Double selfScore,
        /** 可选硬约束得分。 */ Double hardConstraints,
        /** 影响级别。 */ WorkImpactLevel impactLevel,
        /** 可选真实 Test Runner execution 标识。 */ String testExecutionId,
        /** 是否由 Java 策略请求建议审计。 */ boolean advisoryAuditRequested
) {

    /** 有界理由最大字符数。 */
    private static final int MAX_RATIONALE_LENGTH = 2_000;

    /**
     * 冻结产物列表并校验最小完成协议。
     */
    public CompleteWorkItemCommand {
        Objects.requireNonNull(commandId, "commandId 不能为空");
        projectId = requireText(projectId, "projectId");
        executionId = requireText(executionId, "executionId");
        owner = requireText(owner, "owner");
        Objects.requireNonNull(decision, "decision 不能为空");
        Objects.requireNonNull(impactLevel, "impactLevel 不能为空");
        artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts 不能为空"));
        if (artifacts.isEmpty() || artifacts.stream().anyMatch(path -> path == null || path.isBlank())) {
            throw new IllegalArgumentException("artifacts 必须包含非空相对路径");
        }
        rationale = requireText(rationale, "rationale");
        if (rationale.length() > MAX_RATIONALE_LENGTH) {
            throw new IllegalArgumentException("rationale 超过长度上限");
        }
        validateScore(selfScore, "selfScore");
        validateScore(hardConstraints, "hardConstraints");
        testExecutionId = testExecutionId == null ? "" : testExecutionId.trim();
    }

    /**
     * 校验可选分数位于 0 到 1 闭区间。
     *
     * @param score 可选分数
     * @param field 字段名称
     */
    private static void validateScore(Double score, String field) {
        if (score != null && (!Double.isFinite(score) || score < 0D || score > 1D)) {
            throw new IllegalArgumentException(field + " 必须位于 0 到 1 之间");
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
