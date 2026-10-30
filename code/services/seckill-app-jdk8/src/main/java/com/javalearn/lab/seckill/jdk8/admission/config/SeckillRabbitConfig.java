package com.javalearn.lab.seckill.jdk8.admission.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.seckill.jdk8.admission.domain.SeckillOrderMessage;
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
 * <p>Names come exclusively from {@code app.seckill.mq.*} and match the JDK 21
 * shell, so all three instances share one exchange/queue pair.</p>
 *
 * <p>The converter is the cross-runtime compatibility point. Spring AMQP's
 * default converter selects the consumer class from the {@code __TypeId__}
 * header, which would be this module's own
 * {@code ...jdk8.admission.domain.SeckillOrderMessage} class name and would not
 * exist in the JDK 21 peers. Two adjustments keep the shared queue readable in
 * both directions:</p>
 * <ul>
 *   <li>the publisher overwrites {@code __TypeId__} with
 *       {@code app.seckill.mq.type-id} (the JDK 21 class name);</li>
 *   <li>the consumer always converts to the listener's inferred argument type
 *       ({@link SeckillOrderMessage}), so a message published by either runtime
 *       is accepted.</li>
 * </ul>
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
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter(objectMapper);
        // Consume by the listener signature, never by the header of the other
        // runtime (whose class name does not exist in this jar).
        converter.setAlwaysConvertToInferredType(true);
        return converter;
    }
}
