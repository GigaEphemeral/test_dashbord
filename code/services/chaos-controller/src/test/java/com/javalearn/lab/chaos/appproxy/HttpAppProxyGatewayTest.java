package com.javalearn.lab.chaos.appproxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.config.ChaosProperties;
import com.javalearn.lab.chaos.error.AppResponseNotJsonException;
import com.javalearn.lab.chaos.error.AppUnreachableException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpAppProxyGatewayTest {

    private static final String TOKEN = "unit-test-token";
    private static final String STATE_JSON = "{\"code\":\"0\",\"message\":\"OK\",\"data\":[]}";

    private HttpServer server;
    private final AtomicReference<String> seenToken = new AtomicReference<>();
    private final AtomicReference<String> seenAccept = new AtomicReference<>();
    private final AtomicReference<String> seenContentType = new AtomicReference<>();
    private final AtomicReference<String> seenBody = new AtomicReference<>();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    void forwardsTheControllerTokenAndRelaysTheJsonEnvelope() throws Exception {
        startServer(exchange -> respond(exchange, 200, "application/json", STATE_JSON));

        ProxyResponse response = gateway(server.getAddress().getPort())
                .exchange("127.0.0.1", "GET", "/lab/thread/state", null);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(STATE_JSON);
        assertThat(seenToken.get()).isEqualTo(TOKEN);
        assertThat(seenAccept.get()).contains("application/json");
    }

    @Test
    void forwardsTheFaultBodyAsJson() throws Exception {
        startServer(exchange -> respond(exchange, 200, "application/json", STATE_JSON));
        byte[] body = "{\"mode\":\"cpu-burn\",\"ttlSeconds\":5}".getBytes(StandardCharsets.UTF_8);

        ProxyResponse response = gateway(server.getAddress().getPort())
                .exchange("127.0.0.1", "POST", "/lab/thread/fault", body);

        assertThat(response.status()).isEqualTo(200);
        assertThat(seenContentType.get()).startsWith("application/json");
        assertThat(seenBody.get()).isEqualTo(new String(body, StandardCharsets.UTF_8));
    }

    @Test
    void relaysUpstreamErrorEnvelopesWithoutRewritingTheStatus() throws Exception {
        String conflict = "{\"code\":\"40901\",\"message\":\"A fault is already active\",\"data\":null}";
        startServer(exchange -> respond(exchange, 409, "application/json", conflict));

        ProxyResponse response = gateway(server.getAddress().getPort())
                .exchange("127.0.0.1", "POST", "/lab/thread/fault", null);

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.body()).isEqualTo(conflict);
    }

    @Test
    void htmlAnswersBecomeAClearBadGatewayCondition() throws Exception {
        startServer(exchange -> respond(exchange, 200, "text/html;charset=UTF-8",
                "<html><body><h1>Not the app</h1></body></html>"));

        assertThatThrownBy(() -> gateway(server.getAddress().getPort())
                .exchange("127.0.0.1", "GET", "/lab/thread/state", null))
                .isInstanceOf(AppResponseNotJsonException.class)
                .hasMessageContaining("non-JSON")
                .hasMessageContaining("text/html")
                .hasMessageContaining("Not the app");
    }

    @Test
    void emptyAnswersAreRejectedAsNonJson() throws Exception {
        startServer(exchange -> respond(exchange, 200, "application/json", ""));

        assertThatThrownBy(() -> gateway(server.getAddress().getPort())
                .exchange("127.0.0.1", "GET", "/lab/thread/state", null))
                .isInstanceOf(AppResponseNotJsonException.class)
                .hasMessageContaining("empty body");
    }

    @Test
    void connectionRefusedIsReportedAsUnreachable() throws Exception {
        startServer(exchange -> respond(exchange, 200, "application/json", STATE_JSON));
        int port = server.getAddress().getPort();
        server.stop(0);
        server = null;

        assertThatThrownBy(() -> gateway(port).exchange("127.0.0.1", "GET", "/lab/thread/state", null))
                .isInstanceOf(AppUnreachableException.class)
                .hasMessageContaining("unreachable")
                .hasMessageContaining("127.0.0.1")
                .satisfies(error -> assertThat(error.getMessage()).doesNotContain(TOKEN));
    }

    private HttpAppProxyGateway gateway(int port) {
        ChaosProperties.AppProxy config = new ChaosProperties.AppProxy();
        config.setPort(port);
        config.setConnectTimeoutSeconds(1);
        config.setRequestTimeoutSeconds(2);
        return new HttpAppProxyGateway(config, TOKEN, new ObjectMapper());
    }

    private void startServer(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seenToken.set(exchange.getRequestHeaders().getFirst("X-Lab-Token"));
            seenAccept.set(exchange.getRequestHeaders().getFirst("Accept"));
            seenContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            seenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            handler.handle(exchange);
        });
        server.start();
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
