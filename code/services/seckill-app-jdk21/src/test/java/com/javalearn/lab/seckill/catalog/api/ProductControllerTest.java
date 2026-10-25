package com.javalearn.lab.seckill.catalog.api;

import com.javalearn.lab.common.BizException;
import com.javalearn.lab.common.CommonErrorCode;
import com.javalearn.lab.seckill.catalog.domain.Product;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProductController.class)
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductQueryService productQueryService;

    @Test
    void returnsProductWithSuccessEnvelope() throws Exception {
        given(productQueryService.get(1L)).willReturn(new Product(
                1L, "极客机械键盘", new BigDecimal("299.00"), "ON_SALE", "87 键热插拔轴体"));

        mockMvc.perform(get("/api/products/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.name").value("极客机械键盘"))
                .andExpect(jsonPath("$.data.price").value(299.00))
                .andExpect(jsonPath("$.data.status").value("ON_SALE"))
                .andExpect(jsonPath("$.data.description").value("87 键热插拔轴体"));
    }

    @Test
    void missingProductReturns404WithNotFoundCode() throws Exception {
        given(productQueryService.get(999999L)).willThrow(
                new BizException(CommonErrorCode.NOT_FOUND, "Product not found: 999999"));

        mockMvc.perform(get("/api/products/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("40401"))
                .andExpect(jsonPath("$.message").value("Product not found: 999999"));
    }

    @Test
    void nonNumericIdReturns400WithInvalidArgumentCode() throws Exception {
        mockMvc.perform(get("/api/products/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"));
    }
}
