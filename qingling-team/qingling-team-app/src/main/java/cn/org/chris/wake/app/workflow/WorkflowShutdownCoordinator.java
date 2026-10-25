package cn.org.chris.wake.app.workflow;

import cn.org.chris.wake.domain.gateway.WorkflowRuntimeRepository;
import cn.org.chris.wake.domain.gateway.WorkspaceRepository;
import cn.org.chris.wake.domain.workflow.ReviewRound;
import cn.org.chris.wake.domain.workflow.WorkExecution;
import cn.org.chris.wake.domain.workflow.WorkflowRuntimeState;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * 在运行时关闭末段把 runtime v2 非终态执行与评审持久化为明确取消终态。
 */
public final class WorkflowShutdownCoordinator {

    /** shutdown 使用的有界失败类型。 */
    public static final String SHUTDOWN_REASON = "RUNTIME_SHUTDOWN";

    /** runtime v2 权威仓储。 */
    private final WorkflowRuntimeRepository runtimeRepository;

    /** 可枚举已初始化项目的工作区端口。 */
    private final WorkspaceRepository workspaceRepository;

    /** 终态更新时间来源。 */
    private final Clock clock;

    /**
     * 使用系统 UTC 时钟创建 shutdown 协调器。
     *
     * @param runtimeRepository runtime v2 仓储
     * @param workspaceRepository 工作区端口
     */
    public WorkflowShutdownCoordinator(
            WorkflowRuntimeRepository runtimeRepository,
            WorkspaceRepository workspaceRepository
    ) {
        this(runtimeRepository, workspaceRepository, Clock.systemUTC());
    }

    /**
     * 创建可注入时钟的 shutdown 协调器。
     *
     * @param runtimeRepository runtime v2 仓储
     * @param workspaceRepository 工作区端口
     * @param clock UTC 时钟
     */
    public WorkflowShutdownCoordinator(
            WorkflowRuntimeRepository runtimeRepository,
            WorkspaceRepository workspaceRepository,
            Clock clock
    ) {
        this.runtimeRepository = Objects.requireNonNull(runtimeRepository, "runtimeRepository 不能为空");
        this.workspaceRepository = Objects.requireNonNull(workspaceRepository, "workspaceRepository 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    /**
     * 取消全部 v2 项目的非终态执行及其开放评审，legacy 项目保持不变。
     *
     * @return 实际形成终态的 WorkExecution 数量
     */
    public int cancelNonTerminal() {
        int cancelled = 0;
        for (String projectId : workspaceRepository.listProjectIds()) {
            if (runtimeRepository.find(projectId).isEmpty()) {
                continue;
            }
            int[] projectCancelled = {0};
            runtimeRepository.update(projectId, state -> {
                WorkflowRuntimeState changed = state;
                Instant clockNow = Instant.now(clock);
                Instant now = clockNow.isBefore(state.updatedAt()) ? state.updatedAt() : clockNow;
                for (WorkExecution execution : state.executions()) {
                    if (!execution.status().terminal()) {
                        changed = changed.replaceExecution(execution.cancel(SHUTDOWN_REASON, now), now);
                        projectCancelled[0]++;
                    }
                }
                for (ReviewRound review : state.reviewRounds()) {
                    if (!review.status().terminal()) {
                        changed = changed.replaceReviewRound(review.cancel(SHUTDOWN_REASON, now), now);
                    }
                }
                return changed;
            });
            cancelled += projectCancelled[0];
        }
        return cancelled;
    }
}
