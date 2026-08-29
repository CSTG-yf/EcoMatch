package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.headless.chat.intent.BankIntentType;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.SemanticIntentHints;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankFewShotExemplarCatalogTest {

    @Test
    void catalogIsSmallAndLimitsEachQueryFamily() {
        List<BankFewShotExemplarCatalog.Exemplar> exemplars =
                BankFewShotExemplarCatalog.exemplars();

        assertTrue(exemplars.size() >= 5 && exemplars.size() <= 8);
        Map<BankFewShotExemplarCatalog.QueryFamily, Long> counts = exemplars.stream()
                .collect(Collectors.groupingBy(BankFewShotExemplarCatalog.Exemplar::family,
                        Collectors.counting()));
        assertTrue(counts.values().stream().allMatch(count -> count <= 2));
        assertTrue(counts.keySet().containsAll(List.of(
                BankFewShotExemplarCatalog.QueryFamily.RANKED_CHANGE,
                BankFewShotExemplarCatalog.QueryFamily.PROVINCE_AVERAGE,
                BankFewShotExemplarCatalog.QueryFamily.STRUCTURE_SHARE,
                BankFewShotExemplarCatalog.QueryFamily.DERIVED_RATIO,
                BankFewShotExemplarCatalog.QueryFamily.MOM_AND_YOY,
                BankFewShotExemplarCatalog.QueryFamily.BOTTOM_RANKING)));
    }

    @Test
    void exemplarsContainContractShapesButNoSqlGoldOrAnswerNumbers() {
        Pattern sql = Pattern.compile("(?is)\\bselect\\b.*\\bfrom\\b");
        Pattern answerNumber = Pattern.compile(
                "(?i)\\\"(?:value|answer|aggregate_value|metric_value|result|sum|average|difference|rate)\\\"\\s*:\\s*-?\\d");

        for (BankFewShotExemplarCatalog.Exemplar exemplar
                : BankFewShotExemplarCatalog.exemplars()) {
            assertNotNull(exemplar.question());
            assertNotNull(exemplar.requirementsJson());
            assertNotNull(exemplar.planJson());
            String content = exemplar.question() + exemplar.requirementsJson()
                    + exemplar.planJson();
            assertFalse(content.contains("TRAIN-"));
            assertFalse(content.contains("VAL-"));
            assertFalse(sql.matcher(content).find());
            assertFalse(answerNumber.matcher(content).find());
        }
    }

    @Test
    void requirementsAndPlanRenderAtMostOneMatchingFamilyExample() {
        String requirements = BankFewShotExemplarCatalog.renderRequirementsExamples(
                "请比较某机构某指标的环比和同比变化");
        BankRequestContract contract = BankRequestContract.builder().intent(BankIntentType.RATIO)
                .metricCodes(List.of("ZB002", "ZB001")).derivedMetrics(List.of(
                        BankQueryPlan.DerivedMetric.builder().metricCode("DERIVED_ZB002_DIV_ZB001")
                                .numerator("ZB002").denominator("ZB001").name("贷款占存款比例")
                                .build())).filters(List.of()).build();
        String plan = BankFewShotExemplarCatalog.renderPlanExamples(contract);

        assertFalse(requirements.isBlank());
        assertTrue(requirements.contains("MOM_AND_YOY"));
        assertFalse(plan.isBlank());
        assertTrue(plan.contains("DERIVED_RATIO"));
    }

    @Test
    void compilerOwnedScalarExamplesKeepTheExpectedPlanShape() {
        BankFewShotExemplarCatalog.Exemplar momAndYoy = exemplar(
                BankFewShotExemplarCatalog.QueryFamily.MOM_AND_YOY);
        assertTrue(momAndYoy.planJson().contains("\"dimensions\":[]"));
        assertTrue(momAndYoy.planJson().contains("\"columns\":[\"ZB001\"]"));
        assertFalse(momAndYoy.planJson().contains("bank_organization"));

        BankFewShotExemplarCatalog.Exemplar ratio = exemplar(
                BankFewShotExemplarCatalog.QueryFamily.DERIVED_RATIO);
        assertTrue(ratio.planJson().contains("\"derivedMetrics\":[]"));
        assertTrue(ratio.planJson().contains("\"bizName\":\"ZB002\""));
        assertTrue(ratio.planJson().contains("\"bizName\":\"ZB001\""));
        assertTrue(ratio.planJson().contains("\"type\":\"RATIO\""));
    }

    @Test
    void requirementsSelectionUsesHighSignalWordsOnly() {
        assertTrue(BankFewShotExemplarCatalog.renderRequirementsExamples(
                "请比较某机构与平均值的差额").isBlank());
        assertTrue(BankFewShotExemplarCatalog.renderRequirementsExamples(
                "请分析某指标增幅后续情况").isBlank());
    }

    @Test
    void syntheticQuestionsStillSelectEverySupportedFamily() {
        assertTrue(BankFewShotExemplarCatalog.renderRequirementsExamples(
                "按某指标增幅排名前两家机构").contains("RANKED_CHANGE"));
        assertTrue(BankFewShotExemplarCatalog.renderRequirementsExamples(
                "比较某机构与全省均值的差额").contains("PROVINCE_AVERAGE"));
        assertTrue(BankFewShotExemplarCatalog.renderRequirementsExamples(
                "对公和个人存款分别占比多少").contains("STRUCTURE_SHARE"));
        assertTrue(BankFewShotExemplarCatalog.renderRequirementsExamples(
                "某机构的存贷比是多少").contains("DERIVED_RATIO"));
        assertTrue(BankFewShotExemplarCatalog.renderRequirementsExamples(
                "某指标的环比和同比变化").contains("MOM_AND_YOY"));
        assertTrue(BankFewShotExemplarCatalog.renderRequirementsExamples(
                "全省倒数前两家机构").contains("BOTTOM_RANKING"));
    }

    @Test
    void everyExemplarPassesTheRealPlanValidator() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        SemanticIntentHints admission = SemanticIntentHints.builder()
                .allowedMetrics(BankSemanticRegistry.metricCodes())
                .allowedDimensions(BankSemanticRegistry.dimensions()).build();
        BankQueryPlanResponseParser parser = new BankQueryPlanResponseParser();

        for (BankFewShotExemplarCatalog.Exemplar exemplar
                : BankFewShotExemplarCatalog.exemplars()) {
            BankRequestContract contract = mapper.readValue(exemplar.requirementsJson(),
                    BankRequestContract.class);
            assertNotNull(contract, exemplar.family().name());
            assertNotNull(parser.parse(exemplar.planJson(), contract.toPlanHints(admission)),
                    exemplar.family().name());
        }
    }

    private BankFewShotExemplarCatalog.Exemplar exemplar(
            BankFewShotExemplarCatalog.QueryFamily family) {
        return BankFewShotExemplarCatalog.exemplars().stream()
                .filter(item -> item.family() == family).findFirst().orElseThrow();
    }
}
