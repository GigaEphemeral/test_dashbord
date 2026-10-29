package com.javalearn.lab.chaos.traffic;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Stage2 B5-02 traffic split. The Nginx upstream weights are read from the
 * compose-mounted nginx config instead of being duplicated in Java: the
 * controller image does not own that file, so a hardcoded copy would drift the
 * moment somebody edits a `weight=`.
 */
@ConfigurationProperties(prefix = "lab.traffic-split")
public class TrafficSplitProperties {

    /** Read-only mount of the active nginx site config. */
    private String nginxUpstreamConf = "/etc/javalab/nginx/default.conf";

    /** Upstream block the app traffic goes through. */
    private String upstreamName = "seckill_backend";

    /** Prometheus job of the Java instances (same value as lab.topology.app-job). */
    private String appJob = "seckill-app";

    /** URI regex excluded from business hits (probe/actuator endpoints). */
    private String businessUriFilter = "/actuator.*";

    /** Byte cap for the mounted nginx config read. */
    private int maxUpstreamBytes = 65536;

    /**
     * Absolute difference (percentage points) between the observed share and
     * the weight-derived share that stops being "normal noise".
     */
    private double imbalanceWarnPoints = 5.0;

    public void validate() {
        if (nginxUpstreamConf == null || nginxUpstreamConf.isBlank()) {
            throw new IllegalStateException("lab.traffic-split.nginx-upstream-conf must not be blank; "
                    + "env LAB_TRAFFIC_SPLIT_NGINX_CONF");
        }
        if (upstreamName == null || !upstreamName.matches("[A-Za-z0-9_]{1,64}")) {
            throw new IllegalStateException("lab.traffic-split.upstream-name must match "
                    + "[A-Za-z0-9_]{1,64}; env LAB_TRAFFIC_SPLIT_UPSTREAM_NAME");
        }
        if (imbalanceWarnPoints < 0 || imbalanceWarnPoints > 100) {
            throw new IllegalStateException("lab.traffic-split.imbalance-warn-points must be 0..100 "
                    + "(was " + imbalanceWarnPoints + "); env LAB_TRAFFIC_SPLIT_IMBALANCE_WARN_POINTS");
        }
        if (appJob == null || !appJob.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalStateException("lab.traffic-split.app-job must match [A-Za-z0-9_-]{1,64}; "
                    + "env LAB_TRAFFIC_SPLIT_APP_JOB");
        }
        if (businessUriFilter == null || businessUriFilter.isBlank()) {
            throw new IllegalStateException("lab.traffic-split.business-uri-filter must not be blank; "
                    + "env LAB_TRAFFIC_SPLIT_BUSINESS_URI_FILTER");
        }
        if (maxUpstreamBytes < 1024 || maxUpstreamBytes > 1_048_576) {
            throw new IllegalStateException("lab.traffic-split.max-upstream-bytes must be 1024..1048576 "
                    + "(was " + maxUpstreamBytes + "); env LAB_TRAFFIC_SPLIT_MAX_UPSTREAM_BYTES");
        }
    }

    public String getNginxUpstreamConf() {
        return nginxUpstreamConf;
    }

    public void setNginxUpstreamConf(String nginxUpstreamConf) {
        this.nginxUpstreamConf = nginxUpstreamConf;
    }

    public String getUpstreamName() {
        return upstreamName;
    }

    public void setUpstreamName(String upstreamName) {
        this.upstreamName = upstreamName;
    }

    public double getImbalanceWarnPoints() {
        return imbalanceWarnPoints;
    }

    public void setImbalanceWarnPoints(double imbalanceWarnPoints) {
        this.imbalanceWarnPoints = imbalanceWarnPoints;
    }

    public String getAppJob() {
        return appJob;
    }

    public void setAppJob(String appJob) {
        this.appJob = appJob;
    }

    public String getBusinessUriFilter() {
        return businessUriFilter;
    }

    public void setBusinessUriFilter(String businessUriFilter) {
        this.businessUriFilter = businessUriFilter;
    }

    public int getMaxUpstreamBytes() {
        return maxUpstreamBytes;
    }

    public void setMaxUpstreamBytes(int maxUpstreamBytes) {
        this.maxUpstreamBytes = maxUpstreamBytes;
    }
}
