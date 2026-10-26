package com.javalearn.lab.seckill.order.api;

import com.javalearn.lab.common.BizException;
import com.javalearn.lab.common.CommonErrorCode;
import com.javalearn.lab.seckill.order.app.OrderApplicationService;
import com.javalearn.lab.seckill.order.app.OrderRequestSource;
import com.javalearn.lab.seckill.order.domain.Order;
import com.javalearn.lab.seckill.order.domain.OrderStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

    private static final String BODY = "{\"userId\":\"u1\",\"productId\":1,\"quantity\":1}";
    private static final Instant CREATED_AT = Instant.parse("2026-10-26T08:00:00Z");
    private static final Order ORDER = new Order(
            7L, "order-no-7", "u1", 1L, 1, OrderStatus.CREATED, "key-1", CREATED_AT);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderApplicationService orderApplicationService;

    @Test
    void createsOrderWithSuccessEnvelope() throws Exception {
        given(orderApplicationService.create(any(CreateOrderCommand.class), eq(OrderRequestSource.SYNC_API))).willReturn(ORDER);

        mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.id").value(7))
                .andExpect(jsonPath("$.data.orderNo").value("order-no-7"))
                .andExpect(jsonPath("$.data.userId").value("u1"))
                .andExpect(jsonPath("$.data.productId").value(1))
                .andExpect(jsonPath("$.data.quantity").value(1))
                .andExpect(jsonPath("$.data.status").value("CREATED"))
                .andExpect(jsonPath("$.data.createdAt").value("2026-10-26T08:00:00Z"));
    }

    @Test
    void repeatingSameKeyReturnsSameOrderId() throws Exception {
        given(orderApplicationService.create(any(CreateOrderCommand.class), eq(OrderRequestSource.SYNC_API))).willReturn(ORDER);

        MvcResult first = mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult replay = mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(first.getResponse().getContentAsString())
                .isEqualTo(replay.getResponse().getContentAsString());
        assertThat(replay.getResponse().getContentAsString()).contains("\"id\":7");
        verify(orderApplicationService, times(2))
                .create(any(CreateOrderCommand.class), eq(OrderRequestSource.SYNC_API));
    }

    @Test
    void missingIdempotencyKeyReturns400() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"))
                .andExpect(jsonPath("$.message").value("Idempotency-Key header is required"));

        verifyNoInteractions(orderApplicationService);
    }

    @Test
    void blankIdempotencyKeyReturns400() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", "   ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"));

        verifyNoInteractions(orderApplicationService);
    }

    @Test
    void invalidQuantityReturns400() throws Exception {
        given(orderApplicationService.create(any(CreateOrderCommand.class), eq(OrderRequestSource.SYNC_API))).willAnswer(invocation -> {
            CreateOrderCommand command = invocation.getArgument(0);
            if (command.getQuantity() <= 0) {
                throw new BizException(CommonErrorCode.INVALID_ARGUMENT, "quantity must be positive");
            }
            return ORDER;
        });

        mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"u1\",\"productId\":1,\"quantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"))
                .andExpect(jsonPath("$.message").value("quantity must be positive"));
    }

    @Test
    void soldOutReturns409Conflict() throws Exception {
        given(orderApplicationService.create(any(CreateOrderCommand.class), eq(OrderRequestSource.SYNC_API)))
                .willThrow(new BizException(CommonErrorCode.CONFLICT, "Sold out"));

        mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("40901"))
                .andExpect(jsonPath("$.message").value("Sold out"));
    }

    @Test
    void malformedBodyReturns400() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"));
    }

    @Test
    void queriesExistingOrder() throws Exception {
        given(orderApplicationService.get(7L)).willReturn(ORDER);

        mockMvc.perform(get("/api/orders/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.id").value(7))
                .andExpect(jsonPath("$.data.orderNo").value("order-no-7"))
                .andExpect(jsonPath("$.data.status").value("CREATED"));
    }

    @Test
    void queriesMissingOrderReturns404() throws Exception {
        given(orderApplicationService.get(999L)).willThrow(
                new BizException(CommonErrorCode.NOT_FOUND, "Order not found: 999"));

        mockMvc.perform(get("/api/orders/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("40401"))
                .andExpect(jsonPath("$.message").value("Order not found: 999"));
    }

    @Test
    void nonNumericOrderIdReturns400() throws Exception {
        mockMvc.perform(get("/api/orders/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"));
    }
}
