package com.javalearn.lab.chaos.web;

import com.javalearn.lab.chaos.action.NetworkResetReport;
import com.javalearn.lab.chaos.ledger.FaultEventService;
import com.javalearn.lab.chaos.ledger.IdempotencyKeys;
import com.javalearn.lab.chaos.ledger.OperationContext;
import com.javalearn.lab.chaos.ledger.OperationExecution;
import com.javalearn.lab.chaos.ledger.OperationOutcome;
import com.javalearn.lab.chaos.ledger.OperationRequest;
import com.javalearn.lab.chaos.ledger.OperationService;
import com.javalearn.lab.chaos.toxiproxy.ProxyEnabledView;
import com.javalearn.lab.chaos.toxiproxy.ProxyResetView;
import com.javalearn.lab.chaos.toxiproxy.ToxicRequest;
import com.javalearn.lab.chaos.toxiproxy.ToxicView;
import com.javalearn.lab.chaos.toxiproxy.ToxiproxyService;
import com.javalearn.lab.chaos.toxiproxy.ToxiproxyStateView;
import com.javalearn.lab.common.Result;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Toxiproxy network-fault API (design §11.3). Token-guarded by the shared
 * {@code TokenFilter}; every proxy name is checked against
 * {@code chaos.toxiproxy.proxies} before a control call is made.
 *
 * <p>B2-04b2: the six mutating endpoints join the B1-02 operation contract. Each
 * request becomes one {@code lab_operation} row with {@code targetKind=toxiproxy}
 * and {@code action=toxiproxy-add|remove|reset|disable|enable|reset-all}; the
 * response carries {@code X-Operation-Id}. A synchronous settle keeps the
 * original {@code Result<ToxicView>} / {@code Result<ProxyResetView>} /
 * {@code Result<ProxyEnabledView>} / {@code Result<Void>} /
 * {@code Result<NetworkResetReport>} body shape and answers 202. When the
 * operation sync budget elapses the answer is 202 with the operation view, and
 * an idempotent replay answers 200 with the stored operation view - the same
 * rule as {@code POST /chaos/instances/{name}/actions}.</p>
 *
 * <p>The optional {@code runId} query parameter (the JSON body is unchanged)
 * attaches a successful action to that run's timeline: one
 * {@code lab_fault_event} row with {@code source=toxiproxy},
 * {@code target=<proxy|all>} and {@code event=toxiproxy-<action>}. Failed
 * actions write no event, and a timeline write failure never changes the action
 * result. An unknown run is 404, a malformed run id 400; an unreachable ledger
 * lets the action run without a timeline row (non-strict, like container
 * faults).</p>
 */
@RestController
@RequestMapping("/chaos/toxiproxy")
public class ToxiproxyController {

    /** Response header carrying the operation id on every write (B2-04b2). */
    public static final String OPERATION_ID_HEADER = "X-Operation-Id";

    private static final String SOURCE = "toxiproxy";
    private static final String TARGET_ALL = "all";

    private final ToxiproxyService toxiproxyService;
    private final OperationService operations;
    private final FaultEventService faultEvents;

    public ToxiproxyController(ToxiproxyService toxiproxyService, OperationService operations,
                               FaultEventService faultEvents) {
        this.toxiproxyService = toxiproxyService;
        this.operations = operations;
        this.faultEvents = faultEvents;
    }

