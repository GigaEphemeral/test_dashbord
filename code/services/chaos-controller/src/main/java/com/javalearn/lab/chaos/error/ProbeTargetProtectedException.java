package com.javalearn.lab.chaos.error;

/**
 * A destructive container action was refused because its target is the MySQL
 * cluster probe container (Stage2 B0-08 slice B).
 *
 * <p>Answered with HTTP 403 and {@link LabErrorCode#PROBE_TARGET_PROTECTED}
 * ({@code 40302}) so a console or a k6 drill can tell "this target is not
 * allowed at all" ({@code 40301}) apart from "this target is the observer of the
 * cluster and killing it would blind the drill".</p>
 */
public class ProbeTargetProtectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ProbeTargetProtectedException(String message) {
        super(message);
    }
}
