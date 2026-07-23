package com.tencent.supersonic.chat.server.processor.execute;

import com.tencent.supersonic.chat.api.pojo.response.QueryResult;
import com.tencent.supersonic.common.pojo.QueryColumn;
import com.tencent.supersonic.headless.api.pojo.AggregateInfo;
import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricRatioCalcProcessorTest {

    @Test
    void skipsRatioCalculationForMaskedMetric() {
        SemanticParseInfo parseInfo = new SemanticParseInfo();
        parseInfo.getMetrics()
                .add(SchemaElement.builder().name("Balance").bizName("balance").build());

        QueryColumn balance = new QueryColumn("balance", "DECIMAL", "balance");
        QueryResult result = new QueryResult();
        result.setQueryColumns(List.of(balance));
        result.setQueryResults(List.of(Map.of("balance", "****")));
        result.setDataMasked(true);
        result.setMaskedColumns(Set.of("BALANCE"));

        AggregateInfo aggregateInfo =
                new MetricRatioCalcProcessor().getAggregateInfo(null, parseInfo, result);

        assertTrue(aggregateInfo.getMetricInfos().isEmpty());
    }
}
