package cn.org.chris.wake.infra.agentscope.tool;

import cn.org.chris.wake.domain.event.EventService;
import cn.org.chris.wake.domain.gateway.SenderGateway;
import cn.org.chris.wake.domain.gateway.WorkspaceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 提供仅 Manager 可注册的项目初始化、事件追加和人类出站 AgentScope 工具。
 */
public final class ManagerTeamTools {

    /** send_to_human 支持的消息用途。 */
    private static final Set<String> VALID_KINDS = Set.of(
            "info", "checkpoint_request", "proposal_review", "delivery", "evolution_report", "error_alert"
    );

    /** 消息用途到项目事件动作的映射。 */
    private static final Map<String, String> EVENT_BY_KIND = Map.of(
            "info", "info_sent",
            "checkpoint_request", "checkpoint_request_sent",
            "proposal_review", "proposal_review_sent",
            "delivery", "delivery_sent",
            "evolution_report", "evolution_report_sent",
            "error_alert", "error_alert_sent"
    );

    /** 共享工作区持久化端口。 */
    private final WorkspaceRepository workspaceRepository;

    /** Manager 单写者事件服务。 */
    private final EventService eventService;

    /** 可为空的飞书或测试出站端口；为空时保持源项目测试模式语义。 */
    private final SenderGateway senderGateway;

