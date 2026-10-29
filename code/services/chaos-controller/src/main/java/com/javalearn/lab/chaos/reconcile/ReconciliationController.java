package com.javalearn.lab.chaos.reconcile;

import com.javalearn.lab.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * B3-04 reconciliation REST surface. Both paths sit behind the existing
 * {@code TokenFilter}; the controller only delegates - verdict logic and
 * persistence live in {@link ReconciliationService}.
 */
@RestController
public class ReconciliationController {

    private final ReconciliationService service;

    public ReconciliationController(ReconciliationService service) {
        this.service = service;
    }

    /** Computes now, persists the result, and returns the live answer. */
    @PostMapping("/lab/runs/{runId}/reconcile")
    public Result<ReconcileResult> reconcile(@PathVariable String runId) {
        return Result.success(service.reconcile(runId));
    }

    /**
     * Last persisted answer. 404/NO_DATA when this run was never reconciled;
     * 404 when the run itself does not exist.
     */
    @GetMapping("/lab/runs/{runId}/reconciliation")
    public Result<ReconcileResult> latest(@PathVariable String runId) {
        return Result.success(service.latest(runId));
    }
}
