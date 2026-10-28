package com.javalearn.lab.chaos.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.bench.BenchScenarioCatalog;
import com.javalearn.lab.chaos.bench.exec.BenchLaunchPlanner;
import com.javalearn.lab.chaos.bench.exec.BenchRunExecutor;
import com.javalearn.lab.chaos.bench.exec.BenchRunLifecycleHook;
import com.javalearn.lab.chaos.bench.exec.BenchScriptsHostDirResolver;
import com.javalearn.lab.chaos.precheck.RunPrecheckService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wires the ledger: two Hikari pools (server-level for CREATE DATABASE,
 * schema-level for Flyway/queries), the explicit Flyway bean (never Boot's
 * auto-configuration, which would use the default location), one daemon
 * scheduler for bootstrap/replay/retention, and the repository/writer/service
 * chain.
 *
 * <p>Both pools set {@code initialization-fail-timeout=-1}: pool creation never
 * opens a connection, so the controller starts normally when MySQL is down and
 * the bootstrap runner keeps retrying in the background.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LedgerProperties.class)
public class LedgerConfig {

    @Bean
    public Clock ledgerClock() {
        return Clock.systemUTC();
    }

    @Bean
    public LedgerTargetResolver ledgerTargetResolver(LedgerProperties properties) {
        return new LedgerTargetResolver(properties);
    }

    @Bean(name = "ledgerServerDataSource", destroyMethod = "close")
    public HikariDataSource ledgerServerDataSource(LedgerProperties properties,
                                                   LedgerTargetResolver resolver) {
        return pool("ledger-server", resolver.serverUrl(), properties);
    }

    @Bean(name = "ledgerDataSource", destroyMethod = "close")
    public HikariDataSource ledgerDataSource(LedgerProperties properties, LedgerTargetResolver resolver) {
        return pool("ledger-" + resolver.mode(), resolver.ledgerUrl(), properties);
    }

