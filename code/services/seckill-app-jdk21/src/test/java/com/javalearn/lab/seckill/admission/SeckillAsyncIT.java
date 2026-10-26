package com.javalearn.lab.seckill.admission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.seckill.admission.domain.SeckillRequest;
import com.javalearn.lab.seckill.admission.domain.SeckillRequestStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end async admission through the real controller, Redis, RabbitMQ,
 * consumer and MySQL.
 *
 * <p>The scenario covers the whole acceptance contract: 202 accepted, polling
 * reaches CREATED with a durable order id, database and Redis stock each drop
 * by one, replaying the same idempotency key returns the same request/order
 * with no extra message or decrement, and the queue drains.</p>
 */
@AutoConfigureObservability
@AutoConfigureMockMvc
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "observability.env=test",
                "observability.instance=seckill-async-it",
                "app.seckill.consumer.delay-ms=0",
                "app.seckill.lock.wait-ms=2000",
                "app.seckill.mq.exchange=seckill.order.exchange.async-it",
                "app.seckill.mq.queue=seckill.order.queue.async-it",
                "app.seckill.mq.routing-key=seckill.order.requested.async-it"
        })
class SeckillAsyncIT extends SeckillIntegrationTestSupport {

    private static final String IDEMPOTENCY_KEY = "async-key-1";
    private static final String BODY =
            "{\"userId\":\"async-user\",\"productId\":20,\"quantity\":1}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void acceptedRequestCreatesOneOrderAndReplayIsInert() throws Exception {
        MvcResult accepted = mockMvc.perform(post("/api/seckill/orders")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.orderId").value(org.hamcrest.Matchers.nullValue()))
                .andReturn();
        JsonNode acceptedData = objectMapper.readTree(
                accepted.getResponse().getContentAsString()).path("data");
        String requestId = acceptedData.path("requestId").asText();
        assertThat(requestId).isNotBlank();

        awaitAuditMessageCount(1);
        SeckillRequest created = awaitRequestStatus(
                requestId, SeckillRequestStatus.CREATED, DEFAULT_AWAIT);
        long orderId = created.orderId();
        assertThat(orderId).isPositive();
        awaitOrderCount(1);
        awaitQueueEmpty();

        assertThat(dbStock()).isEqualTo(INITIAL_STOCK - 1);
        assertThat(redisStock()).isEqualTo(INITIAL_STOCK - 1);
        assertThat(ordersForProduct()).isEqualTo(1);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT idempotency_key, product_id, status FROM order_order WHERE id = ?",
                orderId);
        assertThat(row.get("idempotency_key")).isEqualTo(IDEMPOTENCY_KEY);
        assertThat(((Number) row.get("product_id")).longValue()).isEqualTo(SECKILL_PRODUCT_ID);
        assertThat(row.get("status")).isEqualTo("CREATED");

        mockMvc.perform(get("/api/seckill/orders/{requestId}", requestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.requestId").value(requestId))
                .andExpect(jsonPath("$.data.status").value("CREATED"))
                .andExpect(jsonPath("$.data.orderId").value(orderId));

        assertThat(ttlSeconds(requestKey(requestId))).isPositive();
        assertThat(ttlSeconds(idempotencyKey(IDEMPOTENCY_KEY))).isPositive();

        MvcResult replay = mockMvc.perform(post("/api/seckill/orders")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("CREATED"))
                .andExpect(jsonPath("$.data.requestId").value(requestId))
                .andExpect(jsonPath("$.data.orderId").value(orderId))
                .andReturn();
        assertThat(replay.getResponse().getStatus()).isEqualTo(202);

        // No second message, order or stock decrement for the replay.
        awaitAuditMessageCount(1);
        awaitQueueEmpty();
        assertThat(orderCount()).isEqualTo(1);
        assertThat(dbStock()).isEqualTo(INITIAL_STOCK - 1);
        assertThat(redisStock()).isEqualTo(INITIAL_STOCK - 1);

        assertThat(attempts("accepted")).isEqualTo(1.0d);
        assertThat(attempts("replay")).isEqualTo(1.0d);
        assertThat(attempts("error")).isZero();
        assertThat(consumerProcessed("created")).isEqualTo(1.0d);
        assertThat(consumerProcessed("failed")).isZero();
        assertThat(stockOversold()).isZero();
    }
}
