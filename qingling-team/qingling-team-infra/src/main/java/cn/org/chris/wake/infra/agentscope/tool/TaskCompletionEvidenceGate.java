package cn.org.chris.wake.infra.agentscope.tool;

import cn.org.chris.wake.domain.gateway.WorkspaceRepository;
import cn.org.chris.wake.domain.workflow.WorkflowSignal;
import cn.org.chris.wake.domain.workflow.WorkflowStage;
import cn.org.chris.wake.infra.agentscope.ProjectTestExecution;
import cn.org.chris.wake.infra.agentscope.ProjectTestExecutionRegistry;

import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 在 Java 侧校验 RD 与 QA 成功回报的不可绕过证据。
 */
public final class TaskCompletionEvidenceGate {

    /** 测试不得依赖不存在或歧义的顶层 app 包，统一从 code 根目录公开模块导入。 */
    private static final Pattern FORBIDDEN_APP_IMPORT = Pattern.compile(
            "(?m)^\\s*(?:from\\s+app(?:\\.|\\s)|import\\s+app(?:\\.|\\s|$))"
    );

    /** 共享项目文件读取端口。 */
    private final WorkspaceRepository workspaceRepository;

    /** 只由真实 run_project_tests 写入的运行时执行证据账本。 */
    private final ProjectTestExecutionRegistry executionRegistry;

    /**
     * 创建任务完成证据门禁。
     *
     * @param workspaceRepository 共享项目文件读取端口
     * @param executionRegistry 真实测试执行证据账本
     */
    public TaskCompletionEvidenceGate(
            WorkspaceRepository workspaceRepository,
            ProjectTestExecutionRegistry executionRegistry
    ) {
        this.workspaceRepository = Objects.requireNonNull(workspaceRepository, "workspaceRepository 不能为空");
        this.executionRegistry = Objects.requireNonNull(executionRegistry, "executionRegistry 不能为空");
    }

    /**
     * 按当前 Java 阶段校验角色的 task_done 正文和共享产物。
     *
     * @param projectId 项目标识
     * @param stage 当前状态机阶段
     * @param role 回报角色
     * @param content task_done 正文
     */
    public void validate(String projectId, WorkflowStage stage, String role, Object content) {
        if ((stage == WorkflowStage.CODE_IMPLEMENTATION || stage == WorkflowStage.DEFECT_FIX)
                && "rd".equals(role)) {
            ProjectTestExecution execution = requireRecordedExecution(projectId, stage, role, content);
            validateRd(projectId, execution);
        }
        if (stage == WorkflowStage.QA_TEST_EXECUTION && "qa".equals(role)) {
            ProjectTestExecution execution = requireRecordedExecution(projectId, stage, role, content);
            validateQa(projectId, execution);
        }
    }

    /**
     * 在 Manager 推进阶段前校验测试终态与信号语义一致，避免失败结果被当作通过。
     *
     * @param projectId 项目标识
     * @param stage 当前工作流阶段
     * @param role 当前阶段 Owner
     * @param content 原始 task_done 结构化正文
     * @param signal Manager 提交的工作流结论
     */
    public void validateTransition(
            String projectId,
            WorkflowStage stage,
            String role,
            Object content,
            WorkflowSignal signal
    ) {
        validate(projectId, stage, role, content);
        if (stage != WorkflowStage.QA_TEST_EXECUTION || !"qa".equals(role)) {
            return;
        }
        ProjectTestExecution execution = requireRecordedExecution(projectId, stage, role, content);
        if (signal == WorkflowSignal.STAGE_ACCEPTED
                && execution.status() != ProjectTestExecution.Status.SUCCEEDED) {
            throw new IllegalStateException("QA 阶段接受被拒绝：真实测试执行未成功");
        }
        if (signal == WorkflowSignal.QA_DEFECT_FOUND
                && execution.status() != ProjectTestExecution.Status.TEST_FAILED) {
            throw new IllegalStateException("QA 缺陷结论被拒绝：真实测试执行没有 pytest 失败");
        }
    }

