package com.javalearn.lab.chaos.topology;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage2 B5-01a topology contract configuration. Every Prometheus job / label
 * / instance suffix and every bound is externalized here so the console heat
 * view can be tuned through {@code LAB_TOPOLOGY_*} without a controller
 * rebuild. The Java defaults document the values used by the lab stack and
 * keep direct construction in tests safe.
 *
 * <p>The topology itself (which tiers and node counts exist) is deliberately
 * not request-driven: design §16.3 requires the full structure even when a
 * container is stopped. Only the enabled set varies per request/run.</p>
 */
@ConfigurationProperties(prefix = "lab.topology")
public class TopologyProperties {

    private static final String PROMQL_DURATION = "[0-9]+(ms|s|m|h|d|w|y)";
    private static final String NAME = "[A-Za-z0-9_.:-]{1,128}";
    private static final String LABEL_NAME = "[A-Za-z_][A-Za-z0-9_]{0,127}";
    private static final String POOL_NAME = "[A-Za-z0-9_.-]{1,64}";
    private static final String CONTAINER_NAME = "[A-Za-z0-9][A-Za-z0-9_.-]{0,62}";

    /** Range selector used by every rate()/histogram_quantile() query. */
    private String rateWindow = "1m";

    /** Topology answer cache; 0 disables it. Defaults to 2s for the 5s UI poll. */
    private int cacheSeconds = 2;

    /** Docker/compose container name prefix ({@code jl-}). */
    private String containerPrefix = "jl-";

    /** Legacy app fallback; Redis and MySQL counts are separate below. */
    private int clusterNodes = 3;

    /** Redis data nodes and the matching Sentinel count (B5-05: 2 + 2). */
    private int redisNodes = 2;

    /** MySQL Group Replication node count (quorum needs 3). */
    private int mysqlNodes = 3;

    /** Prometheus job scraping the JDK 21 application tier. */
    private String appJob = "seckill-app";

    /** Instance label suffix; the label value is {@code <service><suffix>}. */
    private String appInstanceSuffix = ":8080";

    /** JVM memory area used by the heap used/limit queries. */
    private String appHeapArea = "heap";

    /** Micrometer executor tag carrying the business thread pool. */
    private String poolName = "business";

    /** Redis exporter job and the label that carries {@code redis-1..3}. */
    private String redisJob = "redis-exporter-ha";
    private String redisNodeLabel = "node";

    /** MySQL exporter job and the label that carries {@code mysql-1..3}. */
    private String mysqlJob = "mysqld-exporter-ha";
    private String mysqlMemberLabel = "member";

    /** Nginx exporter job (connections active). */
    private String nginxJob = "nginx-exporter";

    /** RabbitMQ detailed queue job (messages ready). */
    private String rabbitJob = "rabbitmq-queue";

    /**
     * Exporter/sidecar containers that stay hidden unless
     * {@code includeSidecars=true}. Missing names are still returned as
     * NOT_RUNNING so the toggle never changes the shape unexpectedly.
     */
    private List<String> sidecars = new ArrayList<>(List.of(
            "jl-nginx-exporter",
            "jl-redis-exporter",
            "jl-redis-1-exporter",
            "jl-redis-2-exporter",
            "jl-redis-3-exporter",
            "jl-mysqld-exporter",
            "jl-mysql-1-exporter",
            "jl-mysql-2-exporter",
            "jl-mysql-3-exporter"));

