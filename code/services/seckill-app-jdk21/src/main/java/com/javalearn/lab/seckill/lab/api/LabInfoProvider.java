package com.javalearn.lab.seckill.lab.api;

import com.javalearn.lab.seckill.lab.toggle.LabToggleService;

import java.util.Objects;

/**
 * Builds the non-secret lab configuration view. The app/env/instance values are
 * the same identifiers used as Micrometer common tags, so a lab call and a
 * metric series can be correlated; the lab token is never part of the view.
 */
public final class LabInfoProvider {

    private final String app;
    private final String env;
    private final String instance;

    public LabInfoProvider(String app, String env, String instance) {
        this.app = Objects.requireNonNull(app, "app");
        this.env = Objects.requireNonNull(env, "env");
        this.instance = Objects.requireNonNull(instance, "instance");
    }

    public LabConfigResponse config(LabToggleService toggleService) {
        return new LabConfigResponse(
                app,
                env,
                instance,
                System.getProperty("java.version"),
                toggleService.snapshot());
    }
}
