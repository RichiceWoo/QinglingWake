package cn.org.chris.wake.infra.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证外部 workspace 模板的首次初始化、安全升级与运行数据保护。
 */
class WorkspaceTemplateInitializerTest {

    /** JUnit 临时目录。 */
    @TempDir
    Path temporaryDirectory;

    /** JSON 状态编解码器。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 重复初始化应保留 MEMORY、用户改动和清单外运行数据，同时升级未修改的 managed 文件。
     *
     * @throws Exception 测试资源或 workspace 读写失败
     */
    @Test
    void shouldInitializeIdempotentlyAndUpgradeOnlyUnmodifiedManagedFiles() throws Exception {
        Path classpath = temporaryDirectory.resolve("classpath");
        Path workspace = temporaryDirectory.resolve("workspace");
        writeTemplate(classpath, "managed|manager/AGENTS.md\nseed|manager/MEMORY.md\n",
                "agents-v1", "memory-seed-v1");

        try (URLClassLoader loader = new URLClassLoader(
                new java.net.URL[]{classpath.toUri().toURL()}, null
        )) {
            WorkspaceTemplateInitializer initializer = initializer(workspace, loader);
            WorkspaceTemplateInitializer.InitializationReport first = initializer.initialize();

            assertThat(first.created()).isEqualTo(2);
            assertThat(Files.readString(workspace.resolve("manager/AGENTS.md"))).isEqualTo("agents-v1");
            assertThat(Files.readString(workspace.resolve("manager/MEMORY.md"))).isEqualTo("memory-seed-v1");

            Files.writeString(workspace.resolve("manager/MEMORY.md"), "runtime-memory");
            Files.writeString(workspace.resolve("runtime.jsonl"), "runtime-data");
            writeTemplate(classpath, "managed|manager/AGENTS.md\nseed|manager/MEMORY.md\n",
                    "agents-v2", "memory-seed-v2");

            WorkspaceTemplateInitializer.InitializationReport upgrade = initializer.initialize();

            assertThat(upgrade.updated()).isEqualTo(1);
            assertThat(upgrade.preserved()).isEqualTo(1);
            assertThat(Files.readString(workspace.resolve("manager/AGENTS.md"))).isEqualTo("agents-v2");
            assertThat(Files.readString(workspace.resolve("manager/MEMORY.md"))).isEqualTo("runtime-memory");
            assertThat(Files.readString(workspace.resolve("runtime.jsonl"))).isEqualTo("runtime-data");

            Files.writeString(workspace.resolve("manager/AGENTS.md"), "user-customized-agents");
            writeTemplate(classpath, "managed|manager/AGENTS.md\nseed|manager/MEMORY.md\n",
                    "agents-v3", "memory-seed-v3");

            WorkspaceTemplateInitializer.InitializationReport protectedRun = initializer.initialize();

            assertThat(protectedRun.updated()).isZero();
            assertThat(protectedRun.preserved()).isEqualTo(2);
            assertThat(Files.readString(workspace.resolve("manager/AGENTS.md")))
                    .isEqualTo("user-customized-agents");
            JsonNode state = objectMapper.readTree(workspace.resolve(".qingling-template-state.json").toFile());
            assertThat(state.path("version").asInt()).isEqualTo(1);
        }
    }

    /**
     * 已存在但没有受管摘要的文件应被视为用户数据，不得在首次运行时覆盖。
     *
     * @throws Exception 测试资源或 workspace 读写失败
     */
    @Test
    void shouldPreservePreexistingUnmanagedFiles() throws Exception {
        Path classpath = temporaryDirectory.resolve("preexisting-classpath");
        Path workspace = temporaryDirectory.resolve("preexisting-workspace");
        writeTemplate(classpath, "managed|manager/AGENTS.md\nseed|manager/MEMORY.md\n",
                "template-agents", "template-memory");
        Files.createDirectories(workspace.resolve("manager"));
        Files.writeString(workspace.resolve("manager/AGENTS.md"), "existing-agents");
        Files.writeString(workspace.resolve("manager/MEMORY.md"), "existing-memory");

        try (URLClassLoader loader = new URLClassLoader(
                new java.net.URL[]{classpath.toUri().toURL()}, null
        )) {
            WorkspaceTemplateInitializer.InitializationReport report = initializer(workspace, loader).initialize();

            assertThat(report.created()).isZero();
            assertThat(report.preserved()).isEqualTo(2);
            assertThat(Files.readString(workspace.resolve("manager/AGENTS.md"))).isEqualTo("existing-agents");
            assertThat(Files.readString(workspace.resolve("manager/MEMORY.md"))).isEqualTo("existing-memory");
        }
    }

    /**
     * 模板目标路径经过符号链接时必须拒绝写入且不影响链接外文件。
     *
     * @throws Exception 测试资源或符号链接创建失败
     */
    @Test
    void shouldRejectSymbolicLinkEscape() throws Exception {
        Path classpath = temporaryDirectory.resolve("symlink-classpath");
        Path workspace = temporaryDirectory.resolve("symlink-workspace");
        Path external = temporaryDirectory.resolve("external");
        writeTemplate(classpath, "managed|manager/AGENTS.md\n", "template-agents", null);
        Files.createDirectories(workspace);
        Files.createDirectories(external);
        Files.createSymbolicLink(workspace.resolve("manager"), external);

        try (URLClassLoader loader = new URLClassLoader(
                new java.net.URL[]{classpath.toUri().toURL()}, null
        )) {
            WorkspaceTemplateInitializer initializer = initializer(workspace, loader);

            assertThatThrownBy(initializer::initialize).isInstanceOf(SecurityException.class);
            assertThat(external.resolve("AGENTS.md")).doesNotExist();
        }
    }

    /**
     * 构造使用动态测试类路径的初始化器。
     *
     * @param workspace 外部 workspace
     * @param loader 测试资源类加载器
     * @return 初始化器
     */
    private WorkspaceTemplateInitializer initializer(Path workspace, ClassLoader loader) {
        return new WorkspaceTemplateInitializer(workspace, objectMapper, "test-template", loader);
    }

    /**
     * 写入一组可在测试中升级的最小模板资源。
     *
     * @param classpath 类路径根目录
     * @param manifest 文件清单内容
     * @param agents AGENTS 模板内容
     * @param memory 可选 MEMORY 模板内容
     * @throws IOException 资源写入失败
     */
    private static void writeTemplate(
            Path classpath,
            String manifest,
            String agents,
            String memory
    ) throws IOException {
        Path root = classpath.resolve("test-template");
        Files.createDirectories(root.resolve("manager"));
        Files.writeString(root.resolve("file-manifest.txt"), manifest);
        Files.writeString(root.resolve("manager/AGENTS.md"), agents);
        if (memory != null) {
            Files.writeString(root.resolve("manager/MEMORY.md"), memory);
        }
    }
}
