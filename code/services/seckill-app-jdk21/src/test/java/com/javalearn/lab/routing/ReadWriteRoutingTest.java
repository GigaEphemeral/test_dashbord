package com.javalearn.lab.routing;

import com.javalearn.lab.seckill.datasource.DataSourceConfig;
import com.javalearn.lab.seckill.datasource.ReadWriteRoutingDataSource;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the datasource routing without MySQL or Testcontainers: two separate
 * in-memory H2 databases contain a {@code routing_marker} row with different
 * values, and the value seen by a probe reveals which pool served it.
 *
 * <p>The three cases map directly to the routing contract:</p>
 * <ol>
 *   <li>{@code @Transactional(readOnly = true)} sees the <em>read</em> database;</li>
 *   <li>a normal {@code @Transactional} method sees the <em>write</em> database;</li>
 *   <li>a call without a transaction sees the <em>write</em> database.</li>
 * </ol>
 *
 * <p>The first case also proves the lazy proxy: {@code DataSourceTransactionManager}
 * asks for a {@link java.sql.Connection} during transaction begin, before
 * {@code TransactionSynchronizationManager} carries the read-only flag. With a
 * plain routing datasource that connection would be resolved as WRITE. Here the
 * read pool is still cold before the probe and the probe's marker comes from
 * the read database, which is only possible because the physical connection is
 * fetched after the flag is visible. The test methods are ordered so the cold
 * pool observation happens before the read-only probe starts it.</p>
 */
@SpringJUnitConfig({DataSourceConfig.class, TransactionTestConfig.class, DbProbe.class})
@TestPropertySource(properties = {
        "app.datasource.write.jdbc-url=jdbc:h2:mem:routing_write;DB_CLOSE_DELAY=-1",
        "app.datasource.write.username=sa",
        "app.datasource.write.password=",
        "app.datasource.write.maximum-pool-size=2",
        "app.datasource.write.minimum-idle=0",
        "app.datasource.write.pool-name=write-pool",
        "app.datasource.read.jdbc-url=jdbc:h2:mem:routing_read;DB_CLOSE_DELAY=-1",
        "app.datasource.read.username=sa",
        "app.datasource.read.password=",
        "app.datasource.read.maximum-pool-size=2",
        "app.datasource.read.minimum-idle=0",
        "app.datasource.read.pool-name=read-pool"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReadWriteRoutingTest {

    @Autowired
    private DbProbe dbProbe;

    @Autowired
    private DataSource dataSource;

    @Autowired
    @Qualifier("writeDataSource")
    private HikariDataSource writeDataSource;

    @Autowired
    @Qualifier("readDataSource")
    private HikariDataSource readDataSource;

    @Test
    @Order(1)
    void readOnlyTransactionReadsFromReadPool() {
        // The marker tables were created via DriverManager, so neither Hikari
        // pool has been started yet.
        assertThat(readDataSource.isRunning())
                .as("read pool must be cold before the first read-only transaction")
                .isFalse();

        assertThat(dbProbe.readMarker()).isEqualTo("read");

        assertThat(readDataSource.isRunning())
                .as("read-only transaction must initialize the read pool")
                .isTrue();
    }

    @Test
    @Order(2)
    void readWriteTransactionReadsFromWritePool() {
        assertThat(dbProbe.writeMarker()).isEqualTo("write");
    }

    @Test
    @Order(3)
    void nonTransactionalCallReadsFromWritePool() {
        assertThat(dbProbe.nonTransactionalMarker()).isEqualTo("write");
    }

    @Test
    @Order(4)
    void primaryDataSourceIsLazyProxyOverRoutingDataSource() {
        assertThat(dataSource).isInstanceOf(LazyConnectionDataSourceProxy.class);
        LazyConnectionDataSourceProxy lazy = (LazyConnectionDataSourceProxy) dataSource;
        assertThat(lazy.getTargetDataSource()).isInstanceOf(ReadWriteRoutingDataSource.class);
        assertThat(writeDataSource.getPoolName()).isEqualTo("write-pool");
        assertThat(readDataSource.getPoolName()).isEqualTo("read-pool");
        assertThat(writeDataSource.getJdbcUrl()).isEqualTo("jdbc:h2:mem:routing_write;DB_CLOSE_DELAY=-1");
        assertThat(readDataSource.getJdbcUrl()).isEqualTo("jdbc:h2:mem:routing_read;DB_CLOSE_DELAY=-1");
    }
}
