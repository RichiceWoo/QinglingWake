package cn.org.chris.wake.domain.model;

import java.util.Objects;

/**
 * 表示由接入层解析、随后由 Runner 下载的飞书附件元信息。
 *
 * @param msgType 附件消息类型，只接受 image 或 file
 * @param fileKey 飞书 file_key 或 image_key
 * @param fileName 附件落盘时使用的文件名
 */
public record Attachment(
        /** 附件消息类型。 */ String msgType,
        /** 飞书侧文件标识。 */ String fileKey,
        /** 安全化之前的原始文件名。 */ String fileName
) {
    /**
     * 校验附件数据，避免非法类型进入下载流程。
     */
    public Attachment {
        if (!"image".equals(msgType) && !"file".equals(msgType)) {
            throw new IllegalArgumentException("msgType 必须是 image 或 file");
        }
        Objects.requireNonNull(fileKey, "fileKey 不能为空");
        Objects.requireNonNull(fileName, "fileName 不能为空");
    }
}
