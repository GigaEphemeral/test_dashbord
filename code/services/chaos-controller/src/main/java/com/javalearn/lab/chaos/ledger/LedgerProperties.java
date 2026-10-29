package com.javalearn.lab.chaos.ledger;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Stage2 B1-01 ledger configuration. Every tunable is declared in
 * {@code application.yml} with a {@code ${ENV:default}} placeholder and mirrored
 * in the compose service environment + {@code .env.example}; the Java defaults
 * below only describe the documented default so direct construction (tests,
 * tools) is safe. Validation names both the property path and the env variable
 * so a bad deployment value is easy to fix.
 *
 * <p>Account choice (decided for this step): the ledger connects as
 * {@code root}. The business account {@code seckill} only owns
 * {@code seckill.*} in standalone and in the cluster, so it cannot
 * {@code CREATE DATABASE lab_ledger}; creating a second account would mean
 * touching the MySQL init scripts. The controller already holds the Docker
 * socket (root-equivalent), so this does not widen the lab boundary. No
 * credential is ever put into a JDBC URL or logged.</p>
 */
@ConfigurationProperties(prefix = "lab.ledger")
public class LedgerProperties {

    public static final String MODE_STANDALONE = "standalone";
    public static final String MODE_ROUTER = "router";

    private static final String IDENTIFIER = "[A-Za-z0-9_]+";

    /** False keeps the controller up with the ledger endpoints answering 503. */
    private boolean enabled = true;

    /** {@code standalone} (jl-mysql:3306) or {@code router} (mysql-router:6446). */
    private String mysqlMode = "standalone";

    /** Ledger JDBC URL used when {@link #mysqlMode} is standalone. */
    private String standaloneUrl =
            "jdbc:mysql://mysql:3306/lab_ledger?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";

    /** Ledger JDBC URL used when {@link #mysqlMode} is router (db-ha, port 6446). */
    private String routerUrl =
            "jdbc:mysql://mysql-router:6446/lab_ledger?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";

    /** Dedicated ledger database, physically separate from the business {@code seckill}. */
    private String schema = "lab_ledger";

    /** Ledger account; root because the business account cannot CREATE DATABASE. */
    private String user = "root";

    /** Ledger password; defaults to the stack's MYSQL_ROOT_PASSWORD. Never logged. */
    private String password = "labroot";

    /** Git commit recorded on every run; {@code unknown} when not injected. */
    private String sourceCommit = "unknown";

    /** Operator recorded in audit rows; the local lab has no user directory. */
    private String operator = "local";

    private int maxPoolSize = 4;

    private int minIdle = 1;

    private int connectionTimeoutMs = 3000;

    private int validationTimeoutMs = 1000;

    /** Delay before retrying CREATE DATABASE / Flyway after a bootstrap failure. */
    private int bootstrapRetrySeconds = 15;

    /** Name of the single daemon thread that owns bootstrap, replay and retention. */
    private String bootstrapThreadName = "ledger-bootstrap";

    /** Flyway migration location; classpath db/ledger is the versioned DDL. */
    private String flywayLocations = "classpath:db/ledger";

    /** Directory for events that could not be written while MySQL was down. */
    private String stashDir = "/app/ledger-stash";

    /** Upper bound of stash files; the oldest is dropped (and WARNed) beyond it. */
    private int stashMaxFiles = 500;

    /** Upper bound of one serialized stash event (bytes). */
    private int stashMaxBytes = 262144;

    /** Stash replay period; replay is ordered and stops at the first failure. */
    private int stashReplayIntervalSeconds = 20;

    /** Keep the newest N runs; older runs are deleted and children cascade. */
    private int retentionRuns = 50;

    private int retentionIntervalMinutes = 30;

    /** Restart scan: RUNNING/STOPPING/VERIFYING -> UNKNOWN on startup. */
    private boolean recoveryEnabled = true;

    /** List page size when {@code limit} is absent. */
    private int queryDefaultLimit = 50;

    /** Hard cap for {@code limit}; callers cannot widen it at runtime. */
    private int queryMaxLimit = 200;

    /** Max bytes of one JSON payload (run params, snapshots). */
    private int maxPayloadBytes = 65536;

