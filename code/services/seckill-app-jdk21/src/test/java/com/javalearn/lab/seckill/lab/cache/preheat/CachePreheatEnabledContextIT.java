package com.javalearn.lab.seckill.lab.cache.preheat;

import com.javalearn.lab.seckill.MySqlRedisIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-context proof that the application boots with preheat enabled and that
 * the asynchronous startup run actually happens after
 * {@code ApplicationReadyEvent} (design 8.4: "not during boot").
 *
 * <p>Uses its own property set (short startup delay, one-hour periodic) so the
 * assertions are deterministic and a periodic run can never overwrite the
 * startup result; the MySQL/Rabbit/Redis singletons are still shared with the
 * existing suite, so no new container is started.</p>
 */
@AutoConfigureObservability
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "observability.env=test",
                "observability.instance=test-preheat-on",
                "app.lab.cache.preheat.load.startup-delay-ms=100",
                "app.lab.cache.preheat.load.periodic-interval-seconds=3600"
        })
class CachePreheatEnabledContextIT extends MySqlRedisIntegrationTestSupport {

    @Autowired
    private LabCachePreheatProperties properties;

    @Autowired
    private CachePreheatService preheatService;

    @Test
    void theAppStartsWithPreheatEnabledAndTheStartupRunHappens() throws Exception {
        assertThat(properties.isEnabled()).isTrue();
        assertThat(preheatService.state().cacheEnabled()).isTrue();

        awaitLastRun(Duration.ofSeconds(10));

        assertThat(preheatService.state().lastRun()).isNotNull();
        assertThat(preheatService.state().lastRun().trigger()).isEqualTo("startup");
        assertThat(preheatService.state().lastRun().outcome()).isIn("success", "cache-disabled");
    }

    private void awaitLastRun(Duration budget) throws InterruptedException {
        long deadline = System.nanoTime() + budget.toNanos();
        while (System.nanoTime() < deadline) {
            if (preheatService.state().lastRun() != null) {
                return;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("No startup preheat run happened within " + budget);
    }
}
