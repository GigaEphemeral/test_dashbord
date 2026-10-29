package com.javalearn.lab.chaos.schedule;

import com.javalearn.lab.chaos.action.ChaosActionType;
import com.javalearn.lab.chaos.config.ChaosProperties;
import com.javalearn.lab.chaos.error.InvalidChaosActionException;
import com.javalearn.lab.chaos.error.InvalidLedgerRequestException;
import com.javalearn.lab.chaos.ledger.LedgerProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * B3-00b create-time validator for the run-scoped fault timeline.
 *
 * <p>Contract (design §16.8): every entry is an object with exactly
 * {@code atSeconds, target, action, recovery, ttlSeconds}. {@code atSeconds} is
 * a non-negative offset below {@code lab.fault-schedule.max-offset-seconds};
 * {@code target} has the Docker container-name shape; {@code action} is one of
 * kill/start/restart/freeze/resume. {@code recovery=auto} requires a TTL in
 * {@code 1..chaos.max-ttl-seconds}; {@code recovery=manual} (or a legacy
 * {@code ttlSeconds=-1}) is normalised to a manual hold with
 * {@code ttlSeconds=null}. Duplicate offsets/targets are legal (a timeline may
 * legitimately hit the same node twice); unknown keys, non-objects and missing
 * fields are rejected so a typo never silently becomes an experiment.</p>
 *
 * <p>The returned entries are what {@code RunLedgerService.create} persists, so
 * the stored JSON is already canonical and the scheduler does not have to
 * re-interpret caller input.</p>
 */
public class FaultScheduleValidator {

    /** Same Docker name shape chaos actions accept (no URL, path or whitespace). */
    private static final Pattern TARGET = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");
    private static final Set<String> KEYS = Set.of(
            "atSeconds", "target", "action", "recovery", "ttlSeconds");
    private static final String PATH = "faultSchedule";

    private final LedgerProperties ledgerProperties;
    private final ChaosProperties chaosProperties;
    private final FaultScheduleProperties scheduleProperties;

    public FaultScheduleValidator(LedgerProperties ledgerProperties,
                                  ChaosProperties chaosProperties,
                                  FaultScheduleProperties scheduleProperties) {
        this.ledgerProperties = ledgerProperties;
        this.chaosProperties = chaosProperties;
        this.scheduleProperties = scheduleProperties;
    }

    /**
     * Validates and normalises one request's timeline.
     *
     * @param raw entries as received by the ledger API; {@code null} means "no
     *            fault schedule" and yields an empty list
     * @throws InvalidLedgerRequestException on any schema violation
     */
    public List<FaultScheduleEntry> validate(List<Map<String, Object>> raw) {
        if (raw == null) {
            return List.of();
        }
        int maxItems = ledgerProperties.getMaxFaultItems();
        if (raw.size() > maxItems) {
            throw new InvalidLedgerRequestException(PATH + " has " + raw.size()
                    + " items, above lab.ledger.max-fault-items=" + maxItems);
        }
        List<FaultScheduleEntry> entries = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            entries.add(normalize(i, raw.get(i)));
        }
        return List.copyOf(entries);
    }

    private FaultScheduleEntry normalize(int index, Map<String, Object> raw) {
        String path = PATH + "[" + index + "]";
        if (raw == null) {
            throw new InvalidLedgerRequestException(path + " must be an object");
        }
        for (String key : raw.keySet()) {
            if (key == null || !KEYS.contains(key)) {
                throw new InvalidLedgerRequestException(path + " has unknown key '" + key
                        + "'; allowed keys are " + KEYS);
            }
        }

        int atSeconds = requireInt(raw.get("atSeconds"), path + ".atSeconds");
        if (atSeconds < 0) {
            throw new InvalidLedgerRequestException(path + ".atSeconds must be at least 0 (was "
                    + atSeconds + ")");
        }
        int maxOffset = scheduleProperties.getMaxOffsetSeconds();
        if (atSeconds > maxOffset) {
            throw new InvalidLedgerRequestException(path + ".atSeconds (" + atSeconds
                    + ") is above lab.fault-schedule.max-offset-seconds=" + maxOffset);
        }

        String target = requireText(raw.get("target"), path + ".target");
        if (!TARGET.matcher(target).matches()) {
            throw new InvalidLedgerRequestException(path + ".target '" + target + "' must match "
                    + TARGET.pattern() + " (a container name, never a URL or host)");
        }

        String rawAction = requireText(raw.get("action"), path + ".action");
        ChaosActionType type;
        try {
            type = ChaosActionType.parse(rawAction);
        } catch (InvalidChaosActionException e) {
            throw new InvalidLedgerRequestException(path + ".action '" + rawAction
                    + "' is not one of " + ChaosActionType.ALLOWED);
        }

        String rawRecovery = requireText(raw.get("recovery"), path + ".recovery")
                .toLowerCase(Locale.ROOT);
        if (!FaultScheduleEntry.RECOVERY_AUTO.equals(rawRecovery)
                && !FaultScheduleEntry.RECOVERY_MANUAL.equals(rawRecovery)) {
            throw new InvalidLedgerRequestException(path + ".recovery must be 'auto' or 'manual'"
                    + " (was '" + rawRecovery + "')");
        }

        Integer ttl = optionalInt(raw.get("ttlSeconds"), path + ".ttlSeconds");
        if (ttl != null && ttl == -1) {
            // Legacy/manual wire value: a manual hold has no automatic revert.
            return new FaultScheduleEntry(atSeconds, target, type.wireName(),
                    FaultScheduleEntry.RECOVERY_MANUAL, null);
        }
        if (FaultScheduleEntry.RECOVERY_MANUAL.equals(rawRecovery)) {
            // A manual hold never carries a TTL, even if one was supplied.
            return new FaultScheduleEntry(atSeconds, target, type.wireName(),
                    FaultScheduleEntry.RECOVERY_MANUAL, null);
        }
        if (ttl == null) {
            throw new InvalidLedgerRequestException(path + ".ttlSeconds is required when"
                    + " recovery=auto (or send ttlSeconds=-1 for a manual hold)");
        }
        int maxTtl = chaosProperties.getMaxTtlSeconds();
        if (ttl < 1 || ttl > maxTtl) {
            throw new InvalidLedgerRequestException(path + ".ttlSeconds must be between 1 and "
                    + maxTtl + " for recovery=auto (was " + ttl + ")");
        }
        return new FaultScheduleEntry(atSeconds, target, type.wireName(),
                FaultScheduleEntry.RECOVERY_AUTO, ttl);
    }

    private static String requireText(Object value, String path) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new InvalidLedgerRequestException(path + " is required and must be a non-blank string");
        }
        return text.trim();
    }

    private static int requireInt(Object value, String path) {
        Integer parsed = optionalInt(value, path);
        if (parsed == null) {
            throw new InvalidLedgerRequestException(path + " is required and must be an integer");
        }
        return parsed;
    }

    private static Integer optionalInt(Object value, String path) {
        if (value == null) {
            return null;
        }
        if (value instanceof Integer number) {
            return number;
        }
        if (value instanceof Long number
                && number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE) {
            return number.intValue();
        }
        if (value instanceof Short number) {
            return number.intValue();
        }
        if (value instanceof Byte number) {
            return number.intValue();
        }
        throw new InvalidLedgerRequestException(path + " must be an integer (was "
                + value.getClass().getSimpleName() + " " + value + ")");
    }
}
