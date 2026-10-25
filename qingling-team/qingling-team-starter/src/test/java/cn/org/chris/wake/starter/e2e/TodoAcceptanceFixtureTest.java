package cn.org.chris.wake.starter.e2e;

import cn.org.chris.wake.domain.workspace.WorkspacePolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证固定 Todo 验收夹具的内容、倒序权威语义和角色只读边界。
 */
class TodoAcceptanceFixtureTest {

    /** JUnit 隔离项目目录。 */
    @TempDir
    Path projectDirectory;

    /** 测试 JSON 编解码器。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 夹具必须安装八条稳定 requirement，并拒绝角色工作区工具写入 acceptance。 */
    @Test
    void shouldInstallReadOnlyStableContract() throws Exception {
        Map<String, String> digests = TodoAcceptanceFixture.install(projectDirectory, objectMapper);
        JsonNode contract = objectMapper.readTree(
                projectDirectory.resolve("acceptance/contract.json").toFile()
        );

        assertThat(contract.path("requirements")).hasSize(8);
        assertThat(contract.path("requirements").findValuesAsText("id"))
                .containsExactly(
                        "TODO-API-001", "TODO-API-002", "TODO-API-003", "TODO-API-004",
                        "TODO-API-005", "TODO-API-006", "TODO-WEB-001", "TODO-DATA-001"
                );
        assertThat(Files.getPosixFilePermissions(projectDirectory.resolve("acceptance/contract.json")))
                .noneMatch(permission -> permission.name().endsWith("WRITE"));
        assertThatThrownBy(() -> WorkspacePolicy.validateWrite("rd", "acceptance/contract.json"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("未知共享路径前缀");
        TodoAcceptanceFixture.verifyUnmodified(projectDirectory, digests);
    }

    /** 即使 RD 自测接受 ASC，Harness 的 TODO-API-002 仍必须独立拒绝该响应。 */
    @Test
    void shouldRejectAscendingIdsIndependentlyFromRdTests() throws Exception {
        JsonNode ascending = objectMapper.readTree("[{\"id\":1},{\"id\":2},{\"id\":3}]");
        JsonNode descending = objectMapper.readTree("[{\"id\":3},{\"id\":2},{\"id\":1}]");

        assertThatThrownBy(() -> TodoAcceptanceFixture.requireDescendingIds(ascending))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("TODO-API-002", "id_desc");
        TodoAcceptanceFixture.requireDescendingIds(descending);
    }
}
