package com.javalearn.lab.seckill;

import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Singleton RabbitMQ 4.1 broker shared by full-context tests.
 *
 * <p>Every full application context starts the seckill listener container, so
 * all full-context tests need a broker even when they only exercise catalog or
 * order persistence. Started once per Surefire JVM for the same reason as the
 * MySQL/Redis singletons: Spring caches contexts, so the AMQP host/port must
 * stay stable for the whole JVM.</p>
 */
public final class RabbitMqTestContainer {

    private static final RabbitMQContainer RABBIT =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.1.0-management"));

    static {
        RABBIT.start();
    }

    private RabbitMqTestContainer() {
    }

    public static String host() {
        return RABBIT.getHost();
    }

    public static int amqpPort() {
        return RABBIT.getAmqpPort();
    }

    public static String username() {
        return RABBIT.getAdminUsername();
    }

    public static String password() {
        return RABBIT.getAdminPassword();
    }
}
