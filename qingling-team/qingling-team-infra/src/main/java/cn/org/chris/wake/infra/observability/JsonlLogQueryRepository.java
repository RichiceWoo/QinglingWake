package cn.org.chris.wake.infra.observability;

import cn.org.chris.wake.domain.gateway.LogQueryRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 从 Python 兼容 JSONL 目录读取 L1、L2 和 session 步骤日志。
 */
public final class JsonlLogQueryRepository implements LogQueryRepository {

    /** Jackson 泛型对象类型。 */
    private static final TypeReference<Map<String, Object>> OBJECT_TYPE = new TypeReference<>() { };

    /** JSON 解析器。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建 JSONL 查询仓储。
     *
     * @param objectMapper JSON 解析器
     */
    public JsonlLogQueryRepository(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    /** {@inheritDoc} */
    @Override
    public List<Map<String, Object>> readL2(Path logsRoot) {
        Path projects = normalized(logsRoot).resolve("shared/projects");
        return readMatching(projects, 4, "l2_task");
    }

    /** {@inheritDoc} */
    @Override
    public List<Map<String, Object>> readL1(Path logsRoot) {
        return readMatching(normalized(logsRoot).resolve("shared/logs/l1_human"), 1, null);
    }

    /** {@inheritDoc} */
    @Override
    public List<Map<String, Object>> readSteps(Path sessionFile) {
        return readFile(sessionFile.toAbsolutePath().normalize());
    }

    /** 查找并按规范化路径排序全部 JSONL 文件。 */
    private List<Map<String, Object>> readMatching(Path root, int maxDepth, String requiredParent) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.find(root, maxDepth, (path, attributes) ->
                attributes.isRegularFile() && path.getFileName().toString().endsWith(".jsonl")
                        && (requiredParent == null || hasParent(path, requiredParent)))) {
            List<Map<String, Object>> result = new ArrayList<>();
            paths.sorted(Comparator.comparing(Path::toString)).forEach(path -> result.addAll(readFile(path)));
            return List.copyOf(result);
        } catch (IOException failure) {
            throw new IllegalStateException("读取日志目录失败", failure);
        }
    }

    /** 判断文件路径是否位于指定名称的父目录下。 */
    private static boolean hasParent(Path path, String parentName) {
        for (Path part : path) {
            if (parentName.equals(part.toString())) {
                return true;
            }
        }
        return false;
    }

    /** 逐行读取单个 JSONL 文件，并与 Python 一样跳过空行和损坏行。 */
    private List<Map<String, Object>> readFile(Path file) {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try (Stream<String> lines = Files.lines(file)) {
            return lines.filter(line -> !line.isBlank()).map(this::parseOrNull)
                    .filter(Objects::nonNull).toList();
        } catch (IOException failure) {
            throw new IllegalStateException("读取日志文件失败", failure);
        }
    }

    /** 解析单行对象，损坏数据返回空值并由调用方丢弃。 */
    private Map<String, Object> parseOrNull(String line) {
        try {
            return objectMapper.readValue(line, OBJECT_TYPE);
        } catch (IOException ignored) {
            return null;
        }
    }

    /** 校验并规范化日志根目录。 */
    private static Path normalized(Path logsRoot) {
        return Objects.requireNonNull(logsRoot, "logsRoot 不能为空").toAbsolutePath().normalize();
    }
}
