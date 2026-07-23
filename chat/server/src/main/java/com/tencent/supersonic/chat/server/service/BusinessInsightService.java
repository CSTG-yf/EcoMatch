package com.tencent.supersonic.chat.server.service;

import com.tencent.supersonic.chat.api.pojo.request.BusinessInsightReq;
import com.tencent.supersonic.chat.api.pojo.request.ChatExecuteReq;
import com.tencent.supersonic.chat.api.pojo.response.BusinessExplanation;
import com.tencent.supersonic.chat.api.pojo.response.ChartInsightResp;
import com.tencent.supersonic.chat.api.pojo.response.QueryResult;
import com.tencent.supersonic.chat.server.pojo.ExecuteContext;
import com.tencent.supersonic.chat.server.processor.execute.BusinessInsightConfig;
import com.tencent.supersonic.chat.server.processor.execute.BusinessInsightProcessor;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import com.tencent.supersonic.headless.api.pojo.response.QueryState;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.Collections;

@Service
public class BusinessInsightService {

    private final BusinessInsightProcessor processor;

    public BusinessInsightService(BusinessInsightConfig config) {
        this.processor = new BusinessInsightProcessor(config);
    }

    public ChartInsightResp recommend(BusinessInsightReq request) {
        QueryResult result = analyze(request);
        return ChartInsightResp.builder().recommendedChart(result.getRecommendedChart())
                .candidateCharts(result.getCandidateCharts()).build();
    }

    public BusinessExplanation explain(BusinessInsightReq request) {
        return analyze(request).getBusinessExplanation();
    }

    private QueryResult analyze(BusinessInsightReq request) {
        if (request == null || CollectionUtils.isEmpty(request.getQueryColumns())
                || CollectionUtils.isEmpty(request.getQueryResults())) {
            throw new InvalidArgumentException("queryColumns and queryResults must not be empty");
        }
        QueryResult result = new QueryResult();
        result.setQueryState(QueryState.SUCCESS);
        result.setQueryColumns(request.getQueryColumns());
        result.setQueryResults(request.getQueryResults());

        ExecuteContext context = new ExecuteContext(
                ChatExecuteReq.builder().queryText(request.getQueryText()).build());
        context.setResponse(result);
        SemanticParseInfo parseInfo = new SemanticParseInfo();
        parseInfo.setMetrics(
                request.getMetrics() == null ? Collections.emptySet() : request.getMetrics());
        context.setParseInfo(parseInfo);
        processor.process(context);
        return result;
    }
}
