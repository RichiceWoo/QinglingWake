package cn.org.chris.wake.starter.e2e;

import cn.org.chris.wake.adapter.api.TestApiController;
import cn.org.chris.wake.adapter.api.dto.TestRequest;
import cn.org.chris.wake.adapter.api.dto.TestResponse;
import cn.org.chris.wake.infra.agentscope.McpSandboxConfiguration;
import cn.org.chris.wake.app.runner.SerialDispatchRegistry;
import cn.org.chris.wake.starter.QinglingTeamApplication;
import cn.org.chris.wake.starter.config.DashScopeModelSelector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 为真实四角色 E2E 提供隔离运行时、用户消息驱动、轮询断言和证据归档。
 */
final class RealTeamE2EDriver implements AutoCloseable {

    /** 单个流水线阶段允许的最长真实执行时间。 */
    private static final Duration STAGE_TIMEOUT = Duration.ofMinutes(30);

    /** 等待文件和事件时的轮询间隔。 */
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

    /** 从 task_done 正文提取 pytest 重试次数。 */
    private static final Pattern PYTEST_ATTEMPTS = Pattern.compile(
            "pytest_attempts[\\\"']?\\s*[:=]\\s*(\\d+)", Pattern.CASE_INSENSITIVE
    );

    /** 当前执行的 Maven 场景名称。 */
    private final String scenario;

    /** 与 AIO-Sandbox `/workspace` 映射的隔离目录。 */
    private final Path workspaceRoot;

    /** 当前场景独占的 Session、Cron 和审计数据目录。 */
    private final Path dataDirectory;

    /** 在 `target` 内保留的场景证据目录。 */
    private final Path evidenceDirectory;

    /** 测试读写 JSON/JSONL 使用的编码器。 */
    private final ObjectMapper objectMapper;

    /** 真实 Spring/AgentScope 运行时上下文。 */
    private final ConfigurableApplicationContext applicationContext;

    /** 无飞书模式下同步驱动真实 Runner 的控制器。 */
    private final TestApiController testApi;

    /** 记录四角色 MCP 注册结果的脱敏配置对象。 */
    private final McpSandboxConfiguration sandboxConfiguration;

    /** 提供 E2E 轮询期间的 dispatch 接纳和积压快照。 */
    private final SerialDispatchRegistry dispatchRegistry;

    /** 记录本场景启动时动态选中的免费模型和脱敏探测结果。 */
    private final DashScopeModelSelector.Selection modelSelection;

    /** 当前测试路由，确保同场景 AgentScope session 稳定。 */
    private final String routingKey;

    /** 本场景启动时间，用于证据摘要。 */
    private final Instant startedAt;

    /** 按用户轮次保存真实模型响应。 */
    private final List<TestResponse> responses = new ArrayList<>();

    /** 为 TestAPI 生成不重复且可读的消息标识。 */
    private int messageSequence;

    /** 记录 MCP OPEN 首次观察时间，避免把短暂自动恢复误报为终态故障。 */
    private final Map<String, Instant> circuitOpenSince = new LinkedHashMap<>();

    /** 最近一次轮询采集的脱敏运行时诊断。 */
    private Map<String, Object> lastRuntimeSnapshot = Map.of();

    /**
     * 保存已启动的真实运行时依赖；调用方应使用 {@link #start(String)}。
     */
    private RealTeamE2EDriver(
            String scenario,
            Path workspaceRoot,
            Path dataDirectory,
            Path evidenceDirectory,
            ObjectMapper objectMapper,
            ConfigurableApplicationContext applicationContext
    ) {
        this.scenario = scenario;
        this.workspaceRoot = workspaceRoot;
        this.dataDirectory = dataDirectory;
        this.evidenceDirectory = evidenceDirectory;
        this.objectMapper = objectMapper;
        this.applicationContext = applicationContext;
        this.testApi = applicationContext.getBean(TestApiController.class);
        this.sandboxConfiguration = applicationContext.getBean(McpSandboxConfiguration.class);
        this.dispatchRegistry = applicationContext.getBean(SerialDispatchRegistry.class);
        this.modelSelection = applicationContext.getBean(DashScopeModelSelector.Selection.class);
        this.routingKey = "test:e2e-" + scenario;
        this.startedAt = Instant.now();
    }

