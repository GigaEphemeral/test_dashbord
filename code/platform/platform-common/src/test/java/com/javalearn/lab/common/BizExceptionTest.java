package com.javalearn.lab.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class BizExceptionTest {

    @Test
    void carriesErrorCodeAndDefaultMessage() {
        BizException exception = new BizException(CommonErrorCode.NOT_FOUND);

        assertSame(CommonErrorCode.NOT_FOUND, exception.getErrorCode());
        assertEquals("Resource not found", exception.getMessage());
    }

    @Test
    void customMessageOverridesDefault() {
        BizException exception = new BizException(CommonErrorCode.NOT_FOUND, "order 42 not found");

        assertSame(CommonErrorCode.NOT_FOUND, exception.getErrorCode());
        assertEquals("order 42 not found", exception.getMessage());
    }

    @Test
    void causeIsPreserved() {
        IllegalStateException cause = new IllegalStateException("boom");

        BizException exception = new BizException(CommonErrorCode.INTERNAL_ERROR, "wrapped", cause);

        assertSame(CommonErrorCode.INTERNAL_ERROR, exception.getErrorCode());
        assertEquals("wrapped", exception.getMessage());
        assertSame(cause, exception.getCause());
    }
}
