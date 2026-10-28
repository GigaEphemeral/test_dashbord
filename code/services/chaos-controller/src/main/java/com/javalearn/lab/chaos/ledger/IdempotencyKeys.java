package com.javalearn.lab.chaos.ledger;

import com.javalearn.lab.chaos.error.InvalidLedgerRequestException;

/**
 * Validation of the optional {@code Idempotency-Key} request header.
 *
 * <p>Contract: 1-128 <em>visible</em> ASCII characters (0x21-0x7E). A space is
 * excluded because HTTP header values are trimmed at the edges and an inner
 * space would make the key hard to paste reliably; control characters are
 * rejected outright. A missing or blank header means "not idempotent" and is
 * not an error.</p>
 */
public final class IdempotencyKeys {

    public static final String HEADER = "Idempotency-Key";
    public static final int MAX_LENGTH = 128;

    private IdempotencyKeys() {
    }

    /**
     * Returns null for absent keys, the key itself when valid, and 400 when the
     * value cannot be used as an idempotency token.
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = raw.trim();
        if (key.length() > MAX_LENGTH) {
            throw invalid("longer than " + MAX_LENGTH + " characters (" + key.length() + ")");
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c < 0x21 || c > 0x7E) {
                throw invalid("contains a non-visible-ASCII character at index " + i
                        + " (0x" + Integer.toHexString(c) + ")");
            }
        }
        return key;
    }

    private static InvalidLedgerRequestException invalid(String reason) {
        return new InvalidLedgerRequestException("header " + HEADER + " " + reason
                + "; expected 1-" + MAX_LENGTH + " visible ASCII characters (0x21-0x7E)");
    }
}
