package com.tencent.supersonic.headless.chat.parser.llm.bank;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BankResultProjectorTest {

    private final BankResultProjector projector = new BankResultProjector();

    @Test
    void shouldProjectSingleOrganizationPointQueryToStableLongForm() {
        BankResultProjector.Contract contract = BankResultProjector.Contract.builder()
                .type(BankResultProjector.ProjectionType.LONG_FORM)
                .organizationColumn("bank_organization")
                .organizationNames(Map.of("ORG008", "江苏省H市农商行"))
                .selectedOrganizationCodes(List.of("ORG008"))
                .metrics(List.of(BankResultProjector.MetricBinding.builder().semanticColumn("zb010")
                        .metricCode("ZB010").build()))
                .build();

        BankResultProjector.Projection projection =
                projector.project(contract, List.of(row("zb010", new BigDecimal("60.28"))));

        assertEquals(List.of("org_code", "org_name", "metric_code", "metric_value"),
                projection.getColumns());
        assertEquals(List.of(row("org_code", "ORG008", "org_name", "江苏省H市农商行", "metric_code",
                "ZB010", "metric_value", new BigDecimal("60.28"))), projection.getRows());
    }

    @Test
    void shouldAddStableRankPositionsAfterProjectingOrganizationRows() {
        BankResultProjector.Contract contract = BankResultProjector.Contract.builder()
                .type(BankResultProjector.ProjectionType.RANKED_LONG_FORM)
                .organizationColumn("bank_organization")
                .organizationNames(Map.of("ORG007", "江苏省G市农商行", "ORG003", "江苏省C市农商行"))
                .metrics(List.of(BankResultProjector.MetricBinding.builder().semanticColumn("zb004")
                        .metricCode("ZB004").build()))
                .build();

        BankResultProjector.Projection projection = projector.project(contract,
                List.of(row("bank_organization", "ORG007", "zb004", new BigDecimal("71.02")),
                        row("bank_organization", "ORG003", "zb004", new BigDecimal("66.10"))));

        assertEquals(
                List.of("org_code", "org_name", "metric_code", "metric_value", "rank_position"),
                projection.getColumns());
        assertEquals(
                List.of(row("org_code", "ORG007", "org_name", "江苏省G市农商行", "metric_code", "ZB004",
                        "metric_value", new BigDecimal("71.02"), "rank_position", 1),
                        row("org_code", "ORG003", "org_name", "江苏省C市农商行", "metric_code", "ZB004",
                                "metric_value", new BigDecimal("66.10"), "rank_position", 2)),
                projection.getRows());
    }

    @Test
    void shouldRankSelectedOrganizationAgainstAllOrganizationsForEachMetric() {
        BankResultProjector.Contract contract = BankResultProjector.Contract.builder()
                .type(BankResultProjector.ProjectionType.RANKED_LONG_FORM)
                .organizationColumn("bank_organization")
                .organizationNames(Map.of("ORG001", "A", "ORG002", "B", "ORG003", "C"))
                .selectedOrganizationCodes(List.of("ORG002"))
                .metrics(List.of(
                        BankResultProjector.MetricBinding.builder().semanticColumn("zb002")
                                .metricCode("ZB002").build(),
                        BankResultProjector.MetricBinding.builder().semanticColumn("zb013")
                                .metricCode("ZB013").build()))
                .build();

        BankResultProjector.Projection projection = projector.project(contract,
                List.of(row("bank_organization", "ORG001", "zb002", new BigDecimal("100"), "zb013",
                        new BigDecimal("1.20")),
                        row("bank_organization", "ORG002", "zb002", new BigDecimal("90"), "zb013",
                                new BigDecimal("0.89")),
                        row("bank_organization", "ORG003", "zb002", new BigDecimal("80"), "zb013",
                                new BigDecimal("0.70"))));

        assertEquals(
                List.of("metric_code", "org_code", "org_name", "metric_value", "rank_position"),
                projection.getColumns());
        assertEquals(
                List.of(row("org_code", "ORG002", "org_name", "B", "metric_code", "ZB002",
                        "metric_value", new BigDecimal("90"), "rank_position", 2),
                        row("org_code", "ORG002", "org_name", "B", "metric_code", "ZB013",
                                "metric_value", new BigDecimal("0.89"), "rank_position", 2)),
                projection.getRows());
    }

    @Test
    void shouldProjectRatioValuesToTheStableBankContract() {
        BankResultProjector.Contract contract = BankResultProjector.Contract.builder()
                .type(BankResultProjector.ProjectionType.RATIO)
                .organizationColumn("bank_organization")
                .organizationNames(Map.of("ORG004", "江苏省D市农商行"))
                .selectedOrganizationCodes(List.of("ORG004")).build();

        BankResultProjector.Projection projection = projector.project(contract,
                List.of(row("numerator_value", new BigDecimal("25.75"), "denominator_value",
                        new BigDecimal("48.50"), "ratio_percent", new BigDecimal("53.0928"))));

        assertEquals(List.of("org_code", "org_name", "numerator_value", "denominator_value",
                "ratio_percent"), projection.getColumns());
        assertEquals(List.of(row("org_code", "ORG004", "org_name", "江苏省D市农商行", "numerator_value",
                new BigDecimal("25.75"), "denominator_value", new BigDecimal("48.50"),
                "ratio_percent", new BigDecimal("53.0928"))), projection.getRows());
    }

    @Test
    void shouldProjectAnOrganizationComparisonWithOneSharedValueDifference() {
        BankResultProjector.Contract contract =
                BankResultProjector.Contract.builder()
                        .type(BankResultProjector.ProjectionType.COMPARISON)
                        .organizationColumn("bank_organization")
                        .organizationNames(Map.of("ORG010", "J", "ORG012", "L"))
                        .metrics(List.of(BankResultProjector.MetricBinding.builder()
                                .semanticColumn("metric_value").metricCode("ZB013").build()))
                        .build();

        BankResultProjector.Projection projection = projector.project(contract, List.of(
                row("bank_organization", "ORG010", "metric_value", new BigDecimal("0.77")),
                row("bank_organization", "ORG012", "metric_value", new BigDecimal("0.89"))));

        assertEquals(List.of("org_code", "org_name", "metric_value", "value_difference"),
                projection.getColumns());
        assertEquals(List.of(
                row("org_code", "ORG012", "org_name", "L", "metric_value", new BigDecimal("0.89"),
                        "value_difference", new BigDecimal("0.12")),
                row("org_code", "ORG010", "org_name", "J", "metric_value", new BigDecimal("0.77"),
                        "value_difference", new BigDecimal("0.12"))),
                projection.getRows());
    }

    @Test
    void shouldProjectProvinceAverageThresholdRowsToTheStableBankContract() {
        BankResultProjector.Contract contract = BankResultProjector.Contract.builder()
                .type(BankResultProjector.ProjectionType.PROVINCIAL_AVERAGE_THRESHOLD)
                .organizationColumn("bank_organization")
                .organizationNames(Map.of("ORG003", "C", "ORG004", "D")).build();

        BankResultProjector.Projection projection = projector.project(contract,
                List.of(row("bank_organization", "ORG003", "metric_value", new BigDecimal("116.02"),
                        "provincial_average", new BigDecimal("72.73307692307692"),
                        "meets_condition", 1),
                        row("bank_organization", "ORG004", "metric_value", new BigDecimal("54.79"),
                                "provincial_average", new BigDecimal("72.73307692307692"),
                                "meets_condition", 0)));

        assertEquals(List.of("org_code", "org_name", "metric_value", "provincial_average",
                "meets_condition"), projection.getColumns());
        assertEquals(
                List.of(row("org_code", "ORG003", "org_name", "C", "metric_value",
                        new BigDecimal("116.02"), "provincial_average",
                        new BigDecimal("72.73307692307692"), "meets_condition", 1),
                        row("org_code", "ORG004", "org_name", "D", "metric_value",
                                new BigDecimal("54.79"), "provincial_average",
                                new BigDecimal("72.73307692307692"), "meets_condition", 0)),
                projection.getRows());
    }

    @Test
    void shouldProjectAnAggregationSummaryWithTheMetricCodeFromTheContract() {
        BankResultProjector.Contract contract =
                BankResultProjector.Contract.builder()
                        .type(BankResultProjector.ProjectionType.AGGREGATION_SUMMARY)
                        .organizationColumn("bank_organization")
                        .organizationNames(Map.of("ORG011", "K"))
                        .metrics(List.of(BankResultProjector.MetricBinding.builder()
                                .semanticColumn("aggregate_value").metricCode("ZB013").build()))
                        .build();

        BankResultProjector.Projection projection = projector.project(contract,
                List.of(row("bank_organization", "ORG011", "aggregate_value",
                        new BigDecimal("1.27"), "min_value", new BigDecimal("1.27"), "max_value",
                        new BigDecimal("1.27"), "observation_count", 1)));

        assertEquals(List.of("org_code", "org_name", "metric_code", "aggregate_value", "min_value",
                "max_value", "observation_count"), projection.getColumns());
        assertEquals(List.of(row("org_code", "ORG011", "org_name", "K", "metric_code", "ZB013",
                "aggregate_value", new BigDecimal("1.27"), "min_value", new BigDecimal("1.27"),
                "max_value", new BigDecimal("1.27"), "observation_count", 1)),
                projection.getRows());
    }

    @Test
    void shouldProjectDateOrderedTrendWithAdjacentQuarterChanges() {
        BankResultProjector.Contract contract = BankResultProjector.Contract.builder()
                .type(BankResultProjector.ProjectionType.TREND).timeColumn("bank_data_date")
                .metrics(List.of(BankResultProjector.MetricBinding.builder().semanticColumn("ZB001")
                        .metricCode("ZB001").build()))
                .build();

        BankResultProjector.Projection projection = projector.project(contract,
                List.of(row("bank_data_date", "2025-09-30", "ZB001", new BigDecimal("54.79")),
                        row("bank_data_date", "2025-03-31", "ZB001", new BigDecimal("55.00")),
                        row("bank_data_date", "2025-06-30", "ZB001", new BigDecimal("54.27"))));

        assertEquals(List.of("data_date", "metric_value", "quarter_change"),
                projection.getColumns());
        assertEquals(List.of(
                row("data_date", "2025-03-31", "metric_value", new BigDecimal("55.00"),
                        "quarter_change", null),
                row("data_date", "2025-06-30", "metric_value", new BigDecimal("54.27"),
                        "quarter_change", new BigDecimal("-0.73")),
                row("data_date", "2025-09-30", "metric_value", new BigDecimal("54.79"),
                        "quarter_change", new BigDecimal("0.52"))),
                projection.getRows());
    }

    @Test
    void shouldRetainOnlyConfiguredQuarterEndDatesBeforeCalculatingTrendChanges() {
        BankResultProjector.Contract contract = BankResultProjector.Contract.builder()
                .type(BankResultProjector.ProjectionType.TREND).timeColumn("bank_data_date")
                .selectedDates(List.of("2025-03-31", "2025-06-30"))
                .metrics(List.of(BankResultProjector.MetricBinding.builder().semanticColumn("ZB001")
                        .metricCode("ZB001").build()))
                .build();

        BankResultProjector.Projection projection = projector.project(contract,
                List.of(row("bank_data_date", "2025-03-31", "ZB001", new BigDecimal("55.00")),
                        row("bank_data_date", "2025-04-01", "ZB001", new BigDecimal("55.80")),
                        row("bank_data_date", "2025-06-30", "ZB001", new BigDecimal("54.27"))));

        assertEquals(List.of(
                row("data_date", "2025-03-31", "metric_value", new BigDecimal("55.00"),
                        "quarter_change", null),
                row("data_date", "2025-06-30", "metric_value", new BigDecimal("54.27"),
                        "quarter_change", new BigDecimal("-0.73"))),
                projection.getRows());
    }

    private static Map<String, Object> row(Object... values) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            row.put((String) values[index], values[index + 1]);
        }
        return row;
    }
}
