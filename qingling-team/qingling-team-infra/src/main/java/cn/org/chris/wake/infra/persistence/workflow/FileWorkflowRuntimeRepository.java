package cn.org.chris.wake.infra.persistence.workflow;

import cn.org.chris.wake.domain.gateway.WorkflowRuntimeRepository;
import cn.org.chris.wake.domain.workflow.ProcessedWorkflowCommand;
import cn.org.chris.wake.domain.workflow.WorkflowCommandId;
import cn.org.chris.wake.domain.workflow.WorkflowRuntimeState;
import cn.org.chris.wake.domain.workspace.WorkspacePolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 使用项目级文件锁、单 JSON aggregate 和 write-then-rename 持久化 runtime v2。
 */
public final class FileWorkflowRuntimeRepository implements WorkflowRuntimeRepository {

    /** 避免同一 JVM 的并发 FileLock 触发 OverlappingFileLockException。 */
    private static final ConcurrentMap<Path, ReentrantLock> LOCAL_LOCKS = new ConcurrentHashMap<>();

    /** runtime v2 固定文件名。 */
    private static final String STATE_FILE_NAME = "workflow-v2.json";

    /** 项目共享 workspace 根目录。 */
    private final Path workspaceRoot;

    /** 使用 snake_case 和 ISO 时间的私有 JSON 编解码器。 */
    private final ObjectMapper objectMapper;

    /** processed command 首次完成时间来源。 */
    private final Clock clock;

    /**
     * 使用系统 UTC 时钟创建文件仓储。
     *
     * @param workspaceRoot workspace 根目录
     * @param objectMapper 应用 JSON 编解码器
     */
    public FileWorkflowRuntimeRepository(Path workspaceRoot, ObjectMapper objectMapper) {
        this(workspaceRoot, objectMapper, Clock.systemUTC());
    }

