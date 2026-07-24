package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.tencent.supersonic.headless.chat.intent.BankIntentType;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.SemanticIntentHints;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BankQueryPlanResponseParserTest {

    private final BankQueryPlanResponseParser parser = new BankQueryPlanResponseParser();

    @Test
    void shouldParseCodeFencedJsonPlanAndValidateItAgainstSemanticHints() {
        BankQueryPlan plan = parser.parse("```json\n" + validPlanJson() + "\n```", hints());

        assertEquals(BankIntentType.RANKING, plan.getIntent());
        assertEquals("ZB001", plan.getMetrics().get(0).getBizName());
        assertEquals(3, plan.getLimit());
    }

    @Test
    void shouldRejectUnknownPropertyInsteadOfAcceptingSqlLikeModelOutput() {
        String output = validPlanJson().replace("\n}",
                ",\n  \"sql\": \"SELECT * FROM bank_daily_metrics\"\n}");

        BankQueryPlanParseException exception = assertThrows(BankQueryPlanParseException.class,
                () -> parser.parse(output, hints()));

        assertEquals(BankQueryPlanParseException.Reason.SCHEMA_VIOLATION, exception.getReason());
    }

    @Test
    void shouldRejectTruncatedJsonWithoutTryingToRecoverBusinessMeaning() {
        BankQueryPlanParseException exception = assertThrows(BankQueryPlanParseException.class,
                () -> parser.parse("{\"version\":\"1.0\",\"intent\":\"RANKING\",\"metrics\":[",
                        hints()));

        assertEquals(BankQueryPlanParseException.Reason.MALFORMED_JSON, exception.getReason());
    }

    @Test
    void shouldRejectPlanThatDropsTheRecognizedOrganization() {
        String output = validPlanJson().replace("  \"organizations\": [{\"code\": \"ORG004\"}],\n",
                "  \"organizations\": [],\n");

        BankQueryPlanParseException exception = assertThrows(BankQueryPlanParseException.class,
                () -> parser.parse(output, hints()));

        assertEquals(BankQueryPlanParseException.Reason.VALIDATION_FAILED, exception.getReason());
    }

    private SemanticIntentHints hints() {
        return SemanticIntentHints.builder().expectedIntent(BankIntentType.RANKING)
                .allowedMetrics(Set.of("ZB001")).allowedDimensions(Set.of("机构", "数据日期"))
                .requiredMetrics(Set.of("ZB001")).requiredOrganizationCodes(Set.of("ORG004"))
                .requiredStartDate(LocalDate.of(2026, 3, 31))
                .requiredEndDate(LocalDate.of(2026, 3, 31)).requiredLimit(3).maxLimit(100).build();
    }

    private String validPlanJson() {
        return """
                {
                  "version": "1.0",
                  "intent": "RANKING",
                  "metrics": [{"bizName": "ZB001", "aggregation": "DEFAULT"}],
                  "dimensions": ["机构"],
                  "organizations": [{"code": "ORG004"}],
                  "time": {
                    "startDate": "2026-03-31",
                    "endDate": "2026-03-31",
                    "granularity": "DAY",
                    "comparison": "NONE"
                  },
                  "filters": [],
                  "calculation": {"type": "DIRECT"},
                  "orderBy": [{"field": "ZB001", "direction": "DESC"}],
                  "limit": 3,
                  "output": {"columns": ["机构", "ZB001"], "orderSensitive": true}
                }
                """;
    }
}
