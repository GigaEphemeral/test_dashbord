package com.javalearn.lab.chaos.metrics;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JDK-HttpClient implementation of the Prometheus {@code /api/v1/query_range}
 * call. The controller already uses this HTTP stack for the app proxy and
 * Grafana annotations, so B1-03 adds no dependency.
 *
 * <p>Every failure that must surface as {@code DEGRADED} - refused connection,
 * timeout, non-2xx, an {@code error} envelope, malformed JSON, a non-matrix
 * result - becomes a {@link PrometheusQueryException}. A successful but empty
 * matrix is <em>not</em> an error: the service decides NO_DATA vs COLLECTING.</p>
 */
public class HttpPrometheusGateway implements PrometheusGateway {

    private static final int MAX_SNIPPET = 200;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String baseUrl;
    private final Duration requestTimeout;

    public HttpPrometheusGateway(MetricsProperties properties, ObjectMapper objectMapper) {
        this(properties.getPrometheusUrl(), properties.getConnectTimeoutSeconds(),
                properties.getRequestTimeoutSeconds(), objectMapper);
    }

    /**
     * Explicit-timeout variant. B1-04's pre-check reuses the parsing and error
     * mapping with its own request budget instead of the aggregation one.
     */
    public HttpPrometheusGateway(String prometheusUrl, int connectTimeoutSeconds,
                                 int requestTimeoutSeconds, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.baseUrl = stripTrailingSlash(prometheusUrl);
        this.requestTimeout = Duration.ofSeconds(requestTimeoutSeconds);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .build();
    }

    @Override
    public PrometheusMatrix queryRange(String query, long startEpochSeconds, long endEpochSeconds,
                                       long stepSeconds) {
        URI uri = URI.create(baseUrl + "/api/v1/query_range"
                + "?query=" + encode(query)
                + "&start=" + startEpochSeconds
                + "&end=" + endEpochSeconds
                + "&step=" + stepSeconds);
        return execute(uri, "range");
    }

    @Override
    public PrometheusMatrix queryInstant(String query, long atEpochSeconds) {
        URI uri = URI.create(baseUrl + "/api/v1/query"
                + "?query=" + encode(query)
                + "&time=" + atEpochSeconds);
        return execute(uri, "instant");
    }

    private PrometheusMatrix execute(URI uri, String kind) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofByteArray());
            String body = response.body() == null ? "" : new String(response.body(), StandardCharsets.UTF_8);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new PrometheusQueryException("prometheus returned HTTP " + response.statusCode()
                        + ": " + snippet(body));
            }
            return parse(body, kind);
        } catch (HttpTimeoutException e) {
            throw new PrometheusQueryException("prometheus request timed out after "
                    + requestTimeout.toSeconds() + "s at " + baseUrl, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PrometheusQueryException("prometheus request was interrupted at " + baseUrl, e);
        } catch (IOException e) {
            throw new PrometheusQueryException("prometheus unreachable at " + baseUrl + ": " + e, e);
        }
    }

    PrometheusMatrix parse(String body) {
        return parse(body, "range");
    }

    PrometheusMatrix parse(String body, String kind) {
        if (body == null || body.isBlank()) {
            throw new PrometheusQueryException("prometheus returned an empty body");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (IOException e) {
            throw new PrometheusQueryException("prometheus returned malformed JSON: " + snippet(body), e);
        }
        if (!"success".equals(root.path("status").asText())) {
            String errorType = root.path("errorType").asText("");
            String error = root.path("error").asText("unknown error");
            throw new PrometheusQueryException("prometheus returned an error"
                    + (errorType.isBlank() ? "" : " (" + errorType + ")") + ": " + error);
        }
        JsonNode data = root.path("data");
        String resultType = data.path("resultType").asText("");
        if ("instant".equals(kind)) {
            return parseVector(data, resultType);
        }
        if (!"matrix".equals(resultType)) {
            throw new PrometheusQueryException("prometheus returned resultType '" + resultType
                    + "' for a range query, expected 'matrix'");
        }
        JsonNode result = data.path("result");
        List<PrometheusSeries> series = new ArrayList<>();
        if (result.isArray()) {
            for (JsonNode item : result) {
                series.add(parseSeries(item, item.path("values")));
            }
        }
        return new PrometheusMatrix(series);
    }

    /** Instant vector: each {@code value} pair becomes a one-point series. */
    private PrometheusMatrix parseVector(JsonNode data, String resultType) {
        if (!"vector".equals(resultType)) {
            throw new PrometheusQueryException("prometheus returned resultType '" + resultType
                    + "' for an instant query, expected 'vector'");
        }
        JsonNode result = data.path("result");
        List<PrometheusSeries> series = new ArrayList<>();
        if (result.isArray()) {
            for (JsonNode item : result) {
                List<PrometheusPoint> points = new ArrayList<>();
                JsonNode value = item.path("value");
                if (value.isArray() && value.size() >= 2) {
                    addPoint(points, value.get(0).asDouble(Double.NaN), value.get(1).asText(""));
                }
                series.add(new PrometheusSeries(labelsOf(item), points));
            }
        }
        return new PrometheusMatrix(series);
    }

    private PrometheusSeries parseSeries(JsonNode item, JsonNode values) {
        List<PrometheusPoint> points = new ArrayList<>();
        if (values != null && values.isArray()) {
            for (JsonNode pair : values) {
                if (!pair.isArray() || pair.size() < 2) {
                    continue;
                }
                addPoint(points, pair.get(0).asDouble(Double.NaN), pair.get(1).asText(""));
            }
        }
        return new PrometheusSeries(labelsOf(item), points);
    }

    private static Map<String, String> labelsOf(JsonNode item) {
        Map<String, String> labels = new LinkedHashMap<>();
        JsonNode metric = item.path("metric");
        if (metric.isObject()) {
            metric.fields().forEachRemaining(entry ->
                    labels.put(entry.getKey(), entry.getValue().asText()));
        }
        return labels;
    }

    private static void addPoint(List<PrometheusPoint> points, double timestamp, String rawValue) {
        double value = parseValue(rawValue);
        if (!Double.isFinite(timestamp) || !Double.isFinite(value)) {
            // NaN/+Inf are real Prometheus answers (e.g. an empty
            // histogram_quantile); they are not samples we can average.
            return;
        }
        points.add(new PrometheusPoint(timestamp, value));
    }

    private static double parseValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return Double.NaN;
        }
        String value = raw.trim();
        if ("+Inf".equals(value)) {
            return Double.POSITIVE_INFINITY;
        }
        if ("-Inf".equals(value)) {
            return Double.NEGATIVE_INFINITY;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new PrometheusQueryException("prometheus returned a non-numeric sample '" + value + "'");
        }
    }

    private static String encode(String value) {
        // URLEncoder emits '+' for spaces; Prometheus accepts both, but %20 keeps
        // tests and logs unambiguous and survives every proxy hop unchanged.
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String stripTrailingSlash(String url) {
        String value = url == null ? "" : url.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        String collapsed = body.replaceAll("\\s+", " ").trim();
        return collapsed.length() > MAX_SNIPPET ? collapsed.substring(0, MAX_SNIPPET) + "..." : collapsed;
    }
}
