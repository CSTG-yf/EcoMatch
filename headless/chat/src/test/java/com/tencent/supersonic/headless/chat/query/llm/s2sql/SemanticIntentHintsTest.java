package com.tencent.supersonic.headless.chat.query.llm.s2sql;

import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.api.pojo.SchemaElementType;
import com.tencent.supersonic.headless.chat.intent.BankFinancialIntentRecognizer;
import com.tencent.supersonic.headless.chat.intent.BankIntentResult;
import com.tencent.supersonic.headless.chat.intent.BankIntentType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void shouldPreferCompleteQuestionEvidenceOverAnExistingPartialMapperResult() {
        LLMReq.LLMSchema schema = new LLMReq.LLMSchema();
        schema.setMetrics(List.of(element("个人贷款余额", "ZB005", SchemaElementType.METRIC),
                element("对公贷款余额", "ZB006", SchemaElementType.METRIC),
                element("各项贷款余额", "ZB002", SchemaElementType.METRIC)));
        BankIntentResult staleMapperResult = new BankFinancialIntentRecognizer()
                .recognize("2026年1月1日江苏省A市农商行的各项贷款余额是多少", LocalDate.of(2026, 7, 23));

        SemanticIntentHints hints =
                SemanticIntentHints.fromQuery("2026年3月末，江苏省G市农商行的个人贷款和对公贷款分别占各项贷款的比例？",
                        staleMapperResult, schema, LocalDate.of(2026, 7, 23));

        assertEquals(BankIntentType.RATIO, hints.getExpectedIntent());
        assertEquals(List.of("ZB002", "ZB005", "ZB006"),
                hints.getRequiredMetrics().stream().sorted().toList());
        assertEquals(List.of("ORG007"), hints.getRequiredOrganizationCodes().stream().toList());
        assertEquals(LocalDate.of(2026, 3, 31), hints.getRequiredStartDate());
        assertEquals(LocalDate.of(2026, 3, 31), hints.getRequiredEndDate());
    }

    @Test
    void shouldCarryDerivedMetricSpecificationsImmutableAndInStableOrder() {
        BankIntentResult intent = new BankIntentResult();
        intent.setIntent(BankIntentType.RANKING);
        intent.setDerivedMetrics(
                List.of(derivedCandidate("DERIVED_ZB002_DIV_ZB001", "存贷比", "ZB002", "ZB001"),
                        derivedCandidate("DERIVED_ZB013_DIV_ZB001", "不良贷款率", "ZB013", "ZB001")));
        LLMReq.LLMSchema schema = new LLMReq.LLMSchema();
        schema.setMetrics(List.of(element("各项贷款余额", "ZB002", SchemaElementType.METRIC),
                element("各项存款余额", "ZB001", SchemaElementType.METRIC),
                element("不良贷款率", "ZB013", SchemaElementType.METRIC)));
        schema.setDimensions(List.of(element("机构", "机构", SchemaElementType.DIMENSION)));
        schema.setPartitionTime(element("数据日期", "数据日期", SchemaElementType.DIMENSION));

        SemanticIntentHints hints = SemanticIntentHints.from(intent, schema);

        assertEquals(2, hints.getRequiredDerivedMetrics().size());
        assertEquals(new SemanticIntentHints.DerivedMetricSpec("DERIVED_ZB002_DIV_ZB001", "ZB002",
                "ZB001", "存贷比"), hints.getRequiredDerivedMetrics().get(0));
        assertEquals(new SemanticIntentHints.DerivedMetricSpec("DERIVED_ZB013_DIV_ZB001", "ZB013",
                "ZB001", "不良贷款率"), hints.getRequiredDerivedMetrics().get(1));
        assertThrows(UnsupportedOperationException.class,
                () -> hints.getRequiredDerivedMetrics()
                        .add(new SemanticIntentHints.DerivedMetricSpec("DERIVED_ZB002_DIV_ZB001",
                                "ZB002", "ZB001", "存贷比")));
    }

    @Test
    void shouldCanonicalizeDerivedMetricOperandsAgainstTheSchema() {
        BankIntentResult intent = new BankIntentResult();
        intent.setIntent(BankIntentType.RANKING);
        intent.setDerivedMetrics(
                List.of(derivedCandidate("DERIVED_ZB002_DIV_ZB001", "存贷比", "ZB002", "ZB001")));
        LLMReq.LLMSchema schema = new LLMReq.LLMSchema();
        schema.setMetrics(List.of(element("各项贷款余额", "zb002", SchemaElementType.METRIC),
                element("各项存款余额", "zb001", SchemaElementType.METRIC)));

        SemanticIntentHints hints = SemanticIntentHints.from(intent, schema);

        SemanticIntentHints.DerivedMetricSpec spec = hints.getRequiredDerivedMetrics().get(0);
        assertEquals("DERIVED_ZB002_DIV_ZB001", spec.code());
        assertEquals("zb002", spec.numerator());
        assertEquals("zb001", spec.denominator());
        assertEquals("存贷比", spec.name());
    }

    @Test
    void shouldDropPresentationRankFromGrowthChangeEvidence() {
        BankIntentResult intent = new BankFinancialIntentRecognizer()
                .recognize("从2024年末到2026-03-31，全省各项存款余额增幅排名前三的农商行有哪些？", LocalDate.of(2026, 7, 22));

        SemanticIntentHints hints = SemanticIntentHints.from(intent, new LLMReq.LLMSchema());

        assertEquals(BankIntentType.CHANGE, hints.getExpectedIntent());
        assertTrue(hints.getRequiredFilters().isEmpty());
        assertEquals(null, hints.getRequiredLimit());
    }

    private BankIntentResult.DerivedMetricCandidate derivedCandidate(String code, String name,
            String numerator, String denominator) {
        return BankIntentResult.DerivedMetricCandidate.builder().code(code).name(name)
                .numerator(numerator).denominator(denominator).build();
    }

    private SchemaElement element(String name, String bizName, SchemaElementType type) {
        return SchemaElement.builder().name(name).bizName(bizName).type(type).build();
    }
}
