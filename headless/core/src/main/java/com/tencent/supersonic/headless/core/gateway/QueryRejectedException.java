package com.tencent.supersonic.headless.core.gateway;

public class QueryRejectedException extends RuntimeException {

    public QueryRejectedException(String message) {
        super(message);
    }

    public QueryRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
