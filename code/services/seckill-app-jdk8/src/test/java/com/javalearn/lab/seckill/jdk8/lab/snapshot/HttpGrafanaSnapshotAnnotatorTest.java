package com.javalearn.lab.seckill.jdk8.lab.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.seckill.jdk8.lab.api.SnapshotView;
import com.javalearn.lab.seckill.jdk8.lab.config.SnapshotProperties;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The JDK 8 Grafana annotation contract, against a local
 * {@link HttpServer} (no Docker): same {@code POST /api/annotations} body as
 * the JDK 21 annotator, basic auth from the environment, the snapshot
 * {@code time}, and failure semantics where a rejection or an unreachable
 * Grafana only WARNs and is counted.
 */
class HttpGrafanaSnapshotAnnotatorTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final SnapshotMetrics metrics = new SnapshotMetrics(registry);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<String> bodies = Collections.synchronizedList(new ArrayList<String>());
    private final List<String> authorizations =
            Collections.synchronizedList(new ArrayList<String>());

    private HttpServer server;
    private volatile int responseStatus;

    @BeforeEach
    void startServer() throws IOException {
        responseStatus = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/annotations", exchange -> {
            bodies.add(new String(readFully(exchange.getRequestBody()), StandardCharsets.UTF_8));
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
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

        annotator.annotate(view("manual"));

        assertThat(bodies).hasSize(1);
        JsonNode body = objectMapper.readTree(bodies.get(0));
        List<String> tags = new ArrayList<String>();
        body.get("tags").forEach(tag -> tags.add(tag.asText()));
        assertThat(tags).containsExactly("snapshot", "thread-lab", "app-jdk8", "manual",
                "demo", "extra");
        assertThat(body.get("time").asLong()).isEqualTo(1_000_000L);
        assertThat(body.get("text").asText())
                .contains("instance=app-jdk8")
                .contains("trigger=manual")
                .contains("files=5")
                .contains("toolset=jstack+jmap+jcmd")
                .contains("dir=19700101-001640-000_app-jdk8");
        assertThat(authorizations.get(0)).isEqualTo("Basic "
                + Base64.getEncoder().encodeToString(
                        "admin:s3cret".getBytes(StandardCharsets.UTF_8)));
        assertThat(result("posted")).isEqualTo(1D);
        assertThat(result("failed")).isZero();
    }

    @Test
    void aRejectedAnnotationIsCountedAndSwallowed() {
        HttpGrafanaSnapshotAnnotator annotator = annotatorFor(url(), 500);

        assertThatCode(() -> annotator.annotate(view("manual"))).doesNotThrowAnyException();

        assertThat(bodies).hasSize(1);
        assertThat(result("failed")).isEqualTo(1D);
        assertThat(result("posted")).isZero();
    }

    @Test
    void anUnreachableGrafanaIsCountedAndSwallowed() {
        HttpGrafanaSnapshotAnnotator annotator = annotatorFor("http://127.0.0.1:1", 200);

        assertThatCode(() -> annotator.annotate(view("manual"))).doesNotThrowAnyException();

        assertThat(result("failed")).isEqualTo(1D);
    }

    @Test
    void anEmptyUrlIsOffAndNeverCallsGrafana() {
        HttpGrafanaSnapshotAnnotator annotator = annotatorFor("", 200);

        annotator.annotate(view("manual"));

        assertThat(bodies).isEmpty();
        assertThat(result("skipped")).isEqualTo(1D);
    }

    @Test
    void theAnnotationSwitchOffSkipsEvenWithAUrl() {
        SnapshotProperties.Annotation config = config(url());
        config.setEnabled(false);
        HttpGrafanaSnapshotAnnotator annotator =
                new HttpGrafanaSnapshotAnnotator(config, "app-jdk8", objectMapper, metrics);

        annotator.annotate(view("manual"));

        assertThat(bodies).isEmpty();
        assertThat(result("skipped")).isEqualTo(1D);
    }

    // ----------------------------------------------------------------- helpers

    private HttpGrafanaSnapshotAnnotator annotatorFor(String grafanaUrl, int status) {
        responseStatus = status;
        return new HttpGrafanaSnapshotAnnotator(config(grafanaUrl), "app-jdk8",
                objectMapper, metrics);
    }

    private SnapshotProperties.Annotation config(String grafanaUrl) {
        SnapshotProperties.Annotation config = new SnapshotProperties.Annotation();
        config.setEnabled(true);
        config.setUrl(grafanaUrl);
        config.setUser("admin");
        config.setPassword("s3cret");
        config.setTags("demo, extra");
        config.setConnectTimeoutMs(2_000L);
        config.setReadTimeoutMs(2_000L);
        return config;
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static SnapshotView view(String trigger) {
        List<String> files = new ArrayList<String>();
        files.add("thread-dump.txt");
        files.add("heap-info.txt");
        files.add("class-histogram.txt");
        files.add("metadata.json");
        files.add("analysis-template.md");
        return new SnapshotView(true, trigger,
                "doc/process/Stage1/snapshots/19700101-001640-000_app-jdk8",
                files, 12L, 1_000_000L, null);
    }

    private double result(String result) {
        return registry.get("thread_lab_snapshot_annotations_total")
                .tag("result", result).counter().count();
    }

    /** Java 8 has no InputStream.readAllBytes; the test must compile with release 8. */
    private static byte[] readFully(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[1024];
        int read;
        while ((read = input.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }
}
