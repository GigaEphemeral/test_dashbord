package com.javalearn.lab.seckill.jdk8.order.infra;

import com.javalearn.lab.seckill.order.domain.Order;
import com.javalearn.lab.seckill.order.domain.OrderRepository;
import com.javalearn.lab.seckill.order.domain.OrderStatus;
import com.javalearn.lab.seckill.order.error.DuplicateOrderKeyException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

/**
 * Outbound adapter: maps between the JPA entity and the framework-neutral
 * {@link Order}. {@code saveAndFlush} forces the INSERT (and therefore any
 * unique-key violation) to surface here, so the application layer can react to
 * a concurrent duplicate before the transaction commits.
 */
@Component
public class JpaOrderRepositoryAdapter implements OrderRepository {

    private final OrderJpaRepository orderJpaRepository;

    public JpaOrderRepositoryAdapter(OrderJpaRepository orderJpaRepository) {
        this.orderJpaRepository = Objects.requireNonNull(orderJpaRepository, "orderJpaRepository");
    }

    @Override
    public Order save(Order order) {
        try {
            OrderJpaEntity saved = orderJpaRepository.saveAndFlush(toEntity(order));
            return toDomain(saved);
        } catch (DataIntegrityViolationException exception) {
            throw new DuplicateOrderKeyException(
                    "Duplicate order key: " + order.getIdempotencyKey(), exception);
        }
    }

    @Override
    public Optional<Order> findById(long id) {
        return orderJpaRepository.findById(id).map(JpaOrderRepositoryAdapter::toDomain);
    }

    @Override
    public Optional<Order> findByIdempotencyKey(String idempotencyKey) {
        return orderJpaRepository.findByIdempotencyKey(idempotencyKey)
                .map(JpaOrderRepositoryAdapter::toDomain);
    }

    private static OrderJpaEntity toEntity(Order order) {
        return new OrderJpaEntity(
                order.getOrderNo(),
                order.getUserId(),
                order.getProductId(),
                order.getQuantity(),
                order.getStatus().name(),
                order.getIdempotencyKey(),
                order.getCreatedAt());
    }

    private static Order toDomain(OrderJpaEntity entity) {
        return new Order(
                entity.getId(),
                entity.getOrderNo(),
                entity.getUserId(),
                entity.getProductId(),
                entity.getQuantity(),
                OrderStatus.valueOf(entity.getStatus()),
                entity.getIdempotencyKey(),
                entity.getCreatedAt());
    }
}
