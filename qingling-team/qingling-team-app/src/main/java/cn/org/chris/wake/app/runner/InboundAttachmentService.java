package cn.org.chris.wake.app.runner;

import cn.org.chris.wake.domain.model.Attachment;
import cn.org.chris.wake.domain.model.InboundMessage;
import cn.org.chris.wake.domain.gateway.AttachmentDownloader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * 下载并安全落盘入站附件，同时构造模型可理解的沙箱路径提示。
 */
public final class InboundAttachmentService {

    /** sessionId 和清理后文件名允许的安全字符。 */
    private static final Pattern SAFE_SEGMENT = Pattern.compile("[A-Za-z0-9._\\-\\p{IsHan}]+");

    /** 外部可写 workspace 根目录，附件保存到 sessions/{id}/uploads。 */
    private final Path workspaceRoot;

    /** 由基础设施实现提供的附件字节下载端口。 */
    private final AttachmentDownloader downloader;

    /**
     * 创建附件服务。
     *
     * @param workspaceRoot 外部共享 workspace 根目录
     * @param downloader 附件下载端口
     */
    public InboundAttachmentService(Path workspaceRoot, AttachmentDownloader downloader) {
        this.workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot 不能为空")
                .toAbsolutePath().normalize();
        this.downloader = Objects.requireNonNull(downloader, "downloader 不能为空");
        initializeRoot();
    }

    /**
     * 无附件时保持正文不变；有附件时下载到 session uploads 并返回路径提示。
     *
     * @param inbound 入站消息
     * @param sessionId 当前 AgentScope 会话标识
     * @return 异步准备结果，下载失败时返回失败标记而不抛出网络异常
     */
    public CompletableFuture<PreparedInbound> prepare(InboundMessage inbound, String sessionId) {
        Objects.requireNonNull(inbound, "inbound 不能为空");
        requireSafeSegment(sessionId, "sessionId");
        if (inbound.attachment() == null) {
            return CompletableFuture.completedFuture(new PreparedInbound(
                    inbound.content(), List.of(), true
            ));
        }
        Attachment attachment = inbound.attachment();
        String safeName = sanitizeFileName(attachment.fileName());
        String relativePath = "sessions/" + sessionId + "/uploads/" + safeName;
        Path target = workspaceRoot.resolve(relativePath).normalize();
        assertInsideWorkspace(target);
        return downloader.download(inbound.msgId(), attachment)
                .handle((bytes, failure) -> {
                    if (failure != null || bytes == null) {
                        return new PreparedInbound(
                                failedAttachmentContent(inbound.content()), List.of(), false
                        );
                    }
                    writeAtomically(target, bytes);
                    String sandboxPath = "/workspace/" + relativePath;
                    return new PreparedInbound(
                            attachmentContent(sandboxPath, inbound.content()),
                            List.of(relativePath),
                            true
                    );
                });
    }

    /**
     * 初始化外部 workspace 根目录并拒绝根路径本身为符号链接。
     */
    private void initializeRoot() {
        try {
            Files.createDirectories(workspaceRoot);
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建附件 workspace: " + workspaceRoot, exception);
        }
        if (Files.isSymbolicLink(workspaceRoot)) {
            throw new IllegalStateException("附件 workspace 不允许符号链接: " + workspaceRoot);
        }
    }

    /**
     * 清理路径分隔符、控制字符和平台不安全字符，并保留中文文件名。
     *
     * @param original 原始文件名
     * @return 可安全作为单个路径段的名称
     */
    private static String sanitizeFileName(String original) {
        String normalized = Objects.requireNonNullElse(original, "attachment").replace('\\', '/');
        String leaf = normalized.substring(normalized.lastIndexOf('/') + 1)
                .replaceAll("[\\p{Cntrl}:*?\"<>|]", "_")
                .replaceAll("\\s+", "_");
        if (leaf.isBlank() || ".".equals(leaf) || "..".equals(leaf) || !SAFE_SEGMENT.matcher(leaf).matches()) {
            return "attachment";
        }
        return leaf.length() > 180 ? leaf.substring(0, 180) : leaf;
    }

    /**
     * 构造附件成功后的模型正文，沙箱路径与用户备注分开呈现。
     *
     * @param sandboxPath 沙箱内绝对路径
     * @param originalText 用户原始备注
     * @return 传给 Agent 的本轮正文
     */
    private static String attachmentContent(String sandboxPath, String originalText) {
        String content = "用户发来了文件，已自动保存至沙盒路径：\n`" + sandboxPath
                + "`\n请根据文件内容和用户意图完成相应处理。";
        if (originalText != null && !originalText.isBlank()) {
            content += "\n用户备注：" + originalText;
        }
        return content;
    }

    /**
     * 构造下载失败时仍可交给 Agent 的提示正文。
     *
     * @param originalText 用户原始备注
     * @return 失败提示
     */
    private static String failedAttachmentContent(String originalText) {
        return ("[附件下载失败] " + Objects.requireNonNullElse(originalText, "")).strip();
    }

    /**
     * 原子写入附件并在每层目录检查符号链接，避免覆盖 workspace 外文件。
     *
     * @param target 目标附件路径
     * @param bytes 下载字节
     */
    private void writeAtomically(Path target, byte[] bytes) {
        assertInsideWorkspace(target);
        Path parent = target.getParent();
        try {
            Files.createDirectories(parent);
            rejectSymbolicLinks(parent);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("附件目标不是普通文件: " + target);
            }
            Path temporary = Files.createTempFile(parent, ".attachment-", ".tmp");
            try {
                try (FileChannel channel = FileChannel.open(
                        temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING
                )) {
                    channel.write(ByteBuffer.wrap(bytes));
                    channel.force(true);
                }
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("附件落盘失败: " + target, exception);
        }
    }

    /**
     * 验证目标仍位于配置的 workspace 根目录内。
     *
     * @param target 待验证路径
     */
    private void assertInsideWorkspace(Path target) {
        if (!target.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("附件路径越界: " + target);
        }
    }

    /**
     * 检查 workspace 内已创建路径的每一层，拒绝符号链接逃逸。
     *
     * @param target 待检查路径
     */
    private void rejectSymbolicLinks(Path target) {
        Path current = workspaceRoot;
        for (Path segment : workspaceRoot.relativize(target)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalStateException("附件路径不允许符号链接: " + current);
            }
        }
    }

    /**
     * 校验 sessionId 只能作为单个安全路径段。
     *
     * @param value sessionId
     * @param name 参数名称
     */
    private static void requireSafeSegment(String value, String name) {
        if (value == null || value.isBlank() || !SAFE_SEGMENT.matcher(value).matches()
                || ".".equals(value) || "..".equals(value)) {
            throw new IllegalArgumentException(name + " 含非法路径字符");
        }
    }

    /**
     * 描述附件准备后的模型输入。
     *
     * @param content 传给 Agent 的本轮正文
     * @param attachmentPaths workspace 相对路径
     * @param attachmentReady 无附件或下载成功时为 true
     */
    public record PreparedInbound(
            /** 传给模型的正文。 */ String content,
            /** 已落盘附件相对路径。 */ List<String> attachmentPaths,
            /** 附件是否可供 Agent 使用。 */ boolean attachmentReady
    ) {
        /**
         * 复制路径列表并标准化空正文。
         */
        public PreparedInbound {
            content = Objects.requireNonNullElse(content, "");
            attachmentPaths = attachmentPaths == null ? List.of() : List.copyOf(attachmentPaths);
        }
    }
}
