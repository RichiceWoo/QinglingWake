package cn.org.chris.wake.infra.feishu;

import cn.org.chris.wake.domain.gateway.AttachmentDownloader;
import cn.org.chris.wake.domain.model.Attachment;
import com.lark.oapi.Client;
import com.lark.oapi.service.im.v1.model.GetMessageResourceReq;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * 通过飞书消息资源接口下载附件字节，实际安全落盘由应用层附件服务负责。
 */
public final class FeishuDownloader implements AttachmentDownloader {

    /** 可替换的飞书 API 端口。 */
    private final FeishuApi api;

    /** 执行阻塞 SDK 调用的线程池。 */
    private final Executor executor;

    /**
     * 使用官方客户端创建附件下载器。
     *
     * @param client 官方飞书客户端
     */
    public FeishuDownloader(Client client) {
        this(FeishuClientFactory.api(client), ForkJoinPool.commonPool());
    }

    /**
     * 使用可测试 API 和执行器创建下载器。
     *
     * @param api 飞书 API 端口
     * @param executor 阻塞 I/O 执行器
     */
    public FeishuDownloader(FeishuApi api, Executor executor) {
        this.api = Objects.requireNonNull(api, "api 不能为空");
        this.executor = Objects.requireNonNull(executor, "executor 不能为空");
    }

    /**
     * 下载图片或文件，API 失败时以不含服务端消息的异常结束 Future。
     */
    @Override
    public CompletableFuture<byte[]> download(String messageId, Attachment attachment) {
        Objects.requireNonNull(attachment, "attachment 不能为空");
        if (messageId == null || messageId.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("messageId 不能为空"));
        }
        return CompletableFuture.supplyAsync(() -> {
            GetMessageResourceReq request = GetMessageResourceReq.newBuilder()
                    .messageId(messageId)
                    .fileKey(attachment.fileKey())
                    .type(attachment.msgType())
                    .build();
            try {
                FeishuApi.ResourceResult result = api.download(request);
                if (!result.success() || result.bytes() == null) {
                    throw new FeishuOperationException("飞书附件下载失败", result.code());
                }
                return result.bytes().clone();
            } catch (FeishuOperationException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new FeishuOperationException("飞书附件下载异常", -1, failure);
            }
        }, executor);
    }
}
