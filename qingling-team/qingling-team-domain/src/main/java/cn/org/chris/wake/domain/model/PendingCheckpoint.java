package cn.org.chris.wake.domain.model;

import java.util.Objects;

/**
 * 表示 Manager 已发出、尚待人类回应的 checkpoint。
 *
 * @param checkpointId checkpoint 唯一标识
 * @param routingKey 发起会话的路由键
 * @param projectId 所属项目标识
 * @param kind checkpoint 业务类型
 * @param question 提问摘要
 * @param createdAtMs 创建时间
 */
public record PendingCheckpoint(
        /** checkpoint 唯一标识。 */ String checkpointId,
        /** 发起会话的路由键。 */ String routingKey,
        /** 所属项目标识。 */ String projectId,
        /** checkpoint_request 等业务类型。 */ String kind,
        /** 发给人类的提问摘要。 */ String question,
        /** 创建时间，单位毫秒。 */ long createdAtMs
) {
    /**
     * 校验 checkpoint 可被稳定路由和解析。
     */
    public PendingCheckpoint {
        Objects.requireNonNull(checkpointId, "checkpointId 不能为空");
        Objects.requireNonNull(routingKey, "routingKey 不能为空");
        projectId = projectId == null ? "" : projectId;
        kind = kind == null ? "checkpoint_request" : kind;
        question = question == null ? "" : question;
    }
}
