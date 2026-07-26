package com.tencent.supersonic.headless.chat.parser.llm;

import com.tencent.supersonic.common.pojo.Pair;
import com.tencent.supersonic.common.util.DateUtils;
import com.tencent.supersonic.headless.api.pojo.*;
import com.tencent.supersonic.headless.chat.ChatQueryContext;
import com.tencent.supersonic.headless.chat.parser.ParserConfig;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMReq;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMResp;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.SemanticIntentHints;
import com.tencent.supersonic.headless.chat.utils.ComponentFactory;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import static com.tencent.supersonic.headless.chat.parser.ParserConfig.*;

@Slf4j
@Service
public class LLMRequestService {

    private static final String BANK_ORGANIZATION_DIMENSION = "bank_organization";
    private static final String BANK_DATA_DATE_DIMENSION = "bank_data_date";

    @Autowired
    private ParserConfig parserConfig;

    public Long getDataSetId(ChatQueryContext queryCtx) {
        DataSetResolver dataSetResolver = ComponentFactory.getModelResolver();
        return dataSetResolver.resolve(queryCtx, queryCtx.getRequest().getDataSetIds());
    }

    public LLMReq getLlmReq(ChatQueryContext queryCtx, Long dataSetId) {
        Map<Long, String> dataSetIdToName = queryCtx.getSemanticSchema().getDataSetIdToName();
        String queryText = queryCtx.getRequest().getQueryText();
        LLMReq.SqlGenType configuredSqlGenType =
                LLMReq.SqlGenType.valueOf(parserConfig.getParameterValue(PARSER_STRATEGY_TYPE));

        LLMReq.LLMSchema llmSchema = new LLMReq.LLMSchema();
        int fieldCntThreshold =
                Integer.valueOf(parserConfig.getParameterValue(PARSER_FIELDS_COUNT_THRESHOLD));
        if (queryCtx.getMapInfo().getMatchedElements(dataSetId).size() <= fieldCntThreshold) {
            llmSchema.setMetrics(queryCtx.getSemanticSchema().getMetrics());
            llmSchema.setDimensions(queryCtx.getSemanticSchema().getDimensions());
        } else {
            llmSchema.setMetrics(getMappedMetrics(queryCtx, dataSetId));
            llmSchema.setDimensions(getMappedDimensions(queryCtx, dataSetId));
        }
        LLMReq.SqlGenType sqlGenType = selectSqlGenType(configuredSqlGenType,
                queryCtx.getSemanticSchema().getDimensions(), dataSetId, Boolean.parseBoolean(
                        parserConfig.getParameterValue(PARSER_BANK_CONSTRAINED_PLAN_ENABLE)));
        if (LLMReq.SqlGenType.BANK_CONSTRAINED_PLAN.equals(sqlGenType)) {
            llmSchema.setDimensions(ensureBankOrganizationDimension(llmSchema.getDimensions(),
                    queryCtx.getSemanticSchema().getDimensions(), dataSetId));
        }

        LLMReq llmReq = new LLMReq();
        llmReq.setQueryText(queryText);
        llmReq.setSchema(llmSchema);
        Pair<String, String> databaseInfo = getDatabaseType(queryCtx, dataSetId);
        llmSchema.setDatabaseType(databaseInfo.first);
        llmSchema.setDatabaseVersion(databaseInfo.second);
        llmSchema.setDataSetId(dataSetId);
        llmSchema.setDataSetName(dataSetIdToName.get(dataSetId));
        llmSchema.setPartitionTime(getPartitionTime(queryCtx, dataSetId));
        llmSchema.setPrimaryKey(getPrimaryKey(queryCtx, dataSetId));

        boolean linkingValueEnabled =
                Boolean.parseBoolean(parserConfig.getParameterValue(PARSER_LINKING_VALUE_ENABLE));
        if (linkingValueEnabled) {
            llmSchema.setValues(getMappedValues(queryCtx, dataSetId));
        }

        llmReq.setCurrentDate(DateUtils.getBeforeDate(0));
        llmReq.setTerms(getMappedTerms(queryCtx, dataSetId));
        llmReq.setSqlGenType(sqlGenType);
        if (LLMReq.SqlGenType.BANK_CONSTRAINED_PLAN.equals(sqlGenType)) {
            llmReq.setBankMaxCandidates(bankMaxCandidates());
        }
        llmReq.setChatAppConfig(queryCtx.getRequest().getChatAppConfig());
        llmReq.setDynamicExemplars(queryCtx.getRequest().getDynamicExemplars());
        llmReq.setSemanticIntentHints(SemanticIntentHints.fromQuery(queryText,
                queryCtx.getBankIntentResult(), llmSchema, LocalDate.now()));

        return llmReq;
    }

