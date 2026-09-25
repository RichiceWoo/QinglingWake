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
     * 按 seq 顺序读取项目事件快照。
     *
     * @param projectId 项目标识
     * @return 不可变事件 Map 列表
     */
    List<Map<String, Object>> readAll(String projectId);
}
