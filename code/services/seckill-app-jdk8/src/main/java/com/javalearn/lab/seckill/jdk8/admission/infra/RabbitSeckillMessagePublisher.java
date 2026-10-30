package com.javalearn.lab.seckill.jdk8.admission.infra;

import com.javalearn.lab.seckill.jdk8.admission.app.SeckillMessagePublisher;
import com.javalearn.lab.seckill.jdk8.admission.domain.SeckillOrderMessage;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.AbstractJavaTypeMapper;

import java.util.Objects;

/**
 * Publishes the accepted request as JSON to the configured direct exchange and
 * routing key. The exchange/queue/binding are declared by
 * {@code SeckillRabbitConfig}.
 *
 * <p>The {@code __TypeId__} header is rewritten to the canonical cross-runtime
 * type id ({@code app.seckill.mq.type-id}, the JDK 21 class name) so the
 * JDK 21 peers can deserialize a message produced by this shell. Without it the
 * header would carry this module's own class name, which the JDK 21 jars cannot
 * load, and one third of the async admissions would fail at the consumer.</p>
 */
public final class RabbitSeckillMessagePublisher implements SeckillMessagePublisher {

    private final RabbitTemplate rabbitTemplate;
    private final String exchange;
    private final String routingKey;
    private final String typeId;

    public RabbitSeckillMessagePublisher(RabbitTemplate rabbitTemplate,
                                         String exchange,
                                         String routingKey,
                                         String typeId) {
        this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate, "rabbitTemplate");
        this.exchange = Objects.requireNonNull(exchange, "exchange");
        this.routingKey = Objects.requireNonNull(routingKey, "routingKey");
        this.typeId = Objects.requireNonNull(typeId, "typeId");
    }

    @Override
    public void publish(SeckillOrderMessage message) {
        rabbitTemplate.convertAndSend(exchange, routingKey, message, typeIdPostProcessor());
    }

    private MessagePostProcessor typeIdPostProcessor() {
        return message -> {
            message.getMessageProperties().setHeader(
                    AbstractJavaTypeMapper.DEFAULT_CLASSID_FIELD_NAME, typeId);
            return message;
        };
    }
}
