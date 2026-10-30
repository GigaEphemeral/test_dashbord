package com.javalearn.lab.seckill.jdk8.lab.cache.preheat;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.event.ApplicationReadyEvent;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The enabled flag gates the automatic runs (startup + periodic) but never the
 * hot-list flush; scheduling is idempotent and a failing task cannot escape the
 * daemon scheduler.
 */
class CachePreheatLifecycleTest {

    private final LabCachePreheatProperties properties = TestLabCachePreheatProperties.defaults();
    private final CachePreheatService preheatService = mock(CachePreheatService.class);
    private final HotKeyTracker tracker = mock(HotKeyTracker.class);
    private final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    private final CachePreheatLifecycle lifecycle =
            new CachePreheatLifecycle(properties, preheatService, tracker, scheduler);

    @Test
    void enabledSchedulesFlushStartupAndPeriodic() {
        lifecycle.onApplicationEvent(mock(ApplicationReadyEvent.class));

        verify(scheduler).scheduleAtFixedRate(any(Runnable.class), eq(10L), eq(10L),
                eq(TimeUnit.SECONDS));
        verify(scheduler).schedule(any(Runnable.class), eq(0L), eq(TimeUnit.MILLISECONDS));
        verify(scheduler).scheduleAtFixedRate(any(Runnable.class), eq(300L), eq(300L),
                eq(TimeUnit.SECONDS));
    }

    @Test
    void theScheduledTasksInvokeTheFlushAndBothPreheatTriggers() {
        lifecycle.onApplicationEvent(mock(ApplicationReadyEvent.class));

        ArgumentCaptor<Runnable> fixedRate = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler, times(2)).scheduleAtFixedRate(fixedRate.capture(), anyLong(),
                anyLong(), eq(TimeUnit.SECONDS));
        Runnable flush = fixedRate.getAllValues().get(0);
        flush.run();
        verify(tracker).publishTopN();

        ArgumentCaptor<Runnable> startup = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(startup.capture(), eq(0L), eq(TimeUnit.MILLISECONDS));
        startup.getValue().run();

        verify(preheatService).run(PreheatTrigger.STARTUP);
        // The two fixed-rate tasks are the flush and the periodic reheat; the
        // periodic task carries the PERIODIC trigger (verified separately by
        // running only the second captured task).
        verify(preheatService, never()).run(PreheatTrigger.PERIODIC);
        Runnable periodic = fixedRate.getAllValues().get(1);
        periodic.run();
        verify(preheatService).run(PreheatTrigger.PERIODIC);
    }

    @Test
    void disabledSchedulesOnlyTheHotListFlush() {
        properties.setEnabled(false);

        lifecycle.onApplicationEvent(mock(ApplicationReadyEvent.class));

        verify(scheduler).scheduleAtFixedRate(any(Runnable.class), eq(10L), eq(10L),
                eq(TimeUnit.SECONDS));
        verify(scheduler, never()).schedule(any(Runnable.class), anyLong(),
                any(TimeUnit.class));
        verify(scheduler, never()).scheduleAtFixedRate(any(Runnable.class), eq(300L), eq(300L),
                eq(TimeUnit.SECONDS));
    }

    @Test
    void startIsIdempotent() {
        lifecycle.onApplicationEvent(mock(ApplicationReadyEvent.class));
        lifecycle.onApplicationEvent(mock(ApplicationReadyEvent.class));

        verify(scheduler, times(1)).schedule(any(Runnable.class), anyLong(),
                any(TimeUnit.class));
        verify(scheduler, times(1)).scheduleAtFixedRate(any(Runnable.class), eq(300L), eq(300L),
                eq(TimeUnit.SECONDS));
    }

    @Test
    void aFailingPreheatTaskIsSwallowedSoTheFixedRateSurvives() {
        lifecycle.onApplicationEvent(mock(ApplicationReadyEvent.class));
        ArgumentCaptor<Runnable> startup = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(startup.capture(), eq(0L), eq(TimeUnit.MILLISECONDS));
        doThrow(new IllegalStateException("boom")).when(preheatService)
                .run(PreheatTrigger.STARTUP);

        assertThatCode(() -> startup.getValue().run()).doesNotThrowAnyException();
    }
}
