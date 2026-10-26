package com.javalearn.lab.chaos.annotation;

import java.util.List;

/**
 * Best-effort chaos event annotation (Grafana today). Implementations must
 * never throw: an annotation failure must not fail the chaos action.
 */
public interface ChaosAnnotator {

    void annotate(List<String> tags, String text);
}
