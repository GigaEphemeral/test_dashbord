package com.javalearn.lab.seckill.jdk8.lab.snapshot;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.Objects;

/**
 * The {@code thread_lab_snapshot_*} meter family on the JDK 8 shell (§9.3).
 *
 * <p>Names and label shapes are identical to the JDK 21
 * {@code ThreadSnapshotMetrics} so one D6 query covers both runtimes; the common
 * {@code jvm_version} tag (already declared in {@code management.metrics.tags})
 * tells 1.8.0_x from 21.0.x without a new label here. The JDK 8 shell has no
 * lifecycle demo, so only the {@code manual} trigger exists — no synthetic
 * {@code freeze} series that could never increase:</p>
 *
 * <ul>
 *   <li>{@code thread_lab_snapshot_attempts_total{trigger=manual}}</li>
 *   <li>{@code thread_lab_snapshot_success_total{trigger}}</li>
 *   <li>{@code thread_lab_snapshot_failures_total{trigger}}</li>
 *   <li>{@code thread_lab_snapshot_skipped_total{reason=disabled}}</li>
 *   <li>{@code thread_lab_snapshot_duration_seconds{trigger}} (count/sum/max)</li>
 *   <li>{@code thread_lab_snapshot_retention_deleted_total}</li>
 * </ul>
 */
public final class SnapshotMetrics {

    static final String TRIGGER_MANUAL = "manual";
    static final String SKIP_DISABLED = "disabled";

    private final Counter attempts;
    private final Counter successes;
    private final Counter failures;
    private final Counter skippedDisabled;
    private final Counter retentionDeleted;
    private final Timer duration;

    public SnapshotMetrics(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        attempts = Counter.builder("thread_lab_snapshot_attempts_total")
                .description("Snapshot bundles requested, by trigger")
                .tag("trigger", TRIGGER_MANUAL)
                .register(registry);
        successes = Counter.builder("thread_lab_snapshot_success_total")
                .description("Snapshot bundles written completely, by trigger")
                .tag("trigger", TRIGGER_MANUAL)
                .register(registry);
        failures = Counter.builder("thread_lab_snapshot_failures_total")
                .description("Snapshot bundles that failed (endpoint still answers 200), by trigger")
                .tag("trigger", TRIGGER_MANUAL)
                .register(registry);
        skippedDisabled = Counter.builder("thread_lab_snapshot_skipped_total")
                .description("Snapshot requests skipped without attempting a capture")
                .tag("reason", SKIP_DISABLED)
                .register(registry);
        retentionDeleted = Counter.builder("thread_lab_snapshot_retention_deleted_total")
                .description("Snapshot folders pruned by count/age retention")
                .register(registry);
        duration = Timer.builder("thread_lab_snapshot_duration_seconds")
                .description("Wall-clock capture time of one snapshot bundle")
                .tag("trigger", TRIGGER_MANUAL)
                .register(registry);
    }

    void onAttempt() {
        attempts.increment();
    }

    void onSuccess() {
        successes.increment();
    }

    void onFailure() {
        failures.increment();
    }

    void onDuration(Duration elapsed) {
        duration.record(elapsed);
    }

    void onSkippedDisabled() {
        skippedDisabled.increment();
    }

    void onRetentionDeleted(int folders) {
        if (folders > 0) {
            retentionDeleted.increment(folders);
        }
    }
}
