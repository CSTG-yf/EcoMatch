package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.tencent.supersonic.common.util.JsonUtil;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Sanitized result contract for one {@code execute_bank_plan} attempt.
 *
 * <p>The contract intentionally has no raw SQL, gold answer, throwable, prompt, or stack-trace
 * field. Callers can return precise stage/error metadata to the model without leaking evaluation
 * assets or executable statements.
 */
@Getter
@Setter
@NoArgsConstructor
public class BankPlanToolResult {

    public static final String PROPERTY_KEY = "bank.nl2sql.toolResult";
    public static final String PLAN_PROPERTY_KEY = "bank.nl2sql.plan";
    public static final int MAX_RESULT_PREVIEW_ROWS = 5;

    private static final Pattern ERROR_CODE_PATTERN = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");

    private int attempt;
    private Status status;
    private Stage failedStage;
    private String errorCode;
    private String message;
    private String previousPlanFingerprint;
    private List<StageResult> stageResults = new ArrayList<>();
    private Map<String, List<String>> allowedValues = Map.of();
    private List<String> correctionHints = List.of();
    private CompiledQuerySummary compiledQuerySummary;
    private List<String> resultSchema = List.of();
    private List<Map<String, Object>> resultPreview = List.of();
    private String traceId;

    public static BankPlanToolResult started(int attempt, String traceId,
            String previousPlanFingerprint, String route, List<String> outputColumns) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be positive");
        }
        BankPlanToolResult result = new BankPlanToolResult();
        result.attempt = attempt;
        result.traceId = requireText(traceId, "traceId");
        result.previousPlanFingerprint = previousPlanFingerprint;
        result.status = Status.IN_PROGRESS;
        result.compiledQuerySummary = new CompiledQuerySummary(requireText(route, "route"),
                immutableStrings(outputColumns));
        result.succeed(Stage.PLAN_SCHEMA);
        result.succeed(Stage.PLAN_SEMANTIC);
        result.succeed(Stage.COMPILE);
        return result;
    }

    public static BankPlanToolResult failed(int attempt, String traceId,
            String previousPlanFingerprint, Stage stage, String errorCode,
            Map<String, List<String>> allowedValues, List<String> correctionHints) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be positive");
        }
        BankPlanToolResult result = new BankPlanToolResult();
        result.attempt = attempt;
        result.traceId = requireText(traceId, "traceId");
        result.previousPlanFingerprint = previousPlanFingerprint;
        result.status = Status.IN_PROGRESS;
        return result.fail(stage, errorCode, allowedValues, correctionHints);
    }

    public static BankPlanToolResult from(Object value) {
        if (value instanceof BankPlanToolResult result) {
            return result;
        }
        if (value == null) {
            return null;
        }
        try {
            return JsonUtil.toObject(JsonUtil.toString(value), BankPlanToolResult.class);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    public BankPlanToolResult succeed(Stage stage) {
        Objects.requireNonNull(stage, "stage");
        if (status == Status.FAILED) {
            throw new IllegalStateException("a failed tool result cannot advance");
        }
        boolean alreadySucceeded = stageResults.stream().anyMatch(result -> result.stage == stage
                && result.status == StageStatus.SUCCEEDED);
        if (!alreadySucceeded) {
            stageResults.add(new StageResult(stage, StageStatus.SUCCEEDED, null,
                    successMessage(stage)));
        }
        return this;
    }

    public BankPlanToolResult fail(Stage stage, String code,
            Map<String, List<String>> allowedValues, List<String> correctionHints) {
        Objects.requireNonNull(stage, "stage");
        String safeCode = requireText(code, "errorCode");
        if (!ERROR_CODE_PATTERN.matcher(safeCode).matches()) {
            throw new IllegalArgumentException("errorCode must be an uppercase stable code");
        }
        this.status = Status.FAILED;
        this.failedStage = stage;
        this.errorCode = safeCode;
        this.message = failureMessage(stage);
        this.allowedValues = immutableAllowedValues(allowedValues);
        this.correctionHints = immutableStrings(correctionHints);
        this.resultSchema = List.of();
        this.resultPreview = List.of();
        this.stageResults.removeIf(result -> result.stage == stage);
        this.stageResults.add(new StageResult(stage, StageStatus.FAILED, safeCode, message));
        return this;
    }

    public BankPlanToolResult complete(List<String> columns, List<Map<String, Object>> rows) {
        if (status == Status.FAILED) {
            throw new IllegalStateException("a failed tool result cannot complete");
        }
        succeed(Stage.RESULT_SEMANTIC);
        this.status = Status.SUCCEEDED;
        this.failedStage = null;
        this.errorCode = null;
        this.message = "银行查询计划执行成功。";
        this.resultSchema = immutableStrings(columns);
        this.resultPreview = boundedRows(rows);
        return this;
    }

    /** Returns only the allowlisted correction context consumed by the next model attempt. */
    public String toRepairFeedback() {
        Map<String, Object> feedback = new LinkedHashMap<>();
        feedback.put("attempt", attempt);
        feedback.put("status", status);
        feedback.put("failedStage", failedStage);
        feedback.put("errorCode", errorCode);
        feedback.put("message", message);
        feedback.put("previousPlanFingerprint", previousPlanFingerprint);
        feedback.put("stageResults", List.copyOf(stageResults));
        feedback.put("allowedValues", allowedValues);
        feedback.put("correctionHints", correctionHints);
        feedback.put("traceId", traceId);
        return JsonUtil.toString(feedback);
    }

    private static List<Map<String, Object>> boundedRows(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> preview = new ArrayList<>();
        for (Map<String, Object> row : rows.stream().limit(MAX_RESULT_PREVIEW_ROWS).toList()) {
            if (row == null) {
                continue;
            }
            preview.add(Collections.unmodifiableMap(new LinkedHashMap<>(row)));
        }
        return List.copyOf(preview);
    }

    private static Map<String, List<String>> immutableAllowedValues(
            Map<String, List<String>> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> copy.put(requireText(key, "allowedValues key"),
                immutableStrings(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static List<String> immutableStrings(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream().map(value -> requireText(value, "list value")).toList();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String successMessage(Stage stage) {
        return switch (stage) {
            case PLAN_SCHEMA -> "计划结构校验通过。";
            case PLAN_SEMANTIC -> "计划语义校验通过。";
            case COMPILE -> "查询编译通过。";
            case SQL_SAFETY -> "SQL 安全检查通过。";
            case DATABASE_PREPARE -> "数据库预检查通过。";
            case DATABASE_EXECUTE -> "数据库执行通过。";
            case RESULT_SEMANTIC -> "结果语义检查通过。";
        };
    }

    private static String failureMessage(Stage stage) {
        return switch (stage) {
            case PLAN_SCHEMA -> "计划结构不符合约束，请修正完整计划。";
            case PLAN_SEMANTIC -> "计划语义不符合业务能力，请根据允许值修正完整计划。";
            case COMPILE -> "查询编译失败，请修正不受支持的计划组合。";
            case SQL_SAFETY -> "SQL 安全检查失败，请修正计划而不是直接生成 SQL。";
            case DATABASE_PREPARE -> "数据库预检查失败，请根据错误码修正完整计划。";
            case DATABASE_EXECUTE -> "数据库执行失败，请根据允许值修正完整计划。";
            case RESULT_SEMANTIC -> "结果形状不符合计划契约，请修正完整计划。";
        };
    }

    public enum Status {
        IN_PROGRESS, SUCCEEDED, FAILED
    }

    public enum Stage {
        PLAN_SCHEMA, PLAN_SEMANTIC, COMPILE, SQL_SAFETY, DATABASE_PREPARE, DATABASE_EXECUTE,
        RESULT_SEMANTIC
    }

    public enum StageStatus {
        SUCCEEDED, FAILED
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class StageResult {
        private Stage stage;
        private StageStatus status;
        private String errorCode;
        private String message;

        private StageResult(Stage stage, StageStatus status, String errorCode, String message) {
            this.stage = stage;
            this.status = status;
            this.errorCode = errorCode;
            this.message = message;
        }
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class CompiledQuerySummary {
        private String route;
        private List<String> outputColumns;

        private CompiledQuerySummary(String route, List<String> outputColumns) {
            this.route = route;
            this.outputColumns = outputColumns;
        }
    }
}
