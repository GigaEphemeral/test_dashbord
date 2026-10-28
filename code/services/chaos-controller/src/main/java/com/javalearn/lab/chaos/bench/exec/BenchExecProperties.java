package com.javalearn.lab.chaos.bench.exec;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Stage2 B2-02a k6 executor configuration ({@code lab.bench.exec}). Every value
 * is externalized (工程规约 §1): image, network, mounts, volume, remote-write
 * URL, base URL, container name prefix and the two hard upper bounds are all
 * env-overridable through {@code LAB_BENCH_EXEC_*} and mirror
 * {@code application.yml}, {@code compose.base.yml} and {@code .env.example}.
 *
 * <p>{@code scripts-host-dir} intentionally defaults to blank: the controller
 * cannot guess the host directory behind the read-only {@code /app/k6} mount.
 * {@link BenchScriptsHostDirResolver} (B2-02b-1) resolves it from the
 * controller container's own mounts (or from this explicit override) and the
 * planner still refuses with {@code 503/50303} instead of rendering a plan
 * whose bind source is empty. The run lifecycle (B2-02b-2) is what feeds the
 * resolved value into the planner.</p>
 *
 * <p>The image is a configuration value, never a request field: a launch
 * request cannot override it (see {@link BenchLaunchPlanner}).</p>
 */
@Component
@ConfigurationProperties(prefix = "lab.bench.exec")
public class BenchExecProperties {

    private String k6Image = "grafana/k6:1.3.0";
    private String network = "jl-net";
    private String scriptsHostDir = "";
    private String scriptsMount = "/scripts";
    private String reportsMount = "/reports";
    private String reportsVolume = "jl-k6-reports";
    private String prometheusRwUrl = "http://prometheus:9090/api/v1/write";
    private String baseUrl = "http://nginx";
    private String containerPrefix = "jl-k6-";
    private int maxVus = 1000;
    private int maxDurationSeconds = 3600;
    private String summaryFile = "summary.json";
    /** Controller container inspected to discover the host k6 directory. */
    private String controllerContainer = "jl-chaos-controller";
    /** Container path that is bind-mounted from the host k6 directory. */
    private String controllerK6Mount = "/app/k6";
    /** Download cap for one summary report (bytes). */
    private long reportMaxBytes = 4L * 1024 * 1024;
    /** Byte cap for one logs answer (truncated, never buffered beyond this). */
    private int logsMaxBytes = 64 * 1024;
    /** Upper bound applied to a requested log tail. */
    private int logsMaxTail = 1000;
    /** Grace period for a manual k6 container stop (seconds). */
    private int stopGraceSeconds = 10;
    /** Stage2 B2-02b-2a: run the exit/timeout watchdog scheduler. */
    private boolean watchdogEnabled = true;
    /** Seconds between two watchdog ticks. */
    private int watchdogIntervalSeconds = 5;
    /** Extra time a k6 run may overrun its requested duration before FAILED. */
    private int timeoutGraceSeconds = 30;
    /**
     * How long a run may stay in STOPPING before the watchdog closes it. The
     * grace keeps the watchdog out of the operator's stop window (STOPPING is
     * written before the container stop); a stashed second transition or a
     * crashed stop is recovered once the grace expires.
     */
    private int stoppingSettleGraceSeconds = 30;
    /** Remove lab.role=k6 containers without a live RUNNING run at startup. */
    private boolean orphanCleanupEnabled = true;

    public String getK6Image() {
        return k6Image;
    }

    public void setK6Image(String k6Image) {
        this.k6Image = k6Image;
    }

    public String getNetwork() {
        return network;
    }

    public void setNetwork(String network) {
        this.network = network;
    }

    public String getScriptsHostDir() {
        return scriptsHostDir;
    }

    public void setScriptsHostDir(String scriptsHostDir) {
        this.scriptsHostDir = scriptsHostDir;
    }

