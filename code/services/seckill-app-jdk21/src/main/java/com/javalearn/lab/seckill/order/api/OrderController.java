package com.javalearn.lab.seckill.order.api;

import com.javalearn.lab.common.BizException;
import com.javalearn.lab.common.CommonErrorCode;
import com.javalearn.lab.common.Result;
import com.javalearn.lab.seckill.order.app.OrderApplicationService;
import com.javalearn.lab.seckill.order.domain.Order;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Order HTTP API. {@code Idempotency-Key} is declared optional so a missing
 * header still produces the {@link Result} envelope (mapped to 400 by the
 * exception handler); the core use case then rejects it explicitly.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderApplicationService orderApplicationService;

    public OrderController(OrderApplicationService orderApplicationService) {
        this.orderApplicationService = orderApplicationService;
    }

    @PostMapping
    public Result<OrderResponse> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateOrderRequest request) {
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) {
            throw new BizException(CommonErrorCode.INVALID_ARGUMENT,
                    "Idempotency-Key header is required");
        }
        Order order = orderApplicationService.create(new CreateOrderCommand(
                request.userId(),
                request.productId(),
                request.quantity(),
                idempotencyKey));
        return Result.success(OrderResponse.from(order));
    }

    @GetMapping("/{id}")
    public Result<OrderResponse> get(@PathVariable long id) {
        return Result.success(OrderResponse.from(orderApplicationService.get(id)));
    }
}
