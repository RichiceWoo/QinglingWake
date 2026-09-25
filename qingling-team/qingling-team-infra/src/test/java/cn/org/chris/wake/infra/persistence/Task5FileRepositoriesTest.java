package cn.org.chris.wake.infra.persistence;

import cn.org.chris.wake.domain.event.EventService;
import cn.org.chris.wake.domain.mailbox.MailboxService;
import cn.org.chris.wake.domain.model.MailMessage;
import cn.org.chris.wake.domain.model.MailStatus;
import cn.org.chris.wake.infra.persistence.event.JsonlEventRepository;
import cn.org.chris.wake.infra.persistence.mailbox.FileMailboxRepository;
import cn.org.chris.wake.infra.persistence.workspace.FileWorkspaceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实临时文件系统验证 Task 5 三类 Repository 的 Python 兼容契约。
 */
class Task5FileRepositoriesTest {

    /** JUnit 为每项测试提供的隔离根目录。 */
    @TempDir
    Path temporaryDirectory;

    /**
     * Java 持久化结果必须与从 Python 实现固化的 golden JSON 完全一致。
     *
     * @throws Exception fixture 或临时文件读取失败
     */
    @Test
    void shouldReadPythonGoldenFixturesAndProduceSameJsonContract() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode expectedMailbox;
        JsonNode expectedEvent;
        try (InputStream mailboxFixture = Objects.requireNonNull(
                getClass().getResourceAsStream("/golden/task5/mailbox.json"), "mailbox fixture 不存在"
        ); InputStream eventFixture = Objects.requireNonNull(
                getClass().getResourceAsStream("/golden/task5/events.jsonl"), "event fixture 不存在"
        )) {
            expectedMailbox = mapper.readTree(mailboxFixture);
            expectedEvent = mapper.readTree(eventFixture);
        }

        Path workspace = temporaryDirectory.resolve("workspace-golden");
        new FileWorkspaceRepository(workspace).initializeProject("p1");
        Clock fixedClock = Clock.fixed(Instant.parse("2026-09-25T01:02:03Z"), ZoneOffset.UTC);
        MailboxService mailboxService = new MailboxService(
                new FileMailboxRepository(workspace, mapper, fixedClock), () -> "msg-1a2b3c4d", fixedClock
        );
        mailboxService.send(
                "p1", "pm", "manager", "task_assign", "产品设计", Map.of("priority", "P0")
        );
        EventService eventService = new EventService(new JsonlEventRepository(workspace, mapper, fixedClock));
        eventService.append("p1", "manager", "project_created", Map.of("source", "human"));

