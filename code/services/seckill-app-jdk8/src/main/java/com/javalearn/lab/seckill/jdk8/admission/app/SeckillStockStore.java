package com.javalearn.lab.seckill.jdk8.admission.app;

/**
 * Atomic Redis stock reservation and best-effort restoration.
 */
public interface SeckillStockStore {

    Reservation reserve(long productId, int quantity);

    /**
     * Adds the quantity back when the product stock key still exists.
     *
     * @return {@code true} when the key existed and was incremented
     */
    boolean restore(long productId, int quantity);

    /**
     * Initializes the Redis stock key only when it is absent. Used by the
     * startup seeder so a running key is never overwritten.
     */
    boolean seedIfAbsent(long productId, int available);

    enum ReservationStatus {
        RESERVED,
        SOLD_OUT,
        MISSING
    }

    /** Java 8 form of the JDK 21 {@code Reservation} record. */
    final class Reservation {

        private final ReservationStatus status;
        private final long remaining;

        public Reservation(ReservationStatus status, long remaining) {
            this.status = status;
            this.remaining = remaining;
        }

        public ReservationStatus getStatus() {
            return status;
        }

        public long getRemaining() {
            return remaining;
        }
    }
}
