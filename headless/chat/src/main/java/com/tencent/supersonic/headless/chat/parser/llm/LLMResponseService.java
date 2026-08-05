package com.tencent.supersonic.headless.chat.parser.llm;

import com.tencent.supersonic.common.jsqlparser.SqlValidHelper;
import com.tencent.supersonic.common.pojo.Constants;
import com.tencent.supersonic.common.pojo.DateConf;
import com.tencent.supersonic.common.pojo.Text2SQLExemplar;
import com.tencent.supersonic.common.util.SensitiveLogUtils;
import com.tencent.supersonic.headless.api.pojo.DataSetSchema;
import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import com.tencent.supersonic.headless.api.pojo.enums.SqlErrorType;
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
import java.util.Collections;
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
        return getDeduplicationSqlRespWithOutcome(currentRetry, llmResp, llmReq)
                .acceptedCandidates();
    }

    DeduplicationOutcome getDeduplicationSqlRespWithOutcome(int currentRetry, LLMResp llmResp,
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
        int candidateCount = 0;
        SqlErrorType validationErrorType = null;
        boolean inconsistentValidationErrorType = false;
        for (Map.Entry<String, LLMSqlResp> entry : sqlRespMap.entrySet()) {
            candidateCount++;
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
                SqlErrorType candidateErrorType = validation.getEvaluation().getErrorType();
                if (validationErrorType == null) {
                    validationErrorType = candidateErrorType;
                } else if (validationErrorType != candidateErrorType) {
                    inconsistentValidationErrorType = true;
                }
                log.warn("currentRetry:{}, rejected S2SQL candidate, reason:{}, sql:{}",
                        currentRetry, SensitiveLogUtils.summarize(failure),
                        SensitiveLogUtils.summarize(entry.getKey()));
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
        boolean allCandidatesRejectedByValidation = candidateCount > 0 && result.isEmpty()
                && validationFailures.size() == candidateCount;
        return new DeduplicationOutcome(result, allCandidatesRejectedByValidation,
                allCandidatesRejectedByValidation && !inconsistentValidationErrorType
                        ? validationErrorType : null);
    }

    private boolean areEquivalent(String left, String right) {
        try {
            return SqlValidHelper.equals(left, right);
        } catch (RuntimeException e) {
            log.debug("Fallback to normalized SQL comparison: type={}, error=[{}]",
                    e.getClass().getSimpleName(), SensitiveLogUtils.summarize(e.getMessage()));
            return StringUtils.normalizeSpace(left)
                    .equalsIgnoreCase(StringUtils.normalizeSpace(right));
        }
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

    private record RankedCandidate(String sql, LLMSqlResp response, double score) {}

    record DeduplicationOutcome(Map<String, LLMSqlResp> acceptedCandidates,
            boolean allCandidatesRejectedByValidation, SqlErrorType validationErrorType) {}
}
