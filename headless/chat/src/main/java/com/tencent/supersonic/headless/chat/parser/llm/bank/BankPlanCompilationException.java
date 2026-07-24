package com.tencent.supersonic.headless.chat.parser.llm.bank;

/** A deterministic compiler error that is safe to surface to candidate ranking and retry logic. */
public class BankPlanCompilationException extends RuntimeException {

    private final Reason reason;

    public BankPlanCompilationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }

    public enum Reason {
        INVALID_PLAN,
        CLARIFICATION_REQUIRED,
        SCHEMA_REQUIRED,
        DATASET_REQUIRED,
        METRIC_UNAVAILABLE,
        DIMENSION_UNAVAILABLE,
        ORGANIZATION_DIMENSION_UNAVAILABLE,
        TIME_DIMENSION_UNAVAILABLE,
        OUTPUT_ORDER_MISMATCH,
        ORDER_FIELD_NOT_SELECTED,
        UNSUPPORTED_FILTER,
        UNSUPPORTED_CALCULATION,
        S2SQL_RENDER_FAILED
    }
}
