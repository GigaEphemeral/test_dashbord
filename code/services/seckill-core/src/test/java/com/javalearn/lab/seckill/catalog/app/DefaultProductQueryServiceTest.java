package com.javalearn.lab.seckill.catalog.app;

import com.javalearn.lab.common.BizException;
import com.javalearn.lab.common.CommonErrorCode;
import com.javalearn.lab.seckill.catalog.domain.Product;
import com.javalearn.lab.seckill.catalog.domain.ProductRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pure unit tests for the catalog query use case: no Spring, no database.
 */
class DefaultProductQueryServiceTest {

    private static final Product KEYBOARD = new Product(
            1L, "Keyboard", new BigDecimal("299.00"), "ON_SALE", "Mechanical keyboard");

    @Test
    void returnsProductProvidedByRepository() {
        DefaultProductQueryService service = new DefaultProductQueryService(id -> Optional.of(KEYBOARD));

        assertSame(KEYBOARD, service.get(1L));
    }

    @Test
    void missingProductThrowsBizExceptionWithNotFoundCode() {
        DefaultProductQueryService service = new DefaultProductQueryService(id -> Optional.empty());

        BizException exception = assertThrows(BizException.class, () -> service.get(999999L));

        assertEquals(CommonErrorCode.NOT_FOUND, exception.getErrorCode());
        assertEquals("Product not found: 999999", exception.getMessage());
    }

    @Test
    void passesExactIdToRepository() {
        AtomicLong seenId = new AtomicLong(-1L);
        ProductRepository repository = id -> {
            seenId.set(id);
            return Optional.of(KEYBOARD);
        };

        new DefaultProductQueryService(repository).get(42L);

        assertEquals(42L, seenId.get());
    }
}
