package com.javalearn.lab.seckill.jdk8.lab.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.seckill.jdk8.lab.auth.LabTokenFilter;
import com.javalearn.lab.seckill.jdk8.lab.config.LabProperties;
import com.javalearn.lab.seckill.jdk8.lab.config.SnapshotProperties;
import com.javalearn.lab.seckill.jdk8.lab.snapshot.SnapshotMetrics;
import com.javalearn.lab.seckill.jdk8.lab.snapshot.ThreadSnapshotService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract of the JDK 8 snapshot endpoints: token-guarded (401 + 40101
 * without / with a wrong {@code X-Lab-Token}), 200 with {@code captured=true}
 * on success, and 200 with {@code captured=false} when the tool fails. Nginx
 * does not proxy {@code /lab/**}; this filter is the gate.
 */
class LabSnapshotEndpointTest {

    private static final String TOKEN = "test-token";

    @TempDir
    Path root;

    @TempDir
    Path templateDir;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private FakeExecutor executor;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws IOException {
        Path template = templateDir.resolve("snapshot-analysis-template.md");
        Files.write(template, "# template\n".getBytes(StandardCharsets.UTF_8));
        SnapshotProperties properties = new SnapshotProperties();
        properties.setEnabled(true);
        properties.setRootDir(root.toString());
        properties.setMaxCount(10L);
        properties.setMaxAgeSeconds(0L);
        properties.setCommandTimeoutMs(5_000L);
        properties.setJmapMode(SnapshotProperties.JMAP_MODE_HISTO);
        properties.setJstackPath("jstack");
        properties.setJmapPath("jmap");
        properties.setTemplatePath(template.toString());

        executor = new FakeExecutor();
        ThreadSnapshotService snapshots = new ThreadSnapshotService(properties, "app-jdk8",
                4_242L, Clock.systemUTC(), executor, objectMapper,
                new SnapshotMetrics(registry), view -> { });

        LabProperties lab = new LabProperties();
        lab.setToken(TOKEN);
        mockMvc = MockMvcBuilders.standaloneSetup(new LabSnapshotController(snapshots))
                .addFilters(new LabTokenFilter(lab, objectMapper))
                .build();
    }

    @Test
    void postWithoutTheTokenIsRejectedWith40101() throws Exception {
        mockMvc.perform(post("/lab/snapshot"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("40101"));
    }

    @Test
    void postWithAWrongTokenIsRejected() throws Exception {
        mockMvc.perform(post("/lab/snapshot").header(LabTokenFilter.TOKEN_HEADER, "not-the-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("40101"));

        assertThat(executor.invocations).isEmpty();
        assertThat(snapshotFolders()).isEmpty();
    }

    @Test
    void postWithTheTokenCapturesTheBundle() throws Exception {
        mockMvc.perform(post("/lab/snapshot").header(LabTokenFilter.TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.captured").value(true))
                .andExpect(jsonPath("$.data.trigger").value("manual"))
                .andExpect(jsonPath("$.data.files.length()").value(5))
                .andExpect(jsonPath("$.data.files[0]").value("thread-dump.txt"))
                .andExpect(jsonPath("$.data.directory")
                        .value(org.hamcrest.Matchers.endsWith("_app-jdk8")));

        assertThat(executor.invocations).hasSize(3);
    }

    @Test
    void getListsBundlesForTheConsole() throws Exception {
        mockMvc.perform(post("/lab/snapshot").header(LabTokenFilter.TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk());

        mockMvc.perform(get("/lab/snapshot").header(LabTokenFilter.TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].trigger").value("manual"))
                .andExpect(jsonPath("$.data[0].timestamp").isNotEmpty())
                .andExpect(jsonPath("$.data[0].totalBytes").isNumber())
                .andExpect(jsonPath("$.data[0].files.length()").value(5));
    }

    @Test
    void getWithoutTheTokenIsRejected() throws Exception {
        mockMvc.perform(get("/lab/snapshot"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("40101"));
    }

    @Test
    void aFailingToolStillAnswersTwoHundredWithTheError() throws Exception {
        executor.fail = true;

        mockMvc.perform(post("/lab/snapshot").header(LabTokenFilter.TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.captured").value(false))
                .andExpect(jsonPath("$.data.error")
                        .value(org.hamcrest.Matchers.containsString("IOException")));
    }

    private List<String> snapshotFolders() throws IOException {
        List<String> names = new ArrayList<String>();
        if (!Files.isDirectory(root)) {
            return names;
        }
        Files.list(root).forEach(path -> {
            if (Files.isDirectory(path)) {
                names.add(path.getFileName().toString());
            }
        });
        Collections.sort(names);
        return names;
    }

    private static final class FakeExecutor
            implements com.javalearn.lab.seckill.jdk8.lab.snapshot.JvmCommandExecutor {

        private final List<List<String>> invocations = new ArrayList<List<String>>();
        private boolean fail;

        @Override
        public String execute(List<String> command, Duration timeout) throws IOException {
            invocations.add(Collections.unmodifiableList(new ArrayList<String>(command)));
            if (fail) {
                throw new IOException("jstack: command not found");
            }
            return "canned " + String.join(" ", command) + "\n";
        }
    }
}
