package cn.org.chris.wake.starter.config;

import cn.org.chris.wake.adapter.api.CaptureSender;
import cn.org.chris.wake.adapter.api.TestApiController;
import cn.org.chris.wake.adapter.feishu.FeishuWebSocketListener;
import cn.org.chris.wake.app.cleanup.CleanupService;
import cn.org.chris.wake.app.cron.CronService;
import cn.org.chris.wake.app.cron.WakeScheduler;
import cn.org.chris.wake.app.observability.LogQueryService;
import cn.org.chris.wake.app.runner.InboundAttachmentService;
import cn.org.chris.wake.app.runner.RoutingKeyResolver;
import cn.org.chris.wake.app.runner.Runner;
import cn.org.chris.wake.app.runner.SerialDispatchRegistry;
import cn.org.chris.wake.app.session.SessionRoutingService;
import cn.org.chris.wake.app.session.SlashCommandService;
import cn.org.chris.wake.domain.event.EventService;
import cn.org.chris.wake.domain.gateway.AttachmentDownloader;
import cn.org.chris.wake.domain.gateway.CronJobRepository;
import cn.org.chris.wake.domain.gateway.MetricsGateway;
import cn.org.chris.wake.domain.gateway.SenderGateway;
import cn.org.chris.wake.domain.gateway.WorkspaceRepository;
import cn.org.chris.wake.domain.mailbox.MailboxService;
import cn.org.chris.wake.domain.model.CleanupPolicy;
import cn.org.chris.wake.infra.agentscope.AgentScopeAgentGateway;
import cn.org.chris.wake.infra.agentscope.McpSandboxConfiguration;
import cn.org.chris.wake.infra.agentscope.RoleSubagentFactory;
import cn.org.chris.wake.infra.agentscope.TeamAgentFactory;
import cn.org.chris.wake.infra.agentscope.tool.ImageAndSearchTools;
import cn.org.chris.wake.infra.agentscope.tool.RoleToolkitFactory;
import cn.org.chris.wake.infra.feishu.FeishuClientFactory;
import cn.org.chris.wake.infra.feishu.FeishuDownloader;
import cn.org.chris.wake.infra.feishu.FeishuSenderGateway;
import cn.org.chris.wake.infra.memory.MemoryExtractionClient;
import cn.org.chris.wake.infra.memory.PgVectorMemoryIndexer;
import cn.org.chris.wake.infra.observability.JsonlLogQueryRepository;
import cn.org.chris.wake.infra.observability.MetricsRecorder;
import cn.org.chris.wake.infra.persistence.cleanup.FileCleanupExecutor;
import cn.org.chris.wake.infra.persistence.cron.FileCronJobRepository;
import cn.org.chris.wake.infra.persistence.event.JsonlEventRepository;
import cn.org.chris.wake.infra.persistence.mailbox.FileMailboxRepository;
import cn.org.chris.wake.infra.persistence.session.FileSessionRouteRepository;
import cn.org.chris.wake.infra.persistence.session.JsonlConversationAuditRepository;
import cn.org.chris.wake.infra.persistence.workspace.FileWorkspaceRepository;
import cn.org.chris.wake.infra.workspace.WorkspaceTemplateInitializer;
import cn.org.chris.wake.starter.cli.LogQueryCommand;
import cn.org.chris.wake.starter.runtime.RoutingSenderGateway;
import cn.org.chris.wake.starter.runtime.RuntimeLifecycle;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.Client;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 按 COLA 依赖方向显式装配 Repository、领域服务、Agent、Runner 与外部适配器。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(QinglingTeamProperties.class)
public class RuntimeConfiguration {

    /** 固定且不可更改的四角色顺序。 */
    private static final List<String> REQUIRED_ROLES = List.of("manager", "pm", "rd", "qa");

