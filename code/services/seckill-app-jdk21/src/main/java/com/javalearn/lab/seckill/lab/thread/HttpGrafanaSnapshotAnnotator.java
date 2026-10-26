package com.javalearn.lab.seckill.lab.thread;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.seckill.lab.api.ThreadSnapshotView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Posts a Grafana annotation for a successful snapshot bundle (design §9.3),
 * using the same {@code POST /api/annotations} contract as the chaos-controller's
 * {@code HttpGrafanaAnnotator}: tags + text + basic auth from the environment.
 *
 * <p>Differences from the chaos one: the annotation carries {@code time} (the
 * snapshot's {@code capturedAtEpochMs}), so the marker sits on the exact snapshot
 * instant rather than on the HTTP post; failures only WARN and count
 * {@code thread_lab_snapshot_annotations_total{result="failed"}}.</p>
 *
 * <p>OFF when {@code annotation.enabled=false} or the URL is empty — the bundle is
 * still written; the skip is counted. Credentials are read from the environment
 * and never logged or put into the annotation text.</p>
 */
public final class HttpGrafanaSnapshotAnnotator implements SnapshotAnnotator {

    private static final Logger log = LoggerFactory.getLogger(HttpGrafanaSnapshotAnnotator.class);

    static final String TAG_SNAPSHOT = "snapshot";
    static final String TAG_LAB = "thread-lab";

    private final boolean enabled;
    private final URI annotationUri;
    private final String authorizationHeader;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ThreadSnapshotMetrics metrics;
    private final String instanceId;
    private final List<String> extraTags;

    public HttpGrafanaSnapshotAnnotator(LabThreadProperties.Snapshot.Annotation config,
                                        String instanceId,
                                        ObjectMapper objectMapper,
                                        ThreadSnapshotMetrics metrics) {
        Objects.requireNonNull(config, "config");
        this.enabled = config.isEnabled();
        this.annotationUri = config.buildAnnotationUri(
                "app.lab.thread.snapshot.annotation.grafana-url");
        this.timeout = Duration.ofSeconds(config.getTimeoutSeconds());
        this.authorizationHeader = basicAuth(config.getUser(), config.getPassword());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                // Grafana's annotation endpoint is plain HTTP/1.1 on jl-net.
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.instanceId = instanceId == null || instanceId.isBlank() ? "unknown" : instanceId.trim();
        this.extraTags = splitTags(config.getTags());
    }

    @Override
    public void annotate(ThreadSnapshotView view, LifecycleSnapshot snapshot) {
        if (!enabled || annotationUri == null) {
            metrics.onAnnotation(ThreadSnapshotMetrics.ANNOTATION_SKIPPED);
            log.debug("Snapshot annotation is off (enabled={}, url set={}); bundle {} kept",
                    enabled, annotationUri != null, view.directory());
            return;
        }
        try {
            List<String> tags = new ArrayList<>();
            tags.add(TAG_SNAPSHOT);
            tags.add(TAG_LAB);
            tags.add(instanceId);
            tags.add(view.trigger());
            tags.addAll(extraTags);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("tags", tags);
            body.put("text", "thread-lab snapshot: instance=" + instanceId
                    + " trigger=" + view.trigger()
                    + " state=" + snapshot.state() + "(" + snapshot.stateCode() + ")"
                    + " step=" + snapshot.step()
                    + " files=" + view.files().size()
                    + " dir=" + folderName(view));
            body.put("time", view.capturedAtEpochMs());

            HttpRequest.Builder request = HttpRequest.newBuilder(annotationUri)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));
            if (authorizationHeader != null) {
                request.header("Authorization", authorizationHeader);
            }
            HttpResponse<String> response = httpClient.send(request.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 == 2) {
                metrics.onAnnotation(ThreadSnapshotMetrics.ANNOTATION_POSTED);
            } else {
                metrics.onAnnotation(ThreadSnapshotMetrics.ANNOTATION_FAILED);
                log.warn("Grafana snapshot annotation rejected: status={} body={}",
                        response.statusCode(), response.body());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            metrics.onAnnotation(ThreadSnapshotMetrics.ANNOTATION_FAILED);
            log.warn("Grafana snapshot annotation interrupted (ignored)");
        } catch (Exception failure) {
            metrics.onAnnotation(ThreadSnapshotMetrics.ANNOTATION_FAILED);
            log.warn("Grafana snapshot annotation failed (ignored): {}", failure.toString());
        }
    }

    private static String folderName(ThreadSnapshotView view) {
        if (view.directory() == null || view.directory().isBlank()) {
            return "";
        }
        return java.nio.file.Path.of(view.directory()).getFileName().toString();
    }

    private static String basicAuth(String user, String password) {
        if (user == null || user.isBlank()) {
            return null;
        }
        String raw = user + ":" + (password == null ? "" : password);
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> splitTags(String tags) {
        List<String> result = new ArrayList<>();
        if (tags == null) {
            return result;
        }
        for (String tag : tags.split(",")) {
            String trimmed = tag.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }
}
