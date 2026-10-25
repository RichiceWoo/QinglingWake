package cn.org.chris.wake.domain.workflow;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 标识一次可幂等重放的工作流命令，重复提交相同值必须得到相同结果。
 *
 * @param value 由调用边界生成的稳定标识，长度为 1 到 160 个安全字符
 */
public record WorkflowCommandId(String value) {

    /** 命令标识只允许进入文件名、JSON 和日志均安全的有限字符集合。 */
    private static final Pattern SAFE_VALUE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,159}");

    /**
     * 校验并固化稳定命令标识，禁止空值、路径字符和无界文本进入去重账本。
     */
    public WorkflowCommandId {
        value = Objects.requireNonNull(value, "workflow command id 不能为空").strip();
        if (!SAFE_VALUE.matcher(value).matches()) {
            throw new IllegalArgumentException("workflow command id 必须是 1 到 160 个安全字符");
        }
    }
}
