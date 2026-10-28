package cn.org.chris.wake.infra.agentscope;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记录父 Agent 回合内已领取的完成义务与尚未解决的协议拒绝，禁止普通文本静默结束。
 *
 * <p>注册表仅保存角色、会话和项目标识，不保存提示词、工具参数、模型输出或异常正文。</p>
 */
public final class CompletionProtocolRegistry {

    /** 按角色和 AgentScope session 隔离的本回合完成义务。 */
    private final ConcurrentHashMap<InvocationKey, Expectation> expectations = new ConcurrentHashMap<>();

    /** 按角色和 AgentScope session 隔离的未解决拒绝记录。 */
    private final ConcurrentHashMap<InvocationKey, Rejection> rejections = new ConcurrentHashMap<>();

    /**
     * 开始新父回合时清除同 session 的历史义务与拒绝，避免恢复回合继承旧状态。
     *
     * @param role 当前团队角色
     * @param sessionId AgentScope session 标识
     */
    public void begin(String role, String sessionId) {
        InvocationKey invocationKey = key(role, sessionId);
        expectations.remove(invocationKey);
        rejections.remove(invocationKey);
    }

    /**
     * 在 coordinated task_assign 成功绑定为 RUNNING 后登记本回合必须完成该项目。
     *
     * @param role 当前团队角色
     * @param sessionId AgentScope session 标识
     * @param projectId 当前项目标识
     */
    public void expect(String role, String sessionId, String projectId) {
        expect(role, sessionId, projectId, "send_task_done");
    }

    /**
     * 登记带确定性下一步的完成义务，供父回合退出和恢复 assignment 直接提示缺失动作。
     *
     * @param role 当前团队角色
     * @param sessionId AgentScope session 标识
     * @param projectId 当前项目标识
     * @param requiredAction 当前阶段必须完成的稳定动作名
     */
    public void expect(String role, String sessionId, String projectId, String requiredAction) {
        expect(role, sessionId, projectId, requiredAction, false);
    }

    /**
     * 登记带工具顺序门禁的完成义务，使恢复回合必须先取得真实测试结果再继续改代码。
     *
     * @param role 当前团队角色
     * @param sessionId AgentScope session 标识
     * @param projectId 当前项目标识
     * @param requiredAction 当前阶段必须完成的稳定动作名
     * @param testBeforeCodeWrite 是否禁止本回合在首次 Test Runner 调用前继续写 code 目录
     */
    public void expect(
            String role,
            String sessionId,
            String projectId,
            String requiredAction,
            boolean testBeforeCodeWrite
    ) {
        if (blank(role) || blank(sessionId) || blank(projectId)) {
            return;
        }
        String checkedAction = blank(requiredAction) ? "send_task_done" : requiredAction.trim();
        expectations.put(
                key(role, sessionId),
                new Expectation(projectId, checkedAction, testBeforeCodeWrite, false)
        );
    }

    /**
     * 记录当前父回合已经真实进入 Test Runner，解除恢复回合的测试前写入门禁。
     *
     * @param role 当前团队角色
     * @param sessionId AgentScope session 标识
     * @param projectId Test Runner 调用的项目标识
     */
    public void recordTestInvocation(String role, String sessionId, String projectId) {
        if (blank(role) || blank(sessionId) || blank(projectId)) {
            return;
        }
        InvocationKey invocationKey = key(role, sessionId);
        expectations.computeIfPresent(invocationKey, (ignored, expectation) -> {
            if (!expectation.projectId().equals(projectId)) {
                throw new IllegalArgumentException("Test Runner 项目与当前完成义务不一致");
            }
            return new Expectation(
                    expectation.projectId(),
                    expectation.requiredAction(),
                    expectation.testBeforeCodeWrite(),
                    true
            );
        });
    }

    /**
     * 判断恢复回合是否仍处于“必须先测试”的代码写入禁区。
     *
     * @param role 当前团队角色
     * @param sessionId AgentScope session 标识
     * @param projectId 待写入项目标识
     * @return 尚未调用 Test Runner 且恢复协议要求测试优先时为 true
     */
    public boolean codeWriteBlockedUntilTest(String role, String sessionId, String projectId) {
        return pending(role, sessionId)
                .filter(expectation -> expectation.projectId().equals(projectId))
                .filter(Expectation::testBeforeCodeWrite)
                .filter(expectation -> !expectation.testInvoked())
                .isPresent();
    }

    /**
     * 标记当前回合的 task_done 被 Java 完成协调器拒绝。
     *
     * @param role 当前团队角色
     * @param sessionId AgentScope session 标识
     * @param projectId 当前项目标识
     */
    public void reject(String role, String sessionId, String projectId) {
        if (blank(role) || blank(sessionId) || blank(projectId)) {
            return;
        }
        rejections.put(key(role, sessionId), new Rejection(projectId));
    }

    /**
     * task_done 成功进入权威协调器后清除本回合义务与拒绝，允许模型正常结束。
     *
     * @param role 当前团队角色
     * @param sessionId AgentScope session 标识
     */
    public void resolve(String role, String sessionId) {
        InvocationKey invocationKey = key(role, sessionId);
        expectations.remove(invocationKey);
        rejections.remove(invocationKey);
    }

    /**
     * 查询当前父回合是否仍有未解决的完成协议拒绝。
     *
     * @param role 当前团队角色
     * @param sessionId AgentScope session 标识
     * @return 仅包含项目标识的拒绝记录
     */
    public Optional<Rejection> unresolved(String role, String sessionId) {
        return Optional.ofNullable(rejections.get(key(role, sessionId)));
    }

    /**
     * 查询当前父回合是否领取了任务但尚未成功提交完成协议。
     *
     * @param role 当前团队角色
     * @param sessionId AgentScope session 标识
     * @return 仅包含项目标识的待完成记录
     */
    public Optional<Expectation> pending(String role, String sessionId) {
        return Optional.ofNullable(expectations.get(key(role, sessionId)));
    }

    /**
     * 父回合终止后释放注册表条目，避免长期 session 累积内存状态。
     *
     * @param role 当前团队角色
     * @param sessionId AgentScope session 标识
     */
    public void end(String role, String sessionId) {
        InvocationKey invocationKey = key(role, sessionId);
        expectations.remove(invocationKey);
        rejections.remove(invocationKey);
    }

    /** 判断文本是否缺少可用标识。 */
    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** 生成角色与 session 的隔离键。 */
    private static InvocationKey key(String role, String sessionId) {
        return new InvocationKey(role == null ? "" : role, sessionId == null ? "" : sessionId);
    }

    /** 表示一次父回合尚未修正的完成协议拒绝。 */
    public record Rejection(
            /** 发生拒绝的项目标识。 */ String projectId
    ) {
    }

    /** 表示父回合已领取但尚未通过 task_done 完成的 coordinated 项目。 */
    public record Expectation(
            /** 等待当前 Owner 完成的项目标识。 */ String projectId,
            /** 父回合退出前必须完成的确定性动作。 */ String requiredAction,
            /** 是否要求恢复回合先执行测试再继续写代码。 */ boolean testBeforeCodeWrite,
            /** 当前父回合是否已经真实调用 Test Runner。 */ boolean testInvoked
    ) {
    }

    /** 表示不会跨角色或跨 session 传播的注册表键。 */
    private record InvocationKey(
            /** 当前团队角色。 */ String role,
            /** AgentScope session 标识。 */ String sessionId
    ) {
    }
}
