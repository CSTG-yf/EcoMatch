package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.chat.intent.BankIntentType;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMReq;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMResp;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.SemanticIntentHints;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BankNl2SqlExecutionCoordinatorTest {

    @Test
    void shouldCompileAStructuredBankPlanToOneControlledS2SqlCandidate() {
        BankNl2SqlExecutionCoordinator coordinator =
                new BankNl2SqlExecutionCoordinator(new BankQueryPlanCompiler(),
                        request -> "SELECT bank_organization, SUM(ZB001) " + "FROM 银行指标数据集");

        BankNl2SqlExecutionCoordinator.ExecutionCandidate candidate = coordinator.coordinate(
                request(BankIntentType.RANKING, rankingPlan()), response(rankingPlan()));

        assertEquals("SELECT bank_organization, SUM(ZB001) FROM 银行指标数据集", candidate.getS2sql());
        assertEquals(BankQueryPlanCompiler.CompilationRoute.STRUCT, candidate.getRoute());
        assertEquals(List.of("bank_organization", "ZB001"), candidate.getOutputColumns());
        assertNotNull(candidate.getFingerprint());
        assertEquals("STRUCT", candidate.diagnostics().get("bank.nl2sql.route"));
        assertEquals(candidate.getFingerprint(),
                candidate.diagnostics().get("bank.nl2sql.fingerprint"));
    }

    @Test
    void shouldKeepATemplatePlanOnItsControlledS2SqlRoute() {
        BankQueryPlan plan = changePlan();
        BankNl2SqlExecutionCoordinator coordinator =
                new BankNl2SqlExecutionCoordinator(new BankQueryPlanCompiler(), request -> {
                    throw new AssertionError("template route must not render a structural request");
                });

        BankNl2SqlExecutionCoordinator.ExecutionCandidate candidate =
                coordinator.coordinate(request(BankIntentType.CHANGE, plan), response(plan));

        assertEquals(BankQueryPlanCompiler.CompilationRoute.S2SQL_TEMPLATE, candidate.getRoute());
        assertEquals(
                List.of("current_value", "baseline_value", "absolute_change", "percent_change"),
                candidate.diagnostics().get("bank.nl2sql.outputColumns"));
        assertEquals(
                List.of("current_value", "baseline_value", "absolute_change", "percent_change"),
                candidate.getOutputColumns());
    }

    private LLMReq request(BankIntentType intent, BankQueryPlan plan) {
        LLMReq request = new LLMReq();
        request.setSchema(schema());
        request.setSemanticIntentHints(SemanticIntentHints.builder().expectedIntent(intent)
                .allowedMetrics(Set.of("ZB001", "ZB002"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date"))
                .requiredMetrics(plan.getMetrics().stream().map(BankQueryPlan.Metric::getBizName)
                        .collect(java.util.stream.Collectors.toSet()))
                .requiredOrganizationCodes(
                        plan.getOrganizations().stream().map(BankQueryPlan.Organization::getCode)
                                .collect(java.util.stream.Collectors.toSet()))
                .requiredStartDate(LocalDate.of(2026, 3, 31))
                .requiredEndDate(LocalDate.of(2026, 3, 31)).requiredLimit(plan.getLimit())
                .maxLimit(100).build());
        return request;
    }

    private LLMResp response(BankQueryPlan plan) {
        LLMResp response = new LLMResp();
        response.setBankQueryPlan(plan);
        return response;
    }

    private LLMReq.LLMSchema schema() {
        LLMReq.LLMSchema schema = new LLMReq.LLMSchema();
        schema.setDataSetId(12L);
        schema.setDataSetName("银行指标数据集");
        schema.setMetrics(List.of(
                SchemaElement.builder().name("各项存款余额").bizName("ZB001").defaultAgg("SUM").build(),
                SchemaElement.builder().name("各项贷款余额").bizName("ZB002").defaultAgg("SUM").build()));
        schema.setDimensions(
                List.of(SchemaElement.builder().name("机构").bizName("bank_organization").build()));
        schema.setPartitionTime(
                SchemaElement.builder().name("数据日期").bizName("bank_data_date").build());
        return schema;
    }

    private BankQueryPlan rankingPlan() {
        return BankQueryPlan.builder().version(BankQueryPlan.CURRENT_VERSION)
                .intent(BankIntentType.RANKING).metrics(List.of(metric("ZB001")))
                .dimensions(List.of("bank_organization"))
                .organizations(List.of(organization("ORG004"))).time(time(null, null))
                .calculation(BankQueryPlan.Calculation.builder()
                        .type(BankQueryPlan.CalculationType.DIRECT).build())
                .orderBy(List.of(BankQueryPlan.OrderBy.builder().field("ZB001")
                        .direction(BankQueryPlan.SortDirection.DESC).build()))
                .limit(3)
                .output(BankQueryPlan.Output.builder()
                        .columns(List.of("bank_organization", "ZB001")).orderSensitive(true)
                        .build())
                .build();
    }

    private BankQueryPlan changePlan() {
        return BankQueryPlan.builder().version(BankQueryPlan.CURRENT_VERSION)
                .intent(BankIntentType.CHANGE).metrics(List.of(metric("ZB001")))
                .dimensions(List.of()).organizations(List.of(organization("ORG004")))
                .time(time(BankQueryPlan.TimeComparison.PERIOD_OVER_PERIOD,
                        LocalDate.of(2026, 2, 28)))
                .calculation(BankQueryPlan.Calculation.builder()
                        .type(BankQueryPlan.CalculationType.CHANGE).build())
                .orderBy(List.of()).output(BankQueryPlan.Output.builder().columns(List.of("ZB001"))
                        .orderSensitive(true).build())
                .build();
    }

    private BankQueryPlan.Metric metric(String bizName) {
        return BankQueryPlan.Metric.builder().bizName(bizName)
                .aggregation(BankQueryPlan.Aggregation.DEFAULT).build();
    }

    private BankQueryPlan.Organization organization(String code) {
        return BankQueryPlan.Organization.builder().code(code).build();
    }

    private BankQueryPlan.TimeRange time(BankQueryPlan.TimeComparison comparison,
            LocalDate baselineDate) {
        return BankQueryPlan.TimeRange.builder().startDate(LocalDate.of(2026, 3, 31))
                .endDate(LocalDate.of(2026, 3, 31)).granularity(BankQueryPlan.TimeGranularity.DAY)
                .comparison(comparison == null ? BankQueryPlan.TimeComparison.NONE : comparison)
                .baselineStartDate(baselineDate).baselineEndDate(baselineDate).build();
    }
}
