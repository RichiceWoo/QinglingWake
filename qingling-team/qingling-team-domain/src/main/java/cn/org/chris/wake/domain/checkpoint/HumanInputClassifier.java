package cn.org.chris.wake.domain.checkpoint;

import cn.org.chris.wake.domain.model.PendingCheckpoint;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按 Python 飞书桥接规则为人类输入分类，并在需要时绑定 pending checkpoint。
 */
public final class HumanInputClassifier {

    /** 文本中允许识别的显式 checkpoint 标识格式。 */
    private static final Pattern CHECKPOINT_ID_PATTERN = Pattern.compile("ckpt-[0-9a-f]{8}");

    /** 识别新需求的中英文关键词。 */
    private static final Set<String> NEW_REQUIREMENT_KEYWORDS = Set.of(
            "帮我做", "做一个", "帮我写", "搭一个", "搭个", "实现", "开发一个", "建一个",
            "构建", "需求", "新项目", "新需求", "build", "create", "implement", "new project", "new feature"
    );

    /** 识别 SOP 共创的中英文关键词。 */
    private static final Set<String> SOP_KEYWORDS = Set.of(
            "sop", "流程", "标准", "工作流", "规范", "workflow"
    );

    /** 识别澄清回答的中英文关键词。 */
    private static final Set<String> CLARIFICATION_KEYWORDS = Set.of(
            "答复", "回答", "澄清", "答案", "answer"
    );

    /** 有 pending 时可直接绑定最新项的决策词。 */
    private static final Set<String> DECISION_TOKENS = Set.of(
            "同意", "批准", "approve", "yes", "确认", "ok", "拒绝", "不同意", "reject", "no"
    );

    /**
     * 工具类不允许实例化。
     */
    private HumanInputClassifier() {
    }

    /**
     * 按固定优先级分类输入：callback、显式 id、最新 pending 决策词、关键词和兜底。
     *
     * @param text 人类输入，可为空
     * @param callbackValue 飞书卡片 callback 数据，可为空
     * @param pendingForRoutingKey 当前 routing key 的 pending 列表，可为空
     * @return 分类及可选 checkpoint 标识
     */
    public static Classification classify(
            String text,
            Map<String, ?> callbackValue,
            List<PendingCheckpoint> pendingForRoutingKey
    ) {
        if (callbackValue != null && callbackValue.containsKey("checkpoint_id")) {
            Object checkpointId = callbackValue.get("checkpoint_id");
            return new Classification(Category.CHECKPOINT_RESPONSE,
                    checkpointId == null ? null : String.valueOf(checkpointId));
        }

        String stripped = text == null ? "" : text.strip();
        if (stripped.isEmpty()) {
            return new Classification(Category.NEED_DISCUSSION, null);
        }
        List<PendingCheckpoint> pending = pendingForRoutingKey == null ? List.of() : pendingForRoutingKey;
        Matcher matcher = CHECKPOINT_ID_PATTERN.matcher(stripped);
        if (matcher.find() && containsCheckpoint(pending, matcher.group())) {
            return new Classification(Category.CHECKPOINT_RESPONSE, matcher.group());
        }

        if (!pending.isEmpty() && matchesKeyword(stripped, DECISION_TOKENS)) {
            return new Classification(Category.CHECKPOINT_RESPONSE, latestPending(pending).checkpointId());
        }
        if (matchesKeyword(stripped, SOP_KEYWORDS)) {
            return new Classification(Category.SOP_COCREATE, null);
        }
        if (matchesKeyword(stripped, NEW_REQUIREMENT_KEYWORDS)) {
            return new Classification(Category.NEW_REQUIREMENT, null);
        }
        if (matchesKeyword(stripped, CLARIFICATION_KEYWORDS)) {
            return new Classification(Category.CLARIFICATION_ANSWER, null);
        }
        return new Classification(Category.NEED_DISCUSSION, null);
    }

    /**
     * 判断显式标识是否属于当前路由的 pending 列表。
     *
     * @param pending 当前路由待确认项
     * @param checkpointId 显式标识
     * @return 存在匹配项时为 true
     */
    private static boolean containsCheckpoint(List<PendingCheckpoint> pending, String checkpointId) {
        return pending.stream().anyMatch(checkpoint -> checkpoint.checkpointId().equals(checkpointId));
    }

    /**
     * 选择创建时间最大的一项；时间相同时保持文件追加顺序中的最后一项。
     *
     * @param pending 非空待确认列表
     * @return 最新 checkpoint
     */
    private static PendingCheckpoint latestPending(List<PendingCheckpoint> pending) {
        PendingCheckpoint latest = pending.get(pending.size() - 1);
        for (int index = pending.size() - 1; index >= 0; index--) {
            PendingCheckpoint candidate = pending.get(index);
            if (candidate.createdAtMs() > latest.createdAtMs()) {
                latest = candidate;
            }
        }
        return latest;
    }

    /**
     * 以忽略英文大小写的包含关系匹配关键词。
     *
     * @param text 输入文本
     * @param keywords 关键词集合
     * @return 任一关键词命中时为 true
     */
    private static boolean matchesKeyword(String text, Set<String> keywords) {
        String lowercase = text.toLowerCase(Locale.ROOT);
        return keywords.stream().anyMatch(keyword -> lowercase.contains(keyword.toLowerCase(Locale.ROOT)));
    }

    /**
     * 人类输入的五种稳定业务分类。
     */
    public enum Category {
        /** 对某个 checkpoint 的批准、拒绝或修改回复。 */
        CHECKPOINT_RESPONSE("checkpoint_response"),

        /** 需要启动新项目或新功能的需求。 */
        NEW_REQUIREMENT("new_requirement"),

        /** 对已有澄清问题的回答。 */
        CLARIFICATION_ANSWER("clarification_answer"),

        /** 与团队共同制定或修订 SOP。 */
        SOP_COCREATE("sop_cocreate"),

        /** 无法可靠归入其他类型，需要继续讨论。 */
        NEED_DISCUSSION("need_discussion");

        /** 与 Python Category 字面值一致的序列化名称。 */
        private final String value;

        /**
         * 保存分类的跨语言稳定名称。
         *
         * @param value Python 兼容名称
         */
        Category(String value) {
            this.value = value;
        }

        /**
         * 返回用于日志和接口输出的 Python 兼容名称。
         *
         * @return 小写下划线分类名
         */
        public String value() {
            return value;
        }
    }

    /**
     * 保存分类结果及其绑定的 checkpoint；非 checkpoint 分类的标识为空。
     *
     * @param category 输入分类
     * @param checkpointId 绑定的 checkpoint 标识，可为空
     */
    public record Classification(
            /** 输入分类。 */ Category category,
            /** 绑定的 checkpoint 标识，可为空。 */ String checkpointId
    ) {
    }
}
