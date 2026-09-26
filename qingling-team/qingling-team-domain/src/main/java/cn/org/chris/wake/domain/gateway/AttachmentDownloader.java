package cn.org.chris.wake.domain.gateway;

import cn.org.chris.wake.domain.model.Attachment;

import java.util.concurrent.CompletableFuture;

/**
 * 定义外部消息附件的原始字节下载端口，避免应用层依赖具体飞书 SDK。
 */
@FunctionalInterface
public interface AttachmentDownloader {

    /**
     * 下载单个消息资源。
     *
     * @param messageId 来源消息标识
     * @param attachment 附件元数据
     * @return 下载字节；失败以异常 Future 表示
     */
    CompletableFuture<byte[]> download(String messageId, Attachment attachment);
}
