package com.javalearn.lab.seckill.jdk8.catalog;

import com.javalearn.lab.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Same path and same Result envelope as the JDK 21 shell's ProductController.
 */
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductReadService productReadService;

    public ProductController(ProductReadService productReadService) {
        this.productReadService = productReadService;
    }

    @GetMapping("/{id}")
    public Result<ProductResponse> getById(@PathVariable long id) {
        return Result.success(ProductResponse.from(productReadService.get(id)));
    }
}
