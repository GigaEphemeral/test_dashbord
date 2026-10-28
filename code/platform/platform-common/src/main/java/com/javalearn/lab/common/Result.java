package com.javalearn.lab.common;

public final class Result<T> {

    private final String code;
    private final String message;
    private final T data;

    private Result(String code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> Result<T> success(T data) {
        return new Result<T>(CommonErrorCode.SUCCESS.code(), CommonErrorCode.SUCCESS.message(), data);
    }

    public static Result<Void> success() {
        return success(null);
    }

    public static <T> Result<T> failure(ErrorCode errorCode) {
        return new Result<T>(errorCode.code(), errorCode.message(), null);
    }

    public static <T> Result<T> failure(ErrorCode errorCode, String message) {
        return new Result<T>(errorCode.code(), message, null);
    }

    /**
     * Failure envelope that still carries evidence. B1-04 uses it for 409/40903:
     * the pre-check report (with {@code blocking[]}) is the {@code data} of the
     * refusal, so the console does not need a second request to explain it.
     */
    public static <T> Result<T> failure(ErrorCode errorCode, String message, T data) {
        return new Result<T>(errorCode.code(), message, data);
    }

    public boolean isSuccess() {
        return CommonErrorCode.SUCCESS.code().equals(code);
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }
}
