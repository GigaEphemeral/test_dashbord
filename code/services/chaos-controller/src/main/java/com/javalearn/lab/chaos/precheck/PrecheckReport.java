package com.javalearn.lab.chaos.precheck;

import java.util.List;

/**
 * Response of {@code GET /lab/runs/{runId}/precheck} and {@code data} of the
 * 409/40903 refusal.
 *
 * <p>{@code items} is always complete; {@code blocking/warnings/passed} are the
 * same findings grouped by status and capped at
 * {@code lab.precheck.max-listed-items} so a long leftover list cannot turn the
 * report into a page dump. A start is refused only by {@code blocking}, which
 * is never empty when {@code verdict=BLOCKED}.</p>
 */
public record PrecheckReport(String runId, String checkedAt, String verdict,
                             List<PrecheckItem> blocking, List<PrecheckItem> warnings,
                             List<PrecheckItem> passed, List<PrecheckItem> items) {

    public PrecheckReport {
        blocking = blocking == null ? List.of() : List.copyOf(blocking);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        passed = passed == null ? List.of() : List.copyOf(passed);
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static PrecheckReport of(String runId, String checkedAt, List<PrecheckItem> items,
                                    int maxListedItems) {
        List<PrecheckItem> all = items == null ? List.of() : List.copyOf(items);
        List<PrecheckItem> blocking = cap(all.stream().filter(PrecheckItem::blocking).toList(),
                maxListedItems);
        List<PrecheckItem> warnings = cap(all.stream().filter(PrecheckItem::warning).toList(),
                maxListedItems);
        List<PrecheckItem> passed = cap(all.stream()
                .filter(item -> item.status() == PrecheckStatus.PASS).toList(), maxListedItems);
        PrecheckVerdict verdict = !blocking.isEmpty() ? PrecheckVerdict.BLOCKED
                : !warnings.isEmpty() ? PrecheckVerdict.WARN : PrecheckVerdict.PASS;
        return new PrecheckReport(runId, checkedAt, verdict.name(), blocking, warnings, passed, all);
    }

    /** A PASS report without items; used when the gate is disabled. */
    public static PrecheckReport disabled(String runId, String checkedAt) {
        return new PrecheckReport(runId, checkedAt, PrecheckVerdict.PASS.name(),
                List.of(), List.of(), List.of(), List.of());
    }

    public boolean blocked() {
        return PrecheckVerdict.BLOCKED.name().equals(verdict);
    }

    /** Warning keys joined for the operation summary; null when there are none. */
    public String warningSummary() {
        if (warnings.isEmpty()) {
            return null;
        }
        return warnings.stream().map(PrecheckItem::key).distinct()
                .reduce((left, right) -> left + "," + right).orElse(null);
    }

    private static List<PrecheckItem> cap(List<PrecheckItem> values, int max) {
        if (values.size() <= max) {
            return values;
        }
        return List.copyOf(values.subList(0, Math.max(1, max)));
    }
}
