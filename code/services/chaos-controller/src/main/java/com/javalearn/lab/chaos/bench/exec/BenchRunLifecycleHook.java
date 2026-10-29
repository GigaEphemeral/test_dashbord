package com.javalearn.lab.chaos.bench.exec;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.bench.BenchScenarioCatalog;
import com.javalearn.lab.chaos.bench.BenchScenarioCatalog.Snapshot;
import com.javalearn.lab.chaos.error.BenchUnavailableException;
import com.javalearn.lab.chaos.error.LedgerRunNotFoundException;
import com.javalearn.lab.chaos.ledger.RunLedgerRepository;
import com.javalearn.lab.chaos.ledger.RunLifecycleHook;
import com.javalearn.lab.chaos.ledger.RunRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Stage2 B2-02b-2a: the real {@link RunLifecycleHook} behind the run state
 * machine. It reads the run's scenario and its persisted {@code request}
 * parameters, resolves the host k6 directory, plans the launch and hands the
 * plan to {@link BenchRunExecutor}.
 *
 * <p>It deliberately does not read or write run status, take the environment
 * lock or touch the ledger writer: {@code RunLifecycleService} owns those. Any
 * rejection or availability failure is rethrown unchanged (with launch context
 * added for {@link BenchUnavailableException}) so the service's existing
 * FAILED + lock-release path runs.</p>
 *
 * <p>Details returned to the service use a {@code k6*} prefix:
 * {@code k6Container}, {@code k6ContainerId}, {@code k6Scenario},
 * {@code k6ReportPath}, {@code k6Status}, {@code k6Stopped},
 * {@code k6ExitCode}, {@code k6ReportAvailable}; since Stage2 B2-03a also
 * {@code k6TrafficMode}, {@code k6TargetInstance}, {@code k6BypassNginx},
 * {@code k6TrafficMixed} and {@code k6Upstream} (the mirrored Nginx upstream
 * weights).</p>
 */
public class BenchRunLifecycleHook implements RunLifecycleHook {

    private static final Logger log = LoggerFactory.getLogger(BenchRunLifecycleHook.class);
    private static final String PARAM_GROUP = "request";

    private final RunLedgerRepository repository;
    private final BenchScenarioCatalog catalog;
    private final BenchLaunchPlanner planner;
    private final BenchScriptsHostDirResolver resolver;
    private final BenchRunExecutor executor;
    private final ObjectMapper objectMapper;

    public BenchRunLifecycleHook(RunLedgerRepository repository, BenchScenarioCatalog catalog,
                                 BenchLaunchPlanner planner, BenchScriptsHostDirResolver resolver,
                                 BenchRunExecutor executor, ObjectMapper objectMapper) {
        this.repository = repository;
        this.catalog = catalog;
        this.planner = planner;
        this.resolver = resolver;
        this.executor = executor;
        this.objectMapper = objectMapper;
    }

    @Override
    public LaunchResult launch(String runId) {
        RunRecord run = repository.findRun(runId)
                .orElseThrow(() -> new LedgerRunNotFoundException("run not found: " + runId));
        Map<String, Object> params = readParams(run);
        String hostDir;
        try {
            hostDir = resolver.resolve();
        } catch (BenchUnavailableException e) {
            throw launchUnavailable(run, null, e);
        }
        try {
            Snapshot snapshot = catalog.load();
            BenchLaunchPlan plan = planner.plan(snapshot, run.runId(), run.scenario(), params,
                    hostDir, run.nodeList());
            BenchExecutionState state = executor.start(plan);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("k6Container", state.containerName());
            if (state.containerId() != null) {
                details.put("k6ContainerId", state.containerId());
            }
            details.put("k6Scenario", plan.scenario());
            details.put("k6ReportPath", plan.reportContainerPath());
            details.put("k6Status", state.status());
            BenchLaunchPlan.Traffic traffic = plan.traffic();
            details.put("k6TrafficMode", traffic.mode());
            details.put("k6TargetInstance", traffic.targetInstance());
            details.put("k6BypassNginx", traffic.bypassNginx());
            details.put("k6TrafficMixed", traffic.mixed());
            details.put("k6Upstream", traffic.upstream());
            log.info("Launched k6 for run {}: container={}, scenario={}, trafficMode={}, "
                            + "target={}, hostDir={}",
                    run.runId(), state.containerName(), plan.scenario(), traffic.mode(),
                    traffic.targetInstance(), hostDir);
            return new LaunchResult(details);
        } catch (BenchUnavailableException e) {
            throw launchUnavailable(run, hostDir, e);
        }
    }

    @Override
    public LaunchResult stop(String runId) {
        boolean stopped = executor.stop(runId);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("k6Stopped", stopped);
        try {
            BenchExecutionState state = executor.state(runId);
            details.put("k6Status", state.status());
            if (state.containerName() != null) {
                details.put("k6Container", state.containerName());
            }
            if (state.exitCode() != null) {
                details.put("k6ExitCode", state.exitCode());
            }
            details.put("k6ReportAvailable", state.reportAvailable());
        } catch (RuntimeException e) {
            // The stop itself succeeded; a failed state read must not turn the
            // stop into an error (the service would record k6StopError).
            details.put("k6StateError", e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("Could not read the k6 state after stopping run {}: {}", runId, e.toString());
        }
        return new LaunchResult(details);
    }

    @Override
    public LaunchResult cleanupArtifacts(String runId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("k6ContainerRemoved", executor.cleanup(runId));
        details.put("k6ReportRemoved", executor.deleteReport(runId));
        return new LaunchResult(details);
    }

    /**
     * The {@code request} group is the flat parameter map persisted by
     * {@code POST /lab/runs}. A missing group is an empty map (the planner then
     * uses manifest defaults); malformed JSON is an availability problem, not a
     * silent default.
     */
    private Map<String, Object> readParams(RunRecord run) {
        Optional<RunLedgerRepository.RunParamRow> row = repository.findParams(run.runId()).stream()
                .filter(param -> PARAM_GROUP.equals(param.groupName()))
                .findFirst();
        if (row.isEmpty() || row.get().payloadJson() == null
                || row.get().payloadJson().isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> params = objectMapper.readValue(row.get().payloadJson(),
                    new TypeReference<Map<String, Object>>() {
                    });
            return params == null ? Map.of() : new LinkedHashMap<>(params);
        } catch (IOException e) {
            throw new BenchUnavailableException("run " + run.runId() + " (scenario '"
                    + run.scenario() + "') has unreadable request params (lab_run_param group '"
                    + PARAM_GROUP + "'): " + e.getMessage(), e);
        }
    }

    private static BenchUnavailableException launchUnavailable(RunRecord run, String hostDir,
                                                               BenchUnavailableException cause) {
        String location = hostDir == null ? "(unresolved)" : "'" + hostDir + "'";
        return new BenchUnavailableException("cannot launch k6 for run " + run.runId()
                + " (scenario '" + run.scenario() + "', hostDir " + location + "): "
                + cause.getMessage(), cause);
    }
}
