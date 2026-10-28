package com.javalearn.lab.chaos.metrics;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Stage2 B1-03 metrics-aggregation configuration. Every tunable is declared in
 * {@code application.yml} with a {@code ${ENV:default}} placeholder and mirrored
 * in the compose service environment + {@code .env.example}. The Java defaults
 * below only describe the documented default so direct construction (tests,
 * tools) is safe; validation names both the property path and the environment
 * variable so a bad deployment value is easy to fix.
 *
 * <p>This layer is deliberately not a PromQL proxy: callers may only name a
 * catalog key. The catalog itself lives in a versioned resource
 * ({@code metrics/catalog.yml} by default) and is validated at startup.</p>
 */
@ConfigurationProperties(prefix = "lab.metrics")
public class MetricsProperties {

    /** Prometheus base URL on the compose network (never a host-only address). */
    private String prometheusUrl = "http://prometheus:9090";

    private int connectTimeoutSeconds = 2;

    private int requestTimeoutSeconds = 10;

    /**
     * Padding added before the run start so the first scrape inside the run is
     * not missed. Also the boundary that turns an empty tail into COLLECTING.
     */
    private int scrapeGraceSeconds = 15;

    /**
     * Padding added after the run end so the last in-flight scrape is captured
     * before the window is closed.
     */
    private int drainGraceSeconds = 30;

    /** Maximum points returned per series; the step is raised to honour it. */
    private int maxPoints = 300;

    /** Lower bound of the computed Prometheus step (seconds). */
    private int minStepSeconds = 15;

    /** Maximum total series across all requested metrics. */
    private int maxSeries = 120;

    /** Maximum number of catalog keys a single request may ask for. */
    private int maxKeys = 40;

    /** Grafana base address used for the deep link (externalized). */
    private String grafanaUrl = "http://grafana:3000";

    /** Tempo base address used for the deep link (externalized). */
    private String tempoUrl = "http://tempo:3200";

    /**
     * Optional Grafana dashboard UID. Blank (default) means the deep link points
     * at Explore; no dashboard UID is hard-coded in the code.
     */
    private String grafanaDashboardUid = "";

    /** Versioned catalog resource (classpath: or file:). */
    private String catalogLocation = "classpath:metrics/catalog.yml";

    /** Startup validation; messages name the property path and env variable. */
    public void validate() {
        requireHttpUrl(prometheusUrl, "lab.metrics.prometheus-url", "LAB_METRICS_PROMETHEUS_URL");
        requireHttpUrl(grafanaUrl, "lab.metrics.grafana-url", "LAB_METRICS_GRAFANA_URL");
        requireHttpUrl(tempoUrl, "lab.metrics.tempo-url", "LAB_METRICS_TEMPO_URL");
        requireText(catalogLocation, "lab.metrics.catalog-location", "LAB_METRICS_CATALOG_LOCATION");
        range(connectTimeoutSeconds, 1, 60, "lab.metrics.connect-timeout-seconds",
                "LAB_METRICS_CONNECT_TIMEOUT_SECONDS");
        range(requestTimeoutSeconds, 1, 300, "lab.metrics.request-timeout-seconds",
                "LAB_METRICS_REQUEST_TIMEOUT_SECONDS");
        range(scrapeGraceSeconds, 0, 3600, "lab.metrics.scrape-grace-seconds",
                "LAB_METRICS_SCRAPE_GRACE_SECONDS");
        range(drainGraceSeconds, 0, 3600, "lab.metrics.drain-grace-seconds",
                "LAB_METRICS_DRAIN_GRACE_SECONDS");
        range(maxPoints, 10, 10_000, "lab.metrics.max-points", "LAB_METRICS_MAX_POINTS");
        range(minStepSeconds, 1, 3600, "lab.metrics.min-step-seconds",
                "LAB_METRICS_MIN_STEP_SECONDS");
        range(maxSeries, 1, 10_000, "lab.metrics.max-series", "LAB_METRICS_MAX_SERIES");
        range(maxKeys, 1, 200, "lab.metrics.max-keys", "LAB_METRICS_MAX_KEYS");
        if (grafanaDashboardUid != null && !grafanaDashboardUid.isBlank()
                && !grafanaDashboardUid.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalStateException("lab.metrics.grafana-dashboard-uid must be blank or "
                    + "match [A-Za-z0-9_-]{1,128} (was '" + grafanaDashboardUid
                    + "'); env LAB_METRICS_GRAFANA_DASHBOARD_UID");
        }
    }

    private static void requireHttpUrl(String value, String property, String env) {
        requireText(value, property, env);
        String lower = value.trim().toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new IllegalStateException(property + " must start with http:// or https:// (was '"
                    + value + "'); env " + env);
        }
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

    public String getPrometheusUrl() {
        return prometheusUrl;
    }

    public void setPrometheusUrl(String prometheusUrl) {
        this.prometheusUrl = prometheusUrl;
    }

    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    public int getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
        this.requestTimeoutSeconds = requestTimeoutSeconds;
    }

    public int getScrapeGraceSeconds() {
        return scrapeGraceSeconds;
    }

    public void setScrapeGraceSeconds(int scrapeGraceSeconds) {
        this.scrapeGraceSeconds = scrapeGraceSeconds;
    }

    public int getDrainGraceSeconds() {
        return drainGraceSeconds;
    }

    public void setDrainGraceSeconds(int drainGraceSeconds) {
        this.drainGraceSeconds = drainGraceSeconds;
    }

    public int getMaxPoints() {
        return maxPoints;
    }

    public void setMaxPoints(int maxPoints) {
        this.maxPoints = maxPoints;
    }

    public int getMinStepSeconds() {
        return minStepSeconds;
    }

    public void setMinStepSeconds(int minStepSeconds) {
        this.minStepSeconds = minStepSeconds;
    }

    public int getMaxSeries() {
        return maxSeries;
    }

    public void setMaxSeries(int maxSeries) {
        this.maxSeries = maxSeries;
    }

    public int getMaxKeys() {
        return maxKeys;
    }

    public void setMaxKeys(int maxKeys) {
        this.maxKeys = maxKeys;
    }

    public String getGrafanaUrl() {
        return grafanaUrl;
    }

    public void setGrafanaUrl(String grafanaUrl) {
        this.grafanaUrl = grafanaUrl;
    }

    public String getTempoUrl() {
        return tempoUrl;
    }

    public void setTempoUrl(String tempoUrl) {
        this.tempoUrl = tempoUrl;
    }

    public String getGrafanaDashboardUid() {
        return grafanaDashboardUid;
    }

    public void setGrafanaDashboardUid(String grafanaDashboardUid) {
        this.grafanaDashboardUid = grafanaDashboardUid;
    }

    public String getCatalogLocation() {
        return catalogLocation;
    }

    public void setCatalogLocation(String catalogLocation) {
        this.catalogLocation = catalogLocation;
    }
}
