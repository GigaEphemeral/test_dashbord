package com.javalearn.lab.seckill.jdk8.lab.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.seckill.jdk8.lab.api.SnapshotBundleView;
import com.javalearn.lab.seckill.jdk8.lab.api.SnapshotView;
import com.javalearn.lab.seckill.jdk8.lab.config.SnapshotProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design §9.3 contract of the JDK 8 {@link ThreadSnapshotService}, Docker-free:
 * a fake executor supplies canned jstack/jmap output, the clock is pinned, the
 * root and template are temp files. Covers the five-file bundle, folder naming,
 * metadata manifest, listing, instance sanitization and the current pid.
 */
class ThreadSnapshotServiceTest {

    @TempDir
    Path root;

    @TempDir
    Path templateDir;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SnapshotTestSupport.FakeExecutor executor =
            new SnapshotTestSupport.FakeExecutor();
    private final SnapshotTestSupport.MutableClock clock =
            new SnapshotTestSupport.MutableClock(1_000_000L);

    private SnapshotProperties properties;
    private ThreadSnapshotService service;

    @BeforeEach
    void setUp() throws IOException {
        Path template = SnapshotTestSupport.writeTemplate(templateDir);
        properties = SnapshotTestSupport.properties(root, template);
        service = newService("app-jdk8");
    }

    @Test
    void captureWritesTheFullBundleWithJdk8ToolOutput() throws IOException {
        SnapshotView view = service.capture();

        assertThat(view.isCaptured()).isTrue();
        assertThat(view.getTrigger()).isEqualTo("manual");
        assertThat(view.getError()).isNull();
        Path directory = Paths.get(view.getDirectory());
        assertThat(directory.getParent().toAbsolutePath()).isEqualTo(root.toAbsolutePath());
        assertThat(directory.getFileName().toString())
                .isEqualTo("19700101-001640-000_app-jdk8");
        assertThat(view.getFiles()).containsExactly(
                "thread-dump.txt", "heap-info.txt", "class-histogram.txt",
                "metadata.json", "analysis-template.md");

        assertThat(read(directory.resolve("thread-dump.txt")))
                .isEqualTo("canned jstack -l 4242\n");
        assertThat(read(directory.resolve("heap-info.txt")))
                .isEqualTo("canned jcmd 4242 GC.heap_info\n");
        assertThat(read(directory.resolve("class-histogram.txt")))
                .isEqualTo("canned jmap -histo 4242\n");
        assertThat(executor.invocations).hasSize(3);
        assertThat(executor.invocations.get(0)).containsExactly("jstack", "-l", "4242");
        assertThat(executor.invocations.get(1)).containsExactly("jcmd", "4242", "GC.heap_info");
        assertThat(executor.invocations.get(2)).containsExactly("jmap", "-histo", "4242");

        JsonNode metadata = objectMapper.readTree(directory.resolve("metadata.json").toFile());
        assertThat(metadata.get("instance").asText()).isEqualTo("app-jdk8");
        assertThat(metadata.get("trigger").asText()).isEqualTo("manual");
        assertThat(metadata.get("toolset").asText()).isEqualTo("jstack+jmap+jcmd");
        assertThat(metadata.get("jvmVersion").asText())
                .isEqualTo(System.getProperty("java.version"));
        assertThat(metadata.get("vmVersion").asText())
                .isEqualTo(ManagementFactory.getRuntimeMXBean().getVmVersion());
        assertThat(metadata.get("jvmVendor").asText()).isNotBlank();
        assertThat(metadata.get("gcCollectors").asText()).isNotBlank();
        assertThat(metadata.get("uptimeMs").asLong()).isPositive();
        assertThat(metadata.get("pid").asLong()).isEqualTo(4_242L);
        assertThat(metadata.get("capturedAtEpochMs").asLong()).isEqualTo(1_000_000L);
        assertThat(metadata.get("jmapMode").asText()).isEqualTo("histo");
        List<String> files = objectMapper.convertValue(metadata.get("files"), List.class);
        assertThat(files).containsExactly(
                "thread-dump.txt", "heap-info.txt", "class-histogram.txt",
                "metadata.json", "analysis-template.md");
        assertThat(metadata.get("commands").get("thread-dump.txt").asText())
                .isEqualTo("jstack -l 4242");
        assertThat(metadata.get("commands").get("heap-info.txt").asText())
                .isEqualTo("jcmd 4242 GC.heap_info");
        assertThat(metadata.get("commands").get("class-histogram.txt").asText())
                .isEqualTo("jmap -histo 4242");

        assertThat(read(directory.resolve("analysis-template.md")))
                .isEqualTo(SnapshotTestSupport.TEMPLATE_TEXT);

        assertThat(triggerCounter("thread_lab_snapshot_attempts_total")).isEqualTo(1D);
        assertThat(triggerCounter("thread_lab_snapshot_success_total")).isEqualTo(1D);
        assertThat(triggerCounter("thread_lab_snapshot_failures_total")).isZero();
        assertThat(registry.get("thread_lab_snapshot_duration_seconds")
                .tag("trigger", "manual").timer().count()).isEqualTo(1L);
    }

