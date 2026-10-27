package com.javalearn.lab.chaos.action;

import com.javalearn.lab.chaos.error.InvalidChaosActionException;

import java.util.Locale;

/** Container actions exposed by the lab API. */
public enum ChaosActionType {

    KILL,
    START,
    RESTART,
    FREEZE,
    RESUME;

    public static final String ALLOWED = "kill|start|restart|freeze|resume";

    public static ChaosActionType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidChaosActionException("action is required (" + ALLOWED + ")");
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidChaosActionException("Unsupported action: " + raw + " (allowed: " + ALLOWED + ")");
        }
    }

    /** True when the action schedules an automatic revert and must be tracked. */
    public boolean reverting() {
        return this == KILL || this == FREEZE;
    }

    /**
     * True when the action takes the container (or its process) down for a while:
     * {@code kill} stops it, {@code freeze} SIGSTOPs it, {@code restart} cycles it.
     * Those are the actions that also blind an observer living in the same
     * container, which is what the B0-08 probe isolation rule refuses.
     * {@code start}/{@code resume} are recovery, never destructive.
     */
    public boolean destructive() {
        return this == KILL || this == FREEZE || this == RESTART;
    }

    /** The action that undoes this one, or {@code null} for non-reverting actions. */
    public ChaosActionType revertAction() {
        return switch (this) {
            case KILL -> START;
            case FREEZE -> RESUME;
            default -> null;
        };
    }

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