    static LLMReq.SqlGenType selectSqlGenType(LLMReq.SqlGenType configuredSqlGenType,
            List<SchemaElement> availableDimensions, Long dataSetId,
            boolean bankConstrainedPlanEnabled) {
        if (bankConstrainedPlanEnabled && isBankDataset(availableDimensions, dataSetId)) {
            return LLMReq.SqlGenType.BANK_CONSTRAINED_PLAN;
        }
        return configuredSqlGenType;
    }

    private static boolean isBankDataset(List<SchemaElement> availableDimensions, Long dataSetId) {
        if (availableDimensions == null) {
            return false;
        }
        Set<String> businessDimensions = availableDimensions.stream().filter(Objects::nonNull)
                .filter(dimension -> belongsToDataSet(dimension, dataSetId))
                .map(SchemaElement::getBizName).filter(Objects::nonNull).map(String::toLowerCase)
                .collect(Collectors.toSet());
        return businessDimensions.contains(BANK_ORGANIZATION_DIMENSION)
                && businessDimensions.contains(BANK_DATA_DATE_DIMENSION);
    }

    private static boolean belongsToDataSet(SchemaElement dimension, Long dataSetId) {
        return dataSetId == null || dimension.getDataSetId() == null
                || Objects.equals(dataSetId, dimension.getDataSetId());
    }

    private int bankMaxCandidates() {
        try {
            return Math.max(1, Math.min(3,
                    Integer.parseInt(parserConfig.getParameterValue(PARSER_BANK_MAX_CANDIDATES))));
        } catch (RuntimeException ignored) {
            return 1;
        }
    }

    public LLMResp runText2SQL(LLMReq llmReq) {
        SqlGenStrategy sqlGenStrategy = SqlGenStrategyFactory.get(llmReq.getSqlGenType());
        String dataSet = llmReq.getSchema().getDataSetName();
        LLMResp result = sqlGenStrategy.generate(llmReq);
        result.setQuery(llmReq.getQueryText());
        result.setDataSet(dataSet);
        return result;
    }

    protected List<LLMReq.Term> getMappedTerms(ChatQueryContext queryCtx, Long dataSetId) {
        List<SchemaElementMatch> matchedElements =
                queryCtx.getMapInfo().getMatchedElements(dataSetId);
        if (CollectionUtils.isEmpty(matchedElements)) {
            return new ArrayList<>();
        }
        return matchedElements.stream().filter(schemaElementMatch -> {
            SchemaElementType elementType = schemaElementMatch.getElement().getType();
            return SchemaElementType.TERM.equals(elementType);
        }).map(schemaElementMatch -> {
            LLMReq.Term term = new LLMReq.Term();
            term.setName(schemaElementMatch.getElement().getName());
            term.setDescription(schemaElementMatch.getElement().getDescription());
            term.setAlias(schemaElementMatch.getElement().getAlias());
            return term;
        }).collect(Collectors.toList());
    }

    protected List<LLMReq.ElementValue> getMappedValues(@NotNull ChatQueryContext queryCtx,
            Long dataSetId) {
        List<SchemaElementMatch> matchedElements =
                queryCtx.getMapInfo().getMatchedElements(dataSetId);
        if (CollectionUtils.isEmpty(matchedElements)) {
            return new ArrayList<>();
        }
        Set<LLMReq.ElementValue> valueMatches = matchedElements.stream()
                .filter(elementMatch -> !elementMatch.isInherited()).filter(schemaElementMatch -> {
                    SchemaElementType type = schemaElementMatch.getElement().getType();
                    return SchemaElementType.VALUE.equals(type)
                            || SchemaElementType.ID.equals(type);
                }).map(elementMatch -> {
                    LLMReq.ElementValue elementValue = new LLMReq.ElementValue();
                    elementValue.setFieldName(elementMatch.getElement().getName());
                    elementValue.setFieldValue(elementMatch.getWord());
                    return elementValue;
                }).collect(Collectors.toSet());
        return new ArrayList<>(valueMatches);
    }

