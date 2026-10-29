package com.javalearn.lab.chaos.reconcile;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * Stage2 B3-04 run-scoped business reconciliation configuration.
 *
 * <p>The business database is the application's {@code seckill} schema, read
 * through a dedicated, read-only Hikari pool. The URL follows the same
 * standalone/router switch as the ledger ({@code MYSQL_MODE}); the account
 * defaults to the stack's business account ({@code seckill/seckill}) and never
 * appears in a URL or a log line.</p>
 *
 * <p>Every key is declared in {@code application.yml} with an
 * {@code ${ENV:default}} placeholder and mirrored in the compose service
 * environment. The Java defaults below only keep direct construction (tests,
 * tools) safe; {@link #validate()} names both the property path and the env
 * variable so a bad deployment value is easy to fix.</p>
 */
@ConfigurationProperties(prefix = "lab.reconcile")
public class ReconcileProperties {

    public static final String MODE_STANDALONE = "standalone";
    public static final String MODE_ROUTER = "router";

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");

    /** False answers every reconcile call DEGRADED and captures no baseline. */
    private boolean enabled = true;

    /** {@code standalone} (jl-mysql:3306) or {@code router} (mysql-router:6446). */
    private String mysqlMode = MODE_STANDALONE;

    /**
     * Optional active business URL override ({@code LAB_RECONCILE_BUSINESS_URL}).
     * Blank keeps the mode-based {@link #standaloneUrl}/{@link #routerUrl}.
     */
    private String businessUrl = "";

    /** Business JDBC URL used when {@link #mysqlMode} is standalone. */
    private String standaloneUrl =
            "jdbc:mysql://mysql:3306/seckill?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";

    /** Business JDBC URL used when {@link #mysqlMode} is router (db-ha, port 6446). */
    private String routerUrl =
            "jdbc:mysql://mysql-router:6446/seckill?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";

    /** Business schema; the reader only issues SELECTs against it. */
    private String schema = "seckill";

    /** Business account; the compose stack creates {@code seckill}. */
    private String user = "seckill";

    /** Business password; defaults to the compose {@code MYSQL_PASSWORD}. Never logged. */
    private String password = "seckill";

    /** Per-statement JDBC timeout for the read-only snapshot queries. */
    private int queryTimeoutSeconds = 5;

    /** Total budget for {@code seckill.order.queue} to reach messages_ready == 0. */
    private int mqDrainTimeoutSeconds = 10;

    /** Per-request timeout of one RabbitMQ management API call. */
    private int mqRequestTimeoutSeconds = 2;

    /** Poll interval while waiting for the queue to drain. */
    private int mqPollIntervalMillis = 500;

    /** Upper bound of sampled product/duplicate rows in one snapshot. */
    private int maxSampleRows = 20;

    /** RabbitMQ management API base URL (compose network, never host-only). */
    private String mqManagementUrl = "http://rabbitmq:15672";

    /** RabbitMQ vhost of the order queue. */
    private String mqVhost = "/";

    /** Durable order queue consumed by the application tier. */
    private String mqQueue = "seckill.order.queue";

    /** RabbitMQ management account. */
    private String mqUser = "lab";

    /** RabbitMQ management password. Never logged. */
    private String mqPassword = "lab";

    /** Startup validation; messages name the property path and env variable. */
    public void validate() {
        boolean router = MODE_ROUTER.equals(mysqlMode);
        if (!MODE_STANDALONE.equals(mysqlMode) && !router) {
            throw new IllegalStateException("lab.reconcile.mysql-mode must be '"
                    + MODE_STANDALONE + "' or '" + MODE_ROUTER + "' (was '" + mysqlMode
                    + "'); env LAB_RECONCILE_MYSQL_MODE");
        }
        if (schema == null || !IDENTIFIER.matcher(schema).matches()) {
            throw new IllegalStateException("lab.reconcile.schema must match " + IDENTIFIER
                    + " (was '" + schema + "'); env LAB_RECONCILE_SCHEMA");
        }
        requireText(router ? routerUrl : standaloneUrl,
                router ? "lab.reconcile.router-url" : "lab.reconcile.standalone-url",
                router ? "LAB_RECONCILE_ROUTER_URL" : "LAB_RECONCILE_STANDALONE_URL");
        if (businessUrl != null && !businessUrl.isBlank() && !businessUrl.startsWith("jdbc:mysql://")) {
            throw new IllegalStateException("lab.reconcile.business-url must be a jdbc:mysql:// URL "
                    + "(was '" + businessUrl + "'); env LAB_RECONCILE_BUSINESS_URL");
        }
        requireText(user, "lab.reconcile.user", "LAB_RECONCILE_BUSINESS_USER");
        requireText(password, "lab.reconcile.password", "LAB_RECONCILE_BUSINESS_PASSWORD");
        range(queryTimeoutSeconds, 1, 300, "lab.reconcile.query-timeout-seconds",
                "LAB_RECONCILE_QUERY_TIMEOUT_SECONDS");
        range(mqDrainTimeoutSeconds, 1, 300, "lab.reconcile.mq-drain-timeout-seconds",
                "LAB_RECONCILE_MQ_DRAIN_TIMEOUT_SECONDS");
        range(mqRequestTimeoutSeconds, 1, 60, "lab.reconcile.mq-request-timeout-seconds",
                "LAB_RECONCILE_MQ_REQUEST_TIMEOUT_SECONDS");
        if (mqRequestTimeoutSeconds > mqDrainTimeoutSeconds) {
            throw new IllegalStateException("lab.reconcile.mq-request-timeout-seconds must not exceed "
                    + "lab.reconcile.mq-drain-timeout-seconds (was " + mqRequestTimeoutSeconds
                    + " > " + mqDrainTimeoutSeconds + "); env LAB_RECONCILE_MQ_REQUEST_TIMEOUT_SECONDS");
        }
        range(mqPollIntervalMillis, 50, 10_000, "lab.reconcile.mq-poll-interval-millis",
                "LAB_RECONCILE_MQ_POLL_INTERVAL_MILLIS");
        range(maxSampleRows, 1, 100, "lab.reconcile.max-sample-rows",
                "LAB_RECONCILE_MAX_SAMPLE_ROWS");
        requireHttpUrl(mqManagementUrl, "lab.reconcile.mq-management-url",
                "LAB_RECONCILE_MQ_MANAGEMENT_URL");
        requireText(mqVhost, "lab.reconcile.mq-vhost", "LAB_RECONCILE_MQ_VHOST");
        requireText(mqQueue, "lab.reconcile.mq-queue", "LAB_RECONCILE_MQ_QUEUE");
        requireText(mqUser, "lab.reconcile.mq-user", "LAB_RECONCILE_MQ_USER");
        requireText(mqPassword, "lab.reconcile.mq-password", "LAB_RECONCILE_MQ_PASSWORD");
    }

    public boolean isRouter() {
        return MODE_ROUTER.equals(mysqlMode);
    }

    public String activeUrl() {
        if (businessUrl != null && !businessUrl.isBlank()) {
            return businessUrl;
        }
        return isRouter() ? routerUrl : standaloneUrl;
    }

    private static void requireText(String value, String property, String env) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " must not be blank; env " + env);
        }
    }

    private static void requireHttpUrl(String value, String property, String env) {
        requireText(value, property, env);
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(property + " must be a valid http(s) URL; env " + env);
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalStateException(property + " must use http or https; env " + env);
        }
    }

    private static void range(int value, int min, int max, String property, String env) {
        if (value < min || value > max) {
            throw new IllegalStateException(property + " must be between " + min + " and " + max
                    + " (was " + value + "); env " + env);
        }
    }

    /** Redacted rendering: the two passwords never leave the process. */
    @Override
    public String toString() {
        return "ReconcileProperties{enabled=" + enabled + ", mysqlMode='" + mysqlMode
                + "', businessUrl='" + businessUrl + "', schema='" + schema + "', user='" + user
                + "', password='***'"
                + ", queryTimeoutSeconds=" + queryTimeoutSeconds
                + ", mqDrainTimeoutSeconds=" + mqDrainTimeoutSeconds
                + ", mqRequestTimeoutSeconds=" + mqRequestTimeoutSeconds
                + ", mqPollIntervalMillis=" + mqPollIntervalMillis
                + ", maxSampleRows=" + maxSampleRows + ", mqManagementUrl='" + mqManagementUrl
                + "', mqVhost='" + mqVhost + "', mqQueue='" + mqQueue + "', mqUser='" + mqUser
                + "', mqPassword='***'}";
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getMysqlMode() {
        return mysqlMode;
    }

    public void setMysqlMode(String mysqlMode) {
        this.mysqlMode = mysqlMode;
    }

    public String getBusinessUrl() {
        return businessUrl;
    }

    public void setBusinessUrl(String businessUrl) {
        this.businessUrl = businessUrl;
    }

    public String getStandaloneUrl() {
        return standaloneUrl;
    }

    public void setStandaloneUrl(String standaloneUrl) {
        this.standaloneUrl = standaloneUrl;
    }

    public String getRouterUrl() {
        return routerUrl;
    }

    public void setRouterUrl(String routerUrl) {
        this.routerUrl = routerUrl;
    }

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
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

    public int getQueryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }

    public void setQueryTimeoutSeconds(int queryTimeoutSeconds) {
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    public int getMqDrainTimeoutSeconds() {
        return mqDrainTimeoutSeconds;
    }

    public void setMqDrainTimeoutSeconds(int mqDrainTimeoutSeconds) {
        this.mqDrainTimeoutSeconds = mqDrainTimeoutSeconds;
    }

    public int getMqRequestTimeoutSeconds() {
        return mqRequestTimeoutSeconds;
    }

    public void setMqRequestTimeoutSeconds(int mqRequestTimeoutSeconds) {
        this.mqRequestTimeoutSeconds = mqRequestTimeoutSeconds;
    }

    public int getMqPollIntervalMillis() {
        return mqPollIntervalMillis;
    }

    public void setMqPollIntervalMillis(int mqPollIntervalMillis) {
        this.mqPollIntervalMillis = mqPollIntervalMillis;
    }

    public int getMaxSampleRows() {
        return maxSampleRows;
    }

    public void setMaxSampleRows(int maxSampleRows) {
        this.maxSampleRows = maxSampleRows;
    }

    public String getMqManagementUrl() {
        return mqManagementUrl;
    }

    public void setMqManagementUrl(String mqManagementUrl) {
        this.mqManagementUrl = mqManagementUrl;
    }

    public String getMqVhost() {
        return mqVhost;
    }

    public void setMqVhost(String mqVhost) {
        this.mqVhost = mqVhost;
    }

    public String getMqQueue() {
        return mqQueue;
    }

    public void setMqQueue(String mqQueue) {
        this.mqQueue = mqQueue;
    }

    public String getMqUser() {
        return mqUser;
    }

    public void setMqUser(String mqUser) {
        this.mqUser = mqUser;
    }

    public String getMqPassword() {
        return mqPassword;
    }

    public void setMqPassword(String mqPassword) {
        this.mqPassword = mqPassword;
    }
}
