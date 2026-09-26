package cn.org.chris.wake.adapter.api;

import cn.org.chris.wake.domain.gateway.SenderGateway;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 测试模式 Sender：不访问飞书，把最终回复完成到 msgId 对应的 Future。
 */
public final class CaptureSender implements SenderGateway {

    /** Loading 卡片测试标识前缀。 */
    private static final String THINKING_CARD_PREFIX = "test-card-thinking-";

    /** 消息标识到待完成回复 Future 的并发映射。 */
    private final Map<String, CompletableFuture<String>> replies = new ConcurrentHashMap<>();

    /** 卡片标识到来源消息标识的并发映射。 */
    private final Map<String, String> cardRoots = new ConcurrentHashMap<>();

    /**
     * 注册待捕获消息；重复注册会让旧等待者失败，避免永久悬挂。
     *
     * @param messageId TestAPI 消息标识
     * @return 最终回复 Future
     */
    public CompletableFuture<String> register(String messageId) {
        String id = requireText(messageId, "messageId");
        CompletableFuture<String> future = new CompletableFuture<>();
        CompletableFuture<String> previous = replies.put(id, future);
        if (previous != null) {
            previous.completeExceptionally(new IllegalStateException("消息标识被重复注册"));
        }
        future.whenComplete((unused, failure) -> replies.remove(id, future));
        return future;
    }

    /**
     * 返回带调用方超时限制的回复 Future。
     *
     * @param messageId 已注册消息标识
     * @param timeout 最长等待时间
     * @return 捕获回复 Future
     */
    public CompletableFuture<String> waitForReply(String messageId, Duration timeout) {
        CompletableFuture<String> future = replies.get(requireText(messageId, "messageId"));
        if (future == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("msg_id 未注册"));
        }
        Duration wait = Objects.requireNonNull(timeout, "timeout 不能为空");
        return future.orTimeout(wait.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * 取消超时或失败请求并释放注册项。
     *
     * @param messageId 待取消消息标识
     */
    public void cancel(String messageId) {
        CompletableFuture<String> future = replies.remove(messageId);
        if (future != null) {
            future.cancel(false);
        }
    }

    /**
     * 取消全部未完成请求，供 TestAPI 清理端点释放等待者。
     */
    public void cancelAll() {
        replies.keySet().forEach(this::cancel);
        cardRoots.clear();
    }

    /**
     * 让 Runner 的异步失败立即传递给正在等待的 TestAPI 请求。
     *
     * @param messageId 来源消息标识
     * @param failure Runner 失败原因
     */
    public void fail(String messageId, Throwable failure) {
        CompletableFuture<String> future = replies.get(messageId);
        if (future != null) {
            future.completeExceptionally(failure);
        }
    }

    /**
     * 捕获普通最终回复。
     */
    @Override
    public CompletableFuture<Void> send(String routingKey, String content, String rootId) {
        complete(rootId, content);
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 返回与 rootId 一一对应的虚拟 Loading 卡片标识。
     */
    @Override
    public CompletableFuture<String> sendThinking(String routingKey, String rootId) {
        String root = requireText(rootId, "rootId");
        String cardId = THINKING_CARD_PREFIX + root;
        cardRoots.put(cardId, root);
        return CompletableFuture.completedFuture(cardId);
    }

    /**
     * 通过虚拟卡片关联完成原消息的回复 Future。
     */
    @Override
    public CompletableFuture<Void> updateCard(String cardMessageId, String content) {
        String rootId = cardRoots.remove(cardMessageId);
        if (rootId != null) {
            complete(rootId, content);
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 捕获 Slash Command 或错误处理产生的纯文本回复。
     */
    @Override
    public CompletableFuture<Void> sendText(String routingKey, String content, String rootId) {
        complete(rootId, content);
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 返回当前仍在等待的回复数量，供测试和生命周期检查使用。
     *
     * @return 待回复数量
     */
    public int pendingCount() {
        return replies.size();
    }

    /**
     * 完成指定来源消息的回复；未知或已结束消息静默忽略。
     */
    private void complete(String messageId, String content) {
        if (messageId == null) {
            return;
        }
        CompletableFuture<String> future = replies.get(messageId);
        if (future != null) {
            future.complete(Objects.requireNonNullElse(content, ""));
        }
    }

    /**
     * 校验捕获关联键非空。
     */
    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value;
    }
}
