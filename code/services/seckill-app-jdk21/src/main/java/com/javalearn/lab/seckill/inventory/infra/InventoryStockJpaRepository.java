package com.javalearn.lab.seckill.inventory.infra;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Atomic stock reservation. The conditional {@code UPDATE ... WHERE available
 * >= :quantity} is a single InnoDB locking statement: concurrent callers queue
 * on the product row, and the database itself refuses to go below zero. The
 * method is transactional on its own so both the order transaction (REQUIRED
 * join) and direct/test callers get a real write transaction.
 */
public interface InventoryStockJpaRepository extends JpaRepository<InventoryStockEntity, Long> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update InventoryStockEntity i "
            + "set i.available = i.available - :quantity "
            + "where i.productId = :productId and i.available >= :quantity")
    int reserve(@Param("productId") long productId, @Param("quantity") int quantity);
}
