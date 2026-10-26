package com.javalearn.lab.chaos.toxiproxy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Result of {@code POST /chaos/toxiproxy/{proxy}/reset}.
 *
 * <p>Reset is a full restore: every toxic is removed <em>and</em> the proxy is
 * enabled again, so one call recovers a demo that used either fault model.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProxyResetView(String proxy, int toxicsRemoved, boolean enabled) {
}
