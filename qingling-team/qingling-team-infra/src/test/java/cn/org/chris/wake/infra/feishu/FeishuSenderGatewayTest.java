package cn.org.chris.wake.infra.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.GetMessageResourceReq;
import com.lark.oapi.service.im.v1.model.PatchMessageReq;
import com.lark.oapi.service.im.v1.model.ReplyMessageReq;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证飞书 Sender 的路由、消息格式、卡片 PATCH 与退避重试契约。
 */
class FeishuSenderGatewayTest {

    /** JSON 测试解析器。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * p2p 与 group 应分别使用 open_id 和 chat_id 创建卡片或文本消息。
     */
    @Test
    void shouldCreateP2pCardAndGroupText() throws Exception {
        FakeApi api = new FakeApi();
        FeishuSenderGateway sender = sender(api, List.of());

        sender.send("p2p:ou_user", "**完成**", "om_root").join();
        CreateMessageReq cardRequest = api.createRequests.get(0);
        JsonNode card = objectMapper.readTree(cardRequest.getCreateMessageReqBody().getContent());

        assertThat(cardRequest.getReceiveIdType()).isEqualTo("open_id");
        assertThat(cardRequest.getCreateMessageReqBody().getReceiveId()).isEqualTo("ou_user");
        assertThat(cardRequest.getCreateMessageReqBody().getMsgType()).isEqualTo("interactive");
        assertThat(card.at("/elements/0/text/content").asText()).isEqualTo("**完成**");

        sender.sendText("group:oc_group", "收到", "om_root").join();
        CreateMessageReq textRequest = api.createRequests.get(1);
        assertThat(textRequest.getReceiveIdType()).isEqualTo("chat_id");
        assertThat(textRequest.getCreateMessageReqBody().getReceiveId()).isEqualTo("oc_group");
        assertThat(objectMapper.readTree(textRequest.getCreateMessageReqBody().getContent()).get("text").asText())
                .isEqualTo("收到");
    }

    /**
     * thread 路由应回复 root 并开启 reply_in_thread。
     */
    @Test
    void shouldReplyInsideThread() {
        FakeApi api = new FakeApi();
        FeishuSenderGateway sender = sender(api, List.of());

        sender.send("thread:oc_group:omt_thread", "结果", "om_root").join();

        ReplyMessageReq request = api.replyRequests.get(0);
        assertThat(request.getMessageId()).isEqualTo("om_root");
        assertThat(request.getReplyMessageReqBody().getReplyInThread()).isTrue();
        assertThat(request.getReplyMessageReqBody().getMsgType()).isEqualTo("interactive");
    }

    /**
     * Loading 返回消息标识后，最终内容应通过 PATCH 更新同一张卡片。
     */
    @Test
    void shouldSendThinkingAndPatchCard() throws Exception {
        FakeApi api = new FakeApi();
        api.messageId = "om_card";
        FeishuSenderGateway sender = sender(api, List.of());

        assertThat(sender.sendThinking("p2p:ou_user", "om_root").join()).isEqualTo("om_card");
        sender.updateCard("om_card", "最终答案").join();

        PatchMessageReq patch = api.patchRequests.get(0);
        assertThat(patch.getMessageId()).isEqualTo("om_card");
        assertThat(objectMapper.readTree(patch.getPatchMessageReqBody().getContent())
                .at("/elements/0/text/content").asText()).isEqualTo("最终答案");
    }

    /**
     * 连续失败应准确等待 1/2/4 秒并在第四次调用成功。
     */
    @Test
    void shouldRetryWithOneTwoFourSecondBackoff() {
        FakeApi api = new FakeApi();
        api.createFailuresRemaining = 3;
        List<Duration> waits = new ArrayList<>();
        FeishuSenderGateway sender = new FeishuSenderGateway(
                api, objectMapper, Runnable::run, waits::add,
                List.of(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4))
        );

        sender.sendText("p2p:ou_user", "重试", "om_root").join();

        assertThat(api.createCalls).isEqualTo(4);
        assertThat(waits).containsExactly(
                Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4)
        );
    }

    /**
     * 最终失败异常只能包含操作和响应码，不得携带 SDK 服务端敏感消息。
     */
    @Test
    void shouldExposeSanitizedFailure() {
        FakeApi api = new FakeApi();
        api.throwingSecret = true;
        FeishuSenderGateway sender = sender(api, List.of());

        assertThatThrownBy(() -> sender.send("p2p:ou_user", "内容", "om_root").join())
                .hasMessageNotContaining("app-secret-value")
                .hasRootCauseInstanceOf(FeishuOperationException.class);
    }

    /**
     * 创建使用直接执行器的无网络 Sender。
     */
    private FeishuSenderGateway sender(FakeApi api, List<Duration> backoffs) {
        return new FeishuSenderGateway(api, objectMapper, Runnable::run, ignored -> { }, backoffs);
    }

    /**
     * 捕获 SDK 请求并按测试配置返回结果。
     */
    private static final class FakeApi implements FeishuApi {

        /** 捕获的新消息请求。 */
        private final List<CreateMessageReq> createRequests = new ArrayList<>();

        /** 捕获的话题回复请求。 */
        private final List<ReplyMessageReq> replyRequests = new ArrayList<>();

        /** 捕获的卡片更新请求。 */
        private final List<PatchMessageReq> patchRequests = new ArrayList<>();

        /** 成功发送时返回的消息标识。 */
        private String messageId = "om_created";

        /** 创建接口在成功前剩余的业务失败次数。 */
        private int createFailuresRemaining;

        /** 创建接口总调用次数。 */
        private int createCalls;

        /** 是否抛出包含模拟密钥的底层异常。 */
        private boolean throwingSecret;

        /**
         * 捕获 create 请求并按配置模拟失败或成功。
         */
        @Override
        public MessageResult create(CreateMessageReq request) {
            createCalls++;
            createRequests.add(request);
            if (throwingSecret) {
                throw new IllegalStateException("app-secret-value");
            }
            if (createFailuresRemaining-- > 0) {
                return new MessageResult(false, 999, null);
            }
            return new MessageResult(true, 0, messageId);
        }

        /**
         * 捕获 reply 请求并返回成功。
         */
        @Override
        public MessageResult reply(ReplyMessageReq request) {
            replyRequests.add(request);
            return new MessageResult(true, 0, messageId);
        }

        /**
         * 捕获 PATCH 请求并返回成功。
         */
        @Override
        public ApiResult patch(PatchMessageReq request) {
            patchRequests.add(request);
            return new ApiResult(true, 0);
        }

        /**
         * Sender 测试不使用下载接口。
         */
        @Override
        public ResourceResult download(GetMessageResourceReq request) {
            throw new UnsupportedOperationException("未使用");
        }
    }
}
