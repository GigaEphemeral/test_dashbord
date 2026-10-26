package com.javalearn.lab.seckill.admission;

import com.javalearn.lab.seckill.MySqlRedisIntegrationTestSupport;
import com.javalearn.lab.seckill.admission.app.SeckillAdmissionService;
import com.javalearn.lab.seckill.admission.app.SeckillRequestStore;
import com.javalearn.lab.seckill.admission.app.SeckillStockStore;
import com.javalearn.lab.seckill.admission.config.SeckillProperties;
import com.javalearn.lab.seckill.admission.domain.SeckillRequest;
import com.javalearn.lab.seckill.admission.domain.SeckillRequestStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.fail;

/**
 * Fixture for the seckill integration tests.
 *
 * <p>Each class runs with its own exchange/queue/routing key (set through
 * properties), which keeps cached Spring contexts from competing for the same
 * messages. A second, consumer-less audit queue is bound to the same exchange
 * and routing key, so tests can prove how many messages were published even
 * after the working queue drained.</p>
 */
public abstract class SeckillIntegrationTestSupport extends MySqlRedisIntegrationTestSupport {

    protected static final long SECKILL_PRODUCT_ID = 20L;
    protected static final int INITIAL_STOCK = 20;
    protected static final Duration DEFAULT_AWAIT = Duration.ofSeconds(60);

    @Autowired
    protected SeckillAdmissionService admissionService;

    @Autowired
    protected SeckillRequestStore seckillRequestStore;

    @Autowired
    protected SeckillStockStore seckillStockStore;

    @Autowired
    protected SeckillProperties seckillProperties;

    @Autowired
    protected StringRedisTemplate redisTemplate;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected RabbitAdmin rabbitAdmin;

    @Autowired
    protected MeterRegistry meterRegistry;

    private String auditQueue;

    @BeforeEach
    void resetSeckillFixture() {
        jdbcTemplate.update("DELETE FROM order_order");
        jdbcTemplate.update(
                "UPDATE inventory_stock SET available = ?, reserved = 0 WHERE product_id = ?",
                INITIAL_STOCK, SECKILL_PRODUCT_ID);

        deleteKeys(seckillProperties.getRequest().getKeyPrefix() + "*");
        deleteKeys(seckillProperties.getIdempotency().getKeyPrefix() + "*");
        redisTemplate.delete(seckillProperties.getStock().getKeyPrefix() + SECKILL_PRODUCT_ID);
        seckillStockStore.seedIfAbsent(SECKILL_PRODUCT_ID, INITIAL_STOCK);

        rabbitAdmin.initialize();
        DirectExchange exchange = new DirectExchange(
                seckillProperties.getMq().getExchange(), true, false);
        rabbitAdmin.declareExchange(exchange);
        Queue working = new Queue(queueName(), true);
        rabbitAdmin.declareQueue(working);
        rabbitAdmin.declareBinding(BindingBuilder.bind(working)
                .to(exchange)
                .with(seckillProperties.getMq().getRoutingKey()));
        rabbitAdmin.purgeQueue(queueName(), false);

        auditQueue = seckillProperties.getMq().getQueue() + ".audit";
        Queue audit = new Queue(auditQueue, false, false, true);
        rabbitAdmin.declareQueue(audit);
        rabbitAdmin.declareBinding(BindingBuilder.bind(audit)
                .to(exchange)
                .with(seckillProperties.getMq().getRoutingKey()));
        rabbitAdmin.purgeQueue(auditQueue, false);
    }

    @AfterEach
    void removeAuditQueue() {
        if (auditQueue != null) {
            rabbitAdmin.deleteQueue(auditQueue);
            auditQueue = null;
        }
    }

    protected String queueName() {
        return seckillProperties.getMq().getQueue();
    }

    protected int auditMessageCount() {
        QueueInformation information = rabbitAdmin.getQueueInfo(auditQueue);
        return information == null ? -1 : information.getMessageCount();
    }

    protected void awaitAuditMessageCount(int expected) {
        await("audit queue has " + expected + " published message(s)", DEFAULT_AWAIT,
                () -> auditMessageCount() == expected);
    }

    protected void awaitQueueEmpty() {
        await("working queue is empty", DEFAULT_AWAIT, () -> {
            QueueInformation information = rabbitAdmin.getQueueInfo(queueName());
            return information != null && information.getMessageCount() == 0;
        });
    }

    protected SeckillRequest awaitRequestStatus(String requestId,
                                                SeckillRequestStatus expected,
                                                Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        SeckillRequest last = null;
        while (System.nanoTime() < deadline) {
            last = seckillRequestStore.find(requestId).orElse(null);
            if (last != null && last.status() == expected) {
                return last;
            }
            sleep();
        }
        if (last == null) {
            fail("Seckill request " + requestId + " was not stored within " + timeout);
        }
        fail("Seckill request " + requestId + " did not reach " + expected
                + " within " + timeout + "; last status " + last.status());
        return last;
    }

    protected void awaitOrderCount(long expected) {
        await("order count is " + expected, DEFAULT_AWAIT, () -> orderCount() == expected);
    }

    protected long orderCount() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM order_order", Long.class);
        return count == null ? 0L : count;
    }

    protected long ordersForProduct() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_order WHERE product_id = ?", Long.class,
                SECKILL_PRODUCT_ID);
        return count == null ? 0L : count;
    }

    protected int dbStock() {
        Integer available = jdbcTemplate.queryForObject(
                "SELECT available FROM inventory_stock WHERE product_id = ?",
                Integer.class, SECKILL_PRODUCT_ID);
        return available == null ? -1 : available;
    }

    protected int minimumDbStock() {
        Integer minimum = jdbcTemplate.queryForObject(
                "SELECT MIN(available) FROM inventory_stock", Integer.class);
        return minimum == null ? -1 : minimum;
    }

    protected Integer redisStock() {
        String value = redisTemplate.opsForValue().get(
                seckillProperties.getStock().getKeyPrefix() + SECKILL_PRODUCT_ID);
        return value == null ? null : Integer.valueOf(value);
    }

    protected Long ttlSeconds(String key) {
        return redisTemplate.getExpire(key, TimeUnit.SECONDS);
    }

    protected String requestKey(String requestId) {
        return seckillProperties.getRequest().getKeyPrefix() + requestId;
    }

    protected String idempotencyKey(String idempotencyKey) {
        return seckillProperties.getIdempotency().getKeyPrefix() + idempotencyKey;
    }

    protected double attempts(String result) {
        Counter counter = meterRegistry.find("seckill_attempts").tag("result", result).counter();
        return counter == null ? 0.0d : counter.count();
    }

    protected double consumerProcessed(String result) {
        Counter counter = meterRegistry.find("order_consumer_processed")
                .tag("result", result)
                .counter();
        return counter == null ? 0.0d : counter.count();
    }

    protected double stockOversold() {
        Counter counter = meterRegistry.find("stock_oversold").counter();
        return counter == null ? 0.0d : counter.count();
    }

    protected void await(String description, Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep();
        }
        if (!condition.getAsBoolean()) {
            fail("Timed out after " + timeout + " waiting for: " + description);
        }
    }

    protected Optional<SeckillRequest> findRequest(String requestId) {
        return seckillRequestStore.find(requestId);
    }

    private void deleteKeys(String pattern) {
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(50L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            fail("Interrupted while awaiting test condition");
        }
    }
}