    /**
     * 在任何目录初始化和外部组件构造前执行配置 fail-fast 校验。
     */
    @Bean
    public RuntimeSettings runtimeSettings(QinglingTeamProperties properties) {
        requireText(properties.agent().apiKey(), "DASHSCOPE_API_KEY 或 QWEN_API_KEY 未配置");
        requireText(properties.agent().model(), "qingling.agent.model 未配置");
        if (!REQUIRED_ROLES.equals(properties.team().roles())) {
            throw new IllegalStateException("qingling.team.roles 必须依次为 manager、pm、rd、qa");
        }
        requirePositive(properties.agent().maxIterations(), "qingling.agent.max-iterations");
        requirePositive(properties.agent().maxInputTokens(), "qingling.agent.max-input-tokens");
        requirePositive(properties.agent().subAgentMaxIterations(), "qingling.agent.sub-agent-max-iterations");
        requirePositive(properties.agent().timeout(), "qingling.agent.timeout");
        requirePositive(properties.debug().replyTimeout(), "qingling.debug.reply-timeout");
        requirePositive(properties.cron().tickInterval(), "qingling.cron.tick-interval");
        requirePositive(properties.cron().heartbeatInterval(), "qingling.cron.heartbeat-interval");
        if (properties.cron().heartbeatStaggerSeconds().size() != REQUIRED_ROLES.size()) {
            throw new IllegalStateException("qingling.cron.heartbeat-stagger-seconds 必须提供四项");
        }
        properties.cron().heartbeatStaggerSeconds().forEach(value ->
                requireNonNegative(value, "qingling.cron.heartbeat-stagger-seconds")
        );
        requireNonNegative(properties.cleanup().sessionRetentionDays(), "qingling.cleanup.session-retention-days");
        properties.cleanup().rules().values().forEach(value ->
                requireNonNegative(value, "qingling.cleanup.rules")
        );
        if (properties.feishu().enabled()) {
            requireText(properties.feishu().appId(), "FEISHU_APP_ID 未配置");
            requireText(properties.feishu().appSecret(), "FEISHU_APP_SECRET 未配置");
        }
        if (properties.sandbox().enabled()) {
            requireText(properties.sandbox().url(), "qingling.sandbox.url 未配置");
            requirePositive(properties.sandbox().timeout(), "qingling.sandbox.timeout");
            requirePositive(properties.sandbox().initializationTimeout(), "qingling.sandbox.initialization-timeout");
            requireNonNegative(properties.sandbox().maxRetries(), "qingling.sandbox.max-retries");
        }
        rejectSensitiveHeaders(properties.sandbox().headers());
        return new RuntimeSettings(properties, properties.workspace().root(), properties.dataDir());
    }

    /** 初始化外部 workspace 模板，保留 seed 文件和用户修改。 */
    @Bean
    public WorkspaceTemplateInitializer workspaceTemplateInitializer(
            RuntimeSettings settings,
            ObjectMapper objectMapper
    ) {
        WorkspaceTemplateInitializer initializer = new WorkspaceTemplateInitializer(
                settings.workspaceRoot(), objectMapper
        );
        initializer.initialize();
        return initializer;
    }

    /** 创建 Cleanup 服务、写入 0600 运行时凭据并可执行启动清理。 */
    @Bean
    public CleanupService cleanupService(
            RuntimeSettings settings,
            ObjectMapper objectMapper,
            WorkspaceTemplateInitializer initializedWorkspace
    ) {
        QinglingTeamProperties properties = settings.properties();
        ensureDirectory(properties.memory().contextDir(), "qingling.memory.context-dir");
        Map<String, Integer> rules = properties.cleanup().rules().isEmpty()
                ? CleanupPolicy.defaults().rules()
                : properties.cleanup().rules();
        CleanupService service = new CleanupService(
                new FileCleanupExecutor(settings.dataDirectory(), objectMapper),
                new CleanupPolicy(rules, properties.cleanup().sessionRetentionDays()),
                java.time.Clock.systemUTC()
        );
        if (properties.feishu().enabled()) {
            service.writeFeishuCredentials(properties.feishu().appId(), properties.feishu().appSecret());
        }
        if (!properties.search().baiduApiKey().isBlank()) {
            service.writeBaiduCredentials(properties.search().baiduApiKey());
        }
        if (properties.cleanup().enabled() && properties.cleanup().runOnStartup()) {
            service.sweepOnStartup();
        }
        return service;
    }

    /** 创建 Python 兼容 Prometheus 指标记录器。 */
    @Bean
    public MetricsRecorder metricsRecorder(MeterRegistry meterRegistry) {
        return new MetricsRecorder(meterRegistry);
    }

