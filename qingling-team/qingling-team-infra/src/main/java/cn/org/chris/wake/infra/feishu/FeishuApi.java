package cn.org.chris.wake.infra.feishu;

import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.GetMessageResourceReq;
import com.lark.oapi.service.im.v1.model.PatchMessageReq;
import com.lark.oapi.service.im.v1.model.ReplyMessageReq;

/**
 * 隔离飞书 SDK 同步调用的最小端口，便于无网络契约测试。
 */
public interface FeishuApi {

    /** 发送新消息。 */
    MessageResult create(CreateMessageReq request) throws Exception;

    /** 回复指定消息或话题。 */
    MessageResult reply(ReplyMessageReq request) throws Exception;

    /** PATCH 更新已有卡片。 */
    ApiResult patch(PatchMessageReq request) throws Exception;

    /** 下载消息资源。 */
    ResourceResult download(GetMessageResourceReq request) throws Exception;

    /**
     * 表示不含业务数据的飞书调用结果。
     *
     * @param success SDK 是否判定调用成功
     * @param code 飞书响应码
     */
    record ApiResult(
            /** 调用是否成功。 */ boolean success,
            /** 飞书响应码，成功通常为 0。 */ int code
    ) {
    }

    /**
     * 表示发送或回复消息的飞书调用结果。
     *
     * @param success SDK 是否判定调用成功
     * @param code 飞书响应码
     * @param messageId 成功创建的消息标识
     */
    record MessageResult(
            /** 调用是否成功。 */ boolean success,
            /** 飞书响应码，成功通常为 0。 */ int code,
            /** 创建的消息标识，失败时可为空。 */ String messageId
    ) {
    }

    /**
     * 表示图片或文件资源下载结果。
     *
     * @param success SDK 是否判定调用成功
     * @param code 飞书响应码
     * @param bytes 下载的原始字节
     */
    record ResourceResult(
            /** 调用是否成功。 */ boolean success,
            /** 飞书响应码，成功通常为 0。 */ int code,
            /** 资源原始字节，失败时可为空。 */ byte[] bytes
    ) {
    }
}
