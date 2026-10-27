package com.javalearn.lab.seckill.lab.thread;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.seckill.lab.api.ThreadSnapshotView;
import com.javalearn.lab.seckill.lab.thread.ThreadLabTestSupport.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static com.javalearn.lab.seckill.lab.thread.ThreadLabTestSupport.clock;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design §9.3 contract of {@link ThreadSnapshotService}, Docker-free: a fake
 * {@link JvmCommandExecutor} supplies canned {@code jcmd} output, the clock is
 * pinned, the root is a temp directory. Covers the bundle contents/names, the
 * folder naming, retention by count and age, the failure path (no throw, no
 * partial folder), the disabled switch, the sanitized instance id and the
 * template copy.
 */
class ThreadSnapshotServiceTest {

    private static final DateTimeFormatter FOLDER_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

    @TempDir
    Path root;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ThreadSnapshotMetrics metrics = new ThreadSnapshotMetrics(registry);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FakeExecutor executor = new FakeExecutor();
    private final RecordingAnnotator annotator = new RecordingAnnotator();
    private final MutableClock clock = clock(1_000_000L);
    private final LabThreadProperties properties = TestLabThreadProperties.defaults();

    private ThreadSnapshotService service;

    @BeforeEach
    void setUp() {
        properties.getSnapshot().setRootDir(root.toString());
        service = newService("app-1");
    }

    @Test
    void freezeCaptureWritesTheFullBundleWithConfiguredNames() throws IOException {
        ThreadSnapshotView view = service.capture(observation("freeze"));

        assertThat(view.captured()).isTrue();
        assertThat(view.trigger()).isEqualTo("freeze");
        assertThat(view.error()).isNull();
        Path directory = Path.of(view.directory());
        assertThat(directory.getParent().toAbsolutePath())
                .isEqualTo(root.toAbsolutePath());
        assertThat(directory.getFileName().toString()).isEqualTo("19700101-001640-000_app-1");
        assertThat(view.files()).containsExactly(
                "thread-dump.txt", "heap-info.txt", "class-histogram.txt",
                "metadata.json", "analysis-template.md");

        assertThat(Files.readString(directory.resolve("thread-dump.txt")))
                .isEqualTo("canned Thread.print -l\n");
        assertThat(Files.readString(directory.resolve("heap-info.txt")))
                .isEqualTo("canned GC.heap_info\n");
        assertThat(Files.readString(directory.resolve("class-histogram.txt")))
                .isEqualTo("canned GC.class_histogram\n");
        assertThat(executor.invocations).containsExactly(
                List.of("Thread.print", "-l"),
                List.of("GC.heap_info"),
                List.of("GC.class_histogram"));

        JsonNode metadata = objectMapper.readTree(directory.resolve("metadata.json").toFile());
        assertThat(metadata.get("instance").asText()).isEqualTo("app-1");
        assertThat(metadata.get("trigger").asText()).isEqualTo("freeze");
        assertThat(metadata.get("state").asText()).isEqualTo("RUNNABLE");
        assertThat(metadata.get("stateCode").asInt()).isEqualTo(1);
        assertThat(metadata.get("step").asInt()).isEqualTo(3);
        assertThat(metadata.get("threadName").asText()).isEqualTo("thread-lab-demo");
        assertThat(metadata.get("threadId").asLong()).isEqualTo(42L);
        assertThat(metadata.get("capturedAtEpochMs").asLong()).isEqualTo(1_000_000L);
        assertThat(metadata.get("jvmVersion").asText())
                .isEqualTo(ManagementFactory.getRuntimeMXBean().getVmVersion());
        assertThat(metadata.get("jvmVendor").asText()).isNotBlank();
        assertThat(metadata.get("uptimeMs").asLong()).isPositive();
        assertThat(metadata.get("pid").asLong()).isPositive();
        assertThat(metadata.get("commands").get("thread-dump.txt").asText())
                .contains("jcmd").contains("Thread.print -l");
        // The metadata manifest must describe the whole bundle (5 files), not
        // only the three jcmd outputs written before metadata.json itself.
        assertThat(objectMapper.convertValue(metadata.get("files"), List.class))
                .containsExactly("thread-dump.txt", "heap-info.txt", "class-histogram.txt",
                        "metadata.json", "analysis-template.md");

        assertThat(Files.readString(directory.resolve("analysis-template.md")))
                .isEqualTo(classpathTemplate());

        assertThat(annotator.views).hasSize(1);
        assertThat(annotator.views.get(0).directory()).isEqualTo(view.directory());
        assertThat(triggerCounter("thread_lab_snapshot_attempts_total", "freeze")).isEqualTo(1D);
        assertThat(triggerCounter("thread_lab_snapshot_success_total", "freeze")).isEqualTo(1D);
        assertThat(registry.get("thread_lab_snapshot_duration_seconds")
                .tag("trigger", "freeze").timer().count()).isEqualTo(1L);
    }

