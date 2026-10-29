package com.javalearn.lab.chaos.reconcile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * RabbitMQ management API probe ({@code GET /api/queues/{vhost}/{queue}}).
 *
 * <p>There is no existing RabbitMQ gateway in the controller, so this is a
 * deliberately small JDK-HttpClient call: Basic auth from
 * {@code lab.reconcile.mq-user/mq-password}, bounded connect/request timeouts,
 * and no retry logic here (the drain loop owns polling). Credentials are only
 * sent in the Authorization header and never logged.</p>
 */
public class HttpRabbitQueueGateway implements RabbitQueueGateway {

    private static final int MAX_SNIPPET = 200;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String baseUrl;
    private final String vhost;
    private final String queue;
    private final String authorization;
    private final Duration requestTimeout;

    public HttpRabbitQueueGateway(ReconcileProperties properties, ObjectMapper objectMapper) {
        this(properties.getMqManagementUrl(), properties.getMqVhost(), properties.getMqQueue(),
                properties.getMqUser(), properties.getMqPassword(),
                properties.getMqRequestTimeoutSeconds(), objectMapper);
    }

    public HttpRabbitQueueGateway(String managementUrl, String vhost, String queue, String user,
                                  String password, int requestTimeoutSeconds,
                                  ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.baseUrl = stripTrailingSlash(managementUrl);
        this.vhost = vhost;
        this.queue = queue;
        this.requestTimeout = Duration.ofSeconds(requestTimeoutSeconds);
        String credentials = user + ":" + password;
        this.authorization = "Basic " + Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(requestTimeoutSeconds, 2)))
                .build();
    }

    @Override
    public RabbitQueueState snapshot() {
        URI uri = URI.create(baseUrl + "/api/queues/" + encode(vhost) + "/" + encode(queue));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("Authorization", authorization)
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofByteArray());
            String body = response.body() == null ? ""
                    : new String(response.body(), StandardCharsets.UTF_8);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("rabbitmq management returned HTTP "
                        + response.statusCode() + ": " + snippet(body));
            }
            JsonNode root = objectMapper.readTree(body);
            long ready = requiredLong(root, "messages_ready", body);
            long unacknowledged = requiredLong(root, "messages_unacknowledged", body);
            long consumers = root.path("consumers").asLong(0);
            return new RabbitQueueState(ready, unacknowledged, consumers);
        } catch (HttpTimeoutException e) {
            throw new IllegalStateException("rabbitmq management timed out after "
                    + requestTimeout.toSeconds() + "s at " + baseUrl, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("rabbitmq management request was interrupted at "
                    + baseUrl, e);
        } catch (IOException e) {
            throw new IllegalStateException("rabbitmq management unreachable at " + baseUrl
                    + ": " + e, e);
        }
    }

    private static long requiredLong(JsonNode root, String field, String body) {
        JsonNode node = root.path(field);
        if (!node.isNumber()) {
            throw new IllegalStateException("rabbitmq management answer lacks numeric '"
                    + field + "': " + snippet(body));
        }
        return node.asLong();
    }

    private static String encode(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        String oneLine = body.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= MAX_SNIPPET ? oneLine : oneLine.substring(0, MAX_SNIPPET);
    }
}
