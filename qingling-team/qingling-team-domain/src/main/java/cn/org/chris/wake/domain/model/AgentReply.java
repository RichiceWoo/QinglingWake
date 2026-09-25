package cn.org.chris.wake.domain.model;

import java.util.List;

/**
 * 表示 Agent 完成一轮执行后返回给应用层的稳定结果。
 *
 * @param content 对人或内部角色的回复正文
 * @param skillsCalled 本轮调用过的 Skill 名称
 */
public record AgentReply(
        /** Agent 最终回复正文。 */ String content,
        /** 本轮实际调用过的 Skill。 */ List<String> skillsCalled
) {
    /**
     * 标准化空回复并复制 Skill 列表。
     */
    public AgentReply {
        content = content == null ? "" : content;
        skillsCalled = skillsCalled == null ? List.of() : List.copyOf(skillsCalled);
    }
}
