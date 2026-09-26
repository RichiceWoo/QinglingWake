package cn.org.chris.wake.app.runner;

import cn.org.chris.wake.app.session.SessionRoutingService;
import cn.org.chris.wake.app.session.SlashCommandResult;
import cn.org.chris.wake.app.session.SlashCommandService;
import cn.org.chris.wake.domain.gateway.AgentGateway;
import cn.org.chris.wake.domain.gateway.CronDispatchGateway;
import cn.org.chris.wake.domain.gateway.MetricsGateway;
import cn.org.chris.wake.domain.gateway.SenderGateway;
import cn.org.chris.wake.domain.model.AgentReply;
import cn.org.chris.wake.domain.model.AgentRequest;
import cn.org.chris.wake.domain.model.InboundMessage;
import cn.org.chris.wake.domain.model.SessionRoute;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 编排入站消息的路由串行化、命令、附件、Agent、审计与出站回复完整链路。
 */
public final class Runner implements CronDispatchGateway {

    /** 对外返回的统一可重试错误提示。 */
    private static final String ERROR_REPLY = "处理出错，请稍后重试。";

    /** routing key 到会话的映射和审计服务。 */
    private final SessionRoutingService sessionRoutingService;

    /** 在进入 Agent 前消费 Slash Command。 */
    private final SlashCommandService slashCommandService;

    /** AgentScope 领域端口。 */
    private final AgentGateway agentGateway;

    /** 飞书或测试 Sender 端口。 */
    private final SenderGateway senderGateway;

    /** 附件下载与 session workspace 落盘服务。 */
    private final InboundAttachmentService attachmentService;

    /** 按 routing key 串行化异步任务的注册表。 */
    private final SerialDispatchRegistry dispatchRegistry;

    /** 将 routing key 解析为角色和用户标识。 */
    private final RoutingKeyResolver routingKeyResolver;

    /** 不依赖具体指标库的 Runner 观测端口。 */
    private final MetricsGateway metrics;

    /** 正在等待或执行的 wake 路由，用于抑制重复 heartbeat/new_mail。 */
    private final Set<String> inFlightWakeRoutes = ConcurrentHashMap.newKeySet();

    /**
     * 创建完整 Runner 执行链。
     *
     * @param sessionRoutingService 会话和审计服务
     * @param slashCommandService Slash Command 服务
     * @param agentGateway Agent 执行端口
     * @param senderGateway 出站消息端口
     * @param attachmentService 附件服务
     * @param dispatchRegistry 串行调度注册表
     * @param routingKeyResolver 路由解析器
     * @param metrics Runner 指标端口
     */
    public Runner(
            SessionRoutingService sessionRoutingService,
            SlashCommandService slashCommandService,
            AgentGateway agentGateway,
            SenderGateway senderGateway,
            InboundAttachmentService attachmentService,
            SerialDispatchRegistry dispatchRegistry,
            RoutingKeyResolver routingKeyResolver,
            MetricsGateway metrics
    ) {
        this.sessionRoutingService = Objects.requireNonNull(
                sessionRoutingService, "sessionRoutingService 不能为空"
        );
        this.slashCommandService = Objects.requireNonNull(slashCommandService, "slashCommandService 不能为空");
        this.agentGateway = Objects.requireNonNull(agentGateway, "agentGateway 不能为空");
        this.senderGateway = Objects.requireNonNull(senderGateway, "senderGateway 不能为空");
        this.attachmentService = Objects.requireNonNull(attachmentService, "attachmentService 不能为空");
        this.dispatchRegistry = Objects.requireNonNull(dispatchRegistry, "dispatchRegistry 不能为空");
        this.routingKeyResolver = Objects.requireNonNull(routingKeyResolver, "routingKeyResolver 不能为空");
        this.metrics = Objects.requireNonNull(metrics, "metrics 不能为空");
    }

    /**
     * 接收入站消息并追加到对应 routing key 的串行队列；不同 key 可并行。
     *
     * @param inbound 标准化入站消息
     * @return 本条消息完整处理结束信号
     */
    @Override
    public CompletableFuture<Void> dispatch(InboundMessage inbound) {
        Objects.requireNonNull(inbound, "inbound 不能为空");
        String routingType = routingType(inbound.routingKey());
        metrics.recordInbound(routingType, inbound.attachment() != null);
        boolean wake = isWake(inbound);
        if (wake && !inFlightWakeRoutes.add(inbound.routingKey())) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> result = dispatchRegistry.submit(
                inbound.routingKey(), () -> processObserved(inbound, routingType)
        );
        metrics.recordQueueDepth(routingType, dispatchRegistry.pendingCount(inbound.routingKey()));
        result.whenComplete((unused, failure) -> {
            if (wake) {
                inFlightWakeRoutes.remove(inbound.routingKey());
            }
            metrics.recordQueueDepth(routingType, dispatchRegistry.pendingCount(inbound.routingKey()));
        });
        return result;
    }