    /** Attempts to allocate a unique {@code run-yyyyMMdd-NNN} id. */
    private int runIdMaxAttempts = 10;

    /** B3-00a: upper bound of enabled Java nodes in one run draft. */
    private int maxNodes = 3;

    /** B3-00a: upper bound of fault-timeline entries in one run draft. */
    private int maxFaultItems = 16;

    /** B1-02: executor and synchronous wait budget for the operation contract. */
    private final Operation operation = new Operation();

    /** B1-02: idempotency replay window. */
    private final Idempotency idempotency = new Idempotency();

    /** B1-02: single-writer run environment lock. */
    private final EnvLock envLock = new EnvLock();

    /** Startup validation; messages name the property path and env variable. */
    public void validate() {
        boolean router = MODE_ROUTER.equals(mysqlMode);
        if (!MODE_STANDALONE.equals(mysqlMode) && !router) {
            throw new IllegalStateException("lab.ledger.mysql-mode must be '" + MODE_STANDALONE
                    + "' or '" + MODE_ROUTER + "' (was '" + mysqlMode + "'); env LAB_LEDGER_MYSQL_MODE");
        }
        if (schema == null || !schema.matches(IDENTIFIER)) {
            throw new IllegalStateException("lab.ledger.schema must match " + IDENTIFIER
                    + " (was '" + schema + "'); env LAB_LEDGER_SCHEMA");
        }
        requireText(router ? routerUrl : standaloneUrl,
                router ? "lab.ledger.router-url" : "lab.ledger.standalone-url",
                router ? "LAB_LEDGER_ROUTER_URL" : "LAB_LEDGER_STANDALONE_URL");
        requireText(user, "lab.ledger.user", "LAB_LEDGER_USER");
        requireText(password, "lab.ledger.password", "LAB_LEDGER_PASSWORD");
        requireText(sourceCommit, "lab.ledger.source-commit", "LAB_SOURCE_COMMIT");
        requireText(operator, "lab.ledger.operator", "LAB_LEDGER_OPERATOR");
        requireText(flywayLocations, "lab.ledger.flyway-locations", "LAB_LEDGER_FLYWAY_LOCATIONS");
        requireText(stashDir, "lab.ledger.stash-dir", "LAB_LEDGER_STASH_DIR");
        requireText(bootstrapThreadName, "lab.ledger.bootstrap-thread-name",
                "LAB_LEDGER_BOOTSTRAP_THREAD_NAME");
        range(maxPoolSize, 1, 64, "lab.ledger.max-pool-size", "LAB_LEDGER_MAX_POOL_SIZE");
        range(minIdle, 0, maxPoolSize, "lab.ledger.min-idle", "LAB_LEDGER_MIN_IDLE");
        range(connectionTimeoutMs, 250, 60000, "lab.ledger.connection-timeout-ms",
                "LAB_LEDGER_CONNECTION_TIMEOUT_MS");
        range(validationTimeoutMs, 250, connectionTimeoutMs, "lab.ledger.validation-timeout-ms",
                "LAB_LEDGER_VALIDATION_TIMEOUT_MS");
        range(bootstrapRetrySeconds, 1, 3600, "lab.ledger.bootstrap-retry-seconds",
                "LAB_LEDGER_BOOTSTRAP_RETRY_SECONDS");
        range(stashMaxFiles, 1, 100000, "lab.ledger.stash-max-files", "LAB_LEDGER_STASH_MAX_FILES");
        range(stashMaxBytes, 1024, 10_485_760, "lab.ledger.stash-max-bytes", "LAB_LEDGER_STASH_MAX_BYTES");
        range(stashReplayIntervalSeconds, 1, 3600, "lab.ledger.stash-replay-interval-seconds",
                "LAB_LEDGER_STASH_REPLAY_INTERVAL_SECONDS");
        range(retentionRuns, 1, 10000, "lab.ledger.retention-runs", "LAB_LEDGER_RETENTION_RUNS");
        range(retentionIntervalMinutes, 1, 1440, "lab.ledger.retention-interval-minutes",
                "LAB_LEDGER_RETENTION_INTERVAL_MINUTES");
        range(queryDefaultLimit, 1, queryMaxLimit, "lab.ledger.query-default-limit",
                "LAB_LEDGER_QUERY_DEFAULT_LIMIT");
        range(queryMaxLimit, 1, 1000, "lab.ledger.query-max-limit", "LAB_LEDGER_QUERY_MAX_LIMIT");
        range(maxPayloadBytes, 1024, 10_485_760, "lab.ledger.max-payload-bytes",
                "LAB_LEDGER_MAX_PAYLOAD_BYTES");
        range(runIdMaxAttempts, 1, 100, "lab.ledger.run-id-max-attempts", "LAB_LEDGER_RUN_ID_MAX_ATTEMPTS");
        range(maxNodes, 1, 64, "lab.ledger.max-nodes", "LAB_LEDGER_MAX_NODES");
        range(maxFaultItems, 1, 256, "lab.ledger.max-fault-items", "LAB_LEDGER_MAX_FAULT_ITEMS");
        requireText(operation.getThreadName(), "lab.ledger.operation.thread-name",
                "LAB_LEDGER_OPERATION_THREAD_NAME");
        range(operation.getPoolSize(), 1, 32, "lab.ledger.operation.pool-size",
                "LAB_LEDGER_OPERATION_POOL_SIZE");
        range(operation.getSyncWaitMs(), 0, 600_000, "lab.ledger.operation.sync-wait-ms",
                "LAB_LEDGER_OPERATION_SYNC_WAIT_MS");
        range(idempotency.getWindowSeconds(), 1, 86_400, "lab.ledger.idempotency.window-seconds",
                "LAB_LEDGER_IDEMPOTENCY_WINDOW_SECONDS");
        requireText(envLock.getKey(), "lab.ledger.env-lock.key", "LAB_LEDGER_ENV_LOCK_KEY");
        range(envLock.getTtlSeconds(), 1, 86_400, "lab.ledger.env-lock.ttl-seconds",
                "LAB_LEDGER_ENV_LOCK_TTL_SECONDS");
        range(envLock.getAcquireAttempts(), 1, 100, "lab.ledger.env-lock.acquire-attempts",
                "LAB_LEDGER_ENV_LOCK_ACQUIRE_ATTEMPTS");
    }

