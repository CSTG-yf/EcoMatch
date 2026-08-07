package com.tencent.supersonic.headless.chat.parser.llm.bank;

import lombok.Getter;

/**
 * Sanitized error envelope for the constrained bank route. It deliberately contains no model
 * response, SQL, result row, or prompt text, so retry decisions cannot leak execution data.
 */
@Getter
public class BankNl2SqlError extends RuntimeException {

    private static final String PARSER_ERROR_PREFIX = "[BANK_CONSTRAINED_PLAN]";

    private final Stage stage;
    private final Category category;
    private final boolean retryable;

    private BankNl2SqlError(Stage stage, Category category, boolean retryable, String message,
            Throwable cause) {
        super(message, cause);
        this.stage = stage;
        this.category = category;
        this.retryable = retryable;
    }

    public static BankNl2SqlError afterSingleRepair(BankQueryPlanParseException cause) {
        return new BankNl2SqlError(Stage.PLAN, category(cause), false,
                "bank query plan remained invalid after one structured repair", cause);
    }

    public static BankNl2SqlError modelFailure(Throwable cause) {
        return new BankNl2SqlError(Stage.PLAN, Category.MODEL_FAILURE, false,
                "bank query plan model generation failed", cause);
    }

    public static BankNl2SqlError compilationFailure(Throwable cause) {
        String detail = cause == null || cause.getMessage() == null ? "bank query plan compilation failed"
                : "bank query plan compilation failed: " + cause.getMessage();
        return new BankNl2SqlError(Stage.COMPILATION, Category.COMPILATION_FAILURE, false, detail,
                cause);
    }

    /**
     * Terminal failure when the bank route produced no executable candidate. Prevents unconstrained
     * free-SQL parsers from winning the same parse request after a bank miss.
     */
    public static BankNl2SqlError noCandidate(
            com.tencent.supersonic.headless.api.pojo.response.ParseResp.BankCandidateRejectionState rejectionState,
            com.tencent.supersonic.headless.api.pojo.response.ParseResp.BankCandidateCompilerReason compilerReason) {
        String detail = rejectionState == null ? "NO_CANDIDATE" : rejectionState.name();
        if (compilerReason != null) {
            detail = detail + "/" + compilerReason.name();
        }
        return new BankNl2SqlError(Stage.PLAN, Category.VALIDATION_FAILED, false,
                "bank constrained plan produced no candidate: " + detail, null);
    }

    public static boolean allowsParserRetry(Throwable error) {
        return error instanceof BankNl2SqlError bankError && bankError.isRetryable();
    }

    /**
     * Produces the internal parser signal for a constrained-plan failure. The prefix is consumed by
     * the chat parser and must never be shown to the user.
     */
    public String toParserErrorMessage() {
        return PARSER_ERROR_PREFIX + toUserMessage();
    }

    public static boolean isTerminalParserError(String errorMsg) {
        return errorMsg != null && errorMsg.startsWith(PARSER_ERROR_PREFIX);
    }

    public static String toUserMessage(String errorMsg) {
        return isTerminalParserError(errorMsg) ? errorMsg.substring(PARSER_ERROR_PREFIX.length())
                : errorMsg;
    }

    private String toUserMessage() {
        return switch (category) {
            case MALFORMED_JSON, SCHEMA_VIOLATION, VALIDATION_FAILED -> "未能可靠识别该银行指标查询，请明确机构、指标和时间范围后重试。";
            case MODEL_FAILURE, COMPILATION_FAILURE -> "银行指标查询服务暂时不可用，请稍后重试。";
        };
    }

    private static Category category(BankQueryPlanParseException cause) {
        return switch (cause.getReason()) {
            case MALFORMED_JSON -> Category.MALFORMED_JSON;
            case SCHEMA_VIOLATION -> Category.SCHEMA_VIOLATION;
            case VALIDATION_FAILED -> Category.VALIDATION_FAILED;
            case MODEL_FAILURE -> Category.MODEL_FAILURE;
        };
    }

    public enum Stage {
        PLAN, COMPILATION
    }

    public enum Category {
        MALFORMED_JSON, SCHEMA_VIOLATION, VALIDATION_FAILED, MODEL_FAILURE, COMPILATION_FAILURE
    }
}