    /** 创建 TestAPI 捕获 Sender；即使接口关闭也用于无飞书安全降级。 */
    @Bean
    public CaptureSender captureSender() {
        return new CaptureSender();
    }

    /** 飞书模式下创建关闭请求正文日志的官方客户端。 */
    @Bean
    @ConditionalOnProperty(prefix = "qingling.feishu", name = "enabled", havingValue = "true")
    public Client feishuClient(RuntimeSettings settings, CleanupService preparedCredentials) {
        return FeishuClientFactory.create(
                settings.properties().feishu().appId(), settings.properties().feishu().appSecret()
        );
    }

    /** 飞书模式下创建 OpenAPI Sender。 */
    @Bean
    @ConditionalOnProperty(prefix = "qingling.feishu", name = "enabled", havingValue = "true")
    public FeishuSenderGateway feishuSenderGateway(Client client, ObjectMapper objectMapper) {
        return new FeishuSenderGateway(client, objectMapper);
    }

    /** 按 test 路由和生产路由选择 Capture 或飞书 Sender。 */
    @Bean
    @Primary
    public SenderGateway senderGateway(
            CaptureSender captureSender,
            ObjectProvider<FeishuSenderGateway> feishuSenderProvider
    ) {
        return new RoutingSenderGateway(captureSender, feishuSenderProvider.getIfAvailable());
    }

    /** 飞书模式下创建消息资源下载器。 */
    @Bean
    @ConditionalOnProperty(prefix = "qingling.feishu", name = "enabled", havingValue = "true")
    public FeishuDownloader feishuDownloader(Client client) {
        return new FeishuDownloader(client);
    }

    /** 无飞书模式下附件下载返回可被 Runner 降级处理的失败 Future。 */
    @Bean
    @Primary
    public AttachmentDownloader attachmentDownloader(ObjectProvider<FeishuDownloader> downloaderProvider) {
        FeishuDownloader downloader = downloaderProvider.getIfAvailable();
        return downloader == null
                ? (messageId, attachment) -> CompletableFuture.failedFuture(
                        new IllegalStateException("无飞书模式不支持附件下载")
                )
                : downloader;
    }

    /** 创建共享 workspace Repository。 */
    @Bean
    public WorkspaceRepository workspaceRepository(RuntimeSettings settings) {
        return new FileWorkspaceRepository(settings.workspaceRoot());
    }

    /** 创建 Cron JSON Repository。 */
    @Bean
    public CronJobRepository cronJobRepository(RuntimeSettings settings, ObjectMapper objectMapper) {
        return new FileCronJobRepository(settings.dataDirectory().resolve("cron/tasks.json"), objectMapper);
    }

    /** 创建团队邮箱领域服务。 */
    @Bean
    public MailboxService mailboxService(RuntimeSettings settings, ObjectMapper objectMapper) {
        return new MailboxService(new FileMailboxRepository(settings.workspaceRoot(), objectMapper));
    }

    /** 创建 Manager 单写者事件服务。 */
    @Bean
    public EventService eventService(RuntimeSettings settings, ObjectMapper objectMapper) {
        return new EventService(new JsonlEventRepository(settings.workspaceRoot(), objectMapper));
    }

    /** 创建 wake 与 heartbeat 调度用例。 */
    @Bean
    public WakeScheduler wakeScheduler(CronJobRepository repository) {
        return new WakeScheduler(repository);
    }

    /** 创建本地图片和可选百度搜索工具。 */
    @Bean
    public ImageAndSearchTools imageAndSearchTools(RuntimeSettings settings, ObjectMapper objectMapper) {
        return new ImageAndSearchTools(
                settings.workspaceRoot(), HttpClient.newHttpClient(),
                settings.properties().search().baiduApiKey(), objectMapper
        );
    }

    /** 创建四角色最小权限 Toolkit 工厂。 */
    @Bean
    public RoleToolkitFactory roleToolkitFactory(
            MailboxService mailboxService,
            WorkspaceRepository workspaceRepository,
            WakeScheduler wakeScheduler,
            EventService eventService,
            SenderGateway senderGateway,
            ImageAndSearchTools imageAndSearchTools,
            ObjectMapper objectMapper
    ) {
        return new RoleToolkitFactory(
                mailboxService, workspaceRepository, wakeScheduler::scheduleMailWake,
                eventService, senderGateway, imageAndSearchTools, objectMapper
        );
    }

