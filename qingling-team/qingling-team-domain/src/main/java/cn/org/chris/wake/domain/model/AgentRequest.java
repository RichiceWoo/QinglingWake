package cn.org.chris.wake.domain.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 表示应用层向某一角色 Agent 发起的一次执行请求。
 *
 * @param role 目标角色
 * @param userId AgentScope 用户标识
 * @param sessionId AgentScope 会话标识
 * @param content 本轮用户输入，不包含审计历史
 * @param attachmentPaths 已下载附件的工作区相对路径
 * @param attributes 与模型历史无关的执行属性
 */
public record AgentRequest(
        /** manager、pm、rd 或 qa 角色。 */ String role,
        /** AgentScope RuntimeContext 的 userId。 */ String userId,
        /** AgentScope RuntimeContext 的 sessionId。 */ String sessionId,
        /** 本轮输入正文。 */ String content,
        /** 本轮附件在 workspace 内的路径。 */ List<String> attachmentPaths,
        /** 仅供工具和审计使用的执行属性。 */ Map<String, Object> attributes
) {
    /**
     * 复制集合以确保领域请求在异步执行期间保持不可变。
     */
    public AgentRequest {
        Objects.requireNonNull(role, "role 不能为空");
        Objects.requireNonNull(userId, "userId 不能为空");
        Objects.requireNonNull(sessionId, "sessionId 不能为空");
        content = content == null ? "" : content;
        attachmentPaths = attachmentPaths == null ? List.of() : List.copyOf(attachmentPaths);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
