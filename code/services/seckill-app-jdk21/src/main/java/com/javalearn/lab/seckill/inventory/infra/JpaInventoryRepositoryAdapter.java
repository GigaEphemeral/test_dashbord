package com.javalearn.lab.seckill.inventory.infra;

import com.javalearn.lab.seckill.inventory.domain.InventoryRepository;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Outbound adapter: a reservation succeeds only when the conditional stock
 * update touched a row.
 */
@Component
public class JpaInventoryRepositoryAdapter implements InventoryRepository {

    private final InventoryStockJpaRepository inventoryStockJpaRepository;

    public JpaInventoryRepositoryAdapter(InventoryStockJpaRepository inventoryStockJpaRepository) {
        this.inventoryStockJpaRepository = Objects.requireNonNull(
                inventoryStockJpaRepository, "inventoryStockJpaRepository");
    }

    @Override
    public boolean reserve(long productId, int quantity) {
        return inventoryStockJpaRepository.reserve(productId, quantity) > 0;
    }
}
