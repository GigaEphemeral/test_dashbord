package com.javalearn.lab.chaos.action;

/**
 * Minimal collaborator that lets {@link ChaosActionService#stopAll()} restore
 * the network-fault layer without depending on the Toxiproxy implementation.
 *
 * <p>Stop-all is the lab's "one button" recovery: container faults are reverted
 * by the action service, and this hook removes every Toxiproxy toxic and
 * re-enables every configured proxy. Implementations must never throw from
 * {@link #resetAll()} - a broken network cleanup is reported in the result and
 * must not stop the container reverts.</p>
 */
@FunctionalInterface
public interface NetworkFaultCleaner {

    /** No network layer wired (unit-test seams and legacy constructors). */
    NetworkFaultCleaner NONE = NetworkResetReport::notConfigured;

    NetworkResetReport resetAll();
}
