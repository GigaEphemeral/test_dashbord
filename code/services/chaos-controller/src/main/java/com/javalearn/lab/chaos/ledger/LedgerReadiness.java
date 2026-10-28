package com.javalearn.lab.chaos.ledger;

import com.javalearn.lab.chaos.error.LedgerUnavailableException;
import org.springframework.dao.DataAccessException;

/**
 * The two readiness rules shared by every ledger entry point: the ledger must be
 * enabled and its bootstrap must be OK, and a live query failure degrades the
 * status and answers 503/50302. Kept in one place so the run API, the operation
 * contract and the lifecycle cannot drift apart.
 */
final class LedgerReadiness {

    private LedgerReadiness() {
    }

    static void requireReady(LedgerProperties properties, LedgerBootstrap bootstrap) {
        if (!properties.isEnabled()) {
            throw new LedgerUnavailableException(
                    "ledger is disabled (lab.ledger.enabled=false; env LAB_LEDGER_ENABLED)");
        }
        LedgerBootstrap.BootstrapStatus current = bootstrap.status();
        if (current.state() != LedgerBootstrap.State.OK) {
            throw new LedgerUnavailableException("ledger state is " + current.state()
                    + (current.lastError() == null ? "" : ": " + current.lastError())
                    + "; see GET /lab/ledger/status");
        }
    }

    static boolean isReady(LedgerProperties properties, LedgerBootstrap bootstrap) {
        return properties.isEnabled() && bootstrap.isReady();
    }

    static LedgerUnavailableException unavailable(LedgerBootstrap bootstrap, DataAccessException error,
                                                  String what) {
        String message = error.getMostSpecificCause().getMessage();
        bootstrap.recordFailure(what + " failed: " + message);
        return new LedgerUnavailableException(what + " failed: " + message
                + "; see GET /lab/ledger/status");
    }
}