        JsonNode actualMailbox = mapper.readTree(
                workspace.resolve("shared/projects/p1/mailboxes/pm.json").toFile()
        );
        String eventLine = Files.readAllLines(
                workspace.resolve("shared/projects/p1/events.jsonl"), StandardCharsets.UTF_8
        ).get(0);
        assertThat(actualMailbox).isEqualTo(expectedMailbox);
        assertThat(mapper.readTree(eventLine)).isEqualTo(expectedEvent);
    }

    /**
     * 并发发送不能丢信，领取、完成和 JSON 字段必须保持三态契约。
     *
     * @throws Exception 并发任务或测试文件读取失败
     */
    @Test
    void shouldPreserveAllConcurrentMailsAndStrictStateTransitions() throws Exception {
        Path workspace = temporaryDirectory.resolve("workspace");
        ObjectMapper mapper = new ObjectMapper();
        new FileWorkspaceRepository(workspace).initializeProject("p1");
        FileMailboxRepository repository = new FileMailboxRepository(
                workspace,
                mapper,
                Clock.fixed(Instant.parse("2026-09-25T01:02:03Z"), ZoneOffset.UTC)
        );
        AtomicInteger ids = new AtomicInteger();
        MailboxService service = new MailboxService(
                repository,
                () -> "msg-" + String.format("%08x", ids.incrementAndGet()),
                Clock.fixed(Instant.parse("2026-09-25T01:02:03Z"), ZoneOffset.UTC)
        );
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<String>> sends = new ArrayList<>();
            for (int index = 0; index < 20; index++) {
                int messageIndex = index;
                sends.add(() -> service.send(
                        "p1", "pm", "manager", "task_assign", "m" + messageIndex, Map.of("idx", messageIndex)
                ));
            }
            List<Future<String>> futures = executor.invokeAll(sends);
            assertThat(futures).allSatisfy(future -> assertThat(future.get()).matches("^msg-[0-9a-f]{8}$"));
        } finally {
            executor.shutdownNow();
        }

        Path inbox = workspace.resolve("shared/projects/p1/mailboxes/pm.json");
        JsonNode onDisk = mapper.readTree(inbox.toFile());
        assertThat(onDisk).hasSize(20);
        assertThat(onDisk.get(0).get("timestamp").asText()).endsWith("+00:00");
        assertThat(onDisk.get(0).get("status").asText()).isEqualTo("unread");
        assertThat(onDisk.get(0).get("processing_since").isNull()).isTrue();

        List<MailMessage> claimed = service.readInbox("p1", "pm");
        assertThat(claimed).hasSize(20).allMatch(message -> message.status() == MailStatus.IN_PROGRESS);
        assertThat(service.readInbox("p1", "pm")).isEmpty();
        service.markDone("p1", "pm", claimed.get(0).id());
        assertThatThrownBy(() -> service.markDone("p1", "pm", claimed.get(0).id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not in_progress");
    }

    /**
     * 超时 in_progress 邮件应恢复为 unread，近期邮件保持不变。
     */
    @Test
    void shouldResetOnlyStaleInProgressMail() {
        Path workspace = temporaryDirectory.resolve("workspace-stale");
        ObjectMapper mapper = new ObjectMapper();
        new FileWorkspaceRepository(workspace).initializeProject("p1");
        FileMailboxRepository initialRepository = new FileMailboxRepository(
                workspace, mapper, Clock.fixed(Instant.parse("2026-09-25T00:00:00Z"), ZoneOffset.UTC)
        );
        MailboxService initialService = new MailboxService(
                initialRepository, () -> "msg-deadbeef",
                Clock.fixed(Instant.parse("2026-09-25T00:00:00Z"), ZoneOffset.UTC)
        );
        initialService.send("p1", "pm", "manager", "task_assign", "s", "c");
        initialService.readInbox("p1", "pm");

        FileMailboxRepository laterRepository = new FileMailboxRepository(
                workspace, mapper, Clock.fixed(Instant.parse("2026-09-25T01:00:00Z"), ZoneOffset.UTC)
        );
        MailboxService laterService = new MailboxService(laterRepository);

        assertThat(laterService.resetStale("p1", "pm", Duration.ofMinutes(10))).isEqualTo(1);
        assertThat(laterService.readInbox("p1", "pm")).hasSize(1);
    }

    /**
     * 项目初始化应完整且幂等，角色权限、原子读写和列举行为应一致。
     *
     * @throws Exception 测试文件读取失败
     */
    @Test
    void shouldInitializeAndProtectSharedWorkspace() throws Exception {
        Path workspace = temporaryDirectory.resolve("workspace-policy");
        FileWorkspaceRepository repository = new FileWorkspaceRepository(workspace);
        repository.initializeProject("p1");
        Path pmInbox = workspace.resolve("shared/projects/p1/mailboxes/pm.json");
        Files.writeString(pmInbox, "[{\"kept\":true}]", StandardCharsets.UTF_8);
        repository.initializeProject("p1");

        repository.write("p1", "pm", "design/product_spec.md", "功能清单");
        repository.write("p1", "rd", "reviews/product_design/rd_review.md", "通过");

        assertThat(Files.readString(pmInbox)).isEqualTo("[{\"kept\":true}]");
        assertThat(repository.read("p1", "manager", "design/product_spec.md")).isEqualTo("功能清单");
        assertThat(repository.list("p1", "qa"))
                .contains("design/product_spec.md", "reviews/product_design/rd_review.md", "events.jsonl");
        assertThatThrownBy(() -> repository.write("p1", "rd", "design/forbidden.md", "x"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> repository.read("p1", "pm", "../../etc/passwd"))
                .isInstanceOf(SecurityException.class);
    }

    /**
     * 已存在目录中的符号链接不得用于读取或写出项目根之外的数据。
     *
     * @throws Exception 创建测试符号链接失败
     */
    @Test
    void shouldRejectSymbolicLinkEscape() throws Exception {
        Path workspace = temporaryDirectory.resolve("workspace-symlink");
        FileWorkspaceRepository repository = new FileWorkspaceRepository(workspace);
        repository.initializeProject("p1");
        Path outside = temporaryDirectory.resolve("outside");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("secret.md"), "secret", StandardCharsets.UTF_8);
        Path link = workspace.resolve("shared/projects/p1/design/external");
        Files.createSymbolicLink(link, outside);

        assertThatThrownBy(() -> repository.read("p1", "pm", "design/external/secret.md"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> repository.write("p1", "pm", "design/external/hack.md", "hack"))
                .isInstanceOf(SecurityException.class);
        assertThat(Files.exists(outside.resolve("hack.md"))).isFalse();
    }

    /**
     * 并发事件必须获得唯一连续 seq，损坏尾行不得阻断读取或后续追加。
     *
     * @throws Exception 并发任务或测试文件写入失败
     */
    @Test
    void shouldKeepEventSequenceUniqueAndRecoverAfterCorruptLine() throws Exception {
        Path workspace = temporaryDirectory.resolve("workspace-events");
        new FileWorkspaceRepository(workspace).initializeProject("p1");
        JsonlEventRepository repository = new JsonlEventRepository(
                workspace,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-09-25T01:02:03Z"), ZoneOffset.UTC)
        );
        EventService service = new EventService(repository);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        Set<Long> sequences = new HashSet<>();
        try {
            List<Callable<Long>> appends = new ArrayList<>();
            for (int index = 0; index < 20; index++) {
                int eventIndex = index;
                appends.add(() -> service.append(
                        "p1", "manager", "requirements_discovery_started", Map.of("idx", eventIndex)
                ));
            }
            for (Future<Long> future : executor.invokeAll(appends)) {
                sequences.add(future.get());
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(sequences).containsExactlyInAnyOrderElementsOf(
                java.util.stream.LongStream.rangeClosed(1, 20).boxed().toList()
        );
        Path eventsPath = workspace.resolve("shared/projects/p1/events.jsonl");
        Files.writeString(eventsPath, "{\"broken\":true", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        assertThat(service.readAll("p1")).hasSize(20);
        assertThat(service.append("p1", "manager", "assigned", Map.of())).isEqualTo(21L);

        List<Map<String, Object>> events = service.readAll("p1");
        assertThat(events).hasSize(21);
        assertThat(events.get(0).get("ts").toString()).endsWith("+00:00");
        assertThat(service.tail("p1", 3))
                .extracting(event -> ((Number) event.get("seq")).longValue())
                .containsExactly(19L, 20L, 21L);
    }
}
