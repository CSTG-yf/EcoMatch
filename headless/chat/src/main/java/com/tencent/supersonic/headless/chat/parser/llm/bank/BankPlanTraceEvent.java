package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.tencent.supersonic.common.util.JsonUtil;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Objects;

/** User-visible, sanitized trace event for one {@code execute_bank_plan} attempt. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BankPlanTraceEvent {

    public static final String PROPERTY_KEY = "bank.nl2sql.trace";

    private int attempt;
    private String traceId;
    private Action action;
    private String actionMessage;
    private PlanSummary planSummary;
    private List<BankPlanToolResult.StageResult> stageResults = List.of();
    private BankPlanToolResult.Stage failedStage;
    private String errorCode;
    private String message;

    public static BankPlanTraceEvent capture(Object planValue, BankPlanToolResult toolResult) {
        Objects.requireNonNull(toolResult, "toolResult");
        Action action = toolResult.getStatus() == BankPlanToolResult.Status.SUCCEEDED
                ? Action.SUCCEEDED
                : Action.FAILED;
        return new BankPlanTraceEvent(toolResult.getAttempt(), toolResult.getTraceId(), action,
                actionMessage(action), summarize(planValue),
                toolResult.getStageResults() == null ? List.of()
                        : List.copyOf(toolResult.getStageResults()),
                toolResult.getFailedStage(), toolResult.getErrorCode(), toolResult.getMessage());
    }

    public void markRepairing() {
        action = Action.REPAIRING;
        actionMessage = actionMessage(action);
    }

    public void markStopped(String reasonCode) {
        action = Action.STOPPED;
        actionMessage = actionMessage(action);
        if (errorCode == null || errorCode.isBlank()) {
            errorCode = reasonCode;
        }
    }

    private static PlanSummary summarize(Object planValue) {
        if (planValue == null) {
            return null;
        }
        BankQueryPlan plan = planValue instanceof BankQueryPlan bankQueryPlan ? bankQueryPlan
                : JsonUtil.toObject(JsonUtil.toString(planValue), BankQueryPlan.class);
        if (plan == null) {
            return null;
        }
        return new PlanSummary(plan.getIntent() == null ? null : plan.getIntent().name(),
                plan.getMetrics() == null ? List.of()
                        : plan.getMetrics().stream().filter(Objects::nonNull)
                                .map(BankQueryPlan.Metric::getBizName).filter(Objects::nonNull)
                                .toList(),
                plan.getOrganizations() == null ? List.of()
                        : plan.getOrganizations().stream().filter(Objects::nonNull)
                                .map(BankQueryPlan.Organization::getCode).filter(Objects::nonNull)
                                .toList(),
                plan.getTime() == null || plan.getTime().getGranularity() == null ? null
                        : plan.getTime().getGranularity().name(),
                plan.getTime() == null || plan.getTime().getComparison() == null ? null
                        : plan.getTime().getComparison().name(),
                plan.getCalculation() == null || plan.getCalculation().getType() == null ? null
                        : plan.getCalculation().getType().name(),
                plan.getOutput() == null || plan.getOutput().getColumns() == null ? List.of()
                        : List.copyOf(plan.getOutput().getColumns()));
    }

    private static String actionMessage(Action action) {
        return switch (action) {
            case REPAIRING -> "工具返回可修正错误，正在重新生成完整计划。";
            case SUCCEEDED -> "计划执行及结果语义检查通过。";
            case STOPPED -> "自动修正已停止，保留最后一次工具结果。";
            case FAILED -> "计划执行未通过。";
        };
    }

    public enum Action {
        REPAIRING, SUCCEEDED, STOPPED, FAILED
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlanSummary {
        private String intent;
        private List<String> metrics = List.of();
        private List<String> organizations = List.of();
        private String timeGranularity;
        private String timeComparison;
        private String calculationType;
        private List<String> outputColumns = List.of();
    }
}
