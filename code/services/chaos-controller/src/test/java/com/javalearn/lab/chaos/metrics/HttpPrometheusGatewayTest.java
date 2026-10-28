package com.javalearn.lab.chaos.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real local HTTP server (no mock of the HTTP stack): asserts the exact
 * {@code /api/v1/query_range} request (path, start, end, step and the PromQL
 * text) and the mapping of every failure shape to {@link PrometheusQueryException}.
 */
class HttpPrometheusGatewayTest {

    private static final String SUCCESS = "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\","
            + "\"result\":[{\"metric\":{\"instance\":\"app-1:8080\"},"
            + "\"values\":[[1793140800,\"1.5\"],[1793140815,\"2.5\"]]}]}}";

    private HttpServer server;
    private final AtomicReference<String> seenPath = new AtomicReference<>("(unset)");
    private final AtomicReference<String> seenQuery = new AtomicReference<>("(unset)");
    private final AtomicReference<Map<String, String>> seenParams = new AtomicReference<>(Map.of());

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    void sendsTheQueryRangePathAndAllParameters() throws Exception {
        startServer(exchange -> respond(exchange, 200, SUCCESS));
        PrometheusGateway gateway = gateway(1, 2);

        PrometheusMatrix matrix = gateway.queryRange(
                "sum by (instance) (rate(http_server_requests_seconds_count{job=\"seckill-app\"}[15s]))",
                1793140800L, 1793142000L, 15L);

        assertThat(seenPath.get()).isEqualTo("/api/v1/query_range");
        assertThat(seenParams.get())
                .containsEntry("start", "1793140800")
                .containsEntry("end", "1793142000")
                .containsEntry("step", "15")
                .containsEntry("query",
                        "sum by (instance) (rate(http_server_requests_seconds_count{job=\"seckill-app\"}[15s]))");
        assertThat(matrix.series()).hasSize(1);
        assertThat(matrix.series().get(0).labels()).containsEntry("instance", "app-1:8080");
        assertThat(matrix.series().get(0).points()).hasSize(2);
        assertThat(matrix.series().get(0).points().get(1).value()).isEqualTo(2.5);
    }

    @Test
    void nonFiniteSamplesAreDroppedInsteadOfBecomingZero() throws Exception {
        String body = "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\","
                + "\"result\":[{\"metric\":{\"instance\":\"app-1\"},\"values\":["
                + "[1,\"NaN\"],[2,\"+Inf\"],[3,\"-Inf\"],[4,\"7\"]]}]}}";
        startServer(exchange -> respond(exchange, 200, body));

        PrometheusMatrix matrix = gateway(1, 2).queryRange("x", 1, 2, 1);

        assertThat(matrix.series().get(0).points()).hasSize(1);
        assertThat(matrix.series().get(0).points().get(0).value()).isEqualTo(7.0);
    }

    @Test
    void anEmptyResultIsASuccessfulEmptyMatrix() throws Exception {
        startServer(exchange -> respond(exchange, 200,
                "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[]}}"));

        PrometheusMatrix matrix = gateway(1, 2).queryRange("x", 1, 2, 1);

        assertThat(matrix.series()).isEmpty();
    }

    @Test
    void httpErrorsBecomeQueryExceptions() throws Exception {
        startServer(exchange -> respond(exchange, 500,
                "{\"status\":\"error\",\"errorType\":\"internal\",\"error\":\"boom\"}"));

        assertThatThrownBy(() -> gateway(1, 2).queryRange("x", 1, 2, 1))
                .isInstanceOf(PrometheusQueryException.class)
                .hasMessageContaining("HTTP 500")
                .hasMessageContaining("boom");
    }

    @Test
    void errorEnvelopesBecomeQueryExceptions() throws Exception {
        startServer(exchange -> respond(exchange, 200,
                "{\"status\":\"error\",\"errorType\":\"bad_data\",\"error\":\"parse error\"}"));

        assertThatThrownBy(() -> gateway(1, 2).queryRange("x", 1, 2, 1))
                .isInstanceOf(PrometheusQueryException.class)
                .hasMessageContaining("bad_data")
                .hasMessageContaining("parse error");
    }

    @Test
    void malformedJsonAndWrongResultTypesAreRefused() throws Exception {
        startServer(exchange -> respond(exchange, 200, "{not json"));
        assertThatThrownBy(() -> gateway(1, 2).queryRange("x", 1, 2, 1))
                .isInstanceOf(PrometheusQueryException.class)
                .hasMessageContaining("malformed JSON");

        server.stop(0);
        startServer(exchange -> respond(exchange, 200,
                "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}"));
        assertThatThrownBy(() -> gateway(1, 2).queryRange("x", 1, 2, 1))
                .isInstanceOf(PrometheusQueryException.class)
                .hasMessageContaining("resultType 'vector'");
    }

