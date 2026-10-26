package com.javalearn.lab.chaos.toxiproxy;

import java.util.List;

/**
 * One row of {@code GET /chaos/toxiproxy/state}.
 *
 * <p>{@code present=false} means the configured proxy name is not registered in
 * Toxiproxy (for example the {@code chaos-proxy} profile is only half wired).
 * The state endpoint keeps returning the other proxies instead of failing the
 * whole console poll.</p>
 */
public record ProxyStateView(String name, boolean present, boolean enabled, List<ToxicView> toxics) {

    public ProxyStateView {
        toxics = toxics == null ? List.of() : List.copyOf(toxics);
    }

    public static ProxyStateView missing(String name) {
        return new ProxyStateView(name, false, false, List.of());
    }

    public static ProxyStateView of(ProxyView proxy) {
        return new ProxyStateView(proxy.name(), true, proxy.enabled(), proxy.toxics());
    }
}
