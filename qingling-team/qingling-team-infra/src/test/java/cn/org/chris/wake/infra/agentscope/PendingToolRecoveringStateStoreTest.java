package cn.org.chris.wake.infra.agentscope;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.JsonFileAgentStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证声明式子 Agent 中断后的 pending tool 可以安全续跑且不会重放原工具。 */
class PendingToolRecoveringStateStoreTest {

    /** JUnit 隔离状态目录。 */
    @TempDir
    Path temporaryDirectory;

    /** 未完成工具调用应在读取时得到一个确定性错误结果。 */
    @Test
    void shouldAddInterruptedResultForPendingToolCall() {
        JsonFileAgentStateStore delegate = new JsonFileAgentStateStore(temporaryDirectory);
        PendingToolRecoveringStateStore store = new PendingToolRecoveringStateStore(delegate);
        AgentState state = AgentState.builder()
                .userId("team:rd")
                .sessionId("sub-code")
                .context(List.of(toolUseMessage("call-1")))
                .build();
        delegate.save("team:rd", "sub-code", "agent_state", state);

        AgentState recovered = store.get(
                "team:rd", "sub-code", "agent_state", AgentState.class
        ).orElseThrow();

        assertThat(recovered.getContext()).hasSize(2);
        assertThat(recovered.getContext().get(1).getContentBlocks(ToolResultBlock.class))
                .singleElement()
                .satisfies(result -> {
                    assertThat(result.getId()).isEqualTo("call-1");
                    assertThat(result.getOutput().toString()).contains("未重放原调用");
                });
    }

    /** 已有工具结果时不得重复追加恢复消息。 */
    @Test
    void shouldKeepCompletedToolCallUnchanged() {
        JsonFileAgentStateStore delegate = new JsonFileAgentStateStore(temporaryDirectory);
        PendingToolRecoveringStateStore store = new PendingToolRecoveringStateStore(delegate);
        ToolUseBlock use = toolUse("call-2");
        Msg result = io.agentscope.core.tool.ToolResultMessageBuilder.buildToolResultMsg(
                ToolResultBlock.error("call-2", "known failure"), use, "rd"
        );
        AgentState state = AgentState.builder()
                .userId("team:rd")
                .sessionId("sub-code")
                .context(List.of(toolUseMessage(use), result))
                .build();
        delegate.save("team:rd", "sub-code", "agent_state", state);

        AgentState recovered = store.get(
                "team:rd", "sub-code", "agent_state", AgentState.class
        ).orElseThrow();

        assertThat(recovered.getContext()).hasSize(2);
    }

    /** 创建包含单个 pending 工具调用的 assistant 消息。 */
    private static Msg toolUseMessage(String callId) {
        return toolUseMessage(toolUse(callId));
    }

    /** 创建包含指定工具调用的 assistant 消息。 */
    private static Msg toolUseMessage(ToolUseBlock toolUse) {
        return Msg.builder()
                .name("rd-subagent")
                .role(MsgRole.ASSISTANT)
                .content(toolUse)
                .build();
    }

    /** 创建无副作用 read_shared 的测试工具调用。 */
    private static ToolUseBlock toolUse(String callId) {
        return ToolUseBlock.builder()
                .id(callId)
                .name("read_shared")
                .input(Map.of("project_id", "todo-mvp", "rel_path", "tech/tech_design.md"))
                .build();
    }
}
