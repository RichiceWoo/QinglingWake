package cn.org.chris.wake.domain.gateway;

import java.util.List;
import java.util.Map;

/**
 * 定义项目 append-only 事件日志的存储端口。
 */
public interface EventRepository {

    /**
     * 以单调递增 seq 追加事件。
     *
     * @param projectId 项目标识
     * @param actor 事件发起角色
     * @param action 受支持的动作名称
     * @param details 事件业务详情
     * @return 分配给本事件的 seq
     */
    long append(String projectId, String actor, String action, Map<String, Object> details);

    /**
     * 按稳定投影标识追加事件；已存在同一标识时返回原 seq，不重复写入。
     *
     * <p>默认实现仅用于不支持 runtime v2 的测试或第三方实现；生产文件仓储必须覆盖本方法，
     * 在与追加相同的锁内完成查重。</p>
     *
     * @param projectId 项目标识
     * @param projectionId 稳定 outbox action 标识
     * @param actor 事件发起角色
     * @param action 事件动作
     * @param details 有界事件详情
     * @return 已存在或新追加事件的 seq
     */
    default long appendOnce(
            String projectId,
            String projectionId,
            String actor,
            String action,
            Map<String, Object> details
    ) {
        return append(projectId, actor, action, details);
    }

    /**
     * 按 seq 顺序读取项目事件快照。
     *
     * @param projectId 项目标识
     * @return 不可变事件 Map 列表
     */
    List<Map<String, Object>> readAll(String projectId);
}
