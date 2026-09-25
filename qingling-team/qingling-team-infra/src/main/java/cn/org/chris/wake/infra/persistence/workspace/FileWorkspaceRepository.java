package cn.org.chris.wake.infra.persistence.workspace;

import cn.org.chris.wake.domain.gateway.WorkspaceRepository;
import cn.org.chris.wake.domain.workspace.WorkspacePolicy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * 使用外部 workspace 实现共享文件读写、初始化和符号链接逃逸防护。
 */
public final class FileWorkspaceRepository implements WorkspaceRepository {

    /** Python CreateProjectTool 规定的项目目录清单。 */
    private static final List<String> PROJECT_DIRECTORIES = List.of(
            "needs", "design", "tech", "code", "qa", "qa/defects", "reviews", "mailboxes", "logs/l2_task"
    );

    /** 初始化时创建的四个角色邮箱。 */
    private static final List<String> MAILBOX_ROLES = List.of("manager", "pm", "rd", "qa");

    /** 序列化同一 JVM 内针对相同文件的写入。 */
    private static final ConcurrentMap<Path, ReentrantLock> LOCAL_LOCKS = new ConcurrentHashMap<>();

    /** 外部 AgentScope workspace 根目录。 */
    private final Path workspaceRoot;

    /**
     * 创建共享工作区 Repository。
     *
     * @param workspaceRoot 外部 workspace 根目录
     */
    public FileWorkspaceRepository(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }

    /**
     * 幂等建立项目目录树、空邮箱、事件流和状态锁文件。
     *
     * @param projectId 项目标识
     */
    @Override
    public void initializeProject(String projectId) {
        Path projectRoot = projectRoot(projectId);
        try {
            Files.createDirectories(workspaceRoot);
            assertNoSymbolicLinks(workspaceRoot);
            for (String directory : PROJECT_DIRECTORIES) {
                Path target = projectRoot.resolve(directory).normalize();
                assertContained(projectRoot, target);
                assertNoSymbolicLinks(target);
                Files.createDirectories(target);
                assertNoSymbolicLinks(target);
            }
            for (String role : MAILBOX_ROLES) {
                Path mailbox = projectRoot.resolve("mailboxes").resolve(role + ".json");
                assertNoSymbolicLinks(mailbox);
                createIfMissing(mailbox, "[]");
            }
            Path events = projectRoot.resolve("events.jsonl");
            Path stateLock = projectRoot.resolve("state.lock");
            assertNoSymbolicLinks(events);
            assertNoSymbolicLinks(stateLock);
            createIfMissing(events, "");
            createIfMissing(stateLock, "");
        } catch (IOException exception) {
            throw new IllegalStateException("初始化项目工作区失败: " + projectId, exception);
        }
    }