    /**
     * 要求 RD 引用的真实执行已经成功，并具有有效 pytest 与覆盖率证据。
     *
     * @param projectId 当前项目标识
     * @param execution 已与当前项目、角色和阶段绑定的真实执行
     */
    private void validateRd(String projectId, ProjectTestExecution execution) {
        if (execution.status() != ProjectTestExecution.Status.SUCCEEDED
                || execution.exitCode() == null
                || execution.exitCode() != 0) {
            throw new IllegalStateException("RD task_done 被拒绝：真实测试执行不是 exit_code=0 的成功终态");
        }
        if (execution.pytestCollected() <= 0 || execution.pytestPassed() != execution.pytestCollected()) {
            throw new IllegalStateException("RD task_done 被拒绝：真实 pytest 未全部通过");
        }
        if (execution.coveragePercent() < 0D || execution.coveragePercent() > 100D) {
            throw new IllegalStateException("RD task_done 被拒绝：真实执行缺少合法覆盖率证据");
        }
        if (!readableNonBlank(projectId, "code/main.py")) {
            throw new IllegalStateException("RD task_done 被拒绝：缺少唯一应用入口 code/main.py");
        }
        if (readableNonBlank(projectId, "code/app/main.py")) {
            throw new IllegalStateException("RD task_done 被拒绝：禁止同时存在替代入口 code/app/main.py");
        }
        validatePythonArtifactContract(projectId);
    }

    /**
     * 要求 QA 已生成非空报告，并在独立文件或报告正文中提供证据矩阵。
     *
     * @param projectId 项目标识
     * @param execution 已与当前 QA 阶段绑定的真实执行
     */
    private void validateQa(String projectId, ProjectTestExecution execution) {
        if (execution.status() != ProjectTestExecution.Status.SUCCEEDED
                && execution.status() != ProjectTestExecution.Status.TEST_FAILED) {
            throw new IllegalStateException("QA task_done 被拒绝：真实测试执行不是 pytest 终态");
        }
        if (execution.pytestCollected() <= 0) {
            throw new IllegalStateException("QA task_done 被拒绝：真实执行未收集到 pytest 用例");
        }
        validatePythonArtifactContract(projectId);
        String report = readRequired(projectId, "qa/test_report.md", "QA task_done 被拒绝：缺少 qa/test_report.md");
        boolean inlineMatrix = report.contains("证据矩阵")
                || report.toLowerCase(Locale.ROOT).contains("evidence matrix");
        boolean separateMatrix = readableNonBlank(projectId, "qa/evidence_matrix.md");
        if (!inlineMatrix && !separateMatrix) {
            throw new IllegalStateException("QA task_done 被拒绝：缺少测试证据矩阵");
        }
    }

    /**
     * 解析 task_done 的 execution ID，并逐字段核对真实 Test Runner 终态。
     *
     * @param projectId 当前项目
     * @param stage 当前工作流阶段
     * @param role 当前任务 Owner
     * @param content task_done 结构化正文
     * @return 完全匹配的真实执行终态
     */
    private ProjectTestExecution requireRecordedExecution(
            String projectId,
            WorkflowStage stage,
            String role,
            Object content
    ) {
        if (!(content instanceof Map<?, ?> values)) {
            throw new IllegalStateException(role.toUpperCase(Locale.ROOT)
                    + " task_done 被拒绝：content 必须是结构化 JSON");
        }
        String executionId = findText(values, "execution_id");
        if (executionId == null || executionId.isBlank()) {
            throw new IllegalStateException(role.toUpperCase(Locale.ROOT)
                    + " task_done 被拒绝：缺少 execution_id");
        }
        ProjectTestExecution execution = executionRegistry.find(projectId, role, stage, executionId)
                .map(ProjectTestExecutionRegistry.RecordedExecution::execution)
                .orElseThrow(() -> new IllegalStateException(role.toUpperCase(Locale.ROOT)
                        + " task_done 被拒绝：execution_id 不属于当前项目、角色和阶段的真实执行"));
        assertText(values, "status", execution.status().name());
        assertNullableInteger(values, "exit_code", execution.exitCode());
        assertInteger(values, "pytest_collected", execution.pytestCollected());
        assertInteger(values, "pytest_passed", execution.pytestPassed());
        assertDouble(values, "coverage_percent", execution.coveragePercent());
        assertText(values, "failure_type", execution.failureType().name());
        assertBoolean(values, "retryable", execution.retryable());
        return execution;
    }

    /** 校验字符串证据字段与真实执行完全一致。 */
    private static void assertText(Map<?, ?> values, String key, String expected) {
        String actual = findText(values, key);
        if (!expected.equals(actual)) {
            throw evidenceMismatch(key);
        }
    }

    /** 校验可空整数证据字段与真实执行完全一致。 */
    private static void assertNullableInteger(Map<?, ?> values, String key, Integer expected) {
        Number actual = findNumber(values, key);
        if (actual == null || expected == null || actual.intValue() != expected) {
            throw evidenceMismatch(key);
        }
    }

