package com.javalearn.lab.seckill.jdk8.lab.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.seckill.jdk8.lab.api.SnapshotView;
import com.javalearn.lab.seckill.jdk8.lab.config.SnapshotProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Failure, disabled and partial-bundle semantics of the JDK 8 snapshot (§9.3):
 * the endpoint contract says a capture never throws and a directory on disk
 * always means a complete five-file bundle. A missing template is a capture
 * failure, not a four-file bundle.
 */
class SnapshotFailureTest {

    @TempDir
    Path root;

    @TempDir
    Path templateDir;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private SnapshotProperties properties;

    @BeforeEach
    void setUp() throws IOException {
        Path template = SnapshotTestSupport.writeTemplate(templateDir);
        properties = SnapshotTestSupport.properties(root, template);
    }

    @Test
    void aFailingToolIsSwallowedAndLeavesNoPartialFolder() throws IOException {
        SnapshotTestSupport.FakeExecutor executor = new SnapshotTestSupport.FakeExecutor();
        executor.failOnInvocation = 2;
        ThreadSnapshotService service = newService(executor);

        SnapshotView view = service.capture();

        assertThat(view.isCaptured()).isFalse();
        assertThat(view.getError()).contains("IOException").contains("unable to attach");
        assertThat(view.getFiles()).isEmpty();
        assertThat(snapshotFolderNames()).isEmpty();
        assertThat(registry.get("thread_lab_snapshot_attempts_total")
                .tag("trigger", "manual").counter().count()).isEqualTo(1D);
        assertThat(registry.get("thread_lab_snapshot_failures_total")
                .tag("trigger", "manual").counter().count()).isEqualTo(1D);
        assertThat(registry.get("thread_lab_snapshot_success_total")
                .tag("trigger", "manual").counter().count()).isZero();
        assertThat(registry.get("thread_lab_snapshot_duration_seconds")
                .tag("trigger", "manual").timer().count()).isEqualTo(1L);
    }

    @Test
    void aMissingTemplateFailsWithoutLeavingABundle() throws IOException {
        properties.setTemplatePath(templateDir.resolve("does-not-exist.md").toString());
        ThreadSnapshotService service = newService(new SnapshotTestSupport.FakeExecutor());

        SnapshotView view = service.capture();

        assertThat(view.isCaptured()).isFalse();
        assertThat(view.getError()).contains("analysis template missing");
        assertThat(snapshotFolderNames()).isEmpty();
    }

    @Test
    void disabledCaptureWritesNothingAndCountsTheSkip() throws IOException {
        properties.setEnabled(false);
        SnapshotTestSupport.FakeExecutor executor = new SnapshotTestSupport.FakeExecutor();
        ThreadSnapshotService service = newService(executor);

        SnapshotView view = service.capture();

        assertThat(view.isCaptured()).isFalse();
        assertThat(view.getError()).contains("disabled");
        assertThat(view.getTrigger()).isEqualTo("manual");
        assertThat(executor.invocations).isEmpty();
        assertThat(snapshotFolderNames()).isEmpty();
        assertThat(registry.get("thread_lab_snapshot_skipped_total")
                .tag("reason", "disabled").counter().count()).isEqualTo(1D);
        assertThat(registry.get("thread_lab_snapshot_attempts_total")
                .tag("trigger", "manual").counter().count()).isZero();
    }

    @Test
    void captureNeverThrowsEvenWhenTheExecutorIsInterrupted() throws IOException {
        ThreadSnapshotService service = newService(new JvmCommandExecutor() {
            @Override
            public String execute(List<String> command, java.time.Duration timeout)
                    throws InterruptedException {
                throw new InterruptedException("test interrupt");
            }
        });

        final SnapshotView[] holder = new SnapshotView[1];
        assertThatCode(() -> holder[0] = service.capture()).doesNotThrowAnyException();

        assertThat(holder[0].isCaptured()).isFalse();
        assertThat(holder[0].getError()).contains("InterruptedException");
        assertThat(Thread.currentThread().isInterrupted())
                .as("the interrupt status is restored").isTrue();
        // Clear it so the remaining tests/thread pool are unaffected.
        Thread.interrupted();
    }

    @Test
    void listingAnEmptyRootReturnsAnEmptyList() {
        ThreadSnapshotService service = newService(new SnapshotTestSupport.FakeExecutor());

        assertThat(service.listBundles()).isEmpty();
    }

    @Test
    void listingIgnoresFilesAndUnrelatedDirectories() throws IOException {
        Files.write(root.resolve("stray.txt"), "x".getBytes());
        Files.createDirectory(root.resolve("not-a-bundle"));
        ThreadSnapshotService service = newService(new SnapshotTestSupport.FakeExecutor());

        assertThat(service.listBundles()).isEmpty();
    }

    private ThreadSnapshotService newService(JvmCommandExecutor executor) {
        return new ThreadSnapshotService(properties, "app-jdk8", SnapshotTestSupport.PID,
                java.time.Clock.systemUTC(), executor, new ObjectMapper(),
                new SnapshotMetrics(registry), view -> { });
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
        return names;
    }
}
