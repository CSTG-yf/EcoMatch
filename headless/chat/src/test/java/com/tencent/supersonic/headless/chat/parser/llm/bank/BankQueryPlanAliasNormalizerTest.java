package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.tencent.supersonic.headless.chat.intent.BankIntentType;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.SemanticIntentHints;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankQueryPlanAliasNormalizerTest {

    @Test
    void shouldRewriteChineseOutputColumnsToMetricCodes() {
        BankQueryPlan plan = BankQueryPlan.builder().intent(BankIntentType.POINT_QUERY)
                .metrics(List.of(BankQueryPlan.Metric.builder().bizName("ZB001")
                        .aggregation(BankQueryPlan.Aggregation.DEFAULT).build()))
                .organizations(List.of(BankQueryPlan.Organization.builder().code("ORG001").build()))
                .time(BankQueryPlan.TimeRange.builder().startDate(LocalDate.of(2025, 6, 15))
                        .endDate(LocalDate.of(2025, 6, 15))
                        .granularity(BankQueryPlan.TimeGranularity.DAY)
                        .comparison(BankQueryPlan.TimeComparison.NONE).build())
                .calculation(BankQueryPlan.Calculation.builder()
                        .type(BankQueryPlan.CalculationType.DIRECT).build())
                .output(BankQueryPlan.Output.builder().columns(List.of("各项存款余额"))
                        .orderSensitive(false).build())
                .build();

        BankQueryPlan normalized = BankQueryPlanAliasNormalizer.normalize(plan);

        assertEquals(List.of("ZB001"), normalized.getOutput().getColumns());
    }

    @Test
    void shouldPassValidatorAfterChineseOutputRewrite() {
        BankQueryPlan plan = BankQueryPlan.builder().version("1.0")
                .intent(BankIntentType.POINT_QUERY)
                .metrics(List.of(BankQueryPlan.Metric.builder().bizName("ZB001")
                        .aggregation(BankQueryPlan.Aggregation.DEFAULT).build()))
                .dimensions(List.of())
                .organizations(List.of(BankQueryPlan.Organization.builder().code("ORG001").build()))
                .time(BankQueryPlan.TimeRange.builder().startDate(LocalDate.of(2025, 6, 15))
                        .endDate(LocalDate.of(2025, 6, 15))
                        .granularity(BankQueryPlan.TimeGranularity.DAY)
                        .comparison(BankQueryPlan.TimeComparison.NONE).build())
                .filters(List.of())
                .calculation(BankQueryPlan.Calculation.builder()
                        .type(BankQueryPlan.CalculationType.DIRECT).build())
                .orderBy(List.of()).limit(null)
                .output(BankQueryPlan.Output.builder().columns(List.of("各项存款余额"))
                        .orderSensitive(false).build())
                .build();

        SemanticIntentHints hints = SemanticIntentHints.builder()
                .expectedIntent(BankIntentType.POINT_QUERY)
                .allowedMetrics(Set.of("ZB001", "ZB002"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date", "机构", "数据日期"))
                .requiredMetrics(Set.of("ZB001")).requiredOrganizationCodes(Set.of("ORG001"))
                .requiredStartDate(LocalDate.of(2025, 6, 15))
                .requiredEndDate(LocalDate.of(2025, 6, 15)).build();

        BankQueryPlan parsed = new BankQueryPlanResponseParser().parse(
                """
                        {"version":"1.0","intent":"POINT_QUERY","metrics":[{"bizName":"ZB001","aggregation":"DEFAULT"}],"dimensions":[],"organizations":[{"code":"ORG001"}],"time":{"startDate":"2025-06-15","endDate":"2025-06-15","granularity":"DAY","comparison":"NONE"},"filters":[],"calculation":{"type":"DIRECT"},"orderBy":[],"limit":null,"output":{"columns":["各项存款余额"],"orderSensitive":false}}
                        """,
                hints);

        assertEquals(List.of("ZB001"), parsed.getOutput().getColumns());
        assertTrue(new BankQueryPlanValidator().validate(parsed, hints).isValid());
    }
}
