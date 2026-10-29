package com.javalearn.lab.chaos.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.action.ChaosActionService;
import com.javalearn.lab.chaos.config.ChaosProperties;
import com.javalearn.lab.chaos.ledger.FaultEventService;
import com.javalearn.lab.chaos.ledger.LedgerProperties;
import com.javalearn.lab.chaos.ledger.OperationService;
import com.javalearn.lab.chaos.ledger.RunLedgerRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * B3-00b wiring for the run-scoped fault timeline.
 *
 * <p>Kept out of {@code LedgerConfig}: the executor and validator are their own
 * concern. Only the two run-lifecycle/ledger bean methods need the new
 * dependency, which is the minimal ledger-side change.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FaultScheduleProperties.class)
public class FaultScheduleConfig {

    /**
     * Dedicated daemon pool. It is deliberately not the chaos TTL scheduler (a
     * slow fault action must never delay a scheduled revert) nor the ledger
     * operation pool.
     */
    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService faultScheduleScheduler(FaultScheduleProperties properties) {
        AtomicInteger sequence = new AtomicInteger();
        String prefix = properties.getThreadName();
        return Executors.newScheduledThreadPool(properties.getPoolSize(), runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    public FaultScheduleValidator faultScheduleValidator(LedgerProperties ledgerProperties,
                                                         ChaosProperties chaosProperties,
                                                         FaultScheduleProperties scheduleProperties) {
        return new FaultScheduleValidator(ledgerProperties, chaosProperties, scheduleProperties);
    }

    @Bean
    public FaultScheduleService faultScheduleService(RunLedgerRepository repository,
                                                     ObjectMapper objectMapper,
                                                     ChaosActionService chaosActionService,
                                                     FaultEventService faultEventService,
                                                     OperationService operationService,
                                                     ScheduledExecutorService faultScheduleScheduler,
                                                     FaultScheduleValidator faultScheduleValidator,
                                                     Clock ledgerClock) {
        return new FaultScheduleService(repository, objectMapper, chaosActionService,
                faultEventService, operationService, faultScheduleScheduler,
                faultScheduleValidator, ledgerClock);
    }
}
