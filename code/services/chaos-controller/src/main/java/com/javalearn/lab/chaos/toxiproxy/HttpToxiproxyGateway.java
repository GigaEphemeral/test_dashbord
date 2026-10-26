package com.javalearn.lab.chaos.toxiproxy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.config.ChaosProperties;
import com.javalearn.lab.chaos.error.InvalidToxiproxyRequestException;
import com.javalearn.lab.chaos.error.ToxiproxyConflictException;
import com.javalearn.lab.chaos.error.ToxiproxyNotFoundException;
import com.javalearn.lab.chaos.error.ToxiproxyUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Toxiproxy v2 client over the JDK's {@link HttpClient}.
 *
 * <p>The repo's existing outbound HTTP call ({@link
 * com.javalearn.lab.chaos.annotation.HttpGrafanaAnnotator}) already uses
 * {@code java.net.http.HttpClient}; reusing it keeps the module free of a
 * second HTTP stack and of any new dependency. Connect and request timeouts
 * both come from {@code chaos.toxiproxy.*}.</p>
 *
 * <p>Toxiproxy semantics encoded here:</p>
 * <ul>
 *   <li>a toxic is {@code POST /proxies/{proxy}/toxics} with
 *       {@code name/type/attributes/toxicity};</li>
 *   <li>deleting a toxic restores that fault immediately;</li>
 *   <li>a proxy is cut with {@code POST /proxies/{proxy} {"enabled":false}} and
 *       restored with {@code true}; disabling does not remove toxics;</li>
 *   <li>Toxiproxy has no per-proxy reset, so reset is list + delete. A toxic
 *       deleted concurrently (404) is not an error during reset.</li>
 * </ul>
 */
public class HttpToxiproxyGateway implements ToxiproxyGateway {

    private static final Logger log = LoggerFactory.getLogger(HttpToxiproxyGateway.class);
    private static final int MAX_ERROR_BODY = 200;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final URI baseUri;
    private final Duration requestTimeout;

    public HttpToxiproxyGateway(ChaosProperties.Toxiproxy config, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.requestTimeout = Duration.ofSeconds(config.getRequestTimeoutSeconds());
        String configured = config.getUrl() == null ? "" : config.getUrl().trim();
        while (configured.endsWith("/")) {
            configured = configured.substring(0, configured.length() - 1);
        }
        this.baseUri = configured.isEmpty() ? null : URI.create(configured);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.getConnectTimeoutSeconds()))
                .build();
    }

    @Override
    public ToxicView addToxic(String proxy, ToxicSpec toxic) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", toxic.name());
        body.put("type", toxic.type().wireName());
        body.put("attributes", toxic.attributes());
        body.put("toxicity", 1.0);

        String what = "toxic '" + toxic.name() + "' on proxy '" + proxy + "'";
        HttpResponse<String> response = exchange("POST", toxicsPath(proxy), body);
        requireSuccess(response, what);
        return parse(response.body(), ToxicView.class, what);
    }

    @Override
    public void removeToxic(String proxy, String toxicName) {
        String what = "toxic '" + toxicName + "' on proxy '" + proxy + "'";
        HttpResponse<String> response = exchange("DELETE", toxicPath(proxy, toxicName), null);
        requireSuccess(response, what);
    }

    @Override
    public int resetProxy(String proxy) {
        int removed = 0;
        for (ToxicView toxic : listToxics(proxy)) {
            HttpResponse<String> response = exchange("DELETE", toxicPath(proxy, toxic.name()), null);
            if (response.statusCode() == 404) {
                // Another reset (or stop-all) already removed it; reset stays idempotent.
                log.debug("Toxic {} disappeared from proxy {} during reset; skipping", toxic.name(), proxy);
                continue;
            }
            requireSuccess(response, "toxic '" + toxic.name() + "' on proxy '" + proxy + "'");
            removed++;
        }
        return removed;
    }

    @Override
    public void setProxyEnabled(String proxy, boolean enabled) {
        Map<String, Object> body = Map.of("enabled", enabled);
        HttpResponse<String> response = exchange("POST", proxyPath(proxy), body);
        requireSuccess(response, "proxy '" + proxy + "'");
    }

    @Override
    public List<ToxicView> listToxics(String proxy) {
        HttpResponse<String> response = exchange("GET", toxicsPath(proxy), null);
        requireSuccess(response, "toxics of proxy '" + proxy + "'");
        return parse(response.body(), new TypeReference<List<ToxicView>>() {
        }, "toxics of proxy '" + proxy + "'");
    }

    @Override
    public ProxyView getProxy(String proxy) {
        HttpResponse<String> response = exchange("GET", proxyPath(proxy), null);
        requireSuccess(response, "proxy '" + proxy + "'");
        return parse(response.body(), ProxyView.class, "proxy '" + proxy + "'");
    }

    private HttpResponse<String> exchange(String method, String path, Object body) {
        if (baseUri == null) {
            throw new ToxiproxyUnavailableException(
                    "Toxiproxy URL is not configured (chaos.toxiproxy.url / CHAOS_TOXIPROXY_URL)");
        }
        URI uri = URI.create(baseUri + path);
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(requestTimeout)
                    .header("Accept", "application/json");
            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", "application/json");
                builder.method(method, HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));
            }
            return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToxiproxyUnavailableException(
                    "Toxiproxy request interrupted: " + method + " " + path, e);
        } catch (IOException | RuntimeException e) {
            throw new ToxiproxyUnavailableException(
                    "Toxiproxy request failed: " + method + " " + path + ": " + e, e);
        }
    }

    private static void requireSuccess(HttpResponse<String> response, String what) {
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return;
        }
        String detail = detail(response.body());
        if (status == 404) {
            throw new ToxiproxyNotFoundException("Toxiproxy does not know " + what + detail);
        }
        if (status == 409) {
            throw new ToxiproxyConflictException("Toxiproxy conflict for " + what + detail);
        }
        if (status == 400) {
            throw new InvalidToxiproxyRequestException("Toxiproxy rejected " + what + detail);
        }
        throw new ToxiproxyUnavailableException(
                "Toxiproxy returned HTTP " + status + " for " + what + detail);
    }

    private <T> T parse(String body, Class<T> type, String what) {
        try {
            return objectMapper.readValue(body, type);
        } catch (JsonProcessingException | RuntimeException e) {
            throw new ToxiproxyUnavailableException(
                    "Malformed Toxiproxy response for " + what + ": " + e.getMessage(), e);
        }
    }

    private <T> T parse(String body, TypeReference<T> type, String what) {
        try {
            return objectMapper.readValue(body, type);
        } catch (JsonProcessingException | RuntimeException e) {
            throw new ToxiproxyUnavailableException(
                    "Malformed Toxiproxy response for " + what + ": " + e.getMessage(), e);
        }
    }

    private static String detail(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String trimmed = body.trim();
        if (trimmed.length() > MAX_ERROR_BODY) {
            trimmed = trimmed.substring(0, MAX_ERROR_BODY) + "...";
        }
        return " (body: " + trimmed + ")";
    }

    private static String proxyPath(String proxy) {
        return "/proxies/" + segment(proxy);
    }

    private static String toxicsPath(String proxy) {
        return proxyPath(proxy) + "/toxics";
    }

    private static String toxicPath(String proxy, String toxicName) {
        return toxicsPath(proxy) + "/" + segment(toxicName);
    }

    private static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
