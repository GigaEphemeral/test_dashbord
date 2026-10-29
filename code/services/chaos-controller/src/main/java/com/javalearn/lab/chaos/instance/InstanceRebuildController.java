package com.javalearn.lab.chaos.instance;

import com.javalearn.lab.common.Result;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.javalearn.lab.chaos.instance.InstanceViews.InstanceApplyView;

/**
 * Stage2 B4-02 controlled rebuild surface (design §10.7).
 *
 * <ul>
 *   <li>{@code POST /lab/instances/{instance}/apply} - rebuild with the stored
 *       configuration ("应用并重启")</li>
 *   <li>{@code POST /lab/instances/{instance}/rollback} - rebuild with
 *       {@code previous_config} ("回滚")</li>
 * </ul>
 *
 * <p>A failed rebuild is HTTP 200 with {@code status=FAILED} (or
 * {@code DEGRADED} when the automatic restore failed) plus the failing check and
 * rollback evidence. Refusals keep their own codes: unknown instance 404, JDK 8
 * shell or invalid config 400/40001, RUNNING run or missing/previous config or
 * concurrent rebuild 409 (40906/40907), DEGRADED ledger 503/50302.</p>
 */
@RestController
public class InstanceRebuildController {

    private final InstanceRebuildService service;

    public InstanceRebuildController(InstanceRebuildService service) {
        this.service = service;
    }

    @PostMapping("/lab/instances/{instance}/apply")
    public Result<InstanceApplyView> apply(@PathVariable String instance) {
        return Result.success(service.apply(instance));
    }

    @PostMapping("/lab/instances/{instance}/rollback")
    public Result<InstanceApplyView> rollback(@PathVariable String instance) {
        return Result.success(service.rollback(instance));
    }

    /** Manual controlled restart with the config that is already APPLIED. */
    @PostMapping("/lab/instances/{instance}/restart")
    public Result<InstanceApplyView> restart(@PathVariable String instance) {
        return Result.success(service.restart(instance));
    }
}
