package com.javalearn.lab.common;

public enum CommonErrorCode implements ErrorCode {

    SUCCESS("0", "OK"),
    INVALID_ARGUMENT("40001", "Invalid argument"),
    UNAUTHORIZED("40101", "Unauthorized"),
    NOT_FOUND("40401", "Resource not found"),
    CONFLICT("40901", "Conflict"),
    RATE_LIMITED("42901", "Too many requests"),
    INTERNAL_ERROR("50001", "Internal error"),
    SERVICE_UNAVAILABLE("50301", "Service unavailable");

    private final String code;
    private final String message;

    CommonErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
