package com.javalearn.lab.seckill.admission.infra;

import com.javalearn.lab.seckill.admission.app.SeckillMessagePublisher;
import com.javalearn.lab.seckill.admission.domain.SeckillOrderMessage;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.Objects;

/**
 * Publishes the accepted request as JSON to the configured direct exchange and
 * routing key. The exchange/queue/binding are declared by
 * {@code SeckillRabbitConfig}.
 */
public final class RabbitSeckillMessagePublisher implements SeckillMessagePublisher {

    private final RabbitTemplate rabbitTemplate;
    private final String exchange;
    private final String routingKey;

    public RabbitSeckillMessagePublisher(RabbitTemplate rabbitTemplate,
                                         String exchange,
                                         String routingKey) {
        this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate, "rabbitTemplate");
        this.exchange = Objects.requireNonNull(exchange, "exchange");
        this.routingKey = Objects.requireNonNull(routingKey, "routingKey");
    }

    @Override
    public void publish(SeckillOrderMessage message) {
        rabbitTemplate.convertAndSend(exchange, routingKey, message);
    }
}
