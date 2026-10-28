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
 * cannot guess the host directory behind the read-only {@code /scripts} mount.
 * The planner refuses with {@code 503/50303} instead of rendering a plan whose
 * bind source is empty; B2-02b resolves the real host path when it wires the
 * Docker client.</p>
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
}
