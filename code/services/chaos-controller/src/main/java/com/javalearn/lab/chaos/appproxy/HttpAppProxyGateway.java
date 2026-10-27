package com.javalearn.lab.chaos.appproxy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.auth.TokenFilter;
import com.javalearn.lab.chaos.config.ChaosProperties;
import com.javalearn.lab.chaos.error.AppResponseNotJsonException;
import com.javalearn.lab.chaos.error.AppUnreachableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Forwards allow-listed thread-lab calls to one app instance over the JDK
 * {@link HttpClient} (same HTTP stack as the toxiproxy and Grafana clients, so
 * no extra dependency).
 *
 * <p>The shared lab token is injected here and attached as {@code X-Lab-Token};
 * it is never echoed, logged or included in an error message. The upstream
 * answer must be JSON: anything else (an HTML error page, an empty body, a
 * truncated reply) becomes a 502-style {@link AppResponseNotJsonException}
 * instead of being relayed as if it were a {@code Result} envelope.</p>
 */
public class HttpAppProxyGateway implements AppProxyGateway {

    private static final Logger log = LoggerFactory.getLogger(HttpAppProxyGateway.class);
    private static final int MAX_SNIPPET = 120;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String scheme;
    private final int port;
    private final Duration requestTimeout;
    private final String labToken;

    public HttpAppProxyGateway(ChaosProperties.AppProxy config, String labToken, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.scheme = config.getScheme().trim().toLowerCase();
        this.port = config.getPort();
        this.requestTimeout = Duration.ofSeconds(config.getRequestTimeoutSeconds());
        this.labToken = labToken;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.getConnectTimeoutSeconds()))
                .build();
    }

    @Override
    public ProxyResponse exchange(String instance, String method, String path, byte[] body) {
        URI uri = URI.create(scheme + "://" + instance + ":" + port + path);
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(requestTimeout)
                    .header(TokenFilter.TOKEN_HEADER, labToken)
                    .header("Accept", "application/json");
            if (body == null || body.length == 0) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", "application/json");
                builder.method(method, HttpRequest.BodyPublishers.ofByteArray(body));
            }
            HttpResponse<byte[]> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            String responseBody = response.body() == null
                    ? ""
                    : new String(response.body(), StandardCharsets.UTF_8);
            int status = response.statusCode();
            if (status == 204 || status == 304) {
                return new ProxyResponse(status, responseBody);
            }
            requireJson(instance, uri, status, response, responseBody);
            return new ProxyResponse(status, responseBody);
        } catch (AppResponseNotJsonException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppUnreachableException(
                    instance + " request was interrupted: " + method + " " + path, e);
        } catch (IOException | RuntimeException e) {
            log.warn("App proxy call to {} failed: {} {}", instance, method, path);
            throw new AppUnreachableException(
                    instance + " is unreachable at " + scheme + "://" + instance + ":" + port
                            + " (" + method + " " + path + "): " + e, e);
        }
    }

    private void requireJson(String instance, URI uri, int status,
                             HttpResponse<byte[]> response, String body) {
        String contentType = response.headers().firstValue("Content-Type").orElse("(none)");
        if (body == null || body.isBlank()) {
            throw new AppResponseNotJsonException(instance + " returned an empty body (HTTP " + status
                    + ", content-type " + contentType + ") for " + uri.getPath());
        }
        try {
            objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new AppResponseNotJsonException(instance + " returned a non-JSON response (HTTP " + status
                    + ", content-type " + contentType + "): " + snippet(body));
        }
    }

    private static String snippet(String body) {
        String collapsed = body.replaceAll("\\s+", " ").trim();
        if (collapsed.length() > MAX_SNIPPET) {
            collapsed = collapsed.substring(0, MAX_SNIPPET) + "...";
        }
        return collapsed;
    }
}