    @PostMapping("/{proxy}/toxics")
    public ResponseEntity<Result<?>> addToxic(
            @PathVariable String proxy,
            @RequestParam(name = "runId", required = false) String runId,
            @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) ToxicRequest request) {
        String run = normalize(runId);
        String runStatus = run == null ? null : faultEvents.validateRun(run);
        Map<String, Object> before = summary(ToxiproxyService.ACTION_ADD, proxy, run, runStatus);
        before.put("toxicType", request == null ? null : request.type());
        before.put("toxicName", request == null ? null : request.name());
        before.put("params", request == null ? Map.of() : request.paramsOrEmpty());

        OperationExecution execution = operations.submit(request(run, proxy,
                ToxiproxyService.ACTION_ADD, IdempotencyKeys.normalize(idempotencyKey), before), () -> {
            ToxicView view = toxiproxyService.addToxic(proxy, request);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("proxy", proxy);
            detail.put("toxicName", view.name());
            detail.put("toxicType", view.type());
            recordEvent(run, proxy, ToxiproxyService.ACTION_ADD, runStatus, detail);
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("proxy", proxy);
            after.put("toxicName", view.name());
            after.put("toxicType", view.type());
            return OperationOutcome.applied(after, view);
        });
        if (execution.settledWithPayload()) {
            return settled(execution, Result.success((ToxicView) execution.payload()));
        }
        return operationResponse(execution);
    }

    /** Path and body semantics are unchanged, but the call is now an operation. */
    @DeleteMapping("/{proxy}/toxics/{name}")
    public ResponseEntity<Result<?>> removeToxic(
            @PathVariable String proxy,
            @PathVariable String name,
            @RequestParam(name = "runId", required = false) String runId,
            @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey) {
        String run = normalize(runId);
        String runStatus = run == null ? null : faultEvents.validateRun(run);
        Map<String, Object> before = summary(ToxiproxyService.ACTION_REMOVE, proxy, run, runStatus);
        before.put("toxicName", name);

        OperationExecution execution = operations.submit(request(run, proxy,
                ToxiproxyService.ACTION_REMOVE, IdempotencyKeys.normalize(idempotencyKey), before), () -> {
            toxiproxyService.removeToxic(proxy, name);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("proxy", proxy);
            detail.put("toxicName", name);
            recordEvent(run, proxy, ToxiproxyService.ACTION_REMOVE, runStatus, detail);
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("proxy", proxy);
            after.put("toxicName", name);
            after.put("removed", true);
            // Removing a toxic restores network state. The payload is a marker
            // because Result<Void> has no body: OperationExecution only reports a
            // synchronous settle when a payload object is present.
            return OperationOutcome.restored(after, Boolean.TRUE);
        });
        if (execution.settledWithPayload()) {
            return settled(execution, Result.success());
        }
        return operationResponse(execution);
    }

    @PostMapping("/{proxy}/reset")
    public ResponseEntity<Result<?>> reset(
            @PathVariable String proxy,
            @RequestParam(name = "runId", required = false) String runId,
            @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey) {
        String run = normalize(runId);
        String runStatus = run == null ? null : faultEvents.validateRun(run);
        Map<String, Object> before = summary(ToxiproxyService.ACTION_RESET, proxy, run, runStatus);

        OperationExecution execution = operations.submit(request(run, proxy,
                ToxiproxyService.ACTION_RESET, IdempotencyKeys.normalize(idempotencyKey), before), () -> {
            ProxyResetView view = toxiproxyService.reset(proxy);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("proxy", view.proxy());
            detail.put("toxicsRemoved", view.toxicsRemoved());
            detail.put("enabled", view.enabled());
            recordEvent(run, proxy, ToxiproxyService.ACTION_RESET, runStatus, detail);
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("proxy", view.proxy());
            after.put("toxicsRemoved", view.toxicsRemoved());
            after.put("enabled", view.enabled());
            return OperationOutcome.restored(after, view);
        });
        if (execution.settledWithPayload()) {
            return settled(execution, Result.success((ProxyResetView) execution.payload()));
        }
        return operationResponse(execution);
    }

    @PostMapping("/{proxy}/disable")
    public ResponseEntity<Result<?>> disable(
            @PathVariable String proxy,
            @RequestParam(name = "runId", required = false) String runId,
            @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey) {
        return setEnabled(proxy, false, runId, idempotencyKey);
    }

    @PostMapping("/{proxy}/enable")
    public ResponseEntity<Result<?>> enable(
            @PathVariable String proxy,
            @RequestParam(name = "runId", required = false) String runId,
            @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey) {
        return setEnabled(proxy, true, runId, idempotencyKey);
    }

    private ResponseEntity<Result<?>> setEnabled(String proxy, boolean enabled, String runId,
                                                 String idempotencyKey) {
        String action = enabled ? ToxiproxyService.ACTION_ENABLE : ToxiproxyService.ACTION_DISABLE;
        String run = normalize(runId);
        String runStatus = run == null ? null : faultEvents.validateRun(run);
        Map<String, Object> before = summary(action, proxy, run, runStatus);
        before.put("enabled", enabled);

        OperationExecution execution = operations.submit(request(run, proxy, action,
                IdempotencyKeys.normalize(idempotencyKey), before), () -> {
            ProxyEnabledView view = toxiproxyService.setEnabled(proxy, enabled);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("proxy", view.proxy());
            detail.put("enabled", view.enabled());
            recordEvent(run, proxy, action, runStatus, detail);
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("proxy", view.proxy());
            after.put("enabled", view.enabled());
            // Enabling is a recovery; disabling applies the network cut.
            return enabled
                    ? OperationOutcome.restored(after, view)
                    : OperationOutcome.applied(after, view);
        });
        if (execution.settledWithPayload()) {
            return settled(execution, Result.success((ProxyEnabledView) execution.payload()));
        }
        return operationResponse(execution);
    }

    @GetMapping("/state")
    public Result<ToxiproxyStateView> state() {
        return Result.success(toxiproxyService.state());
    }

    /**
     * Network-only recovery; stop-all also calls the same service method. The
     * event is written only when something was actually restored
     * ({@code reset}/{@code partial}); a report that restored nothing says so in
     * the operation row instead of claiming a recovery in the timeline.
     */
    @PostMapping("/reset-all")
    public ResponseEntity<Result<?>> resetAll(
            @RequestParam(name = "runId", required = false) String runId,
            @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey) {
        String run = normalize(runId);
        String runStatus = run == null ? null : faultEvents.validateRun(run);
        Map<String, Object> before = summary(ToxiproxyService.ACTION_RESET_ALL, TARGET_ALL,
                run, runStatus);

        OperationExecution execution = operations.submit(request(run, TARGET_ALL,
                ToxiproxyService.ACTION_RESET_ALL, IdempotencyKeys.normalize(idempotencyKey),
                before), () -> {
            NetworkResetReport report = toxiproxyService.resetAll();
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("status", report.status());
            after.put("proxiesReset", report.proxiesReset());
            after.put("proxiesTotal", report.proxiesTotal());
            if (run != null && recovered(report)) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("status", report.status());
                detail.put("proxiesReset", report.proxiesReset());
                detail.put("proxiesTotal", report.proxiesTotal());
                recordEvent(run, TARGET_ALL, ToxiproxyService.ACTION_RESET_ALL, runStatus, detail);
            }
            return OperationOutcome.restored(after, report);
        });
        if (execution.settledWithPayload()) {
            return settled(execution, Result.success((NetworkResetReport) execution.payload()));
        }
        return operationResponse(execution);
    }

    private static boolean recovered(NetworkResetReport report) {
        return NetworkResetReport.STATUS_RESET.equals(report.status())
                || NetworkResetReport.STATUS_PARTIAL.equals(report.status());
    }

    private static OperationRequest request(String runId, String target, String action,
                                            String idempotencyKey, Map<String, Object> before) {
        return new OperationRequest(runId, "toxiproxy", target, action, null, idempotencyKey,
                before, false);
    }

    private static Map<String, Object> summary(String action, String target, String runId,
                                               String runStatus) {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("proxy", target);
        before.put("action", action);
        if (runId != null) {
            before.put("runId", runId);
            before.put("runStatus", runStatus);
        }
        return before;
    }

    /** Timeline rows are best-effort; the operation id comes from the worker context. */
    private void recordEvent(String runId, String target, String event, String runStatus,
                             Map<String, Object> detail) {
        if (runId == null) {
            return;
        }
        faultEvents.recordEvent(runId, OperationContext.currentOperationId().orElse(null),
                SOURCE, target, event, detail, runStatus);
    }

    private static <T> ResponseEntity<Result<?>> settled(OperationExecution execution,
                                                         Result<T> body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(OPERATION_ID_HEADER, execution.operation().operationId())
                .body(body);
    }

    private ResponseEntity<Result<?>> operationResponse(OperationExecution execution) {
        HttpStatus status = execution.replay() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status)
                .header(OPERATION_ID_HEADER, execution.operation().operationId())
                .body(Result.success(operations.view(execution)));
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
