package com.tencent.supersonic.headless.chat.parser.llm;

import com.tencent.supersonic.common.jsqlparser.SqlValidHelper;
import com.tencent.supersonic.common.pojo.Constants;
import com.tencent.supersonic.common.pojo.DateConf;
import com.tencent.supersonic.common.pojo.Text2SQLExemplar;
import com.tencent.supersonic.headless.api.pojo.DataSetSchema;
import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import com.tencent.supersonic.headless.chat.ChatQueryContext;
import com.tencent.supersonic.headless.chat.query.QueryManager;
import com.tencent.supersonic.headless.chat.query.llm.LLMSemanticQuery;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMResp;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMSqlQuery;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMSqlResp;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.MapUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
public class LLMResponseService {

    public void addParseInfo(ChatQueryContext queryCtx, ParseResult parseResult, String s2SQL,
            Double weight) {
        addParseInfo(queryCtx, parseResult, s2SQL, weight, Collections.emptyMap());
    }

    public void addParseInfo(ChatQueryContext queryCtx, ParseResult parseResult, String s2SQL,
            Double weight, Map<String, Object> diagnostics) {
        if (Objects.isNull(weight)) {
            weight = 0D;
        }
        LLMSemanticQuery semanticQuery = QueryManager.createLLMQuery(LLMSqlQuery.QUERY_MODE);
        SemanticParseInfo parseInfo = semanticQuery.getParseInfo();
        parseInfo.setDataSet(queryCtx.getSemanticSchema().getDataSet(parseResult.getDataSetId()));
        parseInfo.setQueryConfig(
                queryCtx.getSemanticSchema().getQueryConfig(parseResult.getDataSetId()));
        parseInfo.getElementMatches()
                .addAll(queryCtx.getMapInfo().getMatchedElements(parseInfo.getDataSetId()));

        Map<String, Object> properties = new HashMap<>();
        properties.put(Constants.CONTEXT, parseResult);
        properties.put("type", "internal");
        Text2SQLExemplar exemplar =
                Text2SQLExemplar.builder().question(queryCtx.getRequest().getQueryText())
                        .sideInfo(parseResult.getLlmResp().getSideInfo())
                        .dbSchema(parseResult.getLlmResp().getSchema())
                        .sql(parseResult.getLlmResp().getSqlOutput()).build();
        properties.put(Text2SQLExemplar.PROPERTY_KEY, exemplar);
        if (diagnostics != null) {
            properties.putAll(diagnostics);
        }
        parseInfo.setProperties(properties);
        parseInfo.setScore(parseScore(queryCtx.getRequest().getQueryText(), weight, diagnostics));
        parseInfo.setQueryMode(semanticQuery.getQueryMode());
        parseInfo.getSqlInfo().setParsedS2SQL(s2SQL);
        parseInfo.getSqlInfo().setCorrectedS2SQL(s2SQL);

        DataSetSchema dataSetSchema =
                queryCtx.getSemanticSchema().getDataSetSchemaMap().get(parseInfo.getDataSetId());
        SchemaElement partitionDimension = dataSetSchema.getPartitionDimension();
        if (Objects.nonNull(partitionDimension)) {
            DateConf dateConf = new DateConf();
            dateConf.setDateField(partitionDimension.getName());
            parseInfo.setDateInfo(dateConf);
        }
        queryCtx.getCandidateQueries().add(semanticQuery);
    }

    public Map<String, LLMSqlResp> getDeduplicationSqlResp(int currentRetry, LLMResp llmResp) {
        Map<String, LLMSqlResp> sqlRespMap = llmResp.getSqlRespMap();
        if (MapUtils.isEmpty(sqlRespMap)) {
            LLMSqlResp llmSqlResp = new LLMSqlResp(1D, new ArrayList<>());
            sqlRespMap.put(llmResp.getSqlOutput(), llmSqlResp);
        }
        Map<String, LLMSqlResp> result = new HashMap<>();
        for (Map.Entry<String, LLMSqlResp> entry : sqlRespMap.entrySet()) {
            String key = entry.getKey();
            if (result.keySet().stream()
                    .anyMatch(existKey -> SqlValidHelper.equals(existKey, key))) {
                continue;
            }
            if (!SqlValidHelper.isValidSQL(key)) {
                log.error("currentRetry:{},sql is not valid:{}", currentRetry, key);
                continue;
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    static double parseScore(String queryText, Double weight, Map<String, ?> diagnostics) {
        Object semanticScore =
                diagnostics == null ? null : diagnostics.get("bank.nl2sql.semanticScore");
        if (semanticScore instanceof Number number && Double.isFinite(number.doubleValue())
                && number.doubleValue() >= 0D) {
            return number.doubleValue();
        }
        return (queryText == null ? 0 : queryText.length()) * (1 + (weight == null ? 0D : weight));
    }
}