    /** 创建显式传入 API Key 的 DashScope 主模型。 */
    @Bean
    public Model agentModel(RuntimeSettings settings, CleanupService preparedRuntime) {
        QinglingTeamProperties.Agent agent = settings.properties().agent();
        return DashScopeChatModel.builder()
                .apiKey(agent.apiKey())
                .modelName(agent.model())
                .stream(true)
                .contextWindowSize(agent.maxInputTokens())
                .build();
    }

    /** 创建启用或关闭状态明确的 AIO-Sandbox MCP 配置。 */
    @Bean
    public McpSandboxConfiguration mcpSandboxConfiguration(RuntimeSettings settings) {
        QinglingTeamProperties.Sandbox sandbox = settings.properties().sandbox();
        if (!sandbox.enabled()) {
            return McpSandboxConfiguration.disabled();
        }
        return McpSandboxConfiguration.streamableHttp(
                "aio-sandbox", sandbox.url(), sandbox.headers(), Map.of(), sandbox.enabledTools(),
                sandbox.timeout(), sandbox.initializationTimeout()
        );
    }

    /** 创建可选 pgvector 记忆索引器，空 DSN 时自动跳过。 */
    @Bean
    public PgVectorMemoryIndexer memoryIndexer(RuntimeSettings settings, ObjectMapper objectMapper) {
        String apiKey = settings.properties().agent().apiKey();
        return new PgVectorMemoryIndexer(
                settings.properties().memory().dbDsn(),
                new MemoryExtractionClient(HttpClient.newHttpClient(), objectMapper, apiKey)
        );
    }

    /** 构造四角色 HarnessAgent 并包装为领域 AgentGateway。 */
    @Bean(destroyMethod = "")
    public AgentScopeAgentGateway agentGateway(
            RuntimeSettings settings,
            WorkspaceTemplateInitializer initializedWorkspace,
            RoleToolkitFactory toolkitFactory,
            Model agentModel,
            McpSandboxConfiguration sandboxConfiguration,
            PgVectorMemoryIndexer memoryIndexer
    ) {
        TeamAgentFactory factory = new TeamAgentFactory(
                agentModel,
                toolkitFactory,
                new RoleSubagentFactory(settings.workspaceRoot()),
                sandboxConfiguration,
                Map.of(),
                settings.properties().agent().maxIterations(),
                settings.properties().agent().maxInputTokens(),
                settings.properties().agent().timeout()
        );
        return new AgentScopeAgentGateway(factory.createAll(), memoryIndexer);
    }

    /** 创建 Session 路由与只写审计服务。 */
    @Bean
    public SessionRoutingService sessionRoutingService(
            RuntimeSettings settings,
            ObjectMapper objectMapper,
            AgentScopeAgentGateway agentGateway
    ) {
        return new SessionRoutingService(
                new FileSessionRouteRepository(settings.dataDirectory(), objectMapper),
                new JsonlConversationAuditRepository(settings.dataDirectory(), objectMapper),
                agentGateway
        );
    }

    /** 创建 Slash Command 用例。 */
    @Bean
    public SlashCommandService slashCommandService(SessionRoutingService sessionRoutingService) {
        return new SlashCommandService(sessionRoutingService);
    }

    /** 创建附件下载与 workspace 落盘服务。 */
    @Bean
    public InboundAttachmentService inboundAttachmentService(
            RuntimeSettings settings,
            AttachmentDownloader downloader
    ) {
        return new InboundAttachmentService(settings.workspaceRoot(), downloader);
    }

    /** 创建 routing key 串行队列注册表。 */
    @Bean
    public SerialDispatchRegistry serialDispatchRegistry() {
        return new SerialDispatchRegistry();
    }

    /** 创建无状态路由解析器。 */
    @Bean
    public RoutingKeyResolver routingKeyResolver() {
        return new RoutingKeyResolver();
    }

    /** 创建完整 Runner 执行链。 */
    @Bean
    public Runner runner(
            SessionRoutingService sessions,
            SlashCommandService slashCommands,
            AgentScopeAgentGateway agents,
            SenderGateway sender,
            InboundAttachmentService attachments,
            SerialDispatchRegistry dispatchRegistry,
            RoutingKeyResolver routingKeyResolver,
            MetricsGateway metrics
    ) {
        return new Runner(
                sessions, slashCommands, agents, sender, attachments,
                dispatchRegistry, routingKeyResolver, metrics
        );
    }

