package com.javalearn.lab.chaos.metrics;

import com.javalearn.lab.chaos.error.InvalidMetricsRequestException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Window boundary parsing. Callers may send ISO-8601 ({@code 2026-10-28T10:00:00Z})
 * or a numeric epoch value; numbers below 1e11 are seconds, the rest milliseconds
 * (a 10-digit second epoch is ~2001-2286, an 13-digit milli epoch is >= 1973).
 * Invalid input is a 400 ({@link InvalidMetricsRequestException}), never a silent
 * fallback to "now".
 */
final class MetricsTime {

    private static final BigDecimal MILLIS_THRESHOLD = BigDecimal.valueOf(100_000_000_000L);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    private MetricsTime() {
    }

    static Instant parse(String raw, String name) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidMetricsRequestException("Query parameter '" + name + "' is required");
        }
        String value = raw.trim();
        if (value.matches("\\d{1,19}(\\.\\d{1,9})?")) {
            BigDecimal number = new BigDecimal(value);
            if (number.compareTo(MILLIS_THRESHOLD) >= 0) {
                return Instant.ofEpochMilli(number.longValue());
            }
            long seconds = number.longValue();
            int nanos = number.subtract(BigDecimal.valueOf(seconds)).movePointRight(9).intValue();
            return Instant.ofEpochSecond(seconds, nanos);
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // try an explicit offset next
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            // fall through to the 400
        }
        throw new InvalidMetricsRequestException("Query parameter '" + name
                + "' must be ISO-8601 or epoch seconds/millis, was '" + value + "'");
    }

    static String iso(Instant instant) {
        return ISO.format(instant);
    }
}
