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
        String codeImplSkill = Files.readString(templateRoot.resolve("rd/skills/code_impl/SKILL.md"));
        String testRun = Files.readString(templateRoot.resolve("qa/subagents/test_run.md"));

        assertThat(codeImpl).contains("Python", "pip", "pytest", "sandbox_execute_bash");
        assertThat(codeImpl)
                .contains("源码必须用 `write_shared` 逐文件写入")
                .contains("禁止 heredoc、`cat >`、`tee` 或 shell 重定向写文件");
        assertThat(codeImplSkill)
                .contains("write_shared(project_id, rel_path, content)")
                .contains("不要用 heredoc、`cat >`、`tee` 或 shell 重定向写源码")
                .contains("禁止直接使用 `sqlite:///:memory:`")
                .contains("SQLAlchemy `StaticPool`")
                .contains("TODO_DB_PATH")
                .doesNotContain("<<'EOF'");
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
     * 沙盒执行模板必须明确 timeout 的整数类型，避免模型用字符串触发 MCP 参数校验失败。
     *
     * @throws Exception 模板资源读取失败
     */
    @Test
    void shouldRequireIntegerSandboxTimeoutArguments() throws Exception {
        Path templateRoot = templateRoot();
        String rdSkill = Files.readString(templateRoot.resolve("rd/skills/code_impl/SKILL.md"));
        String rdSubagent = Files.readString(templateRoot.resolve("rd/subagents/code_impl.md"));
        String qaSkill = Files.readString(templateRoot.resolve("qa/skills/test_run/SKILL.md"));
        String qaSubagent = Files.readString(templateRoot.resolve("qa/subagents/test_run.md"));

        assertThat(rdSkill).contains("timeout=300", "禁止传字符串");
        assertThat(rdSubagent).contains("JSON 整数秒", "禁止传字符串");
        assertThat(qaSkill).contains("timeout=300", "禁止传字符串");
        assertThat(qaSubagent).contains("JSON 整数秒", "禁止传字符串");
    }

    /**
     * Manager 新需求模板必须先创建项目并持久化需求，再发送固定标识的确认请求。
     *
     * @throws Exception 模板资源读取失败
     */
    @Test
    void shouldRequireProjectAndRequirementsBeforeCheckpointRequest() throws Exception {
        Path templateRoot = templateRoot();
        String managerAgents = Files.readString(templateRoot.resolve("manager/AGENTS.md"));
        String requirementsGuide = Files.readString(
                templateRoot.resolve("manager/skills/requirements_guide/SKILL.md")
        );
        String requirementsWrite = Files.readString(
                templateRoot.resolve("manager/skills/requirements_write/SKILL.md")
        );
        String checkpointReply = Files.readString(
                templateRoot.resolve("manager/skills/handle_checkpoint_reply/SKILL.md")
        );
        String featureSop = Files.readString(
                templateRoot.resolve("manager/skills/sop_feature_dev/SKILL.md")
        );

        assertThat(managerAgents)
                .contains("create_project(project_id, project_name, needs_content=<完整五节 Markdown>)")
                .contains("write_shared(project_id, \"needs/requirements.md\", content)")
                .contains("checkpoint_id=\"requirements_review\"")
                .contains("禁止发送需求 checkpoint")
                .contains("也不是批准")
                .contains("禁止调用 `mark_done`")
                .contains("checkpoint_approved` 成功落入事件流后")
                .contains("禁止把用户原句直接当成 `needs_content`");
        assertThat(requirementsGuide)
                .contains("工具调用顺序不可交换")
                .contains("禁止只把需求写在 checkpoint 消息正文")
                .contains("checkpoint_id=\"requirements_review\"");
        assertThat(requirementsWrite)
                .contains("必须先落盘再确认")
                .contains("create_project` → `write_shared` → `append_event(requirements_drafted)`")
                .contains("不得临时创造 `initial_requirements`")
                .contains("初始需求消息不构成 approve");
        assertThat(checkpointReply)
                .contains("人类回复不是邮箱")
                .contains("绝不调用 `mark_done`")
                .contains("批准事件先于分派")
                .contains("随后才允许 `send_mail(to=\"pm\")`");
        assertThat(featureSop)
                .contains("checkpoint_id=\"requirements_review\"")
                .contains("禁止直接复制用户原句作为需求文档")
                .contains("外部 checkpoint 回复不是 mailbox message")
                .contains("append_event(\"checkpoint_approved\"")
                .contains("send_mail(to=\"pm\"");
    }

    /**
     * RD 代码实现模板必须约束 FastAPI 与 SQLite 测试使用同一可见数据库，避免真实沙盒中的内存库建表丢失。
     *
     * @throws Exception 模板资源读取失败
     */
    @Test
    void shouldRequireSharedSqliteTestDatabaseForFastApiRequests() throws Exception {
        String codeImplSkill = Files.readString(templateRoot().resolve("rd/skills/code_impl/SKILL.md"));

        assertThat(codeImplSkill)
                .contains("命名临时 SQLite 文件")
                .contains("请求连接能看到已建表")
                .contains("测试与应用配置必须同源")
                .contains("不得让 API 测试与 service 测试各自创建互不可见的 `:memory:` 数据库")
                .contains("server_default=text(\"CURRENT_TIMESTAMP\")")
                .contains("禁止把 `strftime(...)` 函数表达式直接放进 `server_default`");
    }

    /**
     * RD 模板必须把共享项目代码目录定义为唯一沙盒执行位置，禁止角色私有副本产生假阳性结果。
     *
     * @throws Exception 模板资源读取失败
     */
    @Test
    void shouldRequireCanonicalSharedCodeDirectoryForRdSandbox() throws Exception {
        Path templateRoot = templateRoot();
        String rdAgents = Files.readString(templateRoot.resolve("rd/AGENTS.md"));
        String codeImplSkill = Files.readString(templateRoot.resolve("rd/skills/code_impl/SKILL.md"));
        String codeImplSubagent = Files.readString(templateRoot.resolve("rd/subagents/code_impl.md"));
        String featureSop = Files.readString(templateRoot.resolve("manager/skills/sop_feature_dev/SKILL.md"));

        assertThat(rdAgents)
                .contains("/workspace/shared/projects/{project_id}/code")
                .contains("禁止使用 `/workspace/code`、`/workspace/rd/code`、`/tmp/code`");
        assertThat(codeImplSkill)
                .contains("test -d /workspace/shared/projects/{pid}/code")
                .contains("禁止把 `write_shared` 产物复制、重建或改写")
                .contains("禁止 `ls /workspace` 后选择角色私有目录作为回退")
                .contains("修复失败用例时仍只能调用 `write_shared`");
        assertThat(codeImplSubagent)
                .contains("第一次沙盒调用先用 `test -d` 验证该精确目录")
                .contains("禁止使用或创建 `/workspace/code`、`/workspace/rd/code`、`/tmp/code`")
                .contains("精确目录不存在时向 Manager 发送 `clarification_request`");
        assertThat(featureSop)
                .contains("沙盒唯一目录 `/workspace/shared/projects/<真实 project_id>/code`");
    }

    /**
     * QA 模板必须把需求文档作为唯一测试契约，并把正式报告持久化到共享项目目录。
     *
     * @throws Exception 模板资源读取失败
     */
    @Test
    void shouldKeepQaCasesInScopeAndPersistReportsToSharedWorkspace() throws Exception {
        Path templateRoot = templateRoot();
        String qaAgents = Files.readString(templateRoot.resolve("qa/AGENTS.md"));
        String testDesign = Files.readString(templateRoot.resolve("qa/skills/test_design/SKILL.md"));
        String testRun = Files.readString(templateRoot.resolve("qa/skills/test_run/SKILL.md"));
        String testRunSubagent = Files.readString(templateRoot.resolve("qa/subagents/test_run.md"));

        assertThat(qaAgents)
                .contains("测试范围的唯一事实来源")
                .contains("明确列入“非目标”的搜索、筛选、分页等能力不得生成用例")
                .contains("requirements.md` → `design/product_spec.md` → `tech/tech_design.md")
                .contains("测试设计阶段不读取实现源码")
                .contains("QA 发现缺陷后只向 Manager 发送 `task_done`")
                .contains("直接进入 `/workspace/shared/projects/{project_id}/code`")
                .contains("禁止用 `write_file`");
        assertThat(testDesign)
                .contains("非目标不得进入计划")
                .contains("文档只声明 `PATCH` 时不得追加 `PUT`")
                .contains("测试设计不读实现源码")
                .contains("requirements > product_spec > tech_design")
                .contains("read_shared(pid, \"qa/test_plan.md\")")
                .contains("回读失败时不得发送 `task_done`");
        assertThat(testRun)
                .contains("不得发明测试契约")
                .contains("优先执行共享代码自带测试")
                .contains("计划错误，不执行")
                .contains("正式产物只能用 `write_shared`")
                .contains("直接在 `/workspace/shared/projects/{pid}/code`")
                .contains("禁止逐个 `read_shared` 后复制到 `/tmp`")
                .contains("禁止 QA 直接向 RD 发送 `task_assign`")
                .contains("read_shared(pid, \"qa/test_report.md\")")
                .contains("qa/contract_tests/test_contract.py")
                .contains("可执行用例数 == QA 契约测试 collected 数")
                .contains("禁止修改预期")
                .contains("test -s requirements.txt");
        assertThat(testRunSubagent)
                .contains("只补充需求文档明确支持的用例")
                .contains("直接在其 `code/` 目录安装依赖并运行 RD pytest")
                .contains("禁止逐文件 `read_shared` 后复制到 `/tmp`")
                .contains("禁止直接向 RD 发送 `task_assign`")
                .contains("禁止用 `write_file`")
                .contains("用 `read_shared` 回读确认");
    }

    /**
     * RD 与 QA 模板必须阻止通过改写断言制造全绿，并要求依赖清单与逐用例契约证据。
     *
     * @throws Exception 模板资源读取失败
     */
    @Test
    void shouldRejectFalseGreenTestsAndRequireReproducibleContractEvidence() throws Exception {
        Path templateRoot = templateRoot();
        String rdSkill = Files.readString(templateRoot.resolve("rd/skills/code_impl/SKILL.md"));
        String rdSubagent = Files.readString(templateRoot.resolve("rd/subagents/code_impl.md"));
        String qaAgents = Files.readString(templateRoot.resolve("qa/AGENTS.md"));
        String qaSkill = Files.readString(templateRoot.resolve("qa/skills/test_run/SKILL.md"));
        String qaSubagent = Files.readString(templateRoot.resolve("qa/subagents/test_run.md"));

        assertThat(rdSkill)
                .contains("失败时先守住文档契约")
                .contains("禁止仅因为当前实现返回 `422`")
                .contains("test -s requirements.txt")
                .contains("禁止把断言改成当前实现的实际状态码或返回值");
        assertThat(rdSubagent)
                .contains("必须创建非空 `requirements.txt`")
                .contains("实现与文档冲突时修业务代码")
                .contains("禁止把断言改成当前实际状态码或返回值");
        assertThat(qaAgents)
                .contains("不能替代 QA 对 `qa/test_plan.md` 的逐条独立契约执行")
                .contains("数量不一致时不得宣称全绿")
                .contains("实现结果与文档契约冲突时必须记缺陷");
        assertThat(qaSkill)
                .contains("RD 自测通过不等于 QA 契约通过")
                .contains("计划与证据必须一对一")
                .contains("qa/contract_tests/test_contract.py")
                .contains("用例未执行")
                .contains("缺少依赖清单即失败");
        assertThat(qaSubagent)
                .contains("RD 自测全绿不能替代 QA 验收")
                .contains("一对一证据矩阵")
                .contains("可执行计划数、pytest collected 数、矩阵行数与 pass+fail 不相等时必须判失败");
    }

    /**
     * 缺陷修复和回归任务必须由 Manager 单点分派，防止 QA 与 Manager 重复创建开放任务。
     *
     * @throws Exception 模板资源读取失败
     */
    @Test
    void shouldRequireManagerOwnedDefectDispatchLoop() throws Exception {
        String featureSop = Files.readString(
                templateRoot().resolve("manager/skills/sop_feature_dev/SKILL.md")
        );

        assertThat(featureSop)
                .contains("由 Manager 且只由 Manager 向 RD 发送一条 `task_assign`")
                .contains("QA 不得直接给 RD 分派任务")
                .contains("已存在相同轮次的开放修复任务，不得重复发送")
                .contains("最新报告明确全通过且无开放 defect 才能进入交付");
    }

    /**
     * Manager 模板必须把技术方案修订与代码实现分成不可跳过的连续阶段。
     *
     * @throws Exception 模板文件读取失败
     */
    @Test
    void shouldRequireCodeImplementationAfterEveryTechDesignRevision() throws Exception {
        Path templateRoot = templateRoot();
        String managerInstructions = Files.readString(templateRoot.resolve("manager/AGENTS.md"));
        String featureSop = Files.readString(templateRoot.resolve("manager/skills/sop_feature_dev/SKILL.md"));

        assertThat(managerInstructions)
                .contains("RD 完成或修订 `tech/tech_design.md` 后仍处于技术方案阶段")
                .contains("只有 RD 明确交付 `code/main.py` 和 `code/tests/` 后才允许分派 QA 测试设计");
        assertThat(featureSop)
                .contains("包括任意轮技术方案修订")
                .contains("技术方案评审后的唯一合法推进")
                .contains("下一任务只能是向 RD 分派“代码实现 (第 1 轮)”")
                .contains("就绝不能向 QA 分派测试设计")
                .contains("`from=rd` 不能单独决定下一阶段");
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
