package cn.org.chris.wake.app.session;

import cn.org.chris.wake.domain.model.SessionRoute;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * 在调用 Agent 前拦截并处理不会进入模型上下文的会话命令。
 */
public final class SlashCommandService {

    /** Slash Command 使用的路由服务。 */
    private final SessionRoutingService routingService;

    /**
     * 创建命令服务。
     *
     * @param routingService 会话路由服务
     */
    public SlashCommandService(SessionRoutingService routingService) {
        this.routingService = Objects.requireNonNull(routingService, "routingService 不能为空");
    }

    /**
     * 尝试消费 /new、/verbose、/status 或 /help；普通文本返回空。
     *
     * @param routingKey 业务路由键
     * @param content 原始消息正文
     * @return 命令结果或空
     */
    public Optional<SlashCommandResult> handle(String routingKey, String content) {
        if (content == null || !content.strip().startsWith("/")) {
            return Optional.empty();
        }
        String normalized = content.strip().toLowerCase(Locale.ROOT);
        if ("/new".equals(normalized)) {
            SessionRoute route = routingService.createNew(routingKey);
            return Optional.of(new SlashCommandResult("new", "已创建新会话。", route.activeSessionId()));
        }
        if (normalized.startsWith("/verbose")) {
            return Optional.of(handleVerbose(routingKey, normalized));
        }
        if ("/status".equals(normalized)) {
            SessionRoute route = routingService.getOrCreate(routingKey);
            String reply = "session=" + route.activeSessionId() + ", verbose=" + route.verbose();
            return Optional.of(new SlashCommandResult("status", reply, route.activeSessionId()));
        }
        if ("/help".equals(normalized)) {
            SessionRoute route = routingService.getOrCreate(routingKey);
            return Optional.of(new SlashCommandResult(
                    "help", "可用命令：/new、/verbose on|off、/status、/help", route.activeSessionId()
            ));
        }
        return Optional.empty();
    }

    /**
     * 解析并执行 verbose 开关命令。
     *
     * @param routingKey 业务路由键
     * @param normalized 已转为小写的命令文本
     * @return 命令执行结果
     */
    private SlashCommandResult handleVerbose(String routingKey, String normalized) {
        String[] parts = normalized.split("\\s+");
        SessionRoute route;
        if (parts.length == 1) {
            route = routingService.getOrCreate(routingKey);
        } else if (parts.length == 2 && ("on".equals(parts[1]) || "off".equals(parts[1]))) {
            route = routingService.updateVerbose(routingKey, "on".equals(parts[1]));
        } else {
            route = routingService.getOrCreate(routingKey);
            return new SlashCommandResult(
                    "verbose", "用法：/verbose on 或 /verbose off", route.activeSessionId()
            );
        }
        return new SlashCommandResult(
                "verbose", "verbose=" + route.verbose(), route.activeSessionId()
        );
    }
}