    /**
     * 使用可测试时钟创建文件仓储。
     *
     * @param workspaceRoot workspace 根目录
     * @param objectMapper 应用 JSON 编解码器
     * @param clock processed command 时钟
     */
    public FileWorkflowRuntimeRepository(Path workspaceRoot, ObjectMapper objectMapper, Clock clock) {
        this.workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot 不能为空")
                .toAbsolutePath().normalize();
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空")
                .copy()
                .registerModule(new JavaTimeModule())
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    /**
     * 只读查询 aggregate；缺失时不创建 runtime 目录、锁文件或状态文件。
     *
     * @param projectId 项目标识
     * @return 可选 aggregate
     */
    @Override
    public Optional<WorkflowRuntimeState> find(String projectId) {
        Path statePath = statePath(projectId);
        if (!Files.exists(statePath, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        assertRegularStatePath(statePath);
        return Optional.of(readState(statePath));
    }

    /**
     * 显式创建新项目 aggregate，并拒绝覆盖已有 v2 权威状态。
     *
     * @param initial 初始 aggregate
     * @return 已持久化 aggregate
     */
    @Override
    public WorkflowRuntimeState create(WorkflowRuntimeState initial) {
        Objects.requireNonNull(initial, "initial 不能为空");
        Path statePath = statePath(initial.projectId());
        return withFileLock(statePath, true, () -> {
            if (Files.exists(statePath, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("workflow runtime 已存在: " + initial.projectId());
            }
            writeState(statePath, initial);
            return initial;
        });
    }

    /**
     * 在单一项目锁内完成 command 去重、状态变换、结果记录和原子提交。
     *
     * @param projectId 项目标识
     * @param commandId 稳定命令标识
     * @param command 首次执行状态变换
     * @return 稳定命令回执
     */
    @Override
    public WorkflowCommandReceipt execute(
            String projectId,
            WorkflowCommandId commandId,
            WorkflowCommand command
    ) {
        Objects.requireNonNull(commandId, "commandId 不能为空");
        Objects.requireNonNull(command, "command 不能为空");
        Path statePath = requireExistingStatePath(projectId);
        return withFileLock(statePath, false, () -> {
            WorkflowRuntimeState current = readState(statePath);
            Optional<ProcessedWorkflowCommand> duplicate = current.findProcessedCommand(commandId);
            if (duplicate.isPresent()) {
                return new WorkflowCommandReceipt(duplicate.get(), current, false);
            }
            WorkflowCommandMutation mutation = Objects.requireNonNull(
                    command.apply(current), "command mutation 不能为空"
            );
            WorkflowRuntimeState changed = requireSameAggregate(current, mutation.state());
            Instant processedAt = latest(Instant.now(clock), changed.updatedAt());
            ProcessedWorkflowCommand processed = new ProcessedWorkflowCommand(
                    commandId, mutation.outcomeCode(), mutation.resultReference(), processedAt
            );
            WorkflowRuntimeState committed = changed.recordCommand(processed, processedAt);
            writeState(statePath, committed);
            return new WorkflowCommandReceipt(processed, committed, true);
        });
    }

    /**
     * 在项目级锁内原子应用 projector ack 等内部状态更新。
     *
     * @param projectId 项目标识
     * @param update 内部状态变换
     * @return 已持久化 aggregate
     */
    @Override
    public WorkflowRuntimeState update(String projectId, WorkflowRuntimeUpdate update) {
        Objects.requireNonNull(update, "update 不能为空");
        Path statePath = requireExistingStatePath(projectId);
        return withFileLock(statePath, false, () -> {
            WorkflowRuntimeState current = readState(statePath);
            WorkflowRuntimeState changed = requireSameAggregate(
                    current, Objects.requireNonNull(update.apply(current), "更新结果不能为空")
            );
            if (!changed.equals(current)) {
                writeState(statePath, changed);
            }
            return changed;
        });
    }

    /**
     * 读取并严格校验 schema 后反序列化完整 aggregate。
     *
     * @param statePath 状态文件路径
     * @return 已校验 aggregate
     */
    private WorkflowRuntimeState readState(Path statePath) {
        try {
            if (Files.size(statePath) == 0L) {
                throw new IllegalStateException("workflow runtime 文件为空: " + statePath);
            }
            byte[] bytes = Files.readAllBytes(statePath);
            JsonNode root = objectMapper.readTree(bytes);
            if (root == null || !root.isObject()) {
                throw new IllegalStateException("workflow runtime 必须是 JSON object: " + statePath);
            }
            JsonNode schema = root.get("schema_version");
            if (schema == null || !schema.canConvertToInt()
                    || schema.intValue() != WorkflowRuntimeState.CURRENT_SCHEMA_VERSION) {
                throw new IllegalStateException("不支持 workflow runtime schema: " + schema);
            }
            return objectMapper.treeToValue(root, WorkflowRuntimeState.class);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("读取 workflow runtime 失败: " + statePath, exception);
        }
    }

    /**
     * 将完整 aggregate 写入临时文件、强制刷盘后原子替换目标。
     *
     * @param statePath 状态文件路径
     * @param state 完整 aggregate
     */
    private void writeState(Path statePath, WorkflowRuntimeState state) {
        Path temporary = statePath.resolveSibling(STATE_FILE_NAME + ".tmp");
        try {
            byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(state);
            try (FileChannel channel = FileChannel.open(
                    temporary,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            )) {
                channel.write(ByteBuffer.wrap(bytes));
                channel.force(true);
            }
            try {
                Files.move(
                        temporary, statePath,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, statePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("写入 workflow runtime 失败: " + statePath, exception);
        }
    }

    /**
     * 使用 JVM 本地锁和跨进程 FileLock 执行项目级临界区。
     *
     * @param statePath 状态文件路径
     * @param createDirectories 是否允许显式创建 runtime 目录
     * @param operation 临界区操作
     * @param <T> 返回类型
     * @return 临界区结果
     */
    private <T> T withFileLock(
            Path statePath,
            boolean createDirectories,
            FileOperation<T> operation
    ) {
        Path runtimeDirectory = statePath.getParent();
        try {
            if (createDirectories) {
                Files.createDirectories(runtimeDirectory);
            } else if (!Files.isDirectory(runtimeDirectory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("workflow runtime 不存在: " + statePath);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("准备 workflow runtime 目录失败: " + runtimeDirectory, exception);
        }
        Path lockPath = runtimeDirectory.resolve(STATE_FILE_NAME + ".lock");
        ReentrantLock localLock = LOCAL_LOCKS.computeIfAbsent(lockPath, ignored -> new ReentrantLock());
        localLock.lock();
        try (FileChannel channel = FileChannel.open(
                lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE
        ); FileLock ignored = channel.lock()) {
            return operation.execute();
        } catch (IOException exception) {
            throw new IllegalStateException("workflow runtime 文件锁操作失败: " + statePath, exception);
        } finally {
            localLock.unlock();
        }
    }

    /**
     * 校验 command 或 update 不能替换项目身份、schema、档位、策略和创建时间。
     *
     * @param current 当前 aggregate
     * @param changed 变更后 aggregate
     * @return 已校验变更后 aggregate
     */
    private static WorkflowRuntimeState requireSameAggregate(
            WorkflowRuntimeState current,
            WorkflowRuntimeState changed
    ) {
        if (current.schemaVersion() != changed.schemaVersion()
                || !current.projectId().equals(changed.projectId())
                || current.profile() != changed.profile()
                || current.reviewPolicy() != changed.reviewPolicy()
                || !current.createdAt().equals(changed.createdAt())) {
            throw new IllegalArgumentException("状态变换不能修改 aggregate 身份或冻结配置");
        }
        return changed;
    }

    /**
     * 构造项目 runtime v2 状态文件路径并阻止目录逃逸。
     *
     * @param projectId 项目标识
     * @return 规范化状态文件路径
     */
    private Path statePath(String projectId) {
        String safeProjectId = WorkspacePolicy.validateProjectId(projectId);
        Path path = workspaceRoot.resolve("shared/projects")
                .resolve(safeProjectId)
                .resolve("runtime")
                .resolve(STATE_FILE_NAME)
                .normalize();
        if (!path.startsWith(workspaceRoot)) {
            throw new SecurityException("workflow runtime 路径逃逸 workspace");
        }
        return path;
    }

    /**
     * 在不创建任何文件的前提下要求 v2 状态已存在。
     *
     * @param projectId 项目标识
     * @return 已存在状态文件路径
     */
    private Path requireExistingStatePath(String projectId) {
        Path path = statePath(projectId);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("workflow runtime 不存在: " + projectId);
        }
        assertRegularStatePath(path);
        return path;
    }

    /**
     * 拒绝把符号链接或非普通文件当成权威状态。
     *
     * @param path 状态文件路径
     */
    private static void assertRegularStatePath(Path path) {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new SecurityException("workflow runtime 必须是普通文件: " + path);
        }
    }

    /**
     * 返回两个时间中较晚者，保证 command 记录不早于业务状态更新时间。
     *
     * @param left 第一个时间
     * @param right 第二个时间
     * @return 较晚时间
     */
    private static Instant latest(Instant left, Instant right) {
        return left.isAfter(right) ? left : right;
    }

    /**
     * 定义可抛 IOException 的文件临界区操作。
     *
     * @param <T> 返回类型
     */
    @FunctionalInterface
    private interface FileOperation<T> {

        /**
         * 执行一次项目级文件锁内操作。
         *
         * @return 操作结果
         * @throws IOException 文件操作失败
         */
        T execute() throws IOException;
    }
}
