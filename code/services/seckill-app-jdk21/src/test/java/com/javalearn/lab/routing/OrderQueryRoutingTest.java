package com.javalearn.lab.routing;

import com.javalearn.lab.seckill.datasource.DataSourceConfig;
import com.javalearn.lab.seckill.order.api.CreateOrderCommand;
import com.javalearn.lab.seckill.order.api.OrderService;
import com.javalearn.lab.seckill.order.app.OrderQueryService;
import com.javalearn.lab.seckill.order.domain.Order;
import com.javalearn.lab.seckill.order.domain.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 15 follow-up: the order lookups that must be read-after-write
 * consistent (the duplicate-key idempotency retry and the order lookup behind
 * {@code GET /api/orders/{id}}) must never be routed to the read pool, because
 * in M3 that pool is the Router 6447 replica and a replica lag window would
 * turn a legitimately idempotent replay into a 503 ("Idempotent order not
 * visible yet") or make a just-created order appear to be missing.
 *
 * <p>The test runs the real {@link OrderQueryService} against the same two H2
 * databases as {@link ReadWriteRoutingTest}: the stub persistence ports peek
 * the {@code routing_marker} row through the primary lazy datasource, so the
 * marker reveals which pool the surrounding transaction used. The expected
 * marker is {@code write} for both lookups; only the dedicated catalog
 * {@code ProductReadService} may see {@code read}.</p>
 *
 * <p>Lives outside {@code com.javalearn.lab.seckill} on purpose, exactly like
 * {@link TransactionTestConfig} (see the package-scan pitfall documented
 * there).</p>
 */
@SpringJUnitConfig({DataSourceConfig.class, TransactionTestConfig.class,
        OrderQueryRoutingTest.OrderQueryFixtures.class})
@TestPropertySource(properties = {
        "app.datasource.mode=standalone",
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
class OrderQueryRoutingTest {

    @Autowired
    private OrderQueryService orderQueryService;

    @Autowired
    private MarkerProbe markerProbe;

    @BeforeEach
    void resetProbe() {
        markerProbe.reset();
    }

    @Test
    void duplicateKeyIdempotencyLookupIsPinnedToTheWritePool() {
        Optional<Order> found = orderQueryService.findByIdempotencyKey("any-key");

        assertThat(found).isEmpty();
        assertThat(markerProbe.lastMarker())
                .as("the idempotent re-read must run on the write pool (consistent read)")
                .isEqualTo("write");
        assertThat(markerProbe.lastTransactionReadOnly())
                .as("the lookup transaction must not be flagged read-only")
                .isFalse();
    }

    @Test
    void orderLookupByIdIsPinnedToTheWritePool() {
        Order order = orderQueryService.get(1L);

        assertThat(order).isNull();
        assertThat(markerProbe.lastMarker())
                .as("GET /api/orders/{id} must see the just-created order (write pool)")
                .isEqualTo("write");
        assertThat(markerProbe.lastTransactionReadOnly()).isFalse();
    }

    /**
     * Shared fixture: records which database answered the probe statement and
     * whether a Spring transaction marked it read-only was active at that
     * moment.
     */
    static final class MarkerProbe {

        private final JdbcTemplate jdbcTemplate;
        private volatile String lastMarker;
        private volatile Boolean lastTransactionReadOnly;

        MarkerProbe(DataSource dataSource) {
            this.jdbcTemplate = new JdbcTemplate(dataSource);
        }

        void peek() {
            lastMarker = jdbcTemplate.queryForObject("SELECT marker FROM routing_marker", String.class);
            lastTransactionReadOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        }

        void reset() {
            lastMarker = null;
            lastTransactionReadOnly = null;
        }

        String lastMarker() {
            return lastMarker;
        }

        Boolean lastTransactionReadOnly() {
            return lastTransactionReadOnly;
        }
    }

    static final class ProbeOrderRepository implements OrderRepository {

        private final MarkerProbe probe;

        ProbeOrderRepository(MarkerProbe probe) {
            this.probe = probe;
        }

        @Override
        public Order save(Order order) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public Optional<Order> findById(long id) {
            probe.peek();
            return Optional.empty();
        }

        @Override
        public Optional<Order> findByIdempotencyKey(String idempotencyKey) {
            probe.peek();
            return Optional.empty();
        }
    }

    static final class ProbeOrderService implements OrderService {

        private final MarkerProbe probe;

        ProbeOrderService(MarkerProbe probe) {
            this.probe = probe;
        }

        @Override
        public Order create(CreateOrderCommand command) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public Order get(long id) {
            probe.peek();
            return null;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OrderQueryFixtures {

        @Bean
        MarkerProbe markerProbe(DataSource dataSource) {
            return new MarkerProbe(dataSource);
        }

        @Bean
        OrderRepository orderRepository(MarkerProbe probe) {
            return new ProbeOrderRepository(probe);
        }

        @Bean
        OrderService orderService(MarkerProbe probe) {
            return new ProbeOrderService(probe);
        }

        @Bean
        OrderQueryService orderQueryService(OrderService orderService, OrderRepository orderRepository) {
            return new OrderQueryService(orderService, orderRepository);
        }
    }
}
