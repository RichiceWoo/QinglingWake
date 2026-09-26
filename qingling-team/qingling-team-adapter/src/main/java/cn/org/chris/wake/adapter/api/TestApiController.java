package cn.org.chris.wake.adapter.api;

import cn.org.chris.wake.adapter.api.dto.TestAttachment;
import cn.org.chris.wake.adapter.api.dto.TestRequest;
import cn.org.chris.wake.adapter.api.dto.TestResponse;
import cn.org.chris.wake.domain.gateway.MetricsGateway;
import cn.org.chris.wake.app.session.SessionRoutingService;
import cn.org.chris.wake.domain.gateway.CronDispatchGateway;
import cn.org.chris.wake.domain.model.InboundMessage;
import cn.org.chris.wake.domain.model.SessionRoute;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 提供与 Python TestAPI 一致的消息注入和测试会话清理 HTTP 接口。
 */
@RestController
public final class TestApiController {

    /** 消息注入端点路径。 */
    private static final String MESSAGE_PATH = "/api/test/message";

    /** 会话清理端点路径。 */
    private static final String SESSIONS_PATH = "/api/test/sessions";

    /** Python 基线的默认 Bot 回复等待时间。 */
    public static final Duration DEFAULT_REPLY_TIMEOUT = Duration.ofSeconds(300);

    /** 把标准化入站消息注入 Runner 的领域端口。 */
    private final CronDispatchGateway dispatcher;

    /** 捕获 Runner 最终出站回复的测试 Sender。 */
    private final CaptureSender captureSender;

    /** 创建、查询和清理 TestAPI 路由会话。 */
    private final SessionRoutingService sessionRoutingService;

    /** session workspace 根目录，默认语义为 data/workspace。 */
    private final Path workspaceDirectory;

    /** 单个 HTTP 请求等待 Bot 回复的最长时间。 */
    private final Duration replyTimeout;

    /** 提供请求时间戳，测试可替换。 */
    private final Clock clock;

    /** 生成缺省 test_ 消息标识，测试可替换。 */
    private final Supplier<String> messageIdSupplier;

    /** HTTP 请求和错误指标端口。 */
    private final MetricsGateway metrics;

    /** 记录 TestAPI 路由对应 senderId，用于 DELETE 时清理 AgentScope state。 */
    private final Map<String, String> testRouteUsers = new ConcurrentHashMap<>();

    /**
     * 使用系统时间和随机消息标识创建生产 TestAPI Controller。
     *
     * @param dispatcher Runner 消息分发端口
     * @param captureSender 测试出站捕获器
     * @param sessionRoutingService 会话路由服务
     * @param workspaceDirectory workspace 根目录
     * @param replyTimeout 可配置回复超时，空值使用 300 秒
     */
    public TestApiController(
            CronDispatchGateway dispatcher,
            CaptureSender captureSender,
            SessionRoutingService sessionRoutingService,
            Path workspaceDirectory,
            Duration replyTimeout
    ) {
        this(
                dispatcher,
                captureSender,
                sessionRoutingService,
                workspaceDirectory,
                replyTimeout,
                Clock.systemUTC(),
                TestApiController::randomMessageId,
                MetricsGateway.noop()
        );
    }

    /**
     * 使用系统时间、随机消息标识和指定指标端口创建生产 Controller。
     */
    public TestApiController(
            CronDispatchGateway dispatcher,
            CaptureSender captureSender,
            SessionRoutingService sessionRoutingService,
            Path workspaceDirectory,
            Duration replyTimeout,
            MetricsGateway metrics
    ) {
        this(dispatcher, captureSender, sessionRoutingService, workspaceDirectory, replyTimeout,
                Clock.systemUTC(), TestApiController::randomMessageId, metrics);
    }

    /**
     * 使用可替换时钟和消息标识生成器创建确定性测试 Controller。
     */
    TestApiController(
            CronDispatchGateway dispatcher,
            CaptureSender captureSender,
            SessionRoutingService sessionRoutingService,
            Path workspaceDirectory,
            Duration replyTimeout,
            Clock clock,
            Supplier<String> messageIdSupplier
    ) {
        this(dispatcher, captureSender, sessionRoutingService, workspaceDirectory, replyTimeout,
                clock, messageIdSupplier, MetricsGateway.noop());
    }

