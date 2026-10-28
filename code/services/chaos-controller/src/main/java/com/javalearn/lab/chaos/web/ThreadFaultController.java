package com.javalearn.lab.chaos.web;

import com.javalearn.lab.chaos.appproxy.AppProxyService;
import com.javalearn.lab.chaos.appproxy.ProxyResponse;
import com.javalearn.lab.chaos.config.ChaosProperties;
import com.javalearn.lab.chaos.error.AppFaultFailedException;
import com.javalearn.lab.chaos.error.AppProxyRequestTooLargeException;
import com.javalearn.lab.chaos.error.InvalidAppProxyRequestException;
import com.javalearn.lab.chaos.error.UnknownAppInstanceException;
import com.javalearn.lab.chaos.ledger.FaultEventService;
import com.javalearn.lab.chaos.ledger.IdempotencyKeys;
import com.javalearn.lab.chaos.ledger.OperationContext;
import com.javalearn.lab.chaos.ledger.OperationExecution;
import com.javalearn.lab.chaos.ledger.OperationOutcome;
import com.javalearn.lab.chaos.ledger.OperationRequest;
import com.javalearn.lab.chaos.ledger.OperationService;
import com.javalearn.lab.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * B2-04b2: unified thread-fault entry point with the operation contract.
 *
 * <p>Three write endpoints under {@code /chaos/instances/{name}/thread}:</p>
 * <ul>
 *   <li>{@code POST /fault} - the request body is relayed verbatim to the app's
 *       {@code POST /lab/thread/fault};</li>
 *   <li>{@code POST /fault/{mode}/{stop|pause|resume}} - only the seven
 *       documented modes and the three lifecycle verbs are accepted;</li>
 *   <li>{@code POST /stop-all} - relayed to {@code POST /lab/thread/stop-all}.</li>
 * </ul>
 *
 * <p><b>Whitelist.</b> The instance must be listed in
 * {@code chaos.app-proxy.instances}; an unknown instance is 404/40401 (the same
 * answer as {@code /chaos/app/**}, so a mistyped host reveals nothing). An
 * unknown mode or action is 400/40001. The app paths are fixed constants - no
 * request can widen the target set.</p>
 *
 * <p><b>Operation.</b> Every call becomes one {@code lab_operation} row with
 * {@code targetKind=thread}, {@code targetName=<instance>} and
 * {@code action=thread-fault|thread-stop-all}. The optional {@code runId} query
 * parameter joins the run timeline ({@code lab_fault_event}, source
 * {@code thread}); failed calls write no event. Synchronous success relays the
 * app's original JSON envelope with HTTP 202 and {@code X-Operation-Id}; a
 * pending operation answers 202 with the operation view, and an idempotent
 * replay answers 200 with the stored operation view. The old
 * {@code /chaos/app/{instance}/lab/thread/**} proxy stays available for reads,
 * lifecycle steps and snapshots.</p>
 */
@RestController
@RequestMapping("/chaos/instances/{name}/thread")
public class ThreadFaultController {

    /** Response header carrying the operation id on every write (B2-04b2). */
    public static final String OPERATION_ID_HEADER = "X-Operation-Id";

    static final String FAULT_PATH = "/lab/thread/fault";
    static final String STOP_ALL_PATH = "/lab/thread/stop-all";
    static final String ACTION_FAULT = "thread-fault";
    static final String ACTION_STOP_ALL = "thread-stop-all";

    /** The seven documented fault modes (mirrors the app's FaultMode keys). */
    static final Set<String> MODES = Set.of(
            "deadlock", "stuck", "cpu-burn", "heap-leak", "oom", "thread-leak", "starvation");

    /** Only the lifecycle verbs the app exposes for one mode. */
    static final Set<String> ACTIONS = Set.of("stop", "pause", "resume");

    private static final MediaType JSON_UTF8 =
            new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8);

    private final AppProxyService appProxyService;
    private final ChaosProperties properties;
    private final OperationService operations;
    private final FaultEventService faultEvents;

    public ThreadFaultController(AppProxyService appProxyService, ChaosProperties properties,
                                 OperationService operations, FaultEventService faultEvents) {
        this.appProxyService = appProxyService;
        this.properties = properties;
        this.operations = operations;
        this.faultEvents = faultEvents;
    }

    @PostMapping("/fault")
    public ResponseEntity<?> fault(
            @PathVariable String name,
            @RequestParam(name = "runId", required = false) String runId,
            @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
            HttpServletRequest request) {
        byte[] body = readBody(request);
        return submit(name, runId, idempotencyKey, ACTION_FAULT, FAULT_PATH, null, body,
                "thread-fault", false, body.length);
    }

    @PostMapping("/fault/{mode}/{action}")
    public ResponseEntity<?> faultAction(
            @PathVariable String name,
            @PathVariable String mode,
            @PathVariable String action,
            @RequestParam(name = "runId", required = false) String runId,
            @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey) {
        String normalizedMode = mode == null ? "" : mode.trim();
        if (!MODES.contains(normalizedMode)) {
            throw new InvalidAppProxyRequestException("Unsupported thread fault mode '"
                    + mode + "'; allowed: " + MODES);
        }
        String normalizedAction = action == null ? "" : action.trim();
        if (!ACTIONS.contains(normalizedAction)) {
            throw new InvalidAppProxyRequestException("Unsupported thread fault action '"
                    + action + "'; allowed: " + ACTIONS);
        }
        String path = FAULT_PATH + "/" + normalizedMode + "/" + normalizedAction;
        boolean restored = "stop".equals(normalizedAction);
        return submit(name, runId, idempotencyKey, ACTION_FAULT, path, null, null,
                "thread-fault-" + normalizedMode + "-" + normalizedAction, restored, 0);
    }

    @PostMapping("/stop-all")
    public ResponseEntity<?> stopAll(
            @PathVariable String name,
            @RequestParam(name = "runId", required = false) String runId,
            @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey) {
        return submit(name, runId, idempotencyKey, ACTION_STOP_ALL, STOP_ALL_PATH, null, null,
                "thread-stop-all", true, 0);
    }

    private ResponseEntity<?> submit(String instance, String runId, String idempotencyKey,
                                     String action, String path, String query, byte[] body,
                                     String event, boolean restored, int bodyBytes) {
        requireInstance(instance);
        String key = IdempotencyKeys.normalize(idempotencyKey);
        String run = normalize(runId);
        String runStatus = run == null ? null : faultEvents.validateRun(run);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("instance", instance);
        before.put("action", action);
        before.put("endpoint", path);
        before.put("bodyBytes", bodyBytes);
        if (run != null) {
            before.put("runId", run);
            before.put("runStatus", runStatus);
        }

        OperationExecution execution = operations.submit(new OperationRequest(run, "thread",
                instance, action, null, key, before, false), () -> {
            ProxyResponse response = appProxyService.forward(instance, "POST", path, query, body);
            if (response == null || response.status() < 200 || response.status() >= 300) {
                throw new AppFaultFailedException(instance + " answered HTTP "
                        + (response == null ? "no response" : response.status())
                        + " for POST " + path + "; the thread action was not applied");
            }
            if (run != null) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("endpoint", path);
                detail.put("appStatus", response.status());
                faultEvents.recordEvent(run, OperationContext.currentOperationId().orElse(null),
                        "thread", instance, event, detail, runStatus);
            }
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("instance", instance);
            after.put("appStatus", response.status());
            String payload = response.body() == null ? "" : response.body();
            return restored
                    ? OperationOutcome.restored(after, payload)
                    : OperationOutcome.applied(after, payload);
        });

        if (execution.settledWithPayload()) {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .contentType(JSON_UTF8)
                    .header(OPERATION_ID_HEADER, execution.operation().operationId())
                    .body(execution.payload());
        }
        HttpStatus status = execution.pending() ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .header(OPERATION_ID_HEADER, execution.operation().operationId())
                .body(Result.success(operations.view(execution)));
    }

    /**
     * The instance whitelist is the same list the app proxy uses. An unknown
     * name is 404/40401 before any operation row is created.
     */
    private void requireInstance(String instance) {
        List<String> instances = properties.getAppProxy().getInstances();
        if (instance == null || instances == null || !instances.contains(instance)) {
            throw new UnknownAppInstanceException("Unknown app instance '" + instance
                    + "'; allowed instances: " + instances);
        }
    }

    private byte[] readBody(HttpServletRequest request) {
        int limit = Math.max(1, appProxyService.maxRequestBytes());
        try {
            byte[] body = request.getInputStream().readNBytes(limit + 1);
            if (body.length > limit) {
                throw new AppProxyRequestTooLargeException(
                        "Request body exceeds " + limit + " bytes");
            }
            return body;
        } catch (IOException e) {
            throw new InvalidAppProxyRequestException(
                    "Could not read the request body: " + e.getMessage());
        }
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
