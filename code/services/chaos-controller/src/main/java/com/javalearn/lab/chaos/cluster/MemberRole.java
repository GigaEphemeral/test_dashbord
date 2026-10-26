package com.javalearn.lab.chaos.cluster;

import java.util.Locale;

/**
 * Bounded role vocabulary of {@code performance_schema.replication_group_members.MEMBER_ROLE}.
 *
 * <p>Only PRIMARY and SECONDARY are real values in a single-primary group;
 * null/blank/unexpected values become {@link #UNKNOWN} so the {@code role}
 * label stays cardinality-bounded.</p>
 */
public enum MemberRole {
    PRIMARY,
    SECONDARY,
    UNKNOWN;

    /** Maps a raw column value case-insensitively; null/blank/unknown → UNKNOWN. */
    public static MemberRole from(String raw) {
        if (raw == null) {
            return UNKNOWN;
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (value.isEmpty() || "NULL".equals(value)) {
            return UNKNOWN;
        }
        try {
            return valueOf(value);
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}
