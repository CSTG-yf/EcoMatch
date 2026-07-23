package com.tencent.supersonic.headless.chat.s2sql;

import com.tencent.supersonic.headless.chat.corrector.LLMPhysicalSqlCorrector;
import org.junit.Assert;
import org.junit.jupiter.api.Test;

class SqlExecutionRepairServiceTest {

    @Test
    void acceptsEquivalentRepairAndRejectsBroadenedQuery() {
        String original = "SELECT metric_value FROM bank_indicator_fact "
                + "WHERE data_date = '2026-04-30' AND metric_code = 'ZB001'";
        String equivalent = "SELECT metric_value FROM bank_indicator_fact "
                + "WHERE data_date = '2026-04-30' AND metric_code = 'ZB001'";
        String broadened =
                "SELECT metric_value FROM bank_indicator_fact " + "WHERE metric_code = 'ZB001'";
        String changedTable = "SELECT metric_value FROM other_fact "
                + "WHERE data_date = '2026-04-30' AND metric_code = 'ZB001'";
        String changedThreshold = "SELECT metric_value FROM bank_indicator_fact "
                + "WHERE data_date = '2026-04-30' AND metric_code = 'ZB001' "
                + "AND metric_value > 1";
        String originalThreshold = original + " AND metric_value > 10";
        String removedField = "SELECT data_date FROM bank_indicator_fact "
                + "WHERE data_date = '2026-04-30' AND metric_code = 'ZB001'";

        Assert.assertTrue(LLMPhysicalSqlCorrector.isSafeRepair(original, equivalent));
        Assert.assertFalse(LLMPhysicalSqlCorrector.isSafeRepair(original, broadened));
        Assert.assertFalse(LLMPhysicalSqlCorrector.isSafeRepair(original, changedTable));
        Assert.assertFalse(
                LLMPhysicalSqlCorrector.isSafeRepair(originalThreshold, changedThreshold));
        Assert.assertFalse(LLMPhysicalSqlCorrector.isSafeRepair(original, removedField));
    }
}