    @Test
    void instanceIsSanitizedAndCollisionsGetASuffix() {
        ThreadSnapshotService weird = newService("jl app/8");

        SnapshotView first = weird.capture();
        SnapshotView second = weird.capture();

        assertThat(Paths.get(first.getDirectory()).getFileName().toString())
                .isEqualTo("19700101-001640-000_jl-app-8");
        assertThat(Paths.get(second.getDirectory()).getFileName().toString())
                .isEqualTo("19700101-001640-000_jl-app-8-2");
        // Both captures share this test's registry, so the meter family reports 2.
        assertThat(triggerCounter("thread_lab_snapshot_attempts_total")).isEqualTo(2D);
    }

    @Test
    void listBundlesReportsFolderTimestampTriggerAndSizes() {
        SnapshotView capture = service.capture();

        List<SnapshotBundleView> bundles = service.listBundles();

        assertThat(bundles).hasSize(1);
        SnapshotBundleView bundle = bundles.get(0);
        assertThat(bundle.getName()).isEqualTo("19700101-001640-000_app-jdk8");
        assertThat(bundle.getDirectory()).isEqualTo(capture.getDirectory());
        assertThat(bundle.getTimestamp()).isEqualTo("1970-01-01T00:16:40Z");
        assertThat(bundle.getTrigger()).isEqualTo("manual");
        assertThat(bundle.getFiles()).hasSize(5);
        assertThat(bundle.getTotalBytes()).isPositive();
        for (com.javalearn.lab.seckill.jdk8.lab.api.SnapshotFileView file : bundle.getFiles()) {
            assertThat(file.getBytes()).isPositive();
        }
    }

    @Test
    void newestBundleIsListedFirst() {
        service.capture();
        clock.advance(Duration.ofSeconds(1));
        SnapshotView newer = service.capture();

        List<SnapshotBundleView> bundles = service.listBundles();

        assertThat(bundles).hasSize(2);
        assertThat(bundles.get(0).getDirectory()).isEqualTo(newer.getDirectory());
        assertThat(bundles.get(1).getTimestamp()).isEqualTo("1970-01-01T00:16:40Z");
    }

    @Test
    void currentPidMatchesTheRuntimeMxBeanName() {
        String name = ManagementFactory.getRuntimeMXBean().getName();
        long expected = Long.parseLong(name.substring(0, name.indexOf('@')));

        assertThat(ThreadSnapshotService.currentPid()).isEqualTo(expected).isPositive();
    }

    @Test
    void aSuccessfulCaptureIsAnnotatedWithItsView() {
        List<SnapshotView> annotated = new ArrayList<SnapshotView>();
        ThreadSnapshotService annotatedService = newService("app-jdk8", annotated::add);

        SnapshotView view = annotatedService.capture();

        assertThat(annotated).hasSize(1);
        assertThat(annotated.get(0).getDirectory()).isEqualTo(view.getDirectory());
        assertThat(annotated.get(0).getTrigger()).isEqualTo("manual");
        assertThat(annotated.get(0).getCapturedAtEpochMs()).isEqualTo(1_000_000L);
    }

    @Test
    void aThrowingAnnotatorNeverFailsTheCapture() {
        ThreadSnapshotService annotatedService = newService("app-jdk8", view -> {
            throw new IllegalStateException("grafana exploded");
        });

        SnapshotView view = annotatedService.capture();

        assertThat(view.isCaptured()).isTrue();
        assertThat(view.getError()).isNull();
        assertThat(triggerCounter("thread_lab_snapshot_success_total")).isEqualTo(1D);
        assertThat(triggerCounter("thread_lab_snapshot_failures_total")).isZero();
    }

    @Test
    void aFailedCaptureIsNeverAnnotated() {
        executor.failOnInvocation = 1;
        List<SnapshotView> annotated = new ArrayList<SnapshotView>();
        ThreadSnapshotService annotatedService = newService("app-jdk8", annotated::add);

        SnapshotView view = annotatedService.capture();

        assertThat(view.isCaptured()).isFalse();
        assertThat(annotated).isEmpty();
    }

    private ThreadSnapshotService newService(String instanceId) {
        return newService(instanceId, view -> { });
    }

    private ThreadSnapshotService newService(String instanceId, SnapshotAnnotator annotator) {
        return new ThreadSnapshotService(properties, instanceId, SnapshotTestSupport.PID, clock,
                executor, objectMapper, new SnapshotMetrics(registry), annotator);
    }

    private double triggerCounter(String name) {
        return registry.get(name).tag("trigger", "manual").counter().count();
    }

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
