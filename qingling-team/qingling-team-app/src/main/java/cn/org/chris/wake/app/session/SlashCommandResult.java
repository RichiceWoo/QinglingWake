package cn.org.chris.wake.app.session;

/**
 * 表示 Slash Command 已被应用层消费后的即时响应。
 *
 * @param command 已识别命令名
 * @param reply 返回给用户的文本
 * @param sessionId 命令完成后的活跃 sessionId
 */
public record SlashCommandResult(
        /** 不含斜杠的规范化命令名。 */ String command,
        /** 返回给用户的命令结果。 */ String reply,
        /** 命令执行后的活跃 sessionId。 */ String sessionId
) {
}
