package com.javalearn.lab.chaos.instance;

import java.time.Instant;

/**
 * One {@code lab_instance_config} row. JSON travels as text so the
 * serialization policy stays in the service.
 *
 * @param configJson         canonical/normalized config JSON (never the raw request)
 * @param previousConfigJson last successfully saved config before the current one
 * @param revision           monotonically increasing per instance
 */
public record StoredInstanceConfig(String instance, String configJson, String previousConfigJson,
                                   InstanceConfigStatus status, int revision, Instant updatedAt,
                                   Instant appliedAt, String lastError) {
}
