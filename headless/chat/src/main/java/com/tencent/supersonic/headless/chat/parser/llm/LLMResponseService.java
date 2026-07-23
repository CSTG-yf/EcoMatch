package com.tencent.supersonic.headless.chat.parser.llm;

import com.tencent.supersonic.common.jsqlparser.SqlValidHelper;
import com.tencent.supersonic.common.pojo.Constants;
import com.tencent.supersonic.common.pojo.DateConf;
import com.tencent.supersonic.common.pojo.Text2SQLExemplar;
import com.tencent.supersonic.headless.api.pojo.DataSetSchema;
import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import com.tencent.supersonic.headless.chat.ChatQueryContext;
import com.tencent.supersonic.headless.chat.parser.llm.validation.ComplexSqlValidationResult;
import com.tencent.supersonic.headless.chat.parser.llm.validation.ComplexSqlValidator;
import com.tencent.supersonic.headless.chat.query.QueryManager;
import com.tencent.supersonic.headless.chat.query.llm.LLMSemanticQuery;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMReq;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMResp;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMSqlQuery;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMSqlResp;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
public class LLMResponseService {

    private final ComplexSqlValidator complexSqlValidator = new ComplexSqlValidator();

    public void addParseInfo(ChatQueryContext queryCtx, ParseResult parseResult, String s2SQL,
            Double weight) {
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
        parseInfo.setProperties(properties);
        parseInfo.setScore(queryCtx.getRequest().getQueryText().length() * (1 + weight));
        parseInfo.setQueryMode(semanticQuery.getQueryMode());
        parseInfo.getSqlInfo().setParsedS2SQL(s2SQL);
        parseInfo.getSqlInfo().setCorrectedS2SQL(s2SQL);
        ComplexSqlValidationResult validation = complexSqlValidator.validate(s2SQL,
                parseResult.getLlmReq().getSchema(), queryCtx.getRequest().getQueryText());
        parseInfo.setSqlEvaluation(validation.getEvaluation());
        properties.put("complexSqlFeatures", validation.getEvaluation().getFeatures());

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
        return getDeduplicationSqlResp(currentRetry, llmResp, null);
    }

    public Map<String, LLMSqlResp> getDeduplicationSqlResp(int currentRetry, LLMResp llmResp,
            LLMReq llmReq) {
        Map<String, LLMSqlResp> sqlRespMap = llmResp.getSqlRespMap();
        if (MapUtils.isEmpty(sqlRespMap)) {
            sqlRespMap = new HashMap<>();
            LLMSqlResp llmSqlResp = new LLMSqlResp(1D, new ArrayList<>());
            if (StringUtils.isNotBlank(llmResp.getSqlOutput())) {
                sqlRespMap.put(llmResp.getSqlOutput(), llmSqlResp);
            }
        }
        List<String> validationFailures = new ArrayList<>();
        List<RankedCandidate> candidates = new ArrayList<>();
        for (Map.Entry<String, LLMSqlResp> entry : sqlRespMap.entrySet()) {
            LLMSqlResp response = entry.getValue() == null
                    ? LLMSqlResp.builder().sqlWeight(0D).fewShots(new ArrayList<>()).build()
                    : entry.getValue();
            ComplexSqlValidationResult validation = complexSqlValidator.validate(entry.getKey(),
                    llmReq == null ? null : llmReq.getSchema(),
                    llmReq == null ? null : llmReq.getQueryText());
            if (!Boolean.TRUE.equals(validation.getEvaluation().getIsValidated())) {
                String failure = validation.getEvaluation().getErrorType() + ": "
                        + validation.getEvaluation().getValidateMsg();
                validationFailures.add(failure);
                log.warn("currentRetry:{}, rejected S2SQL candidate, reason:{}, sql:{}",
                        currentRetry, failure, entry.getKey());
                continue;
            }
            double modelWeight = response.getSqlWeight();
            candidates.add(new RankedCandidate(entry.getKey(), response,
                    modelWeight + validation.getRankingScore()));
        }
        candidates.sort(Comparator.comparingDouble(RankedCandidate::score).reversed());
        Map<String, LLMSqlResp> result = new LinkedHashMap<>();
        for (RankedCandidate candidate : candidates) {
            if (result.keySet().stream()
                    .anyMatch(existKey -> areEquivalent(existKey, candidate.sql()))) {
                continue;
            }
            result.put(candidate.sql(), candidate.response());
        }
        if (result.isEmpty() && llmReq != null && !validationFailures.isEmpty()) {
            String feedback = validationFailures.stream().distinct().limit(3)
                    .collect(Collectors.joining(" | "));
            String prefix =
                    StringUtils.isBlank(llmReq.getPriorExts()) ? "" : llmReq.getPriorExts() + "\n";
            llmReq.setPriorExts(prefix + "Previous SQL candidates were rejected. Correct these "
                    + "issues without dropping requested filters: " + feedback);
        }
        return result;
    }

    private boolean areEquivalent(String left, String right) {
        try {
            return SqlValidHelper.equals(left, right);
        } catch (RuntimeException e) {
            log.debug("fallback to normalized SQL comparison for complex candidate", e);
            return StringUtils.normalizeSpace(left)
                    .equalsIgnoreCase(StringUtils.normalizeSpace(right));
        }
    }

    private record RankedCandidate(String sql, LLMSqlResp response, double score) {}
}
