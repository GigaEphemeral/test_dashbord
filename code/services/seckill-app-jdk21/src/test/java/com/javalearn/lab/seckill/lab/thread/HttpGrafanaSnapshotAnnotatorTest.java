package com.javalearn.lab.seckill.lab.thread;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.seckill.lab.api.ThreadSnapshotView;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The Grafana annotation contract of design §9.3, against a JDK
 * {@link HttpServer} (no Docker, no extra test dependency): same
 * {@code POST /api/annotations} body as the chaos-controller, plus the snapshot
 * {@code time}; a rejection or an unreachable Grafana only WARNs and is counted,
 * and an unset URL is an OFF state, not an error.
 */
class HttpGrafanaSnapshotAnnotatorTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ThreadSnapshotMetrics metrics = new ThreadSnapshotMetrics(registry);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<String> bodies = new ArrayList<>();
    private final List<String> authorizations = new ArrayList<>();

    private HttpServer server;
    private volatile int responseStatus;

    @BeforeEach
    void startServer() throws IOException {
        responseStatus = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/annotations", exchange -> {
            synchronized (bodies) {
                bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            }
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void postsTagsTextTimeAndBasicAuth() throws Exception {
        HttpGrafanaSnapshotAnnotator annotator = annotatorFor(url(), 200);

        annotator.annotate(view("manual"), snapshot("RUNNABLE", 1, 2));

        assertThat(bodies).hasSize(1);
        JsonNode body = objectMapper.readTree(bodies.get(0));
        List<String> tags = new ArrayList<>();
        body.get("tags").forEach(tag -> tags.add(tag.asText()));
        assertThat(tags).containsExactly("snapshot", "thread-lab", "app-1", "manual", "demo", "extra");
        assertThat(body.get("time").asLong()).isEqualTo(1_000_000L);
        assertThat(body.get("text").asText())
                .contains("instance=app-1")
                .contains("trigger=manual")
                .contains("state=RUNNABLE(1)")
                .contains("step=2")
                .contains("dir=19700101-001640-000_app-1");
        assertThat(authorizations.get(0)).isEqualTo("Basic "
                + Base64.getEncoder().encodeToString(
                        "admin:s3cret".getBytes(StandardCharsets.UTF_8)));
        assertThat(posted()).isEqualTo(1D);
        assertThat(failed()).isZero();
    }

    @Test
    void aRejectedAnnotationIsCountedAndSwallowed() {
        HttpGrafanaSnapshotAnnotator annotator = annotatorFor(url(), 500);

        assertThatCode(() -> annotator.annotate(view("freeze"), snapshot("BLOCKED", 2, 1)))
                .doesNotThrowAnyException();

        assertThat(bodies).hasSize(1);
        assertThat(failed()).isEqualTo(1D);
        assertThat(posted()).isZero();
    }

    @Test
    void anUnreachableGrafanaIsCountedAndSwallowed() {
        HttpGrafanaSnapshotAnnotator annotator = annotatorFor("http://127.0.0.1:1", 200);

        assertThatCode(() -> annotator.annotate(view("freeze"), snapshot("WAITING", 3, 0)))
                .doesNotThrowAnyException();

        assertThat(failed()).isEqualTo(1D);
    }

    @Test
    void anEmptyUrlIsOffAndNeverCallsGrafana() {
        HttpGrafanaSnapshotAnnotator annotator = annotatorFor("", 200);

        annotator.annotate(view("freeze"), snapshot("TIMED_WAITING", 4, 3));

        assertThat(bodies).isEmpty();
        assertThat(skipped()).isEqualTo(1D);
    }

    @Test
    void theAnnotationSwitchOffSkipsEvenWithAUrl() {
        LabThreadProperties.Snapshot.Annotation config = config(url());
        config.setEnabled(false);
        HttpGrafanaSnapshotAnnotator annotator =
                new HttpGrafanaSnapshotAnnotator(config, "app-1", objectMapper, metrics);

        annotator.annotate(view("freeze"), snapshot("NEW", 0, 0));

        assertThat(bodies).isEmpty();
        assertThat(skipped()).isEqualTo(1D);
    }

    // ----------------------------------------------------------------- helpers

    private HttpGrafanaSnapshotAnnotator annotatorFor(String grafanaUrl, int status) {
        responseStatus = status;
        return new HttpGrafanaSnapshotAnnotator(config(grafanaUrl), "app-1", objectMapper, metrics);
    }

    private LabThreadProperties.Snapshot.Annotation config(String grafanaUrl) {
        LabThreadProperties.Snapshot.Annotation config =
                TestLabThreadProperties.defaults().getSnapshot().getAnnotation();
        config.setGrafanaUrl(grafanaUrl);
        config.setUser("admin");
        config.setPassword("s3cret");
        config.setTags("demo, extra");
        config.setTimeoutSeconds(2L);
        return config;
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static ThreadSnapshotView view(String trigger) {
        return new ThreadSnapshotView(true, trigger,
                "doc/process/snapshots/19700101-001640-000_app-1",
                List.of("thread-dump.txt", "heap-info.txt", "class-histogram.txt",
                        "metadata.json", "analysis-template.md"),
                12L, 1_000_000L, null);
    }

    private static LifecycleSnapshot snapshot(String state, int stateCode, int step) {
        return new LifecycleSnapshot(42L, "thread-lab-demo", state, stateCode, step,
                1_000_000L, "manual");
    }

    private double posted() {
        return registry.get("thread_lab_snapshot_annotations_total")
                .tag("result", "posted").counter().count();
    }

    private double failed() {
        return registry.get("thread_lab_snapshot_annotations_total")
                .tag("result", "failed").counter().count();
    }

    private double skipped() {
        return registry.get("thread_lab_snapshot_annotations_total")
                .tag("result", "skipped").counter().count();
    }
}
