package com.tencent.supersonic.headless.chat.parser.llm.bank;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankRepairShapeGuidanceTest {

    @Test
    void everyRegisteredCodeResolvesToAPositiveShapeSkeleton() {
        for (String code : BankRepairShapeGuidance.registeredCodes()) {
            Optional<String> skeleton = BankRepairShapeGuidance.forCode(code);
            assertTrue(skeleton.isPresent(), "missing skeleton for code " + code);
            String text = skeleton.get();
            assertFalse(text.isBlank(), "blank skeleton for code " + code);
            assertTrue(text.contains("正确整体形状骨架"),
                    "skeleton for " + code + " must state the positive overall shape");
        }
    }

    @Test
    void strategyFamilyAndValidatorCodesAreRegistered() {
        Set<String> expected = Set.of(
                "additive_composite_mismatch",
                "province_wide_institution_ranking_mismatch",
                "generic_point_ratio_mismatch",
                "derived_point_ratio_mismatch",
                "per_capita_profit_mismatch",
                "PERCENT_METRIC_RANGE_SUM",
                "PROVINCE_AVERAGE_BENCHMARK_CONTRACT_REQUIRED",
                "PROVINCE_AVERAGE_BENCHMARK_VALUES_FORBIDDEN",
                "COMPOUND_BENCHMARK_METRIC_UNKNOWN",
                "COMPOUND_BENCHMARK_INTENT_REQUIRED",
                "COMPOUND_BENCHMARK_METRICS_REQUIRED",
                "COMPOUND_BENCHMARK_DIRECT_CALCULATION_REQUIRED",
                "COMPOUND_BENCHMARK_NO_COMPARISON_REQUIRED",
                "COMPOUND_BENCHMARK_DERIVED_METRIC_FORBIDDEN",
                "COMPOUND_BENCHMARK_CONDITION_UNPAIRED",
                "COMPOUND_BENCHMARK_DIRECTION_CONFLICT",
                "COMPOUND_BENCHMARK_GLOBAL_DIRECTION_FORBIDDEN",
                "COMPOUND_BENCHMARK_METRIC_FILTER_FORBIDDEN",
                "COMPOUND_BENCHMARK_POPULATION_REQUIRED",
                "COMPOUND_BENCHMARK_DIMENSION_REQUIRED",
                "COMPOUND_BENCHMARK_NO_ORDER_REQUIRED",
                "COMPOUND_BENCHMARK_NO_LIMIT_REQUIRED");
        assertEquals(expected, BankRepairShapeGuidance.registeredCodes());
    }

    @Test
    void skeletonsTranscribeTheOwningGateConditions() {
        String additive = BankRepairShapeGuidance.forCode("additive_composite_mismatch")
                .orElseThrow();
        assertTrue(additive.contains("intent=POINT_QUERY"));
        assertTrue(additive.contains("DERIVED_SUM_<M1>_AND_<M2>"));
        assertTrue(additive.contains("comparison=NONE"));

        String ranking = BankRepairShapeGuidance
                .forCode("province_wide_institution_ranking_mismatch").orElseThrow();
        assertTrue(ranking.contains("intent=RANKING"));
        assertTrue(ranking.contains("organizationCodes=[]"));
        assertTrue(ranking.contains("requiredLimit"));
        assertTrue(ranking.contains("rank_from_bottom"));

        String generic = BankRepairShapeGuidance.forCode("generic_point_ratio_mismatch")
                .orElseThrow();
        assertTrue(generic.contains("intent=RATIO"));
        assertTrue(generic.contains("derivedMetrics=[]"));

        String derived = BankRepairShapeGuidance.forCode("derived_point_ratio_mismatch")
                .orElseThrow();
        assertTrue(derived.contains("intent=RATIO"));
        assertTrue(derived.contains("DERIVED_<分子ZB###>_DIV_<分母ZB###>"));
        // The legacy per-capita code shares the derived-ratio family skeleton.
        assertEquals(derived,
                BankRepairShapeGuidance.forCode("per_capita_profit_mismatch").orElseThrow());

        String percentSeries = BankRepairShapeGuidance.forCode("PERCENT_METRIC_RANGE_SUM")
                .orElseThrow();
        assertTrue(percentSeries.contains("intent=TREND"));
        assertTrue(percentSeries.contains("\"aggregation\":\"AVG\""));
        assertTrue(percentSeries.contains("granularity=DAY"));
        assertTrue(percentSeries.contains("bank_data_date"));

        String benchmark = BankRepairShapeGuidance
                .forCode("PROVINCE_AVERAGE_BENCHMARK_CONTRACT_REQUIRED").orElseThrow();
        assertTrue(benchmark.contains("\"value\":\"PROVINCE_AVERAGE\""));
        assertTrue(benchmark.contains("metric_value"));

        assertTrue(BankRepairShapeGuidance.forCode("PROVINCE_AVERAGE_BENCHMARK_VALUES_FORBIDDEN")
                .orElseThrow().contains("values 必须恰为 []"));

        String direction = BankRepairShapeGuidance.forCode("COMPOUND_BENCHMARK_DIRECTION_CONFLICT")
                .orElseThrow();
        assertTrue(direction.contains("intent=THRESHOLD"));
        assertTrue(direction.contains("higher-better"));
        assertTrue(direction.contains("lower-better"));

        String unpaired = BankRepairShapeGuidance.forCode("COMPOUND_BENCHMARK_CONDITION_UNPAIRED")
                .orElseThrow();
        assertTrue(unpaired.contains("恰好一条"));
    }

    @Test
    void skeletonsCarryNoSampleIdsGoldAnswersOrConcreteDates() {
        for (String code : BankRepairShapeGuidance.registeredCodes()) {
            String text = BankRepairShapeGuidance.forCode(code).orElseThrow();
            assertFalse(text.contains("TRAIN-"), "sample id leak in " + code);
            assertFalse(text.contains("VAL-"), "sample id leak in " + code);
            assertFalse(text.contains("TEST-"), "sample id leak in " + code);
            assertFalse(text.matches("(?s).*\\d{4}-\\d{2}-\\d{2}.*"),
                    "concrete date leak in " + code);
        }
    }

    @Test
    void unknownBlankOrNullCodesResolveToEmpty() {
        assertTrue(BankRepairShapeGuidance.forCode("no_such_shape_code").isEmpty());
        assertTrue(BankRepairShapeGuidance.forCode("").isEmpty());
        assertTrue(BankRepairShapeGuidance.forCode("   ").isEmpty());
        assertTrue(BankRepairShapeGuidance.forCode(null).isEmpty());
    }

    @Test
    void prefixlessContractParserMessagesFallBackToSignatureMatch() {
        Optional<String> derivedSpec = BankRepairShapeGuidance.forMessage(
                "metricCodes must contain at least one exact registry code; derivedMetrics must "
                        + "declare one exact published code with distinct registry numerator and "
                        + "denominator");
        assertTrue(derivedSpec.isPresent());
        assertTrue(derivedSpec.get().contains("DERIVED_<分子ZB###>_DIV_<分母ZB###>"));

        assertTrue(BankRepairShapeGuidance.forMessage("time must include startDate").isEmpty());
        assertTrue(BankRepairShapeGuidance.forMessage(null).isEmpty());
    }

    @Test
    void rawUpperCaseValidatorPrefixResolvesItsRegisteredSkeleton() {
        Optional<String> benchmark = BankRepairShapeGuidance.forRawCodePrefix(
                "PROVINCE_AVERAGE_BENCHMARK_CONTRACT_REQUIRED: province average must use exact "
                        + "benchmark/COMPARE/PROVINCE_AVERAGE");
        assertTrue(benchmark.isPresent());
        assertTrue(benchmark.get().contains("\"value\":\"PROVINCE_AVERAGE\""));
        assertTrue(benchmark.get().contains("metric_value"));

        Optional<String> compound = BankRepairShapeGuidance.forRawCodePrefix(
                "COMPOUND_BENCHMARK_POPULATION_REQUIRED: compound benchmark threshold scans every "
                        + "organization; organizations must be empty");
        assertTrue(compound.isPresent());
        assertTrue(compound.get().contains("organizations=[]"));

        // Exact key match only: an unregistered UPPER prefix resolves to empty.
        assertTrue(BankRepairShapeGuidance.forRawCodePrefix(
                "OUTPUT_MISSING_DIMENSION: output.columns must retain bank_organization")
                .isEmpty());
        assertTrue(BankRepairShapeGuidance.forRawCodePrefix(
                "PROVINCE_AVERAGE_BENCHMARK_CONTRACT_REQUIRED_IS_NOT_REAL: synthetic").isEmpty());
        // No leading token at all.
        assertTrue(BankRepairShapeGuidance.forRawCodePrefix(
                "model response is not complete strict JSON").isEmpty());
        assertTrue(BankRepairShapeGuidance.forRawCodePrefix(null).isEmpty());
    }
}
