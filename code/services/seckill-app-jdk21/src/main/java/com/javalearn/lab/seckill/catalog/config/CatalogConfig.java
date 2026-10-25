package com.javalearn.lab.seckill.catalog.config;

import com.javalearn.lab.seckill.catalog.api.ProductQueryService;
import com.javalearn.lab.seckill.catalog.app.DefaultProductQueryService;
import com.javalearn.lab.seckill.catalog.domain.ProductRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CatalogConfig {

    @Bean
    public ProductQueryService productQueryService(ProductRepository productRepository) {
        return new DefaultProductQueryService(productRepository);
    }
}