    /** Operation executor + synchronous wait budget (B1-02). */
    public static class Operation {

        /** Thread-name prefix of the dedicated daemon pool (not the chaos TTL scheduler). */
        private String threadName = "ledger-operation";

        /** Worker threads; raise it when slow fault actions can overlap. */
        private int poolSize = 2;

        /** Budget before the API answers 202 PENDING_CONFIRMATION and keeps working. */
        private int syncWaitMs = 3000;

        public String getThreadName() {
            return threadName;
        }

        public void setThreadName(String threadName) {
            this.threadName = threadName;
        }

        public int getPoolSize() {
            return poolSize;
        }

        public void setPoolSize(int poolSize) {
            this.poolSize = poolSize;
        }

        public int getSyncWaitMs() {
            return syncWaitMs;
        }

        public void setSyncWaitMs(int syncWaitMs) {
            this.syncWaitMs = syncWaitMs;
        }
    }

    /** Idempotency replay window (B1-02). */
    public static class Idempotency {

        /** Same key + action + target replays the original operation inside this window. */
        private int windowSeconds = 600;

        public int getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(int windowSeconds) {
            this.windowSeconds = windowSeconds;
        }
    }

    /** Single active write-run lock (B1-02). */
    public static class EnvLock {

        /** Primary key of the {@code lab_env_lock} row. */
        private String key = "env:write-run";

        /** Takeover escape hatch for a dead holder; also the lease a run holds. */
        private int ttlSeconds = 1800;

        /** Retries when the lock row keeps changing between insert and read. */
        private int acquireAttempts = 3;

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public int getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(int ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }

        public int getAcquireAttempts() {
            return acquireAttempts;
        }

