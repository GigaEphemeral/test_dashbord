package com.javalearn.lab.seckill.order.app;

import com.javalearn.lab.common.BizException;
import com.javalearn.lab.common.CommonErrorCode;
import com.javalearn.lab.seckill.inventory.domain.InventoryRepository;
import com.javalearn.lab.seckill.order.api.CreateOrderCommand;
import com.javalearn.lab.seckill.order.api.OrderService;
import com.javalearn.lab.seckill.order.domain.Order;
import com.javalearn.lab.seckill.order.domain.OrderRepository;
import com.javalearn.lab.seckill.order.domain.OrderStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Framework-neutral order use case: validation, idempotent replay, atomic
 * stock reservation and order creation. The caller owns the transaction so the
 * stock update and the order insert commit or roll back together.
 */
public final class DefaultOrderService implements OrderService {

    private final OrderRepository orderRepository;
    private final InventoryRepository inventoryRepository;

    public DefaultOrderService(OrderRepository orderRepository,
                               InventoryRepository inventoryRepository) {
        this.orderRepository = Objects.requireNonNull(orderRepository, "orderRepository");
        this.inventoryRepository = Objects.requireNonNull(inventoryRepository, "inventoryRepository");
    }

    @Override
    public Order create(CreateOrderCommand command) {
        Objects.requireNonNull(command, "command");
        validate(command);

        Order existing = orderRepository.findByIdempotencyKey(command.getIdempotencyKey()).orElse(null);
        if (existing != null) {
            return existing;
        }

        if (!inventoryRepository.reserve(command.getProductId(), command.getQuantity())) {
            throw new BizException(CommonErrorCode.CONFLICT, "Sold out");
        }

        Order order = new Order(
                0L,
                UUID.randomUUID().toString(),
                command.getUserId(),
                command.getProductId(),
                command.getQuantity(),
                OrderStatus.CREATED,
                command.getIdempotencyKey(),
                Instant.now());
        return orderRepository.save(order);
    }

    @Override
    public Order get(long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new BizException(
                        CommonErrorCode.NOT_FOUND, "Order not found: " + id));
    }

    private static void validate(CreateOrderCommand command) {
        if (isBlank(command.getUserId())) {
            throw new BizException(CommonErrorCode.INVALID_ARGUMENT, "userId is required");
        }
        if (isBlank(command.getIdempotencyKey())) {
            throw new BizException(CommonErrorCode.INVALID_ARGUMENT, "Idempotency-Key is required");
        }
        if (command.getProductId() <= 0) {
            throw new BizException(CommonErrorCode.INVALID_ARGUMENT, "productId must be positive");
        }
        if (command.getQuantity() <= 0) {
            throw new BizException(CommonErrorCode.INVALID_ARGUMENT, "quantity must be positive");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
