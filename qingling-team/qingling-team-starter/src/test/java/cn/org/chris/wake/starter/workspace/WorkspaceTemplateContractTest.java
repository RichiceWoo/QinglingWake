package cn.org.chris.wake.starter.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.org.chris.wake.infra.workspace.WorkspaceTemplateInitializer;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.skill.WorkspaceSkillRepository;
import io.agentscope.harness.agent.subagent.AgentSpecLoader;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证迁移清单完整性以及 AgentScope 对四角色 Skill 和 Sub-Agent 的真实发现能力。
 */
class WorkspaceTemplateContractTest {

    /** JUnit 提供的外部 workspace 临时目录。 */
    @TempDir
    Path temporaryDirectory;

    /** JSON 清单编解码器。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 四角色应发现的 Skill 名称。 */
    private static final Map<String, Set<String>> EXPECTED_SKILLS = expectedSkills();

    /** 四角色应发现的任务型 Sub-Agent 名称。 */
    private static final Map<String, Set<String>> EXPECTED_SUBAGENTS = Map.of(
            "manager", Set.of("mailbox_ops"),
            "pm", Set.of("mailbox_ops"),
            "rd", Set.of("mailbox_ops", "code_impl"),
            "qa", Set.of("mailbox_ops", "test_run")
    );

    /**
     * 迁移清单必须覆盖 35 个唯一 Skill，且每个目标与任务型 Sub-Agent 均实际存在。
     *
     * @throws Exception 模板资源或 JSON 读取失败
     */
    @Test
    void shouldMapEveryDeclaredSourceSkillToExistingAgentScopeResources() throws Exception {
        Path templateRoot = templateRoot();
        JsonNode manifest = objectMapper.readTree(templateRoot.resolve("migration-manifest.json").toFile());
        JsonNode skills = manifest.path("skills");

        assertThat(manifest.path("source_skill_count").asInt()).isEqualTo(35);
        assertThat(skills).hasSize(35);
        assertThat(stream(skills).map(node -> node.path("source").asText()))
                .doesNotHaveDuplicates();
        assertThat(stream(skills).map(node -> node.path("target").asText()))
                .allSatisfy(target -> assertThat(templateRoot.resolve(target)).isRegularFile());
        assertThat(stream(skills).filter(node -> "task".equals(node.path("type").asText())))
                .hasSize(6)
                .allSatisfy(node -> assertThat(templateRoot.resolve(node.path("subagent").asText()))
                        .isRegularFile());
        assertThat(templateRoot.resolve("file-manifest.txt")).isRegularFile();
    }

    /**
     * 当 Python 源 workspace 可访问时，逐项比较实际扫描结果，使后续新增 Skill 未登记时立即失败。
     *
     * @throws Exception 源 workspace 或迁移清单读取失败
     */
    @Test
    void shouldDetectAnySourceSkillMissingFromMigrationManifest() throws Exception {
        Path sourceWorkspace = locateSourceWorkspace();
        Assumptions.assumeTrue(sourceWorkspace != null, "当前构建环境未挂载 Python 源 workspace");
        JsonNode manifest = objectMapper.readTree(templateRoot().resolve("migration-manifest.json").toFile());
        Set<String> declared = stream(manifest.path("skills"))
                .map(node -> node.path("source").asText())
                .collect(Collectors.toSet());

        Set<String> actual;
        try (Stream<Path> paths = Files.walk(sourceWorkspace)) {
            actual = paths.filter(path -> path.getFileName().toString().equals("SKILL.md"))
                    .map(sourceWorkspace::relativize)
                    .map(WorkspaceTemplateContractTest::portablePath)
                    .collect(Collectors.toSet());
        }

        assertThat(actual).hasSize(35);
        assertThat(declared).containsExactlyInAnyOrderElementsOf(actual);
    }

    /**
     * 使用 AgentScope 2.0.3 自身的仓库与声明加载器验证四角色可发现预期资源。
     *
     * @throws Exception 模板路径解析失败
     */
    @Test
    void shouldBeDiscoverableByAgentScopeForAllRoles() throws Exception {
        Path templateRoot = templateRoot();
        for (String role : EXPECTED_SKILLS.keySet()) {
            Path roleRoot = templateRoot.resolve(role);
            LocalFilesystem filesystem = new LocalFilesystem(roleRoot);
            WorkspaceSkillRepository skills = new WorkspaceSkillRepository(
                    filesystem, "skills", RuntimeContext::empty
            );
            List<SubagentDeclaration> declarations = AgentSpecLoader.loadFromDirectory(
                    roleRoot.resolve("subagents"), roleRoot
            );

            assertThat(Set.copyOf(skills.getAllSkillNames())).isEqualTo(EXPECTED_SKILLS.get(role));
            assertThat(declarations.stream().map(SubagentDeclaration::getName).collect(Collectors.toSet()))
                    .isEqualTo(EXPECTED_SUBAGENTS.get(role));
            assertThat(declarations).allSatisfy(declaration -> {
                assertThat(declaration.getSkills()).isNotEmpty();
                assertThat(declaration.getTools()).isNotEmpty();
            });
        }
    }

