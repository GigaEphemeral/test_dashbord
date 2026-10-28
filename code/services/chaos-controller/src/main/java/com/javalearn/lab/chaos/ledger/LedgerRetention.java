package com.javalearn.lab.chaos.ledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Retention: keeps the newest {@code lab.ledger.retention-runs} runs. The delete
 * is a single statement against {@code lab_run}; every child table has
 * {@code ON DELETE CASCADE}, so steps/env/params/operations/events/windows/
 * checks disappear with the run and no orphan is left behind. Runs once after a
 * successful bootstrap and then at {@code retention-interval-minutes}.
 */
public class LedgerRetention {

    private static final Logger log = LoggerFactory.getLogger(LedgerRetention.class);

    private final LedgerProperties properties;
    private final RunLedgerRepository repository;

    public LedgerRetention(LedgerProperties properties, RunLedgerRepository repository) {
        this.properties = properties;
        this.repository = repository;
    }

    public int prune() {
        int deleted = repository.deleteRunsBeyondKeep(properties.getRetentionRuns());
        if (deleted > 0) {
            log.info("Ledger retention removed {} run(s); keeping the newest {}",
                    deleted, properties.getRetentionRuns());
        }
        return deleted;
    }
}
