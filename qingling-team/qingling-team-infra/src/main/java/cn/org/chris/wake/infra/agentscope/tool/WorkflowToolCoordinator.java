package cn.org.chris.wake.infra.agentscope.tool;

import java.util.Map;

/**
 * 隔离 AgentScope 工具与 app 层 runtime v2 协调器，保持 infra 不反向依赖 app。
 */
public interface WorkflowToolCoordinator {

    /**
     * 判断当前运行时是否启用 runtime v2 新项目协调链。
     *
     * @return coordinated 配置开启时返回 true
     */
    boolean runtimeV2Enabled();

    /**
     * 为新项目创建冻结 profile/policy 的 aggregate 与需求 checkpoint。
     *
     * @param projectId 项目标识
     * @param projectName 项目名称
     * @return profile、policy 与初始 execution 的有限结果
     */
    Map<String, Object> initializeProject(String projectId, String projectName);

    /**
     * 判断项目是否已经由 runtime v2 aggregate 接管。
     *
     * @param projectId 项目标识
     * @return coordinated 项目返回 true
     */
    boolean coordinated(String projectId);

    /**
     * 将投影 assignment 绑定到同一 WorkExecution，并记录当前 dispatch 与 Agent session。
     *
     * @param projectId 项目标识
     * @param role 当前 Owner
     * @param assignmentId 邮箱投影标识
     * @param assignmentContent task_assign 结构化正文
     * @param dispatchId 当前稳定 dispatch 标识
     * @param agentSessionId 当前 AgentScope session 标识
     */
    void claimAndStart(
            String projectId,
            String role,
            String assignmentId,
            Object assignmentContent,
            String dispatchId,
            String agentSessionId
    );

    /**
     * 在真实 Test Runner 启动前绑定最新 execution 并消耗当前工作尝试的调用预算。
     *
     * @param projectId 项目标识
     * @param role 当前阶段 Owner
     * @param stage 测试启动时冻结的业务阶段名称
     * @param testExecutionId 即将启动的 Test Runner execution 标识
     */
    void beginTestExecution(
            String projectId,
            String role,
            String stage,
            String testExecutionId
    );

    /**
     * 将最小 task_done 正文转换为一次原子完成命令。
     *
     * @param projectId 项目标识
     * @param role 当前 Owner
     * @param subject 兼容旧正文时使用的有界主题
     * @param content Map 或合法 JSON 解析后的正文
     * @return 可直接返回模型的有限权威结果
     */
    Map<String, Object> complete(
            String projectId,
            String role,
            String subject,
            Object content
    );

    /**
     * 以 review_round_id 和有限 verdict 提交 Reviewer 结论。
     *
     * @param projectId 项目标识
     * @param reviewer 当前 Reviewer 角色
     * @param content Map 或合法 JSON 解析后的正文
     * @return 可直接返回模型的有限权威结果
     */
    Map<String, Object> submitReview(String projectId, String reviewer, Object content);

    /**
     * 处理人类需求或交付 checkpoint 的有限结论。
     *
     * @param projectId 项目标识
     * @param signal checkpoint 有限信号
     * @return Java 固定迁移结果
     */
    Map<String, Object> resolveCheckpoint(String projectId, String signal);

    /**
     * 创建只报告 legacy 模式、且拒绝 coordinated 操作的空实现。
     *
     * @return legacy 空协调器
     */
    static WorkflowToolCoordinator legacy() {
        return new WorkflowToolCoordinator() {

            /** legacy 运行时不为新项目启用 runtime v2。 */
            @Override
            public boolean runtimeV2Enabled() {
                return false;
            }

            /** legacy 项目初始化继续由旧 workspace/event 链处理。 */
            @Override
            public Map<String, Object> initializeProject(String projectId, String projectName) {
                return Map.of("coordinated", false);
            }

            /** legacy 项目从不由 runtime v2 接管。 */
            @Override
            public boolean coordinated(String projectId) {
                return false;
            }

            /** legacy 路径不调用 runtime v2 领取。 */
            @Override
            public void claimAndStart(
                    String projectId,
                    String role,
                    String assignmentId,
                    Object assignmentContent,
                    String dispatchId,
                    String agentSessionId
            ) {
                throw new IllegalStateException("legacy 项目不支持 runtime v2 领取");
            }

            /** legacy 路径不绑定 runtime v2 测试 execution。 */
            @Override
            public void beginTestExecution(
                    String projectId,
                    String role,
                    String stage,
                    String testExecutionId
            ) {
                throw new IllegalStateException("legacy 项目不支持 runtime v2 测试绑定");
            }

            /** legacy 路径不调用 runtime v2 完成。 */
            @Override
            public Map<String, Object> complete(
                    String projectId,
                    String role,
                    String subject,
                    Object content
            ) {
                throw new IllegalStateException("legacy 项目不支持 runtime v2 完成");
            }

            /** legacy 路径不调用 runtime v2 评审。 */
            @Override
            public Map<String, Object> submitReview(String projectId, String reviewer, Object content) {
                throw new IllegalStateException("legacy 项目不支持 runtime v2 评审");
            }

            /** legacy checkpoint 继续由旧 advance_workflow 处理。 */
            @Override
            public Map<String, Object> resolveCheckpoint(String projectId, String signal) {
                throw new IllegalStateException("legacy 项目不支持 runtime v2 checkpoint");
            }
        };
    }
}
