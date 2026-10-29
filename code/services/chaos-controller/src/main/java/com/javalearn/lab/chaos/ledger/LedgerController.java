package com.javalearn.lab.chaos.ledger;

import com.javalearn.lab.common.Result;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.javalearn.lab.chaos.ledger.LedgerViews.LedgerRecoveryView;
import static com.javalearn.lab.chaos.ledger.LedgerViews.LedgerStatusView;
import static com.javalearn.lab.chaos.ledger.LedgerViews.OperationCloseRequest;
import static com.javalearn.lab.chaos.ledger.LedgerViews.OperationPageView;
import static com.javalearn.lab.chaos.ledger.LedgerViews.OperationView;
import static com.javalearn.lab.chaos.ledger.LedgerViews.RunCreateRequest;
import static com.javalearn.lab.chaos.ledger.LedgerViews.RunCreateView;
import static com.javalearn.lab.chaos.ledger.LedgerViews.RunDeleteView;
import static com.javalearn.lab.chaos.ledger.LedgerViews.RunDetailView;
import static com.javalearn.lab.chaos.ledger.LedgerViews.RunPageView;
import static com.javalearn.lab.chaos.ledger.LedgerViews.RunView;

/**
 * Ledger REST surface. Every path is behind the existing {@code TokenFilter}
 * (only observability endpoints are public).
 *
 * <p>B1-02 adds the operation contract: run start/stop answer with an
 * {@code operationId} (200 when settled inside the sync budget, 202
 * PENDING_CONFIRMATION when it was not), and {@code GET /lab/operations} is the
 * polling surface. {@code POST /lab/runs} keeps creating the DRAFT record; the
 * start path takes the environment lock and advances the state machine.</p>
 */
@RestController
public class LedgerController {

    private final RunLedgerService service;
    private final RunLifecycleService lifecycle;
    private final OperationService operations;

    public LedgerController(RunLedgerService service, RunLifecycleService lifecycle,
                            OperationService operations) {
        this.service = service;
        this.lifecycle = lifecycle;
        this.operations = operations;
    }

    @PostMapping("/lab/runs")
    public ResponseEntity<Result<RunCreateView>> create(@RequestBody RunCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Result.success(service.create(request)));
    }

    @GetMapping("/lab/runs")
    public Result<RunPageView> list(
            @RequestParam(name = "scenario", required = false) String scenario,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "offset", required = false) Integer offset,
            @RequestParam(name = "includeArchived", required = false) Boolean includeArchived) {
        return Result.success(service.list(scenario, status, limit, offset,
                Boolean.TRUE.equals(includeArchived)));
    }

    /** B3-00a: hide a closed run from the default list (reversible). */
    @PostMapping("/lab/runs/{runId}/archive")
    public Result<RunView> archive(@PathVariable String runId) {
        return Result.success(service.setArchived(runId, true));
    }

    @PostMapping("/lab/runs/{runId}/unarchive")
    public Result<RunView> unarchive(@PathVariable String runId) {
        return Result.success(service.setArchived(runId, false));
    }

    /**
     * B3-00a: hard delete of a draft/closed run. k6 and report artifacts are
     * removed first (best effort, reported in the answer), then the row delete
     * cascades to every child table.
     */
    @DeleteMapping("/lab/runs/{runId}")
    public Result<RunDeleteView> delete(@PathVariable String runId) {
        return Result.success(lifecycle.delete(runId));
    }

    @GetMapping("/lab/runs/{runId}")
    public Result<RunDetailView> detail(@PathVariable String runId) {
        return Result.success(service.get(runId));
    }

    /**
     * Runs the B1-04 pre-check first (before any transition or lock), then takes
     * the write-run lock and advances DRAFT → VALIDATING → READY → RUNNING. A
     * blocking pre-check finding is 409/40903 with the report in {@code data}
     * and leaves the run DRAFT. 202 + PENDING_CONFIRMATION when the worker
     * outlived the sync budget; the operation keeps going and
     * {@code GET /lab/operations/{id}} shows the result.
     */
    @PostMapping("/lab/runs/{runId}/start")
    public ResponseEntity<Result<OperationView>> start(
            @PathVariable String runId,
            @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey) {
        return operationResponse(lifecycle.start(runId, IdempotencyKeys.normalize(idempotencyKey)));
    }

    /**
     * RUNNING → STOPPING → COMPLETED and releases the lock. An UNKNOWN run is
     * closed as ABORTED (operator cleanup after a controller restart) and its
     * lock released; the after-summary states whether the lock was really freed.
     */
    @PostMapping("/lab/runs/{runId}/stop")
    public ResponseEntity<Result<OperationView>> stop(
            @PathVariable String runId,
            @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey) {
        return operationResponse(lifecycle.stop(runId, IdempotencyKeys.normalize(idempotencyKey)));
    }

    @GetMapping("/lab/operations/{operationId}")
    public Result<OperationView> operation(@PathVariable String operationId) {
        return Result.success(operations.view(operationId));
    }

    /**
     * B2-04a: close an UNKNOWN operation as RESTORED or FAILED. Only the
     * operation row changes (last_error/restored_at); the run status and the
     * write-run lock are untouched. Repeated close with the same verdict is 200
     * idempotent, the other verdict is 409/40905, a missing operation is 404.
     */
    @PostMapping("/lab/operations/{operationId}/close")
    public Result<OperationView> closeOperation(
            @PathVariable String operationId,
            @RequestBody(required = false) OperationCloseRequest request) {
        return Result.success(operations.closeUnknown(operationId, request));
    }

    @GetMapping("/lab/operations")
    public Result<OperationPageView> operationList(
            @RequestParam(name = "runId", required = false) String runId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "offset", required = false) Integer offset) {
        return Result.success(operations.page(runId, status, limit, offset));
    }

    @GetMapping("/lab/ledger/status")
    public Result<LedgerStatusView> status() {
        return Result.success(service.status());
    }

    @GetMapping("/lab/ledger/recovery")
    public Result<LedgerRecoveryView> recovery() {
        return Result.success(service.recovery());
    }

    private ResponseEntity<Result<OperationView>> operationResponse(OperationExecution execution) {
        HttpStatus status = execution.pending() ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(Result.success(operations.view(execution)));
    }
}
