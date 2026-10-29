package com.javalearn.lab.chaos.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.ledger.LedgerViews.RunCreateView;
import com.javalearn.lab.chaos.ledger.RunLedgerService;
import com.javalearn.lab.common.Result;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * B5-03 single-run report. {@code /report} is the inline view; {@code /export}
 * is the same document as a JSON attachment; {@code /clone} creates one new
 * DRAFT for a manual repetition.
 */
@RestController
public class RunReportController {

    private final RunReportService reports;
    private final RunLedgerService ledger;
    private final ObjectMapper objectMapper;

    public RunReportController(RunReportService reports, RunLedgerService ledger,
                               ObjectMapper objectMapper) {
        this.reports = reports;
        this.ledger = ledger;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/lab/runs/{runId}/report")
    public Result<RunReportViews.RunReport> report(@PathVariable String runId) {
        return Result.success(reports.report(runId));
    }

    @GetMapping("/lab/runs/{runId}/export")
    public ResponseEntity<byte[]> export(@PathVariable String runId) {
        try {
            byte[] body = objectMapper.writeValueAsBytes(reports.report(runId));
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.attachment()
                                    .filename("run-" + runId + "-report.json")
                                    .build().toString())
                    .body(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize the run report: " + e.getMessage(), e);
        }
    }

    @PostMapping("/lab/runs/{runId}/clone")
    public ResponseEntity<Result<RunCreateView>> clone(@PathVariable String runId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Result.success(ledger.clone(runId)));
    }
}
