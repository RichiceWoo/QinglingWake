package cn.org.chris.wake.domain.model;

import java.time.ZoneId;
import java.util.Objects;

/**
 * 表示 tasks.json 中可由 at、every 或 cron 规则触发的任务。
 *
 * @param id 任务唯一标识
 * @param name 人类可读名称
 * @param enabled 是否启用
 * @param schedule 调度规则
 * @param payload 触发后注入的消息
 * @param state 最近运行状态
 * @param createdAtMs 创建时间
 * @param updatedAtMs 更新时间
 * @param deleteAfterRun 执行成功后是否删除
 */
public record CronJob(
        /** 定时任务唯一标识。 */ String id,
        /** 定时任务展示名称。 */ String name,
        /** 是否参与调度。 */ boolean enabled,
        /** 三选一调度规则。 */ Schedule schedule,
        /** 触发后投递的消息。 */ Payload payload,
        /** 最近一次调度状态。 */ State state,
        /** 创建时间，单位毫秒。 */ long createdAtMs,
        /** 更新时间，单位毫秒。 */ long updatedAtMs,
        /** 是否在成功执行后删除。 */ boolean deleteAfterRun
) {
    /**
     * 校验任务的必需字段。
     */
    public CronJob {
        Objects.requireNonNull(id, "id 不能为空");
        Objects.requireNonNull(name, "name 不能为空");
        Objects.requireNonNull(schedule, "schedule 不能为空");
        Objects.requireNonNull(payload, "payload 不能为空");
        state = state == null ? new State(null, null, null, null) : state;
    }

    /**
     * 定义调度规则类型。
     */
    public enum ScheduleKind {
        /** 在固定毫秒时间戳触发一次。 */
        AT,
        /** 按固定毫秒间隔重复触发。 */
        EVERY,
        /** 按 cron 表达式和时区重复触发。 */
        CRON
    }

    /**
     * 描述三选一调度配置。
     *
     * @param kind 调度类型
     * @param atMs 单次触发时间
     * @param everyMs 重复间隔
     * @param expression cron 表达式
     * @param timezone cron 时区
     */
    public record Schedule(
            /** 调度规则类型。 */ ScheduleKind kind,
            /** AT 规则使用的毫秒时间戳。 */ Long atMs,
            /** EVERY 规则使用的毫秒间隔。 */ Long everyMs,
            /** CRON 规则使用的表达式。 */ String expression,
            /** CRON 规则使用的时区。 */ String timezone
    ) {
        /**
         * 根据 kind 校验且只接受对应规则的必需参数。
         */
        public Schedule {
            Objects.requireNonNull(kind, "kind 不能为空");
            switch (kind) {
                case AT -> requirePositive(atMs, "atMs");
                case EVERY -> requirePositive(everyMs, "everyMs");
                case CRON -> {
                    if (expression == null || expression.isBlank()) {
                        throw new IllegalArgumentException("cron expression 不能为空");
                    }
                    ZoneId.of(Objects.requireNonNull(timezone, "timezone 不能为空"));
                }
            }
        }

        /**
         * 校验毫秒时间值必须为正数。
         *
         * @param value 待校验的毫秒值
         * @param name 字段名称
         */
        private static void requirePositive(Long value, String name) {
            if (value == null || value <= 0) {
                throw new IllegalArgumentException(name + " 必须为正数");
            }
        }
    }

    /**
     * 描述触发后投递给 Runner 的消息。
     *
     * @param routingKey 目标路由键
     * @param message 注入消息正文
     */
    public record Payload(
            /** 目标 routing key。 */ String routingKey,
            /** 触发时注入的文本。 */ String message
    ) {
        /**
         * 校验目标路由与消息正文。
         */
        public Payload {
            Objects.requireNonNull(routingKey, "routingKey 不能为空");
            Objects.requireNonNull(message, "message 不能为空");
        }
    }

    /**
     * 保存最近一次运行结果及下一次计划时间。
     *
     * @param nextRunAtMs 下一次运行时间
     * @param lastRunAtMs 上一次运行时间
     * @param lastStatus 上一次运行状态
     * @param lastError 上一次失败摘要
     */
    public record State(
            /** 下一次计划运行时间。 */ Long nextRunAtMs,
            /** 上一次实际运行时间。 */ Long lastRunAtMs,
            /** ok 或 error 状态。 */ String lastStatus,
            /** 脱敏后的最近错误摘要。 */ String lastError
    ) {
    }
}
