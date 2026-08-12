package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tencent.supersonic.headless.chat.intent.BankIntentType;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.SemanticIntentHints;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Model-owned statement of what the current user turn asks for.
 *
 * <p>This is deliberately separate from {@link BankQueryPlan}: the first model response declares
 * the user-visible semantic requirements, and the second model response declares how those
 * requirements are executed. Deterministic code only checks the two contracts against each other
 * and against the registry; it never derives a business identifier from question text.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = false)
public class BankRequestContract {

    public static final String CURRENT_VERSION = "1.0";
    public static final String PROPERTY_KEY = "bank.nl2sql.requirements";

    @Builder.Default
    private String version = CURRENT_VERSION;
    private Action action;
    private BankIntentType intent;
    @Builder.Default
    private List<String> metricCodes = new ArrayList<>();
    @Builder.Default
    private List<BankQueryPlan.DerivedMetric> derivedMetrics = new ArrayList<>();
    @Builder.Default
    private List<String> organizationCodes = new ArrayList<>();
    private BankQueryPlan.TimeRange time;
    @Builder.Default
    private List<BankQueryPlan.Filter> filters = new ArrayList<>();
    private Integer requiredLimit;
    @Builder.Default
    private List<AnswerFactType> answerFactTypes = new ArrayList<>();
    private String clarification;

    public enum Action {
        EXECUTE, CLARIFY
    }

    /** Facts which the final-answer model must support with returned result rows. */
    public enum AnswerFactType {
        VALUE, TREND_DIRECTION, COMPARISON_VALUE, PROVINCE_AVERAGE, GAP_VALUE, RANK,
        CHANGE_VALUE, CHANGE_RATE, RATIO_VALUE, COUNT
    }

    /**
     * Converts model-owned requirements into the deterministic admission context used by the plan
     * parser. The provided admission context contains only live schema capabilities.
     */
    public SemanticIntentHints toPlanHints(SemanticIntentHints admission) {
        if (action != Action.EXECUTE) {
            throw new IllegalStateException("only an executable request contract can produce a plan");
        }
        return SemanticIntentHints.builder().expectedIntent(intent)
                .allowedMetrics(admission == null ? null : admission.getAllowedMetrics())
                .allowedDimensions(admission == null ? null : admission.getAllowedDimensions())
                .requiredMetrics(new LinkedHashSet<>(metricCodes))
                .requiredOrganizationCodes(new LinkedHashSet<>(organizationCodes))
                .requiredDerivedMetrics(derivedMetrics.stream().map(metric ->
                        new SemanticIntentHints.DerivedMetricSpec(metric.getMetricCode(),
                                metric.getNumerator(), metric.getDenominator(), metric.getName()))
                        .toList())
                .requiredStartDate(time.getStartDate()).requiredEndDate(time.getEndDate())
                .requiredFilters(filters.stream().map(filter ->
                        new SemanticIntentHints.RequiredFilter(filter.getField(),
                                filter.getOperator(), filter.getValue())).toList())
                .requiredLimit(requiredLimit)
                .maxLimit(admission == null ? SemanticIntentHints.DEFAULT_MAX_LIMIT
                        : admission.getMaxLimit())
                .build();
    }
}
