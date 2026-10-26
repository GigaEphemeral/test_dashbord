package com.javalearn.lab.chaos.toxiproxy;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Typed description of one toxic, already validated against
 * {@code chaos.toxiproxy.*} bounds.
 *
 * <p>The model deliberately has one variant per supported fault shape so a
 * caller cannot send {@code rateKbps} to a latency toxic by accident. The HTTP
 * gateway translates a spec into Toxiproxy's {@code attributes} object:</p>
 *
 * <ul>
 *   <li>{@link Latency} → {@code latency} + {@code jitter} (ms)</li>
 *   <li>{@link Bandwidth} → {@code rate} (KB/s)</li>
 *   <li>{@link Timeout} → {@code timeout} (ms); this is the network-cut model:
 *       data stops flowing and the connection is closed after the timeout.
 *       {@code timeout} must be positive, otherwise Toxiproxy would stall the
 *       connection forever instead of cutting it.</li>
 * </ul>
 *
 * <p>Toxics are applied to Toxiproxy's default stream ({@code downstream}) and
 * with {@code toxicity = 1.0}; neither is exposed because a partial-toxicity
 * network fault would make a teaching demo non-deterministic.</p>
 */
public sealed interface ToxicSpec permits ToxicSpec.Latency, ToxicSpec.Bandwidth, ToxicSpec.Timeout {

    /** Toxic name unique inside one proxy. */
    String name();

    ToxicType type();

    Map<String, Integer> attributes();

    record Latency(String name, int latencyMs, int jitterMs) implements ToxicSpec {

        @Override
        public ToxicType type() {
            return ToxicType.LATENCY;
        }

        @Override
        public Map<String, Integer> attributes() {
            Map<String, Integer> attributes = new LinkedHashMap<>();
            attributes.put("latency", latencyMs);
            attributes.put("jitter", jitterMs);
            return attributes;
        }
    }

    record Bandwidth(String name, int rateKbps) implements ToxicSpec {

        @Override
        public ToxicType type() {
            return ToxicType.BANDWIDTH;
        }

        @Override
        public Map<String, Integer> attributes() {
            return Map.of("rate", rateKbps);
        }
    }

    record Timeout(String name, int timeoutMs) implements ToxicSpec {

        @Override
        public ToxicType type() {
            return ToxicType.TIMEOUT;
        }

        @Override
        public Map<String, Integer> attributes() {
            return Map.of("timeout", timeoutMs);
        }
    }
}
