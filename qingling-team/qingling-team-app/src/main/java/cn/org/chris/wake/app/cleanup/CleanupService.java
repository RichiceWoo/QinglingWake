package cn.org.chris.wake.app.cleanup;

import cn.org.chris.wake.domain.gateway.CleanupExecutor;
import cn.org.chris.wake.domain.model.CleanupPolicy;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * 编排启动清理、手工清理与安全凭证初始化。
 */
public final class CleanupService {

    /** 文件系统清理执行端口。 */
    private final CleanupExecutor executor;

    /** 当前清理策略。 */
    private final CleanupPolicy policy;

    /** 可测试时钟。 */
    private final Clock clock;

    /** 串行化同一服务实例的清理扫描。 */
    private final Object sweepMonitor = new Object();

    /**
     * 创建使用默认策略和系统 UTC 时钟的服务。
     *
     * @param executor 文件系统执行端口
     */
    public CleanupService(CleanupExecutor executor) {
        this(executor, CleanupPolicy.defaults(), Clock.systemUTC());
    }

    /**
     * 创建可注入策略和时钟的服务。
     *
     * @param executor 文件系统执行端口
     * @param policy 清理策略
     * @param clock 可测试时钟
     */
    public CleanupService(CleanupExecutor executor, CleanupPolicy policy, Clock clock) {
        this.executor = Objects.requireNonNull(executor, "executor 不能为空");
        this.policy = Objects.requireNonNull(policy, "policy 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    /**
     * 在线程池中串行执行完整清理，避免阻塞调用线程。
     *
     * @return 异步规则统计
     */
    public CompletableFuture<Map<String, Integer>> sweep() {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (sweepMonitor) {
                return executor.sweep(policy, clock.instant());
            }
        });
    }

    /**
     * 同步执行一次启动清理。
     *
     * @return 规则统计
     */
    public Map<String, Integer> sweepOnStartup() {
        synchronized (sweepMonitor) {
            return executor.sweep(policy, clock.instant());
        }
    }

    /**
     * 初始化会话工作目录。
     *
     * @param sessionId 会话标识
     */
    public void ensureWorkspaceDirectories(String sessionId) {
        executor.ensureWorkspaceDirectories(sessionId);
    }

    /**
     * 安全写入飞书凭证。
     *
     * @param appId 飞书应用标识
     * @param appSecret 飞书应用密钥
     */
    public void writeFeishuCredentials(String appId, String appSecret) {
        executor.writeFeishuCredentials(appId, appSecret);
    }

    /**
     * API Key 非空时安全写入百度凭证。
     *
     * @param apiKey 百度千帆 API Key
     */
    public void writeBaiduCredentials(String apiKey) {
        executor.writeBaiduCredentials(apiKey);
    }
}
