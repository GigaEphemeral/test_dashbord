package com.javalearn.lab.seckill.jdk8.lab.snapshot;

import com.javalearn.lab.seckill.jdk8.lab.config.SnapshotProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retention of the JDK 8 snapshot root (§9.3): count and age pruning use the
 * same folder-name timestamp contract as the JDK 21 service, and unrelated
 * directories are never touched. Seeded bundles are real folders written by a
 * previous capture, pruned by a real {@code capture()} — not by hand.
 */
class SnapshotRetentionTest {

    @TempDir
    Path root;

    @TempDir
    Path templateDir;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final SnapshotTestSupport.MutableClock clock =
            new SnapshotTestSupport.MutableClock(1_000_000L);

    private SnapshotProperties properties;
    private ThreadSnapshotService service;

    @BeforeEach
    void setUp() throws IOException {
        Path template = SnapshotTestSupport.writeTemplate(templateDir);
        properties = SnapshotTestSupport.properties(root, template);
        service = newService();
    }

    @Test
    void maxCountKeepsTheNewestAndDeletesTheRest() throws IOException {
        properties.setMaxCount(2L);
        properties.setMaxAgeSeconds(0L);
        clock.advance(Duration.ofHours(3));
        Instant now = clock.instant();
        Path oldest = seed(now.minus(Duration.ofHours(2)), "app-jdk8");
        Path middle = seed(now.minus(Duration.ofMinutes(10)), "app-jdk8");
        Path recent = seed(now.minus(Duration.ofMinutes(3)), "app-jdk8");
        Path unrelated = Files.createDirectory(root.resolve("keep-me"));

        com.javalearn.lab.seckill.jdk8.lab.api.SnapshotView view = service.capture();

        assertThat(Files.exists(java.nio.file.Paths.get(view.getDirectory()))).isTrue();
        assertThat(Files.exists(recent)).as("newest seeded bundle fits max-count").isTrue();
        assertThat(Files.notExists(middle)).as("older bundle is pruned by count").isTrue();
        assertThat(Files.notExists(oldest)).as("oldest bundle is pruned by count").isTrue();
        assertThat(Files.exists(unrelated)).as("unrelated directories are never touched").isTrue();
        assertThat(registry.get("thread_lab_snapshot_retention_deleted_total").counter().count())
                .isEqualTo(2D);
    }

    @Test
    void maxAgePrunesOldBundlesEvenUnderTheCountLimit() throws IOException {
        properties.setMaxCount(10L);
        properties.setMaxAgeSeconds(3_600L);
        clock.advance(Duration.ofHours(3));
        Instant now = clock.instant();
        Path aged = seed(now.minus(Duration.ofHours(2)), "app-jdk8");
        Path recent = seed(now.minus(Duration.ofMinutes(3)), "app-jdk8");

        service.capture();

        assertThat(Files.notExists(aged)).as("older than max-age is pruned").isTrue();
        assertThat(Files.exists(recent)).as("inside the age window stays").isTrue();
        assertThat(registry.get("thread_lab_snapshot_retention_deleted_total").counter().count())
                .isEqualTo(1D);
    }

    @Test
    void zeroMaxAgeDisablesTheAgeRule() throws IOException {
        properties.setMaxCount(10L);
        properties.setMaxAgeSeconds(0L);
        clock.advance(Duration.ofDays(30));
        Path ancient = seed(clock.instant().minus(Duration.ofDays(20)), "app-jdk8");

        service.capture();

        assertThat(Files.exists(ancient))
                .as("max-age-seconds=0 keeps everything under max-count").isTrue();
        assertThat(registry.get("thread_lab_snapshot_retention_deleted_total").counter().count())
                .isZero();
    }

    @Test
    void aFailedCaptureDoesNotPruneAnything() throws IOException {
        properties.setMaxCount(1L);
        clock.advance(Duration.ofHours(1));
        Path older = seed(clock.instant().minus(Duration.ofMinutes(5)), "app-jdk8");
        SnapshotTestSupport.FakeExecutor failing = new SnapshotTestSupport.FakeExecutor();
        failing.failAll = true;
        ThreadSnapshotService failingService = new ThreadSnapshotService(properties, "app-jdk8",
                SnapshotTestSupport.PID, clock, failing,
                new com.fasterxml.jackson.databind.ObjectMapper(), new SnapshotMetrics(registry),
                view -> { });

        assertThat(failingService.capture().isCaptured()).isFalse();

        assertThat(Files.exists(older)).as("retention only runs for a complete bundle").isTrue();
        assertThat(snapshotFolderNames()).hasSize(1);
    }

    private ThreadSnapshotService newService() {
        return new ThreadSnapshotService(properties, "app-jdk8", SnapshotTestSupport.PID, clock,
                new SnapshotTestSupport.FakeExecutor(),
                new com.fasterxml.jackson.databind.ObjectMapper(), new SnapshotMetrics(registry),
                view -> { });
    }

    private Path seed(Instant at, String instance) throws IOException {
        Path directory = Files.createDirectory(root.resolve(
                ThreadSnapshotService.FOLDER_TIME.format(at) + "_" + instance));
        Files.write(directory.resolve("thread-dump.txt"), "old".getBytes(StandardCharsets.UTF_8));
        return directory;
    }

    private List<String> snapshotFolderNames() throws IOException {
        List<String> names = new ArrayList<String>();
        try (Stream<Path> entries = Files.list(root)) {
            entries.forEach(path -> {
                if (Files.isDirectory(path)
                        && path.getFileName().toString().matches("\\d{8}-\\d{6}-\\d{3}_.*")) {
                    names.add(path.getFileName().toString());
                }
            });
        }
        Collections.sort(names);
        return names;
    }
}
