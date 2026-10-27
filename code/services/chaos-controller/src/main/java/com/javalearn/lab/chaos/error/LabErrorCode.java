package com.javalearn.lab.chaos.error;

import com.javalearn.lab.common.ErrorCode;

public enum LabErrorCode implements ErrorCode {

    FORBIDDEN("40301", "Forbidden"),
    METHOD_NOT_ALLOWED("40501", "Method not allowed"),
    BAD_GATEWAY("50201", "Bad gateway");

    private final String code;
    private final String message;

    LabErrorCode(String code, String message) {
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
