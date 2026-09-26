package cn.org.chris.wake.adapter.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * `POST /api/test/message` 的 Python 兼容请求体。
 *
 * @param routingKey 必填业务路由键
 * @param content 可空用户正文，默认空字符串
 * @param msgId 可选来源消息标识
 * @param senderId 模拟用户标识，默认 ou_test001
 * @param attachment 可选本地附件
 */
public record TestRequest(
        /** 必填业务路由键。 */ @JsonProperty("routing_key") String routingKey,
        /** 用户正文，缺省时为空。 */ String content,
        /** 可选消息标识，空时由服务生成。 */ @JsonProperty("msg_id") String msgId,
        /** 模拟发送者标识。 */ @JsonProperty("sender_id") String senderId,
        /** 可选本地附件。 */ TestAttachment attachment
) {

    /** Python 基线中的默认测试发送者。 */
    public static final String DEFAULT_SENDER_ID = "ou_test001";

    /**
     * 补齐 Python Pydantic 模型中的 content 和 sender_id 默认值。
     */
    public TestRequest {
        content = content == null ? "" : content;
        senderId = senderId == null ? DEFAULT_SENDER_ID : senderId;
    }
}
