package cn.org.chris.wake.app.checkpoint;

import cn.org.chris.wake.domain.checkpoint.HumanInputClassifier;
import cn.org.chris.wake.domain.gateway.CheckpointRepository;
import cn.org.chris.wake.domain.model.PendingCheckpoint;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 编排 checkpoint 注册、查询、输入分类和幂等解决，不承担 Manager 业务决策。
 */
public final class CheckpointService {

    /** checkpoint 持久化端口。 */
    private final CheckpointRepository repository;

    /** 生成可替换的 ckpt-xxxxxxxx 标识。 */
    private final Supplier<String> checkpointIdSupplier;

    /** 提供创建和解决时间。 */
    private final Clock clock;

    /**
     * 创建使用随机标识和系统 UTC 时钟的服务。
     *
     * @param repository checkpoint 持久化端口
     */
    public CheckpointService(CheckpointRepository repository) {
        this(repository, CheckpointService::randomCheckpointId, Clock.systemUTC());
    }

    /**
     * 创建可注入标识生成器和时钟的服务。
     *
     * @param repository checkpoint 持久化端口
     * @param checkpointIdSupplier checkpoint 标识生成器
     * @param clock UTC 时钟
     */
    public CheckpointService(
            CheckpointRepository repository,
            Supplier<String> checkpointIdSupplier,
            Clock clock
    ) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
        this.checkpointIdSupplier = Objects.requireNonNull(checkpointIdSupplier, "checkpointIdSupplier 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    /**
     * 使用自动生成的标识注册 pending checkpoint。
     *
     * @param routingKey 发起会话的路由键
     * @param projectId 所属项目标识
     * @param kind checkpoint 业务类型
     * @param question 提问摘要
     * @return 新 checkpoint 标识
     */
    public String register(String routingKey, String projectId, String kind, String question) {
        return register(routingKey, projectId, kind, question, null);
    }

    /**
     * 使用可选显式标识注册 pending checkpoint。
     *
     * @param routingKey 发起会话的路由键
     * @param projectId 所属项目标识
     * @param kind checkpoint 业务类型
     * @param question 提问摘要
     * @param checkpointId 显式标识；为空时自动生成
     * @return 实际 checkpoint 标识
     */
    public String register(
            String routingKey,
            String projectId,
            String kind,
            String question,
            String checkpointId
    ) {
        String actualId = checkpointId == null || checkpointId.isBlank()
                ? checkpointIdSupplier.get()
                : checkpointId;
        PendingCheckpoint checkpoint = new PendingCheckpoint(
                requireText(actualId, "checkpointId"),
                requireText(routingKey, "routingKey"),
                projectId,
                kind,
                question,
                clock.millis()
        );
        repository.register(checkpoint);
        return actualId;
    }

    /**
     * 查询全部尚未解决的 checkpoint。
     *
     * @return 按注册顺序排列的 pending 列表
     */
    public List<PendingCheckpoint> pending() {
        return repository.findPending();
    }

    /**
     * 查询指定路由尚未解决的 checkpoint。
     *
     * @param routingKey 业务路由键
     * @return 当前路由 pending 列表
     */
    public List<PendingCheckpoint> pending(String routingKey) {
        return repository.findPending(requireText(routingKey, "routingKey"));
    }

    /**
     * 查询指定路由最新的 pending checkpoint。
     *
     * @param routingKey 业务路由键
     * @return 最新项
     */
    public Optional<PendingCheckpoint> latestPending(String routingKey) {
        return repository.findLatestPending(requireText(routingKey, "routingKey"));
    }

    /**
     * 使用当前路由 pending 列表为一条人类输入分类。
     *
     * @param routingKey 业务路由键
     * @param text 人类输入文本
     * @param callbackValue 飞书卡片 callback 数据，可为空
     * @return 输入分类及绑定标识
     */
    public HumanInputClassifier.Classification classify(
            String routingKey,
            String text,
            Map<String, ?> callbackValue
    ) {
        return HumanInputClassifier.classify(text, callbackValue, pending(routingKey));
    }

    /**
     * 幂等解决指定 checkpoint，不存在或已解决时返回 false。
     *
     * @param checkpointId checkpoint 标识
     * @return 首次解决时为 true
     */
    public boolean resolve(String checkpointId) {
        return repository.resolve(requireText(checkpointId, "checkpointId"), clock.instant());
    }

    /**
     * 将指定路由的 pending 列表转换为 Python dataclass 的 snake_case Map。
     *
     * @param routingKey 业务路由键
     * @return 可直接用于 JSON 输出的只读列表
     */
    public List<Map<String, Object>> serializePending(String routingKey) {
        return pending(routingKey).stream().map(CheckpointService::toMap).toList();
    }

    /**
     * 将单个 checkpoint 转换为跨语言稳定字段。
     *
     * @param checkpoint 待转换 checkpoint
     * @return Python 兼容字段 Map
     */
    private static Map<String, Object> toMap(PendingCheckpoint checkpoint) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("checkpoint_id", checkpoint.checkpointId());
        data.put("routing_key", checkpoint.routingKey());
        data.put("project_id", checkpoint.projectId());
        data.put("kind", checkpoint.kind());
        data.put("question", checkpoint.question());
        data.put("created_at_ms", checkpoint.createdAtMs());
        return Map.copyOf(data);
    }

    /**
     * 校验路由和标识等必需文本不为空。
     *
     * @param value 原始文本
     * @param field 字段名称
     * @return 已校验文本
     */
    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }

    /**
     * 生成与 Python 格式一致的八位十六进制 checkpoint 标识。
     *
     * @return ckpt-xxxxxxxx 格式标识
     */
    private static String randomCheckpointId() {
        return "ckpt-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
