package com.javalearn.lab.chaos.reconcile;

import java.util.List;

/** One reconciliation finding. {@code actual} and {@code expected} are human-readable. */
public record ReconcileItem(String key, String expected, String actual, String verdict,
                            String evidence, List<String> sample) {

    public ReconcileItem {
        sample = sample == null ? List.of() : List.copyOf(sample);
    }
}