    /** 工具 JSON 响应编解码器。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建 Manager 独占工具集合。
     *
     * @param workspaceRepository 共享工作区端口
     * @param eventService 事件领域服务
     * @param senderGateway 可为空的出站端口
     * @param objectMapper JSON 编解码器
     */
    public ManagerTeamTools(
            WorkspaceRepository workspaceRepository,
            EventService eventService,
            SenderGateway senderGateway,
            ObjectMapper objectMapper
    ) {
        this.workspaceRepository = Objects.requireNonNull(workspaceRepository, "workspaceRepository 不能为空");
        this.eventService = Objects.requireNonNull(eventService, "eventService 不能为空");
        this.senderGateway = senderGateway;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    /**
     * 幂等创建项目目录树、需求文档和 project_created 事件。
     *
     * @param projectId 项目标识
     * @param projectName 项目名称
     * @param needsContent 初始需求正文
     * @return 项目标识和事件序号 JSON
     */
    @Tool(
            name = "create_project",
            description = "Manager 专用：初始化项目目录、四角色邮箱、需求文档和 project_created 事件。",
            concurrencySafe = true
    )
    public String createProject(
            @ToolParam(name = "project_id", description = "小写项目 ID") String projectId,
            @ToolParam(name = "project_name", description = "项目名称") String projectName,
            @ToolParam(name = "needs_content", description = "写入 needs/requirements.md 的需求正文")
            String needsContent
    ) {
        try {
            String checkedProjectId = AgentToolSupport.requireProjectId(projectId);
            String checkedProjectName = AgentToolSupport.requireText(projectName, "project_name");
            workspaceRepository.initializeProject(checkedProjectId);
            workspaceRepository.write(
                    checkedProjectId, "manager", "needs/requirements.md", Objects.requireNonNullElse(needsContent, "")
            );
            long sequence = eventService.append(checkedProjectId, "manager", "project_created", Map.of(
                    "project_id", checkedProjectId,
                    "project_name", checkedProjectName
            ));
            return AgentToolSupport.success(objectMapper, Map.of(
                    "project_id", checkedProjectId,
                    "event_seq", sequence
            ));
        } catch (RuntimeException exception) {
            return AgentToolSupport.error(objectMapper, exception);
        }
    }

    /**
     * 以 Manager 单写者身份向项目事件流追加合法动作。
     *
     * @param projectId 项目标识
     * @param action 事件动作
     * @param payload 结构化事件详情
     * @return 新事件序号 JSON
     */
    @Tool(
            name = "append_event",
            description = "Manager 专用：向项目 events.jsonl 追加一条白名单事件。",
            concurrencySafe = true
    )
    public String appendEvent(
            @ToolParam(name = "project_id", description = "项目 ID") String projectId,
            @ToolParam(name = "action", description = "附录 A.3 事件动作") String action,
            @ToolParam(name = "payload", description = "结构化事件 payload") Map<String, Object> payload
    ) {
        try {
            long sequence = eventService.append(
                    AgentToolSupport.requireProjectId(projectId), "manager", action,
                    payload == null ? Map.of() : payload
            );
            return AgentToolSupport.success(objectMapper, Map.of("seq", sequence));
        } catch (RuntimeException exception) {
            return AgentToolSupport.error(objectMapper, exception);
        }
    }

    /**
     * 在 Java 侧机械执行评审插入判据，避免模型自行遍历项目或计算摘要而陷入工具循环。
     *
     * @param projectId 当前项目标识
     * @param messageId task_done 邮件标识
     * @param fromRole 完成任务的角色
     * @param taskDoneContent task_done 结构化正文
     * @return 稳定的评审判定 JSON
     */
    @Tool(
            name = "check_review_criteria",
            description = "Manager 专用：对 task_done 正文执行固定评审判据，返回 threshold_met、reasons 和 next_action。",
            readOnly = true,
            concurrencySafe = true
    )
    public String checkReviewCriteria(
            @ToolParam(name = "project_id", description = "当前项目 ID") String projectId,
            @ToolParam(name = "msg_id", description = "task_done 邮件 ID") String messageId,
            @ToolParam(name = "from_role", description = "task_done 发件角色：pm/rd/qa") String fromRole,
            @ToolParam(name = "task_done_content", description = "task_done 的结构化 content")
            Map<String, Object> taskDoneContent
    ) {
        try {
            String checkedProjectId = AgentToolSupport.requireProjectId(projectId);
            String checkedMessageId = AgentToolSupport.requireText(messageId, "msg_id");
            String checkedRole = AgentToolSupport.requireText(fromRole, "from_role");
            if (!Set.of("pm", "rd", "qa").contains(checkedRole)) {
                throw new IllegalArgumentException("from_role 必须是 pm、rd 或 qa");
            }
            Map<String, Object> content = taskDoneContent == null ? Map.of() : taskDoneContent;
            List<String> reasons = new ArrayList<>();
            String impactLevel = textValue(content.get("impact_level"));
            if (Set.of("high", "critical").contains(impactLevel)) {
                reasons.add("impact_level=" + impactLevel);
            }
            double selfScore = numberValue(content.get("self_score"), 1.0D);
            if (selfScore < 0.70D) {
                reasons.add("self_score=" + selfScore + " < 0.70");
            }
            double hardConstraints = nestedNumberValue(content, "breakdown", "hard_constraints", 1.0D);
            if (hardConstraints < 0.80D) {
                reasons.add("hard_constraints=" + hardConstraints + " < 0.80");
            }
            String skill = textValue(content.get("skill"));
            long completedSimilarTasks = skill.isBlank() ? -1L : completedSimilarTasks(checkedRole, skill);
            if (!skill.isBlank() && completedSimilarTasks < 3L) {
                reasons.add("novice_skill=" + skill + ", completed=" + completedSimilarTasks);
            }
            int sampleBucket = stableSampleBucket(checkedMessageId);
            if (sampleBucket < 15) {
                reasons.add("audit_sample_bucket=" + sampleBucket + " < 15");
            }
            boolean thresholdMet = !reasons.isEmpty();
            Map<String, Object> decision = new LinkedHashMap<>();
            decision.put("project_id", checkedProjectId);
            decision.put("threshold_met", thresholdMet);
            decision.put("reasons", reasons);
            decision.put("next_action", thresholdMet ? "insert_review" : "proceed_next_stage");
            decision.put("completed_similar_tasks", completedSimilarTasks);
            decision.put("sample_bucket", sampleBucket);
            return AgentToolSupport.success(objectMapper, decision);
        } catch (RuntimeException exception) {
            return AgentToolSupport.error(objectMapper, exception);
        }
    }

    /**
     * 跨项目统计最近三十天同角色、同 Skill 已完成任务数。
     *
     * @param role 任务角色
     * @param skill Skill 名称
     * @return 已完成次数
     */
    private long completedSimilarTasks(String role, String skill) {
        Instant cutoff = Instant.now().minus(Duration.ofDays(30));
        return workspaceRepository.listProjectIds().stream()
                .flatMap(projectId -> eventService.readAll(projectId).stream())
                .filter(event -> "task_done_received".equals(event.get("action")))
                .filter(event -> eventAfter(event, cutoff))
                .map(event -> event.get("payload"))
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .filter(payload -> role.equals(textValue(payload.get("from_role"))))
                .filter(payload -> skill.equals(textValue(payload.get("skill"))))
                .count();
    }

    /** 判断事件时间是否位于统计窗口内；缺失或损坏时间不计入历史。 */
    private static boolean eventAfter(Map<String, Object> event, Instant cutoff) {
        try {
            return OffsetDateTime.parse(textValue(event.get("ts"))).toInstant().compareTo(cutoff) >= 0;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /** 使用与 Python 规则一致的 MD5 大整数模一百得到幂等抽查桶。 */
    private static int stableSampleBucket(String messageId) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5")
                    .digest(messageId.getBytes(StandardCharsets.UTF_8));
            return new BigInteger(1, digest).mod(BigInteger.valueOf(100L)).intValue();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 不支持 MD5 摘要", exception);
        }
    }

    /** 从嵌套 Map 中读取数值，字段不存在或类型不合法时使用默认值。 */
    private static double nestedNumberValue(
            Map<String, Object> source,
            String parentKey,
            String childKey,
            double defaultValue
    ) {
        Object nested = source.get(parentKey);
        if (!(nested instanceof Map<?, ?> nestedMap)) {
            return defaultValue;
        }
        return numberValue(nestedMap.get(childKey), defaultValue);
    }

    /** 将 JSON 数值或数值字符串转换为 double。 */
    private static double numberValue(Object value, double defaultValue) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? defaultValue : Double.parseDouble(value.toString());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    /** 将可空 JSON 字段安全转换为去空白字符串。 */
    private static String textValue(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    /**
     * 向人类发送消息并在关联项目存在时追加对应联动事件。
     *
     * @param routingKey 飞书或测试路由键
     * @param message Markdown 消息正文
     * @param kind 消息用途，缺省为 info
     * @param projectId 可选关联项目
     * @param checkpointId checkpoint_request 必需的确认标识
     * @param runtimeContext AgentScope 注入的当前执行上下文，不进入工具 schema
     * @return 发送语义结果 JSON
     */
    @Tool(
            name = "send_to_human",
            description = "Manager 专用：向当前人类路由发送消息，并为关联项目记录用途对应事件；routing_key 固定传 __current__。",
            concurrencySafe = true
    )
    public String sendToHuman(
            @ToolParam(
                    name = "routing_key",
                    description = "固定传 __current__；运行时从当前外部消息或项目历史安全解析真实路由"
            ) String routingKey,
            @ToolParam(name = "message", description = "发送给人类的 Markdown 文本") String message,
            @ToolParam(
                    name = "kind",
                    required = false,
                    description = "info/checkpoint_request/proposal_review/delivery/evolution_report/error_alert"
            ) String kind,
            @ToolParam(name = "project_id", required = false, description = "可选关联项目 ID") String projectId,
            @ToolParam(
                    name = "checkpoint_id",
                    required = false,
                    description = "kind=checkpoint_request 时必填"
            ) String checkpointId,
            RuntimeContext runtimeContext
    ) {
        String normalizedKind = kind == null || kind.isBlank() ? "info" : kind;
        try {
            if (!VALID_KINDS.contains(normalizedKind)) {
                throw new IllegalArgumentException("invalid kind: " + normalizedKind);
            }
            if ("checkpoint_request".equals(normalizedKind)
                    && (checkpointId == null || checkpointId.isBlank())) {
                throw new IllegalArgumentException("checkpoint_request requires checkpoint_id");
            }
            String checkedRoutingKey = resolveHumanRoutingKey(routingKey, projectId, runtimeContext);
            String checkedMessage = AgentToolSupport.requireText(message, "message");
            sendBestEffort(checkedRoutingKey, checkedMessage);
            appendOutboundEventBestEffort(projectId, normalizedKind, checkedRoutingKey, checkpointId);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("routing_key", checkedRoutingKey);
            response.put("kind", normalizedKind);
            response.put("checkpoint_id", Objects.requireNonNullElse(checkpointId, ""));
            return AgentToolSupport.success(objectMapper, response);
        } catch (RuntimeException exception) {
            return AgentToolSupport.error(objectMapper, exception);
        }
    }

    /**
     * 优先使用可信运行上下文或项目历史中的人类路由，避免模型臆造收件人。
     *
     * @param proposedRoutingKey 模型提供的候选路由
     * @param projectId 可选项目标识
     * @param runtimeContext 当前 AgentScope 执行上下文
     * @return 已验证的人类路由键
     */
    private String resolveHumanRoutingKey(
            String proposedRoutingKey,
            String projectId,
            RuntimeContext runtimeContext
    ) {
        String currentRoutingKey = runtimeContext == null
                ? null
                : runtimeContext.get("routingKey", String.class);
        if (isHumanRoutingKey(currentRoutingKey)) {
            return currentRoutingKey;
        }
        String projectRoutingKey = findProjectHumanRoutingKey(projectId);
        if (projectRoutingKey != null) {
            return projectRoutingKey;
        }
        return AgentToolSupport.requireText(proposedRoutingKey, "routing_key");
    }

    /**
     * 从项目事件尾部恢复最近一次已验证的人类出站路由。
     *
     * @param projectId 可选项目标识
     * @return 最近的人类路由；项目无记录时返回 null
     */
    private String findProjectHumanRoutingKey(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return null;
        }
        try {
            java.util.List<Map<String, Object>> events = eventService.readAll(projectId);
            for (int index = events.size() - 1; index >= 0; index--) {
                Object payloadValue = events.get(index).get("payload");
                if (!(payloadValue instanceof Map<?, ?> payload)) {
                    continue;
                }
                Object routingValue = payload.get("routing_key");
                String routingKey = routingValue == null ? null : routingValue.toString();
                if (isHumanRoutingKey(routingKey)) {
                    return routingKey;
                }
            }
        } catch (RuntimeException ignored) {
            // 路由恢复属于保护措施，项目尚未初始化时仍允许校验模型提供的候选路由。
        }
        return null;
    }

    /**
     * 判断路由是否指向外部人类会话，而非团队内部角色。
     *
     * @param routingKey 待判断路由
     * @return 非空且不以 team: 开头时返回 true
     */
    private static boolean isHumanRoutingKey(String routingKey) {
        return routingKey != null && !routingKey.isBlank() && !routingKey.startsWith("team:");
    }

    /**
     * 最多等待十五秒发送消息；与 Python 实现一致，超时或发送异常不阻断后续事件记录。
     *
     * @param routingKey 目标路由
     * @param message 消息正文
     */
    private void sendBestEffort(String routingKey, String message) {
        if (senderGateway == null) {
            return;
        }
        try {
            senderGateway.send(routingKey, message, "")
                    .orTimeout(15, TimeUnit.SECONDS)
                    .exceptionally(ignored -> null)
                    .join();
        } catch (RuntimeException ignored) {
            // 人类出站失败按源实现降级，项目事件仍可继续记录。
        }
    }

    /**
     * 项目标识合法时追加用途事件；事件失败不改变已经完成的人类出站结果。
     *
     * @param projectId 可选项目标识
     * @param kind 消息用途
     * @param routingKey 目标路由
     * @param checkpointId 可选确认标识
     */
    private void appendOutboundEventBestEffort(
            String projectId,
            String kind,
            String routingKey,
            String checkpointId
    ) {
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        try {
            String checkedProjectId = AgentToolSupport.requireProjectId(projectId);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("routing_key", routingKey);
            payload.put("kind", kind);
            if (checkpointId != null && !checkpointId.isBlank()) {
                payload.put("checkpoint_id", checkpointId);
            }
            eventService.append(checkedProjectId, "manager", EVENT_BY_KIND.get(kind), payload);
        } catch (RuntimeException ignored) {
            // 源实现把联动事件视为 best effort，不能反向覆盖已发送结果。
        }
    }
}
