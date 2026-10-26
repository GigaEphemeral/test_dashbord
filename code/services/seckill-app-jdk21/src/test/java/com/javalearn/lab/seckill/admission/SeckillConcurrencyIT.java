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
import java.util.function.IntFunction;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency proof for the async path: 40 distinct idempotency keys against
 * 20 Redis units must yield exactly 20 admissions and 20 sold-out responses,
 * exactly 20 durable orders, zero stock in MySQL and Redis, and no oversell.
 */
@AutoConfigureObservability
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "observability.env=test",
                "observability.instance=seckill-concurrency-it",
                "app.seckill.consumer.delay-ms=0",
                "app.seckill.lock.wait-ms=5000",
                "app.seckill.mq.exchange=seckill.order.exchange.concurrency-it",
                "app.seckill.mq.queue=seckill.order.queue.concurrency-it",
                "app.seckill.mq.routing-key=seckill.order.requested.concurrency-it"
        })
class SeckillConcurrencyIT extends SeckillIntegrationTestSupport {

    private static final int ATTEMPTS = 40;
    private static final int EXPECTED_ACCEPTED = 20;

    @Test
    void fortyConcurrentRequestsSellExactlyTwentyWithoutOversell() throws Exception {
        List<AttemptResult> results = runConcurrently(ATTEMPTS, index ->
                new CreateSeckillOrderRequest(
                        "concurrency-user-" + index, SECKILL_PRODUCT_ID, 1));

        List<AttemptResult> accepted = results.stream()
                .filter(result -> result.response() != null)
                .collect(Collectors.toList());
        List<AttemptResult> conflicts = results.stream()
                .filter(result -> "40901".equals(result.errorCode()))
                .collect(Collectors.toList());
        List<AttemptResult> unexpected = results.stream()
                .filter(result -> result.unexpected() != null)
                .collect(Collectors.toList());

        assertThat(unexpected).isEmpty();
        assertThat(accepted).hasSize(EXPECTED_ACCEPTED);
        assertThat(conflicts).hasSize(ATTEMPTS - EXPECTED_ACCEPTED);
        Set<String> requestIds = accepted.stream()
                .map(result -> result.response().requestId())
                .collect(Collectors.toSet());
        assertThat(requestIds).hasSize(EXPECTED_ACCEPTED);

        awaitOrderCount(EXPECTED_ACCEPTED);
        awaitQueueEmpty();
        awaitAuditMessageCount(EXPECTED_ACCEPTED);

        assertThat(orderCount()).isEqualTo(EXPECTED_ACCEPTED);
        assertThat(ordersForProduct()).isEqualTo(EXPECTED_ACCEPTED);
        assertThat(dbStock()).isZero();
        assertThat(redisStock()).isZero();
        assertThat(minimumDbStock()).isGreaterThanOrEqualTo(0);
        assertThat(stockOversold()).isZero();

        assertThat(attempts("accepted")).isEqualTo(EXPECTED_ACCEPTED);
        assertThat(attempts("sold_out")).isEqualTo(ATTEMPTS - EXPECTED_ACCEPTED);
        assertThat(attempts("lock_timeout")).isZero();
        assertThat(attempts("error")).isZero();
        assertThat(consumerProcessed("created")).isEqualTo(EXPECTED_ACCEPTED);
        assertThat(consumerProcessed("failed")).isZero();
    }

    private List<AttemptResult> runConcurrently(
            int count,
            IntFunction<CreateSeckillOrderRequest> requestFactory) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AttemptResult>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < count; index++) {
                CreateSeckillOrderRequest request = requestFactory.apply(index);
                String idempotencyKey = "concurrency-key-" + index;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        return new AttemptResult(
                                admissionService.admit(idempotencyKey, request), null, null);
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
