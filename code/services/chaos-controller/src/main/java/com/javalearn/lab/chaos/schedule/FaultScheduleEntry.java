package com.javalearn.lab.chaos.schedule;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * B3-00b: one normalised entry of a run-scoped fault timeline.
 *
 * <p>{@code atSeconds} is the offset from the k6 launch (run start), never a
 * wall-clock instant. {@code recovery} is the canonical lowercase wire value
 * ({@code auto}/{@code manual}); a manual entry always carries
 * {@code ttlSeconds=null}, so the persisted group is already normalised. The
 * scheduler maps a manual kill/freeze to {@code ttlSeconds=-1} for the chaos API
 * (manual hold, no automatic revert); manual one-shot actions stay TTL-less.</p>
 */
@JsonPropertyOrder({"atSeconds", "target", "action", "recovery", "ttlSeconds"})
@JsonInclude(JsonInclude.Include.ALWAYS)
public record FaultScheduleEntry(int atSeconds, String target, String action, String recovery,
                                 Integer ttlSeconds) {

    public static final String RECOVERY_AUTO = "auto";
    public static final String RECOVERY_MANUAL = "manual";

    /** True when this entry asks for a manual hold (no automatic revert). */
    public boolean manual() {
        return RECOVERY_MANUAL.equals(recovery);
    }
}
