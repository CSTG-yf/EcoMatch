package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.tencent.supersonic.common.pojo.ChatApp;
import com.tencent.supersonic.common.pojo.ChatModelConfig;
import com.tencent.supersonic.headless.chat.intent.BankIntentType;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMReq;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMResp;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.SemanticIntentHints;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BankPlanGenStrategyTest {

    @Test
    void modelGeneratesRequirementsThenAnExactPlanWithoutQuestionRuleRewriting() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(requirementsJson(), validPlanJson());

        LLMReq request = request();
        LLMResp response = new TestBankPlanGenStrategy(model).generate(request);

        assertEquals("MODEL", response.getBankCandidateDiagnostics().get("bank.nl2sql.planSource"));
        assertEquals(Set.of("ZB001", "ZB002"), response.getBankRequestContract().getMetricCodes()
                .stream().collect(java.util.stream.Collectors.toSet()));
        assertEquals(BankIntentType.COMPARISON, response.getBankQueryPlan().getIntent());
        assertEquals("ZB001", response.getBankQueryPlan().getMetrics().get(0).getBizName());
        assertEquals(Set.of("ZB001", "ZB002"),
                request.getSemanticIntentHints().getRequiredMetrics());
        assertEquals(1,
                response.getBankCandidateDiagnostics().get("bank.nl2sql.requirementsAttempts"));
        assertEquals(List.of(), response.getBankCandidateDiagnostics()
                .get("bank.nl2sql.requirementsRepairReasons"));
        verify(model, times(2)).generate(anyString());
        assertEquals("json_schema", request.getChatAppConfig().get(BankPlanGenStrategy.APP_KEY)
                .getChatModelConfig().getJsonFormatType());
        assertEquals(0, request.getChatAppConfig().get(BankPlanGenStrategy.APP_KEY)
                .getChatModelConfig().getMaxRetries());
    }

    @Test
    void missingMetricIsReturnedToTheModelAsARepairableRequirementsError() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(requirementsJson(),
                validPlanJson().replace(
                        "{\"bizName\":\"ZB001\",\"aggregation\":\"DEFAULT\",\"alias\":null},", ""),
                validPlanJson());
        LLMReq request = request();

        LLMResp response = new TestBankPlanGenStrategy(model).generate(request);

        assertNotNull(response.getBankQueryPlan());
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(model, times(3)).generate(prompts.capture());
        String repair = prompts.getAllValues().get(2);
        assertTrue(repair.contains("required_metrics_missing: ZB001"));
        assertTrue(repair.contains("<requirements_contract>"));
        assertTrue(repair.contains("<stage>PLAN</stage>"));
        assertFalse(repair.contains("SELECT "));
    }

    @Test
    void invalidRequirementIdentifiersAreRepairedByTheModelInsteadOfCanonicalizedByBackend() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(
                requirementsJson().replace("\"ZB001\",\"ZB002\"", "\"zb001\",\"ZB002\""),
                requirementsJson(), validPlanJson());

        LLMResp response = new TestBankPlanGenStrategy(model).generate(request());

        assertNotNull(response.getBankQueryPlan());
        verify(model, times(3)).generate(anyString());
    }

    @Test
    void explicitClosedMetricListRejectsModelCatalogExpansionAndRepairsRequirements() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        String expandedRequirements =
                requirementsJson().replace("\"ZB001\",\"ZB002\"", "\"ZB001\",\"ZB002\",\"ZB003\"");
        when(model.generate(anyString())).thenReturn(expandedRequirements, requirementsJson(),
                validPlanJson());

        LLMReq request = request();
        request.setQueryText("请比较江苏省D市农商行在2025-07-31的指标。" + "待评价指标集合：\n各项存款余额、各项贷款余额。");
        request.setSemanticIntentHints(
                SemanticIntentHints.builder().expectedIntent(BankIntentType.UNKNOWN)
                        .allowedMetrics(Set.of("ZB001", "ZB002", "ZB003"))
                        .allowedDimensions(Set.of("bank_organization", "bank_data_date")).build());

        LLMResp response = new TestBankPlanGenStrategy(model).generate(request);

        assertEquals(Set.of("ZB001", "ZB002"), response.getBankRequestContract().getMetricCodes()
                .stream().collect(java.util.stream.Collectors.toSet()));
        assertEquals(2,
                response.getBankCandidateDiagnostics().get("bank.nl2sql.requirementsAttempts"));
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(model, times(3)).generate(prompts.capture());
        String repair = prompts.getAllValues().get(1);
        assertTrue(repair.contains("explicit_closed_metric_list_mismatch"));
        assertTrue(repair.contains("unexpected=[ZB003]"));
        assertTrue(repair.contains("Regenerate the complete requirements JSON"));
        assertFalse(repair.contains("SELECT "));
    }

    @Test
    void depositStructureShareReturnsMissingPartToTheModelForRequirementsRepair() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(incompleteDepositShareRequirementsJson(),
                depositShareRequirementsJson(), depositSharePlanJson());

        LLMReq request = request();
        request.setQueryText("请计算江苏省I市农商行在2026-02-28的对公与个人存款构成比例。");
        request.setSemanticIntentHints(
                SemanticIntentHints.builder().expectedIntent(BankIntentType.UNKNOWN)
                        .allowedMetrics(Set.of("ZB001", "ZB003", "ZB004"))
                        .allowedDimensions(Set.of("bank_organization", "bank_data_date")).build());

        LLMResp response = new TestBankPlanGenStrategy(model).generate(request);

        assertEquals(List.of("ZB003", "ZB004", "ZB001"),
                response.getBankRequestContract().getMetricCodes());
        assertEquals(2,
                response.getBankCandidateDiagnostics().get("bank.nl2sql.requirementsAttempts"));
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(model, times(3)).generate(prompts.capture());
        String repair = prompts.getAllValues().get(1);
        assertTrue(repair.contains("deposit_structure_share_mismatch"));
        assertTrue(repair.contains("missing=[ZB004]"));
        assertFalse(repair.contains("SELECT "));
    }

    @Test
    void perCapitaProfitReturnsMissingDenominatorToTheModelForRequirementsRepair() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(incompletePerCapitaRequirementsJson(),
                perCapitaRequirementsJson(), perCapitaPlanJson());

        LLMReq request = request();
        request.setQueryText("请计算江苏省J市农商行2026-01-31的人均利润。");
        request.setSemanticIntentHints(SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.UNKNOWN).allowedMetrics(Set.of("ZB011", "ZB018"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date")).build());

        LLMResp response = new TestBankPlanGenStrategy(model).generate(request);

        assertEquals(List.of("ZB011", "ZB018"), response.getBankRequestContract().getMetricCodes());
        assertEquals(2,
                response.getBankCandidateDiagnostics().get("bank.nl2sql.requirementsAttempts"));
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(model, times(3)).generate(prompts.capture());
        String repair = prompts.getAllValues().get(1);
        assertTrue(repair.contains("per_capita_profit_mismatch"));
        assertTrue(repair.contains("missing=[ZB018]"));
        assertTrue(repair.contains("DERIVED_ZB011_DIV_ZB018"));
    }

    @Test
    void riskRatePairAcceptsEquivalentMetricOrderFromTheModel() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(reversedRiskRateRequirementsJson(),
                reversedRiskRatePlanJson());

        LLMReq request = request();
        request.setQueryText("江苏省B市农商行在2025-09-30，逾期贷款率相较不良贷款率相差多少？");
        request.setSemanticIntentHints(SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.UNKNOWN).allowedMetrics(Set.of("ZB013", "ZB017"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date")).build());

        LLMResp response = new TestBankPlanGenStrategy(model).generate(request);

        assertEquals(List.of("ZB017", "ZB013"), response.getBankRequestContract().getMetricCodes());
        verify(model, times(2)).generate(anyString());
    }

    @Test
    void explicitProvinceRankReturnsPointIntentToTheModelForRequirementsRepair() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(pointRankRequirementsJson(),
                rankingRequirementsJson(), rankingPlanJson());

        LLMReq request = request();
        request.setQueryText("江苏省H市农商行的成本收入比在2026-04-30是多少？全省排第几？");
        request.setSemanticIntentHints(SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.UNKNOWN).allowedMetrics(Set.of("ZB016"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date")).build());

        LLMResp response = new TestBankPlanGenStrategy(model).generate(request);

        assertEquals(BankIntentType.RANKING, response.getBankRequestContract().getIntent());
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(model, times(3)).generate(prompts.capture());
        assertTrue(prompts.getAllValues().get(1).contains("explicit_province_ranking_mismatch"));
    }

    @Test
    void selectedInstitutionWinnerReturnsComparisonIntentToTheModelForRequirementsRepair() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(selectedRankingRequirementsJson("COMPARISON"),
                selectedRankingRequirementsJson("RANKING"), selectedRankingPlanJson());

        LLMReq request = request();
        request.setQueryText("2025年底，江苏省A市农商行、江苏省E市农商行、江苏省I市农商行三家谁存款最多？");
        request.setSemanticIntentHints(SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.UNKNOWN).allowedMetrics(Set.of("ZB001"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date")).build());

        LLMResp response = new TestBankPlanGenStrategy(model).generate(request);

        assertEquals(BankIntentType.RANKING, response.getBankRequestContract().getIntent());
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(model, times(3)).generate(prompts.capture());
        assertTrue(
                prompts.getAllValues().get(1).contains("selected_organization_ranking_mismatch"));
    }

    @Test
    void organizationDifferenceReturnsPointIntentToTheModelForRequirementsRepair() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(
                organizationComparisonRequirementsJson("POINT_QUERY"),
                organizationComparisonRequirementsJson("COMPARISON"),
                organizationComparisonPlanJson());

        LLMReq request = request();
        request.setQueryText("2025年6月末，江苏省C市农商行比江苏省G市农商行的存款多多少？");
        request.setSemanticIntentHints(SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.UNKNOWN).allowedMetrics(Set.of("ZB001"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date")).build());

        LLMResp response = new TestBankPlanGenStrategy(model).generate(request);

        assertEquals(BankIntentType.COMPARISON, response.getBankRequestContract().getIntent());
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(model, times(3)).generate(prompts.capture());
        assertTrue(prompts.getAllValues().get(1).contains("organization_comparison_mismatch"));
    }

    @Test
    void daysAboveProvinceAverageReturnsClarificationToTheModelForRequirementsRepair() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(daysAboveRequirementsJson("THRESHOLD"),
                daysAboveRequirementsJson("AGGREGATION"), daysAbovePlanJson());

        LLMReq request = request();
        request.setQueryText("2025年全年，江苏省B市农商行的不良贷款率有多少天高于全省均值？");
        request.setSemanticIntentHints(SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.UNKNOWN).allowedMetrics(Set.of("ZB013"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date")).build());

        LLMResp response = new TestBankPlanGenStrategy(model).generate(request);

        assertEquals(BankIntentType.AGGREGATION, response.getBankRequestContract().getIntent());
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(model, times(3)).generate(prompts.capture());
        assertTrue(prompts.getAllValues().get(1).contains("days_above_province_average_mismatch"));
    }

    @Test
    void daysAboveProvinceAverageRepairsAnInitialClarifyBeforeTheGenericClarifyExit() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(clarificationJson(),
                daysAboveRequirementsJson("AGGREGATION"), daysAbovePlanJson());

        LLMReq request = request();
        request.setQueryText("2025年全年，江苏省B市农商行的不良贷款率有多少天高于全省均值？");
        request.setSemanticIntentHints(SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.UNKNOWN).allowedMetrics(Set.of("ZB013"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date")).build());

        LLMResp response = new TestBankPlanGenStrategy(model).generate(request);

        assertEquals(BankIntentType.AGGREGATION, response.getBankRequestContract().getIntent());
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(model, times(3)).generate(prompts.capture());
        assertTrue(prompts.getAllValues().get(1).contains("days_above_province_average_mismatch"));
    }

    @Test
    void monthAndYearComparisonRepairsAnInitialClarifyWithTheExactContract() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(clarificationJson(),
                monthAndYearRequirementsJson(), monthAndYearPlanJson());

        LLMReq request = request();
        request.setQueryText("分析江苏省F市农商行在2026-04-30的各项贷款余额环比（较上月）和同比（较去年同期）的变化情况。");
        request.setSemanticIntentHints(SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.UNKNOWN).allowedMetrics(Set.of("ZB002"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date")).build());

        LLMResp response = new TestBankPlanGenStrategy(model).generate(request);

        assertEquals(BankQueryPlan.TimeComparison.MOM_AND_YOY,
                response.getBankRequestContract().getTime().getComparison());
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(model, times(3)).generate(prompts.capture());
        assertTrue(prompts.getAllValues().get(1).contains("mom_and_yoy_requirements_mismatch"));
    }

    @Test
    void provinceBottomRankingRepairsAnInitialClarifyWithoutInventingAnOrganization() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(clarificationJson(),
                provinceBottomRequirementsJson(), provinceBottomPlanJson());

        LLMReq request = request();
        request.setQueryText("2025年8月末，全省净利润排最后一名的是哪家？");
        request.setSemanticIntentHints(SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.UNKNOWN).allowedMetrics(Set.of("ZB011"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date")).build());

        LLMResp response = new TestBankPlanGenStrategy(model).generate(request);

        assertTrue(response.getBankRequestContract().getOrganizationCodes().isEmpty());
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(model, times(3)).generate(prompts.capture());
        assertTrue(prompts.getAllValues().get(1)
                .contains("explicit_province_bottom_ranking_mismatch"));
    }

    @Test
    void wholePopulationTopRankingRejectsAnArbitraryOrganization() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(
                provinceWideRankingRequirementsJson("ZB001", "2025-12-31", "POINT_QUERY", true),
                provinceWideRankingRequirementsJson("ZB001", "2025-12-31", "RANKING", false),
                provinceWideRankingPlanJson("ZB001", "2025-12-31", "DESC"));

        LLMReq request = request();
        request.setQueryText("2025年12月31日，13家农商行中谁的存款规模排第一？");
        request.setSemanticIntentHints(SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.UNKNOWN).allowedMetrics(Set.of("ZB001"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date")).build());

        LLMResp response = new TestBankPlanGenStrategy(model).generate(request);

        assertEquals(BankIntentType.RANKING, response.getBankRequestContract().getIntent());
        assertTrue(response.getBankRequestContract().getOrganizationCodes().isEmpty());
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(model, times(3)).generate(prompts.capture());
        assertTrue(prompts.getAllValues().get(1)
                .contains("province_wide_institution_ranking_mismatch"));
    }

    @Test
    void wholePopulationLowestRankingKeepsTheOrganizationScopeEmpty() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(
                provinceWideRankingRequirementsJson("ZB013", "2026-03-31", "RANKING", true),
                provinceWideRankingRequirementsJson("ZB013", "2026-03-31", "RANKING", false),
                provinceWideRankingPlanJson("ZB013", "2026-03-31", "ASC"));

        LLMReq request = request();
        request.setQueryText("2026年3月末，哪家农商行的不良贷款率最低？");
        request.setSemanticIntentHints(SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.UNKNOWN).allowedMetrics(Set.of("ZB013"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date")).build());

        LLMResp response = new TestBankPlanGenStrategy(model).generate(request);

        assertEquals(BankIntentType.RANKING, response.getBankRequestContract().getIntent());
        assertTrue(response.getBankRequestContract().getOrganizationCodes().isEmpty());
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(model, times(3)).generate(prompts.capture());
        assertTrue(prompts.getAllValues().get(1)
                .contains("province_wide_institution_ranking_mismatch"));
    }

    @Test
    void depositStructureEqualityRepairsComparisonIntoPointQuery() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(depositEqualityRequirementsJson("COMPARISON"),
                depositEqualityRequirementsJson("POINT_QUERY"), depositEqualityPlanJson());

        LLMReq request = request();
        request.setQueryText("2025年12月末，江苏省C市农商行的对公存款加个人存款是不是等于各项存款？差额多少？");
        request.setSemanticIntentHints(
                SemanticIntentHints.builder().expectedIntent(BankIntentType.UNKNOWN)
                        .allowedMetrics(Set.of("ZB001", "ZB003", "ZB004"))
                        .allowedDimensions(Set.of("bank_organization", "bank_data_date")).build());

        LLMResp response = new TestBankPlanGenStrategy(model).generate(request);

        assertEquals(BankIntentType.POINT_QUERY, response.getBankRequestContract().getIntent());
        assertEquals(List.of("ZB003", "ZB004", "ZB001"),
                response.getBankRequestContract().getMetricCodes());
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(model, times(3)).generate(prompts.capture());
        assertTrue(prompts.getAllValues().get(1).contains("deposit_structure_equality_mismatch"));
    }

    @Test
    void daysAboveProvinceAverageRejectsASeparateMetricDirectionFilter() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(daysAboveRequirementsWithDirectionFilterJson(),
                daysAboveRequirementsJson("AGGREGATION"), daysAbovePlanJson());

        LLMReq request = request();
        request.setQueryText("2025年全年，江苏省B市农商行的不良贷款率有多少天高于全省均值？");
        request.setSemanticIntentHints(SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.UNKNOWN).allowedMetrics(Set.of("ZB013"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date")).build());

        new TestBankPlanGenStrategy(model).generate(request);

        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(model, times(3)).generate(prompts.capture());
        assertTrue(prompts.getAllValues().get(1)
                .contains("days_above_province_average_metric_filter_forbidden"));
    }

    @Test
    void namedOrganizationRankQuestionDoesNotUseProvinceWideBottomSelectorContract() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(rankingRequirementsJson(), rankingPlanJson());

        LLMReq request = request();
        request.setQueryText("江苏省H市农商行的成本收入比在2026-04-30是否全省排名最后？");
        request.setSemanticIntentHints(SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.UNKNOWN).allowedMetrics(Set.of("ZB016"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date")).build());

        LLMResp response = new TestBankPlanGenStrategy(model).generate(request);

        assertEquals(List.of("ORG008"), response.getBankRequestContract().getOrganizationCodes());
        verify(model, times(2)).generate(anyString());
    }

    @Test
    void loanStructureShareReturnsRatioIntentToTheModelForRequirementsRepair() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(loanStructureShareRequirementsJson("RATIO"),
                loanStructureShareRequirementsJson("POINT_QUERY"), loanStructureSharePlanJson());

        LLMReq request = request();
        request.setQueryText("2026年3月末，江苏省G市农商行的个人贷款和对公贷款分别占各项贷款的比例？");
        request.setSemanticIntentHints(
                SemanticIntentHints.builder().expectedIntent(BankIntentType.UNKNOWN)
                        .allowedMetrics(Set.of("ZB002", "ZB005", "ZB006"))
                        .allowedDimensions(Set.of("bank_organization", "bank_data_date")).build());

        LLMResp response = new TestBankPlanGenStrategy(model).generate(request);

        assertEquals(BankIntentType.POINT_QUERY, response.getBankRequestContract().getIntent());
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(model, times(3)).generate(prompts.capture());
        assertTrue(prompts.getAllValues().get(1).contains("loan_structure_share_mismatch"));
    }

    @Test
    void aliasParaphraseDepositStructureShareReachesTheSameCompositionContract() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(incompleteDepositShareRequirementsJson(),
                depositShareRequirementsJson(), depositSharePlanJson());

        LLMReq request = request();
        request.setQueryText("2026年2月末，江苏省I市农商行的储蓄存款和公司存款在存款总额中的占比情况如何？");
        request.setSemanticIntentHints(
                SemanticIntentHints.builder().expectedIntent(BankIntentType.UNKNOWN)
                        .allowedMetrics(Set.of("ZB001", "ZB003", "ZB004"))
                        .allowedDimensions(Set.of("bank_organization", "bank_data_date")).build());

        LLMResp response = new TestBankPlanGenStrategy(model).generate(request);

        assertEquals(List.of("ZB003", "ZB004", "ZB001"),
                response.getBankRequestContract().getMetricCodes());
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(model, times(3)).generate(prompts.capture());
        assertTrue(prompts.getAllValues().get(1).contains("deposit_structure_share_mismatch"));
        assertTrue(prompts.getAllValues().get(1).contains("missing=[ZB004]"));
    }

    @Test
    void aliasParaphraseLoanStructureShareReachesTheSameCompositionContract() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(loanStructureShareRequirementsJson("RATIO"),
                loanStructureShareRequirementsJson("POINT_QUERY"), loanStructureSharePlanJson());

        LLMReq request = request();
        request.setQueryText("江苏省G市农商行2026年3月末的零售贷款与对公贷款占各项贷款余额的比重。");
        request.setSemanticIntentHints(
                SemanticIntentHints.builder().expectedIntent(BankIntentType.UNKNOWN)
                        .allowedMetrics(Set.of("ZB002", "ZB005", "ZB006"))
                        .allowedDimensions(Set.of("bank_organization", "bank_data_date")).build());

        LLMResp response = new TestBankPlanGenStrategy(model).generate(request);

        assertEquals(List.of("ZB006", "ZB005", "ZB002"),
                response.getBankRequestContract().getMetricCodes());
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(model, times(3)).generate(prompts.capture());
        assertTrue(prompts.getAllValues().get(1).contains("loan_structure_share_mismatch"));
    }

    @Test
    void derivedLoanToDepositRatioReturnsBothOperandsForRequirementsRepair() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(incompleteLoanToDepositRatioRequirementsJson(),
                loanToDepositRatioRequirementsJson(), loanToDepositRatioPlanJson());

        LLMReq request = request();
        request.setQueryText("江苏省C市农商行2026年3月末的存贷比是多少？");
        request.setSemanticIntentHints(SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.UNKNOWN).allowedMetrics(Set.of("ZB001", "ZB002"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date")).build());

        LLMResp response = new TestBankPlanGenStrategy(model).generate(request);

        assertEquals(List.of("ZB002", "ZB001"), response.getBankRequestContract().getMetricCodes());
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(model, times(3)).generate(prompts.capture());
        assertTrue(prompts.getAllValues().get(1).contains("derived_point_ratio_mismatch"));
        assertTrue(prompts.getAllValues().get(1).contains("DERIVED_ZB002_DIV_ZB001"));
    }

    @Test
    void metricPairGapGeneralizesBeyondTheRiskRatePair() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(coveragePairRequirementsJson("COMPARISON"),
                coveragePairRequirementsJson("POINT_QUERY"), coveragePairPlanJson());

        LLMReq request = request();
        request.setQueryText("江苏省F市农商行在2026年1月末，拨备覆盖率相较资本充足率相差多少？");
        request.setSemanticIntentHints(SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.UNKNOWN).allowedMetrics(Set.of("ZB015", "ZB016"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date")).build());

        LLMResp response = new TestBankPlanGenStrategy(model).generate(request);

        assertEquals(BankIntentType.POINT_QUERY, response.getBankRequestContract().getIntent());
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(model, times(3)).generate(prompts.capture());
        assertTrue(prompts.getAllValues().get(1).contains("metric_pair_gap_mismatch"));
    }

    @Test
    void provinceRankParaphraseKeepsTheRankingIntent() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(pointRankRequirementsJson(),
                rankingRequirementsJson(), rankingPlanJson());

        LLMReq request = request();
        request.setQueryText("江苏省H市农商行的资本充足率在2026-04-30于全省排名第几？");
        request.setSemanticIntentHints(SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.UNKNOWN).allowedMetrics(Set.of("ZB016"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date")).build());

        LLMResp response = new TestBankPlanGenStrategy(model).generate(request);

        assertEquals(BankIntentType.RANKING, response.getBankRequestContract().getIntent());
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(model, times(3)).generate(prompts.capture());
        assertTrue(prompts.getAllValues().get(1).contains("explicit_province_ranking_mismatch"));
    }

    @Test
    void explicitLoanShareReturnsPointIntentToTheModelForRequirementsRepair() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(loanShareRequirementsJson("POINT_QUERY"),
                loanShareRequirementsJson("RATIO"), loanSharePlanJson());

        LLMReq request = request();
        request.setQueryText("某机构在指定日的不良贷款余额占贷款总额的比例是多少？");
        request.setSemanticIntentHints(SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.UNKNOWN).allowedMetrics(Set.of("ZB014", "ZB002"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date")).build());

        LLMResp response = new TestBankPlanGenStrategy(model).generate(request);

        assertEquals(BankIntentType.RATIO, response.getBankRequestContract().getIntent());
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(model, times(3)).generate(prompts.capture());
        assertTrue(prompts.getAllValues().get(1).contains("loan_share_ratio_mismatch"));
    }

    @Test
    void genericPointRatioClarificationIsReturnedToTheModelWithCatalogOperands() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(clarificationJson(),
                genericPointRatioRequirementsJson("RATIO"), genericPointRatioPlanJson());

        LLMReq request = request();
        request.setQueryText("江苏省A市农商行在2026-04-30的净利息收入占营业收入的比重有多大？");
        request.setSemanticIntentHints(SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.UNKNOWN).allowedMetrics(Set.of("ZB008", "ZB009"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date")).build());

        LLMResp response = new TestBankPlanGenStrategy(model).generate(request);

        assertEquals(BankIntentType.RATIO, response.getBankRequestContract().getIntent());
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(model, times(3)).generate(prompts.capture());
        assertTrue(prompts.getAllValues().get(1).contains("generic_point_ratio_mismatch"));
        assertTrue(prompts.getAllValues().get(1).contains("ZB008"));
    }

    @Test
    void endpointDirectionReturnsIntentMismatchToTheModelForRequirementsRepair() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(endpointChangeRequirementsJson("TREND"),
                endpointChangeRequirementsJson("CHANGE"), endpointChangePlanJson());

        LLMReq request = request();
        request.setQueryText("某机构从年中期末到年末，存款、贷款、风险率和利润的变动方向分别是什么？");
        request.setSemanticIntentHints(
                SemanticIntentHints.builder().expectedIntent(BankIntentType.UNKNOWN)
                        .allowedMetrics(Set.of("ZB001", "ZB002", "ZB011", "ZB013"))
                        .allowedDimensions(Set.of("bank_organization", "bank_data_date")).build());

        LLMResp response = new TestBankPlanGenStrategy(model).generate(request);

        assertEquals(BankIntentType.CHANGE, response.getBankRequestContract().getIntent());
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(model, times(3)).generate(prompts.capture());
        assertTrue(prompts.getAllValues().get(1).contains("endpoint_change_direction_mismatch"));
    }

    @Test
    void quarterlyDirectionKeepsTrendIntentWithoutEndpointRepair() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(quarterlyTrendRequirementsJson(),
                quarterlyTrendPlanJson());

        LLMReq request = request();
        request.setQueryText("某农商行从2025年一季度末到四季度末，逐季度存款变化方向如何？");
        request.setSemanticIntentHints(SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.UNKNOWN).allowedMetrics(Set.of("ZB001"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date")).build());

        LLMResp response = new TestBankPlanGenStrategy(model).generate(request);

        assertEquals(BankIntentType.TREND, response.getBankRequestContract().getIntent());
        verify(model, times(2)).generate(anyString());
    }

    @Test
    void provinceAverageComparisonReturnsUnsupportedIntentToTheModelForRequirementsRepair() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(requirementsJson()
                .replace("\"intent\":\"COMPARISON\"", "\"intent\":\"POINT_QUERY\""),
                requirementsJson(), validPlanJson());

        LLMReq request = request();
        request.setQueryText("请把某机构指定日的存款、贷款与全省均值逐项对比。");

        LLMResp response = new TestBankPlanGenStrategy(model).generate(request);

        assertEquals(BankIntentType.COMPARISON, response.getBankRequestContract().getIntent());
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(model, times(3)).generate(prompts.capture());
        assertTrue(prompts.getAllValues().get(1).contains("province_average_comparison_mismatch"));
    }

    @Test
    void multiOrganizationTotalReturnsMissingOrganizationDimensionToTheModelForPlanRepair() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(multiOrganizationTotalRequirementsJson(),
                multiOrganizationTotalPlanJson(false), multiOrganizationTotalPlanJson(true));

        LLMReq request = request();
        request.setQueryText("两个指定机构的期末存款合计是多少？请保留逐机构核验依据。");
        request.setSemanticIntentHints(SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.UNKNOWN).allowedMetrics(Set.of("ZB001"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date")).build());

        LLMResp response = new TestBankPlanGenStrategy(model).generate(request);

        assertEquals(List.of("bank_organization"), response.getBankQueryPlan().getDimensions());
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(model, times(3)).generate(prompts.capture());
        assertTrue(prompts.getAllValues().get(2)
                .contains("multi_organization_total_dimension_mismatch"));
    }

    @Test
    void dailyAverageOnlyModeIsReturnedToTheModelForPlanRepair() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(dailyAverageRequirementsJson(),
                dailyAveragePlanJson(null), dailyAveragePlanJson("AVERAGE_ONLY"));

        LLMReq request = request();
        request.setQueryText("请给出江苏省I市农商行2026全年的日均各项贷款余额。");
        request.setSemanticIntentHints(SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.UNKNOWN).allowedMetrics(Set.of("ZB002"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date")).build());

        LLMResp response = new TestBankPlanGenStrategy(model).generate(request);

        assertEquals(BankQueryPlan.AggregationResultMode.AVERAGE_ONLY,
                response.getBankQueryPlan().getOutput().getAggregationMode());
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(model, times(3)).generate(prompts.capture());
        assertTrue(prompts.getAllValues().get(2).contains("daily_average_output_mode_mismatch"));
    }

    @Test
    void invalidComparisonRangeIsReturnedToTheModelBeforePlanGeneration() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(invalidChangeRequirementsJson(),
                validChangeRequirementsJson(), validChangePlanJson());

        LLMResp response = new TestBankPlanGenStrategy(model).generate(request());

        assertEquals(BankIntentType.CHANGE, response.getBankQueryPlan().getIntent());
        assertEquals(2,
                response.getBankCandidateDiagnostics().get("bank.nl2sql.requirementsAttempts"));
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(model, times(3)).generate(prompts.capture());
        String repair = prompts.getAllValues().get(1);
        assertTrue(repair.contains("baselineEndDate < startDate"));
        assertTrue(repair.contains("<stage>REQUIREMENTS</stage>"));
        assertFalse(repair.contains("SELECT "));
    }

    @Test
    void rankedGrowthOverPopulationKeepsTheRankingIntentContract() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(rankedGrowthRequirementsJson("RANKING"),
                rankedGrowthRequirementsJson("CHANGE"), rankedGrowthPlanJson());

        LLMReq request = request();
        request.setQueryText("2024年12月末至2026年4月末期间，各家农商行净利润增长最快的前三家是谁？增幅分别是多少？");
        request.setSemanticIntentHints(SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.UNKNOWN).allowedMetrics(Set.of("ZB011"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date")).build());

        LLMResp response = new TestBankPlanGenStrategy(model).generate(request);

        assertEquals(BankIntentType.CHANGE, response.getBankRequestContract().getIntent());
        assertEquals(2,
                response.getBankCandidateDiagnostics().get("bank.nl2sql.requirementsAttempts"));
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(model, times(3)).generate(prompts.capture());
        assertTrue(prompts.getAllValues().get(1).contains("ranked_change_family_mismatch"));
    }

    @Test
    void invalidStartOfYearBaselineIsReturnedToTheModelBeforePlanGeneration() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(invalidStartOfYearRequirementsJson(),
                validStartOfYearRequirementsJson(), validStartOfYearPlanJson());

        LLMResp response = new TestBankPlanGenStrategy(model).generate(request());

        assertEquals(BankIntentType.CHANGE, response.getBankQueryPlan().getIntent());
        assertEquals(2,
                response.getBankCandidateDiagnostics().get("bank.nl2sql.requirementsAttempts"));
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(model, times(3)).generate(prompts.capture());
        String repair = prompts.getAllValues().get(1);
        assertTrue(repair.contains("prior calendar year end"));
        assertTrue(repair.contains("<stage>REQUIREMENTS</stage>"));
        assertFalse(repair.contains("SELECT "));
    }

    @Test
    void unsupportedAnswerFactTypeIsReturnedToTheModelBeforePlanGeneration() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString()))
                .thenReturn(
                        requirementsJson().replace("\"VALUE\",\"PROVINCE_AVERAGE\",\"GAP_VALUE\"",
                                "\"VALUE\",\"MINIMUM_VALUE\""),
                        requirementsJson(), validPlanJson());

        LLMResp response = new TestBankPlanGenStrategy(model).generate(request());

        assertNotNull(response.getBankQueryPlan());
        assertEquals(2,
                response.getBankCandidateDiagnostics().get("bank.nl2sql.requirementsAttempts"));
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(model, times(3)).generate(prompts.capture());
        String repair = prompts.getAllValues().get(1);
        assertTrue(repair.contains("MINIMUM_VALUE"));
        assertTrue(repair.contains("<stage>REQUIREMENTS</stage>"));
        assertFalse(repair.contains("SELECT "));
    }

    @Test
    void oneClarificationIsRecheckedByTheModelBeforeBeingReturnedToTheUser() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(clarificationJson(), requirementsJson(),
                validPlanJson());

        LLMReq request = request();
        LLMResp response = new TestBankPlanGenStrategy(model).generate(request);

        assertNotNull(response.getBankQueryPlan());
        assertEquals(List.of("CLARIFICATION_RECHECK"), request.getBankRequirementsRepairReasons());
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(model, times(3)).generate(prompts.capture());
        assertTrue(prompts.getAllValues().get(1).contains("model selected CLARIFY"));
    }

    @Test
    void repeatedClarificationIsRecheckedUntilTheBoundedRequirementAttemptLimit() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(clarificationJson(), clarificationJson(),
                requirementsJson(), validPlanJson());

        LLMReq request = request();
        LLMResp response = new TestBankPlanGenStrategy(model).generate(request);

        assertNotNull(response.getBankQueryPlan());
        assertEquals(List.of("CLARIFICATION_RECHECK", "CLARIFICATION_RECHECK"),
                request.getBankRequirementsRepairReasons());
        verify(model, times(4)).generate(anyString());
    }

    @Test
    void clarificationRepairReturnsExactCatalogEvidenceWithoutReplacingTheModelPlan() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(clarificationJson(), ratioRequirementsJson(),
                ratioPlanJson());
        LLMReq request = request();
        request.setQueryText("江苏省L市农商行2026-02-28的存贷比请帮我查一下。");

        LLMResp response = new TestBankPlanGenStrategy(model).generate(request);

        assertEquals("MODEL", response.getBankCandidateDiagnostics().get("bank.nl2sql.planSource"));
        assertEquals(BankIntentType.RATIO, response.getBankQueryPlan().getIntent());
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(model, times(3)).generate(prompts.capture());
        String repair = prompts.getAllValues().get(1);
        assertTrue(repair.contains("organizationCodes=[ORG012(江苏省L市农商行)]"));
        assertTrue(repair.contains("metricCodes=[ZB002(各项贷款余额), ZB001(各项存款余额)]"));
        assertTrue(repair.contains("DERIVED_ZB002_DIV_ZB001(存贷比=ZB002/ZB001)"));
        assertTrue(repair.contains("time=2026-02-28..2026-02-28 granularity=DAY"));
        assertTrue(repair.contains("regenerate the entire requirements JSON yourself"));
        assertEquals(Set.of("ZB001", "ZB002"),
                request.getSemanticIntentHints().getRequiredMetrics());
    }

    @Test
    void transportFailureDoesNotUseADeterministicFallbackPlan() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenThrow(new RuntimeException("connection timeout"));

        BankNl2SqlError error = assertThrows(BankNl2SqlError.class,
                () -> new TestBankPlanGenStrategy(model).generate(request()));

        assertEquals(BankNl2SqlError.Category.MODEL_FAILURE, error.getCategory());
    }

    @Test
    void toolRepairKeepsModelRequirementsAndReturnsACompleteReplacementPlan() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(validPlanJson());
        LLMReq request = request();
        request.setBankRequestContract(new BankRequestContractResponseParser()
                .parse(requirementsJson(), request.getSemanticIntentHints()));
        request.setPreviousBankQueryPlanJson(validPlanJson());
        request.setBankPlanToolResult(BankPlanToolResult.failed(1, "trace-1", "fingerprint-1",
                BankPlanToolResult.Stage.COMPILE, "UNSUPPORTED_PLAN_COMBINATION", Map.of(),
                List.of("根据错误码修正计划")));

        LLMResp response = new TestBankPlanGenStrategy(model).generate(request);

        assertEquals("MODEL_TOOL_REPAIR",
                response.getBankCandidateDiagnostics().get("bank.nl2sql.planSource"));
        verify(model).generate(org.mockito.ArgumentMatchers
                .<String>argThat(prompt -> prompt.contains("<tool_result>")
                        && prompt.contains("<requirements_contract>")
                        && prompt.contains("UNSUPPORTED_PLAN_COMBINATION")));
    }

    @Test
    void modelCanRequestAUserClarificationInsteadOfGuessingAnIdentifier() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn("""
                {"version":"1.0","action":"CLARIFY","intent":null,"metricCodes":[],
                "derivedMetrics":[],"organizationCodes":[],"time":null,"filters":[],
                "requiredLimit":null,"answerFactTypes":[],"clarification":"请明确要查询的具体指标。"}
                """);

        BankNl2SqlError error = assertThrows(BankNl2SqlError.class,
                () -> new TestBankPlanGenStrategy(model).generate(request()));

        assertEquals(BankNl2SqlError.Category.CLARIFICATION_REQUIRED, error.getCategory());
        assertEquals("请明确要查询的具体指标。",
                error.toParserErrorMessage().replace("[BANK_CONSTRAINED_PLAN]", ""));
    }

    @Test
    void repairDiagnosticsCarryStableErrorCodesForBothStages() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(
                requirementsJson().replace("\"ZB001\",\"ZB002\"", "\"ZB001\",\"ZB002\",\"ZB003\""),
                requirementsJson(), validPlanJson());

        LLMReq request = request();
        request.setQueryText("请比较江苏省D市农商行在2025-07-31的指标。" + "待评价指标集合：\n各项存款余额、各项贷款余额。");
        request.setSemanticIntentHints(
                SemanticIntentHints.builder().expectedIntent(BankIntentType.UNKNOWN)
                        .allowedMetrics(Set.of("ZB001", "ZB002", "ZB003"))
                        .allowedDimensions(Set.of("bank_organization", "bank_data_date")).build());

        LLMResp response = new TestBankPlanGenStrategy(model).generate(request);

        assertNotNull(response.getBankQueryPlan());
        assertEquals(List.of("explicit_closed_metric_list_mismatch"),
                response.getBankCandidateDiagnostics().get("bank.nl2sql.requirementsRepairCodes"));
        assertEquals(List.of(),
                response.getBankCandidateDiagnostics().get("bank.nl2sql.planRepairCodes"));
    }

    @Test
    void planRepairDiagnosticsCarryStableErrorCodes() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(dailyAverageRequirementsJson(),
                dailyAveragePlanJson(null), dailyAveragePlanJson("AVERAGE_ONLY"));

        LLMReq request = request();
        request.setQueryText("请给出江苏省I市农商行2026全年的日均各项贷款余额。");
        request.setSemanticIntentHints(SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.UNKNOWN).allowedMetrics(Set.of("ZB002"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date")).build());

        LLMResp response = new TestBankPlanGenStrategy(model).generate(request);

        assertEquals(BankQueryPlan.AggregationResultMode.AVERAGE_ONLY,
                response.getBankQueryPlan().getOutput().getAggregationMode());
        assertEquals(List.of(),
                response.getBankCandidateDiagnostics().get("bank.nl2sql.requirementsRepairCodes"));
        assertEquals(List.of("daily_average_output_mode_mismatch"),
                response.getBankCandidateDiagnostics().get("bank.nl2sql.planRepairCodes"));
    }

    @Test
    void stageDiagnosticsExposePerStageCacheCounters() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(requirementsJson(), validPlanJson());

        LLMResp response = new TestBankPlanGenStrategy(model).generate(request());

        @SuppressWarnings("unchecked")
        Map<String, Object> prefixCache = (Map<String, Object>) response
                .getBankCandidateDiagnostics().get("bankPlanPrefixCache");
        assertNotNull(prefixCache);
        assertTrue(prefixCache.containsKey("requirements"));
        assertTrue(prefixCache.containsKey("plan"));
        @SuppressWarnings("unchecked")
        Map<String, Object> requirementsStats =
                (Map<String, Object>) prefixCache.get("requirements");
        @SuppressWarnings("unchecked")
        Map<String, Object> planStats = (Map<String, Object>) prefixCache.get("plan");
        assertEquals(1L, requirementsStats.get("modelCalls"));
        assertEquals(1L, planStats.get("modelCalls"));
    }

    private LLMReq request() {
        LLMReq request = new LLMReq();
        request.setQueryText("2025年7月末，江苏省D市农商行的各项存款余额和各项贷款余额与全省均值相比如何？");
        request.setSqlGenType(LLMReq.SqlGenType.BANK_CONSTRAINED_PLAN);
        request.setSemanticIntentHints(SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.UNKNOWN).allowedMetrics(Set.of("ZB001", "ZB002"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date")).build());
        request.setChatAppConfig(Map.of(BankPlanGenStrategy.APP_KEY,
                ChatApp.builder().chatModelConfig(new ChatModelConfig()).build()));
        return request;
    }

    private String requirementsJson() {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"COMPARISON",
                "metricCodes":["ZB001","ZB002"],"derivedMetrics":[],"organizationCodes":["ORG004"],
                "time":{"startDate":"2025-07-31","endDate":"2025-07-31","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},
                "filters":[{"field":"benchmark","operator":"COMPARE","value":"PROVINCE_AVERAGE","values":[]}],
                "requiredLimit":null,"answerFactTypes":["VALUE","PROVINCE_AVERAGE","GAP_VALUE"],"clarification":null}
                """;
    }

    private String clarificationJson() {
        return """
                {"version":"1.0","action":"CLARIFY","intent":"UNKNOWN","metricCodes":[],
                "derivedMetrics":[],"organizationCodes":[],"time":null,"filters":[],
                "requiredLimit":null,"answerFactTypes":[],"clarification":"请明确具体指标、机构和时间范围。"}
                """;
    }

    private String ratioRequirementsJson() {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"RATIO",
                "metricCodes":["ZB002","ZB001"],
                "derivedMetrics":[{"metricCode":"DERIVED_ZB002_DIV_ZB001","numerator":"ZB002","denominator":"ZB001","name":"存贷比"}],
                "organizationCodes":["ORG012"],
                "time":{"startDate":"2026-02-28","endDate":"2026-02-28","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},
                "filters":[],"requiredLimit":null,"answerFactTypes":["RATIO_VALUE"],"clarification":null}
                """;
    }

    private String ratioPlanJson() {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"RATIO",
                "metrics":[{"bizName":"ZB002","aggregation":"DEFAULT","alias":null},{"bizName":"ZB001","aggregation":"DEFAULT","alias":null}],
                "derivedMetrics":[],
                "dimensions":["bank_organization"],"organizations":[{"code":"ORG012","bizName":null}],
                "time":{"startDate":"2026-02-28","endDate":"2026-02-28","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},
                "filters":[],"calculation":{"type":"RATIO","baseline":"ZB001"},"orderBy":[],"limit":null,
                "output":{"columns":["bank_organization","ZB002","ZB001"],"orderSensitive":false}}
                """;
    }

    private String incompleteDepositShareRequirementsJson() {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"RATIO",
                "metricCodes":["ZB003","ZB001"],"derivedMetrics":[],"organizationCodes":["ORG009"],
                "time":{"startDate":"2026-02-28","endDate":"2026-02-28","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},
                "filters":[],"requiredLimit":null,"answerFactTypes":["RATIO_VALUE"],"clarification":null}
                """;
    }

    private String depositShareRequirementsJson() {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"POINT_QUERY",
                "metricCodes":["ZB003","ZB004","ZB001"],"derivedMetrics":[],"organizationCodes":["ORG009"],
                "time":{"startDate":"2026-02-28","endDate":"2026-02-28","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},
                "filters":[],"requiredLimit":null,"answerFactTypes":["VALUE","RATIO_VALUE"],"clarification":null}
                """;
    }

    private String depositSharePlanJson() {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"POINT_QUERY",
                "metrics":[{"bizName":"ZB003","aggregation":"DEFAULT","alias":null},{"bizName":"ZB004","aggregation":"DEFAULT","alias":null},{"bizName":"ZB001","aggregation":"DEFAULT","alias":null}],
                "derivedMetrics":[],"dimensions":["bank_organization"],"organizations":[{"code":"ORG009","bizName":null}],
                "time":{"startDate":"2026-02-28","endDate":"2026-02-28","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},
                "filters":[],"calculation":{"type":"DIRECT","baseline":null},"orderBy":[],"limit":null,
                "output":{"columns":["bank_organization","ZB003","ZB004","ZB001"],"orderSensitive":true}}
                """;
    }

    private String incompletePerCapitaRequirementsJson() {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"POINT_QUERY",
                "metricCodes":["ZB011"],"derivedMetrics":[],"organizationCodes":["ORG010"],
                "time":{"startDate":"2026-01-31","endDate":"2026-01-31","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},
                "filters":[],"requiredLimit":null,"answerFactTypes":["VALUE"],"clarification":null}
                """;
    }

    private String perCapitaRequirementsJson() {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"RATIO",
                "metricCodes":["ZB011","ZB018"],
                "derivedMetrics":[{"metricCode":"DERIVED_ZB011_DIV_ZB018","numerator":"ZB011","denominator":"ZB018","name":"人均利润"}],
                "organizationCodes":["ORG010"],
                "time":{"startDate":"2026-01-31","endDate":"2026-01-31","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},
                "filters":[],"requiredLimit":null,"answerFactTypes":["RATIO_VALUE"],"clarification":null}
                """;
    }

    private String perCapitaPlanJson() {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"RATIO",
                "metrics":[{"bizName":"ZB011","aggregation":"DEFAULT","alias":null},{"bizName":"ZB018","aggregation":"DEFAULT","alias":null}],
                "derivedMetrics":[],"dimensions":["bank_organization"],"organizations":[{"code":"ORG010","bizName":null}],
                "time":{"startDate":"2026-01-31","endDate":"2026-01-31","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},
                "filters":[],"calculation":{"type":"RATIO","baseline":"ZB018"},"orderBy":[],"limit":null,
                "output":{"columns":["bank_organization","ZB011","ZB018"],"orderSensitive":true}}
                """;
    }

    private String reversedRiskRateRequirementsJson() {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"POINT_QUERY",
                "metricCodes":["ZB017","ZB013"],"derivedMetrics":[],"organizationCodes":["ORG002"],
                "time":{"startDate":"2025-09-30","endDate":"2025-09-30","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},
                "filters":[],"requiredLimit":null,"answerFactTypes":["VALUE","GAP_VALUE"],"clarification":null}
                """;
    }

    private String reversedRiskRatePlanJson() {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"POINT_QUERY",
                "metrics":[{"bizName":"ZB017","aggregation":"DEFAULT","alias":null},{"bizName":"ZB013","aggregation":"DEFAULT","alias":null}],
                "derivedMetrics":[],"dimensions":["bank_organization"],"organizations":[{"code":"ORG002","bizName":null}],
                "time":{"startDate":"2025-09-30","endDate":"2025-09-30","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},
                "filters":[],"calculation":{"type":"DIRECT","baseline":null},"orderBy":[],"limit":null,
                "output":{"columns":["bank_organization","ZB017","ZB013"],"orderSensitive":false}}
                """;
    }

    private String pointRankRequirementsJson() {
        return rankingRequirementsJson().replace("\"intent\":\"RANKING\"",
                "\"intent\":\"POINT_QUERY\"");
    }

    private String rankingRequirementsJson() {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"RANKING",
                "metricCodes":["ZB016"],"derivedMetrics":[],"organizationCodes":["ORG008"],
                "time":{"startDate":"2026-04-30","endDate":"2026-04-30","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},
                "filters":[],"requiredLimit":null,"answerFactTypes":["VALUE","RANK"],"clarification":null}
                """;
    }

    private String rankingPlanJson() {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"RANKING",
                "metrics":[{"bizName":"ZB016","aggregation":"DEFAULT","alias":null}],
                "derivedMetrics":[],"dimensions":["bank_organization"],"organizations":[{"code":"ORG008","bizName":null}],
                "time":{"startDate":"2026-04-30","endDate":"2026-04-30","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},
                "filters":[],"calculation":{"type":"DIRECT","baseline":null},
                "orderBy":[{"field":"ZB016","direction":"ASC"}],"limit":null,
                "output":{"columns":["bank_organization","ZB016"],"orderSensitive":true,"aggregationMode":null}}
                """;
    }

    private String selectedRankingRequirementsJson(String intent) {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"%s",
                "metricCodes":["ZB001"],"derivedMetrics":[],"organizationCodes":["ORG001","ORG005","ORG009"],
                "time":{"startDate":"2025-12-31","endDate":"2025-12-31","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},
                "filters":[],"requiredLimit":1,"answerFactTypes":["VALUE"],"clarification":null}
                """
                .formatted(intent);
    }

    private String selectedRankingPlanJson() {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"RANKING",
                "metrics":[{"bizName":"ZB001","aggregation":"DEFAULT","alias":null}],
                "derivedMetrics":[],"dimensions":["bank_organization"],"organizations":[{"code":"ORG001","bizName":null},{"code":"ORG005","bizName":null},{"code":"ORG009","bizName":null}],
                "time":{"startDate":"2025-12-31","endDate":"2025-12-31","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},
                "filters":[],"calculation":{"type":"DIRECT","baseline":null},"orderBy":[{"field":"ZB001","direction":"DESC"}],"limit":1,
                "output":{"columns":["bank_organization","ZB001"],"orderSensitive":true,"aggregationMode":null}}
                """;
    }

    private String organizationComparisonRequirementsJson(String intent) {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"%s",
                "metricCodes":["ZB001"],"derivedMetrics":[],"organizationCodes":["ORG003","ORG007"],
                "time":{"startDate":"2025-06-30","endDate":"2025-06-30","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},
                "filters":[],"requiredLimit":null,"answerFactTypes":["VALUE","GAP_VALUE"],"clarification":null}
                """
                .formatted(intent);
    }

    private String organizationComparisonPlanJson() {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"COMPARISON",
                "metrics":[{"bizName":"ZB001","aggregation":"DEFAULT","alias":null}],
                "derivedMetrics":[],"dimensions":["bank_organization"],"organizations":[{"code":"ORG003","bizName":null},{"code":"ORG007","bizName":null}],
                "time":{"startDate":"2025-06-30","endDate":"2025-06-30","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},
                "filters":[],"calculation":{"type":"DIRECT","baseline":null},"orderBy":[],"limit":null,
                "output":{"columns":["bank_organization","ZB001"],"orderSensitive":false}}
                """;
    }

    private String daysAboveRequirementsJson(String intent) {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"%s",
                "metricCodes":["ZB013"],"derivedMetrics":[],"organizationCodes":["ORG002"],
                "time":{"startDate":"2025-01-01","endDate":"2025-12-31","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},
                "filters":[{"field":"benchmark","operator":"COMPARE","value":"PROVINCE_AVERAGE","values":[]}],
                "requiredLimit":null,"answerFactTypes":["COUNT"],"clarification":null}
                """
                .formatted(intent);
    }

    private String daysAbovePlanJson() {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"AGGREGATION",
                "metrics":[{"bizName":"ZB013","aggregation":"DEFAULT","alias":null}],
                "derivedMetrics":[],"dimensions":["bank_organization"],"organizations":[{"code":"ORG002","bizName":null}],
                "time":{"startDate":"2025-01-01","endDate":"2025-12-31","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},
                "filters":[{"field":"benchmark","operator":"COMPARE","value":"PROVINCE_AVERAGE","values":[]}],
                "calculation":{"type":"COUNT_DAYS_ABOVE_PROVINCE_AVERAGE","baseline":null},"orderBy":[],"limit":null,
                "output":{"columns":["bank_organization","ZB013"],"orderSensitive":true}}
                """;
    }

    private String daysAboveRequirementsWithDirectionFilterJson() {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"AGGREGATION",
                "metricCodes":["ZB013"],"derivedMetrics":[],"organizationCodes":["ORG002"],
                "time":{"startDate":"2025-01-01","endDate":"2025-12-31","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},
                "filters":[{"field":"benchmark","operator":"COMPARE","value":"PROVINCE_AVERAGE","values":[]},{"field":"metric_value","operator":"GT","value":"PROVINCE_AVERAGE","values":[]}],
                "requiredLimit":null,"answerFactTypes":["COUNT"],"clarification":null}
                """;
    }

    private String monthAndYearRequirementsJson() {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"CHANGE",
                "metricCodes":["ZB002"],"derivedMetrics":[],"organizationCodes":["ORG006"],
                "time":{"startDate":"2026-04-30","endDate":"2026-04-30","granularity":"DAY","comparison":"MOM_AND_YOY","baselineStartDate":null,"baselineEndDate":null},
                "filters":[],"requiredLimit":null,"answerFactTypes":["VALUE","CHANGE_RATE"],"clarification":null}
                """;
    }

    private String monthAndYearPlanJson() {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"CHANGE",
                "metrics":[{"bizName":"ZB002","aggregation":"DEFAULT","alias":null}],
                "derivedMetrics":[],"dimensions":[],"organizations":[{"code":"ORG006","bizName":null}],
                "time":{"startDate":"2026-04-30","endDate":"2026-04-30","granularity":"DAY","comparison":"MOM_AND_YOY","baselineStartDate":null,"baselineEndDate":null},
                "filters":[],"calculation":{"type":"CHANGE","baseline":null},"orderBy":[],"limit":null,
                "output":{"columns":["ZB002"],"orderSensitive":true}}
                """;
    }

    private String provinceBottomRequirementsJson() {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"RANKING",
                "metricCodes":["ZB011"],"derivedMetrics":[],"organizationCodes":[],
                "time":{"startDate":"2025-08-31","endDate":"2025-08-31","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},
                "filters":[{"field":"rank_from_bottom","operator":"LTE","value":"1","values":[]}],
                "requiredLimit":1,"answerFactTypes":["VALUE","RANK"],"clarification":null}
                """;
    }

    private String provinceBottomPlanJson() {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"RANKING",
                "metrics":[{"bizName":"ZB011","aggregation":"DEFAULT","alias":null}],
                "derivedMetrics":[],"dimensions":["bank_organization"],"organizations":[],
                "time":{"startDate":"2025-08-31","endDate":"2025-08-31","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},
                "filters":[{"field":"rank_from_bottom","operator":"LTE","value":"1","values":[]}],
                "calculation":{"type":"DIRECT","baseline":null},
                "orderBy":[{"field":"ZB011","direction":"DESC"}],"limit":1,
                "output":{"columns":["bank_organization","ZB011"],"orderSensitive":true}}
                """;
    }

    private String provinceWideRankingRequirementsJson(String metric, String date, String intent,
            boolean namedOrganization) {
        String organizations = namedOrganization ? "[\"ORG004\"]" : "[]";
        return """
                {"version":"1.0","action":"EXECUTE","intent":"%s",
                "metricCodes":["%s"],"derivedMetrics":[],"organizationCodes":%s,
                "time":{"startDate":"%s","endDate":"%s","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},
                "filters":[{"field":"rank","operator":"LTE","value":"1","values":[]}],
                "requiredLimit":1,"answerFactTypes":["VALUE","RANK"],"clarification":null}
                """
                .formatted(intent, metric, organizations, date, date);
    }

    private String provinceWideRankingPlanJson(String metric, String date, String direction) {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"RANKING",
                "metrics":[{"bizName":"%s","aggregation":"DEFAULT","alias":null}],
                "derivedMetrics":[],"dimensions":["bank_organization"],"organizations":[],
                "time":{"startDate":"%s","endDate":"%s","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},
                "filters":[{"field":"rank","operator":"LTE","value":"1","values":[]}],
                "calculation":{"type":"DIRECT","baseline":null},"orderBy":[{"field":"%s","direction":"%s"}],"limit":1,
                "output":{"columns":["bank_organization","%s"],"orderSensitive":true}}
                """
                .formatted(metric, date, date, metric, direction, metric);
    }

    private String depositEqualityRequirementsJson(String intent) {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"%s",
                "metricCodes":["ZB003","ZB004","ZB001"],"derivedMetrics":[],"organizationCodes":["ORG003"],
                "time":{"startDate":"2025-12-31","endDate":"2025-12-31","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},
                "filters":[],"requiredLimit":null,"answerFactTypes":["VALUE","GAP_VALUE"],"clarification":null}
                """
                .formatted(intent);
    }

    private String depositEqualityPlanJson() {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"POINT_QUERY",
                "metrics":[{"bizName":"ZB003","aggregation":"DEFAULT","alias":null},{"bizName":"ZB004","aggregation":"DEFAULT","alias":null},{"bizName":"ZB001","aggregation":"DEFAULT","alias":null}],
                "derivedMetrics":[],"dimensions":["bank_organization"],"organizations":[{"code":"ORG003","bizName":null}],
                "time":{"startDate":"2025-12-31","endDate":"2025-12-31","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},
                "filters":[],"calculation":{"type":"DIRECT","baseline":null},"orderBy":[],"limit":null,
                "output":{"columns":["bank_organization","ZB003","ZB004","ZB001"],"orderSensitive":true}}
                """;
    }

    private String loanStructureShareRequirementsJson(String intent) {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"%s",
                "metricCodes":["ZB006","ZB005","ZB002"],"derivedMetrics":[],"organizationCodes":["ORG007"],
                "time":{"startDate":"2026-03-31","endDate":"2026-03-31","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},
                "filters":[],"requiredLimit":null,"answerFactTypes":["VALUE","RATIO_VALUE"],"clarification":null}
                """
                .formatted(intent);
    }

    private String loanStructureSharePlanJson() {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"POINT_QUERY",
                "metrics":[{"bizName":"ZB006","aggregation":"DEFAULT","alias":null},{"bizName":"ZB005","aggregation":"DEFAULT","alias":null},{"bizName":"ZB002","aggregation":"DEFAULT","alias":null}],
                "derivedMetrics":[],"dimensions":["bank_organization"],"organizations":[{"code":"ORG007","bizName":null}],
                "time":{"startDate":"2026-03-31","endDate":"2026-03-31","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},
                "filters":[],"calculation":{"type":"DIRECT","baseline":null},"orderBy":[],"limit":null,
                "output":{"columns":["bank_organization","ZB006","ZB005","ZB002"],"orderSensitive":true}}
                """;
    }

    private String incompleteLoanToDepositRatioRequirementsJson() {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"POINT_QUERY",
                "metricCodes":["ZB002"],"derivedMetrics":[],"organizationCodes":["ORG003"],
                "time":{"startDate":"2026-03-31","endDate":"2026-03-31","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},
                "filters":[],"requiredLimit":null,"answerFactTypes":["VALUE"],"clarification":null}
                """;
    }

    private String loanToDepositRatioRequirementsJson() {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"RATIO",
                "metricCodes":["ZB002","ZB001"],
                "derivedMetrics":[{"metricCode":"DERIVED_ZB002_DIV_ZB001","numerator":"ZB002","denominator":"ZB001","name":"存贷比"}],
                "organizationCodes":["ORG003"],
                "time":{"startDate":"2026-03-31","endDate":"2026-03-31","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},
                "filters":[],"requiredLimit":null,"answerFactTypes":["RATIO_VALUE"],"clarification":null}
                """;
    }

    private String loanToDepositRatioPlanJson() {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"RATIO",
                "metrics":[{"bizName":"ZB002","aggregation":"DEFAULT","alias":null},{"bizName":"ZB001","aggregation":"DEFAULT","alias":null}],
                "derivedMetrics":[],"dimensions":["bank_organization"],"organizations":[{"code":"ORG003","bizName":null}],
                "time":{"startDate":"2026-03-31","endDate":"2026-03-31","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},
                "filters":[],"calculation":{"type":"RATIO","baseline":"ZB001"},"orderBy":[],"limit":null,
                "output":{"columns":["bank_organization","ZB002","ZB001"],"orderSensitive":true}}
                """;
    }

    private String coveragePairRequirementsJson(String intent) {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"%s",
                "metricCodes":["ZB015","ZB016"],"derivedMetrics":[],"organizationCodes":["ORG006"],
                "time":{"startDate":"2026-01-31","endDate":"2026-01-31","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},
                "filters":[],"requiredLimit":null,"answerFactTypes":["VALUE","GAP_VALUE"],"clarification":null}
                """
                .formatted(intent);
    }

    private String coveragePairPlanJson() {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"POINT_QUERY",
                "metrics":[{"bizName":"ZB015","aggregation":"DEFAULT","alias":null},{"bizName":"ZB016","aggregation":"DEFAULT","alias":null}],
                "derivedMetrics":[],"dimensions":["bank_organization"],"organizations":[{"code":"ORG006","bizName":null}],
                "time":{"startDate":"2026-01-31","endDate":"2026-01-31","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},
                "filters":[],"calculation":{"type":"DIRECT","baseline":null},"orderBy":[],"limit":null,
                "output":{"columns":["bank_organization","ZB015","ZB016"],"orderSensitive":false}}
                """;
    }

    private String dailyAverageRequirementsJson() {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"AGGREGATION",
                "metricCodes":["ZB002"],"derivedMetrics":[],"organizationCodes":["ORG009"],
                "time":{"startDate":"2026-01-01","endDate":"2026-12-31","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},
                "filters":[],"requiredLimit":null,"answerFactTypes":["VALUE"],"clarification":null}
                """;
    }

    private String loanShareRequirementsJson(String intent) {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"%s",
                "metricCodes":["ZB014","ZB002"],"derivedMetrics":[],"organizationCodes":["ORG011"],
                "time":{"startDate":"2025-12-31","endDate":"2025-12-31","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},
                "filters":[],"requiredLimit":null,"answerFactTypes":["RATIO_VALUE"],"clarification":null}
                """
                .formatted(intent);
    }

    private String loanSharePlanJson() {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"RATIO",
                "metrics":[{"bizName":"ZB014","aggregation":"DEFAULT","alias":null},{"bizName":"ZB002","aggregation":"DEFAULT","alias":null}],
                "derivedMetrics":[],"dimensions":["bank_organization"],"organizations":[{"code":"ORG011","bizName":null}],
                "time":{"startDate":"2025-12-31","endDate":"2025-12-31","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},
                "filters":[],"calculation":{"type":"RATIO","baseline":"ZB002"},"orderBy":[],"limit":null,
                "output":{"columns":["bank_organization","ZB014","ZB002"],"orderSensitive":false}}
                """;
    }

    private String genericPointRatioRequirementsJson(String intent) {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"%s",
                "metricCodes":["ZB008","ZB009"],"derivedMetrics":[],"organizationCodes":["ORG001"],
                "time":{"startDate":"2026-04-30","endDate":"2026-04-30","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},
                "filters":[],"requiredLimit":null,"answerFactTypes":["RATIO_VALUE"],"clarification":null}
                """
                .formatted(intent);
    }

    private String genericPointRatioPlanJson() {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"RATIO",
                "metrics":[{"bizName":"ZB008","aggregation":"DEFAULT","alias":null},{"bizName":"ZB009","aggregation":"DEFAULT","alias":null}],
                "derivedMetrics":[],"dimensions":["bank_organization"],"organizations":[{"code":"ORG001","bizName":null}],
                "time":{"startDate":"2026-04-30","endDate":"2026-04-30","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},
                "filters":[],"calculation":{"type":"RATIO","baseline":"ZB009"},"orderBy":[],"limit":null,
                "output":{"columns":["bank_organization","ZB008","ZB009"],"orderSensitive":true}}
                """;
    }

    private String endpointChangeRequirementsJson(String intent) {
        boolean change = "CHANGE".equals(intent);
        String time = change
                ? "{\"startDate\":\"2025-12-31\",\"endDate\":\"2025-12-31\",\"granularity\":\"DAY\",\"comparison\":\"PERIOD_OVER_PERIOD\",\"baselineStartDate\":\"2025-06-30\",\"baselineEndDate\":\"2025-06-30\"}"
                : "{\"startDate\":\"2025-06-30\",\"endDate\":\"2025-12-31\",\"granularity\":\"DAY\",\"comparison\":\"NONE\",\"baselineStartDate\":null,\"baselineEndDate\":null}";
        return """
                {"version":"1.0","action":"EXECUTE","intent":"%s",
                "metricCodes":["ZB001","ZB002","ZB013","ZB011"],"derivedMetrics":[],"organizationCodes":["ORG008"],
                "time":%s,
                "filters":[],"requiredLimit":null,"answerFactTypes":["VALUE","TREND_DIRECTION"],"clarification":null}
                """
                .formatted(intent, time);
    }

    private String endpointChangePlanJson() {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"CHANGE",
                "metrics":[{"bizName":"ZB001","aggregation":"DEFAULT","alias":null},{"bizName":"ZB002","aggregation":"DEFAULT","alias":null},{"bizName":"ZB013","aggregation":"DEFAULT","alias":null},{"bizName":"ZB011","aggregation":"DEFAULT","alias":null}],
                "derivedMetrics":[],"dimensions":["bank_organization"],"organizations":[{"code":"ORG008","bizName":null}],
                "time":{"startDate":"2025-12-31","endDate":"2025-12-31","granularity":"DAY","comparison":"PERIOD_OVER_PERIOD","baselineStartDate":"2025-06-30","baselineEndDate":"2025-06-30"},
                "filters":[],"calculation":{"type":"CHANGE","baseline":null},"orderBy":[],"limit":null,
                "output":{"columns":["bank_organization","ZB001","ZB002","ZB013","ZB011"],"orderSensitive":false}}
                """;
    }

    private String quarterlyTrendRequirementsJson() {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"TREND",
                "metricCodes":["ZB001"],"derivedMetrics":[],"organizationCodes":["ORG008"],
                "time":{"startDate":"2025-03-31","endDate":"2025-12-31","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},
                "filters":[],"requiredLimit":null,"answerFactTypes":["VALUE","TREND_DIRECTION"],"clarification":null}
                """;
    }

    private String quarterlyTrendPlanJson() {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"TREND",
                "metrics":[{"bizName":"ZB001","aggregation":"DEFAULT","alias":null}],
                "derivedMetrics":[],"dimensions":["bank_data_date"],"organizations":[{"code":"ORG008","bizName":null}],
                "time":{"startDate":"2025-03-31","endDate":"2025-12-31","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},
                "filters":[],"calculation":{"type":"DIRECT","baseline":null},
                "orderBy":[{"field":"bank_data_date","direction":"ASC"}],"limit":null,
                "output":{"columns":["bank_data_date","ZB001"],"orderSensitive":true}}
                """;
    }

    private String multiOrganizationTotalRequirementsJson() {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"AGGREGATION",
                "metricCodes":["ZB001"],"derivedMetrics":[],"organizationCodes":["ORG001","ORG002"],
                "time":{"startDate":"2025-12-31","endDate":"2025-12-31","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},
                "filters":[],"requiredLimit":null,"answerFactTypes":["VALUE"],"clarification":null}
                """;
    }

    private String multiOrganizationTotalPlanJson(boolean retainOrganization) {
        String dimensions = retainOrganization ? "[\"bank_organization\"]" : "[]";
        String output = retainOrganization ? "[\"bank_organization\",\"ZB001\"]" : "[\"ZB001\"]";
        return """
                {"version":"1.0","action":"EXECUTE","intent":"AGGREGATION",
                "metrics":[{"bizName":"ZB001","aggregation":"DEFAULT","alias":null}],
                "derivedMetrics":[],"dimensions":%s,"organizations":[{"code":"ORG001","bizName":null},{"code":"ORG002","bizName":null}],
                "time":{"startDate":"2025-12-31","endDate":"2025-12-31","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},
                "filters":[],"calculation":{"type":"DIRECT","baseline":null},"orderBy":[],"limit":null,
                "output":{"columns":%s,"orderSensitive":true}}
                """
                .formatted(dimensions, output);
    }

    private String dailyAveragePlanJson(String aggregationMode) {
        String mode = aggregationMode == null ? "null" : "\"" + aggregationMode + "\"";
        return """
                {"version":"1.0","action":"EXECUTE","intent":"AGGREGATION",
                "metrics":[{"bizName":"ZB002","aggregation":"AVG","alias":null}],
                "derivedMetrics":[],"dimensions":["bank_organization"],"organizations":[{"code":"ORG009","bizName":null}],
                "time":{"startDate":"2026-01-01","endDate":"2026-12-31","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},
                "filters":[],"calculation":{"type":"DIRECT","baseline":null},"orderBy":[],"limit":null,
                "output":{"columns":["bank_organization","ZB002"],"orderSensitive":false,"aggregationMode":%s}}
                """
                .formatted(mode);
    }

    private String invalidChangeRequirementsJson() {
        return validChangeRequirementsJson().replace(
                "\"startDate\":\"2026-03-31\"," + "\"endDate\":\"2026-03-31\"",
                "\"startDate\":\"2024-12-31\"," + "\"endDate\":\"2026-03-31\"");
    }

    private String validChangeRequirementsJson() {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"CHANGE",
                "metricCodes":["ZB001"],"derivedMetrics":[],"organizationCodes":[],
                "time":{"startDate":"2026-03-31","endDate":"2026-03-31","granularity":"DAY","comparison":"PERIOD_OVER_PERIOD","baselineStartDate":"2024-12-31","baselineEndDate":"2024-12-31"},
                "filters":[],"requiredLimit":3,"answerFactTypes":["CHANGE_RATE"],"clarification":null}
                """;
    }

    private String rankedGrowthRequirementsJson(String intent) {
        String comparison = "CHANGE".equals(intent) ? "PERIOD_OVER_PERIOD" : "NONE";
        return """
                {"version":"1.0","action":"EXECUTE","intent":"%s",
                "metricCodes":["ZB011"],"derivedMetrics":[],"organizationCodes":[],
                "time":{"startDate":"2026-04-30","endDate":"2026-04-30","granularity":"DAY","comparison":"%s","baselineStartDate":"2024-12-31","baselineEndDate":"2024-12-31"},
                "filters":[],"requiredLimit":3,"answerFactTypes":["VALUE","RANK","CHANGE_RATE"],"clarification":null}
                """
                .formatted(intent, comparison);
    }

    private String rankedGrowthPlanJson() {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"CHANGE",
                "metrics":[{"bizName":"ZB011","aggregation":"DEFAULT","alias":null}],
                "derivedMetrics":[],"dimensions":["bank_organization"],"organizations":[],
                "time":{"startDate":"2026-04-30","endDate":"2026-04-30","granularity":"DAY","comparison":"PERIOD_OVER_PERIOD","baselineStartDate":"2024-12-31","baselineEndDate":"2024-12-31"},
                "filters":[],"calculation":{"type":"CHANGE","baseline":null},
                "orderBy":[],"limit":3,
                "output":{"columns":["bank_organization","ZB011"],"orderSensitive":true}}
                """;
    }

    private String invalidStartOfYearRequirementsJson() {
        return validStartOfYearRequirementsJson().replace(
                "\"baselineStartDate\":\"2025-12-31\"," + "\"baselineEndDate\":\"2025-12-31\"",
                "\"baselineStartDate\":\"2026-01-01\"," + "\"baselineEndDate\":\"2026-01-01\"");
    }

    private String validStartOfYearRequirementsJson() {
        return validChangeRequirementsJson()
                .replace("\"comparison\":\"PERIOD_OVER_PERIOD\"",
                        "\"comparison\":\"START_OF_YEAR\"")
                .replace(
                        "\"baselineStartDate\":\"2024-12-31\","
                                + "\"baselineEndDate\":\"2024-12-31\"",
                        "\"baselineStartDate\":\"2025-12-31\","
                                + "\"baselineEndDate\":\"2025-12-31\"");
    }

    private String validChangePlanJson() {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"CHANGE",
                "metrics":[{"bizName":"ZB001","aggregation":"DEFAULT","alias":null}],
                "derivedMetrics":[],"dimensions":["bank_organization"],"organizations":[],
                "time":{"startDate":"2026-03-31","endDate":"2026-03-31","granularity":"DAY","comparison":"PERIOD_OVER_PERIOD","baselineStartDate":"2024-12-31","baselineEndDate":"2024-12-31"},
                "filters":[],"calculation":{"type":"CHANGE","baseline":null},"orderBy":[],"limit":3,
                "output":{"columns":["bank_organization","ZB001"],"orderSensitive":false}}
                """;
    }

    private String validStartOfYearPlanJson() {
        return validChangePlanJson()
                .replace("\"comparison\":\"PERIOD_OVER_PERIOD\"",
                        "\"comparison\":\"START_OF_YEAR\"")
                .replace(
                        "\"baselineStartDate\":\"2024-12-31\","
                                + "\"baselineEndDate\":\"2024-12-31\"",
                        "\"baselineStartDate\":\"2025-12-31\","
                                + "\"baselineEndDate\":\"2025-12-31\"");
    }

    private String validPlanJson() {
        return """
                {"version":"1.0","action":"EXECUTE","intent":"COMPARISON",
                "metrics":[{"bizName":"ZB001","aggregation":"DEFAULT","alias":null},{"bizName":"ZB002","aggregation":"DEFAULT","alias":null}],
                "derivedMetrics":[],"dimensions":["bank_organization"],"organizations":[{"code":"ORG004","bizName":null}],
                "time":{"startDate":"2025-07-31","endDate":"2025-07-31","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},
                "filters":[{"field":"benchmark","operator":"COMPARE","value":"PROVINCE_AVERAGE","values":[]}],
                "calculation":{"type":"DIRECT","baseline":null},"orderBy":[],"limit":null,
                "output":{"columns":["bank_organization","ZB001","ZB002"],"orderSensitive":false}}
                """;
    }

    private static class TestBankPlanGenStrategy extends BankPlanGenStrategy {
        private final ChatLanguageModel model;

        private TestBankPlanGenStrategy(ChatLanguageModel model) {
            this.model = model;
        }

        @Override
        protected ChatLanguageModel getChatLanguageModel(ChatModelConfig modelConfig) {
            return model;
        }
    }
}
