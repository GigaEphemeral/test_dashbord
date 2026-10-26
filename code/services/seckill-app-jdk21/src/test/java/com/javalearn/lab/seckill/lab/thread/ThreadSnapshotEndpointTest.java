package com.javalearn.lab.seckill.lab.thread;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.seckill.lab.api.LabThreadController;
import com.javalearn.lab.seckill.lab.thread.ThreadLabTestSupport.ManualTtlScheduler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static com.javalearn.lab.seckill.lab.thread.ThreadLabTestSupport.clock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract of {@code POST /lab/thread/snapshot} (design §9.3): it returns the
 * snapshot metadata (folder + file names), and a failed or disabled capture is
 * still a 200 with {@code captured=false} — a broken snapshot must never surface
 * as a 5xx, exactly like {@code freeze} must not fail because of it.
 */
class ThreadSnapshotEndpointTest {

    @TempDir
    Path root;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LabThreadProperties properties = TestLabThreadProperties.defaults();
    private final FakeExecutor executor = new FakeExecutor();

    private ThreadSnapshotService snapshots;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        properties.getSnapshot().setRootDir(root.toString());
        snapshots = new ThreadSnapshotService(properties, "app-1", clock(1_000L), executor,
                objectMapper, new ThreadSnapshotMetrics(registry), (view, snapshot) -> { });
        ThreadFaultService faults = new ThreadFaultService(properties, clock(1_000L),
                new ManualTtlScheduler(), new ThreadLabMetrics(registry));
        LifecycleDemoService demo = new LifecycleDemoService(properties, clock(1_000L),
                new ManualTtlScheduler(), new LifecycleDemoMetrics(registry), snapshot -> { });
        mockMvc = MockMvcBuilders.standaloneSetup(
                new LabThreadController(faults, demo, snapshots)).build();
    }

    @Test
    void snapshotEndpointReturnsTheBundleMetadata() throws Exception {
        mockMvc.perform(post("/lab/thread/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.captured").value(true))
                .andExpect(jsonPath("$.data.trigger").value("manual"))
                .andExpect(jsonPath("$.data.files.length()").value(5))
                .andExpect(jsonPath("$.data.files[0]").value("thread-dump.txt"))
                .andExpect(jsonPath("$.data.directory").value(
                        org.hamcrest.Matchers.endsWith("_app-1")));

        assertThat(executor.invocations).hasSize(3);
    }

    @Test
    void aFailingCaptureStillAnswersTwoHundredWithTheError() throws Exception {
        executor.fail = true;

        mockMvc.perform(post("/lab/thread/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.captured").value(false))
                .andExpect(jsonPath("$.data.error").value(
                        org.hamcrest.Matchers.containsString("IOException")));
    }

    @Test
    void aDisabledCaptureAnswersTwoHundredWithoutWriting() throws Exception {
        properties.getSnapshot().setEnabled(false);

        mockMvc.perform(post("/lab/thread/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.captured").value(false))
                .andExpect(jsonPath("$.data.error").value(
                        org.hamcrest.Matchers.containsString("disabled")));

        long written;
        try (var entries = java.nio.file.Files.list(root)) {
            written = entries.count();
        }
        assertThat(written).as("@TempDir exists; no bundle may be written into it").isZero();
    }

    private static final class FakeExecutor implements JvmCommandExecutor {
        private final java.util.List<java.util.List<String>> invocations = new java.util.ArrayList<>();
        private boolean fail;

        @Override
        public String execute(List<String> args, Duration timeout) throws IOException {
            invocations.add(List.copyOf(args));
            if (fail) {
                throw new IOException("jcmd: command not found");
            }
            return "canned\n";
        }
    }
}