    @Test
    void manualCaptureUsesTheSanitizedInstanceAndSuffixesCollisions() {
        ThreadSnapshotService weird = newService("jl app/1");

        ThreadSnapshotView first = weird.capture(observation("manual"));
        ThreadSnapshotView second = weird.capture(observation("manual"));

        assertThat(first.trigger()).isEqualTo("manual");
        assertThat(Path.of(first.directory()).getFileName().toString())
                .isEqualTo("19700101-001640-000_jl-app-1");
        assertThat(second.directory()).isNotEqualTo(first.directory());
        assertThat(Path.of(second.directory()).getFileName().toString())
                .isEqualTo("19700101-001640-000_jl-app-1-2");
        assertThat(triggerCounter("thread_lab_snapshot_attempts_total", "manual")).isEqualTo(2D);
        assertThat(triggerCounter("thread_lab_snapshot_success_total", "manual")).isEqualTo(2D);
    }

    @Test
    void retentionPrunesByAgeThenByCountAndLeavesUnrelatedEntriesAlone() throws IOException {
        properties.getSnapshot().setMaxCount(2L);
        properties.getSnapshot().setMaxAgeSeconds(3_600L);
        clock.advance(Duration.ofMillis(1_000_000_000L - 1_000_000L));
        Instant now = clock.instant();
        Path aged = seedSnapshot(now.minus(Duration.ofHours(2)), "app-1");
        Path middle = seedSnapshot(now.minus(Duration.ofMinutes(10)), "app-1");
        Path recent = seedSnapshot(now.minus(Duration.ofMinutes(3)), "app-1");
        Path unrelated = Files.createDirectory(root.resolve("keep-me"));

        ThreadSnapshotView view = service.capture(observation("freeze"));

        assertThat(Files.exists(Path.of(view.directory()))).isTrue();
        assertThat(Files.exists(recent)).as("recent stays within max-count").isTrue();
        assertThat(Files.notExists(middle)).as("oldest inside the age window is pruned by count").isTrue();
        assertThat(Files.notExists(aged)).as("older than max-age is pruned").isTrue();
        assertThat(Files.exists(unrelated)).as("unrelated directories are never touched").isTrue();
        assertThat(snapshotFolderNames()).containsExactlyInAnyOrder(
                Path.of(view.directory()).getFileName().toString(),
                recent.getFileName().toString());
        assertThat(registry.get("thread_lab_snapshot_retention_deleted_total").counter().count())
                .isEqualTo(2D);
    }

    @Test
    void zeroMaxAgeDisablesTheAgeRule() throws IOException {
        properties.getSnapshot().setMaxCount(10L);
        properties.getSnapshot().setMaxAgeSeconds(0L);
        clock.advance(Duration.ofDays(30));
        Path ancient = seedSnapshot(clock.instant().minus(Duration.ofDays(20)), "app-1");

        service.capture(observation("freeze"));

        assertThat(Files.exists(ancient)).as("max-age-seconds=0 keeps everything under max-count").isTrue();
        assertThat(registry.get("thread_lab_snapshot_retention_deleted_total").counter().count()).isZero();
    }

    @Test
    void captureFailureIsSwallowedAndLeavesNoPartialFolder() throws IOException {
        executor.fail = true;

        ThreadSnapshotView view = service.capture(observation("freeze"));

        assertThat(view.captured()).isFalse();
        assertThat(view.error()).contains("IOException").contains("jcmd: command not found");
        assertThat(snapshotFolderNames()).isEmpty();
        assertThat(annotator.views).isEmpty();
        assertThat(triggerCounter("thread_lab_snapshot_attempts_total", "freeze")).isEqualTo(1D);
        assertThat(triggerCounter("thread_lab_snapshot_failures_total", "freeze")).isEqualTo(1D);
        assertThat(registry.get("thread_lab_snapshot_success_total")
                .tag("trigger", "freeze").counter().count()).isZero();
        assertThat(registry.get("thread_lab_snapshot_duration_seconds")
                .tag("trigger", "freeze").timer().count()).isEqualTo(1L);
    }

