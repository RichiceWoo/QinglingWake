package cn.org.chris.wake.domain.event;

import cn.org.chris.wake.domain.gateway.EventRepository;
import cn.org.chris.wake.domain.workspace.WorkspacePolicy;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 执行事件单写者和动作白名单规则，并将顺序号分配委托给持久层原子操作。
 */
public final class EventService {

    /** 只能由 Java 专用方法写入、禁止通用模型工具伪造的内部动作。 */
    private static final Set<String> RESERVED_ACTIONS = Set.of("workflow_transitioned");

    /** 与 Python 附录 A.3 一致的固定事件动作。 */
    public static final Set<String> VALID_ACTIONS = Set.of(
            "project_created", "archived",
            "requirements_discovery_started", "requirements_coverage_assessed", "requirements_drafted",
            "checkpoint_requested", "checkpoint_reply_classified", "checkpoint_approved", "checkpoint_rejected",
            "assigned", "task_done_received", "revision_requested", "rd_delivered",
            "review_criteria_checked", "decided_insert_review", "review_requested", "review_received",
            "delivery_requested", "delivered",
            "workflow_transitioned", "completion_rejected",
            "retro_triggered", "retro_report_received", "retro_proposal_invalid",
            "retro_approved_by_manager", "retro_approved_by_human", "retro_rejected_by_human",
            "retro_apply_failed", "task_quality_adjusted", "evolved",
            "error_alert_raised", "recovered_checkpoint_response",
            "info_sent", "checkpoint_request_sent", "proposal_review_sent",
            "delivery_sent", "evolution_report_sent", "error_alert_sent"
    );

    /** append-only 事件持久化端口。 */
    private final EventRepository repository;

    /**
     * 创建事件领域服务。
     *
     * @param repository 事件持久化端口
     */
    public EventService(EventRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
    }

    /**
     * 校验 Manager 单写者和动作名称后追加事件。
     *
     * @param projectId 所属项目
     * @param actor 事件发起者，只能为 manager
     * @param action 附录 A.3 动作
     * @param payload 事件详情
     * @return 从 1 开始的项目内 seq
     */
    public long append(String projectId, String actor, String action, Map<String, Object> payload) {
        WorkspacePolicy.validateProjectId(projectId);
        if (!"manager".equals(actor)) {
            throw new IllegalArgumentException("actor 必须是 manager");
        }
        if (!isValidAction(action)) {
            throw new IllegalArgumentException("unknown action: " + action);
        }
        if (RESERVED_ACTIONS.contains(action)) {
            throw new IllegalArgumentException("reserved action 只能由 Java 状态机写入: " + action);
        }
        Map<String, Object> safePayload = payload == null ? Map.of() : Map.copyOf(payload);
        return repository.append(projectId, actor, action, safePayload);
    }

    /**
     * 由 Java 状态机专用入口追加阶段迁移，通用 append_event 工具无法调用该语义。
     *
     * @param projectId 所属项目
     * @param payload 状态机生成的不可变迁移详情
     * @return 从 1 开始的项目内 seq
     */
    public long appendWorkflowTransition(String projectId, Map<String, Object> payload) {
        WorkspacePolicy.validateProjectId(projectId);
        Map<String, Object> safePayload = payload == null ? Map.of() : Map.copyOf(payload);
        return repository.append(projectId, "manager", "workflow_transitioned", safePayload);
    }

    /**
     * 读取全部合法事件，损坏行由 Repository 忽略。
     *
     * @param projectId 所属项目
     * @return 按文件顺序排列的事件
     */
    public List<Map<String, Object>> readAll(String projectId) {
        WorkspacePolicy.validateProjectId(projectId);
        return repository.readAll(projectId);
    }

    /**
     * 返回最后 n 条事件；n 为零时返回空列表。
     *
     * @param projectId 所属项目
     * @param count 需要返回的尾部数量
     * @return 最后 count 条事件
     */
    public List<Map<String, Object>> tail(String projectId, int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count 不能小于零");
        }
        List<Map<String, Object>> events = readAll(projectId);
        int from = Math.max(0, events.size() - count);
        return List.copyOf(events.subList(from, events.size()));
    }

    /**
     * 判断固定动作或 retro_applied_by_{role} 通配动作是否合法。
     *
     * @param action 待校验动作
     * @return 合法时为 true
     */
    public static boolean isValidAction(String action) {
        if (action == null) {
            return false;
        }
        if (VALID_ACTIONS.contains(action)) {
            return true;
        }
        if (!action.startsWith("retro_applied_by_")) {
            return false;
        }
        String role = action.substring("retro_applied_by_".length());
        return WorkspacePolicy.ROLES.contains(role);
    }
}
