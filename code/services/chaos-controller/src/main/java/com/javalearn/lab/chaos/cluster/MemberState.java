package com.javalearn.lab.chaos.cluster;

import java.util.Locale;

/**
 * Bounded state vocabulary of {@code performance_schema.replication_group_members.MEMBER_STATE}.
 *
 * <p>The enum name is used verbatim as the {@code state} label, so the label set
 * can never grow beyond these six values no matter what the server reports.
 * MySQL documents ONLINE / RECOVERING / OFFLINE / ERROR / UNREACHABLE; anything
 * else (including {@code NULL}) is folded into {@link #UNKNOWN} instead of
 * creating a new series.</p>
 */
public enum MemberState {
    ONLINE,
    RECOVERING,
    OFFLINE,
    ERROR,
    UNREACHABLE,
    UNKNOWN;

    /** Maps a raw column value case-insensitively; null/blank/unknown → UNKNOWN. */
    public static MemberState from(String raw) {
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