    /**
     * 在实际开始和结束处理时维护活跃 worker 指标，并覆盖同步抛错路径。
     */
    private CompletableFuture<Void> processObserved(InboundMessage inbound, String routingType) {
        metrics.recordWorkerDelta(routingType, 1);
        try {
            return processWithFailureHandling(inbound)
                    .whenComplete((unused, failure) -> metrics.recordWorkerDelta(routingType, -1));
        } catch (RuntimeException failure) {
            metrics.recordWorkerDelta(routingType, -1);
            throw failure;
        }
    }

    /**
     * 执行一条消息，并在失败时记录指标和尝试发送外部错误提示后保留原始异常。
     *
     * @param inbound 入站消息
     * @return 成功或保留原始异常的 Future
     */
    private CompletableFuture<Void> processWithFailureHandling(InboundMessage inbound) {
        return process(inbound).handle((unused, failure) -> {
            if (failure == null) {
                return CompletableFuture.<Void>completedFuture(null);
            }
            Throwable cause = unwrap(failure);
            metrics.recordFailure("runner", cause.getClass().getSimpleName());
            if (routingKeyResolver.isTeamRoute(inbound.routingKey())) {
                return CompletableFuture.<Void>failedFuture(cause);
            }
            CompletableFuture<Void> notification;
            try {
                notification = senderGateway.sendText(inbound.routingKey(), ERROR_REPLY, inbound.rootId());
            } catch (RuntimeException senderFailure) {
                metrics.recordFailure("sender", senderFailure.getClass().getSimpleName());
                return CompletableFuture.<Void>failedFuture(cause);
            }
            return notification.handle((ignored, senderFailure) -> {
                if (senderFailure != null) {
                    metrics.recordFailure("sender", unwrap(senderFailure).getClass().getSimpleName());
                }
                return (Void) null;
            }).thenCompose(ignored -> CompletableFuture.<Void>failedFuture(cause));
        }).thenCompose(future -> future);
    }

    /**
     * 按固定顺序处理单条消息：命令、会话、附件、Loading、Agent、审计和回复。
     *
     * @param inbound 入站消息
     * @return 完整处理完成信号
     */
    private CompletableFuture<Void> process(InboundMessage inbound) {
        RoutingKeyResolver.RoutingTarget target = routingKeyResolver.resolve(inbound);
        Optional<SlashCommandResult> slashResult = slashCommandService.handle(
                inbound.routingKey(), inbound.content()
        );
        if (slashResult.isPresent()) {
            if (target.teamRoute()) {
                return CompletableFuture.completedFuture(null);
            }
            return senderGateway.sendText(
                    inbound.routingKey(), slashResult.orElseThrow().reply(), inbound.rootId()
            );
        }
        SessionRoute session = sessionRoutingService.getOrCreate(inbound.routingKey());
        return attachmentService.prepare(inbound, session.activeSessionId())
                .thenCompose(prepared -> {
                    if (!prepared.attachmentReady()) {
                        metrics.recordFailure("attachment", "DownloadFailed");
                    }
                    return executeAgent(inbound, target, session, prepared);
                });
    }

    /**
     * 可选发送 Loading 卡片后调用 Agent，并在成功时写审计和发送最终回复。
     *
     * @param inbound 入站消息
     * @param target 角色路由结果
     * @param session 当前活跃会话
     * @param prepared 附件准备后的模型输入
     * @return Agent 和出站操作完成信号
     */
    private CompletableFuture<Void> executeAgent(
            InboundMessage inbound,
            RoutingKeyResolver.RoutingTarget target,
            SessionRoute session,
            InboundAttachmentService.PreparedInbound prepared
    ) {
        CompletableFuture<String> thinking = target.teamRoute()
                ? CompletableFuture.completedFuture(null)
                : sendThinkingOrContinue(inbound);
        return thinking.thenCompose(cardMessageId -> {
            AgentRequest request = new AgentRequest(
                    target.role(),
                    target.userId(),
                    session.activeSessionId(),
                    prepared.content(),
                    prepared.attachmentPaths(),
                    requestAttributes(inbound, session)
            );
            return agentGateway.execute(request)
                    .thenCompose(reply -> persistAndReply(
                            inbound, target, session, prepared, reply, cardMessageId
                    ));
        });
    }

