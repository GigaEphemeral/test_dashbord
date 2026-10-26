package com.javalearn.lab.seckill.lab.thread;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.seckill.lab.thread.ThreadLabTestSupport.ManualTtlScheduler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static com.javalearn.lab.seckill.lab.thread.ThreadLabTestSupport.clock;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end wiring of design §9.3 without Docker: a real {@link LifecycleDemoService}
 * with the real {@link FileLifecycleSnapshotSink} and a fake {@code jcmd} — one
 * {@code freeze} must leave exactly one complete bundle on disk, with the freeze
 * observation in {@code metadata.json}.
 */
class FreezeSnapshotWiringTest {

    @TempDir
    Path root;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LabThreadProperties properties = TestLabThreadProperties.defaults();

    @Test
    void freezeWritesTheBundleThroughTheRealSink() throws IOException {
        properties.getSnapshot().setRootDir(root.toString());
        ThreadSnapshotService snapshots = new ThreadSnapshotService(properties, "app-1",
                clock(1_000L), (args, timeout) -> "canned " + String.join(" ", args) + "\n",
                objectMapper, new ThreadSnapshotMetrics(registry), (view, snapshot) -> { });
        LifecycleDemoMetrics demoMetrics = new LifecycleDemoMetrics(registry);
        LifecycleDemoService demo = new LifecycleDemoService(properties, clock(1_000L),
                new ManualTtlScheduler(), demoMetrics, new FileLifecycleSnapshotSink(snapshots));
        demoMetrics.bind(demo);

        try {
            demo.start();
            ThreadLabTestSupport.waitFor("demo state RUNNABLE", Duration.ofSeconds(5),
                    () -> demo.liveState() == DemoState.RUNNABLE ? Boolean.TRUE : null);

            demo.freeze();

            List<Path> folders;
            try (var entries = Files.list(root)) {
                folders = entries.filter(Files::isDirectory).toList();
            }
            assertThat(folders).hasSize(1);
            Path bundle = folders.get(0);
            assertThat(bundle.getFileName().toString()).isEqualTo("19700101-000001-000_app-1");
            assertThat(bundle.resolve("thread-dump.txt")).hasContent("canned Thread.print -l\n");
            assertThat(bundle.resolve("heap-info.txt")).hasContent("canned GC.heap_info\n");
            assertThat(bundle.resolve("class-histogram.txt")).hasContent("canned GC.class_histogram\n");
            assertThat(bundle.resolve("analysis-template.md")).exists();

            JsonNode metadata = objectMapper.readTree(bundle.resolve("metadata.json").toFile());
            assertThat(metadata.get("trigger").asText()).isEqualTo("freeze");
            assertThat(metadata.get("state").asText()).isEqualTo("RUNNABLE");
            assertThat(metadata.get("reason").asText()).isEqualTo("freeze");
            assertThat(metadata.get("threadName").asText())
                    .isEqualTo(properties.getLifecycle().getThreadName());
        } finally {
            demo.reset();
        }
    }
}
