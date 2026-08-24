package com.tencent.supersonic.headless.chat.query.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SemanticParseInfoOrderingTest {

    @Test
    void usesIntentScoreAsFinalTieBreakerForEquivalentSchemaMatches() {
        SemanticParseInfo genericGroupBy = parse("METRIC_GROUPBY", 10D);
        SemanticParseInfo explicitTopN = parse("METRIC_ORDERBY", 12D);
        List<SemanticParseInfo> parses =
                new ArrayList<>(List.of(explicitTopN, genericGroupBy));

        SemanticParseInfo.sort(parses);

        assertEquals(List.of("METRIC_ORDERBY", "METRIC_GROUPBY"),
                parses.stream().map(SemanticParseInfo::getQueryMode).toList());
    }

    private SemanticParseInfo parse(String queryMode, double score) {
        SemanticParseInfo parseInfo = new SemanticParseInfo();
        parseInfo.setQueryMode(queryMode);
        parseInfo.setScore(score);
        parseInfo.setDataSet(SchemaElement.builder().dataSetId(1L).build());
        return parseInfo;
    }
}
