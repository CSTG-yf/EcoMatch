package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.tencent.supersonic.headless.api.pojo.bank.BankDataDomain;
import com.tencent.supersonic.headless.chat.intent.BankIntentType;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.SemanticIntentHints;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Synthetic-plan tests for the data-domain date guard: every plan date slot must lie inside the
 * observed {@code data_date} domain, the guard must fall open while the domain cache is not
 * initialized, and rejections must name the offending slot plus the real domain range so the
 * repair round can self-correct. No evaluation question text or gold artifact is used here.
 */
class BankQueryPlanDateDomainGuardTest {

    private static final LocalDate DOMAIN_MIN = LocalDate.of(2024, 1, 1);
    private static final LocalDate DOMAIN_MAX = LocalDate.of(2025, 12, 31);
    private static final String DOMAIN_TEXT = "[" + DOMAIN_MIN + ".." + DOMAIN_MAX + "]";

    private final BankQueryPlanValidator validator = new BankQueryPlanValidator();

    @BeforeEach
    void resetDataDomainBefore() {
        BankDataDomain.reset();
    }

    @AfterEach
    void resetDataDomainAfter() {
        BankDataDomain.reset();
    }

    @Test
    void fallsOpenWhenTheDataDomainCacheHasNeverBeenInitialized() {
        assertTrue(BankDataDomain.current() == null);
        BankQueryPlan hallucinated = periodOverPeriodChangePlan(
                LocalDate.of(2023, 12, 31), LocalDate.of(2023, 12, 31));

        BankQueryPlanValidator.ValidationResult result = validator.validate(hallucinated, changeRequirements());

        // Fall-open: out-of-domain-looking dates are not rejected before the domain is observed.
        assertFalse(result.codes().contains("DATE_OUT_OF_DATA_DOMAIN"));
    }

    @Test
    void acceptsAPlanWhoseDateSlotsAllLieInsideTheDataDomain() {
        BankDataDomain.tryInitialize(DOMAIN_MIN, DOMAIN_MAX);

        assertTrue(validator.validate(pointPlan(LocalDate.of(2025, 7, 31), LocalDate.of(2025, 7, 31)),
                pointRequirements()).isValid());
    }

    @Test
    void acceptsDateSlotsExactlyAtTheDomainBoundaries() {
        BankDataDomain.tryInitialize(DOMAIN_MIN, DOMAIN_MAX);

        BankQueryPlan minBoundary = pointPlan(DOMAIN_MIN, DOMAIN_MIN);
        assertTrue(validator.validate(minBoundary, pointRequirements()).isValid());

        BankQueryPlan maxBoundary = pointPlan(DOMAIN_MAX, DOMAIN_MAX);
        assertTrue(validator.validate(maxBoundary, pointRequirements()).isValid());

        BankQueryPlan fullDomainWindow = pointPlan(DOMAIN_MIN, DOMAIN_MAX);
        assertTrue(validator.validate(fullDomainWindow, pointRequirements()).isValid());
    }

    @Test
    void rejectsAnOutOfDomainStartDateNamingTheSlotAndTheRealDomain() {
        BankDataDomain.tryInitialize(DOMAIN_MIN, DOMAIN_MAX);
        BankQueryPlan plan = pointPlan(LocalDate.of(2023, 12, 31), LocalDate.of(2025, 7, 31));

        BankQueryPlanValidator.ValidationResult result = validator.validate(plan, pointRequirements());

        assertFalse(result.isValid());
        assertTrue(result.codes().contains("DATE_OUT_OF_DATA_DOMAIN"));
        assertTrue(result.summary().contains("time.startDate=2023-12-31"));
        assertTrue(result.summary().contains(DOMAIN_TEXT));
        assertTrue(result.summary().contains("真实日期"));
    }

    @Test
    void rejectsAnOutOfDomainEndDateNamingTheSlotAndTheRealDomain() {
        BankDataDomain.tryInitialize(DOMAIN_MIN, DOMAIN_MAX);
        BankQueryPlan plan = pointPlan(LocalDate.of(2025, 7, 31), LocalDate.of(2026, 6, 30));

        BankQueryPlanValidator.ValidationResult result = validator.validate(plan, pointRequirements());

        assertFalse(result.isValid());
        assertTrue(result.codes().contains("DATE_OUT_OF_DATA_DOMAIN"));
        assertTrue(result.summary().contains("time.endDate=2026-06-30"));
        assertTrue(result.summary().contains(DOMAIN_TEXT));
        assertTrue(result.summary().contains("真实日期"));
    }

    @Test
    void rejectsOutOfDomainBaselineSlotsOfAPeriodOverPeriodPlan() {
        BankDataDomain.tryInitialize(DOMAIN_MIN, DOMAIN_MAX);
        // Synthetic date-hallucination shape: the current period is real but the baseline window
        // points at a date that never existed in the dataset, so the baseline join would be empty.
        BankQueryPlan plan = periodOverPeriodChangePlan(LocalDate.of(2023, 12, 31),
                LocalDate.of(2023, 12, 31));

        BankQueryPlanValidator.ValidationResult result = validator.validate(plan, changeRequirements());

        assertFalse(result.isValid());
        assertTrue(result.codes().contains("DATE_OUT_OF_DATA_DOMAIN"));
        assertTrue(result.summary().contains("time.baselineStartDate=2023-12-31"));
        assertTrue(result.summary().contains("time.baselineEndDate=2023-12-31"));
        assertTrue(result.summary().contains(DOMAIN_TEXT));
    }

