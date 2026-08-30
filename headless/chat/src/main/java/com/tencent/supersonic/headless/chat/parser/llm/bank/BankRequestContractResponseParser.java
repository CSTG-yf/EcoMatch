package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.tencent.supersonic.headless.chat.intent.BankIntentType;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.SemanticIntentHints;
import org.apache.commons.lang3.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Strict parser and whitelist validator for the model-owned request contract. */
public class BankRequestContractResponseParser {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public BankRequestContract parse(String modelOutput, SemanticIntentHints admission) {
        String json = unwrapJson(modelOutput);
        try {
            JsonNode node = OBJECT_MAPPER.readTree(json);
            if (node == null || !node.isObject()) {
                throw failure(BankQueryPlanParseException.Reason.SCHEMA_VIOLATION,
                        "model requirements response must contain one JSON object");
            }
            BankRequestContract contract = OBJECT_MAPPER.treeToValue(node, BankRequestContract.class);
            // Recognizable province-average slots are canonicalized before validation; the
            // repair loop shows the model cannot reliably produce the exact filter shape.
            contract.setFilters(
                    BankProvinceAverageFilterNormalizer.normalize(contract.getFilters()));
            List<String> errors = validate(contract, admission);
            if (!errors.isEmpty()) {
                throw failure(BankQueryPlanParseException.Reason.VALIDATION_FAILED,
                        String.join("; ", errors));
            }
            return contract;
        } catch (BankQueryPlanParseException exception) {
            throw exception;
        } catch (UnrecognizedPropertyException exception) {
            throw failure(BankQueryPlanParseException.Reason.SCHEMA_VIOLATION,
                    "model requirements response contains an unsupported property", exception);
        } catch (InvalidFormatException exception) {
            if (isUnsupportedAnswerFactType(exception)) {
                throw failure(BankQueryPlanParseException.Reason.VALIDATION_FAILED,
                        "answerFactTypes only accepts VALUE, TREND_DIRECTION, "
                                + "COMPARISON_VALUE, PROVINCE_AVERAGE, GAP_VALUE, RANK, "
                                + "CHANGE_VALUE, CHANGE_RATE, RATIO_VALUE, or COUNT. "
                                + "Highest/lowest values and dates use VALUE; do not use "
                                + "MINIMUM_VALUE or MAXIMUM_VALUE",
                        exception);
            }
            throw failure(BankQueryPlanParseException.Reason.MALFORMED_JSON,
                    "model requirements response is not complete strict JSON", exception);
        } catch (JsonProcessingException exception) {
            throw failure(BankQueryPlanParseException.Reason.MALFORMED_JSON,
                    "model requirements response is not complete strict JSON", exception);
        } catch (IllegalArgumentException exception) {
            throw failure(BankQueryPlanParseException.Reason.SCHEMA_VIOLATION,
                    exception.getMessage(), exception);
        }
    }

    private List<String> validate(BankRequestContract contract, SemanticIntentHints admission) {
        List<String> errors = new ArrayList<>();
        if (contract == null) {
            return List.of("requirements contract is required");
        }
        if (!BankRequestContract.CURRENT_VERSION.equals(contract.getVersion())) {
            errors.add("version must be \"" + BankRequestContract.CURRENT_VERSION + "\"");
        }
        if (contract.getAction() == null) {
            errors.add("action is required");
            return errors;
        }
        if (contract.getAction() == BankRequestContract.Action.CLARIFY) {
            if (StringUtils.isBlank(contract.getClarification())) {
                errors.add("clarification is required when action is CLARIFY");
            }
            return errors;
        }
        if (contract.getIntent() == null || contract.getIntent() == BankIntentType.UNKNOWN) {
            errors.add("intent must be one supported execution intent");
        }
        validateExactCodes("metricCodes", contract.getMetricCodes(), BankSemanticRegistry.metricCodes(),
                admission == null ? Set.of() : admission.getAllowedMetrics(), true, errors);
        validateExactCodes("organizationCodes", contract.getOrganizationCodes(),
                BankSemanticRegistry.organizationCodes(), Set.of(), false, errors);
        validateTime(contract.getTime(), errors);
        validateComparisonIntent(contract, errors);
        validateFilters(contract.getFilters(), errors);
        validateAnswerFacts(contract.getAnswerFactTypes(), errors);
        if (contract.getRequiredLimit() != null && (contract.getRequiredLimit() < 1
                || contract.getRequiredLimit() > (admission == null ? SemanticIntentHints.DEFAULT_MAX_LIMIT
                        : admission.getMaxLimit()))) {
            errors.add("requiredLimit must be within the configured maximum");
        }
        validateDerivedMetrics(contract.getDerivedMetrics(), errors);
        if (StringUtils.isNotBlank(contract.getClarification())) {
            errors.add("clarification must be null when action is EXECUTE");
        }
        return errors;
    }

