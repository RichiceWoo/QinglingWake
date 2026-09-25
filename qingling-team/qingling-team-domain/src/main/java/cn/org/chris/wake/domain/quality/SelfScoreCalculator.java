package cn.org.chris.wake.domain.quality;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 计算五维任务自评，并从 Agent 输出中的 JSON 对象提取自评结果。
 */
public final class SelfScoreCalculator {

    /** 五个必需维度及其默认权重，权重总和为 1。 */
    public static final Map<String, Double> DEFAULT_WEIGHTS = Map.of(
            "completeness", 0.20,
            "self_review", 0.30,
            "hard_constraints", 0.20,
            "clarity", 0.15,
            "timeliness", 0.15
    );

    /** 计算时必须同时提供的五个维度。 */
    public static final Set<String> REQUIRED_DIMENSIONS = DEFAULT_WEIGHTS.keySet();

    /** JSON 数值语法，用于严格提取 self_score 和 breakdown 数值。 */
    private static final String JSON_NUMBER = "-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?";

    /** 定位根 JSON 对象中的 self_score 数值。 */
    private static final Pattern SELF_SCORE_PATTERN = Pattern.compile(
            "\\\"self_score\\\"\\s*:\\s*(" + JSON_NUMBER + ")"
    );

    /** 定位根 JSON 对象中的 breakdown 字段。 */
    private static final Pattern BREAKDOWN_KEY_PATTERN = Pattern.compile("\\\"breakdown\\\"\\s*:\\s*\\{");

    /** 提取 breakdown 中的数值键值对。 */
    private static final Pattern NUMBER_ENTRY_PATTERN = Pattern.compile(
            "\\\"([^\\\"]+)\\\"\\s*:\\s*(" + JSON_NUMBER + ")"
    );

    /**
     * 工具类不允许实例化。
     */
    private SelfScoreCalculator() {
    }

    /**
     * 使用默认权重计算总分，并以 HALF_UP 保留两位小数。
     *
     * @param breakdown 五维分数，所有值必须位于 0 到 1
     * @return 0 到 1 的两位小数总分
     */
    public static double compute(Map<String, ? extends Number> breakdown) {
        return compute(breakdown, DEFAULT_WEIGHTS);
    }

