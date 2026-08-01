package com.tencent.supersonic.headless.server.integration;

public class IntegrationException extends RuntimeException {

    private final IntegrationErrorCode code;
    private final boolean retryable;

    public IntegrationException(IntegrationErrorCode code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public IntegrationException(IntegrationErrorCode code, String message, boolean retryable,
            Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
    }

    public IntegrationErrorCode getCode() {
        return code;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
