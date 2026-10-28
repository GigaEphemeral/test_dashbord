package com.javalearn.lab.chaos.ledger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Keeps credentials out of audit data: {@code lab_operation.last_error}, the
 * before/after summaries and every string the operation service derives from a
 * request or an exception.
 *
 * <p>Two rules, deliberately simple and explainable:</p>
 * <ol>
 *   <li>a summary key that looks like a credential is dropped entirely (never
 *       stored, never hashed);</li>
 *   <li>a value that carries {@code password=...}, {@code token: ...},
 *       {@code api_key=...} or {@code Bearer ...} has the secret replaced with
 *       {@code ***}, so the audit keeps the shape of the error but not the
 *       secret.</li>
 * </ol>
 *
 * <p>This is an audit guard for a teaching lab, not a DLP engine: callers still
 * must not put credentials into summaries. The pattern mirrors the ledger's
 * snapshot guard ({@code RunLedgerService}) so both paths agree.</p>
 */
public final class AuditSanitizer {

    /** Column width of {@code lab_operation.last_error}. */
    public static final int MAX_ERROR_LENGTH = 1024;

    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i).*(password|passwd|pwd|secret|token|credential|api[-_]?key|authorization).*");

    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?i)\\b(password|passwd|pwd|secret|token|credential|api[-_]?key)(\\s*[=:]\\s*)"
                    + "(\"[^\"]*\"|'[^']*'|\\S+)");

    private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+");

    private static final Pattern URL_USERINFO = Pattern.compile("(://[^:/@\\s]+:)[^@/\\s]+@");

    private AuditSanitizer() {
    }

    /** Masks credential-looking assignments in free text; never returns null. */
    public static String scrub(String raw) {
        if (raw == null) {
            return null;
        }
        String masked = ASSIGNMENT.matcher(raw).replaceAll("$1$2***");
        masked = BEARER.matcher(masked).replaceAll("$1***");
        masked = URL_USERINFO.matcher(masked).replaceAll("$1***@");
        return clip(masked, MAX_ERROR_LENGTH);
    }

    /** One-line description of a failure, safe for {@code last_error}. */
    public static String describe(Throwable error) {
        if (error == null) {
            return null;
        }
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        return scrub(error.getClass().getSimpleName() + ": " + message);
    }

    /**
     * Drops credential-looking keys and scrubs string values. Nested maps are
     * copied so the caller's request snapshot is never mutated. Returns a
     * non-null, order-preserving map.
     */
    public static Map<String, Object> scrubSummary(Map<String, Object> summary) {
        Map<String, Object> clean = new LinkedHashMap<>();
        if (summary == null) {
            return clean;
        }
        summary.forEach((key, value) -> {
            if (key == null || SENSITIVE_KEY.matcher(key).matches()) {
                return;
            }
            clean.put(key, scrubValue(value));
        });
        return clean;
    }

    private static Object scrubValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return scrub(text);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        return scrub(value.toString());
    }

    /** Truncates to {@code max} characters (callers pass schema widths). */
    public static String clip(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
