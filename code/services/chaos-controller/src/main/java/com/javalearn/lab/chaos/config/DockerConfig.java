package com.javalearn.lab.chaos.config;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import com.javalearn.lab.chaos.gateway.ContainerGateway;
import com.javalearn.lab.chaos.gateway.DockerContainerGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Wires the Docker Java client. The daemon address comes from DOCKER_HOST
 * (compose sets unix:///var/run/docker.sock); the config builder falls back to
 * the same default when the variable is absent.
 *
 * <p>{@code lab.docker.enabled=false} lets unit tests run without any Docker
 * client construction.</p>
 */
@Configuration(proxyBeanMethods = false)
public class DockerConfig {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "lab.docker.enabled", havingValue = "true", matchIfMissing = true)
    public DockerClient dockerClient() {
        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .maxConnections(16)
                .connectionTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(30))
                .build();
        return DockerClientImpl.getInstance(config, httpClient);
    }

    @Bean
    @ConditionalOnProperty(name = "lab.docker.enabled", havingValue = "true", matchIfMissing = true)
    public ContainerGateway containerGateway(DockerClient dockerClient) {
        return new DockerContainerGateway(dockerClient);
    }
}
