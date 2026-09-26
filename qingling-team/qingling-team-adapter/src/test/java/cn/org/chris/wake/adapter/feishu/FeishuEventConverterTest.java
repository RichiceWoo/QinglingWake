package cn.org.chris.wake.adapter.feishu;

import cn.org.chris.wake.domain.model.InboundMessage;
import com.lark.oapi.channel.model.NormalizedMessage;
import com.lark.oapi.channel.model.ResourceDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证飞书归一化事件到领域消息的转换和白名单契约。
 */
class FeishuEventConverterTest {

    /**
     * 单聊必须绕过群白名单，并回退使用当前消息作为 root。
     */
    @Test
    void shouldAlwaysAllowP2p() {
        FeishuEventConverter converter = new FeishuEventConverter(Set.of("oc_allowed"));

        InboundMessage inbound = converter.convert(message(
                "om_1", "oc_other", "p2p", "ou_user", "你好", "text", List.of(), "", "", 123L
        )).orElseThrow();

        assertThat(inbound.routingKey()).isEqualTo("p2p:ou_user");
        assertThat(inbound.rootId()).isEqualTo("om_1");
        assertThat(inbound.content()).isEqualTo("你好");
        assertThat(inbound.meta()).containsEntry("source", "feishu");

        InboundMessage post = converter.convert(message(
                "om_post", "oc_other", "p2p", "ou_user", "标题\n正文", "post", List.of(), "", "", 124L
        )).orElseThrow();
        assertThat(post.content()).isEqualTo("标题\n正文");
        assertThat(post.meta()).containsEntry("messageType", "post");
    }

    /**
     * 群消息只允许白名单 chatId，话题消息应保留 thread 路由和根消息。
     */
    @Test
    void shouldFilterGroupAndBuildThreadRoute() {
        FeishuEventConverter converter = new FeishuEventConverter(Set.of("oc_allowed"));

        assertThat(converter.convert(message(
                "om_blocked", "oc_blocked", "group", "ou_user", "", "text", List.of(), "", "", 1L
        ))).isEmpty();

        InboundMessage inbound = converter.convert(message(
                "om_2", "oc_allowed", "group", "ou_user", "", "image",
                List.of(new ResourceDescriptor("image", "img_key", "", null)),
                "om_root", "omt_thread", 456L
        )).orElseThrow();

        assertThat(inbound.routingKey()).isEqualTo("thread:oc_allowed:omt_thread");
        assertThat(inbound.rootId()).isEqualTo("om_root");
        assertThat(inbound.attachment().msgType()).isEqualTo("image");
        assertThat(inbound.attachment().fileName()).isEqualTo("img_key.jpg");
    }

    /**
     * file 资源应保留 SDK 归一化后的文件名和 key。
     */
    @Test
    void shouldConvertFileResource() {
        FeishuEventConverter converter = new FeishuEventConverter(Set.of());

        InboundMessage inbound = converter.convert(message(
                "om_3", "oc_group", "group", "ou_user", "说明", "file",
                List.of(new ResourceDescriptor("file", "file_key", "报告.pdf", 10L)),
                "", "", 789L
        )).orElseThrow();

        assertThat(inbound.routingKey()).isEqualTo("group:oc_group");
        assertThat(inbound.attachment().fileKey()).isEqualTo("file_key");
        assertThat(inbound.attachment().fileName()).isEqualTo("报告.pdf");
    }

    /**
     * 构造测试使用的 SDK 归一化消息。
     */
    private static NormalizedMessage message(
            String messageId,
            String chatId,
            String chatType,
            String senderId,
            String content,
            String rawType,
            List<ResourceDescriptor> resources,
            String rootId,
            String threadId,
            long createTime
    ) {
        return new NormalizedMessage(
                messageId, chatId, chatType, senderId, "用户", content, rawType,
                resources, List.of(), false, false, rootId, threadId, "", createTime, null
        );
    }
}
