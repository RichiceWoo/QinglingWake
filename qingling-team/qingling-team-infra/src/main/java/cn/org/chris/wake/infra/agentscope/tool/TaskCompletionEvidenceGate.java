package cn.org.chris.wake.infra.agentscope.tool;

import cn.org.chris.wake.domain.gateway.WorkspaceRepository;
import cn.org.chris.wake.domain.workflow.WorkflowStage;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 在 Java 侧校验 RD 与 QA 成功回报的不可绕过证据。
 */
public final class TaskCompletionEvidenceGate {

    /** 可从字符串覆盖率字段中提取百分比的模式。 */
    private static final Pattern PERCENT_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*%");

    /** 共享项目文件读取端口。 */
    private final WorkspaceRepository workspaceRepository;

    /**
     * 创建任务完成证据门禁。
     *
     * @param workspaceRepository 共享项目文件读取端口
     */
    public TaskCompletionEvidenceGate(WorkspaceRepository workspaceRepository) {
        this.workspaceRepository = Objects.requireNonNull(workspaceRepository, "workspaceRepository 不能为空");
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
            validateRd(content);
        }
        if (stage == WorkflowStage.QA_TEST_EXECUTION && "qa".equals(role)) {
            validateQa(projectId);
        }
    }

    /**
     * 要求 RD 提供退出码、pytest 数量和覆盖率三类真实证据。
     *
     * @param content task_done 正文
     */
    private static void validateRd(Object content) {
        if (!(content instanceof Map<?, ?> values)) {
            throw new IllegalStateException("RD task_done 被拒绝：content 必须是结构化 JSON");
        }
        Number exitCode = findNumber(values, "exit_code", "pytest_exit_code");
        if (exitCode == null || exitCode.intValue() != 0) {
            throw new IllegalStateException("RD task_done 被拒绝：缺少 exit_code=0 证据");
        }
        Number pytestCount = findNumber(
                values, "pytest_count", "pytest_collected", "pytest_passed",
                "tests_collected", "tests_passed", "passed"
        );
        if (pytestCount == null || pytestCount.intValue() <= 0) {
            throw new IllegalStateException("RD task_done 被拒绝：缺少大于零的 pytest 数量证据");
        }
        Double coverage = findCoverage(values);
        if (coverage == null || coverage < 0D || coverage > 100D) {
            throw new IllegalStateException("RD task_done 被拒绝：缺少合法覆盖率证据");
        }
    }

    /**
     * 要求 QA 已生成非空报告，并在独立文件或报告正文中提供证据矩阵。
     *
     * @param projectId 项目标识
     */
    private void validateQa(String projectId) {
        String report = readRequired(projectId, "qa/test_report.md", "QA task_done 被拒绝：缺少 qa/test_report.md");
        boolean inlineMatrix = report.contains("证据矩阵")
                || report.toLowerCase(Locale.ROOT).contains("evidence matrix");
        boolean separateMatrix = readableNonBlank(projectId, "qa/evidence_matrix.md");
        if (!inlineMatrix && !separateMatrix) {
            throw new IllegalStateException("QA task_done 被拒绝：缺少测试证据矩阵");
        }
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
            Object value = values.get(key);
            Number number = toNumber(value);
            if (number != null) {
                return number;
            }
        }
        for (Object value : values.values()) {
            if (value instanceof Map<?, ?> nested) {
                Number number = findNumber(nested, keys);
                if (number != null) {
                    return number;
                }
            }
        }
        return null;
    }

    /**
     * 递归提取 coverage 或 coverage_percent 字段。
     *
     * @param values 待搜索结构
     * @return 覆盖率百分比；不存在时返回 null
     */
    private static Double findCoverage(Map<?, ?> values) {
        for (String key : List.of("coverage", "coverage_percent", "line_coverage")) {
            Object value = values.get(key);
            Number number = toNumber(value);
            if (number != null) {
                return number.doubleValue();
            }
            if (value instanceof String text) {
                Matcher matcher = PERCENT_PATTERN.matcher(text);
                if (matcher.find()) {
                    return Double.parseDouble(matcher.group(1));
                }
            }
        }
        for (Object value : values.values()) {
            if (value instanceof Map<?, ?> nested) {
                Double coverage = findCoverage(nested);
                if (coverage != null) {
                    return coverage;
                }
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
}