    /**
     * 校验真实模型和 Docker 沙盒前置条件，再启动隔离的 Spring 运行时。
     *
     * @param scenario Task 18 场景名称
     * @return 已启动驱动器
     * @throws Exception 目录准备或 Spring 启动失败
     */
    static RealTeamE2EDriver start(String scenario) throws Exception {
        requireModelCredential();
        String sandboxUrl = sandboxUrl();
        requireSandboxReachable(sandboxUrl);
        Path projectRoot = locateProjectRoot();
        Path workspaceRoot = projectRoot.resolve("target/e2e-workspace");
        Path dataDirectory = projectRoot.resolve("target/e2e-data").resolve(scenario);
        Path evidenceDirectory = projectRoot.resolve("target/e2e-evidence").resolve(scenario);
        resetTargetDirectory(projectRoot, workspaceRoot);
        resetTargetDirectory(projectRoot, dataDirectory);
        resetTargetDirectory(projectRoot, evidenceDirectory);
        Files.createDirectories(workspaceRoot);
        Files.createDirectories(dataDirectory);
        Files.createDirectories(evidenceDirectory);

        ConfigurableApplicationContext context = new SpringApplicationBuilder(QinglingTeamApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--qingling.workspace.root=" + workspaceRoot,
                        "--qingling.data-dir=" + dataDirectory,
                        "--qingling.memory.context-dir=" + dataDirectory.resolve("ctx"),
                        "--qingling.memory.db-dsn=",
                        "--qingling.feishu.enabled=false",
                        "--qingling.debug.test-api-enabled=true",
                        "--qingling.debug.reply-timeout=30m",
                        "--qingling.agent.timeout=30m",
                        "--qingling.sandbox.enabled=true",
                        "--qingling.sandbox.url=" + sandboxUrl,
                        "--qingling.sandbox.timeout=10m",
                        "--qingling.sandbox.initialization-timeout=30s",
                        "--qingling.cleanup.enabled=false",
                        "--qingling.cron.heartbeat-interval=10m",
                        "--qingling.cron.heartbeat-stagger-seconds=600,607,614,621"
                );
        RealTeamE2EDriver driver = new RealTeamE2EDriver(
                scenario, workspaceRoot, dataDirectory, evidenceDirectory,
                context.getBean(ObjectMapper.class), context
        );
        driver.saveModelSelectionEvidence();
        return driver;
    }

    /**
     * 模拟一条用户消息并等待真实 Manager 最终回复。
     *
     * @param text 用户正文
     * @return TestAPI 的真实响应
     */
    TestResponse say(String text) {
        String messageId = "e2e-" + scenario + "-" + (++messageSequence);
        ResponseEntity<?> response = testApi.sendMessage(new TestRequest(
                routingKey, text, messageId, "e2e-user", null
        ));
        if (!response.getStatusCode().is2xxSuccessful() || !(response.getBody() instanceof TestResponse body)) {
            throw new AssertionError("真实 Agent 请求失败，HTTP=" + response.getStatusCode()
                    + "，body=" + String.valueOf(response.getBody()));
        }
        responses.add(body);
        assertThat(body.reply()).as("真实 Manager 回复").isNotBlank();
        return body;
    }

    /** 等待 Manager 创建唯一项目并生成需求文档和需求确认事件。 */
    void waitForProjectAndRequirements() throws Exception {
        waitUntil(() -> currentProjectDirectory() != null, STAGE_TIMEOUT, "project_created directory");
        waitForFile("needs/requirements.md", STAGE_TIMEOUT);
        waitUntil(
                () -> hasEvent("checkpoint_request_sent") || hasEvent("checkpoint_requested"),
                STAGE_TIMEOUT,
                "requirements checkpoint"
        );
    }

    /** 提交需求修改意见，并等待修订内容进入需求或产品文档。 */
    void reviseRequirements() throws Exception {
        say("不同意当前版本，请新增“把当前任务列表分享到微信”的按钮；其他内容保持不变，修订后再次让我确认。");
        waitUntil(() -> documentContains("needs/requirements.md", "分享", "微信")
                        || documentContains("design/product_spec.md", "分享", "微信"),
                STAGE_TIMEOUT, "revised requirements");
    }

    /** 批准需求并等待 PM、RD、QA 流水线产物以及交付请求全部产生。 */
    void approveRequirementsAndWaitForDelivery() throws Exception {
        int approvals = 0;
        while (!fileExists("design/product_spec.md") && approvals < 3) {
            approvals++;
            say("同意当前需求版本，请继续严格按 SOP 推进产品设计、研发、测试和交付。");
            if (waitUpTo(() -> fileExists("design/product_spec.md"), Duration.ofMinutes(8))) {
                break;
            }
        }
        waitForFile("design/product_spec.md", STAGE_TIMEOUT);
        waitForFile("tech/tech_design.md", STAGE_TIMEOUT);
        waitForFile("code/main.py", STAGE_TIMEOUT);
        waitForFile("qa/test_plan.md", STAGE_TIMEOUT);
        waitForFile("qa/test_report.md", STAGE_TIMEOUT);
        waitUntil(() -> hasEvent("delivery_requested") || hasEvent("delivery_sent"),
                STAGE_TIMEOUT, "delivery request");
    }

    /** 批准最终交付，并要求 Manager 记录 delivered 事件。 */
    void approveDelivery() throws Exception {
        if (!hasEvent("delivered")) {
            say("同意，交付验收通过。请记录 delivered 事件并完成本项目。");
        }
        waitUntil(() -> hasEvent("delivered"), STAGE_TIMEOUT, "delivered");
    }