    /**
     * task Skill 必须保持 Python、pip 与 pytest 行为，同时模板不得残留旧 CrewAI 胶水工具。
     *
     * @throws Exception 模板扫描或文件读取失败
     */
    @Test
    void shouldPreservePythonTaskBehaviorWithoutLegacyRuntimeGlue() throws Exception {
        Path templateRoot = templateRoot();
        String codeImpl = Files.readString(templateRoot.resolve("rd/subagents/code_impl.md"));
        String testRun = Files.readString(templateRoot.resolve("qa/subagents/test_run.md"));

        assertThat(codeImpl).contains("Python", "pip", "pytest", "sandbox_execute_bash");
        assertThat(testRun).contains("Python", "pip", "pytest", "sandbox_execute_bash");
        try (Stream<Path> files = Files.walk(templateRoot)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                if (file.getFileName().toString().equals("migration-manifest.json")
                        || file.getFileName().toString().equals("file-manifest.txt")
                        || file.getFileName().toString().equals("AGENTS.md")) {
                    continue;
                }
                assertThat(Files.readString(file))
                        .as("模板文件 %s 不应引用旧运行时胶水", templateRoot.relativize(file))
                        .doesNotContain("CrewAI", "Sub-Crew", "skill_loader", "mailbox_cli.py",
                                "sandbox_file_operations");
            }
        }
        try (Stream<Path> files = Files.walk(templateRoot)) {
            assertThat(files.noneMatch(path -> path.getFileName().toString().equals("load_skills.yaml"))).isTrue();
        }
    }

    /**
     * starter 类路径必须让 infra 初始化器一次性创建完整模板，第二次运行不得覆盖任何文件。
     *
     * @throws Exception 外部 workspace 读取失败
     */
    @Test
    void shouldInitializeCompleteTemplateFromStarterClasspath() throws Exception {
        Path workspace = temporaryDirectory.resolve("external-workspace");
        WorkspaceTemplateInitializer initializer = new WorkspaceTemplateInitializer(workspace, objectMapper);

        WorkspaceTemplateInitializer.InitializationReport first = initializer.initialize();
        WorkspaceTemplateInitializer.InitializationReport second = initializer.initialize();

        assertThat(first.created()).isEqualTo(50);
        assertThat(first.updated()).isZero();
        assertThat(second.created()).isZero();
        assertThat(second.preserved()).isEqualTo(4);
        assertThat(second.unchanged()).isEqualTo(46);
        assertThat(workspace.resolve("manager/AGENTS.md")).isRegularFile();
        assertThat(workspace.resolve("qa/subagents/test_run.md")).isRegularFile();
    }

    /**
     * 返回打包进测试类路径的 workspace-template 目录。
     *
     * @return 模板文件系统路径
     * @throws URISyntaxException 资源 URI 非法
     */
    private static Path templateRoot() throws URISyntaxException {
        var resource = WorkspaceTemplateContractTest.class.getClassLoader().getResource("workspace-template");
        assertThat(resource).as("workspace-template 应进入 starter 类路径").isNotNull();
        return Path.of(resource.toURI());
    }

    /**
     * 优先使用系统属性，随后从当前工程祖先目录定位 Python 源 workspace。
     *
     * @return 源 workspace；未挂载时返回 null
     */
    private static Path locateSourceWorkspace() {
        String configured = System.getProperty("qingling.source.workspace");
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured).toAbsolutePath().normalize();
            return Files.isDirectory(path) ? path : null;
        }
        Path cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (cursor != null) {
            Path candidate = cursor.resolveSibling("kid0317/xiaopaw-team-main/workspace").normalize();
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            cursor = cursor.getParent();
        }
        return null;
    }

    /**
     * 将 JSON 数组转换为顺序 Stream。
     *
     * @param array JSON 数组
     * @return 节点流
     */
    private static Stream<JsonNode> stream(JsonNode array) {
        return java.util.stream.StreamSupport.stream(array.spliterator(), false);
    }

    /**
     * 统一不同操作系统的相对路径分隔符。
     *
     * @param path 相对路径
     * @return 使用正斜杠的路径
     */
    private static String portablePath(Path path) {
        return path.toString().replace('\\', '/');
    }

    /**
     * 创建四角色预期 Skill 的稳定映射。
     *
     * @return 角色到 Skill 名称集合
     */
    private static Map<String, Set<String>> expectedSkills() {
        Map<String, Set<String>> skills = new LinkedHashMap<>();
        skills.put("manager", Set.of(
                "check_review_criteria", "handle_checkpoint_reply", "list_available_sops", "mailbox_ops",
                "read_project_state", "requirements_guide", "requirements_write", "review_proposal",
                "self_score", "sop_cocreate_guide", "sop_feature_dev", "sop_write", "team_retrospective"
        ));
        skills.put("pm", Set.of(
                "mailbox_ops", "product_design", "review_tech_design_from_pm", "self_retrospective", "self_score"
        ));
        skills.put("rd", Set.of(
                "code_impl", "mailbox_ops", "review_product_design_from_rd", "review_test_design",
                "self_retrospective", "self_score", "tech_design"
        ));
        skills.put("qa", Set.of(
                "mailbox_ops", "review_code", "review_product_design_from_qa", "review_tech_design_from_qa",
                "self_retrospective", "self_score", "test_design", "test_run"
        ));
        return Map.copyOf(skills);
    }
}
