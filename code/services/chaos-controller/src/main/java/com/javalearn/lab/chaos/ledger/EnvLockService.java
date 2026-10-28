package com.javalearn.lab.chaos.ledger;

import com.javalearn.lab.chaos.error.EnvLockConflictException;
import com.javalearn.lab.chaos.error.LedgerUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Single-writer environment lock (design §10.5): one run at a time may change
 * the environment, read-only observation stays parallel.
 *
 * <p>Acquisition is insert-first: the primary key guarantees exactly one holder.
 * A conflicting row whose {@code expires_at} has passed is taken over with a
 * compare-and-set on the observed expiry, so two contenders can never both win;
 * a live row is a 409/40902 that names the holder and the two remedies (stop
 * the holder run, or wait for the TTL). The TTL is the escape hatch for a dead
 * controller - releasing is best-effort and honest: {@link ReleaseReport} says
 * whether this run actually deleted the row or the lock had already expired /
 * been taken over.</p>
 */
public class EnvLockService {

    private static final Logger log = LoggerFactory.getLogger(EnvLockService.class);

    private final LedgerProperties properties;
    private final RunLedgerRepository repository;
    private final Clock clock;

    public EnvLockService(LedgerProperties properties, RunLedgerRepository repository, Clock clock) {
        this.properties = properties;
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * @throws EnvLockConflictException when another live run holds the lock
     * @throws LedgerUnavailableException when the lock table cannot be read
     */
    public LockHandle acquire(String runId) {
        String lockKey = properties.getEnvLock().getKey();
        int ttlSeconds = properties.getEnvLock().getTtlSeconds();
        String holder = properties.getOperator();
        try {
            int attempts = properties.getEnvLock().getAcquireAttempts();
        for (int attempt = 1; attempt <= attempts; attempt++) {
                Instant now = clock.instant();
                Instant expiresAt = now.plusSeconds(ttlSeconds);
                try {
                    repository.insertEnvLock(lockKey, runId, holder, now, expiresAt);
                    log.info("Acquired write-run lock {} for {} (ttl={}s, expires={})",
                            lockKey, runId, ttlSeconds, expiresAt);
                    return new LockHandle(lockKey, runId, holder, now, expiresAt);
                } catch (DuplicateKeyException e) {
                    Optional<RunLedgerRepository.EnvLockRow> current = repository.findEnvLock(lockKey);
                    if (current.isEmpty()) {
                        // Released between the insert and the read: retry.
                        continue;
                    }
                    RunLedgerRepository.EnvLockRow row = current.get();
                    if (runId.equals(row.runId())) {
                        // Same run asking again (e.g. a replayed start that lost
                        // the idempotency window): its own lock is not a conflict.
                        return new LockHandle(row.lockKey(), row.runId(), row.holder(),
                                row.acquiredAt(), row.expiresAt());
                    }
                    if (!row.expiresAt().isAfter(now)) {
                        int taken = repository.takeOverEnvLock(lockKey, row.expiresAt(), runId, holder,
                                now, expiresAt);
                        if (taken > 0) {
                            log.warn("Took over expired write-run lock {} from {} for {} (old expiry {})",
                                    lockKey, row.runId(), runId, row.expiresAt());
                            return new LockHandle(lockKey, runId, holder, now, expiresAt);
                        }
                        continue;
                    }
                    throw new EnvLockConflictException(conflictMessage(row));
                }
            }
        } catch (DataAccessException e) {
            throw new LedgerUnavailableException("write-run lock unavailable: "
                    + e.getMostSpecificCause().getMessage() + "; see GET /lab/ledger/status");
        }
        throw new EnvLockConflictException("write-run lock " + lockKey
                + " kept changing during " + properties.getEnvLock().getAcquireAttempts()
                + " acquisition attempts; retry the start");
    }

    /**
     * Deletes the lock row only when this run holds it. A missing row is
     * reported, never treated as success: it means the TTL expired (and another
     * run may already hold it).
     */
    public ReleaseReport release(String runId) {
        String lockKey = properties.getEnvLock().getKey();
        try {
            int deleted = repository.releaseEnvLock(lockKey, runId);
            if (deleted > 0) {
                log.info("Released write-run lock {} held by {}", lockKey, runId);
                return new ReleaseReport(true, null, null);
            }
            Optional<RunLedgerRepository.EnvLockRow> current = repository.findEnvLock(lockKey);
            if (current.isEmpty()) {
                return new ReleaseReport(false,
                        "lock was already released or its TTL had expired; nothing to delete", null);
            }
            RunLedgerRepository.EnvLockRow row = current.get();
            return new ReleaseReport(false,
                    "lock had expired and is now held by run " + row.runId()
                            + " until " + row.expiresAt() + "; this stop did not release it",
                    row.runId());
        } catch (DataAccessException e) {
            return new ReleaseReport(false, "lock release failed: "
                    + e.getMostSpecificCause().getMessage() + "; the lock stays until its TTL expires", null);
        }
    }

    public Optional<RunLedgerRepository.EnvLockRow> current() {
        return repository.findEnvLock(properties.getEnvLock().getKey());
    }

    private String conflictMessage(RunLedgerRepository.EnvLockRow row) {
        return "write-run lock " + row.lockKey() + " is held by run " + row.runId()
                + " until " + row.expiresAt() + " (holder " + row.holder()
                + ", ttl " + properties.getEnvLock().getTtlSeconds() + "s). Release it with"
                + " POST /lab/runs/" + row.runId() + "/stop (an UNKNOWN run is closed as ABORTED),"
                + " or wait for the TTL to expire";
    }

    /** One held lock: the run that owns it and when it can be taken over. */
    public record LockHandle(String lockKey, String runId, String holder, Instant acquiredAt,
                             Instant expiresAt) {
    }

    /** Whether this stop really removed the lock, plus the honest reason when not. */
    public record ReleaseReport(boolean released, String note, String currentHolderRunId) {
    }
}
