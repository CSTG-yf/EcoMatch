package com.tencent.supersonic.headless.chat.parser.llm.bank;

/** Signals a model response that cannot safely become a constrained bank query plan. */
public class BankQueryPlanParseException extends IllegalArgumentException {

    private final Reason reason;

    public BankQueryPlanParseException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public BankQueryPlanParseException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }

    public enum Reason {
        MALFORMED_JSON, SCHEMA_VIOLATION, VALIDATION_FAILED, MODEL_FAILURE
    }
}