    /** 校验整数证据字段与真实执行完全一致。 */
    private static void assertInteger(Map<?, ?> values, String key, int expected) {
        Number actual = findNumber(values, key);
        if (actual == null || actual.intValue() != expected) {
            throw evidenceMismatch(key);
        }
    }

    /** 校验浮点证据字段与真实执行完全一致。 */
    private static void assertDouble(Map<?, ?> values, String key, double expected) {
        Number actual = findNumber(values, key);
        if (actual == null || Math.abs(actual.doubleValue() - expected) > 0.0001D) {
            throw evidenceMismatch(key);
        }
    }

    /** 校验布尔证据字段与真实执行完全一致。 */
    private static void assertBoolean(Map<?, ?> values, String key, boolean expected) {
        Object actual = findValue(values, key);
        boolean parsed;
        if (actual instanceof Boolean bool) {
            parsed = bool;
        } else if (actual instanceof String text && ("true".equalsIgnoreCase(text)
                || "false".equalsIgnoreCase(text))) {
            parsed = Boolean.parseBoolean(text);
        } else {
            throw evidenceMismatch(key);
        }
        if (parsed != expected) {
            throw evidenceMismatch(key);
        }
    }

    /** 构造不暴露运行时内部值的字段不匹配异常。 */
    private static IllegalStateException evidenceMismatch(String key) {
        return new IllegalStateException("task_done 被拒绝：" + key + " 与真实 run_project_tests 结果不一致");
    }

    /** 递归查找首个候选文本字段。 */
    private static String findText(Map<?, ?> values, String key) {
        Object value = findValue(values, key);
        return value == null ? null : value.toString();
    }

    /** 递归查找字段值，优先使用当前对象的直接字段。 */
    private static Object findValue(Map<?, ?> values, String key) {
        if (values.containsKey(key)) {
            return values.get(key);
        }
        for (Object value : values.values()) {
            if (value instanceof Map<?, ?> nested) {
                Object found = findValue(nested, key);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * 递归查找候选字段中的数值，兼容 evidence/pytest 等嵌套对象。
     *
     * @param values 待搜索结构
     * @param keys 候选字段名
     * @return 首个数值；不存在时返回 null
     */
    private static Number findNumber(Map<?, ?> values, String... keys) {
        for (String key : keys) {
            Object value = findValue(values, key);
            Number number = toNumber(value);
            if (number != null) {
                return number;
            }
        }
        return null;
    }

    /**
     * 将数值或纯数字字符串转换为 Number。
     *
     * @param value 原始字段值
     * @return 数值；无法转换时返回 null
     */
    private static Number toNumber(Object value) {
        if (value instanceof Number number) {
            return number;
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 读取必须存在的非空共享文件。
     *
     * @param projectId 项目标识
     * @param path 项目内相对路径
     * @param errorMessage 缺失时错误信息
     * @return 非空正文
     */
    private String readRequired(String projectId, String path, String errorMessage) {
        try {
            String content = workspaceRepository.read(projectId, "qa", path);
            if (content == null || content.isBlank()) {
                throw new IllegalStateException(errorMessage);
            }
            return content;
        } catch (RuntimeException exception) {
            if (exception instanceof IllegalStateException) {
                throw exception;
            }
            throw new IllegalStateException(errorMessage);
        }
    }

    /**
     * 判断可选共享文件是否存在且非空。
     *
     * @param projectId 项目标识
     * @param path 项目内相对路径
     * @return 文件存在且非空时为 true
     */
    private boolean readableNonBlank(String projectId, String path) {
        try {
            return !workspaceRepository.read(projectId, "qa", path).isBlank();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * 强制 Python 产物只有 code/main.py 入口，且测试从 code 根公开模块导入。
     *
     * @param projectId 当前项目标识
     */
    private void validatePythonArtifactContract(String projectId) {
        List<String> paths = workspaceRepository.list(projectId, "qa");
        if (paths.contains("code/app/main.py")) {
            throw new IllegalStateException("task_done 被拒绝：禁止存在替代入口 code/app/main.py");
        }
        for (String path : paths) {
            if (!path.endsWith(".py")
                    || !(path.startsWith("code/tests/") || path.startsWith("qa/contract_tests/"))) {
                continue;
            }
            String content = workspaceRepository.read(projectId, "qa", path);
            if (FORBIDDEN_APP_IMPORT.matcher(content).find()) {
                throw new IllegalStateException(
                        "task_done 被拒绝：测试必须从 code 根公开模块导入，禁止依赖 app 包: " + path
                );
            }
        }
    }
}
