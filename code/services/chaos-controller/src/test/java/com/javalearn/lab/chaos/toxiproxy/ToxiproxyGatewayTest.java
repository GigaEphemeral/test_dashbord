package com.javalearn.lab.chaos.toxiproxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.config.ChaosProperties;
import com.javalearn.lab.chaos.error.InvalidToxiproxyRequestException;
import com.javalearn.lab.chaos.error.ToxiproxyConflictException;
import com.javalearn.lab.chaos.error.ToxiproxyNotFoundException;
import com.javalearn.lab.chaos.error.ToxiproxyUnavailableException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the real HTTP client against an in-process loopback server: no
 * Docker, no external network, but the request paths, JSON shape and status
 * mapping of the Toxiproxy v2 API are actually spoken.
 */
class ToxiproxyGatewayTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();

    private HttpServer server;
    private volatile Responder responder;
    private HttpToxiproxyGateway gateway;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), body));
            responder.respond(exchange);
        });
        server.start();

        ChaosProperties properties = new ChaosProperties();
        properties.getToxiproxy().setUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getToxiproxy().setConnectTimeoutSeconds(1);
        properties.getToxiproxy().setRequestTimeoutSeconds(2);
        gateway = new HttpToxiproxyGateway(properties.getToxiproxy(), objectMapper);

        json(404, "{\"error\":\"not found\"}");
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void addLatencyToxicPostsToxiproxyShapeAndParsesView() throws Exception {
        json(200, "{\"name\":\"latency\",\"type\":\"latency\",\"stream\":\"downstream\","
                + "\"toxicity\":1.0,\"attributes\":{\"latency\":500,\"jitter\":50}}");

        ToxicView view = gateway.addToxic("redis", new ToxicSpec.Latency("latency", 500, 50));

        assertThat(requests).hasSize(1);
        Recorded request = requests.get(0);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/proxies/redis/toxics");
        JsonNode body = objectMapper.readTree(request.body());
        assertThat(body.get("name").asText()).isEqualTo("latency");
        assertThat(body.get("type").asText()).isEqualTo("latency");
        assertThat(body.get("attributes").get("latency").asInt()).isEqualTo(500);
        assertThat(body.get("attributes").get("jitter").asInt()).isEqualTo(50);
        assertThat(body.get("toxicity").asDouble()).isEqualTo(1.0);
        assertThat(body.has("stream")).isFalse();

        assertThat(view.name()).isEqualTo("latency");
        assertThat(view.type()).isEqualTo("latency");
        assertThat(view.attributes()).containsEntry("latency", 500).containsEntry("jitter", 50);
        assertThat(view.toxicity()).isEqualTo(1.0);
    }

    @Test
    void addBandwidthAndTimeoutToxicsUseTheirOwnAttributes() throws Exception {
        json(200, "{\"name\":\"bandwidth\",\"type\":\"bandwidth\",\"toxicity\":1.0,"
                + "\"attributes\":{\"rate\":64}}");
        gateway.addToxic("mysql", new ToxicSpec.Bandwidth("bandwidth", 64));
        JsonNode bandwidth = objectMapper.readTree(requests.get(0).body());
        assertThat(bandwidth.get("attributes").get("rate").asInt()).isEqualTo(64);
        assertThat(bandwidth.get("attributes").has("latency")).isFalse();

        json(200, "{\"name\":\"timeout\",\"type\":\"timeout\",\"toxicity\":1.0,"
                + "\"attributes\":{\"timeout\":250}}");
        gateway.addToxic("mysql", new ToxicSpec.Timeout("timeout", 250));
        JsonNode timeout = objectMapper.readTree(requests.get(1).body());
        assertThat(timeout.get("type").asText()).isEqualTo("timeout");
        assertThat(timeout.get("attributes").get("timeout").asInt()).isEqualTo(250);
    }

    @Test
    void removeToxicUsesDeleteAndMaps404ToNotFound() {
        json(404, "{\"error\":\"toxic not found\"}");

        assertThatThrownBy(() -> gateway.removeToxic("redis", "latency"))
                .isInstanceOf(ToxiproxyNotFoundException.class)
                .hasMessageContaining("latency");

        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).method()).isEqualTo("DELETE");
        assertThat(requests.get(0).path()).isEqualTo("/proxies/redis/toxics/latency");
    }

    @Test
    void setProxyEnabledPostsTheEnabledFlag() throws Exception {
        json(200, "{\"name\":\"mysql\",\"listen\":\"0.0.0.0:36379\","
                + "\"upstream\":\"mysql:3306\",\"enabled\":false,\"toxics\":[]}");

        gateway.setProxyEnabled("mysql", false);

        Recorded request = requests.get(0);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/proxies/mysql");
        assertThat(objectMapper.readTree(request.body()).get("enabled").asBoolean()).isFalse();
    }

    @Test
    void getProxyParsesEnabledStateAndToxics() {
        json(200, "{\"name\":\"redis\",\"listen\":\"0.0.0.0:26379\","
                + "\"upstream\":\"redis:6379\",\"enabled\":true,\"toxics\":["
                + "{\"name\":\"latency\",\"type\":\"latency\",\"toxicity\":1.0,"
                + "\"attributes\":{\"latency\":500,\"jitter\":50}}]}");

        ProxyView view = gateway.getProxy("redis");

        assertThat(requests.get(0).method()).isEqualTo("GET");
        assertThat(requests.get(0).path()).isEqualTo("/proxies/redis");
        assertThat(view.enabled()).isTrue();
        assertThat(view.toxics()).hasSize(1);
        assertThat(view.toxics().get(0).name()).isEqualTo("latency");
    }

    @Test
    void resetProxyListsThenDeletesEveryToxic() {
        responder = exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                write(exchange, 200, "[{\"name\":\"latency\",\"type\":\"latency\",\"toxicity\":1.0,"
                        + "\"attributes\":{\"latency\":500,\"jitter\":50}},"
                        + "{\"name\":\"timeout\",\"type\":\"timeout\",\"toxicity\":1.0,"
                        + "\"attributes\":{\"timeout\":250}}]");
            } else {
                noContent(exchange);
            }
        };

        int removed = gateway.resetProxy("redis");

        assertThat(removed).isEqualTo(2);
        assertThat(requests).extracting(Recorded::path).containsExactly(
                "/proxies/redis/toxics",
                "/proxies/redis/toxics/latency",
                "/proxies/redis/toxics/timeout");
        assertThat(requests).extracting(Recorded::method).containsExactly("GET", "DELETE", "DELETE");
    }

    @Test
    void resetProxyToleratesToxicsRemovedConcurrently() {
        responder = exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                write(exchange, 200, "[{\"name\":\"latency\",\"type\":\"latency\",\"toxicity\":1.0,"
                        + "\"attributes\":{\"latency\":500,\"jitter\":50}}]");
            } else {
                write(exchange, 404, "{\"error\":\"gone\"}");
            }
        };

        assertThat(gateway.resetProxy("redis")).isZero();
    }

    @Test
    void serverErrorBecomesUnavailable() {
        json(500, "{\"error\":\"boom\"}");

        assertThatThrownBy(() -> gateway.setProxyEnabled("redis", true))
                .isInstanceOf(ToxiproxyUnavailableException.class)
                .hasMessageContaining("HTTP 500");
    }

    @Test
    void badRequestBecomesInvalidArgument() {
        json(400, "{\"error\":\"invalid attributes\"}");

        assertThatThrownBy(() -> gateway.addToxic("redis", new ToxicSpec.Bandwidth("bandwidth", 64)))
                .isInstanceOf(InvalidToxiproxyRequestException.class)
                .hasMessageContaining("invalid attributes");
    }

    @Test
    void conflictBecomesConflict() {
        json(409, "{\"error\":\"toxic already exists\"}");

        assertThatThrownBy(() -> gateway.addToxic("redis", new ToxicSpec.Latency("latency", 500, 50)))
                .isInstanceOf(ToxiproxyConflictException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void missingProxyOnListBecomesNotFound() {
        json(404, "{\"error\":\"proxy not found\"}");

        assertThatThrownBy(() -> gateway.listToxics("mysql"))
                .isInstanceOf(ToxiproxyNotFoundException.class)
                .hasMessageContaining("mysql");
    }

    private void json(int status, String body) {
        responder = exchange -> write(exchange, status, body);
    }

    private static void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void noContent(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }

    private record Recorded(String method, String path, String body) {
    }

    @FunctionalInterface
    private interface Responder {
        void respond(HttpExchange exchange) throws IOException;
    }
}
