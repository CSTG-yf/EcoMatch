package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.headless.chat.intent.BankIntentType;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.SemanticIntentHints;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankQueryPlanJsonCanonicalizerTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final BankQueryPlanResponseParser parser = new BankQueryPlanResponseParser();

    @Test
    void shouldRewriteInventedFieldNamesIntoBankQueryPlan() throws Exception {
        String raw = """
                {
                  "intent": "CHANGE",
                  "metrics": [{"zb_id": "各项存款余额", "aggregation": "SUM"}],
                  "organizations": [{"org_id": "ORG001"}],
                  "time_range": {
                    "start": "2025-03-31",
                    "end": "2025-03-31",
                    "granularity": "DAY"
                  },
                  "additional_analysis": {"foo": true},
                  "output": {
                    "columns": ["balance_change_amount", "deposit_balance"],
                    "orderSensitive": false
                  }
                }
                """;
        var canonical = BankQueryPlanJsonCanonicalizer.canonicalize(mapper.readTree(raw));
        assertEquals("1.0", canonical.get("version").asText());
        assertEquals("ZB001", canonical.get("metrics").get(0).get("bizName").asText());
        assertEquals("ORG001", canonical.get("organizations").get(0).get("code").asText());
        assertTrue(canonical.has("time"));
        assertEquals("2025-03-31", canonical.get("time").get("startDate").asText());
        assertTrue(canonical.path("additional_analysis").isMissingNode());
    }

    @Test
    void shouldParseCanonicalizedChangePlanAgainstHints() {
        SemanticIntentHints hints = SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.CHANGE).allowedMetrics(Set.of("ZB001"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date"))
                .requiredMetrics(Set.of("ZB001")).requiredOrganizationCodes(Set.of("ORG001"))
                .requiredStartDate(LocalDate.of(2024, 12, 31))
                .requiredEndDate(LocalDate.of(2025, 3, 31)).build();

        String raw = """
                {
                  "intent": "CHANGE",
                  "metrics": [{"zb_id": "ZB001", "aggregation": "DEFAULT"}],
                  "dimensions": [],
                  "organizations": [{"org_id": "ORG001"}],
                  "time_range": {
                    "start": "2025-03-31",
                    "end": "2025-03-31",
                    "granularity": "DAY",
                    "comparison": "PERIOD_OVER_PERIOD",
                    "baseline_start": "2024-12-31",
                    "baseline_end": "2024-12-31"
                  },
                  "calculation": {"type": "CHANGE"},
                  "orderBy": [],
                  "limit": null,
                  "output": {"columns": ["ZB001"], "orderSensitive": true},
                  "sql": "SELECT 1"
                }
                """;

        BankQueryPlan plan = parser.parse(raw, hints);
        assertNotNull(plan);
        assertEquals(BankIntentType.CHANGE, plan.getIntent());
        assertEquals("ZB001", plan.getMetrics().get(0).getBizName());
        assertEquals(LocalDate.of(2025, 3, 31), plan.getTime().getStartDate());
        assertEquals(BankQueryPlan.TimeComparison.PERIOD_OVER_PERIOD,
                plan.getTime().getComparison());
    }
}
