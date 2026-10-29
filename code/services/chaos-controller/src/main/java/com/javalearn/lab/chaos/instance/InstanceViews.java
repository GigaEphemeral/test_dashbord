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
}
