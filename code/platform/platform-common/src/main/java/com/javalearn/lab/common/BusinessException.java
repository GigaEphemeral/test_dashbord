package com.javalearn.lab.common;

/**
 * A {@link BizException} that represents an <em>expected business outcome</em>
 * of a call (for example "sold out" or "lock timeout"), not a backend fault.
 *
 * <p>The distinction matters for the resilience layer: a circuit breaker may
 * ignore {@code BusinessException} while it must still count an infrastructure
 * fault that the application wrapped as a plain {@code BizException} (such as
 * the {@code 50301} Redis/RabbitMQ failures) as a failure. Because the
 * ignore-list check is {@code configuredClass.isAssignableFrom(thrownClass)},
 * ignoring this subclass does NOT swallow its parent {@code BizException};
 * only {@code BusinessException} instances and future subclasses of it are
 * treated as expected outcomes.</p>
 *
 * <p>Framework-neutral Java 8 type, as required for platform-common.</p>
 */
public class BusinessException extends BizException {

    private static final long serialVersionUID = 1L;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode);
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