        public void setAcquireAttempts(int acquireAttempts) {
            this.acquireAttempts = acquireAttempts;
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

    public boolean isRouter() {
        return MODE_ROUTER.equals(mysqlMode);
    }

    public String activeUrl() {
        return isRouter() ? routerUrl : standaloneUrl;
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

    public String getSourceCommit() {
        return sourceCommit;
    }

    public void setSourceCommit(String sourceCommit) {
        this.sourceCommit = sourceCommit;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    public void setMaxPoolSize(int maxPoolSize) {
        this.maxPoolSize = maxPoolSize;
    }

    public int getMinIdle() {
        return minIdle;
    }

    public void setMinIdle(int minIdle) {
        this.minIdle = minIdle;
    }

    public int getConnectionTimeoutMs() {
        return connectionTimeoutMs;
    }

    public void setConnectionTimeoutMs(int connectionTimeoutMs) {
        this.connectionTimeoutMs = connectionTimeoutMs;
    }

    public int getValidationTimeoutMs() {
        return validationTimeoutMs;
    }

    public void setValidationTimeoutMs(int validationTimeoutMs) {
        this.validationTimeoutMs = validationTimeoutMs;
    }

    public int getBootstrapRetrySeconds() {
        return bootstrapRetrySeconds;
    }

    public void setBootstrapRetrySeconds(int bootstrapRetrySeconds) {
        this.bootstrapRetrySeconds = bootstrapRetrySeconds;
    }

    public String getBootstrapThreadName() {
        return bootstrapThreadName;
    }

    public void setBootstrapThreadName(String bootstrapThreadName) {
        this.bootstrapThreadName = bootstrapThreadName;
    }

    public String getFlywayLocations() {
        return flywayLocations;
    }

    public void setFlywayLocations(String flywayLocations) {
        this.flywayLocations = flywayLocations;
    }

    public String getStashDir() {
        return stashDir;
    }

    public void setStashDir(String stashDir) {
        this.stashDir = stashDir;
    }

    public int getStashMaxFiles() {
        return stashMaxFiles;
    }

    public void setStashMaxFiles(int stashMaxFiles) {
        this.stashMaxFiles = stashMaxFiles;
    }

    public int getStashMaxBytes() {
        return stashMaxBytes;
    }

    public void setStashMaxBytes(int stashMaxBytes) {
        this.stashMaxBytes = stashMaxBytes;
    }

    public int getStashReplayIntervalSeconds() {
        return stashReplayIntervalSeconds;
    }

    public void setStashReplayIntervalSeconds(int stashReplayIntervalSeconds) {
        this.stashReplayIntervalSeconds = stashReplayIntervalSeconds;
    }

    public int getRetentionRuns() {
        return retentionRuns;
    }

    public void setRetentionRuns(int retentionRuns) {
        this.retentionRuns = retentionRuns;
    }

    public int getRetentionIntervalMinutes() {
        return retentionIntervalMinutes;
    }

    public void setRetentionIntervalMinutes(int retentionIntervalMinutes) {
        this.retentionIntervalMinutes = retentionIntervalMinutes;
    }

    public boolean isRecoveryEnabled() {
        return recoveryEnabled;
    }

    public void setRecoveryEnabled(boolean recoveryEnabled) {
        this.recoveryEnabled = recoveryEnabled;
    }

    public int getQueryDefaultLimit() {
        return queryDefaultLimit;
    }

    public void setQueryDefaultLimit(int queryDefaultLimit) {
        this.queryDefaultLimit = queryDefaultLimit;
    }

    public int getQueryMaxLimit() {
        return queryMaxLimit;
    }

    public void setQueryMaxLimit(int queryMaxLimit) {
        this.queryMaxLimit = queryMaxLimit;
    }

    public int getMaxPayloadBytes() {
        return maxPayloadBytes;
    }

    public void setMaxPayloadBytes(int maxPayloadBytes) {
        this.maxPayloadBytes = maxPayloadBytes;
    }

    public int getRunIdMaxAttempts() {
        return runIdMaxAttempts;
    }

    public void setRunIdMaxAttempts(int runIdMaxAttempts) {
        this.runIdMaxAttempts = runIdMaxAttempts;
    }

    public int getMaxNodes() {
        return maxNodes;
    }

    public void setMaxNodes(int maxNodes) {
        this.maxNodes = maxNodes;
    }

    public int getMaxFaultItems() {
        return maxFaultItems;
    }

    public void setMaxFaultItems(int maxFaultItems) {
        this.maxFaultItems = maxFaultItems;
    }

    public Operation getOperation() {
        return operation;
    }

    public Idempotency getIdempotency() {
        return idempotency;
    }

    public EnvLock getEnvLock() {
        return envLock;
    }
}
