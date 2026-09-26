package cn.org.chris.wake.app.observability;

import cn.org.chris.wake.domain.gateway.LogQueryRepository;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * 实现 Python log_query 的 stats、tasks、steps、l1 与 all-agents 查询契约。
 */
public final class LogQueryService {

    /** JSONL 日志只读端口。 */
    private final LogQueryRepository repository;

    /** 计算查询时间窗口的 UTC 时钟。 */
    private final Clock clock;

    /**
     * 使用系统 UTC 时间创建日志查询服务。
     *
     * @param repository JSONL 日志只读端口
     */
    public LogQueryService(LogQueryRepository repository) {
        this(repository, Clock.systemUTC());
    }

    /**
     * 使用可替换时钟创建确定性日志查询服务。
     *
     * @param repository JSONL 日志只读端口
     * @param clock 查询时钟
     */
    public LogQueryService(LogQueryRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    /**
     * 汇总指定 Agent 在时间窗口内的任务质量和人工纠正次数。
     *
     * @param logsRoot 日志根目录
     * @param agentId Agent 角色标识
     * @param days 最近天数
     * @return Python 兼容统计对象
     */
    public Map<String, Object> stats(Path logsRoot, String agentId, int days) {
        List<Map<String, Object>> tasks = matchingTasks(logsRoot, agentId, days);
        List<Double> qualities = tasks.stream().map(LogQueryService::quality).filter(Objects::nonNull).toList();
        long failures = qualities.stream().filter(value -> value < 0.5D).count();
        long corrections = repository.readL1(logsRoot).stream()
                .filter(entry -> withinDays(entry, days))
                .filter(entry -> "rejected".equals(contentValue(entry, "classified_as")))
                .count();
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("agent_id", agentId);
        result.put("period_days", days);
        result.put("task_count", tasks.size());
        result.put("avg_quality", qualities.isEmpty() ? null : round3(qualities.stream().mapToDouble(Double::doubleValue).average().orElse(0D)));
        result.put("failure_count", failures);
        result.put("human_correction_count", corrections);
        return result;
    }

    /**
     * 查询并按 Python 规则排序指定 Agent 的精简任务列表。
     *
     * @param logsRoot 日志根目录
     * @param agentId Agent 角色标识
     * @param days 最近天数
     * @param sort 排序名：time、quality_asc 或 quality_desc
     * @param limit 可空最大条数
     * @return Python 兼容任务对象
     */
    public Map<String, Object> tasks(Path logsRoot, String agentId, int days, String sort, Integer limit) {
        List<Map<String, Object>> entries = new ArrayList<>(matchingTasks(logsRoot, agentId, days));
        Comparator<Map<String, Object>> comparator = switch (sort == null ? "time" : sort) {
            case "quality_asc" -> Comparator.comparingDouble(LogQueryService::pythonAscendingQuality);
            case "quality_desc" -> Comparator.comparingDouble(LogQueryService::pythonDescendingQuality);
            default -> Comparator.comparing(entry -> stringValue(entry.get("ts")), Comparator.nullsFirst(String::compareTo));
        };
        entries.sort(comparator);
        if (limit != null && limit >= 0 && entries.size() > limit) {
            entries = new ArrayList<>(entries.subList(0, limit));
        }
        List<Map<String, Object>> slim = entries.stream().map(LogQueryService::slimTask).toList();
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("agent_id", agentId);
        result.put("period_days", days);
        result.put("tasks", slim);
        return result;
    }

    /**
     * 查询指定任务的步骤，可只保留失败步骤。
     *
     * @param sessionFile session JSONL 文件
     * @param taskId 任务标识
     * @param onlyFailed 是否仅返回失败步骤
     * @return Python 兼容步骤对象
     */
    public Map<String, Object> steps(Path sessionFile, String taskId, boolean onlyFailed) {
        if (!Files.exists(sessionFile)) {
            LinkedHashMap<String, Object> missing = new LinkedHashMap<>();
            missing.put("task_id", taskId);
            missing.put("steps", List.of());
            return missing;
        }
        List<Map<String, Object>> entries = repository.readSteps(sessionFile).stream()
                .filter(entry -> Objects.equals(taskId, stringValue(entry.get("task_id"))))
                .filter(entry -> !onlyFailed || Boolean.TRUE.equals(entry.get("failed")))
                .toList();
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("task_id", taskId);
        result.put("only_failed", onlyFailed);
        result.put("steps", entries);
        return result;
    }

    /**
     * 查询 L1 人工反馈并可按用户文本关键字过滤。
     *
     * @param logsRoot 日志根目录
     * @param days 最近天数
     * @param keyword 可空关键字
     * @return Python 兼容 L1 对象
     */
    public Map<String, Object> l1(Path logsRoot, int days, String keyword) {
        List<Map<String, Object>> entries = repository.readL1(logsRoot).stream()
                .filter(entry -> withinDays(entry, days))
                .filter(entry -> keyword == null || userText(entry).contains(keyword))
                .toList();
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("period_days", days);
        result.put("keyword", keyword);
        result.put("entries", entries);
        return result;
    }

    /**
     * 汇总时间窗口内出现过的全部 Agent，并标识平均质量最低者。
     *
     * @param logsRoot 日志根目录
     * @param days 最近天数
     * @return Python 兼容跨 Agent 汇总
     */
    public Map<String, Object> allAgents(Path logsRoot, int days) {
        TreeSet<String> roles = new TreeSet<>();
        repository.readL2(logsRoot).stream().filter(entry -> withinDays(entry, days))
                .map(entry -> stringValue(entry.get("role"))).filter(Objects::nonNull).forEach(roles::add);
        LinkedHashMap<String, Map<String, Object>> perAgent = new LinkedHashMap<>();
        roles.forEach(role -> perAgent.put(role, stats(logsRoot, role, days)));
        String bottleneck = perAgent.values().stream().filter(entry -> entry.get("avg_quality") instanceof Number)
                .min(Comparator.comparingDouble(entry -> ((Number) entry.get("avg_quality")).doubleValue()))
                .map(entry -> stringValue(entry.get("agent_id"))).orElse(null);
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("period_days", days);
        result.put("per_agent", perAgent);
        result.put("bottleneck", bottleneck);
        return result;
    }

    /** 筛选指定 Agent 和时间窗口内的任务。 */
    private List<Map<String, Object>> matchingTasks(Path logsRoot, String agentId, int days) {
        return repository.readL2(logsRoot).stream()
                .filter(entry -> Objects.equals(agentId, stringValue(entry.get("role"))))
                .filter(entry -> withinDays(entry, days)).toList();
    }

    /** 判断日志时间戳是否落在最近天数内。 */
    private boolean withinDays(Map<String, Object> entry, int days) {
        String timestamp = stringValue(entry.get("ts"));
        if (timestamp == null) {
            return false;
        }
        try {
            Instant instant = OffsetDateTime.parse(timestamp).toInstant();
            return !instant.isBefore(clock.instant().minus(days, ChronoUnit.DAYS));
        } catch (DateTimeParseException ignored) {
            return false;
        }
    }

    /** 提取嵌套 content.user_text，缺失时返回空串。 */
    private static String userText(Map<String, Object> entry) {
        String value = contentValue(entry, "user_text");
        return value == null ? "" : value;
    }

    /** 提取 content 中的可空字符串字段。 */
    private static String contentValue(Map<String, Object> entry, String key) {
        Object content = entry.get("content");
        if (!(content instanceof Map<?, ?> map)) {
            return null;
        }
        return stringValue(map.get(key));
    }

    /** 构造与 Python CLI 相同字段集合的精简任务对象。 */
    private static Map<String, Object> slimTask(Map<String, Object> entry) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of("task_id", "ts", "role", "result_quality_estimate",
                "result_quality_breakdown", "self_review_passed")) {
            result.put(key, entry.get(key));
        }
        result.put("artifacts_produced", entry.getOrDefault("artifacts_produced", List.of()));
        result.put("task_desc", entry.get("task_desc"));
        result.put("errors", entry.getOrDefault("errors", List.of()));
        return result;
    }

    /** 提取可空质量分。 */
    private static Double quality(Map<String, Object> entry) {
        Object value = entry.get("result_quality_estimate");
        return value instanceof Number number ? number.doubleValue() : null;
    }

    /** 复制 Python `(quality or 1.0)` 的升序语义。 */
    private static double pythonAscendingQuality(Map<String, Object> entry) {
        Double value = quality(entry);
        return value == null || value == 0D ? 1D : value;
    }

    /** 复制 Python `-(quality or 0)` 的降序语义。 */
    private static double pythonDescendingQuality(Map<String, Object> entry) {
        Double value = quality(entry);
        return -(value == null ? 0D : value);
    }

    /** 将任意非空值转为字符串。 */
    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    /** 按 Python round(..., 3) 的契约保留三位小数。 */
    private static double round3(double value) {
        return Math.round(value * 1000D) / 1000D;
    }
}
