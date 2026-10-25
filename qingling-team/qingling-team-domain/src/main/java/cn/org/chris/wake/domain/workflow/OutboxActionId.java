package cn.org.chris.wake.domain.workflow;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 标识一次可幂等投影的 outbox 副作用，事件、邮件和 wake 必须复用该值去重。
 *
 * @param value 由工作流命令和动作序号派生的稳定安全标识
 */
public record OutboxActionId(String value) {

    /** outbox 标识限制为可安全写入 JSON、日志和 Cron 元数据的有限字符集合。 */
    private static final Pattern SAFE_VALUE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,191}");

    /**
     * 校验并固化稳定动作标识，避免路径穿越或无界业务正文进入投影去重键。
     */
    public OutboxActionId {
        value = Objects.requireNonNull(value, "outbox action id 不能为空").strip();
        if (!SAFE_VALUE.matcher(value).matches()) {
            throw new IllegalArgumentException("outbox action id 必须是 1 到 192 个安全字符");
        }
    }
}
