package com.javalearn.lab.seckill.datasource;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task M3-2 config contract, verified WITHOUT Docker, a live Router or a live
 * MySQL — exactly the Docker-free style of {@code RedisModeSwitchTest}:
 *
 * <ol>
 *   <li>{@code application.yml} plus the environment bind BOTH modes; the dev
 *       default is {@code standalone} with the single-node MySQL endpoints the
 *       pools used before the switch (byte-identical dev rendering).</li>
 *   <li>The MODE is the switch, not the presence of {@code DB_WRITE_URL} /
 *       {@code DB_READ_URL}: with the Router keys set but {@code MYSQL_MODE}
 *       unset, the pools keep the dev endpoints (no half-switched pair).</li>
 *   <li>{@code router} mode repoints the write pool at the Router read-write
 *       endpoint (6446) and the read pool at the read-only endpoint (6447);
 *       asserted on the built Hikari config, no connection is opened.</li>
 *   <li>Unknown modes and a router mode without both URLs fail fast, naming
 *       the env keys — never a silent fallback to the dev MySQL.</li>
 *   <li>The routing contract of {@link ReadWriteRoutingDataSource} is
 *       unchanged: {@code readOnly} → READ pool, everything else → WRITE.</li>
 * </ol>
 */
class MySqlModeSwitchTest {

    private static final String DEV_WRITE_URL =
            "jdbc:mysql://dev-mysql:3306/seckill?useSSL=false";
    private static final String DEV_READ_URL =
            "jdbc:mysql://dev-mysql:3306/seckill?useSSL=false";
    private static final String ROUTER_WRITE_URL =
            "jdbc:mysql://mysql-router:6446/seckill?useSSL=false&allowPublicKeyRetrieval=true";
    private static final String ROUTER_READ_URL =
            "jdbc:mysql://mysql-router:6447/seckill?useSSL=false&allowPublicKeyRetrieval=true";