    private void validateExactCodes(String field, Collection<String> values, Set<String> registry,
            Set<String> admissionAllowList, boolean required, List<String> errors) {
        if (values == null || values.isEmpty()) {
            if (required) {
                errors.add(field + " must contain at least one exact registry code");
            }
            return;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String value : values) {
            if (StringUtils.isBlank(value) || !registry.contains(value)
                    || !admissionAllowList.isEmpty() && !admissionAllowList.contains(value)) {
                errors.add(field + " contains an unknown or non-exact code: " + value);
            }
            if (!seen.add(value)) {
                errors.add(field + " contains a duplicate code: " + value);
            }
        }
    }

    private void validateTime(BankQueryPlan.TimeRange time, List<String> errors) {
        if (time == null || time.getStartDate() == null || time.getEndDate() == null
                || time.getGranularity() == null || time.getComparison() == null) {
            errors.add("time must include startDate, endDate, granularity and comparison");
            return;
        }
        if (time.getEndDate().isBefore(time.getStartDate())) {
            errors.add("time.endDate must not be before time.startDate");
        }
        if (time.getComparison() != BankQueryPlan.TimeComparison.NONE
                && time.getComparison() != BankQueryPlan.TimeComparison.MOM_AND_YOY) {
            if (time.getBaselineStartDate() == null || time.getBaselineEndDate() == null) {
                errors.add("comparison requires baselineStartDate and baselineEndDate");
            } else if (time.getBaselineStartDate().isAfter(time.getBaselineEndDate())
                    || !time.getBaselineEndDate().isBefore(time.getStartDate())) {
                errors.add("comparison baseline must be a complete range earlier than the current "
                        + "range: baselineStartDate <= baselineEndDate < startDate. For a point "
                        + "comparison, set startDate=endDate to the current point and "
                        + "baselineStartDate=baselineEndDate to the earlier point");
            }
        }
        if (time.getComparison() == BankQueryPlan.TimeComparison.START_OF_YEAR
                && time.getBaselineStartDate() != null && time.getBaselineEndDate() != null) {
            LocalDate priorYearEnd = LocalDate.of(time.getEndDate().getYear() - 1, 12, 31);
            if (!priorYearEnd.equals(time.getBaselineStartDate())
                    || !priorYearEnd.equals(time.getBaselineEndDate())) {
                errors.add("START_OF_YEAR means compare to the prior calendar year end: "
                        + "baselineStartDate and baselineEndDate must both be the prior "
                        + "year's 12-31, not current-year 01-01");
            }
        }
    }

    private void validateComparisonIntent(BankRequestContract contract, List<String> errors) {
        BankQueryPlan.TimeRange time = contract.getTime();
        if (time != null && time.getComparison() != null
                && time.getComparison() != BankQueryPlan.TimeComparison.NONE
                && contract.getIntent() != BankIntentType.CHANGE) {
            errors.add("a non-NONE time comparison requires intent=CHANGE");
        }
    }

