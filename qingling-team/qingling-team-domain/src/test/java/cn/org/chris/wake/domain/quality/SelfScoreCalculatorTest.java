package cn.org.chris.wake.domain.quality;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 覆盖 Python `test_self_score.py` 的五维计算、舍入、校验和提取场景。
 */
class SelfScoreCalculatorTest {

    /**
     * 默认五维权重应合计为 1，0.895 必须按 HALF_UP 得到 0.90。
     */
    @Test
    void shouldComputeFiveDimensionWeightedScore() {
        Map<String, Double> breakdown = Map.of(
                "completeness", 1.0,
                "self_review", 0.9,
                "hard_constraints", 1.0,
                "clarity", 0.7,
                "timeliness", 0.8
        );

        assertThat(SelfScoreCalculator.DEFAULT_WEIGHTS.values().stream().mapToDouble(Double::doubleValue).sum())
                .isEqualTo(1.0);
        assertThat(SelfScoreCalculator.compute(breakdown)).isEqualTo(0.90);
    }

    /**
     * 全满分应保持 1.0 且结果最多两位小数。
     */
    @Test
    void shouldKeepFullScoreWithinTwoDecimals() {
        assertThat(SelfScoreCalculator.compute(Map.of(
                "completeness", 1.0,
                "self_review", 1.0,
                "hard_constraints", 1.0,
                "clarity", 1.0,
                "timeliness", 1.0
        ))).isEqualTo(1.0);
    }

    /**
     * 缺失必需维度和任一越界值都必须拒绝。
     */
    @Test
    void shouldRejectMissingOrOutOfRangeDimensions() {
        assertThatThrownBy(() -> SelfScoreCalculator.compute(Map.of("completeness", 1.0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
        assertThatThrownBy(() -> SelfScoreCalculator.compute(Map.of(
                "completeness", 1.5,
                "self_review", 1.0,
                "hard_constraints", 1.0,
                "clarity", 1.0,
                "timeliness", 1.0
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("range");
    }

    /**
     * 原始文本中的平衡 JSON 块应提取总分和 breakdown。
     */
    @Test
    void shouldExtractFromRawJsonBlock() {
        String raw = """
                产品设计完成。产出摘要：
                {
                  "self_score": 0.85,
                  "breakdown": {
                    "completeness": 1.0,
                    "self_review": 0.9,
                    "hard_constraints": 1.0,
                    "clarity": 0.6,
                    "timeliness": 0.8
                  },
                  "rationale": "包含 {花括号} 也不影响解析"
                }
                """;

        SelfScoreCalculator.Extraction result = SelfScoreCalculator.extract(null, raw);

        assertThat(result.score()).isEqualTo(0.85);
        assertThat(result.breakdown()).containsEntry("completeness", 1.0);
    }

    /**
     * 结构化 json_output 优先于 raw，breakdown 非 Map 时按空明细处理。
     */
    @Test
    void shouldPrioritizeStructuredOutput() {
        SelfScoreCalculator.Extraction result = SelfScoreCalculator.extract(
                Map.of("self_score", 0.6, "breakdown", Map.of("self_review", 0.5)),
                "{\"self_score\":0.9}"
        );

        assertThat(result.score()).isEqualTo(0.6);
        assertThat(result.breakdown()).containsEntry("self_review", 0.5);
    }

    /**
     * 结构化输出已声明 self_score 但数值非法时，不得回退到 raw 掩盖错误。
     */
    @Test
    void shouldNotFallbackWhenStructuredScoreIsInvalid() {
        SelfScoreCalculator.Extraction result = SelfScoreCalculator.extract(
                Map.of("self_score", "invalid"),
                "{\"self_score\":0.9}"
        );

        assertThat(result.score()).isNull();
        assertThat(result.breakdown()).isNull();
    }

    /**
     * 未包含合法 self_score 结构时返回两个空字段。
     */
    @Test
    void shouldReturnEmptyWhenScoreIsMissing() {
        SelfScoreCalculator.Extraction result = SelfScoreCalculator.extract(
                null, "产品设计完成，已写入 design/product_spec.md"
        );

        assertThat(result.score()).isNull();
        assertThat(result.breakdown()).isNull();
    }
}