    protected List<SchemaElement> getMappedMetrics(@NotNull ChatQueryContext queryCtx,
            Long dataSetId) {
        List<SchemaElementMatch> matchedElements =
                queryCtx.getMapInfo().getMatchedElements(dataSetId);
        if (CollectionUtils.isEmpty(matchedElements)) {
            return Collections.emptyList();
        }
        return matchedElements.stream().filter(schemaElementMatch -> {
            SchemaElementType elementType = schemaElementMatch.getElement().getType();
            return SchemaElementType.METRIC.equals(elementType);
        }).map(SchemaElementMatch::getElement).collect(Collectors.toList());
    }

    protected List<SchemaElement> getMappedDimensions(@NotNull ChatQueryContext queryCtx,
            Long dataSetId) {

        List<SchemaElementMatch> matchedElements =
                queryCtx.getMapInfo().getMatchedElements(dataSetId);
        List<SchemaElement> dimensionElements = matchedElements.stream().filter(
                element -> SchemaElementType.DIMENSION.equals(element.getElement().getType()))
                .map(SchemaElementMatch::getElement).collect(Collectors.toList());

        return new ArrayList<>(dimensionElements);
    }

    static List<SchemaElement> ensureBankOrganizationDimension(List<SchemaElement> selected,
            List<SchemaElement> available, Long dataSetId) {
        List<SchemaElement> dimensions =
                selected == null ? new ArrayList<>() : new ArrayList<>(selected);
        boolean present = dimensions.stream().filter(Objects::nonNull).anyMatch(
                dimension -> BANK_ORGANIZATION_DIMENSION.equalsIgnoreCase(dimension.getBizName()));
        if (present || available == null) {
            return dimensions;
        }
        available.stream().filter(Objects::nonNull)
                .filter(dimension -> Objects.equals(dataSetId, dimension.getDataSetId()))
                .filter(dimension -> BANK_ORGANIZATION_DIMENSION
                        .equalsIgnoreCase(dimension.getBizName()))
                .findFirst().ifPresent(dimensions::add);
        return dimensions;
    }

    protected SchemaElement getPartitionTime(@NotNull ChatQueryContext queryCtx, Long dataSetId) {
        SemanticSchema semanticSchema = queryCtx.getSemanticSchema();
        if (semanticSchema == null || semanticSchema.getDataSetSchemaMap() == null) {
            return null;
        }
        Map<Long, DataSetSchema> dataSetSchemaMap = semanticSchema.getDataSetSchemaMap();
        DataSetSchema dataSetSchema = dataSetSchemaMap.get(dataSetId);
        return dataSetSchema.getPartitionDimension();
    }

    protected SchemaElement getPrimaryKey(@NotNull ChatQueryContext queryCtx, Long dataSetId) {
        SemanticSchema semanticSchema = queryCtx.getSemanticSchema();
        if (semanticSchema == null || semanticSchema.getDataSetSchemaMap() == null) {
            return null;
        }
        Map<Long, DataSetSchema> dataSetSchemaMap = semanticSchema.getDataSetSchemaMap();
        DataSetSchema dataSetSchema = dataSetSchemaMap.get(dataSetId);
        return dataSetSchema.getPrimaryKey();
    }

    protected Pair<String, String> getDatabaseType(@NotNull ChatQueryContext queryCtx,
            Long dataSetId) {
        SemanticSchema semanticSchema = queryCtx.getSemanticSchema();
        if (semanticSchema == null || semanticSchema.getDataSetSchemaMap() == null) {
            return null;
        }
        Map<Long, DataSetSchema> dataSetSchemaMap = semanticSchema.getDataSetSchemaMap();
        DataSetSchema dataSetSchema = dataSetSchemaMap.get(dataSetId);
        return new Pair(dataSetSchema.getDatabaseType(), dataSetSchema.getDatabaseVersion());
    }
}