    @PostConstruct
    public void validate() {
        requireMatch(rateWindow, PROMQL_DURATION, "lab.topology.rate-window",
                "LAB_TOPOLOGY_RATE_WINDOW");
        if (cacheSeconds < 0 || cacheSeconds > 300) {
            throw new IllegalStateException("lab.topology.cache-seconds must be between 0 and 300"
                    + " (was " + cacheSeconds + "); env LAB_TOPOLOGY_CACHE_SECONDS");
        }
        if (containerPrefix == null || containerPrefix.isBlank()
                || containerPrefix.contains("/") || containerPrefix.contains(" ")) {
            throw new IllegalStateException("lab.topology.container-prefix must be a non-blank"
                    + " container-name prefix (was '" + containerPrefix
                    + "'); env LAB_TOPOLOGY_CONTAINER_PREFIX");
        }
        if (clusterNodes < 1 || clusterNodes > 9) {
            throw new IllegalStateException("lab.topology.cluster-nodes must be between 1 and 9"
                    + " (was " + clusterNodes + "); env LAB_TOPOLOGY_CLUSTER_NODES");
        }
        if (redisNodes < 1 || redisNodes > 9) {
            throw new IllegalStateException("lab.topology.redis-nodes must be between 1 and 9"
                    + " (was " + redisNodes + "); env LAB_TOPOLOGY_REDIS_NODES");
        }
        if (mysqlNodes < 1 || mysqlNodes > 9) {
            throw new IllegalStateException("lab.topology.mysql-nodes must be between 1 and 9"
                    + " (was " + mysqlNodes + "); env LAB_TOPOLOGY_MYSQL_NODES");
        }
        requireMatch(appJob, NAME, "lab.topology.app-job", "LAB_TOPOLOGY_APP_JOB");
        if (appInstanceSuffix == null || !appInstanceSuffix.matches("(:[0-9]{1,5})?")) {
            throw new IllegalStateException("lab.topology.app-instance-suffix must be blank or"
                    + " ':port' (was '" + appInstanceSuffix
                    + "'); env LAB_TOPOLOGY_APP_INSTANCE_SUFFIX");
        }
        requireMatch(appHeapArea, "[A-Za-z0-9_.-]{1,64}", "lab.topology.app-heap-area",
                "LAB_TOPOLOGY_APP_HEAP_AREA");
        requireMatch(poolName, POOL_NAME, "lab.topology.pool-name", "LAB_TOPOLOGY_POOL_NAME");
        requireMatch(redisJob, NAME, "lab.topology.redis-job", "LAB_TOPOLOGY_REDIS_JOB");
        requireMatch(redisNodeLabel, LABEL_NAME, "lab.topology.redis-node-label",
                "LAB_TOPOLOGY_REDIS_NODE_LABEL");
        requireMatch(mysqlJob, NAME, "lab.topology.mysql-job", "LAB_TOPOLOGY_MYSQL_JOB");
        requireMatch(mysqlMemberLabel, LABEL_NAME, "lab.topology.mysql-member-label",
                "LAB_TOPOLOGY_MYSQL_MEMBER_LABEL");
        requireMatch(nginxJob, NAME, "lab.topology.nginx-job", "LAB_TOPOLOGY_NGINX_JOB");
        requireMatch(rabbitJob, NAME, "lab.topology.rabbit-job", "LAB_TOPOLOGY_RABBIT_JOB");
        if (sidecars == null) {
            throw new IllegalStateException("lab.topology.sidecars must not be null;"
                    + " env LAB_TOPOLOGY_SIDECARS");
        }
        List<String> seen = new ArrayList<>();
        for (String sidecar : sidecars) {
            if (sidecar == null || !sidecar.matches(CONTAINER_NAME)) {
                throw new IllegalStateException("lab.topology.sidecars entry '" + sidecar
                        + "' must be a container name; env LAB_TOPOLOGY_SIDECARS");
            }
            if (seen.contains(sidecar)) {
                throw new IllegalStateException("lab.topology.sidecars repeats '" + sidecar
                        + "'; env LAB_TOPOLOGY_SIDECARS");
            }
            seen.add(sidecar);
        }
    }

    private static void requireMatch(String value, String pattern, String property, String env) {
        if (value == null || !value.matches(pattern)) {
            throw new IllegalStateException(property + " must match " + pattern
                    + " (was '" + value + "'); env " + env);
        }
    }

    public String getRateWindow() {
        return rateWindow;
    }

    public void setRateWindow(String rateWindow) {
        this.rateWindow = rateWindow;
    }

    public int getCacheSeconds() {
        return cacheSeconds;
    }

    public void setCacheSeconds(int cacheSeconds) {
        this.cacheSeconds = cacheSeconds;
    }

    public String getContainerPrefix() {
        return containerPrefix;
    }

    public void setContainerPrefix(String containerPrefix) {
        this.containerPrefix = containerPrefix;
    }

    public int getRedisNodes() {
        return redisNodes;
    }

    public void setRedisNodes(int redisNodes) {
        this.redisNodes = redisNodes;
    }

    public int getMysqlNodes() {
        return mysqlNodes;
    }

    public void setMysqlNodes(int mysqlNodes) {
        this.mysqlNodes = mysqlNodes;
    }

    public int getClusterNodes() {
        return clusterNodes;
    }

    public void setClusterNodes(int clusterNodes) {
        this.clusterNodes = clusterNodes;
    }

    public String getAppJob() {
        return appJob;
    }

    public void setAppJob(String appJob) {
        this.appJob = appJob;
    }

    public String getAppInstanceSuffix() {
        return appInstanceSuffix;
    }

    public void setAppInstanceSuffix(String appInstanceSuffix) {
        this.appInstanceSuffix = appInstanceSuffix;
    }

    public String getAppHeapArea() {
        return appHeapArea;
    }

    public void setAppHeapArea(String appHeapArea) {
        this.appHeapArea = appHeapArea;
    }

    public String getPoolName() {
        return poolName;
    }

    public void setPoolName(String poolName) {
        this.poolName = poolName;
    }

    public String getRedisJob() {
        return redisJob;
    }

    public void setRedisJob(String redisJob) {
        this.redisJob = redisJob;
    }

    public String getRedisNodeLabel() {
        return redisNodeLabel;
    }

    public void setRedisNodeLabel(String redisNodeLabel) {
        this.redisNodeLabel = redisNodeLabel;
    }

    public String getMysqlJob() {
        return mysqlJob;
    }

    public void setMysqlJob(String mysqlJob) {
        this.mysqlJob = mysqlJob;
    }

    public String getMysqlMemberLabel() {
        return mysqlMemberLabel;
    }

    public void setMysqlMemberLabel(String mysqlMemberLabel) {
        this.mysqlMemberLabel = mysqlMemberLabel;
    }

    public String getNginxJob() {
        return nginxJob;
    }

    public void setNginxJob(String nginxJob) {
        this.nginxJob = nginxJob;
    }

    public String getRabbitJob() {
        return rabbitJob;
    }

    public void setRabbitJob(String rabbitJob) {
        this.rabbitJob = rabbitJob;
    }

    public List<String> getSidecars() {
        return sidecars;
    }

    public void setSidecars(List<String> sidecars) {
        this.sidecars = sidecars;
    }
}