    /** 验证四场景共享的产物、邮件、事件、Session、MCP 和最终回复契约。 */
    void assertCommonContract() throws Exception {
        Path project = requireProjectDirectory();
        assertThat(projectDirectories()).as("E2E 不得串项目").containsExactly(project);
        assertThat(List.of(
                "needs/requirements.md", "design/product_spec.md", "tech/tech_design.md",
                "code/main.py", "qa/test_plan.md", "qa/test_report.md"
        )).allSatisfy(relative -> assertThat(project.resolve(relative)).isRegularFile());

        List<JsonNode> events = events();
        assertThat(events).extracting(node -> node.path("action").asText())
                .contains("project_created", "checkpoint_approved", "delivery_requested", "delivery_sent", "delivered");
        for (int index = 0; index < events.size(); index++) {
            assertThat(events.get(index).path("seq").asLong()).isEqualTo(index + 1L);
        }
        events.stream()
                .filter(event -> event.path("payload").has("routing_key"))
                .forEach(event -> assertThat(event.path("payload").path("routing_key").asText())
                        .as("checkpoint/交付不得串 routing key").isEqualTo(routingKey));

        List<JsonNode> mail = allMail();
        assertThat(mail).isNotEmpty();
        assertThat(mail).allSatisfy(message -> {
            assertThat(message.path("project_id").asText()).isEqualTo(project.getFileName().toString());
            assertThat(message.path("status").asText()).isIn("unread", "in_progress", "done");
        });
        assertThat(mail).anySatisfy(message -> assertThat(message.path("type").asText()).isEqualTo("task_done"));
        assertRoleCompletionOrder(mail);

        assertThat(responses).isNotEmpty();
        String stableSessionId = responses.get(0).sessionId();
        assertThat(stableSessionId).isNotBlank();
        assertThat(responses).extracting(TestResponse::sessionId)
                .as("同一用户路由必须复用 AgentScope session")
                .containsOnly(stableSessionId);
        assertThat(responses).extracting(TestResponse::reply).allSatisfy(reply -> assertThat(reply).isNotBlank());

        waitUntil(() -> sandboxConfiguration.registrationStatuses().size() >= 4,
                Duration.ofSeconds(45), "four MCP registrations");
        assertThat(sandboxConfiguration.registrationStatuses())
                .allSatisfy(status -> {
                    assertThat(status.serverName()).isEqualTo("aio-sandbox");
                    assertThat(status.transport()).isEqualTo(McpSandboxConfiguration.STREAMABLE_HTTP_TRANSPORT);
                    assertThat(status.status().name()).isEqualTo("SUCCESS");
                });
        assertThat(sandboxConfiguration.calledToolNames())
                .as("AIO-Sandbox 必须被真实调用，不能只完成 MCP 注册")
                .contains("sandbox_execute_bash");
        assertThat(Files.readString(project.resolve("qa/test_report.md"))).isNotBlank();
        assertNoCrewAiResidue(project);
    }

    /**
     * 验证 revise、缺陷修复和 pytest 自愈等场景专属不变量。
     *
     * @param selectedScenario 当前场景名称
     */
    void assertScenarioContract(String selectedScenario) throws Exception {
        switch (selectedScenario) {
            case "happy-path" -> assertThat(events()).hasSizeGreaterThanOrEqualTo(8);
            case "checkpoint-revise" -> {
                assertThat(documentContains("needs/requirements.md", "分享", "微信")
                        || documentContains("design/product_spec.md", "分享", "微信")).isTrue();
                long checkpointCount = events().stream()
                        .filter(event -> Set.of("checkpoint_requested", "checkpoint_request_sent")
                                .contains(event.path("action").asText()))
                        .count();
                assertThat(checkpointCount).as("修订后应再次请求确认").isGreaterThanOrEqualTo(2L);
            }
            case "qa-defect-rd-fix" -> assertQaDefectFixLoop();
            case "code-fail-recovery" -> assertCodeFailureRecovery();
            default -> throw new AssertionError("未知 E2E 场景: " + selectedScenario);
        }
    }

    /** 将项目、Session/Cron 数据与脱敏成功摘要复制到 target/e2e-evidence。 */
    void saveEvidence() throws Exception {
        saveEvidence(null);
    }

