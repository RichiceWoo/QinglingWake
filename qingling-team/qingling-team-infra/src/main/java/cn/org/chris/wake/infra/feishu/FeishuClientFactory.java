package cn.org.chris.wake.infra.feishu;

import com.lark.oapi.Client;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageResp;
import com.lark.oapi.service.im.v1.model.GetMessageResourceReq;
import com.lark.oapi.service.im.v1.model.GetMessageResourceResp;
import com.lark.oapi.service.im.v1.model.PatchMessageReq;
import com.lark.oapi.service.im.v1.model.PatchMessageResp;
import com.lark.oapi.service.im.v1.model.ReplyMessageReq;
import com.lark.oapi.service.im.v1.model.ReplyMessageResp;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 集中创建飞书官方客户端，并关闭请求正文调试日志以避免凭据或消息内容泄漏。
 */
public final class FeishuClientFactory {

    /** 默认飞书 OpenAPI 请求超时，单位秒。 */
    private static final long DEFAULT_TIMEOUT_SECONDS = 30L;

    /**
     * 工具类不允许实例化。
     */
    private FeishuClientFactory() {
    }

    /**
     * 使用企业自建应用凭据创建官方 OpenAPI 客户端。
     *
     * @param appId 飞书应用标识
     * @param appSecret 飞书应用密钥
     * @return 禁止输出请求正文的 SDK 客户端
     */
    public static Client create(String appId, String appSecret) {
        requireCredential(appId, "appId");
        requireCredential(appSecret, "appSecret");
        return Client.newBuilder(appId, appSecret)
                .logReqAtDebug(false)
                .requestTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .source("qingling-team")
                .build();
    }

    /**
     * 把官方客户端包装为发送与下载共同使用的最小 API 端口。
     *
     * @param client 官方飞书客户端
     * @return 可测试的同步 API 端口
     */
    public static FeishuApi api(Client client) {
        return new SdkFeishuApi(Objects.requireNonNull(client, "client 不能为空"));
    }

    /**
     * 校验凭据存在，错误信息只包含配置项名称。
     */
    private static void requireCredential(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 未配置");
        }
    }

    /**
     * 官方 SDK 到项目最小飞书 API 契约的实现。
     */
    private static final class SdkFeishuApi implements FeishuApi {

        /** 官方飞书客户端。 */
        private final Client client;

        /**
         * 创建 SDK API 适配器。
         */
        private SdkFeishuApi(Client client) {
            this.client = client;
        }

        /**
         * 调用发送新消息接口。
         */
        @Override
        public MessageResult create(CreateMessageReq request) throws Exception {
            CreateMessageResp response = client.im().message().create(request);
            String messageId = response.getData() == null ? null : response.getData().getMessageId();
            return new MessageResult(response.success(), response.getCode(), messageId);
        }

        /**
         * 调用回复消息接口。
         */
        @Override
        public MessageResult reply(ReplyMessageReq request) throws Exception {
            ReplyMessageResp response = client.im().message().reply(request);
            String messageId = response.getData() == null ? null : response.getData().getMessageId();
            return new MessageResult(response.success(), response.getCode(), messageId);
        }

        /**
         * 调用 PATCH 更新消息接口。
         */
        @Override
        public ApiResult patch(PatchMessageReq request) throws Exception {
            PatchMessageResp response = client.im().message().patch(request);
            return new ApiResult(response.success(), response.getCode());
        }

        /**
         * 下载消息携带的图片或文件资源。
         */
        @Override
        public ResourceResult download(GetMessageResourceReq request) throws Exception {
            GetMessageResourceResp response = client.im().messageResource().get(request);
            byte[] bytes = response.getData() == null ? null : response.getData().toByteArray();
            return new ResourceResult(response.success(), response.getCode(), bytes);
        }
    }
}