    /**
     * 发送 Loading 失败时记录 sender 指标并降级为稍后直接发送最终回复。
     *
     * @param inbound 入站消息
     * @return 卡片消息标识，失败时为空
     */
    private CompletableFuture<String> sendThinkingOrContinue(InboundMessage inbound) {
        try {
            return senderGateway.sendThinking(inbound.routingKey(), inbound.rootId())
                    .handle((cardMessageId, failure) -> {
                        if (failure != null) {
                            metrics.recordFailure("sender", unwrap(failure).getClass().getSimpleName());
                            return null;
                        }
                        return cardMessageId;
                    });
        } catch (RuntimeException failure) {
            metrics.recordFailure("sender", failure.getClass().getSimpleName());
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * 在 Agent 成功后先追加审计，再按团队/外部路由规则处理最终回复。
     *
     * @param inbound 入站消息
     * @param target 角色路由结果
     * @param session 当前会话
     * @param prepared 模型输入
     * @param reply Agent 回复
     * @param cardMessageId 可空 Loading 卡片标识
     * @return 审计和出站完成信号
     */
    private CompletableFuture<Void> persistAndReply(
            InboundMessage inbound,
            RoutingKeyResolver.RoutingTarget target,
            SessionRoute session,
            InboundAttachmentService.PreparedInbound prepared,
            AgentReply reply,
            String cardMessageId
    ) {
        sessionRoutingService.recordTurn(
                inbound.routingKey(),
                session.activeSessionId(),
                prepared.content(),
                reply.content(),
                inbound.msgId()
        );
        if (target.teamRoute()) {
            return CompletableFuture.completedFuture(null);
        }
        if (cardMessageId == null || cardMessageId.isBlank()) {
            return senderGateway.send(inbound.routingKey(), reply.content(), inbound.rootId());
        }
        CompletableFuture<Void> update;
        try {
            update = senderGateway.updateCard(cardMessageId, reply.content());
        } catch (RuntimeException failure) {
            metrics.recordFailure("sender", failure.getClass().getSimpleName());
            return senderGateway.send(inbound.routingKey(), reply.content(), inbound.rootId());
        }
        return update
                .handle((unused, failure) -> failure)
                .thenCompose(failure -> {
                    if (failure == null) {
                        return CompletableFuture.completedFuture(null);
                    }
                    metrics.recordFailure("sender", unwrap(failure).getClass().getSimpleName());
                    return senderGateway.send(inbound.routingKey(), reply.content(), inbound.rootId());
                });
    }

    /**
     * 构造只含执行元数据的 RuntimeContext 属性，不加入任何审计历史。
     *
     * @param inbound 入站消息
     * @param session 当前会话
     * @return 不可变执行属性
     */
    private static Map<String, Object> requestAttributes(InboundMessage inbound, SessionRoute session) {
        Map<String, Object> attributes = new LinkedHashMap<>(inbound.meta());
        attributes.put("routingKey", inbound.routingKey());
        attributes.put("rootId", inbound.rootId());
        attributes.put("verbose", session.verbose());
        attributes.put("cron", inbound.cron());
        return Map.copyOf(attributes);
    }

    /**
     * 判断消息是否属于需要去重的显式 wake。
     *
     * @param inbound 入站消息
     * @return meta 含 wake_reason 或 cron wake 正文时为 true
     */
    private static boolean isWake(InboundMessage inbound) {
        return inbound.meta().containsKey("wake_reason")
                || inbound.cron() && inbound.content().startsWith("__wake__:");
    }

    /**
     * 将 routing key 归类为低基数指标标签。
     *
     * @param routingKey 业务路由键
     * @return p2p、group、thread、team 或 unknown
     */
    private static String routingType(String routingKey) {
        if (routingKey == null) {
            return "unknown";
        }
        for (String type : Set.of("p2p", "group", "thread", "team")) {
            if (routingKey.startsWith(type + ":")) {
                return type;
            }
        }
        return "unknown";
    }

    /**
     * 去掉异步包装异常，便于稳定记录真实失败类型。
     *
     * @param failure 异步失败
     * @return 原始原因
     */
    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
