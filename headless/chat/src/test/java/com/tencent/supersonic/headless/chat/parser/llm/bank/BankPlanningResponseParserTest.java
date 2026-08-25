package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.tencent.supersonic.headless.chat.intent.BankIntentType;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.SemanticIntentHints;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BankPlanningResponseParserTest {

    private final BankPlanningResponseParser parser = new BankPlanningResponseParser();

    @Test
    void parsesOneCompleteRequirementsAndPlanResponse() {
        BankPlanningResponse response = parser.parse(executeResponse(), admissionHints());

        assertEquals(BankRequestContract.Action.EXECUTE,
                response.getRequirements().getAction());
        assertEquals(BankIntentType.POINT_QUERY, response.getPlan().getIntent());
    }

    @Test
    void rejectsAResponseWithoutBothEnvelopeFields() {
        BankQueryPlanParseException error = assertThrows(BankQueryPlanParseException.class,
                () -> parser.parse("{\"requirements\":" + requirementsJson() + "}",
                        admissionHints()));

        assertEquals(BankQueryPlanParseException.Reason.SCHEMA_VIOLATION, error.getReason());
    }

    @Test
    void rejectsNullPlanForExecutableRequirements() {
        BankQueryPlanParseException error = assertThrows(BankQueryPlanParseException.class,
                () -> parser.parse("{\"requirements\":" + requirementsJson()
                        + ",\"plan\":null}", admissionHints()));

        assertEquals(BankQueryPlanParseException.Reason.SCHEMA_VIOLATION, error.getReason());
    }

    @Test
    void acceptsNullPlanForAValidClarification() {
        BankPlanningResponse response = parser.parse("{\"requirements\":"
                + clarificationJson() + ",\"plan\":null}", admissionHints());

        assertEquals(BankRequestContract.Action.CLARIFY,
                response.getRequirements().getAction());
        assertNull(response.getPlan());
    }

    @Test
    void rejectsAPlanThatDropsADeclaredMetric() {
        String mismatched = executeResponse().replace(
                "{\"bizName\":\"ZB001\",\"aggregation\":\"DEFAULT\",\"alias\":null}",
                "{\"bizName\":\"ZB002\",\"aggregation\":\"DEFAULT\",\"alias\":null}");

        assertThrows(BankQueryPlanParseException.class,
                () -> parser.parse(mismatched, admissionHints()));
    }

    private SemanticIntentHints admissionHints() {
        return SemanticIntentHints.builder().expectedIntent(BankIntentType.UNKNOWN)
                .allowedMetrics(Set.of("ZB001", "ZB002"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date")).build();
    }

    private String executeResponse() {
        return "{\"requirements\":" + requirementsJson() + ",\"plan\":" + planJson() + "}";
    }

    private String requirementsJson() {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"POINT_QUERY",
                "metricCodes":["ZB001"],"derivedMetrics":[],"organizationCodes":["ORG004"],
                "time":{"startDate":"2025-07-31","endDate":"2025-07-31","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},
                "filters":[],"requiredLimit":null,"answerFactTypes":["VALUE"],"clarification":null}
                """.strip();
    }

    private String planJson() {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"POINT_QUERY",
                "metrics":[{"bizName":"ZB001","aggregation":"DEFAULT","alias":null}],
                "derivedMetrics":[],"dimensions":["bank_organization"],
                "organizations":[{"code":"ORG004","bizName":null}],
                "time":{"startDate":"2025-07-31","endDate":"2025-07-31","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},
                "filters":[],"calculation":{"type":"DIRECT","baseline":null},"orderBy":[],"limit":null,
                "output":{"columns":["bank_organization","ZB001"],"orderSensitive":false}}
                """.strip();
    }

    private String clarificationJson() {
        return """
                {"version":"1.0","action":"CLARIFY","intent":"UNKNOWN","metricCodes":[],
                "derivedMetrics":[],"organizationCodes":[],"time":null,"filters":[],
                "requiredLimit":null,"answerFactTypes":[],"clarification":"请明确指标。"}
                """.strip();
    }
}
