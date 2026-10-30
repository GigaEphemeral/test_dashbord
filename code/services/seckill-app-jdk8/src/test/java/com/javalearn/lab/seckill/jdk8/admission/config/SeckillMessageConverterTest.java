package com.javalearn.lab.seckill.jdk8.admission.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.javalearn.lab.seckill.jdk8.admission.domain.SeckillOrderMessage;
import com.javalearn.lab.seckill.jdk8.admission.infra.RabbitSeckillMessagePublisher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.AbstractJavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Cross-runtime wire contract of the shared seckill queue: this shell must
 * publish the JDK 21 {@code __TypeId__} and accept an incoming message whose
 * header names the other runtime's class.
 */
class SeckillMessageConverterTest {

    private static final String CANONICAL_TYPE_ID =
            "com.javalearn.lab.seckill.admission.domain.SeckillOrderMessage";

    @Test
    void publishesCanonicalTypeIdAndConsumesByInferredType() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        Jackson2JsonMessageConverter converter =
                new SeckillRabbitConfig().rabbitMessageConverter(objectMapper);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        RabbitSeckillMessagePublisher publisher = new RabbitSeckillMessagePublisher(
                rabbitTemplate, "seckill.order.exchange", "seckill.order.requested",
                CANONICAL_TYPE_ID);

        SeckillOrderMessage outbound =
                new SeckillOrderMessage("req-1", "key-1", "u1", 20L, 1);
        publisher.publish(outbound);

        ArgumentCaptor<MessagePostProcessor> postProcessor =
                ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(rabbitTemplate).convertAndSend(
                eq("seckill.order.exchange"), eq("seckill.order.requested"),
                any(), postProcessor.capture());

        Message message = converter.toMessage(outbound, new MessageProperties());
        Message posted = postProcessor.getValue().postProcessMessage(message);
        Object typeIdHeader = posted.getMessageProperties()
                .getHeader(AbstractJavaTypeMapper.DEFAULT_CLASSID_FIELD_NAME);
        assertThat(typeIdHeader).isEqualTo(CANONICAL_TYPE_ID);

        // A message published by either runtime: the header may name the JDK 21
        // class (which this jar does not contain); conversion still succeeds
        // because the listener's inferred parameter type is authoritative.
        MessageProperties incoming = new MessageProperties();
        incoming.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        incoming.setHeader(AbstractJavaTypeMapper.DEFAULT_CLASSID_FIELD_NAME,
                "com.javalearn.lab.seckill.jdk8.admission.domain.SeckillOrderMessage");
        incoming.setInferredArgumentType(SeckillOrderMessage.class);

        Object converted = converter.fromMessage(new Message(posted.getBody(), incoming));

        assertThat(converted).isInstanceOf(SeckillOrderMessage.class);
        SeckillOrderMessage decoded = (SeckillOrderMessage) converted;
        assertThat(decoded.getRequestId()).isEqualTo("req-1");
        assertThat(decoded.getIdempotencyKey()).isEqualTo("key-1");
        assertThat(decoded.getProductId()).isEqualTo(20L);
        assertThat(decoded.getQuantity()).isEqualTo(1);
    }
}
