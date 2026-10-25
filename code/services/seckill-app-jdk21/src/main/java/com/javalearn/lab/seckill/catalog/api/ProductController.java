package com.javalearn.lab.seckill.catalog.api;

import com.javalearn.lab.common.Result;
import com.javalearn.lab.seckill.catalog.domain.Product;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductQueryService productQueryService;

    public ProductController(ProductQueryService productQueryService) {
        this.productQueryService = productQueryService;
    }

    @GetMapping("/{id}")
    public Result<ProductResponse> getById(@PathVariable long id) {
        Product product = productQueryService.get(id);
        return Result.success(ProductResponse.from(product));
    }
}
