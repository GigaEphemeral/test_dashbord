package com.javalearn.lab.chaos.toxiproxy;

import java.util.List;

/**
 * Console-friendly snapshot of the network-fault layer.
 *
 * <p>Contains only proxy names and toxics; the control-plane URL and every
 * credential stay out of the response.</p>
 */
public record ToxiproxyStateView(boolean enabled, List<String> allowedProxies, List<ProxyStateView> proxies) {

    public ToxiproxyStateView {
        allowedProxies = allowedProxies == null ? List.of() : List.copyOf(allowedProxies);
        proxies = proxies == null ? List.of() : List.copyOf(proxies);
    }
}
