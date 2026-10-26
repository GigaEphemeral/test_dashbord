package com.javalearn.lab.seckill.datasource;

/**
 * The two physical datasources of the application tier.
 *
 * <p>In dev both point at the same MySQL instance; in M3 they point at the
 * MySQL Router read-write (6446) and read-only (6447) ports. Keeping them as an
 * explicit enum makes the routing decision visible in logs and tests.</p>
 */
public enum DataSourceType {

    /** Primary/write connection pool; the default for every connection. */
    WRITE,

    /** Read connection pool, used only inside read-only transactions. */
    READ
}
