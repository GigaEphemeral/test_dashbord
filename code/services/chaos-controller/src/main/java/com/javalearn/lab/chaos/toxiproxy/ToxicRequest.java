package com.javalearn.lab.chaos.toxiproxy;

import java.util.Map;

/**
 * Request body of {@code POST /chaos/toxiproxy/{proxy}/toxics}.
 *
 * <p>Example: {@code {"type":"latency","params":{"latencyMs":500,"jitterMs":50}}}
 * or {@code {"type":"bandwidth","params":{"rateKbps":100}}} or
 * {@code {"type":"timeout","params":{"timeoutMs":1000}}}. Every parameter is
 * optional and falls back to the corresponding {@code chaos.toxiproxy.default-*}
 * value; unknown parameters are rejected so a typo cannot silently inject a
 * different fault than the presenter thinks.</p>
 *
 * @param type   {@code latency}, {@code bandwidth} or {@code timeout}
 * @param name   optional toxic name; defaults to the type name
 * @param params type-specific values; may be omitted
 */
public record ToxicRequest(String type, String name, Map<String, Integer> params) {

    public Map<String, Integer> paramsOrEmpty() {
        return params == null ? Map.of() : params;
    }
}
