package com.javalearn.lab.chaos.toxiproxy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Proxy status as reported by {@code GET /proxies/{proxy}}.
 *
 * <p>Only the fields the console needs are modelled; Toxiproxy also returns
 * {@code listen} and {@code upstream}, which are neither secret nor useful for
 * the lab API and are therefore ignored.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProxyView(String name, boolean enabled, List<ToxicView> toxics) {

    public ProxyView {
        toxics = toxics == null ? List.of() : List.copyOf(toxics);
    }
}
