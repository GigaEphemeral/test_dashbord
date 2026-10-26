package com.javalearn.lab.seckill.jdk8.catalog;

import com.javalearn.lab.seckill.catalog.api.ProductQueryService;
import com.javalearn.lab.seckill.catalog.domain.Product;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Hot read path of the JDK 8 shell: the uncached database read the D7 panel
 * compares against the JDK 21 shell. The JDK 21 cache/lab stages
 * (Caffeine L1 + Redis L2, penetration defence) need Boot 3 and are out of scope
 * here, so this is the direct ProductReadService equivalent.
 */
@Service
public class ProductReadService {

    private final ProductQueryService productQueryService;

    public ProductReadService(ProductQueryService productQueryService) {
        this.productQueryService = Objects.requireNonNull(productQueryService, "productQueryService");
    }

    @Transactional(readOnly = true)
    public Product get(long id) {
        return productQueryService.get(id);
    }
}
