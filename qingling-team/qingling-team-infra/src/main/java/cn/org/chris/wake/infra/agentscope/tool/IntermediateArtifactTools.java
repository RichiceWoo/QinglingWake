package cn.org.chris.wake.infra.agentscope.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 保存当前 Agent 实例最近一次中间思考产物，帮助模型显式组织复杂步骤。
 */
public final class IntermediateArtifactTools {

    /** 任意结构转为稳定文本时使用的 JSON 编解码器。 */
    private final ObjectMapper objectMapper;

    /** 当前 Agent 生命周期内最近一次中间产物，不写入长期记忆或审计历史。 */
    private final AtomicReference<String> latestArtifact = new AtomicReference<>();

    /**
     * 创建与 Python IntermediateTool 兼容的中间产物工具。
     *
     * @param objectMapper JSON 编解码器
     */
    public IntermediateArtifactTools(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    /**
     * 将字符串、列表、Map 或其他值转换为文本并保存在当前工具实例中。
     *
     * @param intermediateProduct 中间思考产物
     * @return 与 Python 工具一致的确认文本
     */
    @Tool(
            name = "Save_Intermediate_Product_Tool",
            description = "保存当前执行过程的中间思考产物，供后续步骤继续组织推理。",
            concurrencySafe = true
    )
    public String saveIntermediateProduct(
            @ToolParam(
                    name = "intermediate_product",
                    description = "需要保存的字符串、列表、字典或其他中间产物"
            ) Object intermediateProduct
    ) {
        latestArtifact.set(stringify(intermediateProduct));
        return "中间结果已保存，可以进行下一步思考。";
    }

    /**
     * 返回当前 Agent 生命周期内最近一次中间产物，主要用于 trace 和测试观测。
     *
     * @return 尚未保存时为空，否则为规范化文本
     */
    public String latestArtifact() {
        return latestArtifact.get();
    }

    /**
     * 按 Python 转换优先级把任意输入规范化为文本。
     *
     * @param value 任意输入
     * @return 稳定文本表示
     */
    private String stringify(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(String::valueOf).collect(Collectors.joining("\n"));
        }
        if (value instanceof Map<?, ?>) {
            try {
                return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
            } catch (JsonProcessingException ignored) {
                return String.valueOf(value);
            }
        }
        return String.valueOf(value);
    }
}