    private void validateAnswerFacts(List<BankRequestContract.AnswerFactType> facts,
            List<String> errors) {
        if (facts == null || facts.isEmpty()) {
            errors.add("answerFactTypes must contain at least one required result fact");
            return;
        }
        if (new LinkedHashSet<>(facts).size() != facts.size()) {
            errors.add("answerFactTypes must not contain duplicates");
        }
    }

    private boolean isUnsupportedAnswerFactType(InvalidFormatException exception) {
        return exception.getPath().stream()
                .anyMatch(reference -> "answerFactTypes".equals(reference.getFieldName()));
    }

    private void validateFilters(List<BankQueryPlan.Filter> filters, List<String> errors) {
        if (filters == null) {
            return;
        }
        boolean hasProvinceAverageBenchmark = filters.stream()
                .anyMatch(this::isProvinceAverageBenchmark);
        for (BankQueryPlan.Filter filter : filters) {
            if (filter == null || StringUtils.isBlank(filter.getField())
                    || StringUtils.isBlank(filter.getOperator())
                    || !BankSemanticRegistry.filterFields().contains(filter.getField())
                    || !BankSemanticRegistry.filterOperators().contains(filter.getOperator())) {
                errors.add("filters must use an exact registry field and operator");
                continue;
            }
            if (StringUtils.isBlank(filter.getValue())
                    && (filter.getValues() == null || filter.getValues().isEmpty())) {
                errors.add("filters must contain a value or values");
            }
            boolean provinceAverageBenchmark = isProvinceAverageBenchmark(filter);
            boolean provinceAverageDirection = isProvinceAverageDirection(filter);
            boolean metricBenchmarkCondition =
                    BankQueryPlanValidator.isMetricBenchmarkCondition(filter);
            if (("benchmark".equals(filter.getField()) || "COMPARE".equals(filter.getOperator()))
                    && !provinceAverageBenchmark) {
                errors.add("province average must use exact filter "
                        + "{\"field\":\"benchmark\",\"operator\":\"COMPARE\","
                        + "\"value\":\"PROVINCE_AVERAGE\",\"values\":[]}");
            }
            if ("PROVINCE_AVERAGE".equals(filter.getValue()) && !provinceAverageBenchmark
                    && !provinceAverageDirection && !metricBenchmarkCondition) {
                errors.add("PROVINCE_AVERAGE may only be a benchmark filter, a metric_value "
                        + "direction object, or a per-metric benchmark condition "
                        + "{\"field\":\"<ZB###>\",\"operator\":\"GT|GTE|LT|LTE\","
                        + "\"value\":\"PROVINCE_AVERAGE\",\"values\":[]}");
            }
            if ((provinceAverageDirection || metricBenchmarkCondition)
                    && !hasProvinceAverageBenchmark) {
                errors.add("province-average direction requires the exact benchmark filter");
            }
            if ((provinceAverageBenchmark || provinceAverageDirection || metricBenchmarkCondition)
                    && (filter.getValues() == null || !filter.getValues().isEmpty())) {
                errors.add("province-average filters values must be exactly []");
            }
        }
    }

    private boolean isProvinceAverageBenchmark(BankQueryPlan.Filter filter) {
        return filter != null && "benchmark".equals(filter.getField())
                && "COMPARE".equals(filter.getOperator())
                && "PROVINCE_AVERAGE".equals(filter.getValue());
    }

    private boolean isProvinceAverageDirection(BankQueryPlan.Filter filter) {
        return filter != null && "metric_value".equals(filter.getField())
                && ("GT".equals(filter.getOperator()) || "GTE".equals(filter.getOperator())
                        || "LT".equals(filter.getOperator()) || "LTE".equals(filter.getOperator()))
                && "PROVINCE_AVERAGE".equals(filter.getValue());
    }