    /** 创建 Cron 热重载和投递服务，由 RuntimeLifecycle 控制启停。 */
    @Bean(destroyMethod = "")
    public CronService cronService(CronJobRepository repository, Runner runner) {
        return new CronService(repository, runner);
    }

    /** 在 debug 开关启用时注册 TestAPI。 */
    @Bean
    @ConditionalOnProperty(prefix = "qingling.debug", name = "test-api-enabled", havingValue = "true")
    public TestApiController testApiController(
            RuntimeSettings settings,
            Runner runner,
            CaptureSender captureSender,
            SessionRoutingService sessions,
            MetricsGateway metrics
    ) {
        return new TestApiController(
                runner, captureSender, sessions, settings.workspaceRoot(),
                settings.properties().debug().replyTimeout(), metrics
        );
    }

    /** 在飞书模式启用时创建 WebSocket Listener，但由生命周期最后启动。 */
    @Bean(destroyMethod = "")
    @ConditionalOnProperty(prefix = "qingling.feishu", name = "enabled", havingValue = "true")
    public FeishuWebSocketListener feishuWebSocketListener(
            RuntimeSettings settings,
            Runner runner,
            MetricsGateway metrics
    ) {
        QinglingTeamProperties.Feishu feishu = settings.properties().feishu();
        return FeishuWebSocketListener.create(
                feishu.appId(), feishu.appSecret(), Set.copyOf(feishu.allowedChats()),
                runner, null, metrics
        );
    }

    /** 创建 JSONL 日志查询服务。 */
    @Bean
    public LogQueryService logQueryService(ObjectMapper objectMapper) {
        return new LogQueryService(new JsonlLogQueryRepository(objectMapper));
    }

    /** 创建可由后续命令入口复用的日志查询 CLI。 */
    @Bean
    public LogQueryCommand logQueryCommand(LogQueryService service, ObjectMapper objectMapper) {
        return new LogQueryCommand(service, objectMapper);
    }

    /** 创建最后启动、最先接管停止的运行时生命周期。 */
    @Bean
    public RuntimeLifecycle runtimeLifecycle(
            WakeScheduler wakeScheduler,
            CronService cronService,
            SerialDispatchRegistry dispatchRegistry,
            AgentScopeAgentGateway agentGateway,
            ObjectProvider<FeishuWebSocketListener> listenerProvider,
            RuntimeSettings settings
    ) {
        return new RuntimeLifecycle(
                wakeScheduler, cronService, dispatchRegistry, agentGateway,
                listenerProvider, settings.properties()
        );
    }

    /** 拒绝空白必填配置，错误信息只包含配置名称。 */
    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(message);
        }
        return value;
    }

    /** 拒绝零值和负数持续时间。 */
    private static void requirePositive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalStateException(name + " 必须大于零");
        }
    }

    /** 拒绝零值和负数数量配置。 */
    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalStateException(name + " 必须大于零");
        }
    }

    /** 拒绝空值与负数配置。 */
    private static void requireNonNegative(Number value, String name) {
        if (value == null || value.longValue() < 0) {
            throw new IllegalStateException(name + " 不得小于零");
        }
    }

    /** 在外部服务构造前创建运行时目录，异常只暴露配置名称。 */
    private static void ensureDirectory(Path directory, String name) {
        try {
            Files.createDirectories(directory.toAbsolutePath().normalize());
        } catch (IOException | SecurityException failure) {
            throw new IllegalStateException(name + " 无法初始化", failure);
        }
    }

    /** 防止外部 YAML 把 Authorization、token 或 secret 直接写入 MCP Header。 */
    private static void rejectSensitiveHeaders(Map<String, String> headers) {
        headers.keySet().forEach(name -> {
            String normalized = name.toLowerCase(java.util.Locale.ROOT);
            if (normalized.contains("authorization") || normalized.contains("token")
                    || normalized.contains("secret") || normalized.contains("key")) {
                throw new IllegalStateException("qingling.sandbox.headers 不允许包含敏感凭据");
            }
        });
    }
}
