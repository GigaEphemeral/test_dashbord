package com.javalearn.lab.routing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;

/**
 * Executes reading-probe statements inside and outside transactions. The
 * {@link JdbcTemplate} uses the primary lazy datasource, so the routing key is
 * whatever the surrounding transaction declares at first statement execution.
 */
@Component
public class DbProbe {

    private final JdbcTemplate jdbcTemplate;

    public DbProbe(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    /** Read-only transaction: must be routed to the read database ("read"). */
    @Transactional(readOnly = true)
    public String readMarker() {
        return marker();
    }

    /** Read-write transaction: must be routed to the write database ("write"). */
    @Transactional
    public String writeMarker() {
        return marker();
    }

    /** No transaction at all: the routing default must be the write database. */
    public String nonTransactionalMarker() {
        return marker();
    }

    private String marker() {
        return jdbcTemplate.queryForObject("SELECT marker FROM routing_marker", String.class);
    }
}
