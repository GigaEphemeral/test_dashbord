package com.javalearn.lab.seckill.admission;

import com.javalearn.lab.seckill.admission.api.CreateSeckillOrderRequest;
import com.javalearn.lab.seckill.admission.api.SeckillResponse;
import com.javalearn.lab.seckill.admission.domain.SeckillRequestStatus;
import com.javalearn.lab.seckill.lab.toggle.LabToggle;
import com.javalearn.lab.seckill.lab.toggle.LabToggleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-context runtime-toggle proof.
 *
 * <p>With {@code idempotency} off, two requests with the same key take two
 * separate Redis stock holds and publish two messages, but the DB unique key
 * still allows exactly one durable order: the toggle demonstrates the
 * app-layer gap, not a database guarantee removal. With {@code mq} off at
 * runtime, the request uses the synchronous DB path and never touches Redis
 * stock or the broker.</p>
 */
@AutoConfigureObservability
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "observability.env=test",
                "observability.instance=seckill-toggle-it",
                "app.toggles.idempotency=false",
                "app.seckill.consumer.delay-ms=0",
                "app.seckill.lock.wait-ms=5000",
                "app.seckill.mq.exchange=seckill.order.exchange.toggle-it",
                "app.seckill.mq.queue=seckill.order.queue.toggle-it",
                "app.seckill.mq.routing-key=seckill.order.requested.toggle-it"
        })
class SeckillToggleIT extends SeckillIntegrationTestSupport {

    private static final String KEY = "toggle-idempotency-key";

    @Autowired
    private LabToggleService toggles;

    @Test
    void idempotencyOffLetsTheDbUniqueKeyPreventDuplicateOrders() {
        assertThat(toggles.isEnabled(LabToggle.IDEMPOTENCY)).isFalse();

        SeckillResponse first = admissionService.admit(KEY, request());
        SeckillResponse second = admissionService.admit(KEY, request());

        assertThat(first.status()).isEqualTo("ACCEPTED");
        assertThat(second.status()).isEqualTo("ACCEPTED");
        assertThat(second.requestId()).isNotEqualTo(first.requestId());

        awaitRequestStatus(first.requestId(), SeckillRequestStatus.CREATED, DEFAULT_AWAIT);
        awaitRequestStatus(second.requestId(), SeckillRequestStatus.CREATED, DEFAULT_AWAIT);
        awaitQueueEmpty();

        assertThat(ordersForProduct()).isEqualTo(1);
        assertThat(dbStock()).isEqualTo(INITIAL_STOCK - 1);
        // Documented app-layer gap: the second request reserved Redis stock
        // again, so the two counters drift by one while the DB stays correct.
        assertThat(redisStock()).isEqualTo(INITIAL_STOCK - 2);
        assertThat(redisTemplate.hasKey(idempotencyKey(KEY))).isFalse();
        assertThat(attempts("replay")).isZero();
        assertThat(stockOversold()).isZero();
    }

    @Test
    void mqOffAtRuntimeUsesTheSynchronousDbPath() {
        toggles.update("mq", false);
        try {
            assertThat(admissionService.isAsyncEnabled()).isFalse();

            SeckillResponse response = admissionService.admit(KEY, request());

            assertThat(response.status()).isEqualTo("CREATED");
            assertThat(response.requestId()).isNull();
            assertThat(response.orderId()).isNotNull();
            assertThat(ordersForProduct()).isEqualTo(1);
            assertThat(dbStock()).isEqualTo(INITIAL_STOCK - 1);
            // The synchronous path never reserves Redis stock or claims the key.
            assertThat(redisStock()).isEqualTo(INITIAL_STOCK);
            assertThat(redisTemplate.hasKey(idempotencyKey(KEY))).isFalse();
        } finally {
            toggles.update("mq", true);
        }
        assertThat(admissionService.isAsyncEnabled()).isTrue();
    }

    private static CreateSeckillOrderRequest request() {
        return new CreateSeckillOrderRequest("toggle-user", SECKILL_PRODUCT_ID, 1);
    }
}