    /** application.yml as the source of truth, plus simulated env variables. */
    private static StandardEnvironment environmentWith(Map<String, Object> envVars) {
        StandardEnvironment environment = new StandardEnvironment();
        try {
            new YamlPropertySourceLoader()
                    .load("application.yml", new ClassPathResource("application.yml"))
                    .forEach(source -> environment.getPropertySources().addFirst(source));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        // Same trick as RedisModeSwitchTest: the SPRING_/DB_ relaxed mapping is
        // applied only to the source literally named "systemEnvironment", which
        // is also how Docker env vars reach the container. Replacing it keeps
        // the test hermetic (no leakage from the host shell).
        environment.getPropertySources().replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new HashMap<>(envVars)));
        return environment;
    }

    private static DataSourceAccessProperties bindAccessProperties(Map<String, Object> envVars) {
        return Binder.get(environmentWith(envVars))
                .bind("app.datasource", DataSourceAccessProperties.class)
                .get();
    }

    private static ApplicationContextRunner dataSourceContext() {
        return new ApplicationContextRunner()
                .withUserConfiguration(DataSourceConfig.class)
                .withPropertyValues(
                        "app.datasource.write.username=seckill",
                        "app.datasource.write.password=lab-only-password",
                        "app.datasource.write.maximum-pool-size=2",
                        "app.datasource.write.minimum-idle=0",
                        "app.datasource.write.pool-name=write-pool",
                        "app.datasource.read.username=seckill",
                        "app.datasource.read.password=lab-only-password",
                        "app.datasource.read.maximum-pool-size=2",
                        "app.datasource.read.minimum-idle=0",
                        "app.datasource.read.pool-name=read-pool");
    }

    @Test
    void devDefaultsBindStandaloneWithTheDevMysqlEndpoints() {
        StandardEnvironment environment = environmentWith(Map.of());
        DataSourceAccessProperties access = bindAccessProperties(Map.of());

        assertThat(access.getMode()).isEqualTo(DataSourceAccessProperties.MODE_STANDALONE);
        assertThat(access.isRouter()).isFalse();
        assertThat(access.getRouter().getWriteUrl()).isEmpty();
        assertThat(access.getRouter().getReadUrl()).isEmpty();
        access.validate();

        // Byte-identical dev rendering: both pools still address the single
        // mysql service, exactly the URL the pre-M3-2 yaml produced (DB_* unset).
        String devUrl = "jdbc:mysql://mysql:3306/seckill"
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        assertThat(environment.getProperty("app.datasource.write.jdbc-url")).isEqualTo(devUrl);
        assertThat(environment.getProperty("app.datasource.read.jdbc-url")).isEqualTo(devUrl);
    }

    @Test
    void routerModeBindsTheRouterUrlsFromTheEnvironment() {
        Map<String, Object> env = Map.of(
                "MYSQL_MODE", "router",
                "DB_WRITE_URL", ROUTER_WRITE_URL,
                "DB_READ_URL", ROUTER_READ_URL);
        DataSourceAccessProperties access = bindAccessProperties(env);

        assertThat(access.isRouter()).isTrue();
        access.validate();
        assertThat(access.getRouter().getWriteUrl()).isEqualTo(ROUTER_WRITE_URL);
        assertThat(access.getRouter().getReadUrl()).isEqualTo(ROUTER_READ_URL);
        // The mode picks the effective endpoint, the standalone value is not lost.
        assertThat(access.effectiveWriteUrl(DEV_WRITE_URL)).isEqualTo(ROUTER_WRITE_URL);
        assertThat(access.effectiveReadUrl(DEV_READ_URL)).isEqualTo(ROUTER_READ_URL);
    }

    @Test
    void modeIsTheSelectorAndRouterKeysAloneNeverSwitchThePools() {
        // A stray DB_*_URL pair without MYSQL_MODE=router must NOT half-switch
        // the pair; the standalone endpoints stay in force.
        Map<String, Object> env = Map.of(
                "DB_WRITE_URL", ROUTER_WRITE_URL,
                "DB_READ_URL", ROUTER_READ_URL);
        DataSourceAccessProperties access = bindAccessProperties(env);

        assertThat(access.getMode()).isEqualTo(DataSourceAccessProperties.MODE_STANDALONE);
        assertThat(access.isRouter()).isFalse();
        access.validate();
        assertThat(access.effectiveWriteUrl(DEV_WRITE_URL)).isEqualTo(DEV_WRITE_URL);
        assertThat(access.effectiveReadUrl(DEV_READ_URL)).isEqualTo(DEV_READ_URL);
    }

    @Test
    void unknownModeIsRejected() {
        DataSourceAccessProperties access =
                bindAccessProperties(Map.of("MYSQL_MODE", "cluster"));
        assertThatThrownBy(access::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MYSQL_MODE")
                .hasMessageContaining(DataSourceAccessProperties.MODE_STANDALONE)
                .hasMessageContaining(DataSourceAccessProperties.MODE_ROUTER);
    }

    @Test
    void routerModeWithoutWriteUrlFailsInsteadOfFallingBackToDev() {
        DataSourceAccessProperties access = bindAccessProperties(Map.of(
                "MYSQL_MODE", "router",
                "DB_READ_URL", ROUTER_READ_URL));

        assertThatThrownBy(access::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_WRITE_URL")
                .hasMessageContaining("fall back");
    }

    @Test
    void routerModeWithoutReadUrlFailsInsteadOfFallingBackToDev() {
        DataSourceAccessProperties access = bindAccessProperties(Map.of(
                "MYSQL_MODE", "router",
                "DB_WRITE_URL", ROUTER_WRITE_URL));

        assertThatThrownBy(access::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_READ_URL")
                .hasMessageContaining("fall back");
    }

    @Test
    void routerContextRepointsBothPoolsAtTheRouterEndpoints() {
        dataSourceContext()
                .withPropertyValues(
                        "app.datasource.mode=router",
                        "app.datasource.router.write-url=" + ROUTER_WRITE_URL,
                        "app.datasource.router.read-url=" + ROUTER_READ_URL,
                        // The standalone URLs are still bound; router mode must win.
                        "app.datasource.write.jdbc-url=" + DEV_WRITE_URL,
                        "app.datasource.read.jdbc-url=" + DEV_READ_URL)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    HikariDataSource write = context.getBean("writeDataSource", HikariDataSource.class);
                    HikariDataSource read = context.getBean("readDataSource", HikariDataSource.class);
                    assertThat(write.getJdbcUrl()).isEqualTo(ROUTER_WRITE_URL);
                    assertThat(read.getJdbcUrl()).isEqualTo(ROUTER_READ_URL);
                    // URL swap happens on the config only; no connection was opened.
                    assertThat(write.isRunning()).isFalse();
                    assertThat(read.isRunning()).isFalse();

                    DataSource primary = context.getBean(DataSource.class);
                    assertThat(primary).isInstanceOf(LazyConnectionDataSourceProxy.class);
                    assertThat(((LazyConnectionDataSourceProxy) primary).getTargetDataSource())
                            .isInstanceOf(ReadWriteRoutingDataSource.class);
                });
    }

    @Test
    void standaloneContextIgnoresRouterKeysAndKeepsBothDevEndpoints() {
        dataSourceContext()
                .withPropertyValues(
                        "app.datasource.mode=standalone",
                        "app.datasource.router.write-url=" + ROUTER_WRITE_URL,
                        "app.datasource.router.read-url=" + ROUTER_READ_URL,
                        "app.datasource.write.jdbc-url=" + DEV_WRITE_URL,
                        "app.datasource.read.jdbc-url=" + DEV_READ_URL)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean("writeDataSource", HikariDataSource.class)
                            .getJdbcUrl()).isEqualTo(DEV_WRITE_URL);
                    assertThat(context.getBean("readDataSource", HikariDataSource.class)
                            .getJdbcUrl()).isEqualTo(DEV_READ_URL);
                });
    }

    @Test
    void routerModeContextFailsWithoutRouterUrls() {
        dataSourceContext()
                .withPropertyValues(
                        "app.datasource.mode=router",
                        "app.datasource.write.jdbc-url=" + DEV_WRITE_URL,
                        "app.datasource.read.jdbc-url=" + DEV_READ_URL)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void routingKeyStillFollowsTheTransactionReadOnlyFlag() {
        ReadWriteRoutingDataSource routing = new ReadWriteRoutingDataSource();
        try {
            assertThat(routing.determineCurrentLookupKey()).isEqualTo(DataSourceType.WRITE);
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
            assertThat(routing.determineCurrentLookupKey()).isEqualTo(DataSourceType.READ);
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
            assertThat(routing.determineCurrentLookupKey()).isEqualTo(DataSourceType.WRITE);
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    void startupLogEndpointDropsQueryStringAndCredentials() {
        assertThat(DataSourceConfig.endpoint(
                "jdbc:mysql://seckill:topsecret@mysql-router:6446/seckill"
                        + "?useSSL=false&password=topsecret"))
                .isEqualTo("jdbc:mysql://mysql-router:6446/seckill");
        assertThat(DataSourceConfig.endpoint(ROUTER_READ_URL))
                .isEqualTo("jdbc:mysql://mysql-router:6447/seckill");
        assertThat(DataSourceConfig.endpoint(null)).isEqualTo("<unset>");
    }
}
