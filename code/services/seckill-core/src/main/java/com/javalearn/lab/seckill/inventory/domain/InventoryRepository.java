package com.javalearn.lab.seckill.inventory.domain;

/**
 * Framework-neutral inventory port. Implementations must reserve atomically:
 * a positive result guarantees exactly {@code quantity} units were taken from
 * the available stock, and available stock can never become negative.
 */
public interface InventoryRepository {

    /**
     * Reserves stock for one order line.
     *
     * @return {@code true} when the reservation succeeded, {@code false} when
     *         there was not enough available stock
     */
    boolean reserve(long productId, int quantity);
}
