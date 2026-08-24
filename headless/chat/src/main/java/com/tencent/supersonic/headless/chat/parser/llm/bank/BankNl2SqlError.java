package com.tencent.supersonic.headless.chat.parser.llm.bank;

import lombok.Getter;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sanitized error envelope for the constrained bank route. It deliberately contains no model
 * response, SQL, result row, or prompt text, so retry decisions cannot leak execution data.
 */
@Getter
public class BankNl2SqlError extends RuntimeException {

    private static final String PARSER_ERROR_PREFIX = "[BANK_CONSTRAINED_PLAN]";
    private static final Pattern STABLE_CODE = Pattern.compile("^([a-z][a-z0-9_]{2,63}):");
    private static final Pattern HTTP_STATUS = Pattern.compile(
            "(?i)(?:status|http)\\s*(?:code)?\\s*[=:]?\\s*(\\d{3})");

    private final Stage stage;
    private final Category category;
    private final boolean retryable;
    private final String stableRepairCode;
    private final ProviderFailureClass providerFailureClass;

    private BankNl2SqlError(Stage stage, Category category, boolean retryable, String message,
            Throwable cause, String stableRepairCode, ProviderFailureClass providerFailureClass) {
        super(message, cause);
        this.stage = stage;
        this.category = category;
        this.retryable = retryable;
        this.stableRepairCode = stableRepairCode;
        this.providerFailureClass = providerFailureClass;
    }

    public static BankNl2SqlError afterSingleRepair(BankQueryPlanParseException cause) {
        return afterPlanRepair(cause);
    }

    public static BankNl2SqlError afterRequirementsRepair(BankQueryPlanParseException cause) {
        return invalidContract(Stage.REQUIREMENTS, cause);
    }

    public static BankNl2SqlError afterPlanRepair(BankQueryPlanParseException cause) {
        return invalidContract(Stage.PLAN, cause);
    }

    private static BankNl2SqlError invalidContract(Stage stage, BankQueryPlanParseException cause) {
        return new BankNl2SqlError(stage, category(cause), false,
                "bank query plan remained invalid after one structured repair", cause,
                stableRepairCode(cause), ProviderFailureClass.NONE);
    }

    public static BankNl2SqlError modelFailure(Throwable cause) {
        return modelFailure(Stage.PLAN, cause);
    }

    public static BankNl2SqlError modelFailure(Stage stage, Throwable cause) {
        ProviderFailureClass providerFailure = ProviderFailureClass.classify(cause);
        return new BankNl2SqlError(stage, Category.MODEL_FAILURE, providerFailure.retryable(),
                "bank query plan model generation failed", cause, providerFailure.stableCode(),
                providerFailure);
    }

    /** The model could not resolve an essential ambiguity from the catalog and user turn. */
    public static BankNl2SqlError clarificationRequired(String clarification) {
        String message = clarification == null || clarification.isBlank()
                ? "请补充机构、指标或时间范围。" : clarification.strip();
        return new BankNl2SqlError(Stage.PLAN, Category.CLARIFICATION_REQUIRED, false, message,
                null, "clarification_required", ProviderFailureClass.NONE);
    }

    public static BankNl2SqlError compilationFailure(Throwable cause) {
        String detail = cause == null || cause.getMessage() == null ? "bank query plan compilation failed"
                : "bank query plan compilation failed: " + cause.getMessage();
        return new BankNl2SqlError(Stage.COMPILATION, Category.COMPILATION_FAILURE, false, detail,
                cause, "compilation_failed", ProviderFailureClass.NONE);
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
                "bank constrained plan produced no candidate: " + detail, null, "no_candidate",
                ProviderFailureClass.NONE);
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
            case CLARIFICATION_REQUIRED -> getMessage();
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

    private static String stableRepairCode(BankQueryPlanParseException cause) {
        if (cause != null && cause.getMessage() != null) {
            Matcher matcher = STABLE_CODE.matcher(cause.getMessage());
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        if (cause == null || cause.getReason() == null) {
            return "validation_failed";
        }
        return cause.getReason().name().toLowerCase(Locale.ROOT);
    }

    public enum Stage {
        REQUIREMENTS, PLAN, COMPILATION
    }

    public enum Category {
        MALFORMED_JSON, SCHEMA_VIOLATION, VALIDATION_FAILED, MODEL_FAILURE, COMPILATION_FAILURE,
        CLARIFICATION_REQUIRED
    }

    /** Typed, non-sensitive provider outcome used only for bounded retry and evaluator telemetry. */
    public enum ProviderFailureClass {
        NONE("none", false),
        TIMEOUT("model_timeout", true),
        HTTP_RATE_LIMIT("model_http_429", true),
        HTTP_5XX("model_http_5xx", true),
        TRANSPORT("model_transport", true),
        EMPTY_RESPONSE("model_empty_response", true),
        NON_RETRYABLE("model_failure", false);

        private final String stableCode;
        private final boolean retryable;

        ProviderFailureClass(String stableCode, boolean retryable) {
            this.stableCode = stableCode;
            this.retryable = retryable;
        }

        public String stableCode() {
            return stableCode;
        }

        public boolean retryable() {
            return retryable;
        }

        static ProviderFailureClass classify(Throwable failure) {
            for (Throwable current = failure; current != null; current = current.getCause()) {
                if (current instanceof HttpTimeoutException || current instanceof TimeoutException) {
                    return TIMEOUT;
                }
                String message = current.getMessage();
                String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
                Matcher status = HTTP_STATUS.matcher(normalized);
                if (status.find()) {
                    int code = Integer.parseInt(status.group(1));
                    if (code == 429) {
                        return HTTP_RATE_LIMIT;
                    }
                    if (code >= 500 && code <= 599) {
                        return HTTP_5XX;
                    }
                }
                if (normalized.contains("timeout") || normalized.contains("timed out")) {
                    return TIMEOUT;
                }
                if (normalized.contains("response missing content")
                        || normalized.contains("empty response")) {
                    return EMPTY_RESPONSE;
                }
                if (current instanceof IOException || normalized.contains("connection reset")
                        || normalized.contains("connection refused")
                        || normalized.contains("broken pipe") || normalized.contains("eof")) {
                    return TRANSPORT;
                }
            }
            return NON_RETRYABLE;
        }
    }
}
