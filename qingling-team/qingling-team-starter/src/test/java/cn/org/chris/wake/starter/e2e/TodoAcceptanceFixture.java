package cn.org.chris.wake.starter.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;

/**
 * 把 Harness 自有 Todo 验收契约安装为角色只读文件，并用摘要检测运行期篡改。
 */
public final class TodoAcceptanceFixture {

    /** 固定契约资源路径。 */
    private static final String CONTRACT_RESOURCE = "e2e/acceptance/todo-contract.json";

    /** 固定 pytest 资源路径。 */
    private static final String TEST_RESOURCE = "e2e/acceptance/test_todo_contract.py";

    /** 只允许所有者读取的文件权限，Docker 映射角色同样不能覆盖。 */
    private static final Set<PosixFilePermission> READ_ONLY_FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ
    );

    /** 只允许遍历和读取的目录权限。 */
    private static final Set<PosixFilePermission> READ_ONLY_DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_EXECUTE
    );

    /** 工具类不允许实例化。 */
    private TodoAcceptanceFixture() {
    }

    /**
     * 安装契约和 pytest，并返回用于后续篡改检查的 SHA-256 摘要。
     *
     * @param projectDirectory 当前 E2E 项目根目录
     * @param objectMapper JSON 校验器
     * @return 相对路径到内容摘要的不可变映射
     * @throws IOException 资源读取、写入或权限设置失败
     */
    public static Map<String, String> install(Path projectDirectory, ObjectMapper objectMapper) throws IOException {
        Path acceptanceDirectory = projectDirectory.resolve("acceptance");
        Files.createDirectories(acceptanceDirectory);
        Path contract = acceptanceDirectory.resolve("contract.json");
        Path test = acceptanceDirectory.resolve("test_todo_contract.py");
        copyResource(CONTRACT_RESOURCE, contract);
        copyResource(TEST_RESOURCE, test);
        JsonNode root = objectMapper.readTree(contract.toFile());
        if (root.path("contract_version").asInt() != 1 || root.path("requirements").size() != 8) {
            throw new IllegalStateException("Todo acceptance 契约结构无效");
        }
        setReadOnly(contract);
        setReadOnly(test);
        Files.setPosixFilePermissions(acceptanceDirectory, READ_ONLY_DIRECTORY_PERMISSIONS);
        return Map.of(
                "acceptance/contract.json", sha256(contract),
                "acceptance/test_todo_contract.py", sha256(test)
        );
    }

    /**
     * 校验安装后的固定文件仍存在且内容摘要未变化。
     *
     * @param projectDirectory 当前项目根目录
     * @param expectedDigests 安装时摘要
     * @throws IOException 文件读取失败
     */
    public static void verifyUnmodified(
            Path projectDirectory,
            Map<String, String> expectedDigests
    ) throws IOException {
        for (Map.Entry<String, String> entry : expectedDigests.entrySet()) {
            Path file = projectDirectory.resolve(entry.getKey());
            if (!Files.isRegularFile(file) || !entry.getValue().equals(sha256(file))) {
                throw new AssertionError("Harness acceptance 契约被修改: " + entry.getKey());
            }
        }
    }

    /**
     * 以 Harness 权威语义校验 Todo 响应严格按 id 倒序，供无 Python 的 L0 测试复现 ASC 偏差。
     *
     * @param responseItems Todo JSON 数组
     */
    public static void requireDescendingIds(JsonNode responseItems) {
        if (!responseItems.isArray()) {
            throw new AssertionError("TODO-API-002 要求响应为 JSON 数组");
        }
        java.util.List<Long> actual = new ArrayList<>();
        responseItems.forEach(item -> actual.add(item.path("id").asLong(Long.MIN_VALUE)));
        java.util.List<Long> expected = actual.stream().sorted(Comparator.reverseOrder()).toList();
        if (!actual.equals(expected)) {
            throw new AssertionError("TODO-API-002 要求 id_desc，actual=" + actual);
        }
    }

    /** 把 classpath 固定资源复制到目标文件，不接受调用方正文。 */
    private static void copyResource(String resource, Path target) throws IOException {
        try (InputStream input = TodoAcceptanceFixture.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("缺少 acceptance 资源: " + resource);
            }
            Files.copy(input, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 将验收文件设置为 POSIX 只读，失败时不静默降级。 */
    private static void setReadOnly(Path file) throws IOException {
        Files.setPosixFilePermissions(file, READ_ONLY_FILE_PERMISSIONS);
    }

    /** 计算文件 SHA-256 十六进制摘要。 */
    private static String sha256(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK 缺少 SHA-256", impossible);
        }
    }
}
