package com.javalearn.lab.chaos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.autoconfigure.jdbc.DataSourceHealthContributorAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;

/**
 * Flyway auto-configuration is excluded on purpose: the ledger declares its own
 * {@code ledgerFlyway} bean with {@code classpath:db/ledger} and the
 * {@code lab_ledger} default schema. Boot's default would look for
 * {@code db/migration} and could not coexist with the two ledger DataSources.
 *
 * <p>The generic DB health contributor is excluded too: the ledger MySQL is
 * deliberately killed during drills, so {@code /health} must keep reflecting
 * the controller process (the ledger has its own
 * {@code GET /lab/ledger/status}). Otherwise a down MySQL would flip the
 * controller container to unhealthy and break the demo compose healthcheck.</p>
 */
@SpringBootApplication(exclude = {
        FlywayAutoConfiguration.class,
        DataSourceHealthContributorAutoConfiguration.class
})
public class ChaosControllerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChaosControllerApplication.class, args);
    }
}
