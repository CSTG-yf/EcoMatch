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
    void shouldRejectCodeFencedJsonPlanInsteadOfSilentlyAcceptingMarkdown() {
        BankQueryPlanParseException exception = assertThrows(BankQueryPlanParseException.class,
                () -> parser.parse("```json\n" + validPlanJson() + "\n```", hints()));

        assertEquals(BankQueryPlanParseException.Reason.SCHEMA_VIOLATION, exception.getReason());
    }

    @Test
    void shouldRejectSqlLikeHelperFieldsInsteadOfSilentlyDroppingThem() {
        String output = validPlanJson().replace("\n}",
                ",\n  \"sql\": \"SELECT * FROM bank_daily_metrics\"\n}");

        BankQueryPlanParseException exception = assertThrows(BankQueryPlanParseException.class,
                () -> parser.parse(output, hints()));

        assertEquals(BankQueryPlanParseException.Reason.SCHEMA_VIOLATION, exception.getReason());
    }

    @Test
    void shouldRejectMetricCodeThatIsNotAnExactCatalogIdentifier() {
        String output = validPlanJson().replace("\"ZB001\"", "\"zb001\"");

        BankQueryPlanParseException exception = assertThrows(BankQueryPlanParseException.class,
                () -> parser.parse(output, hints()));

        assertEquals(BankQueryPlanParseException.Reason.VALIDATION_FAILED, exception.getReason());
    }

    @Test
    void shouldRejectChineseDisplayNamesInsteadOfNormalizingThemIntoSemanticIdentifiers() {
        String output = validPlanJson().replace("bank_organization", "机构");

        BankQueryPlanParseException exception = assertThrows(BankQueryPlanParseException.class,
                () -> parser.parse(output, hints()));

        assertEquals(BankQueryPlanParseException.Reason.VALIDATION_FAILED, exception.getReason());
    }

    @Test
    void shouldRejectTruncatedJsonWithoutTryingToRecoverBusinessMeaning() {
        BankQueryPlanParseException exception = assertThrows(BankQueryPlanParseException.class,
                () -> parser.parse("{\"version\":\"1.0\",\"intent\":\"RANKING\",\"metrics\":[",
                        hints()));

        assertEquals(BankQueryPlanParseException.Reason.MALFORMED_JSON, exception.getReason());
    }

    @Test
    void shouldExplainThatTextualNullIsNotAValidDateForRepair() {
        String output =
                validPlanJson().replace("\"startDate\": \"2026-03-31\"", "\"startDate\": \"null\"");

        BankQueryPlanParseException exception = assertThrows(BankQueryPlanParseException.class,
                () -> parser.parse(output, hints()));

        assertEquals(BankQueryPlanParseException.Reason.MALFORMED_JSON, exception.getReason());
        assertEquals(
                "time.startDate must be an ISO-8601 date or JSON null, not the string \"null\"",
                exception.getMessage());
    }

    @Test
    void shouldRejectPlanThatDropsTheRecognizedOrganization() {
        String output = validPlanJson().replace("  \"organizations\": [{\"code\": \"ORG004\"}],\n",
                "  \"organizations\": [],\n");

        BankQueryPlanParseException exception = assertThrows(BankQueryPlanParseException.class,
                () -> parser.parse(output, hints()));

        assertEquals(BankQueryPlanParseException.Reason.VALIDATION_FAILED, exception.getReason());
    }

    @Test
    void shouldAcceptCanonicalPointQueryPlanWithNullAliasAndNullOrganizationBizName() {
        BankQueryPlan plan = parser.parse(pointPlanJson(), pointHints());

        assertEquals(BankIntentType.POINT_QUERY, plan.getIntent());
        assertEquals(Set.of("ZB001"), Set.of(plan.getMetrics().get(0).getBizName()));
        assertEquals(null, plan.getMetrics().get(0).getAlias());
        assertEquals("ORG004", plan.getOrganizations().get(0).getCode());
        assertEquals(null, plan.getOrganizations().get(0).getBizName());
    }

    @Test
    void shouldRejectNonNullNonStringAliasInsteadOfCoercingIt() {
        String output = pointPlanJson().replace("\"alias\": null", "\"alias\": []");

        BankQueryPlanParseException exception = assertThrows(BankQueryPlanParseException.class,
                () -> parser.parse(output, pointHints()));

        assertEquals(BankQueryPlanParseException.Reason.MALFORMED_JSON, exception.getReason());
    }

    @Test
    void shouldRejectNonNullNonStringOrganizationBizNameInsteadOfCoercingIt() {
        String output = pointPlanJson().replace("\"bizName\": null", "\"bizName\": {\"x\": \"y\"}");

        BankQueryPlanParseException exception = assertThrows(BankQueryPlanParseException.class,
                () -> parser.parse(output, pointHints()));

        assertEquals(BankQueryPlanParseException.Reason.MALFORMED_JSON, exception.getReason());
    }

    private SemanticIntentHints hints() {
        return SemanticIntentHints.builder().expectedIntent(BankIntentType.RANKING)
                .allowedMetrics(Set.of("ZB001"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date"))
                .requiredMetrics(Set.of("ZB001")).requiredOrganizationCodes(Set.of("ORG004"))
                .requiredStartDate(LocalDate.of(2026, 3, 31))
                .requiredEndDate(LocalDate.of(2026, 3, 31)).requiredLimit(3).maxLimit(100).build();
    }

    private SemanticIntentHints pointHints() {
        return SemanticIntentHints.builder().expectedIntent(BankIntentType.POINT_QUERY)
                .allowedMetrics(Set.of("ZB001"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date"))
                .requiredMetrics(Set.of("ZB001")).requiredOrganizationCodes(Set.of("ORG004"))
                .requiredStartDate(LocalDate.of(2026, 3, 31))
                .requiredEndDate(LocalDate.of(2026, 3, 31)).maxLimit(100).build();
    }

    private String validPlanJson() {
        return """
                {
                  "version": "1.0",
                  "action": "EXECUTE",
                  "intent": "RANKING",
                  "metrics": [{"bizName": "ZB001", "aggregation": "DEFAULT"}],
                  "dimensions": ["bank_organization"],
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
                  "output": {"columns": ["bank_organization", "ZB001"], "orderSensitive": true}
                }
                """;
    }

    private String pointPlanJson() {
        return """
                {
                  "version": "1.0",
                  "action": "EXECUTE",
                  "intent": "POINT_QUERY",
                  "metrics": [{"bizName": "ZB001", "aggregation": "DEFAULT", "alias": null}],
                  "derivedMetrics": [],
                  "dimensions": [],
                  "organizations": [{"code": "ORG004", "bizName": null}],
                  "time": {
                    "startDate": "2026-03-31",
                    "endDate": "2026-03-31",
                    "granularity": "DAY",
                    "comparison": "NONE",
                    "baselineStartDate": null,
                    "baselineEndDate": null
                  },
                  "filters": [],
                  "calculation": {"type": "DIRECT", "baseline": null},
                  "orderBy": [],
                  "limit": null,
                  "output": {"columns": ["ZB001"], "orderSensitive": false, "aggregationMode": null}
                }
                """;
    }
}
