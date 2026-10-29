package com.javalearn.lab.chaos.instance;

import java.time.Instant;

/**
 * One {@code lab_instance_config} row. JSON travels as text so the
 * serialization policy stays in the service.
 *
 * @param configJson         canonical/normalized config JSON (never the raw request)
 * @param previousConfigJson last successfully saved config before the current one
 * @param revision           monotonically increasing per instance
 * @param stableUntil        end of the B4-02 stable period (set after success and failure)
 * @param appliedImageTag    image tag running after the last successful apply/rollback
 * @param appliedImageId     image id running after the last successful apply/rollback
 * @param lastApplyAt        time of the last finished apply/rollback attempt
 * @param previousImageTag   image tag running before the last apply (rollback target)
 * @param previousImageId    image id running before the last apply (rollback target)
 */
public record StoredInstanceConfig(String instance, String configJson, String previousConfigJson,
                                   InstanceConfigStatus status, int revision, Instant updatedAt,
                                   Instant appliedAt, String lastError, Instant stableUntil,
                                   String appliedImageTag, String appliedImageId,
                                   Instant lastApplyAt, String previousImageTag,
                                   String previousImageId) {

    /** B4-01 shape: everything B4-02 adds is empty. */
    public StoredInstanceConfig(String instance, String configJson, String previousConfigJson,
                                InstanceConfigStatus status, int revision, Instant updatedAt,
                                Instant appliedAt, String lastError) {
        this(instance, configJson, previousConfigJson, status, revision, updatedAt, appliedAt,
                lastError, null, null, null, null, null, null);
    }
}
