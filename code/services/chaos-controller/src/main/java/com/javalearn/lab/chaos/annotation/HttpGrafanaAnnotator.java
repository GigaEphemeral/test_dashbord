package com.javalearn.lab.chaos.annotation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.config.ChaosProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Posts best-effort annotations to Grafana ({@code /api/annotations}).
 *
 * <p>URL, credentials, timeout and tags come from configuration. Any failure is
 * logged and swallowed: the chaos action itself has already been applied.</p>
 */
public class HttpGrafanaAnnotator implements ChaosAnnotator {

    private static final Logger log = LoggerFactory.getLogger(HttpGrafanaAnnotator.class);
    private static final String ANNOTATION_PATH = "/api/annotations";

    private final ObjectMapper objectMapper;
    private final String authorizationHeader;
    private final Duration timeout;
    private final URI annotationUri;
    private final HttpClient httpClient;

    public HttpGrafanaAnnotator(ChaosProperties.Annotation config, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.timeout = Duration.ofSeconds(config.getTimeoutSeconds());
        this.annotationUri = buildUri(config.getGrafanaUrl());
        this.authorizationHeader = basicAuth(config.getUser(), config.getPassword());
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public void annotate(List<String> tags, String text) {
        if (annotationUri == null) {
            log.debug("chaos.annotation.grafana-url is not set; skipping annotation");
            return;
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("tags", tags);
            body.put("text", text);

            HttpRequest.Builder builder = HttpRequest.newBuilder(annotationUri)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));
            if (authorizationHeader != null) {
                builder.header("Authorization", authorizationHeader);
            }

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("Grafana annotation rejected: status={} body={}", response.statusCode(), response.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Grafana annotation interrupted (ignored)");
        } catch (Exception e) {
            log.warn("Grafana annotation failed (ignored): {}", e.toString());
        }
    }

    private static URI buildUri(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        String base = baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + ANNOTATION_PATH);
    }

    private static String basicAuth(String user, String password) {
        if (user == null || user.isBlank()) {
            return null;
        }
        String raw = user + ":" + (password == null ? "" : password);
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
