package cn.org.chris.wake.domain.workflow;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 作为 runtime v2 唯一权威，原子保存工作执行、评审、命令结果与 outbox。
 *
 * @param schemaVersion 持久化 schema 版本
 * @param projectId 所属项目标识
 * @param profile 项目创建时冻结的档位
 * @param reviewPolicy 项目创建时冻结的评审策略
 * @param executions 全部工作执行快照
 * @param reviewRounds 全部评审轮次快照
 * @param processedCommands 有界已处理命令窗口
 * @param outboxActions 待投影与近期已投影动作
 * @param createdAt aggregate 创建时间
 * @param updatedAt aggregate 最近更新时间
 */
public record WorkflowRuntimeState(
        /** 持久化 schema 版本。 */ int schemaVersion,
        /** 所属项目标识。 */ String projectId,
        /** 项目创建时冻结的工作流档位。 */ WorkflowProfile profile,
        /** 项目创建时冻结的评审策略。 */ ReviewPolicy reviewPolicy,
        /** 全部工作执行快照。 */ List<WorkExecution> executions,
        /** 全部评审轮次快照。 */ List<ReviewRound> reviewRounds,
        /** 有界已处理命令窗口。 */ List<ProcessedWorkflowCommand> processedCommands,
        /** 待投影与近期已投影动作。 */ List<OutboxAction> outboxActions,
        /** aggregate 创建时间。 */ Instant createdAt,
        /** aggregate 最近更新时间。 */ Instant updatedAt
) {

    /** 当前唯一支持的 runtime v2 schema。 */
    public static final int CURRENT_SCHEMA_VERSION = 2;

    /** 已处理命令去重窗口上限。 */
    public static final int MAX_PROCESSED_COMMANDS = 512;

    /**
     * 冻结集合并校验跨实体身份、唯一开放执行和引用关系。
     */
    public WorkflowRuntimeState {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("不支持 workflow runtime schema: " + schemaVersion);
        }
        projectId = requireText(projectId, "projectId");
        Objects.requireNonNull(profile, "profile 不能为空");
        Objects.requireNonNull(reviewPolicy, "reviewPolicy 不能为空");
        boolean strictProfile = profile == WorkflowProfile.STRICT;
        boolean strictPolicy = reviewPolicy == ReviewPolicy.STRICT_BLOCKING;
        if (strictProfile != strictPolicy) {
            throw new IllegalArgumentException("STRICT profile 与 STRICT_BLOCKING policy 必须同时冻结");
        }
        Objects.requireNonNull(createdAt, "createdAt 不能为空");
        Objects.requireNonNull(updatedAt, "updatedAt 不能为空");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt 不能早于 createdAt");
        }
        executions = List.copyOf(Objects.requireNonNull(executions, "executions 不能为空"));
        reviewRounds = List.copyOf(Objects.requireNonNull(reviewRounds, "reviewRounds 不能为空"));
        processedCommands = List.copyOf(Objects.requireNonNull(
                processedCommands, "processedCommands 不能为空"
        ));
        outboxActions = List.copyOf(Objects.requireNonNull(outboxActions, "outboxActions 不能为空"));
        validateExecutions(projectId, profile, executions);
        validateReviewRounds(projectId, executions, reviewRounds);
        validateUniqueIds(processedCommands, outboxActions);
        if (processedCommands.size() > MAX_PROCESSED_COMMANDS) {
            throw new IllegalArgumentException("processedCommands 超过有界窗口");
        }
    }

    /**
     * 显式创建一个新项目的空 runtime v2 aggregate。
     *
     * @param projectId 项目标识
     * @param profile 冻结工作流档位
     * @param reviewPolicy 冻结评审策略
     * @param now 创建时间
     * @return 空 v2 aggregate
     */
    public static WorkflowRuntimeState initialize(
            String projectId,
            WorkflowProfile profile,
            ReviewPolicy reviewPolicy,
            Instant now
    ) {
        return new WorkflowRuntimeState(
                CURRENT_SCHEMA_VERSION, projectId, profile, reviewPolicy,
                List.of(), List.of(), List.of(), List.of(), now, now
        );
    }

    /**
     * 添加新的工作执行并禁止同 project/stage/owner 存在平行开放项。
     *
     * @param execution 新工作执行
     * @param now aggregate 更新时间
     * @return 包含新执行的 aggregate
     */
    public WorkflowRuntimeState addExecution(WorkExecution execution, Instant now) {
        Objects.requireNonNull(execution, "execution 不能为空");
        if (executions.stream().anyMatch(existing -> existing.executionId().equals(execution.executionId()))) {
            throw new IllegalStateException("executionId 已存在: " + execution.executionId());
        }
        List<WorkExecution> next = new ArrayList<>(executions);
        next.add(execution);
        return copy(next, reviewRounds, processedCommands, outboxActions, now);
    }

    /**
     * 以相同 execution ID 替换状态快照，禁止借更新创建新事实源。
     *
     * @param execution 更新后的工作执行
     * @param now aggregate 更新时间
     * @return 替换后的 aggregate
     */
    public WorkflowRuntimeState replaceExecution(WorkExecution execution, Instant now) {
        Objects.requireNonNull(execution, "execution 不能为空");
        List<WorkExecution> next = new ArrayList<>(executions);
        int index = indexOfExecution(execution.executionId());
        next.set(index, execution);
        return copy(next, reviewRounds, processedCommands, outboxActions, now);
    }

    /**
     * 添加与现有 WorkExecution 关联的新评审轮次。
     *
     * @param reviewRound 新评审轮次
     * @param now aggregate 更新时间
     * @return 包含新轮次的 aggregate
     */
    public WorkflowRuntimeState addReviewRound(ReviewRound reviewRound, Instant now) {
        Objects.requireNonNull(reviewRound, "reviewRound 不能为空");
        if (reviewRounds.stream().anyMatch(existing -> existing.reviewRoundId()
                .equals(reviewRound.reviewRoundId()))) {
            throw new IllegalStateException("reviewRoundId 已存在: " + reviewRound.reviewRoundId());
        }
        List<ReviewRound> next = new ArrayList<>(reviewRounds);
        next.add(reviewRound);
        return copy(executions, next, processedCommands, outboxActions, now);
    }

    /**
     * 以相同 review round ID 替换评审状态快照。
     *
     * @param reviewRound 更新后的评审轮次
     * @param now aggregate 更新时间
     * @return 替换后的 aggregate
     */
    public WorkflowRuntimeState replaceReviewRound(ReviewRound reviewRound, Instant now) {
        Objects.requireNonNull(reviewRound, "reviewRound 不能为空");
        List<ReviewRound> next = new ArrayList<>(reviewRounds);
        int index = indexOfReviewRound(reviewRound.reviewRoundId());
        next.set(index, reviewRound);
        return copy(executions, next, processedCommands, outboxActions, now);
    }

    /**
     * 将稳定 action ID 的投影动作加入同一原子提交。
     *
     * @param action 新投影动作
     * @param now aggregate 更新时间
     * @return 包含动作的 aggregate
     */
    public WorkflowRuntimeState enqueue(OutboxAction action, Instant now) {
        Objects.requireNonNull(action, "action 不能为空");
        Optional<OutboxAction> existing = findOutboxAction(action.actionId());
        if (existing.isPresent()) {
            if (existing.get().equals(action)) {
                return this;
            }
            throw new IllegalStateException("outbox actionId 已存在且内容不同: " + action.actionId().value());
        }
        List<OutboxAction> next = new ArrayList<>(outboxActions);
        next.add(action);
        return copy(executions, reviewRounds, processedCommands, next, now);
    }

    /**
     * 替换同一 action ID 的投影状态快照。
     *
     * @param action 更新后的动作
     * @param now aggregate 更新时间
     * @return 替换后的 aggregate
     */
    public WorkflowRuntimeState replaceOutboxAction(OutboxAction action, Instant now) {
        Objects.requireNonNull(action, "action 不能为空");
        List<OutboxAction> next = new ArrayList<>(outboxActions);
        int index = indexOfOutboxAction(action.actionId());
        next.set(index, action);
        return copy(executions, reviewRounds, processedCommands, next, now);
    }

    /**
     * 记录首次 command 结果并裁剪最旧去重记录。
     *
     * @param command 首次处理结果
     * @param now aggregate 更新时间
     * @return 包含稳定命令结果的 aggregate
     */
    public WorkflowRuntimeState recordCommand(ProcessedWorkflowCommand command, Instant now) {
        Objects.requireNonNull(command, "command 不能为空");
        Optional<ProcessedWorkflowCommand> existing = findProcessedCommand(command.commandId());
        if (existing.isPresent()) {
            if (existing.get().equals(command)) {
                return this;
            }
            throw new IllegalStateException("commandId 已存在且结果不同: " + command.commandId().value());
        }
        List<ProcessedWorkflowCommand> next = new ArrayList<>(processedCommands);
        next.add(command);
        next.sort(Comparator.comparing(ProcessedWorkflowCommand::processedAt)
                .thenComparing(item -> item.commandId().value()));
        while (next.size() > MAX_PROCESSED_COMMANDS) {
            next.remove(0);
        }
        return copy(executions, reviewRounds, next, outboxActions, now);
    }

    /**
     * 按 execution ID 查询工作执行。
     *
     * @param executionId 工作执行标识
     * @return 可选工作执行
     */
    public Optional<WorkExecution> findExecution(String executionId) {
        String requiredId = requireText(executionId, "executionId");
        return executions.stream().filter(item -> item.executionId().equals(requiredId)).findFirst();
    }

    /**
     * 按 command ID 查询首次处理结果。
     *
     * @param commandId 命令标识
     * @return 可选首次结果
     */
    public Optional<ProcessedWorkflowCommand> findProcessedCommand(WorkflowCommandId commandId) {
        Objects.requireNonNull(commandId, "commandId 不能为空");
        return processedCommands.stream().filter(item -> item.commandId().equals(commandId)).findFirst();
    }

    /**
     * 按 action ID 查询投影动作。
     *
     * @param actionId 动作标识
     * @return 可选投影动作
     */
    public Optional<OutboxAction> findOutboxAction(OutboxActionId actionId) {
        Objects.requireNonNull(actionId, "actionId 不能为空");
        return outboxActions.stream().filter(item -> item.actionId().equals(actionId)).findFirst();
    }

    /**
     * 返回当前需要重放的全部 pending outbox 动作。
     *
     * @return 创建顺序稳定的 pending 动作
     */
    public List<OutboxAction> pendingOutbox() {
        return outboxActions.stream()
                .filter(action -> action.status() == OutboxActionStatus.PENDING)
                .sorted(Comparator.comparing(OutboxAction::createdAt)
                        .thenComparing(action -> action.actionId().value()))
                .toList();
    }

    /**
     * 查找 execution 在快照中的唯一位置。
     *
     * @param executionId execution 标识
     * @return 列表索引
     */
    private int indexOfExecution(String executionId) {
        for (int index = 0; index < executions.size(); index++) {
            if (executions.get(index).executionId().equals(executionId)) {
                return index;
            }
        }
        throw new IllegalStateException("executionId 不存在: " + executionId);
    }

    /**
     * 查找 review round 在快照中的唯一位置。
     *
     * @param reviewRoundId 评审轮次标识
     * @return 列表索引
     */
    private int indexOfReviewRound(String reviewRoundId) {
        for (int index = 0; index < reviewRounds.size(); index++) {
            if (reviewRounds.get(index).reviewRoundId().equals(reviewRoundId)) {
                return index;
            }
        }
        throw new IllegalStateException("reviewRoundId 不存在: " + reviewRoundId);
    }

    /**
     * 查找 outbox action 在快照中的唯一位置。
     *
     * @param actionId 动作标识
     * @return 列表索引
     */
    private int indexOfOutboxAction(OutboxActionId actionId) {
        for (int index = 0; index < outboxActions.size(); index++) {
            if (outboxActions.get(index).actionId().equals(actionId)) {
                return index;
            }
        }
        throw new IllegalStateException("outbox actionId 不存在: " + actionId.value());
    }

    /**
     * 创建保持项目配置不变的下一 aggregate 快照。
     *
     * @param nextExecutions 下一执行集合
     * @param nextReviewRounds 下一评审集合
     * @param nextCommands 下一命令窗口
     * @param nextOutbox 下一 outbox 集合
     * @param now aggregate 更新时间
     * @return 不可变下一快照
     */
    private WorkflowRuntimeState copy(
            List<WorkExecution> nextExecutions,
            List<ReviewRound> nextReviewRounds,
            List<ProcessedWorkflowCommand> nextCommands,
            List<OutboxAction> nextOutbox,
            Instant now
    ) {
        Instant requiredNow = Objects.requireNonNull(now, "操作时间不能为空");
        if (requiredNow.isBefore(updatedAt)) {
            throw new IllegalArgumentException("aggregate updatedAt 不能回退");
        }
        return new WorkflowRuntimeState(
                schemaVersion, projectId, profile, reviewPolicy,
                nextExecutions, nextReviewRounds, nextCommands, nextOutbox,
                createdAt, requiredNow
        );
    }

    /**
     * 校验 execution 的项目、档位、标识和开放唯一性。
     *
     * @param projectId aggregate 项目标识
     * @param profile aggregate 固定档位
     * @param executions 工作执行集合
     */
    private static void validateExecutions(
            String projectId,
            WorkflowProfile profile,
            List<WorkExecution> executions
    ) {
        Set<String> ids = new HashSet<>();
        Set<String> openKeys = new HashSet<>();
        for (WorkExecution execution : executions) {
            if (!ids.add(execution.executionId())) {
                throw new IllegalArgumentException("重复 executionId: " + execution.executionId());
            }
            if (!projectId.equals(execution.projectId()) || profile != execution.profile()) {
                throw new IllegalArgumentException("WorkExecution 与 aggregate 项目或 profile 不一致");
            }
            if (!execution.status().terminal()) {
                String key = execution.stage() + "|" + execution.owner();
                if (!openKeys.add(key)) {
                    throw new IllegalArgumentException("同 stage/owner 存在平行开放 WorkExecution: " + key);
                }
            }
        }
    }

    /**
     * 校验评审轮次引用现有同项目 WorkExecution 且标识唯一。
     *
     * @param projectId aggregate 项目标识
     * @param executions 工作执行集合
     * @param reviewRounds 评审轮次集合
     */
    private static void validateReviewRounds(
            String projectId,
            List<WorkExecution> executions,
            List<ReviewRound> reviewRounds
    ) {
        Set<String> executionIds = executions.stream()
                .map(WorkExecution::executionId)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> reviewIds = new HashSet<>();
        Set<String> openExecutionIds = new HashSet<>();
        for (ReviewRound review : reviewRounds) {
            if (!reviewIds.add(review.reviewRoundId())) {
                throw new IllegalArgumentException("重复 reviewRoundId: " + review.reviewRoundId());
            }
            if (!projectId.equals(review.projectId()) || !executionIds.contains(review.workExecutionId())) {
                throw new IllegalArgumentException("ReviewRound 必须引用同项目现有 WorkExecution");
            }
            if (!review.status().terminal() && !openExecutionIds.add(review.workExecutionId())) {
                throw new IllegalArgumentException("同一 WorkExecution 只能有一个开放 ReviewRound");
            }
        }
    }

    /**
     * 校验 command 与 outbox action 标识分别唯一。
     *
     * @param commands 已处理命令
     * @param actions outbox 动作
     */
    private static void validateUniqueIds(
            List<ProcessedWorkflowCommand> commands,
            List<OutboxAction> actions
    ) {
        Set<WorkflowCommandId> commandIds = new HashSet<>();
        for (ProcessedWorkflowCommand command : commands) {
            if (!commandIds.add(command.commandId())) {
                throw new IllegalArgumentException("重复 commandId: " + command.commandId().value());
            }
        }
        Set<OutboxActionId> actionIds = new HashSet<>();
        for (OutboxAction action : actions) {
            if (!actionIds.add(action.actionId())) {
                throw new IllegalArgumentException("重复 outbox actionId: " + action.actionId().value());
            }
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
