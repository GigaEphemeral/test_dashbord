package com.javalearn.lab.seckill.jdk8.admission.api;

import com.javalearn.lab.common.BizException;
import com.javalearn.lab.common.CommonErrorCode;
import com.javalearn.lab.seckill.jdk8.admission.app.SeckillAdmissionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract of {@code /api/seckill/orders}, identical to the JDK 21 shell
 * (202/200 switch, Result envelope, 409/404/400 mappings). Ported to the Boot
 * 2.7 test slice ({@code @MockBean} instead of the Spring 6.2
 * {@code @MockitoBean}).
 */
@WebMvcTest(SeckillController.class)
class SeckillControllerTest {

    private static final String BODY = "{\"userId\":\"u1\",\"productId\":20,\"quantity\":1}";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SeckillAdmissionService admissionService;

    @Test
    void asyncAcceptReturns202WithAcceptedStatus() throws Exception {
        given(admissionService.isAsyncEnabled()).willReturn(true);
        given(admissionService.admit(eq("key-1"), any(CreateSeckillOrderRequest.class)))
                .willReturn(new SeckillResponse("request-1", "ACCEPTED", null));

        mockMvc.perform(post("/api/seckill/orders")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.requestId").value("request-1"))
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.orderId").value(nullValue()));
    }

    @Test
    void replayReturnsSameRequestWithCurrentStatus() throws Exception {
        given(admissionService.isAsyncEnabled()).willReturn(true);
        given(admissionService.admit(eq("key-1"), any(CreateSeckillOrderRequest.class)))
                .willReturn(new SeckillResponse("request-1", "CREATED", 7L));

        mockMvc.perform(post("/api/seckill/orders")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.requestId").value("request-1"))
                .andExpect(jsonPath("$.data.status").value("CREATED"))
                .andExpect(jsonPath("$.data.orderId").value(7));
    }

    @Test
    void syncToggleReturns200WithCreatedOrder() throws Exception {
        given(admissionService.isAsyncEnabled()).willReturn(false);
        given(admissionService.admit(eq("key-1"), any(CreateSeckillOrderRequest.class)))
                .willReturn(new SeckillResponse(null, "CREATED", 7L));

        mockMvc.perform(post("/api/seckill/orders")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(7))
                .andExpect(jsonPath("$.data.status").value("CREATED"));
    }

    @Test
    void missingIdempotencyKeyReturns400() throws Exception {
        mockMvc.perform(post("/api/seckill/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"))
                .andExpect(jsonPath("$.message").value("Idempotency-Key header is required"));

        verifyNoInteractions(admissionService);
    }

    @Test
    void blankIdempotencyKeyReturns400() throws Exception {
        mockMvc.perform(post("/api/seckill/orders")
                        .header("Idempotency-Key", "   ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"));

        verifyNoInteractions(admissionService);
    }

    @Test
    void invalidQuantityReturns400() throws Exception {
        given(admissionService.admit(eq("key-1"), any(CreateSeckillOrderRequest.class)))
                .willThrow(new BizException(
                        CommonErrorCode.INVALID_ARGUMENT, "quantity must be positive"));

        mockMvc.perform(post("/api/seckill/orders")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"u1\",\"productId\":20,\"quantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"))
                .andExpect(jsonPath("$.message").value("quantity must be positive"));
    }

    @Test
    void soldOutReturns409Conflict() throws Exception {
        given(admissionService.admit(eq("key-1"), any(CreateSeckillOrderRequest.class)))
                .willThrow(new BizException(CommonErrorCode.CONFLICT, "Sold out"));

        mockMvc.perform(post("/api/seckill/orders")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("40901"))
                .andExpect(jsonPath("$.message").value("Sold out"));
    }

    @Test
    void lockTimeoutReturns409Conflict() throws Exception {
        given(admissionService.admit(eq("key-1"), any(CreateSeckillOrderRequest.class)))
                .willThrow(new BizException(CommonErrorCode.CONFLICT, "Seckill lock timeout"));

        mockMvc.perform(post("/api/seckill/orders")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("40901"));
    }

    @Test
    void pollsCreatedRequest() throws Exception {
        given(admissionService.getRequest("request-1"))
                .willReturn(new SeckillRequestView("request-1", "CREATED", 7L, null));

        mockMvc.perform(get("/api/seckill/orders/request-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.requestId").value("request-1"))
                .andExpect(jsonPath("$.data.status").value("CREATED"))
                .andExpect(jsonPath("$.data.orderId").value(7));
    }

    @Test
    void unknownRequestReturns404() throws Exception {
        given(admissionService.getRequest("missing"))
                .willThrow(new BizException(
                        CommonErrorCode.NOT_FOUND, "Seckill request not found: missing"));

        mockMvc.perform(get("/api/seckill/orders/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("40401"))
                .andExpect(jsonPath("$.message").value("Seckill request not found: missing"));
    }
}