    /**
     * 使用可替换时钟、消息标识生成器和指标端口创建 Controller。
     */
    TestApiController(
            CronDispatchGateway dispatcher,
            CaptureSender captureSender,
            SessionRoutingService sessionRoutingService,
            Path workspaceDirectory,
            Duration replyTimeout,
            Clock clock,
            Supplier<String> messageIdSupplier,
            MetricsGateway metrics
    ) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher 不能为空");
        this.captureSender = Objects.requireNonNull(captureSender, "captureSender 不能为空");
        this.sessionRoutingService = Objects.requireNonNull(
                sessionRoutingService, "sessionRoutingService 不能为空"
        );
        this.workspaceDirectory = Objects.requireNonNull(workspaceDirectory, "workspaceDirectory 不能为空")
                .toAbsolutePath().normalize();
        this.replyTimeout = replyTimeout == null ? DEFAULT_REPLY_TIMEOUT : positiveTimeout(replyTimeout);
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
        this.messageIdSupplier = Objects.requireNonNull(messageIdSupplier, "messageIdSupplier 不能为空");
        this.metrics = Objects.requireNonNull(metrics, "metrics 不能为空");
    }

    /**
     * 注入一条模拟飞书消息并同步等待 CaptureSender 返回最终回复。
     *
     * @param request Python 兼容测试请求
     * @return 成功响应或 422/500 错误 JSON
     */
    @PostMapping("/api/test/message")
    public ResponseEntity<?> sendMessage(@RequestBody TestRequest request) {
        long startedNanos = System.nanoTime();
        ResponseEntity<?> validation = validate(request);
        if (validation != null) {
            return observed(MESSAGE_PATH, "POST", startedNanos, validation);
        }
        String messageId = request.msgId() == null || request.msgId().isBlank()
                ? messageIdSupplier.get()
                : request.msgId();
        String content;
        try {
            content = prepareContent(request);
        } catch (IllegalArgumentException invalidAttachment) {
            return observed(MESSAGE_PATH, "POST", startedNanos,
                    unprocessable("attachment", "附件路径或文件名无效"));
        } catch (IOException copyFailure) {
            metrics.recordFailure("http", copyFailure.getClass().getSimpleName());
            return observed(MESSAGE_PATH, "POST", startedNanos,
                    error(HttpStatus.INTERNAL_SERVER_ERROR, "Attachment copy failed"));
        }

        testRouteUsers.put(request.routingKey(), request.senderId());
        CompletableFuture<String> replyFuture = captureSender.register(messageId);
        InboundMessage inbound = new InboundMessage(
                request.routingKey(),
                content,
                messageId,
                messageId,
                request.senderId(),
                clock.millis(),
                false,
                null,
                Map.of()
        );
        try {
            dispatcher.dispatch(inbound).whenComplete((unused, failure) -> {
                if (failure != null) {
                    captureSender.fail(messageId, unwrap(failure));
                }
            });
            String reply = replyFuture.get(replyTimeout.toMillis(), TimeUnit.MILLISECONDS);
            SessionRoute session = sessionRoutingService.getOrCreate(request.routingKey());
            return observed(MESSAGE_PATH, "POST", startedNanos, ResponseEntity.ok(new TestResponse(
                    messageId,
                    reply,
                    session.activeSessionId(),
                    elapsedMillis(startedNanos),
                    List.of()
            )));
        } catch (TimeoutException timeout) {
            captureSender.cancel(messageId);
            metrics.recordFailure("http", timeout.getClass().getSimpleName());
            return observed(MESSAGE_PATH, "POST", startedNanos,
                    error(HttpStatus.INTERNAL_SERVER_ERROR, "Timed out waiting for Bot reply"));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            captureSender.cancel(messageId);
            metrics.recordFailure("http", interrupted.getClass().getSimpleName());
            return observed(MESSAGE_PATH, "POST", startedNanos,
                    error(HttpStatus.INTERNAL_SERVER_ERROR, "Request interrupted"));
        } catch (ExecutionException failure) {
            captureSender.cancel(messageId);
            metrics.recordFailure("http", unwrap(failure).getClass().getSimpleName());
            return observed(MESSAGE_PATH, "POST", startedNanos,
                    error(HttpStatus.INTERNAL_SERVER_ERROR, "Runner dispatch failed"));
        }
    }

    /**
     * 清理全部 TestAPI 路由、审计、已知 AgentScope state 和待完成回复。
     *
     * @return 固定 `{status: ok}` 响应
     */
    @DeleteMapping("/api/test/sessions")
    public ResponseEntity<Map<String, String>> deleteSessions() {
        long startedNanos = System.nanoTime();
        sessionRoutingService.clearAll(Map.copyOf(testRouteUsers));
        testRouteUsers.clear();
        captureSender.cancelAll();
        return observed(SESSIONS_PATH, "DELETE", startedNanos, ResponseEntity.ok(Map.of("status", "ok")));
    }

    /**
     * 将 Spring 无法解析的请求体转换为 Python 兼容 400 JSON。
     *
     * @return 固定 Invalid JSON body 错误
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> invalidJsonBody() {
        ResponseEntity<Map<String, Object>> response = error(HttpStatus.BAD_REQUEST, "Invalid JSON body");
        metrics.recordHttpRequest(MESSAGE_PATH, "POST", response.getStatusCode().value(), 0D);
        return response;
    }

    /** 记录固定路由、方法、状态码和请求耗时后原样返回响应。 */
    private <T> ResponseEntity<T> observed(
            String path,
            String method,
            long startedNanos,
            ResponseEntity<T> response
    ) {
        metrics.recordHttpRequest(path, method, response.getStatusCode().value(),
                Math.max(0D, (System.nanoTime() - startedNanos) / 1_000_000_000D));
        return response;
    }

    /**
     * 校验 Pydantic 模型中的必填字段和嵌套附件字段。
     */
    private static ResponseEntity<?> validate(TestRequest request) {
        if (request == null) {
            return unprocessable("body", "Field required");
        }
        if (request.routingKey() == null || request.routingKey().isBlank()) {
            return unprocessable("routing_key", "Field required");
        }
        if (request.senderId() == null || request.senderId().isBlank()) {
            return unprocessable("sender_id", "String should have at least 1 character");
        }
        if (request.attachment() != null
                && (request.attachment().filePath() == null || request.attachment().filePath().isBlank())) {
            return unprocessable("attachment.file_path", "Field required");
        }
        return null;
    }

    /**
     * 如有附件则复制到 session uploads 并生成与 Python 一致的沙盒路径提示。
     */
    private String prepareContent(TestRequest request) throws IOException {
        TestAttachment attachment = request.attachment();
        if (attachment == null) {
            return request.content();
        }
        Path source = Path.of(attachment.filePath());
        if (!Files.exists(source)) {
            return "（附件文件不存在：" + attachment.filePath() + "）";
        }
        SessionRoute session = sessionRoutingService.getOrCreate(request.routingKey());
        String targetName = attachment.fileName() == null ? source.getFileName().toString() : attachment.fileName();
        Path simpleName = Path.of(targetName).getFileName();
        if (simpleName == null || simpleName.toString().isBlank() || !simpleName.toString().equals(targetName)) {
            throw new IllegalArgumentException("附件目标文件名必须为单一文件名");
        }
        Path uploads = workspaceDirectory.resolve("sessions")
                .resolve(session.activeSessionId())
                .resolve("uploads")
                .normalize();
        Files.createDirectories(uploads);
        Files.copy(
                source,
                uploads.resolve(simpleName),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES
        );
        String sandboxPath = "/workspace/sessions/" + session.activeSessionId()
                + "/uploads/" + simpleName;
        String hint = "用户发来了文件，已自动保存至沙盒路径：\n`" + sandboxPath
                + "`\n请根据文件内容和用户意图完成相应处理。";
        if (!request.content().isEmpty()) {
            hint += "\n（用户备注：" + request.content() + "）";
        }
        return hint;
    }

    /**
     * 创建与 Pydantic 422 响应顶层结构一致的字段错误。
     */
    private static ResponseEntity<Map<String, Object>> unprocessable(String field, String message) {
        Map<String, Object> detail = Map.of(
                "type", "value_error",
                "loc", List.of(field.split("\\.")),
                "msg", message
        );
        return ResponseEntity.unprocessableEntity().body(Map.of("error", List.of(detail)));
    }

    /**
     * 创建不包含内部异常和敏感配置的统一错误响应。
     */
    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }

    /**
     * 生成与 Python `test_{uuid前12位}` 格式一致的随机消息标识。
     */
    private static String randomMessageId() {
        return "test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /**
     * 将单调时钟差转换为非负毫秒数。
     */
    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
    }

    /**
     * 校验配置超时为正数。
     */
    private static Duration positiveTimeout(Duration timeout) {
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("replyTimeout 必须大于零");
        }
        return timeout;
    }

    /**
     * 去除 CompletableFuture 的 CompletionException 包装。
     */
    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof java.util.concurrent.CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
