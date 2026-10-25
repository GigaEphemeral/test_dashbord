package com.javalearn.lab.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResultTest {

    @Test
    void successWithDataCarriesSuccessMetadataAndSameData() {
        Object payload = new Object();

        Result<Object> result = Result.success(payload);

        assertEquals("0", result.getCode());
        assertEquals("OK", result.getMessage());
        assertSame(payload, result.getData());
        assertTrue(result.isSuccess());
    }

    @Test
    void successWithoutDataHasNullDataAndIsSuccessful() {
        Result<Void> result = Result.success();

        assertEquals("0", result.getCode());
        assertEquals("OK", result.getMessage());
        assertNull(result.getData());
        assertTrue(result.isSuccess());
    }

    @Test
    void failureCarriesErrorCodeMetadataAndNoData() {
        Result<String> result = Result.failure(CommonErrorCode.NOT_FOUND);

        assertEquals("40401", result.getCode());
        assertEquals("Resource not found", result.getMessage());
        assertNull(result.getData());
        assertFalse(result.isSuccess());
    }

    @Test
    void failureWithCustomMessageUsesCustomMessage() {
        Result<Void> result = Result.failure(CommonErrorCode.INVALID_ARGUMENT, "name is required");

        assertEquals("40001", result.getCode());
        assertEquals("name is required", result.getMessage());
        assertNull(result.getData());
        assertFalse(result.isSuccess());
    }
}
