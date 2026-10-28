package com.javalearn.lab.chaos.precheck;

import com.javalearn.lab.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * B1-04 pre-check API. Under {@code /lab/**} it is covered by the shared
 * {@code TokenFilter}.
 *
 * <p>The endpoint always answers 200 with the report - a BLOCKED verdict is
 * data the console renders, not an HTTP error. 404 means the run does not
 * exist; 503/50302 means the ledger cannot answer. The start endpoint keeps
 * its own contract: a blocking report there is 409/40903.</p>
 */
@RestController
public class PrecheckController {

    private final RunPrecheckService precheck;

    public PrecheckController(RunPrecheckService precheck) {
        this.precheck = precheck;
    }

    @GetMapping("/lab/runs/{runId}/precheck")
    public Result<PrecheckReport> precheck(@PathVariable String runId) {
        return Result.success(precheck.inspectReport(runId));
    }
}