    @Test
    void disabledCaptureWritesNothingAndCountsTheSkip() throws IOException {
        properties.getSnapshot().setEnabled(false);

        ThreadSnapshotView view = service.capture(observation("freeze"));

        assertThat(view.captured()).isFalse();
        assertThat(view.error()).contains("disabled");
        assertThat(snapshotFolderNames()).as("root is @TempDir; it must stay empty").isEmpty();
        assertThat(executor.invocations).isEmpty();
        assertThat(registry.get("thread_lab_snapshot_skipped_total")
                .tag("reason", "disabled").counter().count()).isEqualTo(1D);
        assertThat(registry.get("thread_lab_snapshot_attempts_total")
                .tag("trigger", "freeze").counter().count()).isZero();
    }

    @Test
    void aBrokenAnnotatorCannotTurnASuccessfulCaptureIntoAFailure() {
        annotator.fail = true;

        ThreadSnapshotView view = service.capture(observation("freeze"));

        assertThat(view.captured()).isTrue();
        assertThat(Files.exists(Path.of(view.directory()).resolve("metadata.json"))).isTrue();
        assertThat(triggerCounter("thread_lab_snapshot_success_total", "freeze")).isEqualTo(1D);
    }

    @Test
    void classpathTemplateMatchesTheRepositoryCopy() throws IOException {
        String resource = classpathTemplate();
        Path doc = repositoryTemplate();
        assertThat(Files.readString(doc, StandardCharsets.UTF_8))
                .as("doc/process copy must not drift from the packaged template")
                .isEqualTo(resource);
    }

    // ----------------------------------------------------------------- helpers

    private ThreadSnapshotService newService(String instanceId) {
        return new ThreadSnapshotService(properties, instanceId, clock, executor,
                objectMapper, metrics, annotator);
    }

    private Path seedSnapshot(Instant at, String instance) throws IOException {
        Path directory = Files.createDirectory(root.resolve(FOLDER_TIME.format(at) + "_" + instance));
        Files.writeString(directory.resolve("thread-dump.txt"), "old", StandardCharsets.UTF_8);
        return directory;
    }

    private List<String> snapshotFolderNames() throws IOException {
        try (Stream<Path> entries = Files.list(root)) {
            return entries.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.matches("\\d{8}-\\d{6}-\\d{3}_.*"))
                    .toList();
        }
    }

    private LifecycleSnapshot observation(String reason) {
        return new LifecycleSnapshot(42L, "thread-lab-demo", "RUNNABLE", 1, 3,
                clock.millis(), reason);
    }

    private double triggerCounter(String name, String trigger) {
        return registry.get(name).tag("trigger", trigger).counter().count();
    }

    private static String classpathTemplate() throws IOException {
        try (InputStream in = ThreadSnapshotServiceTest.class.getClassLoader()
                .getResourceAsStream(ThreadSnapshotService.TEMPLATE_RESOURCE)) {
            assertThat(in).as("template resource").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Path repositoryTemplate() {
        Path fromModule = Path.of("../../..", "doc", "process", "snapshot-analysis-template.md");
        if (Files.exists(fromModule)) {
            return fromModule;
        }
        Path fromRoot = Path.of("doc", "process", "snapshot-analysis-template.md");
        assertThat(Files.exists(fromRoot))
                .as("run the test from the module or the repository root")
                .isTrue();
        return fromRoot;
    }

    // ------------------------------------------------------------- test doubles

    private static final class FakeExecutor implements JvmCommandExecutor {
        private final List<List<String>> invocations = new ArrayList<>();
        private boolean fail;

        @Override
        public String execute(List<String> args, Duration timeout) throws IOException {
            invocations.add(List.copyOf(args));
            if (fail) {
                throw new IOException("jcmd: command not found");
            }
            return "canned " + String.join(" ", args) + "\n";
        }
    }

    private static final class RecordingAnnotator implements SnapshotAnnotator {
        private final List<ThreadSnapshotView> views = new ArrayList<>();
        private boolean fail;

        @Override
        public void annotate(ThreadSnapshotView view, LifecycleSnapshot snapshot) {
            views.add(view);
            if (fail) {
                throw new IllegalStateException("grafana down");
            }
        }
    }
}
