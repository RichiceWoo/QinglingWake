package cn.org.chris.wake.infra.feishu;

import cn.org.chris.wake.domain.model.Attachment;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.GetMessageResourceReq;
import com.lark.oapi.service.im.v1.model.PatchMessageReq;
import com.lark.oapi.service.im.v1.model.ReplyMessageReq;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证附件下载请求字段、字节返回和脱敏失败行为。
 */
class FeishuDownloaderTest {

    /**
     * 下载请求应携带 messageId、fileKey 和 image/file 类型。
     */
    @Test
    void shouldDownloadMessageResource() {
        DownloadApi api = new DownloadApi();
        FeishuDownloader downloader = new FeishuDownloader(api, Runnable::run);

        byte[] bytes = downloader.download(
                "om_1", new Attachment("file", "file_key", "报告.pdf")
        ).join();

        assertThat(bytes).containsExactly(1, 2, 3);
        assertThat(api.request.getMessageId()).isEqualTo("om_1");
        assertThat(api.request.getFileKey()).isEqualTo("file_key");
        assertThat(api.request.getType()).isEqualTo("file");
    }

    /**
     * API 失败只暴露响应码，不暴露服务端可能返回的敏感文本。
     */
    @Test
    void shouldFailWithSanitizedCode() {
        DownloadApi api = new DownloadApi();
        api.result = new FeishuApi.ResourceResult(false, 234001, null);
        FeishuDownloader downloader = new FeishuDownloader(api, Runnable::run);

        assertThatThrownBy(() -> downloader.download(
                "om_1", new Attachment("image", "img_key", "img.jpg")
        ).join()).hasMessageContaining("234001");
    }

    /**
     * 仅实现下载行为的测试 API。
     */
    private static final class DownloadApi implements FeishuApi {

        /** 最近一次资源下载请求。 */
        private GetMessageResourceReq request;

        /** 测试预设的资源响应。 */
        private ResourceResult result = new ResourceResult(true, 0, new byte[]{1, 2, 3});

        /**
         * 下载器测试不使用 create 接口。
         */
        @Override
        public MessageResult create(CreateMessageReq request) {
            throw new UnsupportedOperationException("未使用");
        }

        /**
         * 下载器测试不使用 reply 接口。
         */
        @Override
        public MessageResult reply(ReplyMessageReq request) {
            throw new UnsupportedOperationException("未使用");
        }

        /**
         * 下载器测试不使用 PATCH 接口。
         */
        @Override
        public ApiResult patch(PatchMessageReq request) {
            throw new UnsupportedOperationException("未使用");
        }

        /**
         * 捕获资源请求并返回预设结果。
         */
        @Override
        public ResourceResult download(GetMessageResourceReq request) {
            this.request = request;
            return result;
        }
    }
}
