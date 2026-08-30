package com.tencent.supersonic.headless.chat.parser.llm.bank;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void shouldProjectPartialStructureShareRatioPercentAtTwoDecimalPlaces() {
        // ZB001 + one part (ZB003) is a partial composition group: the generic long-form share
        // path runs and must publish ratio_percent at the same 2-dp scale as the dedicated
        // deposit/loan structure share projectors.
        BankResultProjector.Contract contract = BankResultProjector.Contract.builder()
                .type(BankResultProjector.ProjectionType.LONG_FORM)
                .organizationColumn("bank_organization")
                .organizationNames(Map.of("ORG001", "江苏省A市农商行"))
                .selectedOrganizationCodes(List.of("ORG001"))
                .metrics(List.of(
                        BankResultProjector.MetricBinding.builder().semanticColumn("zb003")
                                .metricCode("ZB003").build(),
                        BankResultProjector.MetricBinding.builder().semanticColumn("zb001")
                                .metricCode("ZB001").build()))
                .build();

        BankResultProjector.Projection projection = projector.project(contract,
                List.of(row("bank_organization", "ORG001", "zb003", new BigDecimal("35.52"),
                        "zb001", new BigDecimal("100"))));

        assertEquals(2, projection.getRows().size());
        // BigDecimal.equals is scale-sensitive: 35.52 pins the 2-dp contract (a 15-dp divide
        // would produce 35.520000000000000 and must fail this assertion).
        assertEquals(new BigDecimal("35.52"), projection.getRows().get(0).get("ratio_percent"));
        assertEquals(new BigDecimal("100.00"), projection.getRows().get(1).get("ratio_percent"));
    }

    @Test
    void shouldProjectMultipleMetricChangeToTheStableOrganizationContract() {
        BankResultProjector.Contract contract = BankResultProjector.Contract.builder()
                .type(BankResultProjector.ProjectionType.MULTI_METRIC_CHANGE)
                .organizationColumn("bank_organization")
                .organizationNames(Map.of("ORG001", "江苏省A市农商行"))
                .selectedOrganizationCodes(List.of("ORG001")).build();

        BankResultProjector.Projection projection = projector.project(contract,
                List.of(row("metric_code", "zb001", "current_value", new BigDecimal("42.25"),
                        "baseline_value", new BigDecimal("41.78"), "absolute_change",
                        new BigDecimal("0.47"), "percent_change", new BigDecimal("1.12"))));

        assertEquals(List.of("org_code", "org_name", "metric_code", "current_value",
                "baseline_value", "absolute_change", "percent_change"), projection.getColumns());
        assertEquals(List.of(row("org_code", "ORG001", "org_name", "江苏省A市农商行", "metric_code",
                "ZB001", "current_value", new BigDecimal("42.25"), "baseline_value",
                new BigDecimal("41.78"), "absolute_change", new BigDecimal("0.47"),
                "percent_change", new BigDecimal("1.12"))), projection.getRows());
    }

    @Test
    void shouldKeepMomAndYoyIdentityInPublishedProjection() {
        BankResultProjector.Contract contract = BankResultProjector.Contract.builder()
                .type(BankResultProjector.ProjectionType.MOM_YOY_CHANGE)
                .organizationColumn("bank_organization")
                .timeColumn("bank_data_date")
                .selectedDates(List.of("2025-04-30", "2026-03-31", "2026-04-30"))
                .organizationNames(Map.of("ORG001", "江苏省A市农商行"))
                .selectedOrganizationCodes(List.of("ORG001"))
                .build();

        BankResultProjector.Projection projection = projector.project(contract,
                List.of(row("current_value", new BigDecimal("41.70"),
                        "mom_baseline_value", new BigDecimal("42.32"),
                        "yoy_baseline_value", new BigDecimal("42.05"))));

        assertEquals(List.of("comparison_type", "current_value", "baseline_value",
                "absolute_change", "percent_change"), projection.getColumns());
        assertEquals("MOM", projection.getRows().get(0).get("comparison_type"));
        assertEquals("YOY", projection.getRows().get(1).get("comparison_type"));
    }

    @Test
    void shouldProjectWideCurrentAndBaselineRowsToMultiMetricChange() {
        BankResultProjector.Contract contract = BankResultProjector.Contract.builder()
                .type(BankResultProjector.ProjectionType.MULTI_METRIC_CHANGE)
                .organizationColumn("bank_organization").timeColumn("bank_data_date")
                .selectedDates(List.of("2026-04-30", "2025-12-31"))
                .organizationNames(Map.of("ORG006", "江苏省F市农商行"))
                .selectedOrganizationCodes(List.of("ORG006"))
                .metrics(List.of(
                        BankResultProjector.MetricBinding.builder().semanticColumn("zb011")
                                .metricCode("ZB011").build(),
                        BankResultProjector.MetricBinding.builder().semanticColumn("zb012")
                                .metricCode("ZB012").build()))
                .build();

        BankResultProjector.Projection projection = projector.project(contract,
                List.of(row("bank_organization", "ORG006", "bank_data_date", "2025-12-31", "zb011",
                        new BigDecimal("2"), "zb012", new BigDecimal("4")),
                        row("bank_organization", "ORG006", "bank_data_date", "2026-04-30", "zb011",
                                new BigDecimal("3"), "zb012", new BigDecimal("3"))));

        assertEquals(2, projection.getRows().size());
        assertEquals("ZB011", projection.getRows().get(0).get("metric_code"));
        assertEquals(new BigDecimal("1"), projection.getRows().get(0).get("absolute_change"));
        assertEquals(new BigDecimal("50.000000000000000"),
                projection.getRows().get(0).get("percent_change"));
        assertEquals(new BigDecimal("-1"), projection.getRows().get(1).get("absolute_change"));
    }

    @Test
    void shouldAggregateEveryObservationInsideMultiMetricChangeRanges() {
        BankResultProjector.Contract contract =
                BankResultProjector.Contract.builder()
                        .type(BankResultProjector.ProjectionType.MULTI_METRIC_CHANGE)
                        .organizationColumn("bank_organization").timeColumn("bank_data_date")
                        .selectedDates(
                                List.of("2026-04-01", "2026-04-30", "2025-04-01", "2025-04-30"))
                        .organizationNames(Map.of("ORG006", "江苏省F市农商行"))
                        .selectedOrganizationCodes(List.of("ORG006"))
                        .metrics(List.of(BankResultProjector.MetricBinding.builder()
                                .semanticColumn("metric_value_0").metricCode("ZB011").build()))
                        .build();

        BankResultProjector.Projection projection = projector.project(contract,
                List.of(row("bank_organization", "ORG006", "bank_data_date", "2025-04-01",
                        "metric_value_0", new BigDecimal("2")),
                        row("bank_organization", "ORG006", "bank_data_date", "2025-04-30",
                                "metric_value_0", new BigDecimal("3")),
                        row("bank_organization", "ORG006", "bank_data_date", "2026-04-01",
                                "metric_value_0", new BigDecimal("7")),
                        row("bank_organization", "ORG006", "bank_data_date", "2026-04-30",
                                "metric_value_0", new BigDecimal("8"))));

        assertTrue(projection.isApplied());
        assertEquals(new BigDecimal("15"), projection.getRows().get(0).get("current_value"));
        assertEquals(new BigDecimal("5"), projection.getRows().get(0).get("baseline_value"));
        assertEquals(new BigDecimal("10"), projection.getRows().get(0).get("absolute_change"));
        assertEquals(new BigDecimal("200.000000000000000"),
                projection.getRows().get(0).get("percent_change"));
    }

    @Test
    void shouldKeepProvinceWideChangeRowsInPublishedMetricOrganizationOrder() {
        BankResultProjector.Contract contract = BankResultProjector.Contract.builder()
                .type(BankResultProjector.ProjectionType.MULTI_METRIC_CHANGE)
                .organizationColumn("bank_organization")
                .organizationNames(Map.of("ORG001", "A", "ORG002", "B"))
                .selectedOrganizationCodes(List.of())
                .metrics(List.of(BankResultProjector.MetricBinding.builder().semanticColumn("zb001")
                        .metricCode("ZB001").build()))
                .build();

        BankResultProjector.Projection projection = projector.project(contract,
                List.of(row("bank_organization", "ORG002", "metric_code", "ZB001", "current_value",
                        new BigDecimal("52"), "baseline_value", new BigDecimal("50"),
                        "absolute_change", new BigDecimal("2"), "percent_change",
                        new BigDecimal("4")),
                        row("bank_organization", "ORG001", "metric_code", "ZB001", "current_value",
                                new BigDecimal("42"), "baseline_value", new BigDecimal("41"),
                                "absolute_change", new BigDecimal("1"), "percent_change",
                                new BigDecimal("2"))));

        assertEquals(List.of("ORG001", "ORG002"),
                projection.getRows().stream().map(row -> row.get("org_code")).toList());
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
    void shouldRankWithinMultipleSelectedOrganizations() {
        BankResultProjector.Contract contract =
                BankResultProjector.Contract.builder()
                        .type(BankResultProjector.ProjectionType.RANKED_LONG_FORM)
                        .organizationColumn("bank_organization")
                        .organizationNames(
                                Map.of("ORG001", "A", "ORG002", "B", "ORG003", "C", "ORG004", "D"))
                        .selectedOrganizationCodes(List.of("ORG001", "ORG003", "ORG004"))
                        .metrics(List.of(BankResultProjector.MetricBinding.builder()
                                .semanticColumn("ZB001").metricCode("ZB001").build()))
                        .topRankLimit(1).build();

        BankResultProjector.Projection projection = projector.project(contract,
                List.of(row("bank_organization", "ORG001", "ZB001", new BigDecimal("50")),
                        row("bank_organization", "ORG002", "ZB001", new BigDecimal("100")),
                        row("bank_organization", "ORG003", "ZB001", new BigDecimal("70")),
                        row("bank_organization", "ORG004", "ZB001", new BigDecimal("60"))));

        assertEquals(
                List.of(row("org_code", "ORG003", "org_name", "C", "metric_code", "ZB001",
                        "metric_value", new BigDecimal("70"), "rank_position", 1)),
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
    void shouldProjectRankedLongFormToRequestedTopAndBottomSlices() {
        BankResultProjector.Contract contract = BankResultProjector.Contract.builder()
                .type(BankResultProjector.ProjectionType.RANKED_LONG_FORM)
                .organizationColumn("bank_organization")
                .organizationNames(Map.of("ORG001", "A", "ORG002", "B", "ORG003", "C", "ORG004",
                        "D", "ORG005", "E"))
                .metrics(List.of(BankResultProjector.MetricBinding.builder().semanticColumn("ZB001")
                        .metricCode("ZB001").build()))
                .topRankLimit(2).bottomRankLimit(2).build();

        BankResultProjector.Projection projection = projector.project(contract,
                List.of(row("bank_organization", "ORG001", "ZB001", new BigDecimal("5")),
                        row("bank_organization", "ORG002", "ZB001", new BigDecimal("4")),
                        row("bank_organization", "ORG003", "ZB001", new BigDecimal("3")),
                        row("bank_organization", "ORG004", "ZB001", new BigDecimal("2")),
                        row("bank_organization", "ORG005", "ZB001", new BigDecimal("1"))));

        assertEquals(
                List.of(row("org_code", "ORG001", "org_name", "A", "metric_code", "ZB001",
                        "metric_value", new BigDecimal("5"), "rank_position", 1),
                        row("org_code", "ORG002", "org_name", "B", "metric_code", "ZB001",
                                "metric_value", new BigDecimal("4"), "rank_position", 2),
                        row("org_code", "ORG004", "org_name", "D", "metric_code", "ZB001",
                                "metric_value", new BigDecimal("2"), "rank_position", 4),
                        row("org_code", "ORG005", "org_name", "E", "metric_code", "ZB001",
                                "metric_value", new BigDecimal("1"), "rank_position", 5)),
                projection.getRows());
    }

    @Test
    void shouldOrderBottomOnlyRankingByRankPositionDescending() {
        // Gold 后N名: worst first (rank_position DESC). ZB013 is lower-is-better, so natural
        // rank ASC is low values first; bottom-3 of five is ranks 3,4,5 then reversed to 5,4,3.
        BankResultProjector.Contract contract = BankResultProjector.Contract.builder()
                .type(BankResultProjector.ProjectionType.RANKED_LONG_FORM)
                .organizationColumn("bank_organization")
                .organizationNames(Map.of("ORG001", "A", "ORG002", "B", "ORG003", "C", "ORG004",
                        "D", "ORG005", "E"))
                .metrics(List.of(BankResultProjector.MetricBinding.builder().semanticColumn("ZB013")
                        .metricCode("ZB013").build()))
                .bottomRankLimit(3).build();

        BankResultProjector.Projection projection = projector.project(contract,
                List.of(row("bank_organization", "ORG001", "ZB013", new BigDecimal("1.0")),
                        row("bank_organization", "ORG002", "ZB013", new BigDecimal("1.1")),
                        row("bank_organization", "ORG003", "ZB013", new BigDecimal("1.2")),
                        row("bank_organization", "ORG004", "ZB013", new BigDecimal("1.3")),
                        row("bank_organization", "ORG005", "ZB013", new BigDecimal("1.4"))));

        assertEquals(
                List.of(row("org_code", "ORG005", "org_name", "E", "metric_code", "ZB013",
                        "metric_value", new BigDecimal("1.4"), "rank_position", 5),
                        row("org_code", "ORG004", "org_name", "D", "metric_code", "ZB013",
                                "metric_value", new BigDecimal("1.3"), "rank_position", 4),
                        row("org_code", "ORG003", "org_name", "C", "metric_code", "ZB013",
                                "metric_value", new BigDecimal("1.2"), "rank_position", 3)),
                projection.getRows());
    }

    @Test
    void shouldAverageDailyValuesBeforeProjectingTopAndBottomRanks() {
        BankResultProjector.Contract contract =
                BankResultProjector.Contract.builder()
                        .type(BankResultProjector.ProjectionType.DAILY_AVERAGE_RANKING)
                        .organizationColumn("bank_organization")
                        .organizationNames(Map.of("ORG001", "A", "ORG002", "B", "ORG003", "C"))
                        .metrics(List.of(BankResultProjector.MetricBinding.builder()
                                .semanticColumn("ZB001").metricCode("ZB001").build()))
                        .topRankLimit(1).bottomRankLimit(1).build();

        BankResultProjector.Projection projection = projector.project(contract,
                List.of(row("bank_organization", "ORG001", "ZB001", new BigDecimal("4")),
                        row("bank_organization", "ORG001", "ZB001", new BigDecimal("6")),
                        row("bank_organization", "ORG002", "ZB001", new BigDecimal("2")),
                        row("bank_organization", "ORG002", "ZB001", new BigDecimal("4")),
                        row("bank_organization", "ORG003", "ZB001", new BigDecimal("1")),
                        row("bank_organization", "ORG003", "ZB001", new BigDecimal("3"))));

        assertEquals(List.of(
                row("org_code", "ORG001", "org_name", "A", "metric_code", "ZB001", "metric_value",
                        new BigDecimal("5.000000000000000"), "rank_position", 1),
                row("org_code", "ORG003", "org_name", "C", "metric_code", "ZB001", "metric_value",
                        new BigDecimal("2.000000000000000"), "rank_position", 3)),
                projection.getRows());
    }

    @Test
    void shouldProjectProvinceAverageThresholdRowsToTheStableBankContract() {
        // Gold M-16 is point aggregation summary for the selected org only.
        BankResultProjector.Contract contract =
                BankResultProjector.Contract.builder()
                        .type(BankResultProjector.ProjectionType.PROVINCIAL_AVERAGE_THRESHOLD)
                        .organizationColumn("bank_organization")
                        .organizationNames(Map.of("ORG003", "C", "ORG004", "D"))
                        .selectedOrganizationCodes(List.of("ORG003"))
                        .metrics(List.of(BankResultProjector.MetricBinding.builder()
                                .semanticColumn("metric_value").metricCode("ZB013").build()))
                        .build();

        BankResultProjector.Projection projection = projector.project(contract,
                List.of(row("bank_organization", "ORG003", "metric_value", new BigDecimal("116.02"),
                        "provincial_average", new BigDecimal("72.73307692307692"),
                        "meets_condition", 1),
                        row("bank_organization", "ORG004", "metric_value", new BigDecimal("54.79"),
                                "provincial_average", new BigDecimal("72.73307692307692"),
                                "meets_condition", 0)));

        assertEquals(List.of("org_code", "org_name", "metric_code", "aggregate_value", "min_value",
                "max_value", "observation_count"), projection.getColumns());
        assertEquals(List.of(row("org_code", "ORG003", "org_name", "C", "metric_code", "ZB013",
                "aggregate_value", new BigDecimal("116.02"), "min_value", new BigDecimal("116.02"),
                "max_value", new BigDecimal("116.02"), "observation_count", 1)),
                projection.getRows());
    }

    @Test
    void shouldProjectProvinceWideThresholdWithAverageAndMeetsCondition() {
        // Gold S-19/M-40: multi-org province-wide threshold keeps provincial_average.
        BankResultProjector.Contract contract =
                BankResultProjector.Contract.builder()
                        .type(BankResultProjector.ProjectionType.PROVINCIAL_AVERAGE_THRESHOLD)
                        .organizationColumn("bank_organization")
                        .organizationNames(Map.of("ORG001", "A", "ORG002", "B"))
                        .selectedOrganizationCodes(List.of())
                        .metrics(List.of(BankResultProjector.MetricBinding.builder()
                                .semanticColumn("metric_value").metricCode("ZB002").build()))
                        .build();

        BankResultProjector.Projection projection = projector.project(contract, List.of(
                row("bank_organization", "ORG001", "metric_value", new BigDecimal("33.95"),
                        "provincial_average", new BigDecimal("59.2"), "meets_condition", 0),
                row("bank_organization", "ORG002", "metric_value", new BigDecimal("70.0"),
                        "provincial_average", new BigDecimal("59.2"), "meets_condition", 1)));

        assertEquals(List.of("org_code", "org_name", "metric_value", "provincial_average",
                "meets_condition"), projection.getColumns());
        assertEquals(List.of(
                row("org_code", "ORG001", "org_name", "A", "metric_value", new BigDecimal("33.95"),
                        "provincial_average", new BigDecimal("59.2"), "meets_condition", 0),
                row("org_code", "ORG002", "org_name", "B", "metric_value", new BigDecimal("70.0"),
                        "provincial_average", new BigDecimal("59.2"), "meets_condition", 1)),
                projection.getRows());
    }

    @Test
    void shouldDeriveMultiMetricProvinceAverageFromFullPopulationAggregationRows() {
        BankResultProjector.Contract contract = BankResultProjector.Contract.builder()
                .type(BankResultProjector.ProjectionType.MULTI_METRIC_PROVINCIAL_AVERAGE)
                .organizationColumn("bank_organization")
                .organizationNames(Map.of("ORG001", "A", "ORG002", "B", "ORG003", "C"))
                .selectedOrganizationCodes(List.of("ORG002"))
                .metrics(List.of(
                        BankResultProjector.MetricBinding.builder().semanticColumn("metric_value")
                                .metricCode("ZB001").build(),
                        BankResultProjector.MetricBinding.builder().semanticColumn("metric_value")
                                .metricCode("ZB013").build()))
                .build();

        BankResultProjector.Projection projection = projector.project(contract,
                List.of(row("bank_organization", "ORG001", "metric_code", "ZB001",
                        "aggregate_value", new BigDecimal("20")),
                        row("bank_organization", "ORG002", "metric_code", "ZB001",
                                "aggregate_value", new BigDecimal("10")),
                        row("bank_organization", "ORG003", "metric_code", "ZB001",
                                "aggregate_value", new BigDecimal("30")),
                        row("bank_organization", "ORG001", "metric_code", "ZB013",
                                "aggregate_value", new BigDecimal("1.0")),
                        row("bank_organization", "ORG002", "metric_code", "ZB013",
                                "aggregate_value", new BigDecimal("1.5")),
                        row("bank_organization", "ORG003", "metric_code", "ZB013",
                                "aggregate_value", new BigDecimal("2.0"))));

        assertEquals(List.of("org_code", "org_name", "metric_code", "metric_value",
                "provincial_average", "gap_value", "absolute_gap"), projection.getColumns());
        assertEquals(List.of(
                row("org_code", "ORG002", "org_name", "B", "metric_code", "ZB001", "metric_value",
                        new BigDecimal("10"), "provincial_average",
                        new BigDecimal("20.000000000000000"), "gap_value",
                        new BigDecimal("-10.000000000000000"), "absolute_gap",
                        new BigDecimal("10.000000000000000")),
                row("org_code", "ORG002", "org_name", "B", "metric_code", "ZB013", "metric_value",
                        new BigDecimal("1.5"), "provincial_average",
                        new BigDecimal("1.500000000000000"), "gap_value", new BigDecimal("0E-15"),
                        "absolute_gap", new BigDecimal("0E-15"))),
                projection.getRows());
    }

    @Test
    void shouldProjectMultiMetricProvinceAverageThresholdRowsWithMeetsCondition() {
        // W4a defect 2: threshold intent keeps the SQL-computed per-metric meets_condition fact
        // (1 above / 0 equal-or-below) alongside value, provincial average and gap.
        BankResultProjector.Contract contract = BankResultProjector.Contract.builder()
                .type(BankResultProjector.ProjectionType.MULTI_METRIC_PROVINCIAL_AVERAGE_THRESHOLD)
                .organizationColumn("bank_organization")
                .organizationNames(Map.of("ORG001", "A", "ORG002", "B", "ORG003", "C"))
                .selectedOrganizationCodes(List.of())
                .metrics(List.of(
                        BankResultProjector.MetricBinding.builder().semanticColumn("metric_value")
                                .metricCode("ZB001").build(),
                        BankResultProjector.MetricBinding.builder().semanticColumn("metric_value")
                                .metricCode("ZB013").build()))
                .build();

        BankResultProjector.Projection projection = projector.project(contract, List.of(
                row("bank_organization", "ORG002", "metric_code", "ZB001", "metric_value",
                        new BigDecimal("90"), "provincial_average", new BigDecimal("70"),
                        "gap_value", new BigDecimal("20"), "meets_condition", 1),
                row("bank_organization", "ORG001", "metric_code", "ZB001", "metric_value",
                        new BigDecimal("50"), "provincial_average", new BigDecimal("70"),
                        "gap_value", new BigDecimal("-20"), "meets_condition", 0),
                row("bank_organization", "ORG001", "metric_code", "ZB013", "metric_value",
                        new BigDecimal("0.7"), "provincial_average", new BigDecimal("1.0"),
                        "gap_value", new BigDecimal("-0.3"), "meets_condition", 0),
                row("bank_organization", "ORG003", "metric_code", "ZB013", "metric_value",
                        new BigDecimal("1.2"), "provincial_average", new BigDecimal("1.0"),
                        "gap_value", new BigDecimal("0.2"), "meets_condition", 1)));

        assertTrue(projection.isApplied());
        assertEquals(List.of("org_code", "org_name", "metric_code", "metric_value",
                "provincial_average", "gap_value", "meets_condition"), projection.getColumns());
        assertEquals(List.of(
                row("org_code", "ORG001", "org_name", "A", "metric_code", "ZB001", "metric_value",
                        new BigDecimal("50"), "provincial_average", new BigDecimal("70"),
                        "gap_value", new BigDecimal("-20"), "meets_condition", 0),
                row("org_code", "ORG002", "org_name", "B", "metric_code", "ZB001", "metric_value",
                        new BigDecimal("90"), "provincial_average", new BigDecimal("70"),
                        "gap_value", new BigDecimal("20"), "meets_condition", 1),
                row("org_code", "ORG001", "org_name", "A", "metric_code", "ZB013", "metric_value",
                        new BigDecimal("0.7"), "provincial_average", new BigDecimal("1.0"),
                        "gap_value", new BigDecimal("-0.3"), "meets_condition", 0),
                row("org_code", "ORG003", "org_name", "C", "metric_code", "ZB013", "metric_value",
                        new BigDecimal("1.2"), "provincial_average", new BigDecimal("1.0"),
                        "gap_value", new BigDecimal("0.2"), "meets_condition", 1)),
                projection.getRows());
    }

    @Test
    void shouldProjectCompoundBenchmarkThresholdRowsWithTheWideOrdinalContract() {
        // Compound benchmark family (多指标复合基准阈值): the SQL pivoted each metric's value and
        // provincial average and AND-combined the direction-aware comparisons; the projection
        // passes those wide facts through with organization identity in canonical ordinal order.
        BankResultProjector.Contract contract = BankResultProjector.Contract.builder()
                .type(BankResultProjector.ProjectionType.COMPOUND_BENCHMARK_THRESHOLD)
                .organizationColumn("bank_organization")
                .organizationNames(Map.of("ORG001", "A", "ORG002", "B"))
                .selectedOrganizationCodes(List.of())
                .metrics(List.of(
                        BankResultProjector.MetricBinding.builder().semanticColumn("first_value")
                                .metricCode("ZB015").build(),
                        BankResultProjector.MetricBinding.builder().semanticColumn("second_value")
                                .metricCode("ZB013").build()))
                .build();

        BankResultProjector.Projection projection = projector.project(contract, List.of(
                row("bank_organization", "ORG001", "first_value", new BigDecimal("150.25"),
                        "first_average", new BigDecimal("180.25"), "second_value",
                        new BigDecimal("1.05"), "second_average", new BigDecimal("1.10"),
                        "meets_condition", 0),
                row("bank_organization", "ORG002", "first_value", new BigDecimal("201.75"),
                        "first_average", new BigDecimal("180.25"), "second_value",
                        new BigDecimal("0.80"), "second_average", new BigDecimal("1.10"),
                        "meets_condition", 1)));

        assertTrue(projection.isApplied());
        assertEquals(List.of("org_code", "org_name", "first_value", "first_average",
                "second_value", "second_average", "meets_condition"), projection.getColumns());
        assertEquals(List.of(
                row("org_code", "ORG001", "org_name", "A", "first_value",
                        new BigDecimal("150.25"), "first_average", new BigDecimal("180.25"),
                        "second_value", new BigDecimal("1.05"), "second_average",
                        new BigDecimal("1.10"), "meets_condition", 0),
                row("org_code", "ORG002", "org_name", "B", "first_value",
                        new BigDecimal("201.75"), "first_average", new BigDecimal("180.25"),
                        "second_value", new BigDecimal("0.80"), "second_average",
                        new BigDecimal("1.10"), "meets_condition", 1)),
                projection.getRows());
    }

    @Test
    void compoundBenchmarkProjectionFailsClosedWhenAWideColumnIsMissing() {
        BankResultProjector.Contract contract = BankResultProjector.Contract.builder()
                .type(BankResultProjector.ProjectionType.COMPOUND_BENCHMARK_THRESHOLD)
                .organizationColumn("bank_organization")
                .organizationNames(Map.of("ORG001", "A"))
                .selectedOrganizationCodes(List.of())
                .metrics(List.of(BankResultProjector.MetricBinding.builder()
                        .semanticColumn("first_value").metricCode("ZB015").build()))
                .build();

        // Missing first_average (the per-metric companion column) must refuse the projection
        // instead of emitting a half-populated row.
        BankResultProjector.Projection projection = projector.project(contract,
                List.of(row("bank_organization", "ORG001", "first_value",
                        new BigDecimal("150.25"), "meets_condition", 1)));

        assertFalse(projection.isApplied());
    }

    @Test
    void shouldProjectDepositStructureShareWithRatioPercent() {
        BankResultProjector.Contract contract = BankResultProjector.Contract.builder()
                .type(BankResultProjector.ProjectionType.LONG_FORM)
                .organizationColumn("bank_organization").organizationNames(Map.of("ORG002", "B"))
                .selectedOrganizationCodes(List.of("ORG002")).structureShare(true)
                .metrics(List.of(
                        BankResultProjector.MetricBinding.builder().semanticColumn("zb001")
                                .metricCode("ZB001").build(),
                        BankResultProjector.MetricBinding.builder().semanticColumn("zb003")
                                .metricCode("ZB003").build(),
                        BankResultProjector.MetricBinding.builder().semanticColumn("zb004")
                                .metricCode("ZB004").build()))
                .build();

        BankResultProjector.Projection projection =
                projector.project(contract, List.of(row("zb001", new BigDecimal("52.11"), "zb003",
                        new BigDecimal("18.51"), "zb004", new BigDecimal("33.6"))));

        assertEquals(
                List.of("org_code", "org_name", "metric_code", "metric_value", "ratio_percent"),
                projection.getColumns());
        assertEquals(3, projection.getRows().size());
        assertEquals("ZB003", projection.getRows().get(0).get("metric_code"));
        assertEquals("ZB004", projection.getRows().get(1).get("metric_code"));
        assertEquals("ZB001", projection.getRows().get(2).get("metric_code"));
        assertEquals(new BigDecimal("35.52"), projection.getRows().get(0).get("ratio_percent"));
        assertEquals(new BigDecimal("64.48"), projection.getRows().get(1).get("ratio_percent"));
        assertEquals(new BigDecimal("100.00"),
                projection.getRows().get(2).get("ratio_percent"));
    }

    @Test
    void shouldProjectPerCapitaProfitToItsBusinessFactContract() {
        BankResultProjector.Contract contract = BankResultProjector.Contract.builder()
                .type(BankResultProjector.ProjectionType.RATIO)
                .organizationColumn("bank_organization")
                .organizationNames(Map.of("ORG003", "江苏省C市农商行"))
                .selectedOrganizationCodes(List.of("ORG003"))
                .metrics(List.of(
                        BankResultProjector.MetricBinding.builder().semanticColumn("zb011")
                                .metricCode("ZB011").build(),
                        BankResultProjector.MetricBinding.builder().semanticColumn("zb018")
                                .metricCode("ZB018").build()))
                .build();

        BankResultProjector.Projection projection = projector.project(contract,
                List.of(row("numerator_value", new BigDecimal("271.69"), "denominator_value",
                        new BigDecimal("288"), "ratio_percent", new BigDecimal("0.943368"))));

        assertEquals(List.of("org_code", "org_name", "net_profit", "employee_count",
                "per_capita_profit"), projection.getColumns());
        assertEquals(List.of(row("org_code", "ORG003", "org_name", "江苏省C市农商行", "net_profit",
                new BigDecimal("271.69"), "employee_count", new BigDecimal("288"),
                "per_capita_profit", new BigDecimal("0.94"))), projection.getRows());
    }

    @Test
    void shouldKeepAggregationExtremaWhenMinAndMaxDiffer() {
        BankResultProjector.Contract contract =
                BankResultProjector.Contract.builder()
                        .type(BankResultProjector.ProjectionType.AGGREGATION_SUMMARY)
                        .organizationColumn("bank_organization")
                        .organizationNames(Map.of("ORG010", "J"))
                        .metrics(List.of(BankResultProjector.MetricBinding.builder()
                                .semanticColumn("aggregate_value").metricCode("ZB001").build()))
                        .build();

        BankResultProjector.Projection projection = projector.project(contract,
                List.of(row("bank_organization", "ORG010", "aggregate_value",
                        new BigDecimal("57.72"), "min_value", new BigDecimal("56.44"), "max_value",
                        new BigDecimal("58.82"), "observation_count", 365)));

        assertEquals(List.of("org_code", "org_name", "metric_code", "aggregate_value", "min_value",
                "max_value", "observation_count"), projection.getColumns());
        assertEquals(1, projection.getRows().size());
        assertEquals(new BigDecimal("56.44"), projection.getRows().get(0).get("min_value"));
    }

    @Test
    void shouldNormalizeSingleDayMultiMetricPivotToAggregateFacts() {
        BankResultProjector.Contract contract = BankResultProjector.Contract.builder()
                .type(BankResultProjector.ProjectionType.MULTI_METRIC_AGGREGATION)
                .organizationColumn("bank_organization")
                .organizationNames(Map.of("ORG003", "江苏省C市农商行"))
                .selectedOrganizationCodes(List.of("ORG003"))
                .metrics(List.of(
                        BankResultProjector.MetricBinding.builder().semanticColumn("zb007")
                                .metricCode("ZB007").build(),
                        BankResultProjector.MetricBinding.builder().semanticColumn("zb008")
                                .metricCode("ZB008").build()))
                .build();

        BankResultProjector.Projection projection = projector.project(contract,
                List.of(row("bank_organization", "江苏省C市农商行", "zb008",
                        new BigDecimal("64.1"), "zb007", new BigDecimal("399.51"))));

        assertEquals(List.of("org_code", "org_name", "metric_code", "aggregate_value",
                "min_value", "max_value", "observation_count"), projection.getColumns());
        assertEquals(List.of(
                row("org_code", "ORG003", "org_name", "江苏省C市农商行", "metric_code", "ZB007",
                        "aggregate_value", new BigDecimal("399.51"), "min_value",
                        new BigDecimal("399.51"), "max_value", new BigDecimal("399.51"),
                        "observation_count", 1),
                row("org_code", "ORG003", "org_name", "江苏省C市农商行", "metric_code", "ZB008",
                        "aggregate_value", new BigDecimal("64.1"), "min_value",
                        new BigDecimal("64.1"), "max_value", new BigDecimal("64.1"),
                        "observation_count", 1)), projection.getRows());
    }

    @Test
    void shouldProjectDualRatePairAsPlainMetricValue() {
        BankResultProjector.Contract contract = BankResultProjector.Contract.builder()
                .type(BankResultProjector.ProjectionType.LONG_FORM)
                .organizationColumn("bank_organization").organizationNames(Map.of("ORG004", "D"))
                .selectedOrganizationCodes(List.of("ORG004"))
                .metrics(List.of(
                        BankResultProjector.MetricBinding.builder().semanticColumn("zb013")
                                .metricCode("ZB013").build(),
                        BankResultProjector.MetricBinding.builder().semanticColumn("zb015")
                                .metricCode("ZB015").build()))
                .build();

        BankResultProjector.Projection projection = projector.project(contract,
                List.of(row("zb013", new BigDecimal("1.58"), "zb015", new BigDecimal("153.06"))));

        assertEquals(List.of("org_code", "org_name", "metric_code", "metric_value"),
                projection.getColumns());
        assertEquals(2, projection.getRows().size());
        assertEquals(new BigDecimal("1.58"), projection.getRows().get(0).get("metric_value"));
    }

    @Test
    void shouldNotTreatLargeRatioAsDepositPerOutletWithoutOutletMetric() {
        BankResultProjector.Contract contract = BankResultProjector.Contract.builder()
                .type(BankResultProjector.ProjectionType.RATIO)
                .organizationColumn("bank_organization").organizationNames(Map.of("ORG011", "K"))
                .selectedOrganizationCodes(List.of("ORG011"))
                .metrics(List.of(
                        BankResultProjector.MetricBinding.builder().semanticColumn("zb014")
                                .metricCode("ZB014").build(),
                        BankResultProjector.MetricBinding.builder().semanticColumn("zb002")
                                .metricCode("ZB002").build()))
                .build();

        BankResultProjector.Projection projection = projector.project(contract,
                List.of(row("numerator_value", new BigDecimal("0.49"), "denominator_value",
                        new BigDecimal("38.27"), "ratio_percent", new BigDecimal("1.28"))));

        assertEquals(List.of("org_code", "org_name", "numerator_value", "denominator_value",
                "ratio_percent"), projection.getColumns());
        assertEquals(new BigDecimal("0.49"), projection.getRows().get(0).get("numerator_value"));
    }

    @Test
    void shouldProjectAnAbsoluteThresholdWithItsMetricCode() {
        BankResultProjector.Contract contract =
                BankResultProjector.Contract.builder()
                        .type(BankResultProjector.ProjectionType.ABSOLUTE_THRESHOLD)
                        .organizationColumn("bank_organization")
                        .organizationNames(Map.of("ORG008", "H"))
                        .metrics(List.of(BankResultProjector.MetricBinding.builder()
                                .semanticColumn("metric_value").metricCode("ZB016").build()))
                        .build();

        BankResultProjector.Projection projection =
                projector.project(contract, List.of(row("bank_organization", "ORG008",
                        "metric_value", new BigDecimal("11.82"), "meets_condition", 1)));

        assertEquals(
                List.of("org_code", "org_name", "metric_code", "metric_value", "meets_condition"),
                projection.getColumns());
        assertEquals(
                List.of(row("org_code", "ORG008", "org_name", "H", "metric_code", "ZB016",
                        "metric_value", new BigDecimal("11.82"), "meets_condition", 1)),
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
    void shouldProjectAverageOnlyWithoutUnrequestedExtrema() {
        BankResultProjector.Contract contract =
                BankResultProjector.Contract.builder()
                        .type(BankResultProjector.ProjectionType.AGGREGATION_SUMMARY)
                        .organizationColumn("bank_organization")
                        .organizationNames(Map.of("ORG002", "B")).dailyAverageOnly(true)
                        .metrics(List.of(BankResultProjector.MetricBinding.builder()
                                .semanticColumn("aggregate_value").metricCode("ZB002").build()))
                        .build();

        BankResultProjector.Projection projection = projector.project(contract,
                List.of(row("bank_organization", "ORG002", "aggregate_value",
                        new BigDecimal("42.3278904109589"), "min_value", new BigDecimal("41.5"),
                        "max_value", new BigDecimal("43.15"), "observation_count", 365)));

        assertEquals(List.of("org_code", "org_name", "metric_code", "daily_average",
                "observation_count"), projection.getColumns());
        assertEquals(
                List.of(row("org_code", "ORG002", "org_name", "B", "metric_code", "ZB002",
                        "daily_average", new BigDecimal("42.33"), "observation_count", 365)),
                projection.getRows());
    }

    @Test
    void shouldProjectMultipleMetricAggregationSummaryFromValidatedSourceMetricCodes() {
        BankResultProjector.Contract contract = BankResultProjector.Contract.builder()
                .type(BankResultProjector.ProjectionType.AGGREGATION_SUMMARY)
                .organizationColumn("bank_organization").organizationNames(Map.of("ORG004", "D"))
                .selectedOrganizationCodes(List.of("ORG004"))
                .metrics(List.of(
                        BankResultProjector.MetricBinding.builder().semanticColumn("metric_code")
                                .metricCode("ZB001").build(),
                        BankResultProjector.MetricBinding.builder().semanticColumn("metric_code")
                                .metricCode("ZB002").build()))
                .build();

        BankResultProjector.Projection projection = projector.project(contract, List.of(
                row("bank_organization", "ORG004", "metric_code", "ZB002", "aggregate_value",
                        new BigDecimal("42.25"), "min_value", new BigDecimal("40.00"), "max_value",
                        new BigDecimal("45.00"), "observation_count", 3),
                row("bank_organization", "ORG004", "metric_code", "ZB001", "aggregate_value",
                        new BigDecimal("54.79"), "min_value", new BigDecimal("50.00"), "max_value",
                        new BigDecimal("60.00"), "observation_count", 3)));

        assertTrue(projection.isApplied());
        assertEquals(List.of(row("org_code", "ORG004", "org_name", "D", "metric_code", "ZB001",
                "aggregate_value", new BigDecimal("54.79"), "min_value", new BigDecimal("50.00"),
                "max_value", new BigDecimal("60.00"), "observation_count", 3),
                row("org_code", "ORG004", "org_name", "D", "metric_code", "ZB002",
                        "aggregate_value", new BigDecimal("42.25"), "min_value",
                        new BigDecimal("40.00"), "max_value", new BigDecimal("45.00"),
                        "observation_count", 3)),
                projection.getRows());

        assertFalse(projector.project(contract,
                List.of(row("bank_organization", "ORG004", "metric_code", "ZB999",
                        "aggregate_value", new BigDecimal("1"), "min_value", new BigDecimal("1"),
                        "max_value", new BigDecimal("1"), "observation_count", 1)))
                .isApplied());
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

    @Test
    void shouldProjectDaysAboveProvinceAverageToTheStableAuditableColumns() {
        BankResultProjector.Contract contract = BankResultProjector.Contract.builder()
                .type(BankResultProjector.ProjectionType.COUNT_DAYS_ABOVE_PROVINCE_AVERAGE)
                .organizationColumn("bank_organization").organizationNames(Map.of("ORG004", "D"))
                .selectedOrganizationCodes(List.of("ORG004"))
                .metrics(List.of(BankResultProjector.MetricBinding.builder()
                        .semanticColumn("days_above_province_average").metricCode("ZB001").build()))
                .build();

        BankResultProjector.Projection projection = projector.project(contract,
                List.of(row("bank_organization", "ORG004", "days_above_province_average", 132,
                        "observation_count", 250, "above_ratio_percent", new BigDecimal("52.8"))));

        assertEquals(List.of("org_code", "org_name", "metric_code", "days_above_average",
                "total_days", "ratio_percent"), projection.getColumns());
        assertEquals(List.of(row("org_code", "ORG004", "org_name", "D", "metric_code", "ZB001",
                "days_above_average", 132, "total_days", 250, "ratio_percent",
                new BigDecimal("52.8"))), projection.getRows());
        // 数值缺失或非数值必须 fail closed,而不是静默丢弃。
        assertFalse(projector
                .project(contract,
                        List.of(row("bank_organization", "ORG004", "days_above_province_average",
                                "many", "observation_count", 250, "above_ratio_percent", 1)))
                .isApplied());
        assertFalse(
                projector
                        .project(contract, List.of(row("bank_organization", "ORG004",
                                "days_above_province_average", 1, "above_ratio_percent", 1)))
                        .isApplied());
    }

    @Test
    void shouldProjectDailyExtremaOrgToMaxAndMinHolders() {
        BankResultProjector.Contract contract =
                BankResultProjector.Contract.builder()
                        .type(BankResultProjector.ProjectionType.DAILY_EXTREMA_ORG)
                        .organizationColumn("bank_organization")
                        .organizationNames(Map.of("ORG003", "C", "ORG008", "H", "ORG001", "A"))
                        .metrics(List.of(BankResultProjector.MetricBinding.builder()
                                .semanticColumn("aggregate_value").metricCode("ZB002").build()))
                        .build();

        BankResultProjector.Projection projection = projector.project(contract,
                List.of(row("bank_organization", "ORG001", "aggregate_value", new BigDecimal("34"),
                        "min_value", new BigDecimal("32"), "max_value", new BigDecimal("35"),
                        "observation_count", 365),
                        row("bank_organization", "ORG003", "aggregate_value", new BigDecimal("90"),
                                "min_value", new BigDecimal("88"), "max_value",
                                new BigDecimal("95.06"), "observation_count", 365),
                        row("bank_organization", "ORG008", "aggregate_value", new BigDecimal("31"),
                                "min_value", new BigDecimal("30.52"), "max_value",
                                new BigDecimal("32"), "observation_count", 365)));

        assertEquals(
                List.of("org_code", "org_name", "metric_code", "metric_value", "rank_position"),
                projection.getColumns());
        assertEquals(
                List.of(row("org_code", "ORG003", "org_name", "C", "metric_code", "ZB002",
                        "metric_value", new BigDecimal("95.06"), "rank_position", 1),
                        row("org_code", "ORG008", "org_name", "H", "metric_code", "ZB002",
                                "metric_value", new BigDecimal("30.52"), "rank_position", 1)),
                projection.getRows());
    }

    @Test
    void shouldRetainSourceRankPositionsVerbatimForDerivedRankingRows() {
        BankResultProjector.Contract contract = BankResultProjector.Contract.builder()
                .type(BankResultProjector.ProjectionType.DERIVED_RANKING)
                .organizationColumn("bank_organization")
                .organizationNames(Map.of("ORG004", "江苏省D市农商行"))
                .selectedOrganizationCodes(List.of("ORG004")).build();

        BankResultProjector.Projection projection = projector.project(contract,
                List.of(row("metric_code", "ZB001", "bank_organization", "ORG004", "metric_value",
                        new BigDecimal("48.50"), "rank_position", 3),
                        row("metric_code", "DERIVED_ZB002_DIV_ZB001", "bank_organization", "ORG004",
                                "metric_value", new BigDecimal("87.30"), "rank_position", 7)));

        assertTrue(projection.isApplied());
        assertEquals(
                List.of("metric_code", "org_code", "org_name", "metric_value", "rank_position"),
                projection.getColumns());
        // 直接沿用 SQL 的 ROW_NUMBER 序位,不重新计算;输出按 metric_code ASC、org_code ASC 确定排序。
        assertEquals(List.of(
                row("metric_code", "DERIVED_ZB002_DIV_ZB001", "org_code", "ORG004", "org_name",
                        "江苏省D市农商行", "metric_value", new BigDecimal("87.30"), "rank_position", 7),
                row("metric_code", "ZB001", "org_code", "ORG004", "org_name", "江苏省D市农商行",
                        "metric_value", new BigDecimal("48.50"), "rank_position", 3)),
                projection.getRows());
    }

    @Test
    void shouldFailClosedWhenDerivedRankingSourceRankIsMissingOrNotUsable() {
        BankResultProjector.Contract contract = BankResultProjector.Contract.builder()
                .type(BankResultProjector.ProjectionType.DERIVED_RANKING)
                .organizationColumn("bank_organization").organizationNames(Map.of("ORG004", "D"))
                .selectedOrganizationCodes(List.of("ORG004")).build();

        assertFalse(projector.project(contract, List.of(row("metric_code", "ZB001",
                "bank_organization", "ORG004", "metric_value", new BigDecimal("1")))).isApplied());
        assertFalse(projector
                .project(contract,
                        List.of(row("metric_code", "ZB001", "bank_organization", "ORG004",
                                "metric_value", new BigDecimal("1"), "rank_position", "first")))
                .isApplied());
    }

    @Test
    void shouldFailClosedWhenADerivedRankingRowHasNoUsableRatioValue() {
        BankResultProjector.Contract contract = BankResultProjector.Contract.builder()
                .type(BankResultProjector.ProjectionType.DERIVED_RANKING)
                .organizationColumn("bank_organization").organizationNames(Map.of("ORG004", "D"))
                .selectedOrganizationCodes(List.of("ORG004")).build();

        // 零分母在 SQL 中产生 NULL 比值并在排名前被排除;若任何行仍缺 metric_value,
        // 投影必须整体 fail closed,绝不臆造一个排名的比值。
        assertFalse(
                projector
                        .project(contract,
                                List.of(row("metric_code", "DERIVED_ZB002_DIV_ZB001",
                                        "bank_organization", "ORG004", "rank_position", 5)))
                        .isApplied());
    }

    @Test
    void shouldSliceRankedTemplateRowsToRequestedTopAndBottomRanks() {
        // Five-metric ranking template rows carry SQL rank_position for the full population;
        // the projector must slice them to the requested top-2/bottom-2 without dropping the
        // metric identity or recomputing ranks.
        BankResultProjector.Contract contract = BankResultProjector.Contract.builder()
                .type(BankResultProjector.ProjectionType.DERIVED_RANKING)
                .organizationColumn("bank_organization")
                .organizationNames(Map.of("ORG001", "A", "ORG002", "B", "ORG003", "C", "ORG004",
                        "D", "ORG005", "E"))
                .metrics(List.of(BankResultProjector.MetricBinding.builder()
                        .semanticColumn("metric_value").metricCode("ZB001").build()))
                .topRankLimit(2).bottomRankLimit(2).build();

        List<Map<String, Object>> sourceRows = new java.util.ArrayList<>();
        for (int rank = 1; rank <= 5; rank++) {
            sourceRows.add(row("metric_code", "ZB001",
                    "bank_organization", String.format("ORG%03d", rank),
                    "metric_value", new BigDecimal(100 - rank), "rank_position", rank));
        }

        BankResultProjector.Projection projection = projector.project(contract, sourceRows);

        assertTrue(projection.isApplied());
        assertEquals(4, projection.getRows().size());
        assertEquals(List.of(1, 2, 4, 5), projection.getRows().stream()
                .map(row -> row.get("rank_position")).toList());
        assertEquals(List.of("ORG001", "ORG002", "ORG004", "ORG005"),
                projection.getRows().stream().map(row -> row.get("org_code")).toList());
    }

    @Test
    void shouldOrderBottomOnlyRankedTemplateRowsByRankDescending() {
        BankResultProjector.Contract contract = BankResultProjector.Contract.builder()
                .type(BankResultProjector.ProjectionType.DERIVED_RANKING)
                .organizationColumn("bank_organization")
                .organizationNames(Map.of("ORG001", "A", "ORG002", "B", "ORG003", "C", "ORG004",
                        "D", "ORG005", "E"))
                .metrics(List.of(BankResultProjector.MetricBinding.builder()
                        .semanticColumn("metric_value").metricCode("ZB013").build()))
                .bottomRankLimit(2).build();

        List<Map<String, Object>> sourceRows = new java.util.ArrayList<>();
        for (int rank = 1; rank <= 5; rank++) {
            sourceRows.add(row("metric_code", "ZB013",
                    "bank_organization", String.format("ORG%03d", rank),
                    "metric_value", new BigDecimal(100 - rank), "rank_position", rank));
        }

        BankResultProjector.Projection projection = projector.project(contract, sourceRows);

        assertTrue(projection.isApplied());
        // 后N名 keeps the published rank_position DESC presentation (worst first).
        assertEquals(List.of(5, 4), projection.getRows().stream()
                .map(row -> row.get("rank_position")).toList());
        assertEquals(List.of("ORG005", "ORG004"),
                projection.getRows().stream().map(row -> row.get("org_code")).toList());
    }

    @Test
    void shouldKeepEveryRowWhenARankedTemplateContractCarriesNoSlice() {
        BankResultProjector.Contract contract = BankResultProjector.Contract.builder()
                .type(BankResultProjector.ProjectionType.DERIVED_RANKING)
                .organizationColumn("bank_organization")
                .organizationNames(Map.of("ORG001", "A", "ORG002", "B"))
                .metrics(List.of(BankResultProjector.MetricBinding.builder()
                        .semanticColumn("metric_value").metricCode("ZB001").build()))
                .build();

        BankResultProjector.Projection projection = projector.project(contract,
                List.of(row("metric_code", "ZB001", "bank_organization", "ORG002",
                        "metric_value", new BigDecimal("90"), "rank_position", 1),
                        row("metric_code", "ZB001", "bank_organization", "ORG001",
                                "metric_value", new BigDecimal("80"), "rank_position", 2)));

        assertTrue(projection.isApplied());
        assertEquals(2, projection.getRows().size());
        // No slice limits keeps the published metric_code ASC, org_code ASC ordering.
        assertEquals(List.of("ORG001", "ORG002"),
                projection.getRows().stream().map(row -> row.get("org_code")).toList());
    }

    private static Map<String, Object> row(Object... values) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            row.put((String) values[index], values[index + 1]);
        }
        return row;
    }
}
