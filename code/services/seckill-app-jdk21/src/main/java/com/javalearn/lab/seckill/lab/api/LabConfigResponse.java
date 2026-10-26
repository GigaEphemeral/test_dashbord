package com.javalearn.lab.seckill.lab.api;

import java.util.Map;

/**
 * {@code GET /lab/config} payload: app tier identity, runtime and the current
 * per-instance toggles. Deliberately excludes every secret (lab token, DB/Redis
 * credentials, endpoints) so it is safe to show to a local operator.
 */
public record LabConfigResponse(String app,
                                String env,
                                String instance,
                                String jvmVersion,
                                Map<String, Boolean> toggles) {
}
