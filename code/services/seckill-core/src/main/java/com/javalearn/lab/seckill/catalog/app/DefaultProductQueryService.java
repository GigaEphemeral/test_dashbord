package com.javalearn.lab.seckill.catalog.app;

import com.javalearn.lab.common.BizException;
import com.javalearn.lab.common.CommonErrorCode;
import com.javalearn.lab.seckill.catalog.api.ProductQueryService;
import com.javalearn.lab.seckill.catalog.domain.Product;
import com.javalearn.lab.seckill.catalog.domain.ProductRepository;

import java.util.Objects;

public final class DefaultProductQueryService implements ProductQueryService {

    private final ProductRepository productRepository;

    public DefaultProductQueryService(ProductRepository productRepository) {
        this.productRepository = Objects.requireNonNull(productRepository, "productRepository");
    }

    @Override
    public Product get(long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new BizException(
                        CommonErrorCode.NOT_FOUND, "Product not found: " + id));
    }
}
