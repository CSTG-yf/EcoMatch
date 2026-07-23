package com.tencent.supersonic.headless.core.gateway;

public class SqlPolicyViolationException extends RuntimeException {

    public SqlPolicyViolationException(String message) {
        super(message);
    }

    public SqlPolicyViolationException(String message, Throwable cause) {
        super(message, cause);
    }
}