    public String getScriptsMount() {
        return scriptsMount;
    }

    public void setScriptsMount(String scriptsMount) {
        this.scriptsMount = scriptsMount;
    }

    public String getReportsMount() {
        return reportsMount;
    }

    public void setReportsMount(String reportsMount) {
        this.reportsMount = reportsMount;
    }

    public String getReportsVolume() {
        return reportsVolume;
    }

    public void setReportsVolume(String reportsVolume) {
        this.reportsVolume = reportsVolume;
    }

    public String getPrometheusRwUrl() {
        return prometheusRwUrl;
    }

    public void setPrometheusRwUrl(String prometheusRwUrl) {
        this.prometheusRwUrl = prometheusRwUrl;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getContainerPrefix() {
        return containerPrefix;
    }

    public void setContainerPrefix(String containerPrefix) {
        this.containerPrefix = containerPrefix;
    }

    public int getMaxVus() {
        return maxVus;
    }

    public void setMaxVus(int maxVus) {
        this.maxVus = maxVus;
    }

    public int getMaxDurationSeconds() {
        return maxDurationSeconds;
    }

    public void setMaxDurationSeconds(int maxDurationSeconds) {
        this.maxDurationSeconds = maxDurationSeconds;
    }

    public String getSummaryFile() {
        return summaryFile;
    }

    public void setSummaryFile(String summaryFile) {
        this.summaryFile = summaryFile;
    }

    public String getControllerContainer() {
        return controllerContainer;
    }

    public void setControllerContainer(String controllerContainer) {
        this.controllerContainer = controllerContainer;
    }

    public String getControllerK6Mount() {
        return controllerK6Mount;
    }

    public void setControllerK6Mount(String controllerK6Mount) {
        this.controllerK6Mount = controllerK6Mount;
    }

    public long getReportMaxBytes() {
        return reportMaxBytes;
    }

    public void setReportMaxBytes(long reportMaxBytes) {
        this.reportMaxBytes = reportMaxBytes;
    }

    public int getLogsMaxBytes() {
        return logsMaxBytes;
    }

    public void setLogsMaxBytes(int logsMaxBytes) {
        this.logsMaxBytes = logsMaxBytes;
    }

    public int getLogsMaxTail() {
        return logsMaxTail;
    }

    public void setLogsMaxTail(int logsMaxTail) {
        this.logsMaxTail = logsMaxTail;
    }

    public int getStopGraceSeconds() {
        return stopGraceSeconds;
    }

    public void setStopGraceSeconds(int stopGraceSeconds) {
        this.stopGraceSeconds = stopGraceSeconds;
    }

    public boolean isWatchdogEnabled() {
        return watchdogEnabled;
    }

    public void setWatchdogEnabled(boolean watchdogEnabled) {
        this.watchdogEnabled = watchdogEnabled;
    }

    public int getWatchdogIntervalSeconds() {
        return watchdogIntervalSeconds;
    }

    public void setWatchdogIntervalSeconds(int watchdogIntervalSeconds) {
        this.watchdogIntervalSeconds = watchdogIntervalSeconds;
    }

    public int getTimeoutGraceSeconds() {
        return timeoutGraceSeconds;
    }

    public void setTimeoutGraceSeconds(int timeoutGraceSeconds) {
        this.timeoutGraceSeconds = timeoutGraceSeconds;
    }

    public int getStoppingSettleGraceSeconds() {
        return stoppingSettleGraceSeconds;
    }

    public void setStoppingSettleGraceSeconds(int stoppingSettleGraceSeconds) {
        this.stoppingSettleGraceSeconds = stoppingSettleGraceSeconds;
    }

    public boolean isOrphanCleanupEnabled() {
        return orphanCleanupEnabled;
    }

    public void setOrphanCleanupEnabled(boolean orphanCleanupEnabled) {
        this.orphanCleanupEnabled = orphanCleanupEnabled;
    }
}
