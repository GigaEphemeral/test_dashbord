package com.javalearn.lab.seckill.order.config;

import com.javalearn.lab.seckill.inventory.domain.InventoryRepository;
import com.javalearn.lab.seckill.order.api.OrderService;
import com.javalearn.lab.seckill.order.app.DefaultOrderService;
import com.javalearn.lab.seckill.order.app.OrderApplicationService;
import com.javalearn.lab.seckill.order.app.OrderQueryService;
import com.javalearn.lab.seckill.order.app.OrderTransactionService;
import com.javalearn.lab.seckill.order.domain.OrderRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the framework-neutral order use case into the Spring shell. The
 * transactional services are plain classes declared as beans so Spring's
 * transaction infrastructure proxies them.
 */
@Configuration
public class OrderConfig {

    @Bean
    public OrderService orderService(OrderRepository orderRepository,
                                     InventoryRepository inventoryRepository) {
        return new DefaultOrderService(orderRepository, inventoryRepository);
    }

    @Bean
    public OrderTransactionService orderTransactionService(OrderService orderService,
                                                           OrderRepository orderRepository,
                                                           MeterRegistry meterRegistry) {
        return new OrderTransactionService(orderService, orderRepository, meterRegistry);
    }

    @Bean
    public OrderQueryService orderQueryService(OrderService orderService,
                                               OrderRepository orderRepository) {
        return new OrderQueryService(orderService, orderRepository);
    }

    @Bean
    public OrderApplicationService orderApplicationService(
            OrderTransactionService orderTransactionService,
            OrderQueryService orderQueryService) {
        return new OrderApplicationService(orderTransactionService, orderQueryService);
    }
}
