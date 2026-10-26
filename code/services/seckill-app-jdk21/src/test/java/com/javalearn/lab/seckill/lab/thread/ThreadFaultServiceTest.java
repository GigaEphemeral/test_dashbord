package com.javalearn.lab.seckill.lab.thread;

import com.javalearn.lab.common.BizException;
import com.javalearn.lab.seckill.lab.api.ThreadActionView;
import com.javalearn.lab.seckill.lab.thread.ThreadLabTestSupport.ManualTtlScheduler;
import com.javalearn.lab.seckill.lab.thread.ThreadLabTestSupport.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.javalearn.lab.seckill.lab.thread.ThreadLabTestSupport.clock;
import static com.javalearn.lab.seckill.lab.thread.ThreadLabTestSupport.liveThreadIds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The M5-1 lifecycle contract on top of {@link ThreadFaultService}: TTL auto-revert,
 * manual hold, pause-freeze / resume-remainder, {@code stop-all}, duplicate-mode
 * rejection and the {@code oom} safety gate — all driven deterministically through
 * an injected {@link MutableClock} and {@link ManualTtlScheduler}.
 */
class ThreadFaultServiceTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MutableClock clock = clock(1_000L);
    private final ManualTtlScheduler scheduler = new ManualTtlScheduler();
    private final LabThreadProperties properties = TestLabThreadProperties.defaults();
    private final ThreadFaultService service =
            new ThreadFaultService(properties, clock, scheduler, new ThreadLabMetrics(registry));

    @Test
    void autoFaultAutoRevertsAtTtlAndCleansUp() {
        ThreadActionView started = service.start("deadlock", "auto", null, null);
        assertThat(started.state()).isEqualTo("ACTIVE");
        assertThat(started.threadIds()).isNotEmpty();
        assertThat(started.remainingTtlMillis()).isEqualTo(Duration.ofSeconds(120).toMillis());
        assertThat(scheduler.latest().delay()).isEqualTo(Duration.ofSeconds(120));

        // The TTL timer fires (deterministically) and reverts.
        scheduler.fireAllPending();

        assertThat(service.list()).isEmpty();
        assertThatThreadIdsDied(started.threadIds());
        assertThat(scheduler.pendingCount()).isZero();
    }

    @Test
    void manualHoldNeverAutoReverts() {
        ThreadActionView started = service.start("cpu-burn", "manual", null, null);
        assertThat(started.hold()).isEqualTo("manual");
        assertThat(started.remainingTtlMillis()).isEqualTo(-1L);
        assertThat(scheduler.armed).as("manual arms no TTL timer").isEmpty();

        clock.advance(Duration.ofHours(1));
        scheduler.fireAllPending(); // nothing armed -> nothing reverts

        assertThat(service.list()).hasSize(1);
        service.stop("cpu-burn");
        assertThat(service.list()).isEmpty();
    }

    @Test
    void pauseFreezesRemainingTtlAndResumeSchedulesOnlyTheRemainder() {
        service.start("stuck", "auto", 100L, null);
        assertThat(scheduler.latest().delay()).isEqualTo(Duration.ofSeconds(100));

        clock.advance(Duration.ofSeconds(30));
        ThreadActionView paused = service.pause("stuck");
        assertThat(paused.state()).isEqualTo("PAUSED");
        assertThat(paused.remainingTtlMillis()).isEqualTo(Duration.ofSeconds(70).toMillis());
        assertThat(scheduler.pendingCount()).as("pause cancels the armed timer").isZero();

        // A long pause must not consume the frozen budget.
        clock.advance(Duration.ofMinutes(10));

        ThreadActionView resumed = service.resume("stuck");
        assertThat(resumed.state()).isEqualTo("ACTIVE");
        assertThat(scheduler.latest().delay()).as("resume re-arms the frozen remainder")
                .isEqualTo(Duration.ofSeconds(70));
        assertThat(resumed.remainingTtlMillis()).isEqualTo(Duration.ofSeconds(70).toMillis());
        assertThat(scheduler.pendingCount()).isEqualTo(1);

        scheduler.fireAllPending();
        assertThat(service.list()).isEmpty();
    }

    @Test
    void stopAllRemovesEveryFaultAndLeaksNoThreads() {
        ThreadActionView deadlock = service.start("deadlock", "manual", null, null);
        ThreadActionView stuck = service.start("stuck", "manual", null, null);
        ThreadActionView starvation = service.start("starvation", "auto", 120L, null);
        assertThat(service.list()).hasSize(3);

        List<ThreadActionView> stopped = service.stopAll();

        assertThat(stopped).hasSize(3);
        assertThat(service.list()).as("registry emptied").isEmpty();
        assertThat(scheduler.pendingCount()).isZero();
        List<Long> all = new ArrayList<>();
        all.addAll(deadlock.threadIds());
        all.addAll(stuck.threadIds());
        all.addAll(starvation.threadIds());
        assertThatThreadIdsDied(all);
    }

    @Test
    void sameModeTwiceIsRejectedWithConflict() {
        service.start("deadlock", "auto", null, null);
        assertThatThrownBy(() -> service.start("deadlock", "auto", null, null))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode().code())
                .isEqualTo("40901");
        assertThat(service.list()).hasSize(1);
    }

    @Test
    void repeatedConcurrentStartsOfOneModeWinOnlyOnce() throws Exception {
        int racers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch ready = new CountDownLatch(racers);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        List<Throwable> unexpected = new ArrayList<>();
        try {
            for (int i = 0; i < racers; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        service.start("deadlock", "auto", null, null);
                        successes.incrementAndGet();
                    } catch (BizException conflict) {
                        if ("40901".equals(conflict.getErrorCode().code())) {
                            conflicts.incrementAndGet();
                        } else {
                            unexpected.add(conflict);
                        }
                    }
                    return null;
                });
            }
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
            service.stopAll();
        }

        assertThat(unexpected).isEmpty();
        assertThat(successes.get()).as("exactly one injection wins").isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(racers - 1);
    }

    @Test
    void oomIsRefusedUnlessInstanceIsMarkedThrowaway() {
        assertThat(properties.getOom().isThrowawayInstance()).isFalse();
        assertThatThrownBy(() -> service.start("oom", "manual", null, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("throwaway-instance");
        // Refusal must not leave a half-created fault behind.
        assertThat(service.list()).isEmpty();
    }

    @Test
    void unknownModeAndUnknownHoldAreInvalidArguments() {
        assertThatThrownBy(() -> service.start("nope", "auto", null, null))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.start("deadlock", "forever", null, null))
                .isInstanceOf(BizException.class);
        assertThat(service.list()).isEmpty();
    }

    private void assertThatThreadIdsDied(List<Long> ids) {
        Set<Long> live = liveThreadIds();
        for (long id : ids) {
            assertThat(live).as("thread %d must be gone after cleanup", id).doesNotContain(id);
        }
    }
}
