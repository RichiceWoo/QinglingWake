package cn.org.chris.wake.starter.e2e;

import cn.org.chris.wake.adapter.api.TestApiController;
import cn.org.chris.wake.adapter.api.dto.TestRequest;
import cn.org.chris.wake.adapter.api.dto.TestResponse;
import cn.org.chris.wake.domain.gateway.CronJobRepository;
import cn.org.chris.wake.domain.model.CronJob;
import cn.org.chris.wake.infra.agentscope.McpSandboxConfiguration;
import cn.org.chris.wake.infra.agentscope.AgentScopeAgentGateway;
import cn.org.chris.wake.app.runner.SerialDispatchRegistry;
import cn.org.chris.wake.starter.QinglingTeamApplication;
import cn.org.chris.wake.starter.config.ModelAvailabilitySelector;
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

    /** 失败或取消子任务全部终态后允许父 Agent 收尾的短暂窗口。 */
    private static final Duration TERMINAL_CHILD_DISPATCH_GRACE = Duration.ofSeconds(20);

    /** RUNNING 子任务稳定事件和会话日志均无变化时允许的最长时间。 */
    private static final Duration RUNNING_CHILD_PROGRESS_TIMEOUT = Duration.ofMinutes(5);

    /** Cron dispatch 已失败且没有恢复动作时允许状态持久化收敛的短暂窗口。 */
    private static final Duration IDLE_CRON_FAILURE_GRACE = Duration.ofSeconds(20);

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

    /** 直接读取原子 tasks.json 快照，避免被同步等待 dispatch 的 CronService 锁阻塞。 */
    private final CronJobRepository cronJobRepository;

    /** 提供父 Agent 与 Sub-Agent 任务终态的脱敏诊断。 */
    private final AgentScopeAgentGateway agentGateway;

    /** 记录本场景启动时动态选中的免费模型和脱敏探测结果。 */
    private final ModelAvailabilitySelector.Selection modelSelection;

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

    /** 记录各路由首次观察到失败子任务全部终态的时间，避免瞬时收尾窗口误报。 */
    private final Map<String, Instant> terminalChildFailureSince = new LinkedHashMap<>();

    /** 记录各次 Cron dispatch 失败首次满足空闲死锁条件的时间，避免恢复调度竞态误报。 */
    private final Map<String, Instant> idleCronFailureSince = new LinkedHashMap<>();

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
        this.cronJobRepository = applicationContext.getBean(CronJobRepository.class);
        this.agentGateway = applicationContext.getBean(AgentScopeAgentGateway.class);
        this.modelSelection = applicationContext.getBean(ModelAvailabilitySelector.Selection.class);
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
        requireModelConfiguration(System.getenv());
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

        Instant startupAt = Instant.now();
        ConfigurableApplicationContext context;
        try {
            context = new SpringApplicationBuilder(QinglingTeamApplication.class)
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
                            "--qingling.agent.validate-explicit-model=true",
                            "--qingling.sandbox.enabled=true",
                            "--qingling.sandbox.url=" + sandboxUrl,
                            "--qingling.sandbox.timeout=10m",
                            "--qingling.sandbox.initialization-timeout=30s",
                            "--qingling.cleanup.enabled=false",
                            "--qingling.cron.heartbeat-interval=10m",
                            "--qingling.cron.heartbeat-stagger-seconds=600,607,614,621"
                    );
        } catch (RuntimeException | AssertionError startupFailure) {
            try {
                saveStartupFailureEvidence(evidenceDirectory, scenario, startupAt, startupFailure);
            } catch (IOException evidenceFailure) {
                startupFailure.addSuppressed(evidenceFailure);
            }
            throw startupFailure;
        }
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
            assertRuntimeHealthy();
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
        summary.put("model_provider", modelSelection.provider().name().toLowerCase(java.util.Locale.ROOT));
        summary.put("selected_model", modelSelection.modelName());
        summary.put("model_connectivity_status", modelConnectivityStatus(modelSelection));
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
        evidence.put("provider", modelSelection.provider().name().toLowerCase(java.util.Locale.ROOT));
        evidence.put("selected_model", modelSelection.modelName());
        evidence.put("connectivity_status", modelConnectivityStatus(modelSelection));
        evidence.put("selection_mode", modelSelection.selectionMode());
        evidence.put("free_model_selection_enabled", modelSelection.dynamicallySelected());
        evidence.put("dynamically_selected", modelSelection.dynamicallySelected());
        evidence.put("attempts", modelSelection.attempts());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                evidenceDirectory.resolve("model-selection.json").toFile(), evidence
        );
    }

    /**
     * Spring 运行时尚未构造完成时也保存脱敏失败摘要，避免启动故障没有 E2E 证据。
     *
     * @param evidenceDirectory 当前场景证据目录
     * @param scenario 当前场景名称
     * @param startedAt 启动尝试时间
     * @param failure 启动失败
     * @throws IOException 证据写入失败
     */
    static void saveStartupFailureEvidence(
            Path evidenceDirectory,
            String scenario,
            Instant startedAt,
            Throwable failure
    ) throws IOException {
        Files.createDirectories(evidenceDirectory);
        Throwable root = rootCause(failure);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("scenario", scenario);
        summary.put("started_at", startedAt.toString());
        summary.put("completed_at", Instant.now().toString());
        summary.put("outcome", "FAILED");
        summary.put("phase", "startup");
        summary.put("model_provider", configuredProvider(System.getenv()));
        summary.put("configured_model", sanitizeEvidenceText(System.getenv("QINGLING_AGENT_MODEL")));
        summary.put("failure_type", root.getClass().getSimpleName());
        summary.put("failure_message", sanitizeEvidenceText(root.getMessage()));
        ObjectMapper mapper = new ObjectMapper();
        mapper.writerWithDefaultPrettyPrinter().writeValue(
                evidenceDirectory.resolve("summary.json").toFile(), summary
        );
        mapper.writerWithDefaultPrettyPrinter().writeValue(
                evidenceDirectory.resolve("failure-summary.json").toFile(), summary
        );
    }

    /** 返回异常链最深处的直接原因，并对循环 cause 做上限保护。 */
    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; depth < 32 && current.getCause() != null && current.getCause() != current; depth++) {
            current = current.getCause();
        }
        return current;
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
            List<CronJob> cronSnapshot = cronJobRepository.findAll();
            String incident = terminalIncidentReason(eventSnapshot);
            String recoveringIncidentConflict = recoveringIncidentConflictReason(eventSnapshot);
            Map<String, Integer> dispatch = new LinkedHashMap<>();
            Map<String, SerialDispatchRegistry.DispatchSnapshot> dispatchStates = new LinkedHashMap<>();
            dispatch.put(routingKey, dispatchRegistry.pendingCount(routingKey));
            dispatchStates.put(routingKey, dispatchRegistry.snapshot(routingKey));
            for (String role : List.of("manager", "pm", "rd", "qa")) {
                String teamRoute = "team:" + role;
                dispatch.put(teamRoute, dispatchRegistry.pendingCount(teamRoute));
                dispatchStates.put(teamRoute, dispatchRegistry.snapshot(teamRoute));
            }
            String completionRejection = completionRejectionReason(eventSnapshot, mailSnapshot, dispatch);
            String protocolDeadEnd = workflowProtocolDeadEndReason(eventSnapshot, mailSnapshot, dispatch);
            String mailboxConflict = duplicateOpenAssignmentReason(mailSnapshot);
            List<Map<String, Object>> mcpHealth = new ArrayList<>();
            Instant now = Instant.now();
            List<AgentScopeAgentGateway.ActiveExecutionDiagnostic> agentDiagnostics =
                    agentGateway.activeExecutionDiagnostics();
            String hangingDispatch = hangingDispatchReason(
                    dispatchStates,
                    agentDiagnostics,
                    terminalChildFailureSince,
                    now,
                    TERMINAL_CHILD_DISPATCH_GRACE,
                    RUNNING_CHILD_PROGRESS_TIMEOUT
            );
            String mcpFailure = null;
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
                    if (mcpFailure == null
                            && Duration.between(openedAt, now).compareTo(Duration.ofSeconds(30)) > 0) {
                        mcpFailure = "circuit 持续 OPEN，tool=" + health.toolName();
                    }
                } else {
                    circuitOpenSince.remove(key);
                }
            }
            String cronFailure = idleCronFailureReason(
                    cronSnapshot,
                    mailSnapshot,
                    dispatchStates,
                    agentDiagnostics,
                    idleCronFailureSince,
                    now,
                    IDLE_CRON_FAILURE_GRACE
            );
            Map<String, Long> mailboxStatuses = new LinkedHashMap<>();
            for (String status : List.of("unread", "in_progress", "done")) {
                mailboxStatuses.put(status, mailSnapshot.stream()
                        .filter(message -> status.equals(message.path("status").asText()))
                        .count());
            }
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("event_count", eventSnapshot.size());
            snapshot.put("mailbox_statuses", mailboxStatuses);
            snapshot.put("mailbox_open", openMailboxEvidence(mailSnapshot));
            snapshot.put("dispatch_accepting", dispatchRegistry.isAccepting());
            snapshot.put("dispatch_pending", dispatch);
            snapshot.put("dispatch_states", dispatchStateEvidence(dispatchStates));
            snapshot.put("agentscope_active", agentDiagnosticEvidence(agentDiagnostics));
            snapshot.put("cron_jobs", cronJobEvidence(cronSnapshot));
            snapshot.put("mcp_health", mcpHealth);
            snapshot.put("terminal_incident", incident == null ? "" : sanitizeEvidenceText(incident));
            snapshot.put("recovering_incident_conflict", recoveringIncidentConflict == null
                    ? "" : sanitizeEvidenceText(recoveringIncidentConflict));
            snapshot.put("mcp_failure", mcpFailure == null ? "" : sanitizeEvidenceText(mcpFailure));
            snapshot.put("cron_failure", cronFailure == null ? "" : sanitizeEvidenceText(cronFailure));
            snapshot.put("mailbox_conflict", mailboxConflict == null
                    ? "" : sanitizeEvidenceText(mailboxConflict));
            snapshot.put("hanging_dispatch", hangingDispatch == null
                    ? "" : sanitizeEvidenceText(hangingDispatch));
            lastRuntimeSnapshot = Map.copyOf(snapshot);
            if (incident != null) {
                throw new AssertionError("不可恢复工具基础设施故障：" + incident);
            }
            if (recoveringIncidentConflict != null) {
                throw new AssertionError("工具恢复预算未收敛：" + recoveringIncidentConflict);
            }
            if (mcpFailure != null) {
                throw new AssertionError("不可恢复 MCP 故障：" + mcpFailure);
            }
            if (cronFailure != null) {
                throw new AssertionError("不可恢复 Cron dispatch 故障：" + cronFailure);
            }
            if (mailboxConflict != null) {
                throw new AssertionError("不可恢复 mailbox 重复任务分派：" + mailboxConflict);
            }
            if (hangingDispatch != null) {
                throw new AssertionError("不可恢复 AgentScope dispatch 悬挂：" + hangingDispatch);
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
     * 当 continuation 重复、子任务停滞或异常终态后父 dispatch 仍活跃时返回直接诊断原因。
     *
     * @param dispatchStates dispatch 活跃快照
     * @param agentDiagnostics AgentScope 父子任务快照
     * @param firstObservedAt 各路由首次观察到异常终态的时间
     * @param now 当前时间
     * @param grace 父 Agent 收尾宽限
     * @param runningProgressTimeout RUNNING 子任务真实进度租约
     * @return 悬挂直接原因；仍在运行或尚处于宽限期时为 null
     */
    static String hangingDispatchReason(
            Map<String, SerialDispatchRegistry.DispatchSnapshot> dispatchStates,
            List<AgentScopeAgentGateway.ActiveExecutionDiagnostic> agentDiagnostics,
            Map<String, Instant> firstObservedAt,
            Instant now,
            Duration grace,
            Duration runningProgressTimeout
    ) {
        Set<String> unhealthyRoutes = new LinkedHashSet<>();
        for (AgentScopeAgentGateway.ActiveExecutionDiagnostic diagnostic : agentDiagnostics) {
            String route = diagnostic.routingKey();
            SerialDispatchRegistry.DispatchSnapshot dispatch = dispatchStates.get(route);
            if (dispatch == null || !dispatch.active()) {
                continue;
            }
            Map<String, List<AgentScopeAgentGateway.ChildTaskDiagnostic>> nonTerminalByAgent =
                    diagnostic.childTasks().stream()
                            .filter(task -> !task.terminal())
                            .collect(java.util.stream.Collectors.groupingBy(
                                    AgentScopeAgentGateway.ChildTaskDiagnostic::agentId,
                                    LinkedHashMap::new,
                                    java.util.stream.Collectors.toList()
                            ));
            for (Map.Entry<String, List<AgentScopeAgentGateway.ChildTaskDiagnostic>> entry
                    : nonTerminalByAgent.entrySet()) {
                if (entry.getValue().size() < 2) {
                    continue;
                }
                String duplicateTaskIds = entry.getValue().stream()
                        .map(AgentScopeAgentGateway.ChildTaskDiagnostic::taskId)
                        .sorted()
                        .reduce((left, right) -> left + "," + right)
                        .orElse("none");
                return "route=" + route
                        + ", role=" + diagnostic.role()
                        + ", project_id=" + diagnostic.projectId()
                        + ", parent_session=" + diagnostic.sessionId()
                        + ", duplicate_non_terminal_agent=" + entry.getKey()
                        + ", task_ids=" + duplicateTaskIds;
            }
            List<AgentScopeAgentGateway.ChildTaskDiagnostic> tasks = latestChildTasks(diagnostic.childTasks());
            AgentScopeAgentGateway.ChildTaskDiagnostic stalledTask = tasks.stream()
                    .filter(task -> "RUNNING".equals(task.status()))
                    .filter(task -> !task.terminal())
                    .filter(task -> Duration.between(task.lastProgressAt(), now)
                            .compareTo(runningProgressTimeout) >= 0)
                    .findFirst()
                    .orElse(null);
            if (stalledTask != null) {
                return "route=" + route
                        + ", role=" + diagnostic.role()
                        + ", project_id=" + diagnostic.projectId()
                        + ", dispatch_active_since=" + dispatch.activeSince()
                        + ", stalled_running_task=" + stalledTask.taskId()
                        + ", agent_id=" + stalledTask.agentId()
                        + ", last_progress_at=" + stalledTask.lastProgressAt();
            }
            boolean allTerminal = !tasks.isEmpty()
                    && tasks.stream().allMatch(AgentScopeAgentGateway.ChildTaskDiagnostic::terminal);
            boolean hasFailedOrCancelled = tasks.stream()
                    .map(AgentScopeAgentGateway.ChildTaskDiagnostic::status)
                    .anyMatch(status -> "FAILED".equals(status) || "CANCELLED".equals(status));
            if (allTerminal && hasFailedOrCancelled) {
                unhealthyRoutes.add(route);
                Instant observedAt = firstObservedAt.computeIfAbsent(route, ignored -> now);
                if (Duration.between(observedAt, now).compareTo(grace) < 0) {
                    continue;
                }
                return "route=" + route
                        + ", role=" + diagnostic.role()
                        + ", project_id=" + diagnostic.projectId()
                        + ", dispatch_active_since=" + dispatch.activeSince()
                        + ", terminal_child_tasks=" + childTaskStates(tasks);
            }
            if (tasks.stream().anyMatch(task -> !task.terminal())) {
                continue;
            }
            Instant lastProgressAt = tasks.stream()
                    .map(AgentScopeAgentGateway.ChildTaskDiagnostic::lastProgressAt)
                    .filter(progressAt -> progressAt.isAfter(diagnostic.parentLastProgressAt()))
                    .max(Comparator.naturalOrder())
                    .orElse(diagnostic.parentLastProgressAt());
            if (Duration.between(lastProgressAt, now).compareTo(runningProgressTimeout) >= 0) {
                return "route=" + route
                        + ", role=" + diagnostic.role()
                        + ", project_id=" + diagnostic.projectId()
                        + ", dispatch_active_since=" + dispatch.activeSince()
                        + ", parent_last_progress_at=" + diagnostic.parentLastProgressAt()
                        + ", terminal_child_tasks=" + childTaskStates(tasks);
            }
        }
        firstObservedAt.keySet().removeIf(route -> !unhealthyRoutes.contains(route));
        return null;
    }

    /**
     * 将子任务终态压缩为不含正文的稳定文本。
     *
     * @param tasks 当前逻辑子任务
     * @return taskId=status 逗号列表；没有子任务时为 none
     */
    private static String childTaskStates(List<AgentScopeAgentGateway.ChildTaskDiagnostic> tasks) {
        return tasks.stream()
                .map(task -> task.taskId() + "=" + task.status())
                .sorted()
                .reduce((left, right) -> left + "," + right)
                .orElse("none");
    }

    /**
     * 按逻辑 agentId 选择最新 continuation，避免旧取消记录被误报为当前悬挂原因。
     *
     * @param tasks 当前父 session 的全部子任务
     * @return 每个逻辑子 Agent 最新的一项任务
     */
    private static List<AgentScopeAgentGateway.ChildTaskDiagnostic> latestChildTasks(
            List<AgentScopeAgentGateway.ChildTaskDiagnostic> tasks
    ) {
        Map<String, AgentScopeAgentGateway.ChildTaskDiagnostic> latestByAgent = new LinkedHashMap<>();
        Comparator<AgentScopeAgentGateway.ChildTaskDiagnostic> recency = Comparator
                .comparing(AgentScopeAgentGateway.ChildTaskDiagnostic::createdAt)
                .thenComparing(AgentScopeAgentGateway.ChildTaskDiagnostic::taskId);
        for (AgentScopeAgentGateway.ChildTaskDiagnostic task : tasks) {
            latestByAgent.merge(
                    task.agentId(),
                    task,
                    (current, candidate) -> recency.compare(candidate, current) > 0 ? candidate : current
            );
        }
        return latestByAgent.values().stream()
                .sorted(Comparator.comparing(AgentScopeAgentGateway.ChildTaskDiagnostic::agentId))
                .toList();
    }

    /**
     * 识别 Cron 已记录 dispatch 失败、当前任务仍开放且恢复链完全空闲的终态故障。
     *
     * @param cronSnapshot 原子 tasks.json 任务快照
     * @param mailSnapshot 当前项目邮箱快照
     * @param dispatchStates 各路由 dispatch 状态
     * @param agentDiagnostics 当前活跃 AgentScope 回合
     * @param firstObservedAt 各次失败首次满足空闲条件的时间
     * @param now 当前时间
     * @param grace 状态持久化与恢复 wake 建立宽限
     * @return 直接 Cron 错误摘要；存在恢复工作或尚在宽限期时为 null
     */
    static String idleCronFailureReason(
            List<CronJob> cronSnapshot,
            List<JsonNode> mailSnapshot,
            Map<String, SerialDispatchRegistry.DispatchSnapshot> dispatchStates,
            List<AgentScopeAgentGateway.ActiveExecutionDiagnostic> agentDiagnostics,
            Map<String, Instant> firstObservedAt,
            Instant now,
            Duration grace
    ) {
        Set<String> candidateKeys = new LinkedHashSet<>();
        String directReason = null;
        for (CronJob job : cronSnapshot) {
            String route = job.payload().routingKey();
            String error = job.state().lastError();
            if (!"error".equalsIgnoreCase(job.state().lastStatus())
                    || error == null
                    || error.isBlank()
                    || !route.startsWith("team:")) {
                continue;
            }
            SerialDispatchRegistry.DispatchSnapshot dispatch = dispatchStates.get(route);
            boolean dispatchBusy = dispatch == null || dispatch.pendingCount() > 0 || dispatch.active();
            boolean agentBusy = agentDiagnostics.stream()
                    .anyMatch(diagnostic -> route.equals(diagnostic.routingKey()));
            if (dispatchBusy || agentBusy || hasScheduledRecoveryWake(cronSnapshot, route)) {
                continue;
            }
            String owner = route.substring("team:".length());
            JsonNode assignment = latestOpenAssignment(mailSnapshot, owner);
            if (assignment == null) {
                continue;
            }
            String candidateKey = job.id() + ":" + String.valueOf(job.state().lastRunAtMs());
            candidateKeys.add(candidateKey);
            Instant observedAt = firstObservedAt.computeIfAbsent(candidateKey, ignored -> now);
            if (directReason != null || Duration.between(observedAt, now).compareTo(grace) < 0) {
                continue;
            }
            directReason = "cron_job=" + job.id()
                    + ", route=" + route
                    + ", owner=" + owner
                    + ", last_run_at_ms=" + String.valueOf(job.state().lastRunAtMs())
                    + ", last_error=" + sanitizeEvidenceText(error)
                    + ", assignment_id=" + assignment.path("id").asText("unknown")
                    + ", assignment_status=" + assignment.path("status").asText("unknown")
                    + ", stage=" + assignment.path("content").path("workflow_stage").asText("unknown");
        }
        firstObservedAt.keySet().removeIf(key -> !candidateKeys.contains(key));
        return directReason;
    }

    /**
     * 判断同一路由是否已有启用的一次性任务承载恢复，避免在 wake 到期前误报终态失败。
     *
     * @param cronSnapshot Cron 任务快照
     * @param route 待检查团队路由
     * @return 存在尚未被 Cron 删除的一次性恢复任务时为 true
     */
    private static boolean hasScheduledRecoveryWake(List<CronJob> cronSnapshot, String route) {
        return cronSnapshot.stream().anyMatch(job -> job.enabled()
                && job.schedule().kind() == CronJob.ScheduleKind.AT
                && route.equals(job.payload().routingKey())
                && (job.schedule().atMs() != null || job.state().nextRunAtMs() != null));
    }

    /**
     * 返回指定 Owner 最新的未完成任务分派，忽略邮件正文中的业务说明。
     *
     * @param mailSnapshot 当前项目邮箱快照
     * @param owner 当前工作流 Owner
     * @return 最新开放任务；不存在时为 null
     */
    private static JsonNode latestOpenAssignment(List<JsonNode> mailSnapshot, String owner) {
        return mailSnapshot.stream()
                .filter(message -> "task_assign".equals(message.path("type").asText()))
                .filter(message -> owner.equals(message.path("to").asText()))
                .filter(message -> Set.of("unread", "in_progress").contains(message.path("status").asText()))
                .max(Comparator.comparing(message -> message.path("timestamp").asText("")))
                .orElse(null);
    }

    /** 将 dispatch 快照转换为可序列化且不含 null 的证据。 */
    private static Map<String, Map<String, Object>> dispatchStateEvidence(
            Map<String, SerialDispatchRegistry.DispatchSnapshot> dispatchStates
    ) {
        Map<String, Map<String, Object>> evidence = new LinkedHashMap<>();
        dispatchStates.forEach((route, state) -> {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("pending", state.pendingCount());
            values.put("active", state.active());
            values.put("active_since", state.activeSince() == null ? "" : state.activeSince().toString());
            evidence.put(route, Map.copyOf(values));
        });
        return Map.copyOf(evidence);
    }

    /** 将 AgentScope 活跃回合转换为仅含项目、路由和任务状态的证据。 */
    private static List<Map<String, Object>> agentDiagnosticEvidence(
            List<AgentScopeAgentGateway.ActiveExecutionDiagnostic> diagnostics
    ) {
        return diagnostics.stream().map(diagnostic -> Map.<String, Object>of(
                "role", diagnostic.role(),
                "session_id", diagnostic.sessionId(),
                "routing_key", diagnostic.routingKey(),
                "project_id", diagnostic.projectId(),
                "started_at", diagnostic.startedAt().toString(),
                "parent_last_progress_at", diagnostic.parentLastProgressAt().toString(),
                "child_tasks", diagnostic.childTasks().stream().map(task -> Map.of(
                        "task_id", task.taskId(),
                        "agent_id", task.agentId(),
                        "status", task.status(),
                        "terminal", task.terminal(),
                        "task_kind", task.taskKind(),
                        "cancel_origin", task.cancelOrigin(),
                        "last_progress_at", task.lastProgressAt().toString()
                )).toList()
        )).toList();
    }

    /**
     * 将 Cron 任务转换为不含消息正文且错误已脱敏的运行时证据。
     *
     * @param cronSnapshot 原子 tasks.json 任务快照
     * @return 可安全写入 summary 的任务状态
     */
    static List<Map<String, Object>> cronJobEvidence(List<CronJob> cronSnapshot) {
        return cronSnapshot.stream().map(job -> {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("id", job.id());
            values.put("routing_key", job.payload().routingKey());
            values.put("schedule_kind", job.schedule().kind().name());
            values.put("enabled", job.enabled());
            Long nextRunAtMs = job.state().nextRunAtMs() == null
                    ? job.schedule().atMs() : job.state().nextRunAtMs();
            values.put("next_run_at_ms", nextRunAtMs == null ? "" : nextRunAtMs);
            values.put("last_run_at_ms", job.state().lastRunAtMs() == null
                    ? "" : job.state().lastRunAtMs());
            values.put("last_status", job.state().lastStatus() == null ? "" : job.state().lastStatus());
            values.put("last_error", sanitizeEvidenceText(job.state().lastError()));
            return Map.copyOf(values);
        }).toList();
    }

    /**
     * 将开放 mailbox 转换为只含路由、阶段与租约状态的证据，不复制 subject 或正文。
     *
     * @param mailSnapshot 当前项目邮箱快照
     * @return unread 与 in_progress 邮件的脱敏摘要
     */
    static List<Map<String, Object>> openMailboxEvidence(List<JsonNode> mailSnapshot) {
        return mailSnapshot.stream()
                .filter(message -> Set.of("unread", "in_progress").contains(message.path("status").asText()))
                .map(message -> {
                    Map<String, Object> values = new LinkedHashMap<>();
                    values.put("id", message.path("id").asText(""));
                    values.put("project_id", message.path("project_id").asText(""));
                    values.put("from", message.path("from").asText(""));
                    values.put("to", message.path("to").asText(""));
                    values.put("type", message.path("type").asText(""));
                    values.put("status", message.path("status").asText(""));
                    values.put("workflow_stage", message.path("content").path("workflow_stage").asText(""));
                    values.put("timestamp", message.path("timestamp").asText(""));
                    values.put("processing_since", message.path("processing_since").asText(""));
                    return Map.copyOf(values);
                })
                .toList();
    }

    /**
     * 检测同项目、阶段与 Owner 同时存在多个开放任务分派的确定性协议冲突。
     *
     * @param mailSnapshot 当前项目邮箱快照
     * @return 脱敏冲突摘要；没有冲突时为 null
     */
    static String duplicateOpenAssignmentReason(List<JsonNode> mailSnapshot) {
        Map<String, List<JsonNode>> assignmentsByScope = new LinkedHashMap<>();
        for (JsonNode message : mailSnapshot) {
            String status = message.path("status").asText();
            String stage = message.path("content").path("workflow_stage").asText();
            if (!"task_assign".equals(message.path("type").asText())
                    || !Set.of("unread", "in_progress").contains(status)
                    || stage.isBlank()) {
                continue;
            }
            String key = message.path("project_id").asText("")
                    + "|" + stage
                    + "|" + message.path("to").asText("");
            assignmentsByScope.computeIfAbsent(key, ignored -> new ArrayList<>()).add(message);
        }
        for (List<JsonNode> assignments : assignmentsByScope.values()) {
            if (assignments.size() < 2) {
                continue;
            }
            JsonNode first = assignments.get(0);
            String states = assignments.stream()
                    .map(message -> message.path("id").asText("unknown")
                            + "=" + message.path("status").asText("unknown"))
                    .sorted()
                    .reduce((left, right) -> left + "," + right)
                    .orElse("unknown");
            return "project_id=" + first.path("project_id").asText("")
                    + ", stage=" + first.path("content").path("workflow_stage").asText("unknown")
                    + ", owner=" + first.path("to").asText("unknown")
                    + ", assignments=" + states;
        }
        return null;
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
     * 识别同一阶段、Owner、工具和失败指纹出现多个 RECOVERING execution 的预算失配。
     *
     * @param eventSnapshot 事件快照
     * @return 重复恢复的脱敏摘要；每类故障至多一次恢复时为 null
     */
    static String recoveringIncidentConflictReason(List<JsonNode> eventSnapshot) {
        Map<String, Set<String>> executionsByFingerprint = new LinkedHashMap<>();
        Map<String, JsonNode> payloadByFingerprint = new LinkedHashMap<>();
        for (JsonNode event : eventSnapshot) {
            if (!"tool_execution_failed".equals(event.path("action").asText())) {
                continue;
            }
            JsonNode payload = event.path("payload");
            if (!payload.path("retryable").asBoolean(false)
                    || !"RECOVERING".equals(payload.path("runtime_status").asText())) {
                continue;
            }
            String tool = payload.path("tool").asText("unknown");
            String failureType = payload.path("failure_type").asText("unknown");
            String fingerprint = payload.path("failure_fingerprint").asText(tool + ":" + failureType);
            String key = payload.path("workflow_stage").asText("unknown")
                    + "|" + payload.path("owner").asText("unknown")
                    + "|" + tool + "|" + fingerprint;
            executionsByFingerprint.computeIfAbsent(key, ignored -> new LinkedHashSet<>())
                    .add(payload.path("execution_id").asText("unknown"));
            payloadByFingerprint.putIfAbsent(key, payload);
        }
        for (Map.Entry<String, Set<String>> entry : executionsByFingerprint.entrySet()) {
            if (entry.getValue().size() < 2) {
                continue;
            }
            JsonNode payload = payloadByFingerprint.get(entry.getKey());
            return "tool=" + payload.path("tool").asText("unknown")
                    + ", failure_fingerprint=" + payload.path("failure_fingerprint")
                            .asText(payload.path("failure_type").asText("unknown"))
                    + ", stage=" + payload.path("workflow_stage").asText("unknown")
                    + ", owner=" + payload.path("owner").asText("unknown")
                    + ", recovering_executions=" + entry.getValue().stream().sorted().toList();
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
                .replaceAll("(?i)(DASHSCOPE_API_KEY|QWEN_API_KEY|NEWAPI_API_KEY|authorization)\\s*[:=]\\s*[^\\s,;]+", "$1=[REDACTED]")
                .replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+", "Bearer [REDACTED]")
                .replaceAll("(?i)\\bsk-[A-Za-z0-9._~+/=-]+", "sk-[REDACTED]");
        return sanitized.length() <= 4_096 ? sanitized : sanitized.substring(0, 4_096) + "...[TRUNCATED]";
    }

    /**
     * 从最后一次运行时快照提取超时所需的最小脱敏上下文，避免只报告下游文件名。
     *
     * @param snapshot 最近一次完整运行时快照
     * @return 可直接附加到断言消息的有界摘要
     */
    static String runtimeTimeoutSummary(Map<String, Object> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return "{}";
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        for (String key : List.of(
                "event_count",
                "mailbox_open",
                "dispatch_pending",
                "dispatch_states",
                "agentscope_active",
                "cron_jobs",
                "mcp_health",
                "terminal_incident",
                "mcp_failure",
                "cron_failure",
                "hanging_dispatch"
        )) {
            if (snapshot.containsKey(key)) {
                summary.put(key, snapshot.get(key));
            }
        }
        return sanitizeEvidenceText(summary.toString());
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
            throw new AssertionError("等待真实 E2E 阶段超时: " + label
                    + "，timeout=" + timeout
                    + "，runtime=" + runtimeTimeoutSummary(lastRuntimeSnapshot));
        }
    }

    /**
     * 按显式 Provider 校验 E2E 环境变量，不允许跨供应商凭据回退。
     *
     * @param environment 待读取的环境变量快照
     */
    static void requireModelConfiguration(Map<String, String> environment) {
        String provider = configuredProvider(environment);
        if ("dashscope".equals(provider)) {
            String dashscope = environment.get("DASHSCOPE_API_KEY");
            String legacy = environment.get("QWEN_API_KEY");
            if (isBlank(dashscope) && isBlank(legacy)) {
                throw new AssertionError(
                        "Task 18 的 DashScope Provider 需要 DASHSCOPE_API_KEY（或兼容变量 QWEN_API_KEY）"
                );
            }
            return;
        }
        if ("newapi".equals(provider)) {
            if (isBlank(environment.get("NEWAPI_API_KEY"))) {
                throw new AssertionError("Task 18 的 NewAPI Provider 需要 NEWAPI_API_KEY");
            }
            String baseUrl = environment.get("NEWAPI_BASE_URL");
            if (isBlank(baseUrl)) {
                throw new AssertionError("Task 18 的 NewAPI Provider 需要 NEWAPI_BASE_URL");
            }
            new cn.org.chris.wake.starter.config.QinglingTeamProperties.NewApi(
                    environment.get("NEWAPI_API_KEY"), baseUrl
            ).normalizedBaseUrl();
            return;
        }
        throw new AssertionError("QINGLING_MODEL_PROVIDER 只支持 dashscope 或 newapi");
    }

    /** 返回默认兼容 DashScope 的小写 Provider 名称。 */
    private static String configuredProvider(Map<String, String> environment) {
        String configured = environment.get("QINGLING_MODEL_PROVIDER");
        return isBlank(configured) ? "dashscope" : configured.strip().toLowerCase(java.util.Locale.ROOT);
    }

    /** 判断环境变量是否为空白。 */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 将模型探针结果收敛为不包含端点、Header 或响应正文的连通状态。
     *
     * @param selection 已完成的模型选择结果
     * @return not_probed、available 或最后一次稳定失败分类
     */
    private static String modelConnectivityStatus(ModelAvailabilitySelector.Selection selection) {
        if (selection.attempts().isEmpty()) {
            return "not_probed";
        }
        return selection.attempts().get(selection.attempts().size() - 1).status();
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
