package com.javalearn.lab.chaos.instance;

import java.util.List;

/** REST payloads for the B4-01 per-instance configuration API. */
public final class InstanceViews {

    private InstanceViews() {
    }

    /**
     * Stored configuration plus the argv it generates. {@code status} is
     * {@code NOT_CONFIGURED} when the instance has no row yet; otherwise it is
     * the persisted {@link InstanceConfigStatus} name.
     */
    public record InstanceConfigView(String instance, InstanceConfig config, String status,
                                     int revision, List<String> jvmArgs, List<String> warnings,
                                     boolean limitedObservability, int requiredMemoryMb,
                                     int memoryLimitMb, String updatedAt, String appliedAt,
                                     String lastError) {
    }

    /** Dry-run validation; nothing is stored and the ledger is not required. */
    public record InstanceValidationView(String instance, boolean valid, InstanceConfig config,
                                         List<String> jvmArgs, List<String> warnings,
                                         boolean limitedObservability, int requiredMemoryMb,
                                         int memoryLimitMb) {
    }

    /**
     * Effective readback. {@code readback} is {@code OK}, {@code UNAVAILABLE} or
     * {@code READBACK_UNAVAILABLE} (JDK 8 limited-observability shell). Values
     * are only ever copied from the container environment; a missing variable
     * stays {@code null} instead of being invented.
     */
    public record EffectiveInstanceView(String instance, String containerName, String imageTag,
                                        String imageId, String javaToolOptions,
                                        String instanceId, String readback,
                                        String observability, List<String> notes) {
    }

    /**
     * One B4-02 health gate. {@code status} is {@code PASS}/{@code FAIL}/
     * {@code SKIPPED}; {@code detail} carries the observed value (pool numbers,
     * upstream status, image identity) or the failing reason.
     */
    public record ApplyCheckView(String name, String status, long durationMs, String detail) {
    }

    /**
     * B4-02 apply/rollback evidence. A failed rebuild is still HTTP 200: the
     * caller gets {@code status=FAILED} (or {@code DEGRADED} when the automatic
     * restore itself failed) plus the failing check, the rollback outcome and
     * the stable period start - never an opaque 500.
     *
     * @param action       {@code APPLY} or {@code ROLLBACK}
     * @param status       final ledger status: {@code APPLIED}/{@code FAILED}/{@code DEGRADED}
     * @param rolledBack   the previous container was restored and started
     * @param rollback     {@code NOT_NEEDED}/{@code RESTORED}/{@code FAILED}
     * @param image        running image tag after the rebuild (null when failed)
     * @param imageId      running image id after the rebuild (null when failed)
     */
    public record InstanceApplyView(String instance, String action, String status,
                                    boolean rolledBack, String rollback, String containerName,
                                    String containerId, String image, String imageId,
                                    String previousImage, String previousImageId,
                                    String javaToolOptions, List<ApplyCheckView> checks,
                                    long durationMs, String stableUntil, String failedCheck,
                                    String error, int revision) {
    }
}
