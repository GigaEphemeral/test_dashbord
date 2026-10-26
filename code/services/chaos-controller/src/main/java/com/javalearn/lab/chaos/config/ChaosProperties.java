package com.javalearn.lab.chaos.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Chaos action configuration. Every TTL, timeout, URL and credential is
 * externalized here (see application.yml / compose env) so the controller has
 * no hardcoded timing or endpoint.
 */
@ConfigurationProperties(prefix = "chaos")
public class ChaosProperties {

    /** TTL applied when kill/freeze does not send one. */
    private int defaultTtlSeconds = 120;

    /** Upper bound accepted for a caller-supplied TTL. */
    private int maxTtlSeconds = 600;

    /** Deadline for post-start/post-resume readiness probing. */
    private int actionTimeoutSeconds = 30;

    /** Watchdog sweep interval (second safety net for expired actions). */
    private int watchdogIntervalSeconds = 5;

    /** Docker stop grace period handed to the daemon. */
    private int stopGraceSeconds = 10;

    /** Revert attempts before a FAILED action stays failed until stop-all. */
    private int maxRevertAttempts = 3;

    /** Core threads of the chaos scheduler (TTL reverts + watchdog + readiness probes). */
    private int schedulerThreads = 4;

    private final Annotation annotation = new Annotation();

    private final Guide guide = new Guide();

    public int getDefaultTtlSeconds() {
        return defaultTtlSeconds;
    }

    public void setDefaultTtlSeconds(int defaultTtlSeconds) {
        this.defaultTtlSeconds = defaultTtlSeconds;
    }

    public int getMaxTtlSeconds() {
        return maxTtlSeconds;
    }

    public void setMaxTtlSeconds(int maxTtlSeconds) {
        this.maxTtlSeconds = maxTtlSeconds;
    }

    public int getActionTimeoutSeconds() {
        return actionTimeoutSeconds;
    }

    public void setActionTimeoutSeconds(int actionTimeoutSeconds) {
        this.actionTimeoutSeconds = actionTimeoutSeconds;
    }

    public int getWatchdogIntervalSeconds() {
        return watchdogIntervalSeconds;
    }

    public void setWatchdogIntervalSeconds(int watchdogIntervalSeconds) {
        this.watchdogIntervalSeconds = watchdogIntervalSeconds;
    }

    public int getStopGraceSeconds() {
        return stopGraceSeconds;
    }

    public void setStopGraceSeconds(int stopGraceSeconds) {
        this.stopGraceSeconds = stopGraceSeconds;
    }

    public int getMaxRevertAttempts() {
        return maxRevertAttempts;
    }

    public void setMaxRevertAttempts(int maxRevertAttempts) {
        this.maxRevertAttempts = maxRevertAttempts;
    }

    public int getSchedulerThreads() {
        return schedulerThreads;
    }

    public void setSchedulerThreads(int schedulerThreads) {
        this.schedulerThreads = schedulerThreads;
    }

    public Annotation getAnnotation() {
        return annotation;
    }

    public Guide getGuide() {
        return guide;
    }

    /** Interactive guide (Task 22b): scenario directory + target allow-list. */
    public static class Guide {

        /** Directory holding shared scenario JSON; compose mounts it read-only. */
        private String scenarioDir = "/app/scenarios";

        /** Container names a scenario may target; datastores are always rejected. */
        private String targetPattern = "^jl-app-[0-9]+$";

        public String getScenarioDir() {
            return scenarioDir;
        }

        public void setScenarioDir(String scenarioDir) {
            this.scenarioDir = scenarioDir;
        }

        public String getTargetPattern() {
            return targetPattern;
        }

        public void setTargetPattern(String targetPattern) {
            this.targetPattern = targetPattern;
        }
    }

    public static class Annotation {

        /** Best-effort Grafana annotations; never fail the chaos action. */
        private boolean enabled = true;

        /** Grafana base URL; injected from compose/env (no URL in code). */
        private String grafanaUrl;

        private String user;

        private String password;

        private int timeoutSeconds = 3;

        /** Extra tags appended to the mandatory chaos/java-learn/<target>/<action> set. */
        private List<String> tags = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getGrafanaUrl() {
            return grafanaUrl;
        }

        public void setGrafanaUrl(String grafanaUrl) {
            this.grafanaUrl = grafanaUrl;
        }

        public String getUser() {
            return user;
        }

        public void setUser(String user) {
            this.user = user;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public List<String> getTags() {
            return tags;
        }

        public void setTags(List<String> tags) {
            this.tags = tags;
        }
    }
}
