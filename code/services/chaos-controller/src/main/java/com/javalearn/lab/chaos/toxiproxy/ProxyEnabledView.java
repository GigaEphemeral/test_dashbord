package com.javalearn.lab.chaos.toxiproxy;

/**
 * Result of {@code POST /chaos/toxiproxy/{proxy}/disable} and {@code /enable}.
 */
public record ProxyEnabledView(String proxy, boolean enabled) {
}
