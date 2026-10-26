package com.javalearn.lab.chaos.web;

import com.javalearn.lab.chaos.action.NetworkResetReport;
import com.javalearn.lab.chaos.toxiproxy.ProxyEnabledView;
import com.javalearn.lab.chaos.toxiproxy.ProxyResetView;
import com.javalearn.lab.chaos.toxiproxy.ToxicRequest;
import com.javalearn.lab.chaos.toxiproxy.ToxicView;
import com.javalearn.lab.chaos.toxiproxy.ToxiproxyService;
import com.javalearn.lab.chaos.toxiproxy.ToxiproxyStateView;
import com.javalearn.lab.common.Result;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Toxiproxy network-fault API (design §11.3). Token-guarded by the shared
 * {@code TokenFilter}; every proxy name is checked against
 * {@code chaos.toxiproxy.proxies} before a control call is made.
 *
 * <p>202 is returned for toxic creation, matching the asynchronous-looking
 * {@code POST /chaos/instances/{name}/actions}; all recovery calls return 200
 * with their resulting view.</p>
 */
@RestController
@RequestMapping("/chaos/toxiproxy")
public class ToxiproxyController {

    private final ToxiproxyService toxiproxyService;

    public ToxiproxyController(ToxiproxyService toxiproxyService) {
        this.toxiproxyService = toxiproxyService;
    }

    @PostMapping("/{proxy}/toxics")
    public ResponseEntity<Result<ToxicView>> addToxic(@PathVariable String proxy,
                                                      @RequestBody(required = false) ToxicRequest request) {
        ToxicView view = toxiproxyService.addToxic(proxy, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Result.success(view));
    }

    @DeleteMapping("/{proxy}/toxics/{name}")
    public Result<Void> removeToxic(@PathVariable String proxy, @PathVariable String name) {
        toxiproxyService.removeToxic(proxy, name);
        return Result.success();
    }

    @PostMapping("/{proxy}/reset")
    public Result<ProxyResetView> reset(@PathVariable String proxy) {
        return Result.success(toxiproxyService.reset(proxy));
    }

    @PostMapping("/{proxy}/disable")
    public Result<ProxyEnabledView> disable(@PathVariable String proxy) {
        return Result.success(toxiproxyService.setEnabled(proxy, false));
    }

    @PostMapping("/{proxy}/enable")
    public Result<ProxyEnabledView> enable(@PathVariable String proxy) {
        return Result.success(toxiproxyService.setEnabled(proxy, true));
    }

    @GetMapping("/state")
    public Result<ToxiproxyStateView> state() {
        return Result.success(toxiproxyService.state());
    }

    /** Network-only recovery; stop-all also calls the same service method. */
    @PostMapping("/reset-all")
    public Result<NetworkResetReport> resetAll() {
        return Result.success(toxiproxyService.resetAll());
    }
}
