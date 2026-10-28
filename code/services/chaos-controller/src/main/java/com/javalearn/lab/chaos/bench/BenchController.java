package com.javalearn.lab.chaos.bench;

import com.javalearn.lab.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only bench scenario catalog. Behind the existing {@code TokenFilter}
 * ({@code /lab/**} is not public), so no new authentication is introduced.
 *
 * <p>Success is always {@code status=OK}; a missing/unreadable/inconsistent
 * manifest is the 503/50303 refused by {@code LabExceptionHandler}, never an
 * empty catalog. {@code views.ui} is the console's source of truth: the
 * dashboard renders exactly those names, so stress/soak stay CLI-only.</p>
 */
@RestController
public class BenchController {

    private final BenchScenarioCatalog catalog;

    public BenchController(BenchScenarioCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/lab/bench/scenarios")
    public Result<BenchScenarioCatalog.Snapshot> scenarios() {
        return Result.success(catalog.load());
    }
}
