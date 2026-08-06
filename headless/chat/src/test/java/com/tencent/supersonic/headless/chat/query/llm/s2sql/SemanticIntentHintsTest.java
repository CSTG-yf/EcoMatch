package com.tencent.supersonic.headless.chat.query.llm.s2sql;

import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.api.pojo.SchemaElementType;
import com.tencent.supersonic.headless.chat.intent.BankIntentResult;
import com.tencent.supersonic.headless.chat.intent.BankIntentType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticIntentHintsTest {

    @Test
    void shouldRecognizeBankEvidenceWhenTheSelectedRuleParseSkippedMapping() {
        LLMReq.LLMSchema schema = new LLMReq.LLMSchema();
        schema.setMetrics(List.of(element("营业支出", "zb010", SchemaElementType.METRIC)));
        schema.setDimensions(List.of(element("数据日期", "数据日期", SchemaElementType.DIMENSION)));

        SemanticIntentHints hints = SemanticIntentHints.fromQuery("2026年1月10日江苏省A市农商行的营业支出是多少",
                null, schema, LocalDate.of(2026, 7, 23));

        assertEquals(BankIntentType.POINT_QUERY, hints.getExpectedIntent());
        assertEquals(List.of("zb010"), hints.getRequiredMetrics().stream().sorted().toList());
        assertEquals(List.of("ORG001"),
                hints.getRequiredOrganizationCodes().stream().sorted().toList());
        assertEquals(LocalDate.of(2026, 1, 10), hints.getRequiredStartDate());
        assertEquals(LocalDate.of(2026, 1, 10), hints.getRequiredEndDate());
        assertTrue(hints.getAllowedMetrics().contains("zb010"));
    }

    @Test
    void shouldSumTopAndBottomRankSlicesForTheRequiredLimit() {
        BankIntentResult intent = new BankIntentResult();
        intent.setIntent(BankIntentType.RANKING);
        intent.setFilters(List.of(
                BankIntentResult.FilterSlot.builder().field("rank").operator("LTE").value("1")
                        .build(),
                BankIntentResult.FilterSlot.builder().field("rank_from_bottom").operator("LTE")
                        .value("1").build()));

        SemanticIntentHints hints = SemanticIntentHints.from(intent, new LLMReq.LLMSchema());

        assertEquals(Integer.valueOf(2), hints.getRequiredLimit());
    }

    private SchemaElement element(String name, String bizName, SchemaElementType type) {
        return SchemaElement.builder().name(name).bizName(bizName).type(type).build();
    }
}
