package com.javalearn.lab.seckill.lab.api;

/**
 * {@code POST /lab/thread/fault} body. Every field is optional except {@code mode}:
 *
 * <ul>
 *   <li>{@code mode} — one of the seven {@code FaultMode} keys.</li>
 *   <li>{@code hold} — {@code auto} (default) or {@code manual}; manual never
 *       auto-reverts.</li>
 *   <li>{@code ttlSeconds} — overrides {@code app.lab.thread.default-ttl-seconds}
 *       for this one {@code auto} fault; ignored for manual.</li>
 *   <li>{@code virtual} — overrides the thread-leak virtual/platform switch for
 *       the JDK21 contrast.</li>
 * </ul>
 */
public record FaultStartRequest(String mode, String hold, Long ttlSeconds, Boolean virtual) {
}
