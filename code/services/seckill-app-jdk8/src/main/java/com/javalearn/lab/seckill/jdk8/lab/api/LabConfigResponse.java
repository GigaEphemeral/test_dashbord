package com.javalearn.lab.seckill.jdk8.lab.api;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /lab/config} payload: app tier identity, runtime, the current
 * per-instance toggles and the effective configuration of the cache family
 * ({@code cacheConfig}, Stage2 B0-04). Deliberately excludes every secret (lab
 * token, DB/Redis credentials, endpoints) so it is safe to show to a local
 * operator.
 */
public final class LabConfigResponse {

    private final String app;
    private final String env;
    private final String instance;
    private final String jvmVersion;
    private final Map<String, Boolean> toggles;
    private final List<LabCacheConfigItem> cacheConfig;

    public LabConfigResponse(String app,
                             String env,
                             String instance,
                             String jvmVersion,
                             Map<String, Boolean> toggles,
                             List<LabCacheConfigItem> cacheConfig) {
        this.app = app;
        this.env = env;
        this.instance = instance;
        this.jvmVersion = jvmVersion;
        this.toggles = toggles;
        this.cacheConfig = Collections.unmodifiableList(cacheConfig);
    }

    public String getApp() {
        return app;
    }

    public String getEnv() {
        return env;
    }

    public String getInstance() {
        return instance;
    }

    public String getJvmVersion() {
        return jvmVersion;
    }

    public Map<String, Boolean> getToggles() {
        return toggles;
    }

    public List<LabCacheConfigItem> getCacheConfig() {
        return cacheConfig;
    }
}
