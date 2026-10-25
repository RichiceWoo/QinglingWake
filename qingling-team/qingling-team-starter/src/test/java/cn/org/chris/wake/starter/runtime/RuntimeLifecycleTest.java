package cn.org.chris.wake.starter.runtime;

import cn.org.chris.wake.domain.workflow.WorkflowCoordinationMode;
import cn.org.chris.wake.domain.workflow.WorkflowProfile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证运行时停止先关闭生产者，再排空或取消消费者，最后关闭 Agent/MCP。
 */
class RuntimeLifecycleTest {

    /** coordinated SIMPLE 只排除 PM，STANDARD/STRICT 与 legacy 仍保留四角色 heartbeat。 */
    @Test
    void shouldExcludePmHeartbeatOnlyForCoordinatedSimpleProfile() {
        assertThat(RuntimeLifecycle.heartbeatEnabled(
                WorkflowCoordinationMode.COORDINATED, WorkflowProfile.SIMPLE, "pm"
        )).isFalse();
        assertThat(RuntimeLifecycle.heartbeatEnabled(
                WorkflowCoordinationMode.COORDINATED, WorkflowProfile.SIMPLE, "rd"
        )).isTrue();
        assertThat(RuntimeLifecycle.heartbeatEnabled(
                WorkflowCoordinationMode.COORDINATED, WorkflowProfile.STANDARD, "pm"
        )).isTrue();
        assertThat(RuntimeLifecycle.heartbeatEnabled(
                WorkflowCoordinationMode.LEGACY, WorkflowProfile.SIMPLE, "pm"
        )).isTrue();
    }

    /** 排空成功时不得执行取消，Agent 必须最后关闭。 */
    @Test
    void shouldStopProducersBeforeSuccessfulDrain() {
        List<String> order = new ArrayList<>();

        RuntimeLifecycle.shutdownInOrder(
                () -> order.add("inbound"),
                () -> order.add("review-watchdog"),
                () -> order.add("watchdog"),
                () -> order.add("wake"),
                () -> order.add("cron"),
                () -> order.add("admission"),
                () -> {
                    order.add("drain");
                    return true;
                },
                () -> order.add("cancel"),
                () -> order.add("workflow"),
                () -> order.add("agents")
        );

        assertThat(order).containsExactly(
                "inbound", "review-watchdog", "watchdog", "wake", "cron", "admission",
                "drain", "workflow", "agents"
        );
    }

    /** 排空超时时必须先取消任务，再关闭 Agent/MCP。 */
    @Test
    void shouldCancelOutstandingTasksBeforeClosingAgentsWhenDrainTimesOut() {
        List<String> order = new ArrayList<>();

        RuntimeLifecycle.shutdownInOrder(
                () -> order.add("inbound"),
                () -> order.add("review-watchdog"),
                () -> order.add("watchdog"),
                () -> order.add("wake"),
                () -> order.add("cron"),
                () -> order.add("admission"),
                () -> {
                    order.add("drain");
                    return false;
                },
                () -> order.add("cancel"),
                () -> order.add("workflow"),
                () -> order.add("agents")
        );

        assertThat(order).containsExactly(
                "inbound", "review-watchdog", "watchdog", "wake", "cron", "admission",
                "drain", "cancel", "workflow", "agents"
        );
    }
}
