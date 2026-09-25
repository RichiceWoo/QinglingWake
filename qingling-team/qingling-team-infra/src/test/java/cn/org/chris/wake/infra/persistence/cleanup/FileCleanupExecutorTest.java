package cn.org.chris.wake.infra.persistence.cleanup;

import cn.org.chris.wake.domain.model.CleanupPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证保留期、data root 边界、会话目录与凭证权限。
 */
class FileCleanupExecutorTest {

    /** JUnit 临时目录。 */
    @TempDir
    Path temporaryDirectory;

    /** JSON 编解码器。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 默认 1/7/30/30/365 天规则应只删除严格过期条目并保留规则目录。
     *
     * @throws IOException 测试文件创建失败
     */
    @Test
    void shouldApplyDefaultRetentionRules() throws IOException {
        Path data = temporaryDirectory.resolve("data");
        Instant now = Instant.parse("2026-09-26T00:00:00Z");
        Path oldTmp = createFile(data.resolve("workspace/sessions/s1/tmp/old.txt"), now.minus(2, ChronoUnit.DAYS));
        Path freshTmp = createFile(data.resolve("workspace/sessions/s1/tmp/fresh.txt"), now.minus(1, ChronoUnit.HOURS));
        Path oldUpload = createFile(data.resolve("workspace/sessions/s1/uploads/old.txt"), now.minus(8, ChronoUnit.DAYS));
        Path oldTrace = createFile(data.resolve("traces/old.jsonl"), now.minus(31, ChronoUnit.DAYS));
        Path oldSession = createFile(data.resolve("sessions/old.jsonl"), now.minus(366, ChronoUnit.DAYS));
        Path freshSession = createFile(data.resolve("sessions/fresh.jsonl"), now.minus(10, ChronoUnit.DAYS));
        FileCleanupExecutor executor = new FileCleanupExecutor(data, objectMapper);

        Map<String, Integer> statistics = executor.sweep(CleanupPolicy.defaults(), now);

        assertThat(statistics).containsEntry("workspace/sessions/*/tmp", 1)
                .containsEntry("workspace/sessions/*/uploads", 1)
                .containsEntry("traces", 1)
                .containsEntry("sessions/*.jsonl", 1);
        assertThat(oldTmp).doesNotExist();
        assertThat(oldUpload).doesNotExist();
        assertThat(oldTrace).doesNotExist();
        assertThat(oldSession).doesNotExist();
        assertThat(freshTmp).exists();
        assertThat(freshSession).exists();
        assertThat(data.resolve("workspace/sessions/s1/tmp")).isDirectory();
    }

    /**
     * 匹配规则遇到指向 data root 外部的符号链接时不得删除外部内容。
     *
     * @throws IOException 测试文件和符号链接创建失败
     */
    @Test
    void shouldNeverFollowSymbolicLinkOutsideDataRoot() throws IOException {
        Path data = temporaryDirectory.resolve("data");
        Path external = temporaryDirectory.resolve("external");
        Path protectedFile = createFile(external.resolve("protected.txt"), Instant.EPOCH);
        Path session = data.resolve("workspace/sessions/s1");
        Files.createDirectories(session);
        Files.createSymbolicLink(session.resolve("tmp"), external);
        FileCleanupExecutor executor = new FileCleanupExecutor(data, objectMapper);

        executor.sweep(CleanupPolicy.defaults(), Instant.parse("2026-09-26T00:00:00Z"));

        assertThat(protectedFile).exists();
    }

    /**
     * 会话目录必须限制在 data root，合法标识创建三个固定子目录。
     */
    @Test
    void shouldInitializeOnlySafeWorkspaceDirectories() {
        Path data = temporaryDirectory.resolve("data");
        FileCleanupExecutor executor = new FileCleanupExecutor(data, objectMapper);

        executor.ensureWorkspaceDirectories("session-01");

        assertThat(data.resolve("workspace/sessions/session-01/uploads")).isDirectory();
        assertThat(data.resolve("workspace/sessions/session-01/outputs")).isDirectory();
        assertThat(data.resolve("workspace/sessions/session-01/tmp")).isDirectory();
        assertThatThrownBy(() -> executor.ensureWorkspaceDirectories("../../outside"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 凭证目录和文件应分别使用 0700、0600，并保持 Python 字段名。
     *
     * @throws IOException 权限或 JSON 读取失败
     */
    @Test
    void shouldWriteCredentialFilesWithOwnerOnlyPermissions() throws IOException {
        Path data = temporaryDirectory.resolve("data");
        FileCleanupExecutor executor = new FileCleanupExecutor(data, objectMapper);

        executor.writeFeishuCredentials("app-id", "app-secret");
        executor.writeBaiduCredentials("api-key");
        executor.writeBaiduCredentials(" ");

        Path config = data.resolve("workspace/.config");
        Path feishu = config.resolve("feishu.json");
        Path baidu = config.resolve("baidu.json");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(config))).isEqualTo("rwx------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(feishu))).isEqualTo("rw-------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(baidu))).isEqualTo("rw-------");
        JsonNode feishuJson = objectMapper.readTree(feishu.toFile());
        assertThat(feishuJson.path("app_id").asText()).isEqualTo("app-id");
        assertThat(feishuJson.path("app_secret").asText()).isEqualTo("app-secret");
    }

    /**
     * 创建测试文件并设置确定性 mtime。
     *
     * @param path 文件路径
     * @param modifiedAt 修改时间
     * @return 原文件路径
     * @throws IOException 创建失败
     */
    private static Path createFile(Path path, Instant modifiedAt) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "data");
        Files.setLastModifiedTime(path, FileTime.from(modifiedAt));
        return path;
    }
}
