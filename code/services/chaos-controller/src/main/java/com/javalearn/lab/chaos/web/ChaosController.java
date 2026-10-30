package com.javalearn.lab.chaos.web;

import com.javalearn.lab.chaos.action.ChaosActionRequest;
import com.javalearn.lab.chaos.action.ChaosActionService;
import com.javalearn.lab.chaos.action.ChaosActionType;
import com.javalearn.lab.chaos.action.ChaosActionView;
import com.javalearn.lab.chaos.action.EnvironmentStopAllService;
import com.javalearn.lab.chaos.action.StopAllResult;
import com.javalearn.lab.chaos.ledger.FaultEventService;
import com.javalearn.lab.chaos.ledger.IdempotencyKeys;
import com.javalearn.lab.chaos.ledger.OperationContext;
import com.javalearn.lab.chaos.ledger.OperationExecution;
import com.javalearn.lab.chaos.ledger.OperationOutcome;
import com.javalearn.lab.chaos.ledger.OperationRequest;
import com.javalearn.lab.chaos.ledger.OperationService;
import com.javalearn.lab.common.Result;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Value;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Chaos action API. Token-protected by {@code TokenFilter}; every target is
 * whitelisted by the gateway before any Docker call.
 *
 * <p>B1-02 wraps both mutating endpoints in the operation contract: one
 * {@code lab_operation} row per request, an {@code operationId} in the answer,
 * optional {@code Idempotency-Key} replay, and a dedicated worker pool with a
 * configurable sync budget. When the budget elapses the API answers 202 with the
 * operation view (PENDING_CONFIRMATION) instead of a transport timeout - the
 * fault action keeps running and the caller polls
 * {@code GET /lab/operations/{operationId}}. A definite failure inside the
 * budget keeps the existing mapping (404/403/409/503).</p>
 *
 * <p>Idempotent replay (200) returns the stored operation, not the original
 * action view: after a replay there is no new action and no new {@code id}.
 * Read-only {@code GET /chaos/actions} is unchanged.</p>
 *
 * <p>B2-04a: an optional {@code runId} attaches a successful fault to that run's
 * timeline ({@code lab_fault_event}); the run must exist (404 otherwise) but does
 * not have to be RUNNING. The action result never depends on the timeline write.
 * {@code GET /chaos/actions} and the POST action view now carry {@code paused}
 * and {@code remainingMs}.</p>
 *
 * <p>B2-04b1: {@code POST /chaos/stop-all} is delegated to
 * {@link EnvironmentStopAllService}, which keeps the container + Toxiproxy
 * rollback here and extends the same button to background traffic, the thread
 * lab and k6 run containers. The operation contract is unchanged: still
 * {@code submitBlocking} with the caller's idempotency key and the
 * {@code environment/lab/stop-all} target. The after-summary now also records
 * the traffic/thread/k6 outcomes so a replayed operation explains what was
 * cleaned even though the sub-steps do not run again.</p>
 */
@RestController
@RequestMapping("/chaos")
public class ChaosController {

    private static final Pattern APP_INSTANCE = Pattern.compile("app-\\d+");

    private final ChaosActionService chaosActionService;
    private final EnvironmentStopAllService environmentStopAllService;
    private final OperationService operations;
    private final FaultEventService faultEvents;

    /** {@code app-1} is the console's instance name; Docker needs {@code jl-app-1}. */
    @Value("${lab.instance.container-prefix:jl-}")
    private String containerPrefix = "jl-";

    public ChaosController(ChaosActionService chaosActionService,
                           EnvironmentStopAllService environmentStopAllService,
                           OperationService operations,
                           FaultEventService faultEvents) {
        this.chaosActionService = chaosActionService;
        this.environmentStopAllService = environmentStopAllService;
        this.operations = operations;
        this.faultEvents = faultEvents;
    }

