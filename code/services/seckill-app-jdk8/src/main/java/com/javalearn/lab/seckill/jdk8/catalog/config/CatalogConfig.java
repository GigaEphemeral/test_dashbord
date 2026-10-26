package com.javalearn.lab.seckill.jdk8.catalog.config;

import com.javalearn.lab.seckill.catalog.api.ProductQueryService;
import com.javalearn.lab.seckill.catalog.app.DefaultProductQueryService;
import com.javalearn.lab.seckill.catalog.domain.ProductRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the shared seckill-core use case into this shell, mirroring the
 * JDK 21 shell's CatalogConfig.
 */
@Configuration
public class CatalogConfig {

    @Bean
    public ProductQueryService productQueryService(ProductRepository productRepository) {
        return new DefaultProductQueryService(productRepository);
    }
}
