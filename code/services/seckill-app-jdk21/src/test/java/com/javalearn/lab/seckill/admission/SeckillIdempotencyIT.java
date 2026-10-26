package com.javalearn.lab.seckill.admission;

import com.javalearn.lab.common.BizException;
import com.javalearn.lab.seckill.admission.api.CreateSeckillOrderRequest;
import com.javalearn.lab.seckill.admission.api.SeckillResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Idempotency proof: ten concurrent requests with the same key produce one
 * admission, one RabbitMQ message, one durable order and exactly one stock
 * decrement; every other caller replays the same request id.
 */
@AutoConfigureObservability
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "observability.env=test",
                "observability.instance=seckill-idempotency-it",
                "app.seckill.consumer.delay-ms=0",
                "app.seckill.lock.wait-ms=5000",
                "app.seckill.mq.exchange=seckill.order.exchange.idempotency-it",
                "app.seckill.mq.queue=seckill.order.queue.idempotency-it",
                "app.seckill.mq.routing-key=seckill.order.requested.idempotency-it"
        })
class SeckillIdempotencyIT extends SeckillIntegrationTestSupport {

    private static final String IDEMPOTENCY_KEY = "idempotent-key-1";
    private static final int CONCURRENT_CALLS = 10;

    @Test
    void tenConcurrentSameKeyCallsCreateOneRequestAndOneOrder() throws Exception {
        List<AttemptResult> results = runConcurrently(CONCURRENT_CALLS);

        assertThat(results).filteredOn(result -> result.unexpected() != null).isEmpty();
        assertThat(results).filteredOn(result -> result.errorCode() != null).isEmpty();
        assertThat(results).allSatisfy(result -> assertThat(result.response()).isNotNull());

        Set<String> requestIds = results.stream()
                .map(result -> result.response().requestId())
                .collect(Collectors.toSet());
        assertThat(requestIds).hasSize(1);
        String requestId = requestIds.iterator().next();
        assertThat(requestId).isNotBlank();

        awaitOrderCount(1);
        awaitQueueEmpty();
        awaitAuditMessageCount(1);

        assertThat(orderCount()).isEqualTo(1);
        assertThat(dbStock()).isEqualTo(INITIAL_STOCK - 1);
        assertThat(redisStock()).isEqualTo(INITIAL_STOCK - 1);
        assertThat(stockOversold()).isZero();

        assertThat(attempts("accepted")).isEqualTo(1.0d);
        assertThat(attempts("replay")).isEqualTo(CONCURRENT_CALLS - 1);
        assertThat(attempts("sold_out")).isZero();
        assertThat(attempts("lock_timeout")).isZero();
        assertThat(attempts("error")).isZero();
        assertThat(consumerProcessed("created")).isEqualTo(1.0d);
        assertThat(consumerProcessed("failed")).isZero();
        assertThat(findRequest(requestId)).isPresent();
    }

    private List<AttemptResult> runConcurrently(int count) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AttemptResult>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < count; index++) {
                int userIndex = index;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        return new AttemptResult(admissionService.admit(
                                IDEMPOTENCY_KEY,
                                new CreateSeckillOrderRequest(
                                        "same-key-user-" + userIndex, SECKILL_PRODUCT_ID, 1)),
                                null, null);
                    } catch (BizException exception) {
                        return new AttemptResult(
                                null, exception.getErrorCode().code(), null);
                    } catch (Throwable throwable) {
                        return new AttemptResult(null, null, throwable);
                    }
                }));
            }
            assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<AttemptResult> results = new ArrayList<>();
            for (Future<AttemptResult> future : futures) {
                results.add(future.get(120, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private record AttemptResult(SeckillResponse response,
                                 String errorCode,
                                 Throwable unexpected) {
    }
}