    @Test
    void connectionRefusedIsReportedAsUnreachable() throws Exception {
        startServer(exchange -> respond(exchange, 200, SUCCESS));
        int port = server.getAddress().getPort();
        server.stop(0);
        server = null;

        assertThatThrownBy(() -> gateway(port, 1, 2).queryRange("x", 1, 2, 1))
                .isInstanceOf(PrometheusQueryException.class)
                .hasMessageContaining("unreachable")
                .hasMessageContaining("127.0.0.1");
    }

    @Test
    void aSlowPrometheusHitsTheExternalizedRequestTimeout() throws Exception {
        startServer(exchange -> {
            try {
                Thread.sleep(2500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, SUCCESS);
        });

        assertThatThrownBy(() -> gateway(1, 1).queryRange("x", 1, 2, 1))
                .isInstanceOf(PrometheusQueryException.class)
                .hasMessageContaining("timed out")
                .hasMessageContaining("1s");
    }

    @Test
    void sendsTheInstantQueryPathAndParsesTheVector() throws Exception {
        startServer(exchange -> respond(exchange, 200,
                "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":["
                        + "{\"metric\":{\"job\":\"seckill-app\"},\"value\":[1793140800,\"3\"]}]}}"));

        PrometheusMatrix matrix = gateway(1, 2).queryInstant("sum(up{job=\"seckill-app\"})", 1793140800L);

        assertThat(seenPath.get()).isEqualTo("/api/v1/query");
        assertThat(seenParams.get())
                .containsEntry("time", "1793140800")
                .containsEntry("query", "sum(up{job=\"seckill-app\"})");
        assertThat(matrix.series()).hasSize(1);
        assertThat(matrix.series().get(0).labels()).containsEntry("job", "seckill-app");
        assertThat(matrix.series().get(0).points()).hasSize(1);
        assertThat(matrix.series().get(0).points().get(0).value()).isEqualTo(3.0);
    }

    @Test
    void anInstantQueryWithoutSeriesIsAnEmptyMatrixNotAnError() throws Exception {
        startServer(exchange -> respond(exchange, 200,
                "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}"));

        assertThat(gateway(1, 2).queryInstant("x", 1L).series()).isEmpty();
    }

    @Test
    void anInstantQueryWithTheWrongResultTypeIsRefused() throws Exception {
        startServer(exchange -> respond(exchange, 200,
                "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[]}}"));

        assertThatThrownBy(() -> gateway(1, 2).queryInstant("x", 1L))
                .isInstanceOf(PrometheusQueryException.class)
                .hasMessageContaining("expected 'vector'");
    }

    @Test
    void thePrecheckTimeoutConstructorUsesItsOwnRequestBudget() throws Exception {
        startServer(exchange -> {
            try {
                Thread.sleep(2500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, SUCCESS);
        });
        HttpPrometheusGateway gateway = new HttpPrometheusGateway(
                "http://127.0.0.1:" + server.getAddress().getPort(), 1, 1, new ObjectMapper());

        assertThatThrownBy(() -> gateway.queryInstant("x", 1L))
                .isInstanceOf(PrometheusQueryException.class)
                .hasMessageContaining("timed out")
                .hasMessageContaining("1s");
    }

    private PrometheusGateway gateway(int connectSeconds, int requestSeconds) throws IOException {
        return gateway(server.getAddress().getPort(), connectSeconds, requestSeconds);
    }

    private PrometheusGateway gateway(int port, int connectSeconds, int requestSeconds) {
        MetricsProperties properties = new MetricsProperties();
        properties.setPrometheusUrl("http://127.0.0.1:" + port + "/");
        properties.setConnectTimeoutSeconds(connectSeconds);
        properties.setRequestTimeoutSeconds(requestSeconds);
        return new HttpPrometheusGateway(properties, new ObjectMapper());
    }

    private void startServer(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seenPath.set(exchange.getRequestURI().getPath());
            String raw = exchange.getRequestURI().getRawQuery();
            seenQuery.set(raw);
            Map<String, String> params = new HashMap<>();
            if (raw != null) {
                for (String pair : raw.split("&")) {
                    int index = pair.indexOf('=');
                    if (index > 0) {
                        params.put(pair.substring(0, index),
                                URLDecoder.decode(pair.substring(index + 1), StandardCharsets.UTF_8));
                    }
                }
            }
            seenParams.set(params);
            handler.handle(exchange);
        });
        server.start();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
