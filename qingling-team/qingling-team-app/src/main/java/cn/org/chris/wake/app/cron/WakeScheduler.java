package cn.org.chris.wake.app.cron;

import cn.org.chris.wake.domain.gateway.CronJobRepository;
import cn.org.chris.wake.domain.mailbox.MailboxService;
import cn.org.chris.wake.domain.model.CronJob;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 编排一次性角色唤醒与四角色错峰 heartbeat 注册。
 */
public final class WakeScheduler {

    /** 默认邮件唤醒延迟。 */
    public static final Duration DEFAULT_WAKE_DELAY = Duration.ofSeconds(1);

    /** 默认 heartbeat 周期。 */
    public static final Duration DEFAULT_HEARTBEAT_INTERVAL = Duration.ofSeconds(30);

    /** 四角色首次 heartbeat 的错峰秒数。 */
    public static final List<Long> DEFAULT_HEARTBEAT_STAGGER_SECONDS = List.of(0L, 7L, 14L, 21L);

    /** 与 Python 启动顺序一致的 heartbeat 角色。 */
    private static final List<String> HEARTBEAT_ROLES = List.of("manager", "pm", "rd", "qa");

    /** Cron 持久化端口。 */
    private final CronJobRepository repository;

    /** 可替换任务标识生成器。 */
    private final Supplier<String> jobIdSupplier;

    /** 可测试时钟。 */
    private final Clock clock;

    /**
     * 创建使用随机任务标识和系统 UTC 时钟的调度器。
     *
     * @param repository Cron 持久化端口
     */
    public WakeScheduler(CronJobRepository repository) {
        this(repository, WakeScheduler::randomJobId, Clock.systemUTC());
    }

    /**
     * 创建可注入标识与时钟的调度器。
     *
     * @param repository Cron 持久化端口
     * @param jobIdSupplier 任务标识生成器
     * @param clock 可测试时钟
     */
    public WakeScheduler(CronJobRepository repository, Supplier<String> jobIdSupplier, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
        this.jobIdSupplier = Objects.requireNonNull(jobIdSupplier, "jobIdSupplier 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    /**
     * 使用默认一秒延迟注册 new_mail 唤醒。
     *
     * @param role 目标团队角色
     * @param projectId 项目标识，可为空
     * @return 新建或复用的任务标识
     */
    public String scheduleMailWake(String role, String projectId) {
        return scheduleWake(role, "new_mail", projectId, DEFAULT_WAKE_DELAY);
    }

    /**
     * 原子注册或复用相同 role、project、reason 的未到期唤醒。
     *
     * @param role 目标团队角色
     * @param reason 唤醒原因
     * @param projectId 项目标识，可为空
     * @param delay 触发延迟
     * @return 新建或复用的任务标识
     */
    public String scheduleWake(String role, String reason, String projectId, Duration delay) {
        validateRole(role);
        String checkedReason = requireText(reason, "reason");
        Objects.requireNonNull(delay, "delay 不能为空");
        if (delay.isNegative()) {
            throw new IllegalArgumentException("delay 不得为负数");
        }
        long nowMs = clock.millis();
        long triggerAtMs = Math.addExact(nowMs, delay.toMillis());
        String projectTag = projectId == null || projectId.isBlank() ? "" : ":" + projectId;
        CronJob candidate = new CronJob(
                jobIdSupplier.get(),
                "wake-" + role + "-" + checkedReason + "-" + nowMs,
                true,
                new CronJob.Schedule(CronJob.ScheduleKind.AT, triggerAtMs, null, null, null),
                new CronJob.Payload("team:" + role, "__wake__:" + checkedReason + projectTag),
                new CronJob.State(null, null, null, null),
                nowMs,
                nowMs,
                true
        );
        return repository.saveWakeIfAbsent(candidate, nowMs).id();
    }

    /**
     * 注册或替换一个固定周期 heartbeat。
     *
     * @param role 目标团队角色
     * @param interval 重复周期
     * @param firstDelay 首次触发延迟
     * @return heartbeat 固定任务标识
     */
    public String scheduleHeartbeat(String role, Duration interval, Duration firstDelay) {
        validateRole(role);
        Objects.requireNonNull(interval, "interval 不能为空");
        Objects.requireNonNull(firstDelay, "firstDelay 不能为空");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval 必须为正数");
        }
        if (firstDelay.isNegative()) {
            throw new IllegalArgumentException("firstDelay 不得为负数");
        }
        long nowMs = clock.millis();
        String jobId = "heartbeat-" + role;
        CronJob heartbeat = new CronJob(
                jobId,
                jobId,
                true,
                new CronJob.Schedule(CronJob.ScheduleKind.EVERY, null, interval.toMillis(), null, null),
                new CronJob.Payload("team:" + role, "__wake__:heartbeat"),
                new CronJob.State(Math.addExact(nowMs, firstDelay.toMillis()), null, null, null),
                nowMs,
                nowMs,
                false
        );
        repository.upsert(heartbeat);
        return jobId;
    }

    /**
     * 以 manager、pm、rd、qa 和 0/7/14/21 秒错峰注册默认 heartbeat。
     */
    public void registerDefaultHeartbeats() {
        for (int index = 0; index < HEARTBEAT_ROLES.size(); index++) {
            scheduleHeartbeat(
                    HEARTBEAT_ROLES.get(index),
                    DEFAULT_HEARTBEAT_INTERVAL,
                    Duration.ofSeconds(DEFAULT_HEARTBEAT_STAGGER_SECONDS.get(index))
            );
        }
    }

    /**
     * 校验目标角色属于四角色团队。
     *
     * @param role 待校验角色
     */
    private static void validateRole(String role) {
        if (!MailboxService.TEAM_ROLES.contains(role)) {
            throw new IllegalArgumentException("未知团队角色: " + role);
        }
    }

    /**
     * 校验必需文本。
     *
     * @param value 原始文本
     * @param field 字段名
     * @return 已校验文本
     */
    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }

    /**
     * 生成与 Python tasks_store 一致的 job-xxxxxxxx 标识。
     *
     * @return 随机任务标识
     */
    private static String randomJobId() {
        return "job-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
