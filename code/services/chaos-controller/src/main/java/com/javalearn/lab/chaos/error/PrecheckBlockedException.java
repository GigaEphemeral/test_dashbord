package com.javalearn.lab.chaos.error;

import com.javalearn.lab.chaos.precheck.PrecheckItem;
import com.javalearn.lab.chaos.precheck.PrecheckReport;

/**
 * A run start was refused by a blocking B1-04 pre-check item. HTTP 409/40903;
 * the report carrying every finding is serialized as the response {@code data},
 * so the console can show the same evidence the dedicated endpoint returns.
 *
 * <p>There is no "force" variant on purpose: the only way past this exception
 * is to fix the environment and let the next pre-check pass. The message is a
 * one-line summary (also what {@code lab_operation.last_error} keeps for the
 * FAILED start operation).</p>
 */
public class PrecheckBlockedException extends RuntimeException {

    private static final int MAX_MESSAGE_LENGTH = 900;

    private final transient PrecheckReport report;

    public PrecheckBlockedException(PrecheckReport report) {
        super(summarize(report));
        this.report = report;
    }

    public PrecheckReport report() {
        return report;
    }

    private static String summarize(PrecheckReport report) {
        if (report == null) {
            return "run precheck BLOCKED";
        }
        StringBuilder message = new StringBuilder("run precheck BLOCKED: ");
        boolean first = true;
        for (PrecheckItem item : report.blocking()) {
            if (!first) {
                message.append("; ");
            }
            message.append(item.key()).append(" (").append(item.message()).append(')');
            first = false;
            if (message.length() > MAX_MESSAGE_LENGTH) {
                break;
            }
        }
        message.append(". Fix the environment and retry the start; there is no force override.");
        return message.length() <= MAX_MESSAGE_LENGTH ? message.toString()
                : message.substring(0, MAX_MESSAGE_LENGTH - 3) + "...";
    }
}
