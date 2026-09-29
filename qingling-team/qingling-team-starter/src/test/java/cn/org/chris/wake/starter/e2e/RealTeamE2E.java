package cn.org.chris.wake.starter.e2e;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

/**
 * 使用真实 Qwen、四角色 AgentScope 和 AIO-Sandbox 执行 Task 18 指定场景。
 */
class RealTeamE2E {

    /** Maven `e2e.scenario` 支持的四个稳定场景名称。 */
    private static final Set<String> SCENARIOS = Set.of(
            "happy-path", "checkpoint-revise", "qa-defect-rd-fix", "code-fail-recovery"
    );

    /** 四场景对应的初始用户需求。 */
    private static final Map<String, String> PROMPTS = Map.of(
            "happy-path",
            "帮我做个超简单的待办清单小网站，MVP 只要添加、列出、完成、删除四个功能。"
                    + "技术栈是 FastAPI、SQLite、单文件 HTML 和原生 JavaScript。"
                    + "所有未说明细节由你决定。请立即创建项目并写需求，但必须先发送需求 checkpoint_request，"
                    + "停在需求确认点等待我的下一条消息；不得把本消息视为预先批准，也不得提前分派 PM。",
            "checkpoint-revise",
            "帮我做个待办清单小网站，包含增、查、完成、删除。技术栈使用 FastAPI、SQLite、原生 HTML/JS。"
                    + "所有未说明细节由你决定。请立即创建项目并起草需求文档，但必须先发送需求 checkpoint_request，"
                    + "停在需求确认点等待我的下一条消息；不得提前分派 PM。",
            "qa-defect-rd-fix",
            "做一个待办清单小网站 MVP。严格硬约束：DELETE /todo/{id} 必须物理删除，第一轮研发故意实现成 soft delete，"
                    + "QA 必须通过测试发现缺陷并发给 RD 修复，修复后重新运行 pytest 直至全通过。"
                    + "技术栈是 FastAPI、SQLite、原生 HTML/JS；所有其他细节由你决定。请立即创建项目并写需求，"
                    + "但必须先发送需求 checkpoint_request 并等待我的下一条批准消息，不得提前分派 PM。",
            "code-fail-recovery",
            "做一个最小待办清单，技术栈是 FastAPI、SQLite、HTML/JS。"
                    + "RD 第一轮必须故意引入一个能被 pytest 捕获的小 bug，再读取 stderr 分析并修复，"
                    + "最终 task_done 必须记录 pytest_attempts 至少为 2；其他细节由你决定。请立即创建项目并写需求，"
                    + "但必须先发送需求 checkpoint_request 并等待我的下一条批准消息，不得提前分派 PM。"
    );

    /**
     * 只运行 Maven 指定的单个真实场景，缺少凭据或沙盒时明确失败而不是跳过。
     */
    @Test
    void shouldCompleteSelectedRealScenario() throws Exception {
        String scenario = System.getProperty("e2e.scenario", "").strip();
        if (!SCENARIOS.contains(scenario)) {
            throw new AssertionError("必须通过 -De2e.scenario 指定场景，可选值: " + SCENARIOS);
        }
        try (RealTeamE2EDriver driver = RealTeamE2EDriver.start(scenario)) {
            driver.say(PROMPTS.get(scenario));
            driver.waitForProjectAndRequirements();
            if ("checkpoint-revise".equals(scenario)) {
                driver.reviseRequirements();
            }
            driver.approveRequirementsAndWaitForDelivery();
            driver.approveDelivery();
            driver.assertCommonContract();
            driver.assertScenarioContract(scenario);
            driver.saveEvidence();
        }
    }
}
