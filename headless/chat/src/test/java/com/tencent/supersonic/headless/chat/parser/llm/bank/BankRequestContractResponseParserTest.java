package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.tencent.supersonic.headless.chat.intent.BankIntentType;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.SemanticIntentHints;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankRequestContractResponseParserTest {

    private final BankRequestContractResponseParser parser =
            new BankRequestContractResponseParser();

    @Test
    void acceptsExactModelRequirementsAndBuildsPlanValidationHints() {
        BankRequestContract contract = parser.parse(executeContractJson(), admissionHints());

        SemanticIntentHints planHints = contract.toPlanHints(admissionHints());

        assertEquals(BankIntentType.COMPARISON, planHints.getExpectedIntent());
        assertEquals(Set.of("ZB001", "ZB002"), planHints.getRequiredMetrics());
        assertEquals(Set.of("ORG004"), planHints.getRequiredOrganizationCodes());
        assertEquals(LocalDate.of(2025, 7, 31), planHints.getRequiredStartDate());
        assertEquals(LocalDate.of(2025, 7, 31), planHints.getRequiredEndDate());
        assertEquals(BankQueryPlan.TimeComparison.NONE, planHints.getRequiredTimeComparison());
        assertEquals(null, planHints.getRequiredBaselineStartDate());
        assertEquals(null, planHints.getRequiredBaselineEndDate());
    }

    @Test
    void rejectsLowercaseUnknownAndDuplicateMetricCodesInsteadOfCanonicalizingThem() {
        assertThrows(BankQueryPlanParseException.class,
                () -> parser.parse(executeContractJson().replace("\"ZB001\",\"ZB002\"",
                        "\"zb001\",\"ZB001\",\"ZB001\""), admissionHints()));
    }

    @Test
    void redirectsCanonicalAdditiveCodesOverAmountOperandsToThePlainMetricContract() {
        // 金额类合计（如两项亿元指标的“之和”）没有派生指标可写：反馈必须给可满足的
        // 重定向（删派生、并列加数），而不是复述模型无法满足的百分率要求。
        BankQueryPlanParseException exception = assertThrows(BankQueryPlanParseException.class,
                () -> parser.parse(executeContractJson().replace("\"derivedMetrics\":[]",
                                "\"derivedMetrics\":[{\"metricCode\":\"DERIVED_SUM_ZB007_AND_ZB008\","
                                        + "\"numerator\":\"ZB007\",\"denominator\":\"ZB008\"}]"),
                        admissionHints()));

        assertTrue(exception.getMessage().contains("plain multi-metric queries"));
        assertTrue(exception.getMessage().contains("remove this derivedMetrics entry"));
    }

    @Test
    void stillDemandsTheCanonicalFormForMalformedAdditiveCodes() {
        BankQueryPlanParseException exception = assertThrows(BankQueryPlanParseException.class,
                () -> parser.parse(executeContractJson().replace("\"derivedMetrics\":[]",
                                "\"derivedMetrics\":[{\"metricCode\":\"DERIVED_SUM_ZB014_AND_ZB013\","
                                        + "\"numerator\":\"ZB014\",\"denominator\":\"ZB013\"}]"),
                        admissionHints()));

        assertTrue(exception.getMessage().contains("canonical code"));
    }

    @Test
    void canonicalizesRecognizableProvinceAverageComparisonSlots() {
        String contract = executeContractJson().replace("\"field\":\"benchmark\"",
                "\"field\":\"metric_value\"");

        BankRequestContract parsed = parser.parse(contract, admissionHints());

        assertEquals(1, parsed.getFilters().size());
        assertEquals("benchmark", parsed.getFilters().get(0).getField());
        assertEquals("COMPARE", parsed.getFilters().get(0).getOperator());
        assertEquals("PROVINCE_AVERAGE", parsed.getFilters().get(0).getValue());
        assertTrue(parsed.getFilters().get(0).getValues().isEmpty());
    }

    @Test
    void canonicalizesProvinceAverageBenchmarkWithWrongOperatorOrExtraValues() {
        String wrongOperator = executeContractJson().replace("\"operator\":\"COMPARE\"",
                "\"operator\":\"EQ\"");
        String extraValues = executeContractJson().replace("\"values\":[]",
                "\"values\":[\"PROVINCE_AVERAGE\"]");

        for (String contract : List.of(wrongOperator, extraValues)) {
            BankRequestContract parsed = parser.parse(contract, admissionHints());
            assertEquals(1, parsed.getFilters().size());
            assertEquals("COMPARE", parsed.getFilters().get(0).getOperator());
            assertTrue(parsed.getFilters().get(0).getValues().isEmpty());
        }
    }

    @Test
    void addsTheBenchmarkFilterWhenOnlyAProvinceAverageDirectionIsRecognized() {
        String contract = executeContractJson().replace(
                "\"filters\":[{\"field\":\"benchmark\",\"operator\":\"COMPARE\","
                        + "\"value\":\"PROVINCE_AVERAGE\",\"values\":[]}]",
                "\"filters\":[{\"field\":\"metric_value\",\"operator\":\"GT\","
                        + "\"value\":\"PROVINCE_AVERAGE\",\"values\":[]}]");

        BankRequestContract parsed = parser.parse(contract, admissionHints());

        assertEquals(2, parsed.getFilters().size());
        assertEquals("metric_value", parsed.getFilters().get(0).getField());
        assertEquals("benchmark", parsed.getFilters().get(1).getField());
        assertEquals("COMPARE", parsed.getFilters().get(1).getOperator());
    }

    @Test
    void stillRejectsProvinceAverageOutsideComparableSlots() {
        String contract = executeContractJson().replace("\"field\":\"benchmark\"",
                "\"field\":\"organization\"").replace("\"operator\":\"COMPARE\"",
                        "\"operator\":\"EQ\"");

        assertThrows(BankQueryPlanParseException.class,
                () -> parser.parse(contract, admissionHints()));
    }

    @Test
    void rejectsAComparisonThatOverlapsItsBaselineBeforePlanGeneration() {
        String contract = executeContractJson().replace(
                "\"comparison\":\"NONE\",\"baselineStartDate\":null,\"baselineEndDate\":null",
                "\"comparison\":\"PERIOD_OVER_PERIOD\",\"baselineStartDate\":\"2025-07-31\","
                        + "\"baselineEndDate\":\"2025-07-31\"");

        BankQueryPlanParseException exception = assertThrows(BankQueryPlanParseException.class,
                () -> parser.parse(contract, admissionHints()));

        assertEquals(BankQueryPlanParseException.Reason.VALIDATION_FAILED, exception.getReason());
        assertTrue(exception.getMessage().contains("baselineEndDate < startDate"));
    }

    @Test
    void preservesThePublishedMomAndYoyContractWithoutAnExplicitBaselineRange() {
        String contract = executeContractJson().replace("\"intent\":\"COMPARISON\"",
                "\"intent\":\"CHANGE\"").replace("\"comparison\":\"NONE\"",
                        "\"comparison\":\"MOM_AND_YOY\"");

        assertEquals(BankRequestContract.Action.EXECUTE,
                parser.parse(contract, admissionHints()).getAction());
    }

    @Test
    void rejectsCurrentYearFirstDayAsTheStartOfYearBaseline() {
        String contract = executeContractJson().replace(
                "\"comparison\":\"NONE\",\"baselineStartDate\":null,\"baselineEndDate\":null",
                "\"comparison\":\"START_OF_YEAR\",\"baselineStartDate\":\"2025-01-01\","
                        + "\"baselineEndDate\":\"2025-01-01\"");

        BankQueryPlanParseException exception = assertThrows(BankQueryPlanParseException.class,
                () -> parser.parse(contract, admissionHints()));

        assertEquals(BankQueryPlanParseException.Reason.VALIDATION_FAILED, exception.getReason());
        assertTrue(exception.getMessage().contains("prior calendar year end"));
    }

    @Test
    void acceptsPriorYearEndAsTheStartOfYearBaseline() {
        String contract = executeContractJson().replace("\"intent\":\"COMPARISON\"",
                "\"intent\":\"CHANGE\"").replace(
                "\"comparison\":\"NONE\",\"baselineStartDate\":null,\"baselineEndDate\":null",
                "\"comparison\":\"START_OF_YEAR\",\"baselineStartDate\":\"2024-12-31\","
                        + "\"baselineEndDate\":\"2024-12-31\"");

        assertEquals(BankRequestContract.Action.EXECUTE,
                parser.parse(contract, admissionHints()).getAction());
    }

    @Test
    void carriesTheModelOwnedComparisonAndBaselineIntoPlanHints() {
        String contractJson = executeContractJson().replace("\"intent\":\"COMPARISON\"",
                "\"intent\":\"CHANGE\"").replace(
                "\"comparison\":\"NONE\",\"baselineStartDate\":null,\"baselineEndDate\":null",
                "\"comparison\":\"START_OF_YEAR\",\"baselineStartDate\":\"2024-12-31\","
                        + "\"baselineEndDate\":\"2024-12-31\"");

        SemanticIntentHints planHints = parser.parse(contractJson, admissionHints())
                .toPlanHints(admissionHints());

        assertEquals(BankQueryPlan.TimeComparison.START_OF_YEAR,
                planHints.getRequiredTimeComparison());
        assertEquals(LocalDate.of(2024, 12, 31), planHints.getRequiredBaselineStartDate());
        assertEquals(LocalDate.of(2024, 12, 31), planHints.getRequiredBaselineEndDate());
    }

    @Test
    void rejectsAComparisonWhenTheModelKeepsANonChangeIntent() {
        String contract = executeContractJson().replace(
                "\"comparison\":\"NONE\",\"baselineStartDate\":null,\"baselineEndDate\":null",
                "\"comparison\":\"PERIOD_OVER_PERIOD\",\"baselineStartDate\":\"2024-12-31\","
                        + "\"baselineEndDate\":\"2024-12-31\"");

        BankQueryPlanParseException exception = assertThrows(BankQueryPlanParseException.class,
                () -> parser.parse(contract, admissionHints()));

        assertEquals(BankQueryPlanParseException.Reason.VALIDATION_FAILED, exception.getReason());
        assertTrue(exception.getMessage().contains("intent=CHANGE"));
    }

    @Test
    void reportsUnsupportedAnswerFactTypesAsRepairableValidationErrors() {
        String contract = executeContractJson().replace(
                "\"VALUE\",\"PROVINCE_AVERAGE\",\"GAP_VALUE\"",
                "\"VALUE\",\"MINIMUM_VALUE\"");

        BankQueryPlanParseException exception = assertThrows(BankQueryPlanParseException.class,
                () -> parser.parse(contract, admissionHints()));

        assertEquals(BankQueryPlanParseException.Reason.VALIDATION_FAILED, exception.getReason());
        assertTrue(exception.getMessage().contains("MINIMUM_VALUE"));
        assertTrue(exception.getMessage().contains("VALUE"));
    }

    @Test
    void rejectsADerivedMetricCodeOutsideThePublishedRegistry() {
        String contract = executeContractJson().replace("\"derivedMetrics\":[]",
                "\"derivedMetrics\":[{\"metricCode\":\"DERIVED_UNKNOWN\","
                        + "\"numerator\":\"ZB002\",\"denominator\":\"ZB001\","
                        + "\"name\":\"存贷比\"}]");

        assertThrows(BankQueryPlanParseException.class,
                () -> parser.parse(contract, admissionHints()));
    }

    @Test
    void rejectsCodeFencesSoTheRepairLoopAlwaysReceivesOneRawJsonObject() {
        BankQueryPlanParseException exception = assertThrows(BankQueryPlanParseException.class,
                () -> parser.parse("```json\n" + executeContractJson() + "\n```", admissionHints()));

        assertEquals(BankQueryPlanParseException.Reason.SCHEMA_VIOLATION, exception.getReason());
    }

    @Test
    void requiresAConcreteClarificationWhenModelCannotResolveTheQuestion() {
        assertThrows(BankQueryPlanParseException.class,
                () -> parser.parse("""
                        {"version":"1.0","action":"CLARIFY","intent":null,
                        "metricCodes":[],"derivedMetrics":[],"organizationCodes":[],"time":null,
                        "filters":[],"requiredLimit":null,"answerFactTypes":[],"clarification":""}
                        """, admissionHints()));
    }

    private SemanticIntentHints admissionHints() {
        return SemanticIntentHints.builder().expectedIntent(BankIntentType.UNKNOWN)
                .allowedMetrics(Set.of("ZB001", "ZB002"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date")).build();
    }

    private String executeContractJson() {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"COMPARISON",
                "metricCodes":["ZB001","ZB002"],"derivedMetrics":[],
                "organizationCodes":["ORG004"],
                "time":{"startDate":"2025-07-31","endDate":"2025-07-31","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},
                "filters":[{"field":"benchmark","operator":"COMPARE","value":"PROVINCE_AVERAGE","values":[]}],
                "requiredLimit":null,"answerFactTypes":["VALUE","PROVINCE_AVERAGE","GAP_VALUE"],"clarification":null}
                """;
    }
}
