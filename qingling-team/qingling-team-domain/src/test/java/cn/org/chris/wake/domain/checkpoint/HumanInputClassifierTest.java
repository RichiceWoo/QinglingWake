package cn.org.chris.wake.domain.checkpoint;

import cn.org.chris.wake.domain.model.PendingCheckpoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 覆盖 Python `test_feishu_bridge.py` 的输入分类优先级和 checkpoint 绑定场景。
 */
class HumanInputClassifierTest {

    /**
     * 飞书 callback 必须无条件优先于文本和 pending 判断。
     */
    @Test
    void shouldPrioritizeCallbackCheckpointId() {
        HumanInputClassifier.Classification result = HumanInputClassifier.classify(
                "帮我做一个新项目",
                Map.of("checkpoint_id", "ckpt-12345678"),
                List.of()
        );

        assertThat(result.category()).isEqualTo(HumanInputClassifier.Category.CHECKPOINT_RESPONSE);
        assertThat(result.checkpointId()).isEqualTo("ckpt-12345678");
    }

    /**
     * 文本中的显式 checkpoint id 只有属于当前路由 pending 时才可绑定。
     */
    @Test
    void shouldBindOnlyKnownExplicitCheckpointId() {
        PendingCheckpoint pending = checkpoint("ckpt-abcdef12", 100L);

        assertThat(HumanInputClassifier.classify(
                "同意 ckpt-abcdef12", null, List.of(pending)
        ).checkpointId()).isEqualTo("ckpt-abcdef12");
        assertThat(HumanInputClassifier.classify(
                "ckpt-deadbeef", null, List.of(pending)
        ).category()).isEqualTo(HumanInputClassifier.Category.NEED_DISCUSSION);
    }

    /**
     * 无显式 id 的批准或拒绝词应绑定创建时间最新的 pending，平票取列表末尾。
     */
    @Test
    void shouldBindDecisionTokenToLatestPending() {
        PendingCheckpoint first = checkpoint("ckpt-11111111", 100L);
        PendingCheckpoint second = checkpoint("ckpt-22222222", 200L);
        PendingCheckpoint tiedLast = checkpoint("ckpt-33333333", 200L);

        HumanInputClassifier.Classification result = HumanInputClassifier.classify(
                "同意", null, List.of(first, second, tiedLast)
        );

        assertThat(result.category()).isEqualTo(HumanInputClassifier.Category.CHECKPOINT_RESPONSE);
        assertThat(result.checkpointId()).isEqualTo("ckpt-33333333");
    }

    /**
     * 关键词分类必须保持 SOP、新需求、澄清及兜底的 Python 顺序。
     *
     * @param text 输入文本
     * @param expected 预期分类枚举名
     */
    @ParameterizedTest
    @CsvSource({
            "'我们来聊下 SOP 流程吧',SOP_COCREATE",
            "'帮我做一个小爪子日记 CRUD 网站',NEW_REQUIREMENT",
            "'这是对上个问题的回答',CLARIFICATION_ANSWER",
            "'你好',NEED_DISCUSSION",
            "'',NEED_DISCUSSION"
    })
    void shouldClassifyKeywordsAndFallback(String text, String expected) {
        HumanInputClassifier.Classification result = HumanInputClassifier.classify(text, null, List.of());

        assertThat(result.category()).isEqualTo(HumanInputClassifier.Category.valueOf(expected));
        assertThat(result.checkpointId()).isNull();
    }

    /**
     * 构造分类测试使用的 pending checkpoint。
     *
     * @param checkpointId checkpoint 标识
     * @param createdAtMs 创建时间
     * @return 测试 checkpoint
     */
    private static PendingCheckpoint checkpoint(String checkpointId, long createdAtMs) {
        return new PendingCheckpoint(checkpointId, "p2p:abc123", "p1", "checkpoint_request", "q?", createdAtMs);
    }
}
