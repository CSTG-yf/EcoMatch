package com.tencent.supersonic.chat.server.service;

import com.tencent.supersonic.chat.api.pojo.request.BusinessInsightReq;
import com.tencent.supersonic.chat.api.pojo.request.ChatExecuteReq;
import com.tencent.supersonic.chat.api.pojo.response.BusinessExplanation;
import com.tencent.supersonic.chat.api.pojo.response.ChartInsightResp;
import com.tencent.supersonic.chat.api.pojo.response.QueryResult;
import com.tencent.supersonic.chat.server.pojo.ExecuteContext;
import com.tencent.supersonic.chat.server.processor.execute.BusinessInsightConfig;
import com.tencent.supersonic.chat.server.processor.execute.BusinessInsightProcessor;
import com.tencent.supersonic.common.pojo.QueryColumn;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import com.tencent.supersonic.headless.api.pojo.response.QueryState;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

@Service
public class BusinessInsightService {

    private final BusinessInsightProcessor processor;
    private final BusinessInsightConfig config;

    public BusinessInsightService(BusinessInsightConfig config) {
        this.config = config;
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
                || request.getQueryResults() == null) {
            throw new InvalidArgumentException(
                    "queryColumns must not be empty and queryResults must not be null");
        }
        if (request.getQueryResults().size() > config.getMaxInputRows()) {
            throw new InvalidArgumentException(
                    "queryResults exceeds maximum row count: " + config.getMaxInputRows());
        }
        if (request.getQueryColumns().size() > config.getMaxInputColumns()) {
            throw new InvalidArgumentException(
                    "queryColumns exceeds maximum column count: " + config.getMaxInputColumns());
        }
        if (request.getQueryResults().stream().anyMatch(Objects::isNull)) {
            throw new InvalidArgumentException("queryResults contains a null row");
        }
        validateColumns(request);
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

    private void validateColumns(BusinessInsightReq request) {
        Set<String> fields = new HashSet<>();
        for (QueryColumn column : request.getQueryColumns()) {
            String field = column == null ? null
                    : StringUtils.firstNonBlank(column.getBizName(), column.getNameEn(),
                            column.getName());
            if (StringUtils.isBlank(field)) {
                throw new InvalidArgumentException("queryColumns contains an unnamed field");
            }
            if (!fields.add(field)) {
                throw new InvalidArgumentException(
                        "queryColumns contains duplicate field: " + field);
            }
            if (!request.getQueryResults().isEmpty() && request.getQueryResults().stream()
                    .noneMatch(row -> row != null && row.containsKey(field))) {
                throw new InvalidArgumentException(
                        "queryResults does not contain declared field: " + field);
            }
        }
    }
}
