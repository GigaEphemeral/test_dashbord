package com.javalearn.lab.seckill.datasource;

import com.javalearn.lab.seckill.MySqlRabbitIntegrationTestSupport;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.HealthContributor;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 15 follow-up: the actuator {@code db} health contributor must check the
 * primary (lazy routing) DataSource only.
 *
 * <p>Boot's {@code DataSourceHealthContributorAutoConfiguration} collects
 * <em>every</em> DataSource bean into a composite when more than one exists.
 * With the write/read pair of Task 15 that made the health endpoint call
 * {@code getConnection()} on the raw {@code readDataSource} bean as well, i.e.
 * the Docker healthcheck started the read pool seconds after boot on every
 * instance. Two consequences: the brief's "actuator health uses the primary
 * DataSource" was violated, and the presence of
 * {@code hikaricp_connections_max{pool="read-pool"}} stopped being evidence
 * that a read-only transaction had run (verify's read-pool check was hollowed
 * out). Declaring a {@code dbHealthContributor} bean makes the auto-
 * configuration back off ({@code @ConditionalOnMissingBean} verified against
 * Spring Boot 3.5.16).</p>
 *
 * <p>The dedicated {@code observability.instance} value keeps this class in a
 * Spring context of its own, so the read pool is guaranteed cold when the test
 * runs: nothing in this context executes a read-only transaction.</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "observability.env=test",
                "observability.instance=datasource-health-it"
        })
class DataSourceHealthIT extends MySqlRabbitIntegrationTestSupport {

    @Autowired
    private ApplicationContext context;

    @Autowired
    @Qualifier("readDataSource")
    private HikariDataSource readDataSource;

    @Test
    void dbHealthChecksThePrimaryDataSourceAndNeverStartsTheReadPool() {
        HealthContributor db = context.getBean("dbHealthContributor", HealthContributor.class);

        assertThat(db)
                .as("db health must be the single primary-datasource indicator, "
                        + "not Boot's per-DataSource composite")
                .isInstanceOf(HealthIndicator.class);

        assertThat(readDataSource.isRunning())
                .as("read pool must be cold before the health call")
                .isFalse();

        assertThat(((HealthIndicator) db).health().getStatus()).isEqualTo(Status.UP);

        assertThat(readDataSource.isRunning())
                .as("the health check must not acquire a raw read-pool connection")
                .isFalse();
    }
}
