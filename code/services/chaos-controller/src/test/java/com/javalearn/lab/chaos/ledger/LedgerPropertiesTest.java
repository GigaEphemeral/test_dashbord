package com.javalearn.lab.chaos.ledger;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Binds the real {@code application.yml} (not a hand-built map) so the defaults
 * tested here are the ones the controller ships, then proves env-style override
 * precedence and typed startup validation.
 */
class LedgerPropertiesTest {

    @Test
    void defaultsComeFromApplicationYml() throws IOException {
        LedgerProperties properties = bind(Map.of());

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getMysqlMode()).isEqualTo("standalone");
        assertThat(properties.getStandaloneUrl()).isEqualTo(
                "jdbc:mysql://mysql:3306/lab_ledger?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
        assertThat(properties.getRouterUrl()).isEqualTo(
                "jdbc:mysql://mysql-router:6446/lab_ledger?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
        assertThat(properties.getSchema()).isEqualTo("lab_ledger");
        assertThat(properties.getUser()).isEqualTo("root");
        assertThat(properties.getPassword()).isEqualTo("labroot");
        assertThat(properties.getSourceCommit()).isEqualTo("unknown");
        assertThat(properties.getOperator()).isEqualTo("local");
        assertThat(properties.getMaxPoolSize()).isEqualTo(4);
        assertThat(properties.getMinIdle()).isEqualTo(1);
        assertThat(properties.getConnectionTimeoutMs()).isEqualTo(3000);
        assertThat(properties.getValidationTimeoutMs()).isEqualTo(1000);
        assertThat(properties.getRetentionRuns()).isEqualTo(50);
        assertThat(properties.getStashReplayIntervalSeconds()).isEqualTo(20);
        assertThat(properties.getQueryMaxLimit()).isEqualTo(200);
        assertThat(properties.getRunIdMaxAttempts()).isEqualTo(10);
        // B1-02 operation contract defaults.
        assertThat(properties.getOperation().getThreadName()).isEqualTo("ledger-operation");
        assertThat(properties.getOperation().getPoolSize()).isEqualTo(2);
        assertThat(properties.getOperation().getSyncWaitMs()).isEqualTo(3000);
        assertThat(properties.getIdempotency().getWindowSeconds()).isEqualTo(600);
        assertThat(properties.getEnvLock().getKey()).isEqualTo("env:write-run");
        assertThat(properties.getEnvLock().getTtlSeconds()).isEqualTo(1800);
        assertThat(properties.getEnvLock().getAcquireAttempts()).isEqualTo(3);
    }

    @Test
    void everyEnvKeyIsDeclaredInTheShippedYml() throws IOException {
        String yml = new ClassPathResource("application.yml").getContentAsString(StandardCharsets.UTF_8);

        assertThat(yml).contains(
                "LAB_LEDGER_ENABLED", "LAB_LEDGER_MYSQL_MODE", "LAB_LEDGER_STANDALONE_URL",
                "LAB_LEDGER_ROUTER_URL", "LAB_LEDGER_SCHEMA", "LAB_LEDGER_USER",
                "LAB_LEDGER_PASSWORD", "LAB_SOURCE_COMMIT", "LAB_LEDGER_OPERATOR",
                "LAB_LEDGER_MAX_POOL_SIZE", "LAB_LEDGER_MIN_IDLE",
                "LAB_LEDGER_CONNECTION_TIMEOUT_MS", "LAB_LEDGER_VALIDATION_TIMEOUT_MS",
                "LAB_LEDGER_BOOTSTRAP_RETRY_SECONDS", "LAB_LEDGER_BOOTSTRAP_THREAD_NAME",
                "LAB_LEDGER_FLYWAY_LOCATIONS", "LAB_LEDGER_STASH_DIR",
                "LAB_LEDGER_STASH_MAX_FILES", "LAB_LEDGER_STASH_MAX_BYTES",
                "LAB_LEDGER_STASH_REPLAY_INTERVAL_SECONDS", "LAB_LEDGER_RETENTION_RUNS",
                "LAB_LEDGER_RETENTION_INTERVAL_MINUTES", "LAB_LEDGER_RECOVERY_ENABLED",
                "LAB_LEDGER_QUERY_DEFAULT_LIMIT", "LAB_LEDGER_QUERY_MAX_LIMIT",
                "LAB_LEDGER_MAX_PAYLOAD_BYTES", "LAB_LEDGER_RUN_ID_MAX_ATTEMPTS",
                // B1-02: operation executor, idempotency window, environment lock.
                "LAB_LEDGER_OPERATION_THREAD_NAME", "LAB_LEDGER_OPERATION_POOL_SIZE",
                "LAB_LEDGER_OPERATION_SYNC_WAIT_MS", "LAB_LEDGER_IDEMPOTENCY_WINDOW_SECONDS",
                "LAB_LEDGER_ENV_LOCK_KEY", "LAB_LEDGER_ENV_LOCK_TTL_SECONDS",
                "LAB_LEDGER_ENV_LOCK_ACQUIRE_ATTEMPTS");
    }

    @Test
    void operationContractKeysOverrideFromTheEnvironment() throws IOException {
        LedgerProperties properties = bind(Map.of(
                "lab.ledger.operation.pool-size", "5",
                "lab.ledger.operation.sync-wait-ms", "250",
                "lab.ledger.idempotency.window-seconds", "30",
                "lab.ledger.env-lock.ttl-seconds", "45"));

        assertThat(properties.getOperation().getPoolSize()).isEqualTo(5);
        assertThat(properties.getOperation().getSyncWaitMs()).isEqualTo(250);
        assertThat(properties.getIdempotency().getWindowSeconds()).isEqualTo(30);
        assertThat(properties.getEnvLock().getTtlSeconds()).isEqualTo(45);
        properties.validate();
    }

    @Test
    void operationContractBoundsAreEnforced() {
        LedgerProperties zeroPool = valid();
        zeroPool.getOperation().setPoolSize(0);
        assertThatThrownBy(zeroPool::validate)
                .hasMessageContaining("lab.ledger.operation.pool-size")
                .hasMessageContaining("LAB_LEDGER_OPERATION_POOL_SIZE");

        LedgerProperties negativeWait = valid();
        negativeWait.getOperation().setSyncWaitMs(-1);
        assertThatThrownBy(negativeWait::validate)
                .hasMessageContaining("lab.ledger.operation.sync-wait-ms");

        LedgerProperties zeroWindow = valid();
        zeroWindow.getIdempotency().setWindowSeconds(0);
        assertThatThrownBy(zeroWindow::validate)
                .hasMessageContaining("lab.ledger.idempotency.window-seconds");

        LedgerProperties zeroTtl = valid();
        zeroTtl.getEnvLock().setTtlSeconds(0);
        assertThatThrownBy(zeroTtl::validate)
                .hasMessageContaining("lab.ledger.env-lock.ttl-seconds");

        LedgerProperties zeroAttempts = valid();
        zeroAttempts.getEnvLock().setAcquireAttempts(0);
        assertThatThrownBy(zeroAttempts::validate)
                .hasMessageContaining("lab.ledger.env-lock.acquire-attempts");
    }

    @Test
    void environmentStyleOverrideWinsBeforeBinding() throws IOException {
        LedgerProperties properties = bind(Map.of(
                "lab.ledger.mysql-mode", "router",
                "lab.ledger.max-pool-size", "7",
                "lab.ledger.password", "from-env",
                "lab.ledger.retention-runs", "12"));

        assertThat(properties.getMysqlMode()).isEqualTo("router");
        assertThat(properties.getMaxPoolSize()).isEqualTo(7);
        assertThat(properties.getPassword()).isEqualTo("from-env");
        assertThat(properties.getRetentionRuns()).isEqualTo(12);
        assertThat(properties.isRouter()).isTrue();
        properties.validate();
    }

    @Test
    void invalidModeNamesPropertyAndEnv() {
        LedgerProperties properties = valid();
        properties.setMysqlMode("cluster");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lab.ledger.mysql-mode")
                .hasMessageContaining("LAB_LEDGER_MYSQL_MODE");
    }

    @Test
    void emptyActiveUrlIsRejectedByTheResolver() throws IOException {
        LedgerProperties properties = bind(Map.of("lab.ledger.standalone-url", ""));

        assertThatThrownBy(() -> new LedgerTargetResolver(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lab.ledger.standalone-url")
                .hasMessageContaining("LAB_LEDGER_STANDALONE_URL");
    }

    @Test
    void routerModeRequiresTheRouterUrl() {
        LedgerProperties properties = valid();
        properties.setMysqlMode("router");
        properties.setRouterUrl("");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lab.ledger.router-url")
                .hasMessageContaining("LAB_LEDGER_ROUTER_URL");
    }

    @Test
    void urlMustPointAtTheLedgerSchema() {
        LedgerProperties properties = valid();
        properties.setStandaloneUrl("jdbc:mysql://mysql:3306/seckill?useSSL=false");

        assertThatThrownBy(() -> new LedgerTargetResolver(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lab_ledger")
                .hasMessageContaining("LAB_LEDGER_STANDALONE_URL");
    }

    @Test
    void poolAndTimeoutBoundsAreEnforced() {
        LedgerProperties tooSmall = valid();
        tooSmall.setMaxPoolSize(0);
        assertThatThrownBy(tooSmall::validate)
                .hasMessageContaining("lab.ledger.max-pool-size")
                .hasMessageContaining("LAB_LEDGER_MAX_POOL_SIZE");

        LedgerProperties idleAboveMax = valid();
        idleAboveMax.setMinIdle(5);
        assertThatThrownBy(idleAboveMax::validate)
                .hasMessageContaining("lab.ledger.min-idle")
                .hasMessageContaining("LAB_LEDGER_MIN_IDLE");

        LedgerProperties validationAboveConnection = valid();
        validationAboveConnection.setValidationTimeoutMs(5000);
        assertThatThrownBy(validationAboveConnection::validate)
                .hasMessageContaining("lab.ledger.validation-timeout-ms")
                .hasMessageContaining("LAB_LEDGER_VALIDATION_TIMEOUT_MS");

        LedgerProperties badRetention = valid();
        badRetention.setRetentionRuns(0);
        assertThatThrownBy(badRetention::validate)
                .hasMessageContaining("lab.ledger.retention-runs")
                .hasMessageContaining("LAB_LEDGER_RETENTION_RUNS");
    }

    @Test
    void targetResolverStripsTheDatabaseForTheServerPool() {
        LedgerProperties properties = valid();
        LedgerTargetResolver resolver = new LedgerTargetResolver(properties);

        assertThat(resolver.ledgerUrl()).isEqualTo("jdbc:mysql://mysql:3306/lab_ledger?useSSL=false");
        assertThat(resolver.serverUrl()).isEqualTo("jdbc:mysql://mysql:3306/?useSSL=false");
    }

    private static LedgerProperties valid() {
        LedgerProperties properties = new LedgerProperties();
        properties.setStandaloneUrl("jdbc:mysql://mysql:3306/lab_ledger?useSSL=false");
        properties.setRouterUrl("jdbc:mysql://mysql-router:6446/lab_ledger?useSSL=false");
        properties.validate();
        return properties;
    }

    private static LedgerProperties bind(Map<String, Object> overrides) throws IOException {
        MockEnvironment environment = new MockEnvironment();
        List<PropertySource<?>> loaded = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        for (PropertySource<?> source : loaded) {
            environment.getPropertySources().addLast(source);
        }
        if (!overrides.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource("envOverride", overrides));
        }
        return Binder.get(environment)
                .bind("lab.ledger", Bindable.of(LedgerProperties.class))
                .orElseThrow(() -> new IllegalStateException("lab.ledger did not bind"));
    }
}
