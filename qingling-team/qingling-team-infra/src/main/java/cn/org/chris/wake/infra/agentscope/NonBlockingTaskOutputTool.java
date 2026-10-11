package cn.org.chris.wake.infra.agentscope;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.tool.TaskTool;

import java.util.Objects;

/**
 * 将 AgentScope 内置 task_output 收敛为纯状态查询，避免模型参数触发固定时长阻塞。
 */
final class NonBlockingTaskOutputTool {

    /** 复用 AgentScope 原生任务查找、结果格式化和终态投递标记能力。 */
    private final TaskTool delegate;

    /**
     * 为指定任务仓库创建无阻塞输出工具。
     *
     * @param taskRepository 当前 HarnessAgent 的任务仓库
     */
    NonBlockingTaskOutputTool(TaskRepository taskRepository) {
        this.delegate = new TaskTool(Objects.requireNonNull(taskRepository, "taskRepository 不能为空"));
    }

    /**
     * 立即返回任务当前快照；保留旧参数仅为兼容模型已生成的调用，不执行任何等待。
     *
     * @param runtimeContext 当前父 Agent 运行上下文
     * @param taskId 待查询任务标识
     * @param ignoredBlock 兼容参数，运行时始终按 false 处理
     * @param ignoredTimeout 兼容参数，运行时不会使用该等待时长
     * @return AgentScope 原生格式的任务状态或结果
     */
    @Tool(
            name = "task_output",
            description = "立即查询后台子任务的当前状态或结果，始终非阻塞。"
                    + " block 和 timeout 仅为兼容参数，不会触发等待；"
                    + "任务仍在运行时应继续其他工作，稍后再次查询。",
            readOnly = true
    )
    public String taskOutput(
            RuntimeContext runtimeContext,
            @ToolParam(name = "task_id", description = "agent_spawn 或 agent_send 返回的 task_id") String taskId,
            @ToolParam(
                    name = "block",
                    required = false,
                    description = "兼容参数；即使为 true 也只执行非阻塞查询"
            ) Boolean ignoredBlock,
            @ToolParam(
                    name = "timeout",
                    required = false,
                    description = "兼容参数；不会用于等待任务完成"
            ) Long ignoredTimeout
    ) {
        return delegate.taskOutput(runtimeContext, taskId, false, 0L);
    }
}
