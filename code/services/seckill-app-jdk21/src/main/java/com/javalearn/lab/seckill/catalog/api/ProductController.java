package com.javalearn.lab.seckill.catalog.api;

import com.javalearn.lab.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductViewService productViewService;

    public ProductController(ProductViewService productViewService) {
        this.productViewService = productViewService;
    }

    @GetMapping("/{id}")
    public Result<ProductResponse> getById(@PathVariable long id) {
        return Result.success(productViewService.get(id));
    }
}
