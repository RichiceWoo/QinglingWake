package cn.org.chris.wake.adapter.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * `POST /api/test/message` 的 Python 兼容响应体。
 *
 * @param msgId 实际使用的请求消息标识
 * @param reply 捕获的 Bot 最终回复
 * @param sessionId 当前 AgentScope 会话标识
 * @param durationMs 请求处理耗时，单位毫秒
 * @param skillsCalled 本轮调用的 Skill 名称；追踪接入前为空列表
 */
public record TestResponse(
        /** 实际使用的请求消息标识。 */ @JsonProperty("msg_id") String msgId,
        /** Bot 最终回复。 */ String reply,
        /** 当前 AgentScope 会话标识。 */ @JsonProperty("session_id") String sessionId,
        /** 请求处理耗时，单位毫秒。 */ @JsonProperty("duration_ms") long durationMs,
        /** 本轮调用的 Skill 名称。 */ @JsonProperty("skills_called") List<String> skillsCalled
) {

    /**
     * 复制 Skill 列表，避免响应对象被外部修改。
     */
    public TestResponse {
        skillsCalled = skillsCalled == null ? List.of() : List.copyOf(skillsCalled);
    }
}
