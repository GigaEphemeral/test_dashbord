package com.javalearn.lab.chaos.precheck;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage2 B1-04 run pre-check configuration. Every value is declared in
 * {@code application.yml} with a {@code ${ENV:default}} placeholder and mirrored
 * in the compose service environment + {@code .env.example}; the Java defaults
 * below only describe the documented default so direct construction (tests,
 * tools) is safe. Validation names both the property path and the env variable.
 *
 * <p>There is deliberately no "force"/"skip" switch: a blocking finding cannot
 * be overridden through this bean. {@code enabled=false} turns the whole gate
 * off for stacks that only use the ledger API (it is not a per-item bypass).</p>
 */
@ConfigurationProperties(prefix = "lab.precheck")
public class PrecheckProperties {

    /** Master switch for the run-start gate; default on. */
    private boolean enabled = true;

    /**
     * Background load container that must be stopped before a run (the
     * {@code traffic} compose profile). Defaults to the historical
     * {@code jl-traffic} name; it is a separate knob from
     * {@code chaos.traffic.container-name} so a renamed profile can be checked
     * without changing the traffic API.
     */
    private String trafficContainer = "jl-traffic";

    /**
     * Application containers whose running image ids are compared. Comma
     * separated (env-friendly); defaults to the three compose instances.
     */
    private String appContainers = "jl-app-1,jl-app-2,jl-app-3";

    /** Prometheus request budget for the monitoring check (seconds). */
    private int prometheusTimeoutSeconds = 5;

    /** Number of {@code up{job="seckill-app"}} targets a healthy app tier has. */
    private int requiredAppTargets = 3;

    /**
     * Host memory kept free on top of the summed {@code mem_limit} of the
     * running lab containers (MB). The budget is
     * {@code sum(mem_limit) + reserve <= Docker Info.MemTotal}; exceeding it is
     * blocking.
     */
    private int memoryReserveMb = 1024;

    /**
     * Optional expected running image id for the app containers. Blank means
     * "only check that the three instances agree". A configured value matches
     * either the full id or an unambiguous prefix (e.g. the 12-char short id).
     */
    private String expectedAppImageId = "";

    /** How many entries {@code blocking[]/warnings[]/passed[]} may list. */
    private int maxListedItems = 10;

    /**
     * Wall-clock budget of one report. Individual network waits are bounded by
     * {@link #prometheusTimeoutSeconds} and the Docker client timeouts; a check
     * that cannot start inside this budget is reported BLOCK (fail closed).
     */
    private int checkTimeoutSeconds = 30;

    /** Parsed {@link #appContainers}, trimmed and de-duplicated. */
    public List<String> appContainerNames() {
        List<String> names = new ArrayList<>();
        if (appContainers == null) {
            return names;
        }
        for (String part : appContainers.split(",")) {
            String name = part.trim();
            if (!name.isEmpty() && !names.contains(name)) {
                names.add(name);
            }
        }
        return List.copyOf(names);
    }

    /** Startup validation; messages name the property path and env variable. */
    public void validate() {
        requireText(trafficContainer, "lab.precheck.traffic-container", "LAB_PRECHECK_TRAFFIC_CONTAINER");
        requireText(appContainers, "lab.precheck.app-containers", "LAB_PRECHECK_APP_CONTAINERS");
        if (appContainerNames().isEmpty()) {
            throw new IllegalStateException("lab.precheck.app-containers lists no container name;"
                    + " env LAB_PRECHECK_APP_CONTAINERS");
        }
        range(prometheusTimeoutSeconds, 1, 300, "lab.precheck.prometheus-timeout-seconds",
                "LAB_PRECHECK_PROMETHEUS_TIMEOUT_SECONDS");
        range(requiredAppTargets, 1, 32, "lab.precheck.required-app-targets",
                "LAB_PRECHECK_REQUIRED_APP_TARGETS");
        range(memoryReserveMb, 0, 1_048_576, "lab.precheck.memory-reserve-mb",
                "LAB_PRECHECK_MEMORY_RESERVE_MB");
        range(maxListedItems, 1, 1000, "lab.precheck.max-listed-items",
                "LAB_PRECHECK_MAX_LISTED_ITEMS");
        range(checkTimeoutSeconds, 1, 3600, "lab.precheck.check-timeout-seconds",
                "LAB_PRECHECK_CHECK_TIMEOUT_SECONDS");
    }

    private static void requireText(String value, String property, String env) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " must not be blank; env " + env);
        }
    }

    private static void range(int value, int min, int max, String property, String env) {
        if (value < min || value > max) {
            throw new IllegalStateException(property + " must be between " + min + " and " + max
                    + " (was " + value + "); env " + env);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTrafficContainer() {
        return trafficContainer;
    }

    public void setTrafficContainer(String trafficContainer) {
        this.trafficContainer = trafficContainer;
    }

    public String getAppContainers() {
        return appContainers;
    }

    public void setAppContainers(String appContainers) {
        this.appContainers = appContainers;
    }

    public int getPrometheusTimeoutSeconds() {
        return prometheusTimeoutSeconds;
    }

    public void setPrometheusTimeoutSeconds(int prometheusTimeoutSeconds) {
        this.prometheusTimeoutSeconds = prometheusTimeoutSeconds;
    }

    public int getRequiredAppTargets() {
        return requiredAppTargets;
    }

    public void setRequiredAppTargets(int requiredAppTargets) {
        this.requiredAppTargets = requiredAppTargets;
    }

    public int getMemoryReserveMb() {
        return memoryReserveMb;
    }

    public void setMemoryReserveMb(int memoryReserveMb) {
        this.memoryReserveMb = memoryReserveMb;
    }

    public String getExpectedAppImageId() {
        return expectedAppImageId;
    }

    public void setExpectedAppImageId(String expectedAppImageId) {
        this.expectedAppImageId = expectedAppImageId;
    }

    public int getMaxListedItems() {
        return maxListedItems;
    }

    public void setMaxListedItems(int maxListedItems) {
        this.maxListedItems = maxListedItems;
    }

    public int getCheckTimeoutSeconds() {
        return checkTimeoutSeconds;
    }

    public void setCheckTimeoutSeconds(int checkTimeoutSeconds) {
        this.checkTimeoutSeconds = checkTimeoutSeconds;
    }
}
