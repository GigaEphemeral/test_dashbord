package com.javalearn.lab.chaos.appproxy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.auth.TokenFilter;
import com.javalearn.lab.chaos.config.ChaosProperties;
import com.javalearn.lab.chaos.error.AppProxyResponseTooLargeException;
import com.javalearn.lab.chaos.error.AppResponseNotJsonException;
import com.javalearn.lab.chaos.error.AppUnreachableException;
import com.javalearn.lab.chaos.error.InvalidAppProxyRequestException;
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
 * <p>The raw query string is appended to the upstream URI verbatim; it is only
 * percent-escaped when {@link URI} would reject a character outright (e.g.
 * {@code | { } ^}), and the escaping touches exactly those bytes, never the
 * already-encoded ones. The upstream therefore sees the caller's parameters
 * untouched (B3 cursor pagination relies on {@code ?cursor=...} surviving).</p>
 *
 * <p>The shared lab token is injected here and attached as {@code X-Lab-Token};
 * it is never echoed, logged or included in an error message. The upstream
 * answer must be JSON and at most {@code chaos.app-proxy.max-response-bytes}
 * bytes: anything else (an HTML error page, an empty body, an oversized reply)
 * becomes a 502-style exception instead of being relayed as if it were a
 * {@code Result} envelope.</p>
 */
public class HttpAppProxyGateway implements AppProxyGateway {

    private static final Logger log = LoggerFactory.getLogger(HttpAppProxyGateway.class);
    private static final int MAX_SNIPPET = 120;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String scheme;
    private final int port;
    private final Duration requestTimeout;
    private final int maxResponseBytes;
    private final String labToken;

    public HttpAppProxyGateway(ChaosProperties.AppProxy config, String labToken, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.scheme = config.getScheme().trim().toLowerCase();
        this.port = config.getPort();
        this.requestTimeout = Duration.ofSeconds(config.getRequestTimeoutSeconds());
        this.maxResponseBytes = config.getMaxResponseBytes();
        this.labToken = labToken;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.getConnectTimeoutSeconds()))
                .build();
    }

    @Override
    public ProxyResponse exchange(String instance, String method, String path, String query, byte[] body) {
        URI uri = buildUri(instance, path, query);
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
            byte[] rawBody = response.body() == null ? new byte[0] : response.body();
            if (rawBody.length > maxResponseBytes) {
                throw new AppProxyResponseTooLargeException(instance + " returned " + rawBody.length
                        + " bytes; the app proxy relays at most " + maxResponseBytes
                        + " bytes for " + method + " " + path);
            }
            String responseBody = new String(rawBody, StandardCharsets.UTF_8);
            int status = response.statusCode();
            if (status == 204 || status == 304) {
                return new ProxyResponse(status, responseBody);
            }
            requireJson(instance, uri, status, response, responseBody);
            return new ProxyResponse(status, responseBody);
        } catch (AppResponseNotJsonException | AppProxyResponseTooLargeException e) {
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

    /**
     * Appends the raw query string to the upstream URI without interpretation.
     * Only when {@link URI#create(String)} rejects a character outright (RFC 3986
     * does not allow e.g. {@code | { } ^} in a query) are those bytes escaped;
     * percent sequences that already parsed are copied untouched.
     */
    private URI buildUri(String instance, String path, String query) {
        String base = scheme + "://" + instance + ":" + port + path;
        if (query == null || query.isEmpty()) {
            return URI.create(base);
        }
        try {
            return URI.create(base + "?" + query);
        } catch (IllegalArgumentException rawRejected) {
            try {
                return URI.create(base + "?" + escapeIllegalQueryChars(query));
            } catch (IllegalArgumentException stillInvalid) {
                // Only a malformed escape (a '%' without two hex digits after it)
                // survives escaping; the servlet container already rejected it
                // on the wire, but never let it reach an upstream URI either.
                throw new InvalidAppProxyRequestException(
                        "Query string is not a valid URI query and cannot be forwarded verbatim");
            }
        }
    }

    /** Percent-encodes every byte outside the RFC 3986 query character set; copies the rest. */
    private static String escapeIllegalQueryChars(String query) {
        StringBuilder out = new StringBuilder(query.length() + 16);
        for (byte b : query.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if (isQuerySafe(c)) {
                out.append((char) c);
            } else {
                out.append('%');
                out.append(Character.toUpperCase(Character.forDigit(c >>> 4, 16)));
                out.append(Character.toUpperCase(Character.forDigit(c & 0x0F, 16)));
            }
        }
        return out.toString();
    }

    private static boolean isQuerySafe(int c) {
        // RFC 3986 query = *( pchar / "/" / "?" ); '%' is kept as-is (the string
        // came from an already-encoded request target); '#' is never part of a
        // servlet query string but must not leak a fragment in either.
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || "-._~:/?[]@!$&'()*+,;=%".indexOf(c) >= 0;
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