    /**
     * 读取已通过路径校验且不经过符号链接的 UTF-8 文件。
     *
     * @param projectId 项目标识
     * @param role 操作角色
     * @param relativePath 项目内相对路径
     * @return 文件文本
     */
    @Override
    public String read(String projectId, String role, String relativePath) {
        Path projectRoot = projectRoot(projectId);
        Path target = projectRoot.resolve(WorkspacePolicy.validateRead(role, relativePath)).normalize();
        assertContained(projectRoot, target);
        assertNoSymbolicLinks(target);
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("共享文件不存在: " + relativePath);
        }
        try {
            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("读取共享文件失败: " + relativePath, exception);
        }
    }

    /**
     * 在角色授权、路径包含和无符号链接校验后原子写入 UTF-8 文件。
     *
     * @param projectId 项目标识
     * @param role 操作角色
     * @param relativePath 项目内相对路径
     * @param content UTF-8 文本内容
     */
    @Override
    public void write(String projectId, String role, String relativePath, String content) {
        Path projectRoot = projectRoot(projectId);
        Path target = projectRoot.resolve(WorkspacePolicy.validateWrite(role, relativePath)).normalize();
        assertContained(projectRoot, target);
        assertNoSymbolicLinks(projectRoot);
        try {
            Path parent = target.getParent();
            assertNoSymbolicLinks(parent);
            Files.createDirectories(parent);
            assertNoSymbolicLinks(parent);
            writeAtomically(target, content == null ? "" : content);
        } catch (IOException exception) {
            throw new IllegalStateException("写入共享文件失败: " + relativePath, exception);
        }
    }

    /**
     * 列出项目下非锁文件和非临时文件的稳定相对路径。
     *
     * @param projectId 项目标识
     * @param role 操作角色
     * @return 字典序排列的相对路径
     */
    @Override
    public List<String> list(String projectId, String role) {
        WorkspacePolicy.validateRead(role, "needs/.list-check");
        Path projectRoot = projectRoot(projectId);
        assertNoSymbolicLinks(projectRoot);
        if (!Files.isDirectory(projectRoot, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(projectRoot)) {
            return paths
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .peek(this::assertNoSymbolicLinks)
                    .map(projectRoot::relativize)
                    .map(Path::toString)
                    .map(path -> path.replace((char) 92, '/'))
                    .filter(path -> !path.endsWith(".lock") && !path.endsWith(".tmp"))
                    .sorted()
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("列出项目工作区失败: " + projectId, exception);
        }
    }

    /**
     * 使用本地锁、文件锁、fsync 和原子 move 写文件。
     *
     * @param target 目标文件
     * @param content UTF-8 文本
     * @throws IOException 写入失败
     */
    private void writeAtomically(Path target, String content) throws IOException {
        Path lockPath = target.resolveSibling(target.getFileName() + ".lock");
        ReentrantLock localLock = LOCAL_LOCKS.computeIfAbsent(lockPath, ignored -> new ReentrantLock());
        localLock.lock();
        try (FileChannel lockChannel = FileChannel.open(
                lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE
        ); FileLock ignored = lockChannel.lock()) {
            Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
            try (FileChannel dataChannel = FileChannel.open(
                    temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE
            )) {
                dataChannel.write(ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)));
                dataChannel.force(true);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException moveUnsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            localLock.unlock();
        }
    }

    /**
     * 文件不存在时创建并强制刷盘，存在时保持用户数据不变。
     *
     * @param path 目标路径
     * @param content 初始内容
     * @throws IOException 创建失败
     */
    private static void createIfMissing(Path path, String content) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)));
            channel.force(true);
        }
    }

    /**
     * 构造并校验项目根目录不会逃逸 workspace。
     *
     * @param projectId 项目标识
     * @return 项目根目录
     */
    private Path projectRoot(String projectId) {
        Path projectRoot = workspaceRoot.resolve("shared/projects")
                .resolve(WorkspacePolicy.validateProjectId(projectId)).normalize();
        assertContained(workspaceRoot, projectRoot);
        return projectRoot;
    }

    /**
     * 校验目标路径在给定根目录内。
     *
     * @param root 允许根目录
     * @param target 目标路径
     */
    private static void assertContained(Path root, Path target) {
        if (!target.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) {
            throw new SecurityException("目标路径逃逸允许根目录: " + target);
        }
    }

    /**
     * 从 workspace 根到目标的所有已存在路径段均不得是符号链接。
     *
     * @param target 待校验目标或父目录
     */
    private void assertNoSymbolicLinks(Path target) {
        Path normalized = target.toAbsolutePath().normalize();
        assertContained(workspaceRoot, normalized);
        Path current = workspaceRoot;
        Path relative = workspaceRoot.relativize(normalized);
        if (Files.isSymbolicLink(current)) {
            throw new SecurityException("workspace 根目录不能是符号链接");
        }
        for (Path part : relative) {
            current = current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new SecurityException("不允许通过符号链接访问共享工作区: " + current);
            }
        }
    }
}
