package com.javalearn.lab.chaos.action;

import com.javalearn.lab.chaos.appproxy.AppProxyService;
import com.javalearn.lab.chaos.appproxy.ProxyResponse;
import com.javalearn.lab.chaos.bench.exec.BenchRunExecutor;
import com.javalearn.lab.chaos.bench.exec.K6ContainerGateway;
import com.javalearn.lab.chaos.config.ChaosProperties;
import com.javalearn.lab.chaos.error.ContainerNotFoundException;
import com.javalearn.lab.chaos.schedule.FaultScheduler;
import com.javalearn.lab.chaos.traffic.TrafficService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stage2 B2-04b1: environment-wide {@code POST /chaos/stop-all}.
 *
 * <p>The container revert and Toxiproxy cleanup stay in
 * {@link ChaosActionService#stopAll()}; this orchestrator extends the same
 * button to the three layers that are not chaos actions:</p>
 *
 * <ol>
 *   <li><strong>containers + Toxiproxy</strong> - {@link ChaosActionService#stopAll()}
 *       (unchanged semantics, including the paused flag and APPLYING deferral);</li>
 *   <li><strong>background traffic</strong> - the optional compose traffic
 *       container;</li>
 *   <li><strong>thread lab</strong> - {@code POST /lab/thread/stop-all} relayed
 *       to every configured app instance;</li>
 *   <li><strong>k6</strong> - stop live run containers and remove every
 *       {@code lab.role=k6} container left in the inventory.</li>
 * </ol>
 *
 * <p>The order is fixed and deliberate: container faults are the destructive
 * layer and must be reverted even when every other call fails; traffic then
 * stops generating new load before the thread and k6 layers clean up what is
 * already running. Every step after the first is best-effort - failures are
 * captured in {@link StopAllResult} and logged, never thrown, so a stopped
 * container can never be reported as a failed stop-all.</p>
 *
 * <p>Thread stop-all is a recovery action and must fan out independently: one
 * unreachable instance is reported as {@code failed} for that instance while
 * the other instances still receive the call.</p>
 */
@Service
public class EnvironmentStopAllService {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentStopAllService.class);

    static final String THREAD_STOP_ALL_PATH = "/lab/thread/stop-all";
    static final String THREAD_OK = "ok";
    static final String THREAD_FAILED = "failed";

    /**
     * Statuses whose container is still alive and therefore needs an explicit
     * stop before removal. {@code RESTARTING} is included: a restarting run can
     * otherwise come back between the inventory and the cleanup. EXITED/DEAD
     * containers only need the removal below.
     */
    private static final Set<String> K6_LIVE_STATUSES = Set.of(
            K6ContainerGateway.STATUS_RUNNING,
            K6ContainerGateway.STATUS_CREATED,
            K6ContainerGateway.STATUS_PAUSED,
            K6ContainerGateway.STATUS_RESTARTING);

    private final ChaosActionService chaosActionService;
    private final TrafficService trafficService;
    private final AppProxyService appProxyService;
    private final BenchRunExecutor benchRunExecutor;
    private final ChaosProperties properties;
    private final FaultScheduler faultSchedule;

    public EnvironmentStopAllService(ChaosActionService chaosActionService,
                                     TrafficService trafficService,
                                     AppProxyService appProxyService,
                                     BenchRunExecutor benchRunExecutor,
                                     ChaosProperties properties) {
        this(chaosActionService, trafficService, appProxyService, benchRunExecutor, properties,
                FaultScheduler.NONE);
    }

    /** B3-00b: stop-all also cancels pending run-scoped fault-schedule tasks. */
    @Autowired
    public EnvironmentStopAllService(ChaosActionService chaosActionService,
                                     TrafficService trafficService,
                                     AppProxyService appProxyService,
                                     BenchRunExecutor benchRunExecutor,
                                     ChaosProperties properties,
                                     FaultScheduler faultSchedule) {
        this.chaosActionService = chaosActionService;
        this.trafficService = trafficService;
        this.appProxyService = appProxyService;
        this.benchRunExecutor = benchRunExecutor;
        this.properties = properties;
        this.faultSchedule = faultSchedule == null ? FaultScheduler.NONE : faultSchedule;
    }

    /** One-button restore; never throws for a failing sub-step. */
    public StopAllResult stopAll() {
        StopAllResult base = stopContainersAndNetwork();
        String traffic = stopTraffic();
        Map<String, String> threads = stopThreads();
        K6Sweep k6 = stopK6Runs();

        log.warn("Environment stop-all: containers reverted={} failed={} pending={}, "
                        + "toxiproxy={}, traffic={}, threads={}, k6 stopped={} cleaned={} failed={}",
                base.reverted(), base.failed(), base.pending(), base.toxiproxy().status(),
                traffic, threads, k6.stopped(), k6.cleaned(), k6.failed());
        return base.withEnvironment(traffic, threads, k6.stopped(), k6.cleaned(), k6.failed());
    }

    /**
     * Step 1. {@link ChaosActionService#stopAll()} already reports, per action,
     * what it could not revert, and catches its network-cleanup failure. A
     * throw here can only come from a bug or an unexpected runtime failure; it
     * is reported as one failed item so steps 2-4 still run and the caller still
     * learns the remaining environment state.
     */
    private StopAllResult stopContainersAndNetwork() {
        try {
            return chaosActionService.stopAll();
        } catch (RuntimeException e) {
            log.error("stop-all: container/Toxiproxy rollback threw; continuing with traffic, "
                    + "thread-lab and k6 cleanup: {}", e.toString());
            return new StopAllResult(0, 1, 0,
                    new NetworkResetReport(NetworkResetReport.STATUS_FAILED, 0, 0,
                            List.of("container/network rollback threw: " + e.getMessage())));
        }
    }

    /**
     * Step 2. The traffic container is optional (compose profile), so a missing
     * container is {@code not-running} rather than a failure. Any other error -
     * including a Docker/daemon problem - is {@code failed}.
     */
    private String stopTraffic() {
        try {
            trafficService.stop();
            return StopAllResult.TRAFFIC_STOPPED;
        } catch (ContainerNotFoundException e) {
            // TrafficService.stop() words this as "not created"; the profile was
            // never installed, which is a normal state for this lab.
            return StopAllResult.TRAFFIC_NOT_RUNNING;
        } catch (RuntimeException e) {
            log.error("stop-all: background traffic did not stop: {}", e.toString());
            return StopAllResult.TRAFFIC_FAILED;
        }
    }

    /**
     * Step 3. One POST per configured instance, each isolated: the app proxy
     * handles the internal token, and stop-all is a recovery action that must
     * not be blocked by a down instance. Only a 2xx answer counts as {@code ok}.
     */
    private Map<String, String> stopThreads() {
        Map<String, String> outcome = new LinkedHashMap<>();
        List<String> instances = properties.getAppProxy().getInstances();
        if (instances == null || instances.isEmpty()) {
            return outcome;
        }
        for (String instance : instances) {
            outcome.put(instance, stopThreads(instance));
        }
        return outcome;
    }

    private String stopThreads(String instance) {
        try {
            ProxyResponse response = appProxyService.forward(instance, "POST",
                    THREAD_STOP_ALL_PATH, null, null);
            if (response != null && response.status() >= 200 && response.status() < 300) {
                return THREAD_OK;
            }
            log.warn("stop-all: thread stop-all on {} answered status {}", instance,
                    response == null ? "no response" : response.status());
            return THREAD_FAILED;
        } catch (RuntimeException e) {
            log.warn("stop-all: thread stop-all on {} failed: {}", instance, e.toString());
            return THREAD_FAILED;
        }
    }

    /**
     * Step 4. The inventory is read once: live containers get a stop first,
     * then every listed container is cleaned up (removed). An EXITED container
     * is only cleaned. A container without a usable {@code runId} cannot be
     * addressed by the executor and is counted as failed instead of silently
     * left behind.
     */
    private K6Sweep stopK6Runs() {
        List<K6ContainerGateway.ContainerSummary> containers;
        try {
            containers = benchRunExecutor.sweepOrphans();
        } catch (RuntimeException e) {
            log.error("stop-all: k6 container inventory failed: {}", e.toString());
            return new K6Sweep(0, 0, 1);
        }
        if (containers == null) {
            log.error("stop-all: k6 inventory returned no list");
            return new K6Sweep(0, 0, 1);
        }

        int stopped = 0;
        int cleaned = 0;
        int failed = 0;
        for (K6ContainerGateway.ContainerSummary container : containers) {
            if (container == null) {
                failed++;
                continue;
            }
            String runId = container.runId();
            if (runId == null || runId.isBlank()) {
                log.warn("stop-all: k6 container {} has no run-id label; skipping it",
                        container.containerName());
                failed++;
                continue;
            }

            // B3-00b: a pending scheduled fault must not fire after stop-all
            // stopped its k6. Best-effort: a cancel failure never blocks cleanup.
            try {
                faultSchedule.cancel(runId);
            } catch (RuntimeException e) {
                log.warn("stop-all: cancelling the fault schedule of run {} failed: {}",
                        runId, e.toString());
            }

            boolean problem = false;
            if (isLive(container.status())) {
                try {
                    if (benchRunExecutor.stop(runId)) {
                        stopped++;
                    }
                } catch (RuntimeException e) {
                    problem = true;
                    log.warn("stop-all: stopping k6 run {} ({}) failed: {}", runId,
                            container.containerName(), e.toString());
                }
            }
            try {
                if (benchRunExecutor.cleanup(runId)) {
                    cleaned++;
                }
            } catch (RuntimeException e) {
                problem = true;
                log.warn("stop-all: cleaning up k6 run {} ({}) failed: {}", runId,
                        container.containerName(), e.toString());
            }
            if (problem) {
                failed++;
            }
        }
        return new K6Sweep(stopped, cleaned, failed);
    }

    private static boolean isLive(String status) {
        return status != null && K6_LIVE_STATUSES.contains(status.toUpperCase());
    }

    /** Counts of one k6 sweep; a container is failed at most once. */
    private record K6Sweep(int stopped, int cleaned, int failed) {
    }
}
