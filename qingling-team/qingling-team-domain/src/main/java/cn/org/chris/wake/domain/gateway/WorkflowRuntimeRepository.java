package cn.org.chris.wake.domain.gateway;

import cn.org.chris.wake.domain.workflow.ProcessedWorkflowCommand;
import cn.org.chris.wake.domain.workflow.WorkflowCommandId;
import cn.org.chris.wake.domain.workflow.WorkflowRuntimeState;

import java.util.Optional;

/**
 * 定义 runtime v2 aggregate 的显式创建、原子命令和锁内更新端口。
 */
public interface WorkflowRuntimeRepository {

    /**
     * 只读查询 runtime v2；缺失时不得隐式创建目录或半成品文件。
     *
     * @param projectId 项目标识
     * @return 可选 aggregate
     */
    Optional<WorkflowRuntimeState> find(String projectId);

    /**
     * 显式创建新项目 runtime v2；已存在时必须失败。
     *
     * @param initial 初始 aggregate
     * @return 已持久化 aggregate
     */
    WorkflowRuntimeState create(WorkflowRuntimeState initial);

    /**
     * 在项目级锁内执行一次带 command ID 的原子 read-modify-write。
     *
     * @param projectId 项目标识
     * @param commandId 稳定命令标识
     * @param command 首次执行时调用的纯状态变换
     * @return 首次或重复命令的稳定回执
     */
    WorkflowCommandReceipt execute(
            String projectId,
            WorkflowCommandId commandId,
            WorkflowCommand command
    );

    /**
     * 在项目级锁内执行不产生 processed command 的内部状态更新。
     *
     * @param projectId 项目标识
     * @param update 内部状态变换
     * @return 已持久化新 aggregate
     */
    WorkflowRuntimeState update(String projectId, WorkflowRuntimeUpdate update);

    /**
     * 表示首次 command 执行后需要原子保存的状态与稳定结果引用。
     *
     * @param state command 产生的新 aggregate
     * @param outcomeCode 有限结果代码
     * @param resultReference 有界结果引用
     */
    record WorkflowCommandMutation(
            /** command 产生的新 aggregate。 */ WorkflowRuntimeState state,
            /** 不含自由文本或凭据的结果代码。 */ String outcomeCode,
            /** 可用于重复响应的有界结果引用。 */ String resultReference
    ) {
    }

    /**
     * 返回 command 的首次处理记录、当前 aggregate 与是否首次执行。
     *
     * @param command 首次处理记录
     * @param state 当前 aggregate
     * @param newlyProcessed 本次是否真正执行状态变换
     */
    record WorkflowCommandReceipt(
            /** 首次处理记录。 */ ProcessedWorkflowCommand command,
            /** 返回时的当前 aggregate。 */ WorkflowRuntimeState state,
            /** 本次是否首次执行状态变换。 */ boolean newlyProcessed
    ) {
    }

    /**
     * 定义带稳定业务结果的纯 aggregate 变换。
     */
    @FunctionalInterface
    interface WorkflowCommand {

        /**
         * 根据锁内最新 aggregate 计算新状态和稳定结果引用。
         *
         * @param current 锁内最新 aggregate
         * @return 待原子提交的 command 变更
         */
        WorkflowCommandMutation apply(WorkflowRuntimeState current);
    }

    /**
     * 定义不产生 processed command 的内部纯 aggregate 变换。
     */
    @FunctionalInterface
    interface WorkflowRuntimeUpdate {

        /**
         * 根据锁内最新 aggregate 计算下一状态。
         *
         * @param current 锁内最新 aggregate
         * @return 待原子提交的新 aggregate
         */
        WorkflowRuntimeState apply(WorkflowRuntimeState current);
    }
}