    /**
     * 使用指定权重计算总分；必需维度缺失或任一输入越界时拒绝。
     *
     * @param breakdown 维度分数
     * @param weights 五个必需维度的权重
     * @return HALF_UP 保留两位小数的总分
     */
    public static double compute(
            Map<String, ? extends Number> breakdown,
            Map<String, ? extends Number> weights
    ) {
        if (breakdown == null) {
            throw new IllegalArgumentException("breakdown 不能为空");
        }
        Set<String> missing = new java.util.HashSet<>(REQUIRED_DIMENSIONS);
        missing.removeAll(breakdown.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("missing dimensions: " + missing);
        }
        for (Map.Entry<String, ? extends Number> entry : breakdown.entrySet()) {
            double value = numericValue(entry.getValue(), "dim " + entry.getKey());
            if (value < 0.0 || value > 1.0) {
                throw new IllegalArgumentException(
                        "dim " + entry.getKey() + "=" + value + " out of range [0, 1]"
                );
            }
        }
        BigDecimal total = BigDecimal.ZERO;
        for (String dimension : REQUIRED_DIMENSIONS) {
            Number weight = weights == null ? null : weights.get(dimension);
            if (weight == null) {
                throw new IllegalArgumentException("missing weight: " + dimension);
            }
            BigDecimal scoreValue = BigDecimal.valueOf(breakdown.get(dimension).doubleValue());
            BigDecimal weightValue = BigDecimal.valueOf(weight.doubleValue());
            total = total.add(scoreValue.multiply(weightValue));
        }
        return total.setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * 优先从结构化输出提取；缺失时再扫描 raw 中含 self_score 的平衡 JSON 对象。
     *
     * @param jsonOutput Agent 已解析的 JSON 输出，可为空
     * @param raw Agent 原始文本，可为空
     * @return 提取结果；找不到合法结构时两个字段均为空
     */
    public static Extraction extract(Map<String, ?> jsonOutput, String raw) {
        if (jsonOutput != null && jsonOutput.containsKey("self_score")) {
            return extractFromMap(jsonOutput);
        }
        if (raw == null || raw.isEmpty()) {
            return Extraction.empty();
        }
        for (int index = 0; index < raw.length(); index++) {
            if (raw.charAt(index) != '{') {
                continue;
            }
            String candidate = extractBalancedObject(raw, index);
            if (candidate == null || !candidate.contains("\"self_score\"")) {
                continue;
            }
            Extraction parsed = parseJsonCandidate(candidate);
            if (parsed.score() != null) {
                return parsed;
            }
        }
        return Extraction.empty();
    }

    /**
     * 从已解析 Map 读取 self_score 和可选 breakdown。
     *
     * @param data 结构化输出
     * @return 提取结果
     */
    private static Extraction extractFromMap(Map<String, ?> data) {
        if (data == null || !data.containsKey("self_score")) {
            return Extraction.empty();
        }
        Double score = nullableNumericValue(data.get("self_score"));
        if (score == null) {
            return Extraction.empty();
        }
        Object rawBreakdown = data.get("breakdown");
        Map<String, Object> breakdown = rawBreakdown instanceof Map<?, ?> map ? stringKeyMap(map) : null;
        return new Extraction(score, breakdown);
    }

    /**
     * 从固定 JSON 契约对象中解析自评数值和 breakdown。
     *
     * @param candidate 单个平衡 JSON 对象
     * @return 解析结果
     */
    private static Extraction parseJsonCandidate(String candidate) {
        Matcher scoreMatcher = SELF_SCORE_PATTERN.matcher(candidate);
        if (!scoreMatcher.find()) {
            return Extraction.empty();
        }
        Double score = nullableNumericValue(scoreMatcher.group(1));
        Matcher breakdownMatcher = BREAKDOWN_KEY_PATTERN.matcher(candidate);
        if (!breakdownMatcher.find()) {
            return new Extraction(score, null);
        }
        int openingBrace = candidate.indexOf('{', breakdownMatcher.start());
        String breakdownObject = extractBalancedObject(candidate, openingBrace);
        if (breakdownObject == null) {
            return Extraction.empty();
        }
        Map<String, Object> breakdown = new LinkedHashMap<>();
        Matcher entryMatcher = NUMBER_ENTRY_PATTERN.matcher(breakdownObject);
        while (entryMatcher.find()) {
            breakdown.put(entryMatcher.group(1), Double.valueOf(entryMatcher.group(2)));
        }
        return new Extraction(score, Map.copyOf(breakdown));
    }

    /**
     * 从指定左花括号开始提取平衡对象，并正确忽略字符串中的括号和转义字符。
     *
     * @param text 原文本
     * @param start 左花括号位置
     * @return 平衡对象文本；不完整时为空
     */
    private static String extractBalancedObject(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = start; index < text.length(); index++) {
            char current = text.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"') {
                inString = true;
            } else if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return text.substring(start, index + 1);
            }
        }
        return null;
    }

    /**
     * 将任意键 Map 转为字符串键的只读浅拷贝。
     *
     * @param source 原 Map
     * @return 字符串键 Map
     */
    private static Map<String, Object> stringKeyMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return Map.copyOf(result);
    }

    /**
     * 将 Number 或数值字符串转换为有限 double，不合法时返回空。
     *
     * @param value 原始值
     * @return 有限数值或 null
     */
    private static Double nullableNumericValue(Object value) {
        try {
            double number = value instanceof Number numeric
                    ? numeric.doubleValue()
                    : Double.parseDouble(String.valueOf(value));
            return Double.isFinite(number) ? number : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /**
     * 将必需数值转换为有限 double。
     *
     * @param value 原始数值
     * @param field 字段名称
     * @return 有限 double
     */
    private static double numericValue(Number value, String field) {
        if (value == null || !Double.isFinite(value.doubleValue())) {
            throw new IllegalArgumentException(field + " 必须是有限数值");
        }
        return value.doubleValue();
    }

    /**
     * 表示从 Agent 输出提取出的自评；缺失时 score 和 breakdown 均为空。
     *
     * @param score 自评总分，可为空
     * @param breakdown 维度明细，可为空
     */
    public record Extraction(
            /** 自评总分，可为空。 */ Double score,
            /** 维度明细只读浅拷贝，可为空。 */ Map<String, Object> breakdown
    ) {
        /**
         * 创建找不到合法自评时的空结果。
         *
         * @return 两字段均为空的结果
         */
        public static Extraction empty() {
            return new Extraction(null, null);
        }
    }
}
