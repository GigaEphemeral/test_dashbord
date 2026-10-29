package com.javalearn.lab.chaos.instance;

import com.javalearn.lab.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static com.javalearn.lab.chaos.instance.InstanceViews.EffectiveInstanceView;
import static com.javalearn.lab.chaos.instance.InstanceViews.InstanceConfigView;
import static com.javalearn.lab.chaos.instance.InstanceViews.InstanceValidationView;

/**
 * Stage2 B4-01 instance configuration surface (design §10.2).
 *
 * <ul>
 *   <li>{@code GET /lab/instances/{instance}/config} - stored draft + argv</li>
 *   <li>{@code PUT /lab/instances/{instance}/config} - validate and save draft
 *       (no restart; B4-02 adds apply)</li>
 *   <li>{@code POST /lab/instances/{instance}/config/validate} - dry run</li>
 *   <li>{@code GET /lab/instances/{instance}/effective} - container readback</li>
 * </ul>
 *
 * <p>All paths are behind the existing {@code TokenFilter}. Unknown instance is
 * 404, invalid config 400/40001, startup change during a RUNNING run 409/40906,
 * DEGRADED ledger 503/50302.</p>
 */
@RestController
public class InstanceConfigController {

    private final InstanceConfigService service;

    public InstanceConfigController(InstanceConfigService service) {
        this.service = service;
    }

    @GetMapping("/lab/instances/{instance}/config")
    public Result<InstanceConfigView> get(@PathVariable String instance) {
        return Result.success(service.get(instance));
    }

    /** "仅保存": validates and writes DRAFT; the JVM keeps running unchanged. */
    @PutMapping("/lab/instances/{instance}/config")
    public Result<InstanceConfigView> save(@PathVariable String instance,
                                           @RequestBody InstanceConfig request) {
        return Result.success(service.save(instance, request));
    }

    @PostMapping("/lab/instances/{instance}/config/validate")
    public Result<InstanceValidationView> validate(@PathVariable String instance,
                                                   @RequestBody InstanceConfig request) {
        return Result.success(service.validate(instance, request));
    }

    @GetMapping("/lab/instances/{instance}/effective")
    public Result<EffectiveInstanceView> effective(@PathVariable String instance) {
        return Result.success(service.effective(instance));
    }
}
