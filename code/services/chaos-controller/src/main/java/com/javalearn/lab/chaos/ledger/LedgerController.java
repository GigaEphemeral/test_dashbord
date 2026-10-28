package com.javalearn.lab.chaos.ledger;

import com.javalearn.lab.common.Result;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.javalearn.lab.chaos.ledger.LedgerViews.LedgerRecoveryView;
import static com.javalearn.lab.chaos.ledger.LedgerViews.LedgerStatusView;
import static com.javalearn.lab.chaos.ledger.LedgerViews.RunCreateRequest;
import static com.javalearn.lab.chaos.ledger.LedgerViews.RunCreateView;
import static com.javalearn.lab.chaos.ledger.LedgerViews.RunDetailView;
import static com.javalearn.lab.chaos.ledger.LedgerViews.RunPageView;

/**
 * Ledger REST surface (design §10.1, B1-01 slice). Every path is behind the
 * existing {@code TokenFilter} (only observability endpoints are public).
 * {@code POST /lab/runs} creates the DRAFT record and its params snapshot;
 * start/stop/report/compare belong to later steps.
 */
@RestController
public class LedgerController {

    private final RunLedgerService service;

    public LedgerController(RunLedgerService service) {
        this.service = service;
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
            @RequestParam(name = "offset", required = false) Integer offset) {
        return Result.success(service.list(scenario, status, limit, offset));
    }

    @GetMapping("/lab/runs/{runId}")
    public Result<RunDetailView> detail(@PathVariable String runId) {
        return Result.success(service.get(runId));
    }

    @GetMapping("/lab/ledger/status")
    public Result<LedgerStatusView> status() {
        return Result.success(service.status());
    }

    @GetMapping("/lab/ledger/recovery")
    public Result<LedgerRecoveryView> recovery() {
        return Result.success(service.recovery());
    }
}