    @PostMapping("/instances/{name}/actions")
    public ResponseEntity<Result<?>> apply(
            @PathVariable String name,
            @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) ChaosActionRequest request) {
        String key = IdempotencyKeys.normalize(idempotencyKey);
        String target = resolveTarget(name);
        ChaosActionType type = ChaosActionType.maybeParse(request == null ? null : request.action())
                .orElse(null);
        String action = type != null ? type.wireName()
                : (request == null || request.action() == null ? "unknown" : request.action());
        Integer ttl = request == null ? null : request.ttlSeconds();
        String runId = normalize(request == null ? null : request.runId());
        // 404 for a reachable ledger with no such run; a database outage lets the
        // fault run without a timeline row (fault actions are non-strict by design).
        String runStatus = runId == null ? null : faultEvents.validateRun(runId);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("container", target);
        before.put("action", action);
        if (runId != null) {
            before.put("runId", runId);
            before.put("runStatus", runStatus);
        }

        OperationExecution execution = operations.submit(new OperationRequest(runId, "container", target,
                action, ttl, key, before, false), () -> {
            ChaosActionView view = chaosActionService.execute(target, request);
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("container", view.target());
            after.put("actionStatus", view.status());
            after.put("expiresAt", view.expiresAt() == null ? null : view.expiresAt().toString());
            if (runId != null) {
                // APPLIED and RESTORED both mean "this fault really happened".
                // A failed action throws before this point and writes no event.
                faultEvents.recordContainerAction(runId,
                        OperationContext.currentOperationId().orElse(null), view.target(), action,
                        view.ttlSeconds(), view.status(), view.expiresAt(), runStatus);
            }
            boolean restored = type == ChaosActionType.START || type == ChaosActionType.RESUME;
            return restored
                    ? OperationOutcome.restored(after, view)
                    : OperationOutcome.applied(after, view);
        });

        if (execution.settledWithPayload()) {
            ChaosActionView view = (ChaosActionView) execution.payload();
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(Result.success(view.withOperationId(execution.operation().operationId())));
        }
        return operationResponse(execution, HttpStatus.ACCEPTED);
    }

    @GetMapping("/actions")
    public Result<List<ChaosActionView>> actions() {
        return Result.success(chaosActionService.activeActions());
    }

    @PostMapping("/stop-all")
    public ResponseEntity<Result<?>> stopAll(
            @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey) {
        String key = IdempotencyKeys.normalize(idempotencyKey);
        OperationExecution execution = operations.submitBlocking(new OperationRequest(null, "environment",
                "lab", "stop-all", null, key, Map.of("scope", "all"), false), () -> {
            StopAllResult result = environmentStopAllService.stopAll();
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("reverted", result.reverted());
            after.put("failed", result.failed());
            after.put("pending", result.pending());
            after.put("traffic", result.traffic());
            after.put("threads", result.threads());
            after.put("k6Stopped", result.k6Stopped());
            after.put("k6Cleaned", result.k6Cleaned());
            after.put("k6Failed", result.k6Failed());
            return OperationOutcome.restored(after, result);
        });
        if (execution.settledWithPayload()) {
            StopAllResult result = (StopAllResult) execution.payload();
            return ResponseEntity.ok(Result.success(
                    result.withOperationId(execution.operation().operationId())));
        }
        return operationResponse(execution, HttpStatus.OK);
    }

    private ResponseEntity<Result<?>> operationResponse(OperationExecution execution,
                                                        HttpStatus settledStatus) {
        HttpStatus status = execution.pending() ? HttpStatus.ACCEPTED
                : execution.replay() ? HttpStatus.OK : settledStatus;
        return ResponseEntity.status(status).body(Result.success(operations.view(execution)));
    }

    /**
     * The console speaks instance names ({@code app-1}); the Docker gateway and
     * the chaos operation ledger speak container names ({@code jl-app-1}).
     * Accept both so an API caller cannot hit "Container not found: app-1".
     */
    private String resolveTarget(String name) {
        String trimmed = name == null ? "" : name.trim();
        return APP_INSTANCE.matcher(trimmed).matches() ? containerPrefix + trimmed : trimmed;
    }

    /** Blank run ids are the same as no run id; the service validates the format. */
    private static String normalize(String runId) {
        if (runId == null) {
            return null;
        }
        String trimmed = runId.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