    @Test
    void acceptsAPeriodOverPeriodPlanWhoseBaselineStaysInsideTheDomain() {
        BankDataDomain.tryInitialize(DOMAIN_MIN, DOMAIN_MAX);
        BankQueryPlan plan = periodOverPeriodChangePlan(LocalDate.of(2024, 6, 30),
                LocalDate.of(2024, 6, 30));

        BankQueryPlanValidator.ValidationResult result = validator.validate(plan, changeRequirements());

        assertFalse(result.codes().contains("DATE_OUT_OF_DATA_DOMAIN"));
    }

    @Test
    void reportsEveryOffendingSlotInOneValidationPass() {
        BankDataDomain.tryInitialize(DOMAIN_MIN, DOMAIN_MAX);
        BankQueryPlan plan = pointPlan(LocalDate.of(2023, 6, 30), LocalDate.of(2026, 6, 30));

        BankQueryPlanValidator.ValidationResult result = validator.validate(plan, pointRequirements());

        long offenceCount = result.errors().stream()
                .filter(error -> "DATE_OUT_OF_DATA_DOMAIN".equals(error.code())).count();
        assertEquals(2, offenceCount);
        assertTrue(result.summary().contains("time.startDate=2023-06-30"));
        assertTrue(result.summary().contains("time.endDate=2026-06-30"));
    }

    private BankQueryPlan pointPlan(LocalDate startDate, LocalDate endDate) {
        return BankQueryPlan.builder().version("1.0").action(BankQueryPlan.PlanAction.EXECUTE)
                .intent(BankIntentType.COMPARISON)
                .metrics(new ArrayList<>(List.of(
                        BankQueryPlan.Metric.builder().bizName("ZB001")
                                .aggregation(BankQueryPlan.Aggregation.DEFAULT).build(),
                        BankQueryPlan.Metric.builder().bizName("ZB002")
                                .aggregation(BankQueryPlan.Aggregation.DEFAULT).build())))
                .derivedMetrics(new ArrayList<>())
                .dimensions(new ArrayList<>(List.of("bank_organization")))
                .organizations(new ArrayList<>(
                        List.of(BankQueryPlan.Organization.builder().code("ORG004").build())))
                .time(BankQueryPlan.TimeRange.builder().startDate(startDate).endDate(endDate)
                        .granularity(BankQueryPlan.TimeGranularity.DAY)
                        .comparison(BankQueryPlan.TimeComparison.NONE).build())
                .filters(new ArrayList<>(List.of(BankQueryPlan.Filter.builder().field("benchmark")
                        .operator("COMPARE").value("PROVINCE_AVERAGE")
                        .values(new ArrayList<>()).build())))
                .calculation(BankQueryPlan.Calculation.builder()
                        .type(BankQueryPlan.CalculationType.DIRECT).build())
                .orderBy(new ArrayList<>()).limit(null)
                .output(BankQueryPlan.Output.builder()
                        .columns(new ArrayList<>(List.of("bank_organization", "ZB001", "ZB002")))
                        .orderSensitive(false).build())
                .build();
    }

    private BankQueryPlan periodOverPeriodChangePlan(LocalDate baselineStart, LocalDate baselineEnd) {
        return BankQueryPlan.builder().version("1.0").action(BankQueryPlan.PlanAction.EXECUTE)
                .intent(BankIntentType.CHANGE)
                .metrics(new ArrayList<>(List.of(BankQueryPlan.Metric.builder().bizName("ZB001")
                        .aggregation(BankQueryPlan.Aggregation.DEFAULT).build())))
                .derivedMetrics(new ArrayList<>())
                .dimensions(new ArrayList<>())
                .organizations(new ArrayList<>(
                        List.of(BankQueryPlan.Organization.builder().code("ORG004").build())))
                .time(BankQueryPlan.TimeRange.builder().startDate(LocalDate.of(2025, 6, 30))
                        .endDate(LocalDate.of(2025, 6, 30))
                        .granularity(BankQueryPlan.TimeGranularity.DAY)
                        .comparison(BankQueryPlan.TimeComparison.PERIOD_OVER_PERIOD)
                        .baselineStartDate(baselineStart).baselineEndDate(baselineEnd).build())
                .filters(new ArrayList<>())
                .calculation(BankQueryPlan.Calculation.builder()
                        .type(BankQueryPlan.CalculationType.CHANGE).build())
                .orderBy(new ArrayList<>()).limit(null)
                .output(BankQueryPlan.Output.builder()
                        .columns(new ArrayList<>(List.of("bank_organization", "ZB001")))
                        .orderSensitive(false).build())
                .build();
    }

    private SemanticIntentHints pointRequirements() {
        return SemanticIntentHints.builder().expectedIntent(BankIntentType.COMPARISON)
                .allowedMetrics(Set.of("ZB001", "ZB002", "ZB003"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date"))
                .requiredMetrics(Set.of("ZB001", "ZB002"))
                .requiredOrganizationCodes(Set.of("ORG004"))
                .requiredFilters(List.of(new SemanticIntentHints.RequiredFilter("benchmark",
                        "COMPARE", "PROVINCE_AVERAGE")))
                .build();
    }

    private SemanticIntentHints changeRequirements() {
        return SemanticIntentHints.builder().expectedIntent(BankIntentType.CHANGE)
                .allowedMetrics(Set.of("ZB001"))
                .allowedDimensions(Set.of("bank_organization", "bank_data_date"))
                .requiredMetrics(Set.of("ZB001")).requiredOrganizationCodes(Set.of("ORG004"))
                .requiredTimeComparison(BankQueryPlan.TimeComparison.PERIOD_OVER_PERIOD).build();
    }
}
