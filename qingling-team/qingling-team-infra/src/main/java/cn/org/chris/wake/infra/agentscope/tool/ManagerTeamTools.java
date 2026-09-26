package cn.org.chris.wake.infra.agentscope.tool;

import cn.org.chris.wake.domain.event.EventService;
import cn.org.chris.wake.domain.gateway.SenderGateway;
import cn.org.chris.wake.domain.gateway.WorkspaceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.util.LinkedHashMap;
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
     * 向人类发送消息并在关联项目存在时追加对应联动事件。
     *
     * @param routingKey 飞书或测试路由键
     * @param message Markdown 消息正文
     * @param kind 消息用途，缺省为 info
     * @param projectId 可选关联项目
     * @param checkpointId checkpoint_request 必需的确认标识
     * @return 发送语义结果 JSON
     */
    @Tool(
            name = "send_to_human",
            description = "Manager 专用：向人类发送消息，并为关联项目记录用途对应事件。",
            concurrencySafe = true
    )
    public String sendToHuman(
            @ToolParam(name = "routing_key", description = "飞书 routing_key，如 p2p:{open_id}") String routingKey,
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
            ) String checkpointId
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
            String checkedRoutingKey = AgentToolSupport.requireText(routingKey, "routing_key");
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