    @Bean(name = "ledgerServerJdbcTemplate")
    public JdbcTemplate ledgerServerJdbcTemplate(
            @Qualifier("ledgerServerDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean(name = "ledgerJdbcTemplate")
    public JdbcTemplate ledgerJdbcTemplate(@Qualifier("ledgerDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    public Flyway ledgerFlyway(@Qualifier("ledgerDataSource") DataSource dataSource,
                               LedgerProperties properties) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(properties.getFlywayLocations().split("\\s*,\\s*"))
                .schemas(properties.getSchema())
                .defaultSchema(properties.getSchema())
                .createSchemas(false)
                .load();
    }

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService ledgerScheduler(LedgerProperties properties) {
        AtomicInteger sequence = new AtomicInteger();
        String prefix = properties.getBootstrapThreadName();
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * B1-02 operation executor: a dedicated daemon pool, deliberately separate
     * from the chaos TTL/revert scheduler ({@code chaosActionScheduler}) so a
     * blocking operation can never delay a scheduled revert. Both the thread
     * name and the pool size are externalized.
     */
    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService ledgerOperationScheduler(LedgerProperties properties) {
        AtomicInteger sequence = new AtomicInteger();
        String prefix = properties.getOperation().getThreadName();
        return Executors.newScheduledThreadPool(properties.getOperation().getPoolSize(), runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    public RunLedgerRepository runLedgerRepository(
            @Qualifier("ledgerJdbcTemplate") JdbcTemplate ledgerJdbcTemplate) {
        return new RunLedgerRepository(ledgerJdbcTemplate);
    }

    @Bean
    public LedgerStash ledgerStash(LedgerProperties properties, RunLedgerRepository repository,
                                   ObjectMapper objectMapper, Clock ledgerClock) {
        return new LedgerStash(properties, repository, objectMapper, ledgerClock);
    }

    @Bean
    public LedgerWriter ledgerWriter(RunLedgerRepository repository, LedgerStash stash,
                                     ObjectMapper objectMapper, Clock ledgerClock) {
        return new LedgerWriter(repository, stash, objectMapper, ledgerClock);
    }

    @Bean
    public LedgerRecovery ledgerRecovery(RunLedgerRepository repository, Clock ledgerClock) {
        return new LedgerRecovery(repository, ledgerClock);
    }

    @Bean
    public LedgerRetention ledgerRetention(LedgerProperties properties, RunLedgerRepository repository) {
        return new LedgerRetention(properties, repository);
    }

    @Bean
    public LedgerBootstrap ledgerBootstrap(LedgerProperties properties, LedgerTargetResolver resolver,
                                           @Qualifier("ledgerServerJdbcTemplate") JdbcTemplate serverJdbcTemplate,
                                           Flyway ledgerFlyway, LedgerRecovery recovery,
                                           LedgerRetention retention, LedgerStash stash,
                                           ScheduledExecutorService ledgerScheduler, Clock ledgerClock) {
        return new LedgerBootstrap(properties, resolver, serverJdbcTemplate, ledgerFlyway,
                recovery, retention, stash, ledgerScheduler, ledgerClock);
    }

    @Bean
    public RunLedgerService runLedgerService(LedgerProperties properties, LedgerTargetResolver resolver,
                                             LedgerBootstrap bootstrap, RunLedgerRepository repository,
                                             LedgerWriter writer, LedgerStash stash,
                                             ObjectMapper objectMapper, Clock ledgerClock) {
        return new RunLedgerService(properties, resolver, bootstrap, repository, writer, stash,
                objectMapper, ledgerClock);
    }

    @Bean
    public OperationService operationService(LedgerProperties properties, LedgerBootstrap bootstrap,
                                             RunLedgerRepository repository, LedgerWriter writer,
                                             @Qualifier("ledgerOperationScheduler")
                                             ScheduledExecutorService ledgerOperationScheduler,
                                             ObjectMapper objectMapper, Clock ledgerClock) {
        return new OperationService(properties, bootstrap, repository, writer, ledgerOperationScheduler,
                objectMapper, ledgerClock);
    }

    @Bean
    public EnvLockService envLockService(LedgerProperties properties, RunLedgerRepository repository,
                                         Clock ledgerClock) {
        return new EnvLockService(properties, repository, ledgerClock);
    }

    /**
     * Stage2 B2-02b-2a: the real launch/stop hook. It only depends on the
     * repository, catalog, planner, resolver and executor - never on the
     * lifecycle service - so there is no cycle. The hook itself does not write
     * run status or the lock; {@link RunLifecycleService} keeps that ownership.
     */
    @Bean
    public RunLifecycleHook runLifecycleHook(RunLedgerRepository repository,
                                             BenchScenarioCatalog benchmarkCatalog,
                                             BenchLaunchPlanner benchLaunchPlanner,
                                             BenchScriptsHostDirResolver scriptsHostDirResolver,
                                             BenchRunExecutor benchRunExecutor,
                                             ObjectMapper objectMapper) {
        return new BenchRunLifecycleHook(repository, benchmarkCatalog, benchLaunchPlanner,
                scriptsHostDirResolver, benchRunExecutor, objectMapper);
    }

    @Bean
    public RunLifecycleService runLifecycleService(LedgerProperties properties, LedgerBootstrap bootstrap,
                                                   RunLedgerRepository repository, LedgerWriter writer,
                                                   EnvLockService envLockService,
                                                   OperationService operationService,
                                                   RunPrecheckService precheck,
                                                   RunLifecycleHook runLifecycleHook,
                                                   Clock ledgerClock) {
        return new RunLifecycleService(properties, bootstrap, repository, writer, envLockService,
                operationService, precheck, runLifecycleHook, ledgerClock);
    }

    private static HikariDataSource pool(String name, String url, LedgerProperties properties) {
        HikariConfig config = new HikariConfig();
        config.setPoolName(name);
        config.setJdbcUrl(url);
        config.setUsername(properties.getUser());
        config.setPassword(properties.getPassword());
        config.setMaximumPoolSize(properties.getMaxPoolSize());
        config.setMinimumIdle(properties.getMinIdle());
        config.setConnectionTimeout(properties.getConnectionTimeoutMs());
        config.setValidationTimeout(properties.getValidationTimeoutMs());
        // Never open a connection at pool creation: the controller must start
        // even while the MySQL primary is down.
        config.setInitializationFailTimeout(-1);
        return new HikariDataSource(config);
    }
}
