package com.javalearn.lab.seckill.admission.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Durable direct exchange, queue and binding for accepted seckill requests,
 * plus the JSON converter used by both the producer and the listener.
 *
 * <p>Names come exclusively from {@code app.seckill.mq.*}. The single
 * {@code MessageConverter} bean is picked up by Boot's RabbitTemplate and
 * listener-container factory configurers.</p>
 */
@Configuration(proxyBeanMethods = false)
public class SeckillRabbitConfig {

    @Bean
    public Declarables seckillOrderTopology(SeckillProperties properties) {
        SeckillProperties.Mq mq = properties.getMq();
        DirectExchange exchange = new DirectExchange(mq.getExchange(), true, false);
        Queue queue = QueueBuilder.durable(mq.getQueue()).build();
        Binding binding = BindingBuilder.bind(queue).to(exchange).with(mq.getRoutingKey());
        return new Declarables(exchange, queue, binding);
    }

    @Bean
    public Jackson2JsonMessageConverter rabbitMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
