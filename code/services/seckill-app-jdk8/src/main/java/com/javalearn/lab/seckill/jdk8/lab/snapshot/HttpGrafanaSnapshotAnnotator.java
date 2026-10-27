package com.javalearn.lab.seckill.jdk8.lab.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.seckill.jdk8.lab.api.SnapshotView;
import com.javalearn.lab.seckill.jdk8.lab.config.SnapshotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * JDK 8 counterpart of the JDK 21 {@code HttpGrafanaSnapshotAnnotator} (design
 * §9.3), written against {@link HttpURLConnection} because {@code java.net.http}
 * is Java 11+.
 *
 * <p>Same wire contract: {@code POST <grafana>/api/annotations} with basic auth
 * and a JSON body {@code {time, tags, text}}, where {@code time} is the
 * snapshot's {@code capturedAtEpochMs} (not the post instant) and the tags are
 * {@code snapshot, thread-lab, <instance>, <trigger>} plus the optional
 * configured extras. Credentials come from the environment and are never
 * logged or embedded in the text.</p>
 *
 * <p>The call happens after a successful capture, outside the capture lock.
 * Every failure — non-2xx, connect/read timeout, malformed URL at call time —
 * only WARNs and increments
 * {@code thread_lab_snapshot_annotations_total{result="failed"}}; the bundle
 * stays complete and {@code POST /lab/snapshot} still reports success. A blank
 * URL or {@code annotation.enabled=false} is an OFF state: the skip is counted
 * as {@code result="skipped"} and no connection is opened.</p>
 */
public final class HttpGrafanaSnapshotAnnotator implements SnapshotAnnotator {

    private static final Logger log = LoggerFactory.getLogger(HttpGrafanaSnapshotAnnotator.class);

    static final String TAG_SNAPSHOT = "snapshot";
    static final String TAG_LAB = "thread-lab";

    private final boolean enabled;
    private final URI annotationUri;
    private final String authorizationHeader;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final ObjectMapper objectMapper;
    private final SnapshotMetrics metrics;
    private final String instanceId;
    private final List<String> extraTags;

    public HttpGrafanaSnapshotAnnotator(SnapshotProperties.Annotation config,
                                        String instanceId,
                                        ObjectMapper objectMapper,
                                        SnapshotMetrics metrics) {
        Objects.requireNonNull(config, "config");
        this.enabled = config.isEnabled();
        this.annotationUri = config.buildAnnotationUri("app.lab.snapshot.annotation.url");
        this.connectTimeoutMs = (int) config.getConnectTimeoutMs();
        this.readTimeoutMs = (int) config.getReadTimeoutMs();
        this.authorizationHeader = basicAuth(config.getUser(), config.getPassword());
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        String id = instanceId == null || instanceId.trim().isEmpty() ? "unknown" : instanceId.trim();
        this.instanceId = id;
        this.extraTags = splitTags(config.getTags());
    }

    @Override
    public void annotate(SnapshotView view) {
        if (!enabled || annotationUri == null) {
            metrics.onAnnotation(SnapshotMetrics.ANNOTATION_SKIPPED);
            log.debug("Snapshot annotation is off (enabled={}, url set={}); bundle {} kept",
                    enabled, annotationUri != null, view.getDirectory());
            return;
        }
        HttpURLConnection connection = null;
        try {
            byte[] payload = objectMapper.writeValueAsBytes(body(view));
            connection = (HttpURLConnection) annotationUri.toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(connectTimeoutMs);
            connection.setReadTimeout(readTimeoutMs);
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setFixedLengthStreamingMode(payload.length);
            if (authorizationHeader != null) {
                connection.setRequestProperty("Authorization", authorizationHeader);
            }
            try (OutputStream out = connection.getOutputStream()) {
                out.write(payload);
            }
            int status = connection.getResponseCode();
            if (status / 100 == 2) {
                drain(connection.getInputStream());
                metrics.onAnnotation(SnapshotMetrics.ANNOTATION_POSTED);
            } else {
                drain(connection.getErrorStream());
                metrics.onAnnotation(SnapshotMetrics.ANNOTATION_FAILED);
                log.warn("Grafana snapshot annotation rejected: status={}", status);
            }
        } catch (Exception failure) {
            metrics.onAnnotation(SnapshotMetrics.ANNOTATION_FAILED);
            log.warn("Grafana snapshot annotation failed (ignored): {}", failure.toString());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private Map<String, Object> body(SnapshotView view) {
        String trigger = view.getTrigger() == null || view.getTrigger().trim().isEmpty()
                ? "unknown" : view.getTrigger().trim();
        List<String> tags = new ArrayList<String>();
        tags.add(TAG_SNAPSHOT);
        tags.add(TAG_LAB);
        tags.add(instanceId);
        tags.add(trigger);
        tags.addAll(extraTags);

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("tags", tags);
        body.put("text", "thread-lab snapshot: instance=" + instanceId
                + " trigger=" + trigger
                + " files=" + view.getFiles().size()
                + " toolset=" + ThreadSnapshotService.TOOLSET
                + " dir=" + folderName(view));
        body.put("time", view.getCapturedAtEpochMs());
        return body;
    }

    private static String folderName(SnapshotView view) {
        if (view.getDirectory() == null || view.getDirectory().trim().isEmpty()) {
            return "";
        }
        return Paths.get(view.getDirectory()).getFileName().toString();
    }

    private static String basicAuth(String user, String password) {
        if (user == null || user.trim().isEmpty()) {
            return null;
        }
        String raw = user + ":" + (password == null ? "" : password);
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> splitTags(String tags) {
        List<String> result = new ArrayList<String>();
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

    private static void drain(InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            byte[] buffer = new byte[256];
            while (stream.read(buffer) != -1) {
                // Drain so the connection can be reused/closed cleanly.
            }
        } catch (IOException ignored) {
            // The status code already decided the metric; a body read error is noise.
        } finally {
            try {
                stream.close();
            } catch (IOException ignored) {
                // Same: best-effort close of a response body.
            }
        }
    }
}
