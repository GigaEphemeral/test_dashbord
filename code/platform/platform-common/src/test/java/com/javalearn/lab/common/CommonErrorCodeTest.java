package com.javalearn.lab.common;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommonErrorCodeTest {

    @Test
    void allCodesAreUnique() {
        Set<String> codes = new HashSet<String>();

        for (CommonErrorCode errorCode : CommonErrorCode.values()) {
            assertTrue(codes.add(errorCode.code()), "duplicate code: " + errorCode.code());
        }
    }

    @Test
    void allMessagesAreNonBlank() {
        for (CommonErrorCode errorCode : CommonErrorCode.values()) {
            assertNotNull(errorCode.message(), "null message for: " + errorCode);
            assertFalse(errorCode.message().trim().isEmpty(), "blank message for: " + errorCode);
        }
    }

    @Test
    void successUsesZeroCode() {
        assertEquals("0", CommonErrorCode.SUCCESS.code());
        assertEquals("OK", CommonErrorCode.SUCCESS.message());
    }
}
