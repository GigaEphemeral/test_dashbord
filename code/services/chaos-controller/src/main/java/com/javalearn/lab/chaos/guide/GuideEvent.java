package com.javalearn.lab.chaos.guide;

/**
 * One step of a scenario timeline (schema v1).
 *
 * @param atSeconds offset from timeline start (positive, ascending)
 * @param action    kill|start|restart|freeze|resume
 * @param target    lab application container name
 * @param ttlSeconds required for kill/freeze, {@code null} for one-shot actions
 * @param title     optional short label for the console
 * @param text      optional one-line explanation
 */
public record GuideEvent(double atSeconds,
                         String action,
                         String target,
                         Integer ttlSeconds,
                         String title,
                         String text) {
}