    private void validateDerivedMetrics(List<BankQueryPlan.DerivedMetric> derivedMetrics,
            List<String> errors) {
        if (derivedMetrics == null) {
            return;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (BankQueryPlan.DerivedMetric metric : derivedMetrics) {
            if (metric == null || StringUtils.isBlank(metric.getMetricCode())) {
                errors.add("derivedMetrics must declare one exact published code with distinct "
                        + "registry numerator and denominator");
                continue;
            }
            if (BankSemanticRegistry.isAdditiveDerivedMetricCode(metric.getMetricCode())) {
                validateAdditiveDerivedMetric(metric, errors);
            } else if (!BankSemanticRegistry.derivedMetricCodes().contains(metric.getMetricCode())
                    || !BankSemanticRegistry.metricCodes().contains(metric.getNumerator())
                    || !BankSemanticRegistry.metricCodes().contains(metric.getDenominator())
                    || metric.getNumerator().equals(metric.getDenominator())
                    || !metric.getMetricCode().equals("DERIVED_" + metric.getNumerator()
                            + "_DIV_" + metric.getDenominator())) {
                errors.add("derivedMetrics must declare one exact published code with distinct "
                        + "registry numerator and denominator");
                continue;
            }
            if (!seen.add(metric.getMetricCode())) {
                errors.add("derivedMetrics contains a duplicate metricCode: " + metric.getMetricCode());
            }
        }
    }

    /**
     * Requirements-stage whitelist for the additive composite derived metric
     * {@code DERIVED_SUM_<M1>_AND_<M2>}: the code must be the canonical (lexicographically
     * ordered) form of two distinct registered percent-unit catalog metrics, and the item must
     * repeat them as numerator (smaller code) and denominator (larger code) so the plan stage
     * sees one coherent operand pair. A canonical code over amount-unit operands is rejected
     * with a satisfiable redirect (drop the derived metric, list the operands plainly) —
     * restating the percent rule there would leave repair without an accepting fixed point.
     */
    private void validateAdditiveDerivedMetric(BankQueryPlan.DerivedMetric metric,
            List<String> errors) {
        String code = metric.getMetricCode();
        String numerator = metric.getNumerator();
        String denominator = metric.getDenominator();
        if (StringUtils.isBlank(numerator) || StringUtils.isBlank(denominator)
                || !BankSemanticRegistry.metricCodes().contains(numerator)
                || !BankSemanticRegistry.metricCodes().contains(denominator)
                || numerator.equals(denominator)
                || !BankSemanticRegistry.additiveDerivedMetricCode(numerator,
                        denominator).equals(code)) {
            errors.add("additive derivedMetrics must declare the canonical code "
                    + "DERIVED_SUM_<M1>_AND_<M2> of two distinct percent-unit (%) catalog "
                    + "metrics with numerator=M1 and denominator=M2: " + code);
            return;
        }
        if (!BankSemanticRegistry.isPercentUnitMetric(numerator)
                || !BankSemanticRegistry.isPercentUnitMetric(denominator)) {
            errors.add("DERIVED_SUM derived metrics are only for percent-unit (%) operand "
                    + "pairs; sums of amount-unit metrics stay plain multi-metric queries: "
                    + "remove this derivedMetrics entry and list the operand metrics "
                    + "directly in metrics and metricCodes: " + code);
        }
    }

    private BankQueryPlanParseException failure(BankQueryPlanParseException.Reason reason,
            String message) {
        return new BankQueryPlanParseException(reason, message);
    }

    private BankQueryPlanParseException failure(BankQueryPlanParseException.Reason reason,
            String message, Throwable cause) {
        return new BankQueryPlanParseException(reason, message, cause);
    }

    private String unwrapJson(String modelOutput) {
        String response = StringUtils.trimToEmpty(modelOutput);
        if (response.startsWith("```")) {
            throw failure(BankQueryPlanParseException.Reason.SCHEMA_VIOLATION,
                    "model requirements response must be one raw JSON object without a code fence");
        }
        if (StringUtils.isBlank(response)) {
            throw failure(BankQueryPlanParseException.Reason.MALFORMED_JSON,
                    "model requirements response is empty");
        }
        return response;
    }
}
