package com.javalearn.lab.chaos.appproxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.config.ChaosProperties;
import com.javalearn.lab.chaos.error.AppProxyResponseTooLargeException;
import com.javalearn.lab.chaos.error.AppResponseNotJsonException;
import com.javalearn.lab.chaos.error.AppUnreachableException;
import com.javalearn.lab.chaos.error.InvalidAppProxyRequestException;
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
    private final AtomicReference<String> seenRawQuery = new AtomicReference<>("(unset)");
    private final AtomicReference<String> seenPath = new AtomicReference<>("(unset)");

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
                .exchange("127.0.0.1", "GET", "/lab/thread/state", null, null);

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
                .exchange("127.0.0.1", "POST", "/lab/thread/fault", null, body);

        assertThat(response.status()).isEqualTo(200);
        assertThat(seenContentType.get()).startsWith("application/json");
        assertThat(seenBody.get()).isEqualTo(new String(body, StandardCharsets.UTF_8));
    }

    @Test
    void relaysUpstreamErrorEnvelopesWithoutRewritingTheStatus() throws Exception {
        String conflict = "{\"code\":\"40901\",\"message\":\"A fault is already active\",\"data\":null}";
        startServer(exchange -> respond(exchange, 409, "application/json", conflict));

        ProxyResponse response = gateway(server.getAddress().getPort())
                .exchange("127.0.0.1", "POST", "/lab/thread/fault", null, null);

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.body()).isEqualTo(conflict);
    }

    @Test
    void htmlAnswersBecomeAClearBadGatewayCondition() throws Exception {
        startServer(exchange -> respond(exchange, 200, "text/html;charset=UTF-8",
                "<html><body><h1>Not the app</h1></body></html>"));

        assertThatThrownBy(() -> gateway(server.getAddress().getPort())
                .exchange("127.0.0.1", "GET", "/lab/thread/state", null, null))
                .isInstanceOf(AppResponseNotJsonException.class)
                .hasMessageContaining("non-JSON")
                .hasMessageContaining("text/html")
                .hasMessageContaining("Not the app");
    }

    @Test
    void emptyAnswersAreRejectedAsNonJson() throws Exception {
        startServer(exchange -> respond(exchange, 200, "application/json", ""));

        assertThatThrownBy(() -> gateway(server.getAddress().getPort())
                .exchange("127.0.0.1", "GET", "/lab/thread/state", null, null))
                .isInstanceOf(AppResponseNotJsonException.class)
                .hasMessageContaining("empty body");
    }

    @Test
    void connectionRefusedIsReportedAsUnreachable() throws Exception {
        startServer(exchange -> respond(exchange, 200, "application/json", STATE_JSON));
        int port = server.getAddress().getPort();
        server.stop(0);
        server = null;

        assertThatThrownBy(() -> gateway(port).exchange("127.0.0.1", "GET", "/lab/thread/state", null, null))
                .isInstanceOf(AppUnreachableException.class)
                .hasMessageContaining("unreachable")
                .hasMessageContaining("127.0.0.1")
                .satisfies(error -> assertThat(error.getMessage()).doesNotContain(TOKEN));
    }

    // ------------------------------------------------------------ query string

    @Test
    void theRawQueryStringReachesTheUpstreamVerbatim() throws Exception {
        startServer(exchange -> respond(exchange, 200, "application/json", STATE_JSON));

        gateway(server.getAddress().getPort()).exchange("127.0.0.1", "GET", "/lab/thread/state",
                "cursor=v2:8&limit=50&like=a%20b&f&plus=1+2&eq=x==", null);

        assertThat(seenPath.get()).isEqualTo("/lab/thread/state");
        assertThat(seenRawQuery.get())
                .isEqualTo("cursor=v2:8&limit=50&like=a%20b&f&plus=1+2&eq=x==");
    }

    @Test
    void absentAndEmptyQueriesSendNoQuestionMark() throws Exception {
        startServer(exchange -> respond(exchange, 200, "application/json", STATE_JSON));
        HttpAppProxyGateway gateway = gateway(server.getAddress().getPort());

        gateway.exchange("127.0.0.1", "GET", "/lab/thread/state", null, null);
        assertThat(seenRawQuery.get()).isNull();

        gateway.exchange("127.0.0.1", "GET", "/lab/thread/state", "", null);
        assertThat(seenRawQuery.get()).isNull();
    }

    @Test
    void charactersTheUriParserRejectsAreEscapedWithoutTouchingTheRest() throws Exception {
        startServer(exchange -> respond(exchange, 200, "application/json", STATE_JSON));

        // '|' is legal on the wire but illegal in a URI query (RFC 3986); the
        // gateway escapes exactly that byte and decodes back to the same value
        gateway(server.getAddress().getPort())
                .exchange("127.0.0.1", "GET", "/lab/cache/state", "pattern=a|b&k=v", null);

        assertThat(seenRawQuery.get()).isEqualTo("pattern=a%7Cb&k=v");
    }

    @Test
    void aMalformedPercentEscapeIsRefusedAsAnInvalidRequest() throws Exception {
        startServer(exchange -> respond(exchange, 200, "application/json", STATE_JSON));

        // "%z" is not a percent-escape; forwarding it verbatim would lie about
        // the caller's bytes, so the proxy refuses instead (400, never upstream)
        assertThatThrownBy(() -> gateway(server.getAddress().getPort())
                .exchange("127.0.0.1", "GET", "/lab/thread/state", "a=%zz", null))
                .isInstanceOf(InvalidAppProxyRequestException.class)
                .hasMessageContaining("Query string");
        assertThat(seenPath.get()).isEqualTo("(unset)");
    }

    // ------------------------------------------------------------- size limits

    @Test
    void oversizedUpstreamAnswersAreRefusedInsteadOfRelayed() throws Exception {
        String fat = "{\"code\":\"0\",\"message\":\"OK\",\"data\":\"" + "x".repeat(4096) + "\"}";
        startServer(exchange -> respond(exchange, 200, "application/json", fat));

        ChaosProperties.AppProxy config = new ChaosProperties.AppProxy();
        config.setPort(server.getAddress().getPort());
        config.setMaxResponseBytes(512);
        assertThatThrownBy(() -> new HttpAppProxyGateway(config, TOKEN, new ObjectMapper())
                .exchange("127.0.0.1", "GET", "/lab/cache/state", null, null))
                .isInstanceOf(AppProxyResponseTooLargeException.class)
                .hasMessageContaining("at most 512")
                .hasMessageContaining("/lab/cache/state");
    }

    @Test
    void answersWithinTheResponseBudgetStillRelay() throws Exception {
        startServer(exchange -> respond(exchange, 200, "application/json", STATE_JSON));

        ChaosProperties.AppProxy config = new ChaosProperties.AppProxy();
        config.setPort(server.getAddress().getPort());
        config.setMaxResponseBytes(512);
        ProxyResponse response = new HttpAppProxyGateway(config, TOKEN, new ObjectMapper())
                .exchange("127.0.0.1", "GET", "/lab/cache/state", null, null);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(STATE_JSON);
    }

    @Test
    void aSlowUpstreamHitsTheExternalizedRequestTimeout() throws Exception {
        startServer(exchange -> {
            try {
                Thread.sleep(2500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "application/json", STATE_JSON);
        });

        ChaosProperties.AppProxy config = new ChaosProperties.AppProxy();
        config.setPort(server.getAddress().getPort());
        config.setConnectTimeoutSeconds(1);
        config.setRequestTimeoutSeconds(1);
        assertThatThrownBy(() -> new HttpAppProxyGateway(config, TOKEN, new ObjectMapper())
                .exchange("127.0.0.1", "GET", "/lab/thread/state", null, null))
                .isInstanceOf(AppUnreachableException.class)
                .hasMessageContaining("unreachable");
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
            seenRawQuery.set(exchange.getRequestURI().getRawQuery());
            seenPath.set(exchange.getRequestURI().getPath());
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
