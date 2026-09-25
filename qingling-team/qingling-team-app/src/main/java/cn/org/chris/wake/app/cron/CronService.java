package cn.org.chris.wake.app.cron;

import cn.org.chris.wake.domain.gateway.CronDispatchGateway;
import cn.org.chris.wake.domain.gateway.CronJobRepository;
import cn.org.chris.wake.domain.model.CronJob;
import cn.org.chris.wake.domain.model.InboundMessage;
import com.cronutils.model.Cron;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static com.cronutils.model.CronType.UNIX;

/**
 * 负责 tasks.json 热重载、到期判断、消息投递和执行后状态推进。
 */
public final class CronService implements AutoCloseable {

    /** UNIX 五段 cron 表达式解析器。 */
    private static final CronParser CRON_PARSER = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(UNIX));

    /** 任务存储端口。 */
    private final CronJobRepository repository;

    /** Runner 消息投递端口。 */
    private final CronDispatchGateway dispatchGateway;

    /** cron 消息标识生成器。 */
    private final Supplier<String> messageIdSupplier;

    /** 可测试时钟。 */
    private final Clock clock;

    /** 当前内存任务快照，访问受实例锁保护。 */
    private List<CronJob> jobs = new ArrayList<>();

    /** 最近加载的文件组合版本。 */
    private CronJobRepository.Revision loadedRevision = CronJobRepository.Revision.missing();

    /** 可选后台 tick 执行器。 */
    private ScheduledExecutorService executor;

    /**
     * 创建使用随机消息标识和系统 UTC 时钟的 Cron 服务。
     *
     * @param repository 任务存储端口
     * @param dispatchGateway Runner 投递端口
     */
    public CronService(CronJobRepository repository, CronDispatchGateway dispatchGateway) {
        this(repository, dispatchGateway, CronService::randomMessageId, Clock.systemUTC());
    }

    /**
     * 创建可注入标识与时钟的 Cron 服务。
     *
     * @param repository 任务存储端口
     * @param dispatchGateway Runner 投递端口
     * @param messageIdSupplier cron 消息标识生成器
     * @param clock 可测试时钟
     */
    public CronService(
            CronJobRepository repository,
            CronDispatchGateway dispatchGateway,
            Supplier<String> messageIdSupplier,
            Clock clock
    ) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
        this.dispatchGateway = Objects.requireNonNull(dispatchGateway, "dispatchGateway 不能为空");
        this.messageIdSupplier = Objects.requireNonNull(messageIdSupplier, "messageIdSupplier 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    /**
     * 加载任务并启动固定延迟后台 tick；重复启动保持幂等。
     *
     * @param tickInterval tick 间隔
     */
    public synchronized void start(Duration tickInterval) {
        Objects.requireNonNull(tickInterval, "tickInterval 不能为空");
        if (tickInterval.isZero() || tickInterval.isNegative() || tickInterval.toMillis() == 0L) {
            throw new IllegalArgumentException("tickInterval 必须至少为一毫秒");
        }
        if (executor != null) {
            return;
        }
        reload(clock.millis());
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "qingling-cron");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::safeTick, 0L, tickInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * 执行一次可测试 tick：必要时热重载，并同步等待全部到期任务投递结束。
     */
    public synchronized void tick() {
        tick(clock.millis());
    }

    /**
     * 使用显式时间执行一次 tick，便于边界测试。
     *
     * @param nowMs 当前毫秒时间
     */
    public synchronized void tick(long nowMs) {
        if (!repository.revision().equals(loadedRevision)) {
            reload(nowMs);
        }
        List<CronJob> updated = new ArrayList<>(jobs.size());
        boolean fired = false;
        Set<String> routesWithBusinessWake = jobs.stream()
                .filter(job -> isDue(job, nowMs))
                .filter(job -> !isHeartbeat(job))
                .map(job -> job.payload().routingKey())
                .collect(Collectors.toUnmodifiableSet());
        for (CronJob job : jobs) {
            if (!isDue(job, nowMs)) {
                updated.add(job);
                continue;
            }
            fired = true;
            boolean shouldDispatch = !isHeartbeat(job) || !routesWithBusinessWake.contains(job.payload().routingKey());
            CronJob afterFire = fire(job, nowMs, shouldDispatch);
            if (afterFire != null) {
                updated.add(afterFire);
            }
        }
        if (fired) {
            repository.replaceAll(updated);
            jobs = updated;
            loadedRevision = repository.revision();
        }
    }

    /**
     * 返回当前内存任务快照。
     *
     * @return 不可变任务列表
     */
    public synchronized List<CronJob> currentJobs() {
        return List.copyOf(jobs);
    }

    /**
     * 停止后台 tick；重复停止保持幂等。
     */
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    /**
     * 关闭服务并停止后台 tick。
     */
    @Override
    public void close() {
        stop();
    }

    /**
     * 保护后台调度线程，避免单次异常终止后续 tick。
     */
    private void safeTick() {
        try {
            tick();
        } catch (RuntimeException ignored) {
            // 单个损坏文件或运行异常留待下一 tick 重试，后台线程不能退出。
        }
    }

    /**
     * 从 Repository 重载任务并补齐首次 next_run_at_ms。
     *
     * @param nowMs 重载时间
     */
    private void reload(long nowMs) {
        List<CronJob> loaded = repository.findAll();
        List<CronJob> initialized = new ArrayList<>(loaded.size());
        for (CronJob job : loaded) {
            initialized.add(initializeNextRun(job, nowMs));
        }
        jobs = initialized;
        loadedRevision = repository.revision();
    }

    /**
     * 按 Python 规则补全 next run，cron 每次热重载均重新计算。
     *
     * @param job 原任务
     * @param nowMs 当前时间
     * @return 初始化后的任务
     */
    private CronJob initializeNextRun(CronJob job, long nowMs) {
        if (!job.enabled()) {
            return job;
        }
        Long nextRunAtMs = job.state().nextRunAtMs();
        if (job.schedule().kind() == CronJob.ScheduleKind.CRON) {
            nextRunAtMs = nextCronMs(job.schedule(), nowMs);
        } else if (nextRunAtMs == null && job.schedule().kind() == CronJob.ScheduleKind.AT) {
            nextRunAtMs = job.schedule().atMs();
        } else if (nextRunAtMs == null && job.schedule().kind() == CronJob.ScheduleKind.EVERY) {
            nextRunAtMs = Math.addExact(nowMs, job.schedule().everyMs());
        }
        return withState(job, new CronJob.State(
                nextRunAtMs,
                job.state().lastRunAtMs(),
                job.state().lastStatus(),
                job.state().lastError()
        ), job.enabled());
    }

    /**
     * 投递任务并按类型计算执行后状态；一次性自删任务返回 null。
     *
     * @param job 到期任务
     * @param firedAtMs 本轮统一触发时间
     * @param shouldDispatch 是否实际投递；同角色业务 wake 到期时 heartbeat 仅推进状态
     * @return 保留后的任务，删除时为 null
     */
    private CronJob fire(CronJob job, long firedAtMs, boolean shouldDispatch) {
        String messageId = messageIdSupplier.get();
        InboundMessage inbound = new InboundMessage(
                job.payload().routingKey(),
                job.payload().message(),
                messageId,
                messageId,
                "cron",
                firedAtMs,
                true,
                null,
                Map.of()
        );
        String status = "ok";
        String error = null;
        if (shouldDispatch) {
            try {
                dispatchGateway.dispatch(inbound).join();
            } catch (CompletionException exception) {
                status = "error";
                Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                error = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            } catch (RuntimeException exception) {
                status = "error";
                error = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            }
        }
        CronJob.State state = new CronJob.State(null, firedAtMs, status, error);
        return switch (job.schedule().kind()) {
            case AT -> job.deleteAfterRun() ? null : withState(job, state, false);
            case EVERY -> withState(job, new CronJob.State(
                    Math.addExact(firedAtMs, job.schedule().everyMs()), firedAtMs, status, error
            ), true);
            case CRON -> withState(job, new CronJob.State(
                    nextCronMs(job.schedule(), firedAtMs), firedAtMs, status, error
            ), true);
        };
    }

    /**
     * 判断任务在本轮是否启用且已经到期。
     *
     * @param job 待判断任务
     * @param nowMs 当前时间
     * @return 是否到期
     */
    private static boolean isDue(CronJob job, long nowMs) {
        return job.enabled() && job.state().nextRunAtMs() != null && job.state().nextRunAtMs() <= nowMs;
    }

    /**
     * 判断任务是否为标准 heartbeat 唤醒。
     *
     * @param job 待判断任务
     * @return 是否为 heartbeat
     */
    private static boolean isHeartbeat(CronJob job) {
        return "__wake__:heartbeat".equals(job.payload().message());
    }

    /**
     * 使用 cron-utils 按 UNIX 五段表达式和指定时区计算严格晚于基准的下次时间。
     *
     * @param schedule cron 调度规则
     * @param baseMs 基准毫秒时间
     * @return 下次触发毫秒时间
     */
    private static long nextCronMs(CronJob.Schedule schedule, long baseMs) {
        Cron cron = CRON_PARSER.parse(schedule.expression());
        cron.validate();
        ZoneId zoneId = ZoneId.of(schedule.timezone());
        ZonedDateTime base = ZonedDateTime.ofInstant(Instant.ofEpochMilli(baseMs), zoneId);
        Optional<ZonedDateTime> next = ExecutionTime.forCron(cron).nextExecution(base);
        return next.orElseThrow(() -> new IllegalArgumentException("cron 表达式没有下次执行时间"))
                .toInstant()
                .toEpochMilli();
    }

    /**
     * 复制任务并替换运行状态与启用标记。
     *
     * @param job 原任务
     * @param state 新状态
     * @param enabled 新启用标记
     * @return 新任务值对象
     */
    private static CronJob withState(CronJob job, CronJob.State state, boolean enabled) {
        return new CronJob(
                job.id(), job.name(), enabled, job.schedule(), job.payload(), state,
                job.createdAtMs(), job.updatedAtMs(), job.deleteAfterRun()
        );
    }

    /**
     * 生成与 Python CronService 一致的 cron_ 前缀消息标识。
     *
     * @return cron 消息标识
     */
    private static String randomMessageId() {
        return "cron_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
