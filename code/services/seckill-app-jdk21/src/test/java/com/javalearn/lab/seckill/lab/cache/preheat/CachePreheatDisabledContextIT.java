package com.javalearn.lab.seckill.lab.cache.preheat;

import com.javalearn.lab.seckill.MySqlRedisIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-context proof that the application boots with the automatic preheat
 * disabled: no startup run is recorded even after the startup delay, while the
 * manual trigger remains usable (its Redis hot list is empty in a fresh test
 * container, so it succeeds with zero loads).
 *
 * <p>Reuses the module's existing MySQL/Rabbit/Redis singletons; the unique
 * {@code app.lab.cache.preheat.enabled=false} property creates one extra
 * context, not one extra container.</p>
 */
@AutoConfigureObservability
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "observability.env=test",
                "observability.instance=test-preheat-off",
                "app.lab.cache.preheat.enabled=false"
        })
class CachePreheatDisabledContextIT extends MySqlRedisIntegrationTestSupport {

    @Autowired
    private LabCachePreheatProperties properties;

    @Autowired
    private CachePreheatService preheatService;

    @Test
    void theAppStartsWithPreheatDisabledAndManualStillRuns() throws Exception {
        assertThat(properties.isEnabled()).isFalse();
        assertThat(preheatService.state().cacheEnabled()).isTrue();

        // The startup delay is 1000ms; waiting past it proves the disabled flag
        // suppressed the automatic run rather than merely being unconfigured.
        Thread.sleep(1_500L);
        assertThat(preheatService.state().lastRun()).isNull();

        PreheatRun manual = preheatService.run(PreheatTrigger.MANUAL);

        assertThat(manual.outcome()).isEqualTo(PreheatRun.Outcome.SUCCESS);
        assertThat(preheatService.state().lastRun()).isNotNull();
        assertThat(preheatService.state().lastRun().trigger()).isEqualTo("manual");
    }
}
