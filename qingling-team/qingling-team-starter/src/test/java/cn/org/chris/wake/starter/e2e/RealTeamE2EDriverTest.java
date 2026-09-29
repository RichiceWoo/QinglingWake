package cn.org.chris.wake.starter.e2e;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证真实 E2E 隔离目录清理不会破坏 Docker bind mount 的根目录身份。
 */
class RealTeamE2EDriverTest {

    /** JUnit 提供的临时 Maven 项目根目录。 */
    @TempDir
    Path projectRoot;

    /**
     * 清理 workspace 时应删除全部旧内容，同时保留根目录及其文件系统标识。
     *
     * @throws Exception 创建或清理测试目录失败
     */
    @Test
    void shouldPreserveBindMountRootWhileClearingContents() throws Exception {
        Path workspaceRoot = projectRoot.resolve("target/e2e-workspace");
        Path nestedFile = workspaceRoot.resolve("shared/projects/stale/events.jsonl");
        Files.createDirectories(nestedFile.getParent());
        Files.writeString(nestedFile, "stale");
        Object fileKeyBefore = Files.readAttributes(
                workspaceRoot, java.nio.file.attribute.BasicFileAttributes.class
        ).fileKey();

        RealTeamE2EDriver.resetTargetDirectory(projectRoot, workspaceRoot);

        Object fileKeyAfter = Files.readAttributes(
                workspaceRoot, java.nio.file.attribute.BasicFileAttributes.class
        ).fileKey();
        assertThat(workspaceRoot).isDirectory();
        assertThat(fileKeyAfter).isEqualTo(fileKeyBefore);
        try (var children = Files.list(workspaceRoot)) {
            assertThat(children).isEmpty();
        }
    }
}
