package com.javalearn.lab.chaos.bench;

import com.javalearn.lab.chaos.bench.exec.BenchExecutionState;
import com.javalearn.lab.chaos.bench.exec.BenchReportContent;
import com.javalearn.lab.chaos.bench.exec.BenchRunExecutor;
import com.javalearn.lab.chaos.bench.exec.K6ContainerGateway;
import com.javalearn.lab.common.Result;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * Stage2 B2-02b-1: read-only k6 run endpoints. Behind the existing
 * {@code TokenFilter} ({@code /lab/**} is not public), so no new authentication
 * is introduced.
 *
 * <p>An absent container is a normal {@code 200} answer with
 * {@code status=ABSENT}, never a 404: the console polls this endpoint before a
 * container exists. Only a missing report file is a 404. Out-of-range
 * {@code tail}/{@code maxBytes} are clamped to the configured caps instead of
 * rejected (the endpoint is a diagnostics view, not a write API).</p>
 */
@RestController
public class BenchExecutionController {

    private final BenchRunExecutor executor;

    public BenchExecutionController(BenchRunExecutor executor) {
        this.executor = executor;
    }

    /** State of the run's k6 container plus report availability. */
    @GetMapping("/lab/runs/{runId}/k6")
    public Result<BenchExecutionState> state(@PathVariable String runId) {
        return Result.success(executor.state(runId));
    }

    /** Bounded log tail; {@code truncated} says whether the byte cap cut it. */
    @GetMapping("/lab/runs/{runId}/k6/logs")
    public Result<LogView> logs(@PathVariable String runId,
                                @RequestParam(required = false) Integer tail,
                                @RequestParam(required = false) Integer maxBytes) {
        K6ContainerGateway.LogChunk chunk = executor.logs(runId, tail, maxBytes);
        return Result.success(new LogView(chunk.text(), chunk.truncated(), chunk.bytes()));
    }

    /** Raw {@code summary.json}; 404 when k6 has not written it, 413 when oversized. */
    @GetMapping("/lab/runs/{runId}/k6/report")
    public ResponseEntity<byte[]> report(@PathVariable String runId) {
        BenchReportContent content = executor.report(runId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(content.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(content.bytes());
    }

    /** Answer shape of {@code GET .../k6/logs}. */
    public record LogView(String text, boolean truncated, long bytes) {
    }
}