    /**
     * 在成功或失败路径保存脱敏证据；项目尚未创建时仍写出运行时摘要。
     *
     * @param failure 场景失败，可为空
     * @throws Exception 证据写入失败
     */
    void saveEvidence(Throwable failure) throws Exception {
        Files.createDirectories(evidenceDirectory);
        Path project = currentProjectDirectory();
        if (project != null) {
            copyTree(project, evidenceDirectory.resolve("project"));
        }
        if (Files.exists(dataDirectory)) {
            copyTree(dataDirectory, evidenceDirectory.resolve("data"));
        }
        List<JsonNode> eventSnapshot = project == null ? List.of() : events();
        List<JsonNode> mailSnapshot = project == null ? List.of() : readAvailableMail(project);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("scenario", scenario);
        summary.put("started_at", startedAt.toString());
        summary.put("completed_at", Instant.now().toString());
        summary.put("outcome", failure == null ? "SUCCESS" : "FAILED");
        summary.put("project_id", project == null ? null : project.getFileName().toString());
        summary.put("routing_key", routingKey);
        summary.put("selected_model", modelSelection.modelName());
        summary.put("model_selection_mode", modelSelection.selectionMode());
        summary.put("free_model_selection_enabled", modelSelection.dynamicallySelected());
        summary.put("model_selection_attempts", modelSelection.attempts());
        summary.put("session_ids", responses.stream().map(TestResponse::sessionId).toList());
        summary.put("reply_count", responses.size());
        summary.put("event_actions", eventSnapshot.stream().map(node -> node.path("action").asText()).toList());
        summary.put("mail_statuses", mailSnapshot.stream().map(node -> Map.of(
                "id", node.path("id").asText(""),
                "to", node.path("to").asText(""),
                "type", node.path("type").asText(""),
                "status", node.path("status").asText("")
        )).toList());
        summary.put("mcp_registrations", sandboxConfiguration.registrationStatuses().stream()
                .map(status -> Map.of(
                        "server", status.serverName(),
                        "transport", status.transport(),
                        "status", status.status().name(),
                        "diagnostic", status.diagnostic()
                )).toList());
        summary.put("called_tools", sandboxConfiguration.calledToolNames());
        summary.put("mcp_health", sandboxConfiguration.circuitHealth());
        summary.put("runtime_snapshot", lastRuntimeSnapshot);
        if (failure != null) {
            summary.put("failure_type", failure.getClass().getSimpleName());
            summary.put("failure_message", sanitizeEvidenceText(failure.getMessage()));
        }
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                evidenceDirectory.resolve("summary.json").toFile(), summary
        );
        if (failure != null) {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                    evidenceDirectory.resolve("failure-summary.json").toFile(), summary
            );
        }
    }

    /** 场景启动后立即保存模型选择，确保后续业务失败仍可审计动态回退结果。 */
    private void saveModelSelectionEvidence() throws IOException {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("selected_model", modelSelection.modelName());
        evidence.put("selection_mode", modelSelection.selectionMode());
        evidence.put("free_model_selection_enabled", modelSelection.dynamicallySelected());
        evidence.put("dynamically_selected", modelSelection.dynamicallySelected());
        evidence.put("attempts", modelSelection.attempts());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                evidenceDirectory.resolve("model-selection.json").toFile(), evidence
        );
    }

    /** 关闭 Spring、Cron 和四角色 AgentScope 资源；证据目录保留供审阅。 */
    @Override
    public void close() {
        applicationContext.close();
    }

    /** 验证 QA 确实生成缺陷，并由 Java 状态机通过 Manager 分派 RD 修复及接收后续完成邮件。 */
    private void assertQaDefectFixLoop() throws Exception {
        Path defects = requireProjectDirectory().resolve("qa/defects");
        try (Stream<Path> files = Files.exists(defects) ? Files.list(defects) : Stream.empty()) {
            assertThat(files.filter(path -> path.getFileName().toString().startsWith("defect_")))
                    .as("QA 应生成 defect_*.md").isNotEmpty();
        }
        List<JsonNode> mail = allMail();
        assertThat(mail).anySatisfy(message -> {
            assertThat(message.path("from").asText()).isEqualTo("manager");
            assertThat(message.path("to").asText()).isEqualTo("rd");
            assertThat(message.path("type").asText()).isEqualTo("task_assign");
            assertThat(message.path("subject").asText()).contains("缺陷修复");
            assertThat(message.path("content").path("workflow_stage").asText()).isEqualTo("DEFECT_FIX");
        });
        assertThat(mail).anySatisfy(message -> {
            assertThat(message.path("from").asText()).isEqualTo("rd");
            assertThat(message.path("type").asText()).isEqualTo("task_done");
        });
        assertThat(Files.readString(requireProjectDirectory().resolve("qa/test_report.md")).toLowerCase(Locale.ROOT))
                .containsAnyOf("pass", "通过", "0 failed");
    }

    /** 验证 RD task_done 明确记录至少两次 pytest 尝试，证明失败后读取 stderr 并恢复。 */
    private void assertCodeFailureRecovery() throws Exception {
        int maxAttempts = allMail().stream()
                .filter(message -> "rd".equals(message.path("from").asText()))
                .filter(message -> "task_done".equals(message.path("type").asText()))
                .mapToInt(RealTeamE2EDriver::pytestAttempts)
                .max()
                .orElse(0);
        assertThat(maxAttempts).as("RD pytest_attempts").isGreaterThanOrEqualTo(2);
    }

    /** 按首次 task_done 的出现顺序验证 PM → RD → QA 角色流水线。 */
    private static void assertRoleCompletionOrder(List<JsonNode> mail) {
        LinkedHashSet<String> order = new LinkedHashSet<>();
        mail.stream()
                .filter(message -> "task_done".equals(message.path("type").asText()))
                .map(message -> message.path("from").asText())
                .filter(Set.of("pm", "rd", "qa")::contains)
                .forEach(order::add);
        assertThat(new ArrayList<>(order)).startsWith("pm", "rd", "qa");
    }

    /** 从字符串或对象正文中提取最大的 pytest_attempts 数值。 */
    private static int pytestAttempts(JsonNode message) {
        JsonNode content = message.path("content");
        if (content.isObject()) {
            return content.path("metrics").path("pytest_attempts").asInt(0);
        }
        Matcher matcher = PYTEST_ATTEMPTS.matcher(content.asText(""));
        int maximum = 0;
        while (matcher.find()) {
            maximum = Math.max(maximum, Integer.parseInt(matcher.group(1)));
        }
        return maximum;
    }

    /** 读取四角色邮箱并保持各文件中的追加顺序。 */
    private List<JsonNode> allMail() throws Exception {
        List<JsonNode> messages = new ArrayList<>();
        for (String role : List.of("manager", "pm", "rd", "qa")) {
            Path mailbox = requireProjectDirectory().resolve("mailboxes").resolve(role + ".json");
            assertThat(mailbox).isRegularFile();
            objectMapper.readTree(mailbox.toFile()).forEach(messages::add);
        }
        return List.copyOf(messages);
    }

    /** 读取当前项目合法 JSONL 事件，损坏行会立即使真实 E2E 失败。 */
    private List<JsonNode> events() throws Exception {
        Path eventsPath = requireProjectDirectory().resolve("events.jsonl");
        if (!Files.isRegularFile(eventsPath)) {
            return List.of();
        }
        List<JsonNode> events = new ArrayList<>();
        for (String line : Files.readAllLines(eventsPath, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                events.add(objectMapper.readTree(line));
            }
        }
        return List.copyOf(events);
    }

    /** 判断当前项目是否包含指定事件动作。 */
    private boolean hasEvent(String action) {
        try {
            return events().stream().anyMatch(event -> action.equals(event.path("action").asText()));
        } catch (Exception exception) {
            throw new IllegalStateException("读取 E2E 事件失败", exception);
        }
    }

    /** 判断指定项目文档是否包含任一业务关键词。 */
    private boolean documentContains(String relativePath, String... keywords) {
        Path project = currentProjectDirectory();
        if (project == null || !Files.isRegularFile(project.resolve(relativePath))) {
            return false;
        }
        try {
            String text = Files.readString(project.resolve(relativePath));
            return Stream.of(keywords).anyMatch(text::contains);
        } catch (IOException exception) {
            throw new IllegalStateException("读取 E2E 文档失败: " + relativePath, exception);
        }
    }

    /** 等待当前项目产生指定相对路径文件。 */
    private void waitForFile(String relativePath, Duration timeout) throws Exception {
        waitUntil(() -> fileExists(relativePath), timeout, "file:" + relativePath);
    }

    /** 判断当前项目是否已经产生指定相对路径。 */
    private boolean fileExists(String relativePath) {
        Path project = currentProjectDirectory();
        return project != null && Files.isRegularFile(project.resolve(relativePath));
    }

    /** 返回唯一项目目录；未创建或出现多个项目时返回空。 */
    private Path currentProjectDirectory() {
        try {
            List<Path> projects = projectDirectories();
            return projects.size() == 1 ? projects.get(0) : null;
        } catch (IOException exception) {
            throw new IllegalStateException("扫描 E2E 项目失败", exception);
        }
    }

    /** 返回按名称排序的全部 E2E 项目目录。 */
    private List<Path> projectDirectories() throws IOException {
        Path projects = workspaceRoot.resolve("shared/projects");
        if (!Files.isDirectory(projects)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(projects)) {
            return paths.filter(Files::isDirectory).sorted().toList();
        }
    }

    /** 返回当前唯一项目，否则提供明确失败原因。 */
    private Path requireProjectDirectory() {
        Path project = currentProjectDirectory();
        if (project == null) {
            throw new AssertionError("E2E 应且只能创建一个项目，实际=" + workspaceRoot.resolve("shared/projects"));
        }
        return project;
    }

    /**
     * 每轮等待同步采集 workflow、MCP、mailbox 与 dispatch，并对不可恢复故障立即失败。
     */
    private void assertRuntimeHealthy() {
        try {
            Path project = currentProjectDirectory();
            List<JsonNode> eventSnapshot = project == null ? List.of() : events();
            List<JsonNode> mailSnapshot = project == null ? List.of() : readAvailableMail(project);
            String incident = terminalIncidentReason(eventSnapshot);
            Map<String, Integer> dispatch = new LinkedHashMap<>();
            dispatch.put(routingKey, dispatchRegistry.pendingCount(routingKey));
            for (String role : List.of("manager", "pm", "rd", "qa")) {
                dispatch.put("team:" + role, dispatchRegistry.pendingCount("team:" + role));
            }
            String completionRejection = completionRejectionReason(eventSnapshot, mailSnapshot, dispatch);
            String protocolDeadEnd = workflowProtocolDeadEndReason(eventSnapshot, mailSnapshot, dispatch);
            List<Map<String, Object>> mcpHealth = new ArrayList<>();
            Instant now = Instant.now();
            for (McpSandboxConfiguration.CircuitHealth health : sandboxConfiguration.circuitHealth()) {
                String key = health.serverName() + ":" + health.toolName() + ":" + mcpHealth.size();
                mcpHealth.add(Map.of(
                        "server", health.serverName(),
                        "tool", health.toolName(),
                        "state", health.state().name(),
                        "failures", health.totalFailures()
                ));
                if (health.state() == McpSandboxConfiguration.CircuitState.OPEN) {
                    Instant openedAt = circuitOpenSince.computeIfAbsent(key, ignored -> now);
                    if (Duration.between(openedAt, now).compareTo(Duration.ofSeconds(30)) > 0) {
                        throw new AssertionError("不可恢复 MCP 故障：circuit 持续 OPEN，tool=" + health.toolName());
                    }
                } else {
                    circuitOpenSince.remove(key);
                }
            }
            Map<String, Long> mailboxStatuses = new LinkedHashMap<>();
            for (String status : List.of("unread", "in_progress", "done")) {
                mailboxStatuses.put(status, mailSnapshot.stream()
                        .filter(message -> status.equals(message.path("status").asText()))
                        .count());
            }
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("event_count", eventSnapshot.size());
            snapshot.put("mailbox_statuses", mailboxStatuses);
            snapshot.put("dispatch_accepting", dispatchRegistry.isAccepting());
            snapshot.put("dispatch_pending", dispatch);
            snapshot.put("mcp_health", mcpHealth);
            lastRuntimeSnapshot = Map.copyOf(snapshot);
            if (incident != null) {
                throw new AssertionError("不可恢复工具基础设施故障：" + incident);
            }
            if (completionRejection != null) {
                throw new AssertionError("不可恢复工作流完成协议故障：" + completionRejection);
            }
            if (protocolDeadEnd != null) {
                throw new AssertionError("不可恢复工作流完成协议故障：" + protocolDeadEnd);
            }
            if (!dispatchRegistry.isAccepting()) {
                throw new AssertionError("运行时异常停止接纳 dispatch");
            }
        } catch (AssertionError failure) {
            throw failure;
        } catch (Exception failure) {
            throw new AssertionError("读取 E2E 运行时诊断失败：" + failure.getClass().getSimpleName(), failure);
        }
    }

    /**
     * 从事件快照中提取不可重试或 BLOCKED 的工具 incident 直接原因。
     *
     * @param eventSnapshot 事件快照
     * @return 终态故障摘要；不存在时为 null
     */
    static String terminalIncidentReason(List<JsonNode> eventSnapshot) {
        for (int index = eventSnapshot.size() - 1; index >= 0; index--) {
            JsonNode event = eventSnapshot.get(index);
            if (!"tool_execution_failed".equals(event.path("action").asText())) {
                continue;
            }
            JsonNode payload = event.path("payload");
            if (!payload.path("retryable").asBoolean(true)
                    || "BLOCKED".equals(payload.path("runtime_status").asText())) {
                return "tool=" + payload.path("tool").asText("unknown")
                        + ", execution_id=" + payload.path("execution_id").asText("unknown")
                        + ", failure_type=" + payload.path("failure_type").asText("unknown")
                        + ", stage=" + payload.path("workflow_stage")
                                .asText(payload.path("stage").asText("unknown"))
                        + ", owner=" + payload.path("owner").asText("unknown");
            }
        }
        return null;
    }

    /**
     * 识别同一阶段连续完成门禁拒绝且模型回合已结束的确定性协议故障。
     *
     * @param eventSnapshot 事件快照
     * @param mailSnapshot 四角色邮件快照
     * @param dispatchPending 各路由待处理和执行中任务数
     * @return 直接协议故障摘要；仍可修正或已经成功回报时为 null
     */
    static String completionRejectionReason(
            List<JsonNode> eventSnapshot,
            List<JsonNode> mailSnapshot,
            Map<String, Integer> dispatchPending
    ) {
        boolean hasActiveDispatch = dispatchPending.values().stream().anyMatch(count -> count != null && count > 0);
        if (hasActiveDispatch) {
            return null;
        }
        int latestTransitionIndex = -1;
        for (int index = eventSnapshot.size() - 1; index >= 0; index--) {
            if ("workflow_transitioned".equals(eventSnapshot.get(index).path("action").asText())) {
                latestTransitionIndex = index;
                break;
            }
        }
        List<JsonNode> rejections = eventSnapshot.subList(latestTransitionIndex + 1, eventSnapshot.size()).stream()
                .filter(event -> "completion_rejected".equals(event.path("action").asText()))
                .toList();
        if (rejections.isEmpty()) {
            return null;
        }
        JsonNode latest = rejections.get(rejections.size() - 1);
        JsonNode payload = latest.path("payload");
        String owner = payload.path("role").asText("");
        String stage = payload.path("workflow_stage").asText("unknown");
        long matchingRejectionCount = rejections.stream()
                .map(event -> event.path("payload"))
                .filter(candidate -> owner.equals(candidate.path("role").asText()))
                .filter(candidate -> stage.equals(candidate.path("workflow_stage").asText()))
                .count();
        if (matchingRejectionCount < 2) {
            return null;
        }
        String rejectionTimestamp = latest.path("ts").asText("");
        boolean hasNewerCompletion = mailSnapshot.stream()
                .filter(message -> "task_done".equals(message.path("type").asText()))
                .filter(message -> owner.equals(message.path("from").asText()))
                .anyMatch(message -> message.path("timestamp").asText("").compareTo(rejectionTimestamp) >= 0);
        if (hasNewerCompletion) {
            return null;
        }
        return "stage=" + stage
                + ", owner=" + owner
                + ", rejection_count=" + matchingRejectionCount
                + ", failure_type=" + payload.path("failure_type").asText("unknown")
                + ", reason=" + payload.path("reason").asText("unknown");
    }

    /**
     * 识别当前阶段任务已关闭但完成邮件或后续迁移缺失的静默死锁。
     *
     * @param eventSnapshot 事件快照
     * @param mailSnapshot 四角色邮件快照
     * @param dispatchPending 各路由待处理和执行中任务数
     * @return 直接协议故障摘要；尚有工作或未形成死锁时为 null
     */
    static String workflowProtocolDeadEndReason(
            List<JsonNode> eventSnapshot,
            List<JsonNode> mailSnapshot,
            Map<String, Integer> dispatchPending
    ) {
        boolean hasActiveDispatch = dispatchPending.values().stream().anyMatch(count -> count != null && count > 0);
        boolean hasOpenMail = mailSnapshot.stream()
                .map(message -> message.path("status").asText())
                .anyMatch(status -> "unread".equals(status) || "in_progress".equals(status));
        if (hasActiveDispatch || hasOpenMail) {
            return null;
        }
        JsonNode transition = null;
        for (int index = eventSnapshot.size() - 1; index >= 0; index--) {
            if ("workflow_transitioned".equals(eventSnapshot.get(index).path("action").asText())) {
                transition = eventSnapshot.get(index);
                break;
            }
        }
        if (transition == null) {
            return null;
        }
        JsonNode transitionPayload = transition.path("payload");
        String stage = transitionPayload.path("to_stage").asText("");
        String owner = transitionPayload.path("assignee").asText("");
        String subject = transitionPayload.path("subject").asText("");
        if (stage.isBlank() || owner.isBlank()) {
            return null;
        }
        JsonNode assignment = mailSnapshot.stream()
                .filter(message -> "task_assign".equals(message.path("type").asText()))
                .filter(message -> owner.equals(message.path("to").asText()))
                .filter(message -> stage.equals(message.path("content").path("workflow_stage").asText())
                        || subject.equals(message.path("subject").asText()))
                .max(Comparator.comparing(message -> message.path("timestamp").asText("")))
                .orElse(null);
        if (assignment == null || !"done".equals(assignment.path("status").asText())) {
            return null;
        }
        String assignmentTimestamp = assignment.path("timestamp").asText("");
        boolean hasCompletion = mailSnapshot.stream()
                .filter(message -> "task_done".equals(message.path("type").asText()))
                .filter(message -> owner.equals(message.path("from").asText()))
                .anyMatch(message -> message.path("timestamp").asText("").compareTo(assignmentTimestamp) >= 0);
        String prefix = "stage=" + stage + ", owner=" + owner
                + ", assignment_id=" + assignment.path("id").asText("unknown");
        if (!hasCompletion) {
            return prefix + ", assignment_status=done, task_done_missing=true";
        }
        return prefix + ", assignment_status=done, workflow_transition_missing=true";
    }

    /**
     * 读取当前已存在的角色 mailbox，并验证 JSON 数组和三态字段。
     *
     * @param project 当前项目目录
     * @return 已存在 mailbox 中的邮件快照
     * @throws IOException mailbox JSON 读取失败
     */
    private List<JsonNode> readAvailableMail(Path project) throws IOException {
        List<JsonNode> messages = new ArrayList<>();
        for (String role : List.of("manager", "pm", "rd", "qa")) {
            Path mailbox = project.resolve("mailboxes").resolve(role + ".json");
            if (!Files.isRegularFile(mailbox)) {
                continue;
            }
            JsonNode root = objectMapper.readTree(mailbox.toFile());
            if (!root.isArray()) {
                throw new IOException("mailbox 不是 JSON 数组: " + role);
            }
            root.forEach(message -> {
                String status = message.path("status").asText();
                if (!Set.of("unread", "in_progress", "done").contains(status)) {
                    throw new IllegalStateException("mailbox 状态非法: " + role + ":" + status);
                }
                messages.add(message);
            });
        }
        return List.copyOf(messages);
    }

    /**
     * 对失败摘要执行凭据脱敏和长度限制。
     *
     * @param text 原始错误文本
     * @return 可写入证据的安全文本
     */
    static String sanitizeEvidenceText(String text) {
        if (text == null) {
            return "";
        }
        String sanitized = text
                .replaceAll("(?i)(DASHSCOPE_API_KEY|QWEN_API_KEY|authorization)\\s*[:=]\\s*[^\\s,;]+", "$1=[REDACTED]")
                .replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+", "Bearer [REDACTED]");
        return sanitized.length() <= 4_096 ? sanitized : sanitized.substring(0, 4_096) + "...[TRUNCATED]";
    }

    /** 在给定上限内轮询条件；超时返回 false 供有限重试使用。 */
    private boolean waitUpTo(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            assertRuntimeHealthy();
            if (condition.getAsBoolean()) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(POLL_INTERVAL.toMillis());
        }
        assertRuntimeHealthy();
        return condition.getAsBoolean();
    }

    /** 在给定上限内等待条件，超时必须失败并标出业务阶段。 */
    private void waitUntil(BooleanSupplier condition, Duration timeout, String label) throws Exception {
        if (!waitUpTo(condition, timeout)) {
            throw new AssertionError("等待真实 E2E 阶段超时: " + label + "，timeout=" + timeout);
        }
    }

    /** 验证模型密钥只从环境变量提供，且不把密钥写入测试参数或证据。 */
    private static void requireModelCredential() {
        String dashscope = System.getenv("DASHSCOPE_API_KEY");
        String legacy = System.getenv("QWEN_API_KEY");
        if ((dashscope == null || dashscope.isBlank()) && (legacy == null || legacy.isBlank())) {
            throw new AssertionError("Task 18 需要真实模型密钥：请设置 DASHSCOPE_API_KEY（或兼容变量 QWEN_API_KEY）");
        }
    }

    /** 返回命令行属性、环境变量或默认值中的 AIO-Sandbox MCP 地址。 */
    private static String sandboxUrl() {
        String property = System.getProperty("e2e.sandbox.url");
        if (property != null && !property.isBlank()) {
            return property;
        }
        String environment = System.getenv("QINGLING_SANDBOX_URL");
        return environment == null || environment.isBlank() ? "http://localhost:8029/mcp" : environment;
    }

    /** 通过 TCP 连接验证 Docker 端口真实可达，避免以 JUnit skip 掩盖环境问题。 */
    private static void requireSandboxReachable(String sandboxUrl) {
        URI uri = URI.create(sandboxUrl);
        int port = uri.getPort() > 0 ? uri.getPort() : 80;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(uri.getHost(), port), 3_000);
        } catch (IOException exception) {
            throw new AssertionError("AIO-Sandbox 不可达：" + sandboxUrl
                    + "；请先运行 docker compose -f sandbox-docker-compose.yaml up -d", exception);
        }
    }

    /** 从当前 Maven 工作目录向上查找包含 Compose 文件的 qingling-team 根目录。 */
    private static Path locateProjectRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("sandbox-docker-compose.yaml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new AssertionError("无法定位 qingling-team/sandbox-docker-compose.yaml");
    }

    /**
     * 清空 Maven target 下的隔离目录内容，但保留根目录 inode，避免 Docker bind mount 指向已删除目录。
     *
     * @param projectRoot Maven 项目根目录
     * @param target 待清理的隔离目录
     * @throws IOException 目录遍历或删除失败
     */
    static void resetTargetDirectory(Path projectRoot, Path target) throws IOException {
        Path normalizedRoot = projectRoot.resolve("target").toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (!normalizedTarget.startsWith(normalizedRoot) || normalizedTarget.equals(normalizedRoot)) {
            throw new SecurityException("拒绝清理非隔离 E2E 目录: " + normalizedTarget);
        }
        if (!Files.exists(normalizedTarget)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(normalizedTarget)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.equals(normalizedTarget)) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    /** 递归复制 E2E 证据并保留目录结构。 */
    private static void copyTree(Path source, Path target) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** 确认生成项目和角色运行状态中没有 CrewAI 运行时残留。 */
    private void assertNoCrewAiResidue(Path project) throws IOException {
        List<Path> roots = List.of(project, workspaceRoot.resolve("manager"), workspaceRoot.resolve("pm"),
                workspaceRoot.resolve("rd"), workspaceRoot.resolve("qa"));
        for (Path root : roots) {
            if (!Files.exists(root)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path file : paths.filter(Files::isRegularFile)
                        .filter(RealTeamE2EDriver::isTextEvidence).toList()) {
                    if (Files.size(file) > 2_000_000L) {
                        continue;
                    }
                    String text = Files.readString(file, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                    assertThat(text).as("CrewAI 残留文件: %s", file).doesNotContain("crewai");
                }
            }
        }
    }

    /** 只把明确文本格式纳入 CrewAI 残留扫描，避免把 Agent 状态二进制误当 UTF-8。 */
    private static boolean isTextEvidence(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return Stream.of(".md", ".json", ".jsonl", ".py", ".txt", ".yaml", ".yml", ".html", ".js")
                .anyMatch(name::endsWith);
    }
}
