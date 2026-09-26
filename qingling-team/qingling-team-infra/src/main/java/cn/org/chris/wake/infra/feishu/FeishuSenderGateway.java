package cn.org.chris.wake.infra.feishu;

import cn.org.chris.wake.domain.gateway.SenderGateway;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.Client;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
import com.lark.oapi.service.im.v1.model.PatchMessageReq;
import com.lark.oapi.service.im.v1.model.PatchMessageReqBody;
import com.lark.oapi.service.im.v1.model.ReplyMessageReq;
import com.lark.oapi.service.im.v1.model.ReplyMessageReqBody;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * 使用飞书 OpenAPI 发送卡片、文本和话题回复，并按 1/2/4 秒执行退避重试。
 */
public final class FeishuSenderGateway implements SenderGateway {

    /** Loading 卡片中展示的固定提示。 */
    private static final String THINKING_TEXT = "⏳ 思考中，请稍候...";

    /** 默认三次重试前的退避间隔。 */
    private static final List<Duration> DEFAULT_BACKOFFS = List.of(
            Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4)
    );

    /** 可替换的飞书 API 端口。 */
    private final FeishuApi api;

    /** 卡片与文本 JSON 序列化器。 */
    private final ObjectMapper objectMapper;

    /** 执行同步 SDK 调用与等待退避的线程池。 */
    private final Executor executor;

    /** 测试可替换的退避等待策略。 */
    private final Sleeper sleeper;

    /** 每次重试失败后的等待间隔。 */
    private final List<Duration> backoffs;

    /**
     * 使用官方客户端和默认 1/2/4 秒重试策略创建 Sender。
     *
     * @param client 官方飞书客户端
     * @param objectMapper JSON 序列化器
     */
    public FeishuSenderGateway(Client client, ObjectMapper objectMapper) {
        this(
                FeishuClientFactory.api(client),
                objectMapper,
                ForkJoinPool.commonPool(),
                duration -> Thread.sleep(duration.toMillis()),
                DEFAULT_BACKOFFS
        );
    }

    /**
     * 使用可测试依赖创建 Sender。
     *
     * @param api 飞书 API 端口
     * @param objectMapper JSON 序列化器
     * @param executor 阻塞 I/O 执行器
     * @param sleeper 退避等待策略
     * @param backoffs 重试间隔；列表长度就是最大重试次数
     */
    public FeishuSenderGateway(
            FeishuApi api,
            ObjectMapper objectMapper,
            Executor executor,
            Sleeper sleeper,
            List<Duration> backoffs
    ) {
        this.api = Objects.requireNonNull(api, "api 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        this.executor = Objects.requireNonNull(executor, "executor 不能为空");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper 不能为空");
        this.backoffs = backoffs == null ? List.of() : List.copyOf(backoffs);
    }

    /**
     * 发送 Markdown 卡片；thread 路由回复 root，其余路由创建新消息。
     */
    @Override
    public CompletableFuture<Void> send(String routingKey, String content, String rootId) {
        return submit(() -> {
            sendWithRetry(routingKey, "interactive", buildCard(content), rootId);
            return null;
        });
    }

    /**
     * 发送 Loading 卡片并返回后续 PATCH 所需的消息标识；失败时返回 null 供 Runner 降级。
     */
    @Override
    public CompletableFuture<String> sendThinking(String routingKey, String rootId) {
        return submit(() -> {
            try {
                return sendWithRetry(routingKey, "interactive", buildCard(THINKING_TEXT), rootId);
            } catch (RuntimeException ignored) {
                return null;
            }
        });
    }

    /**
     * 使用 PATCH 将 Loading 卡片替换为最终 Markdown 内容。
     */
    @Override
    public CompletableFuture<Void> updateCard(String cardMessageId, String content) {
        return submit(() -> {
            requireText(cardMessageId, "cardMessageId");
            PatchMessageReq request = PatchMessageReq.newBuilder()
                    .messageId(cardMessageId)
                    .patchMessageReqBody(PatchMessageReqBody.newBuilder()
                            .content(buildCard(content))
                            .build())
                    .build();
            retry("更新卡片", () -> {
                FeishuApi.ApiResult result = api.patch(request);
                ensureSuccess("更新卡片", result.success(), result.code());
                return null;
            });
            return null;
        });
    }

    /**
     * 发送飞书纯文本消息，主要用于 Slash Command 和错误降级。
     */
    @Override
    public CompletableFuture<Void> sendText(String routingKey, String content, String rootId) {
        return submit(() -> {
            sendWithRetry(routingKey, "text", buildText(content), rootId);
            return null;
        });
    }

    /**
     * 根据 routing key 构造 create 或 reply 请求，并返回创建的消息标识。
     */
    private String sendWithRetry(String routingKey, String messageType, String content, String rootId) {
        Route route = Route.parse(routingKey, rootId);
        return retry("发送消息", () -> {
            FeishuApi.MessageResult result;
            if (route.thread()) {
                ReplyMessageReq request = ReplyMessageReq.newBuilder()
                        .messageId(route.rootId())
                        .replyMessageReqBody(ReplyMessageReqBody.newBuilder()
                                .msgType(messageType)
                                .content(content)
                                .replyInThread(true)
                                .uuid(idempotencyKey(rootId))
                                .build())
                        .build();
                result = api.reply(request);
            } else {
                CreateMessageReq request = CreateMessageReq.newBuilder()
                        .receiveIdType(route.receiveIdType())
                        .createMessageReqBody(CreateMessageReqBody.newBuilder()
                                .receiveId(route.receiveId())
                                .msgType(messageType)
                                .content(content)
                                .uuid(idempotencyKey(rootId))
                                .build())
                        .build();
                result = api.create(request);
            }
            ensureSuccess("发送消息", result.success(), result.code());
            return result.messageId();
        });
    }

    /**
     * 首次调用后最多按 backoffs 配置重试，异常对外只暴露操作名与响应码。
     */
    private <T> T retry(String operation, CheckedSupplier<T> action) {
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt <= backoffs.size(); attempt++) {
            try {
                return action.get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new FeishuOperationException(operation + "被中断", -1, interrupted);
            } catch (FeishuOperationException failure) {
                lastFailure = failure;
            } catch (Exception failure) {
                lastFailure = new FeishuOperationException(operation + "异常", -1, failure);
            }
            if (attempt < backoffs.size()) {
                try {
                    sleeper.sleep(backoffs.get(attempt));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new FeishuOperationException(operation + "重试等待被中断", -1, interrupted);
                }
            }
        }
        throw Objects.requireNonNull(lastFailure, "重试失败原因不能为空");
    }

    /**
     * 把阻塞调用调度到指定执行器，并保留异常 Future 语义。
     */
    private <T> CompletableFuture<T> submit(CheckedSupplier<T> action) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return action.get();
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new FeishuOperationException("飞书调用异常", -1, failure);
            }
        }, executor);
    }

    /**
     * 构造 Python 兼容的 interactive 卡片 JSON。
     */
    private String buildCard(String content) {
        Map<String, Object> card = Map.of(
                "config", Map.of("wide_screen_mode", true),
                "elements", List.of(Map.of(
                        "tag", "div",
                        "text", Map.of("content", Objects.requireNonNullElse(content, ""), "tag", "lark_md")
                ))
        );
        return writeJson(card);
    }

    /**
     * 构造飞书 text 消息 JSON。
     */
    private String buildText(String content) {
        return writeJson(Map.of("text", Objects.requireNonNullElse(content, "")));
    }

    /**
     * 将消息体序列化为 JSON，失败时不包含原始消息正文。
     */
    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new FeishuOperationException("飞书消息序列化失败", -1, failure);
        }
    }

    /**
     * 校验 SDK 结果并转换为脱敏异常。
     */
    private static void ensureSuccess(String operation, boolean success, int code) {
        if (!success) {
            throw new FeishuOperationException(operation + "失败", code);
        }
    }

    /**
     * 复用来源消息标识作为幂等键，缺失时生成随机键。
     */
    private static String idempotencyKey(String rootId) {
        return rootId == null || rootId.isBlank() ? UUID.randomUUID().toString() : rootId;
    }

    /**
     * 校验发送所需文本字段非空。
     */
    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value;
    }

    /**
     * 测试可替换的退避等待端口。
     */
    @FunctionalInterface
    public interface Sleeper {

        /** 按指定时长等待，允许响应线程中断。 */
        void sleep(Duration duration) throws InterruptedException;
    }

    /**
     * 允许抛出受检异常的内部操作。
     */
    @FunctionalInterface
    private interface CheckedSupplier<T> {

        /** 执行一次 SDK 或等待操作。 */
        T get() throws Exception;
    }

    /**
     * 描述 routing key 对应的飞书发送目标。
     *
     * @param receiveIdType create 接口的 open_id 或 chat_id
     * @param receiveId create 接口接收者标识
     * @param rootId thread reply 的根消息标识
     * @param thread 是否使用 reply_in_thread
     */
    private record Route(
            /** create 接口接收者标识类型。 */ String receiveIdType,
            /** create 接口接收者标识。 */ String receiveId,
            /** thread 回复的根消息标识。 */ String rootId,
            /** 是否使用话题回复接口。 */ boolean thread
    ) {

        /**
         * 解析 p2p、group 和 thread 路由并拒绝未知路由。
         */
        private static Route parse(String routingKey, String rootId) {
            String route = requireText(routingKey, "routingKey");
            if (route.startsWith("p2p:")) {
                return new Route("open_id", requireText(route.substring(4), "openId"), "", false);
            }
            if (route.startsWith("group:")) {
                return new Route("chat_id", requireText(route.substring(6), "chatId"), "", false);
            }
            if (route.startsWith("thread:")) {
                return new Route("", "", requireText(rootId, "rootId"), true);
            }
            throw new IllegalArgumentException("不支持的 routingKey 类型");
        }
    }
}
