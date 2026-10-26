package com.javalearn.lab.seckill.lab.thread;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The {@code thread_lab_snapshot_*} meter family of design §9.3.
 *
 * <p>Series (declared exactly; both triggers and every outcome are registered
 * eagerly so a D6 panel binds to the label set at zero):</p>
 * <ul>
 *   <li>{@code thread_lab_snapshot_attempts_total{trigger=freeze|manual}}</li>
 *   <li>{@code thread_lab_snapshot_success_total{trigger}}</li>
 *   <li>{@code thread_lab_snapshot_failures_total{trigger}}</li>
 *   <li>{@code thread_lab_snapshot_skipped_total{reason=disabled}}</li>
 *   <li>{@code thread_lab_snapshot_duration_seconds{trigger}} (timer: count/sum/max)</li>
 *   <li>{@code thread_lab_snapshot_annotations_total{result=posted|skipped|failed}}</li>
 *   <li>{@code thread_lab_snapshot_retention_deleted_total}</li>
 * </ul>
 *
 * <p>Kept separate from {@link LifecycleDemoMetrics}: the demo's counters count
 * state-machine commands, these count artefact production, and the on-demand
 * {@code POST /lab/thread/snapshot} produces artefacts without touching the demo.</p>
 */
public final class ThreadSnapshotMetrics {

    static final String TRIGGER_FREEZE = "freeze";
    static final String TRIGGER_MANUAL = "manual";

    static final String ANNOTATION_POSTED = "posted";
    static final String ANNOTATION_SKIPPED = "skipped";
    static final String ANNOTATION_FAILED = "failed";

    private final Map<String, Counter> attempts = new LinkedHashMap<>();
    private final Map<String, Counter> successes = new LinkedHashMap<>();
    private final Map<String, Counter> failures = new LinkedHashMap<>();
    private final Map<String, Timer> durations = new LinkedHashMap<>();
    private final Map<String, Counter> annotations = new LinkedHashMap<>();
    private final Counter skippedDisabled;
    private final Counter retentionDeleted;

    public ThreadSnapshotMetrics(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        for (String trigger : new String[]{TRIGGER_FREEZE, TRIGGER_MANUAL}) {
            attempts.put(trigger, Counter.builder("thread_lab_snapshot_attempts_total")
                    .description("Snapshot bundles requested, by trigger")
                    .tag("trigger", trigger)
                    .register(registry));
            successes.put(trigger, Counter.builder("thread_lab_snapshot_success_total")
                    .description("Snapshot bundles written completely, by trigger")
                    .tag("trigger", trigger)
                    .register(registry));
            failures.put(trigger, Counter.builder("thread_lab_snapshot_failures_total")
                    .description("Snapshot bundles that failed (freeze is unaffected), by trigger")
                    .tag("trigger", trigger)
                    .register(registry));
            durations.put(trigger, Timer.builder("thread_lab_snapshot_duration_seconds")
                    .description("Wall-clock capture time of one snapshot bundle")
                    .tag("trigger", trigger)
                    .register(registry));
        }
        skippedDisabled = Counter.builder("thread_lab_snapshot_skipped_total")
                .description("Snapshot requests skipped without attempting a capture")
                .tag("reason", "disabled")
                .register(registry);
        for (String result : new String[]{ANNOTATION_POSTED, ANNOTATION_SKIPPED, ANNOTATION_FAILED}) {
            annotations.put(result, Counter.builder("thread_lab_snapshot_annotations_total")
                    .description("Best-effort Grafana annotations of snapshots, by result")
                    .tag("result", result)
                    .register(registry));
        }
        retentionDeleted = Counter.builder("thread_lab_snapshot_retention_deleted_total")
                .description("Snapshot folders pruned by count/age retention")
                .register(registry);
    }

    void onAttempt(String trigger) {
        attempts.get(trigger).increment();
    }

    void onSuccess(String trigger) {
        successes.get(trigger).increment();
    }

    void onFailure(String trigger) {
        failures.get(trigger).increment();
    }

    void onDuration(String trigger, Duration duration) {
        durations.get(trigger).record(duration);
    }

    void onSkippedDisabled() {
        skippedDisabled.increment();
    }

    void onAnnotation(String result) {
        annotations.get(result).increment();
    }

    void onRetentionDeleted(int folders) {
        if (folders > 0) {
            retentionDeleted.increment(folders);
        }
    }
}
