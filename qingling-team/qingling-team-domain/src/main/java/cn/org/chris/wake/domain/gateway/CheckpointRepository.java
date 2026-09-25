package cn.org.chris.wake.domain.gateway;

import cn.org.chris.wake.domain.model.PendingCheckpoint;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 定义 pending checkpoint 及幂等解决标记的存储端口。
 */
public interface CheckpointRepository {

    /**
     * 注册一条新的 pending checkpoint。
     *
     * @param checkpoint 待保存 checkpoint
     */
    void register(PendingCheckpoint checkpoint);

    /**
     * 幂等追加解决标记。
     *
     * @param checkpointId checkpoint 标识
     * @param resolvedAt 解决时间
     * @return 首次解决时为 true
     */
    boolean resolve(String checkpointId, Instant resolvedAt);

    /**
     * 查询指定路由当前尚未解决的 checkpoint。
     *
     * @param routingKey 业务路由键
     * @return 按创建时间排列的 pending 列表
     */
    List<PendingCheckpoint> findPending(String routingKey);

    /**
     * 查询指定路由最新的 pending checkpoint。
     *
     * @param routingKey 业务路由键
     * @return 最新 pending 项
     */
    Optional<PendingCheckpoint> findLatestPending(String routingKey);
}
