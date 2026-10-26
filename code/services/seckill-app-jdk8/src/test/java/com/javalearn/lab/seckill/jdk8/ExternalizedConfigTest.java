package com.javalearn.lab.seckill.jdk8;

import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.metrics.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工程规约 section 1 guard: every runtime value in application.yml is a
 * ${ENV:default} placeholder. This boots a second context with env-style
 * overrides (the same keys compose would set) and proves they win over the
 * defaults, all the way down to the Hikari pool and the metric common tags.
 * The demo database name is overridden too: the seed script must not run twice
 * against the shared in-memory instance the other test context already filled.
 */
@AutoConfigureMetrics
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "APP_NAME=jdk8-override-app",
                "SERVER_PORT=19999",
                "DB_POOL_MAX_SIZE=7",
                "DB_POOL_NAME=override-pool",
                "DB_CONNECTION_TIMEOUT_MS=4321",
                "INSTANCE_ID=override-instance",
                "DEMO_DB_URL=jdbc:h2:mem:seckill-jdk8-config-binding;DB_CLOSE_DELAY=-1"
        })
@ActiveProfiles("demo")
class ExternalizedConfigTest {

    @Autowired
    private Environment environment;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PrometheusMeterRegistry prometheusMeterRegistry;

    @Test
    void envStylePlaceholdersResolveOverTheDefaults() {
        assertThat(environment.getProperty("spring.application.name")).isEqualTo("jdk8-override-app");
        assertThat(environment.getProperty("server.port")).isEqualTo("19999");
        assertThat(environment.getProperty("spring.datasource.hikari.maximum-pool-size")).isEqualTo("7");
        assertThat(environment.getProperty("spring.datasource.hikari.pool-name")).isEqualTo("override-pool");
        assertThat(environment.getProperty("observability.instance")).isEqualTo("override-instance");
        assertThat(environment.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:h2:mem:seckill-jdk8-config-binding;DB_CLOSE_DELAY=-1");
    }

    @Test
    void overriddenPoolSettingsReachTheRealDataSource() {
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        HikariDataSource hikari = (HikariDataSource) dataSource;
        assertThat(hikari.getMaximumPoolSize()).isEqualTo(7);
        assertThat(hikari.getPoolName()).isEqualTo("override-pool");
        assertThat(hikari.getConnectionTimeout()).isEqualTo(4321L);
    }

    @Test
    void commonTagsFollowTheOverriddenApplicationName() {
        String scrape = prometheusMeterRegistry.scrape();
        assertThat(scrape).contains("app=\"jdk8-override-app\"");
        assertThat(scrape).contains("instance=\"override-instance\"");
        assertThat(scrape).contains("jvm_version=\"");
    }
}
