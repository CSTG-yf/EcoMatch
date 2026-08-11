package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.tencent.supersonic.headless.chat.intent.BankIntentType;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.SemanticIntentHints;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    }

    @Test
    void rejectsLowercaseUnknownAndDuplicateMetricCodesInsteadOfCanonicalizingThem() {
        assertThrows(BankQueryPlanParseException.class,
                () -> parser.parse(executeContractJson().replace("\"ZB001\",\"ZB002\"",
                        "\"zb001\",\"ZB001\",\"ZB001\""), admissionHints()));
    }

    @Test
    void rejectsProvinceAverageOutsideTheExactBenchmarkContract() {
        assertThrows(BankQueryPlanParseException.class,
                () -> parser.parse(executeContractJson().replace("\"field\":\"benchmark\"",
                        "\"field\":\"metric_value\""), admissionHints()));
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
