package com.javalearn.lab.seckill.lab.api;

import java.util.List;
import java.util.Map;

/**
 * {@code GET /lab/config} payload: app tier identity, runtime, the current
 * per-instance toggles and the effective configuration of the cache family
 * ({@code cacheConfig}, Stage2 B0-04). Deliberately excludes every secret (lab
 * token, DB/Redis credentials, endpoints) so it is safe to show to a local
 * operator.
 *
 * <p>{@code cacheConfig} reports value + source + declared default per key, read
 * out of the running environment rather than from a second table of constants,
 * so it cannot drift from what the instance actually bound.</p>
 */
public record LabConfigResponse(String app,
                                String env,
                                String instance,
                                String jvmVersion,
                                Map<String, Boolean> toggles,
                                List<LabCacheConfigItem> cacheConfig) {
}
