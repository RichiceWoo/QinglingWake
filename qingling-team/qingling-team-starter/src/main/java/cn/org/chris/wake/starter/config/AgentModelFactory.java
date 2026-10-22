package cn.org.chris.wake.starter.config;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;

import java.util.Objects;

/**
 * 根据显式 Provider 创建对应的 AgentScope 模型，禁止跨供应商隐式回退。
 */
public final class AgentModelFactory {

    /**
     * 创建父 Agent 与子 Agent 共用 Provider 的模型实例。
     *
     * @param agent Agent 模型、Provider 与执行边界配置
     * @param modelName 已选择并验证的模型名称
     * @return 与 Provider 对应的 AgentScope 模型
     */
    public Model create(QinglingTeamProperties.Agent agent, String modelName) {
        Objects.requireNonNull(agent, "agent 配置不能为空");
        String selectedModel = requireText(modelName, "modelName");
        GenerateOptions options = GenerateOptions.builder()
                .maxTokens(agent.maxOutputTokens())
                .build();
        return switch (agent.provider()) {
            case DASHSCOPE -> DashScopeChatModel.builder()
                    .apiKey(agent.dashscope().apiKey())
                    .modelName(selectedModel)
                    .stream(true)
                    .enableThinking(false)
                    .defaultOptions(options)
                    .contextWindowSize(agent.maxInputTokens())
                    .build();
            case NEWAPI -> OpenAIChatModel.builder()
                    .apiKey(agent.newApi().apiKey())
                    .modelName(selectedModel)
                    .baseUrl(agent.newApi().normalizedBaseUrl().toString())
                    .endpointPath("chat/completions")
                    .stream(true)
                    .generateOptions(options)
                    .contextWindowSize(agent.maxInputTokens())
                    .build();
        };
    }

    /**
     * 校验模型名称非空，同时确保异常不携带任何 Provider 凭据。
     *
     * @param value 待校验模型名称
     * @param name 脱敏字段名称
     * @return 去除首尾空白的模型名称
     */
    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 未配置");
        }
        return value.strip();
    }
}
