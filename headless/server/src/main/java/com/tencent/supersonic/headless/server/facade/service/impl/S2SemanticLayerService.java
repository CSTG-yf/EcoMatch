package com.tencent.supersonic.headless.server.facade.service.impl;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.tencent.supersonic.common.pojo.Constants;
import com.tencent.supersonic.common.pojo.QueryColumn;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.AuthType;
import com.tencent.supersonic.common.pojo.enums.TaskStatusEnum;
import com.tencent.supersonic.common.util.SensitiveLogUtils;
import com.tencent.supersonic.headless.api.pojo.DataSetSchema;
import com.tencent.supersonic.headless.api.pojo.Dimension;
import com.tencent.supersonic.headless.api.pojo.MetaFilter;
import com.tencent.supersonic.headless.api.pojo.enums.SemanticType;
import com.tencent.supersonic.headless.api.pojo.request.*;
import com.tencent.supersonic.headless.api.pojo.response.*;
import com.tencent.supersonic.headless.chat.knowledge.HanlpMapResult;
import com.tencent.supersonic.headless.chat.knowledge.KnowledgeBaseService;
import com.tencent.supersonic.headless.chat.knowledge.MapResult;
import com.tencent.supersonic.headless.chat.knowledge.SearchService;
import com.tencent.supersonic.headless.chat.knowledge.helper.HanlpHelper;
import com.tencent.supersonic.headless.chat.knowledge.helper.NatureHelper;
import com.tencent.supersonic.headless.core.cache.QueryCache;
import com.tencent.supersonic.headless.core.executor.QueryExecutor;
import com.tencent.supersonic.headless.core.gateway.QueryPerformanceMonitor;
import com.tencent.supersonic.headless.core.pojo.QueryStatement;
import com.tencent.supersonic.headless.core.pojo.SqlQuery;
import com.tencent.supersonic.headless.core.pojo.StructQuery;
import com.tencent.supersonic.headless.core.translator.SemanticTranslator;
import com.tencent.supersonic.headless.core.translator.TranslatorConfig;
import com.tencent.supersonic.headless.core.utils.ComponentFactory;
import com.tencent.supersonic.headless.server.annotation.S2DataPermission;
import com.tencent.supersonic.headless.server.facade.service.SemanticLayerService;
import com.tencent.supersonic.headless.server.manager.SemanticSchemaManager;
import com.tencent.supersonic.headless.server.security.DataMaskingService;
import com.tencent.supersonic.headless.server.security.audit.AuditEventPublisher;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEvent;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEventType;
import com.tencent.supersonic.headless.server.security.audit.model.AuditOutcome;
import com.tencent.supersonic.headless.server.service.*;
import com.tencent.supersonic.headless.server.utils.MetricDrillDownChecker;
import com.tencent.supersonic.headless.server.utils.QueryUtils;
import com.tencent.supersonic.headless.server.utils.StatUtils;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@Slf4j
public class S2SemanticLayerService implements SemanticLayerService {

    private final StatUtils statUtils;
    private final QueryUtils queryUtils;
    private final SemanticSchemaManager semanticSchemaManager;
    private final DataSetService dataSetService;
    private final SchemaService schemaService;
    private final SemanticTranslator semanticTranslator;
    private final MetricDrillDownChecker metricDrillDownChecker;
    private final KnowledgeBaseService knowledgeBaseService;
    private final MetricService metricService;
    private final DomainService domainService;
    private final DimensionService dimensionService;
    private final TranslatorConfig translatorConfig;
    private final DataMaskingService dataMaskingService;
    private final AuditEventPublisher auditEventPublisher;
    private final QueryCache queryCache;
    private final List<QueryExecutor> queryExecutors;

    @Autowired
    public S2SemanticLayerService(StatUtils statUtils, QueryUtils queryUtils,
            SemanticSchemaManager semanticSchemaManager, DataSetService dataSetService,
            SchemaService schemaService, SemanticTranslator semanticTranslator,
            MetricDrillDownChecker metricDrillDownChecker,
            KnowledgeBaseService knowledgeBaseService, MetricService metricService,
            DimensionService dimensionService, DomainService domainService,
            TranslatorConfig translatorConfig, DataMaskingService dataMaskingService,
            AuditEventPublisher auditEventPublisher) {
        this(statUtils, queryUtils, semanticSchemaManager, dataSetService, schemaService,
                semanticTranslator, metricDrillDownChecker, knowledgeBaseService, metricService,
                dimensionService, domainService, translatorConfig, dataMaskingService,
                auditEventPublisher, ComponentFactory.getQueryCache(),
                ComponentFactory.getQueryExecutors());
    }

    S2SemanticLayerService(StatUtils statUtils, QueryUtils queryUtils,
            SemanticSchemaManager semanticSchemaManager, DataSetService dataSetService,
            SchemaService schemaService, SemanticTranslator semanticTranslator,
            MetricDrillDownChecker metricDrillDownChecker,
            KnowledgeBaseService knowledgeBaseService, MetricService metricService,
            DimensionService dimensionService, DomainService domainService,
            TranslatorConfig translatorConfig, DataMaskingService dataMaskingService,
            AuditEventPublisher auditEventPublisher, QueryCache queryCache,
            List<QueryExecutor> queryExecutors) {
        this.statUtils = statUtils;
        this.queryUtils = queryUtils;
        this.semanticSchemaManager = semanticSchemaManager;
        this.dataSetService = dataSetService;
        this.schemaService = schemaService;
        this.semanticTranslator = semanticTranslator;
        this.metricDrillDownChecker = metricDrillDownChecker;
        this.knowledgeBaseService = knowledgeBaseService;
        this.metricService = metricService;
        this.dimensionService = dimensionService;
        this.domainService = domainService;
        this.translatorConfig = translatorConfig;
        this.dataMaskingService = dataMaskingService;
        this.auditEventPublisher = auditEventPublisher;
        this.queryCache = queryCache;
        this.queryExecutors = queryExecutors;
    }

    public DataSetSchema getDataSetSchema(Long id) {
        return schemaService.getDataSetSchema(id);
    }

    @S2DataPermission
    @Override
    public SemanticTranslateResp translate(SemanticQueryReq queryReq, User user) throws Exception {
        QueryStatement queryStatement = buildQueryStatement(queryReq, user);
        long translateStart = System.nanoTime();
        try {
            semanticTranslator.translate(queryStatement);
        } finally {
            QueryPerformanceMonitor.record(QueryPerformanceMonitor.Stage.TRANSLATE,
                    System.nanoTime() - translateStart);
        }
        return SemanticTranslateResp.builder().querySQL(queryStatement.getSql())
                .isOk(queryStatement.isOk()).errMsg(queryStatement.getErrMsg()).build();
    }

    @Override
    @S2DataPermission
    @SneakyThrows
    public SemanticQueryResp queryByReq(SemanticQueryReq queryReq, User user) {
        TaskStatusEnum state = TaskStatusEnum.SUCCESS;
        long queryStart = System.nanoTime();
        String auditSql = getRequestSql(queryReq);
        log.info("semantic query request [{}]", SensitiveLogUtils.summarize(queryReq));
        publishQueryStarted(queryReq, user, auditSql);
        try {
            // 1.initStatInfo
            statUtils.initStatInfo(queryReq, user);

            // 2.query from cache
            String cacheKey = queryCache.getCacheKey(queryReq, user);
            Object query = queryCache.query(queryReq, cacheKey);
            if (Objects.nonNull(query)) {
                log.debug("query cache hit, key:{}", cacheKey);
            }
            if (Objects.nonNull(query)) {
                SemanticQueryResp queryResp = (SemanticQueryResp) query;
                queryResp.setUseCache(true);
                auditSql = StringUtils.defaultIfBlank(queryResp.getSql(), auditSql);
                publishQuerySucceeded(queryReq, queryResp, user, auditSql, queryStart, true);
                return queryResp;
            }
            StatUtils.get().setUseResultCache(false);

            // 3 translate query
            QueryStatement queryStatement = buildQueryStatement(queryReq, user);
            if (!queryStatement.isTranslated()) {
                long translateStart = System.nanoTime();
                try {
                    semanticTranslator.translate(queryStatement);
                } finally {
                    QueryPerformanceMonitor.record(QueryPerformanceMonitor.Stage.TRANSLATE,
                            System.nanoTime() - translateStart);
                }
            }
            auditSql = StringUtils.defaultIfBlank(queryStatement.getSql(), auditSql);

            // Check whether the dimensions of the metric drill-down are correct temporarily,
            // add the abstraction of a validator later.
            metricDrillDownChecker.checkQuery(queryStatement);

            // 4.execute query
            SemanticQueryResp queryResp = null;
            for (QueryExecutor queryExecutor : queryExecutors) {
                if (queryExecutor.accept(queryStatement)) {
                    queryResp = queryExecutor.execute(queryStatement);
                    queryUtils.populateQueryColumns(queryResp, queryStatement.getSemanticSchema());
                }
            }

            if (Objects.isNull(queryResp)) {
                state = TaskStatusEnum.ERROR;
            } else {
                queryResp.appendErrorMsg(queryStatement.getErrMsg());
                maskBeforeCache(queryReq, queryResp, user);
            }

            // 5.reset cache and set stateInfo
            Boolean setCacheSuccess = queryCache.put(queryReq, cacheKey, queryResp);
            if (setCacheSuccess) {
                // if result is not null, update cache data
                statUtils.updateResultCacheKey(cacheKey);
            }

            if (queryResp == null) {
                publishQueryFailed(queryReq, user, auditSql, queryStart, "NO_QUERY_RESULT", null);
            } else {
                publishQuerySucceeded(queryReq, queryResp, user, auditSql, queryStart, false);
            }

            return queryResp;
        } catch (Exception e) {
            log.error("Exception in semantic query [{}]: type={}, error=[{}]",
                    SensitiveLogUtils.summarize(queryReq), e.getClass().getSimpleName(),
                    SensitiveLogUtils.summarize(e));
            state = TaskStatusEnum.ERROR;
            publishQueryFailed(queryReq, user, auditSql, queryStart, "QUERY_EXCEPTION",
                    e.getClass().getSimpleName());
            throw e;
        } finally {
            statUtils.statInfo2DbAsync(state);
        }
    }

    private void maskBeforeCache(SemanticQueryReq queryReq, SemanticQueryResp queryResp,
            User user) {
        SchemaFilterReq filter = new SchemaFilterReq();
        filter.setModelIds(queryReq.getModelIds());
        filter.setDataSetId(queryReq.getDataSetId());
        dataMaskingService.mask(queryResp, schemaService.fetchSemanticSchema(filter), user);
    }

    private void publishQueryStarted(SemanticQueryReq queryReq, User user, String rawSql) {
        try {
            auditEventPublisher.publishBestEffort(AuditEvent.builder()
                    .eventType(AuditEventType.QUERY_STARTED).outcome(AuditOutcome.UNKNOWN)
                    .resourceType("SEMANTIC_QUERY").resourceId(queryResourceId(queryReq))
                    .rawSql(rawSql).metricCodes(queryMetricCodes(queryReq, null))
                    .metadata(queryMetadata(queryReq, null, "STARTED", false, null)).build(), user);
        } catch (RuntimeException e) {
            logAuditFailure(AuditEventType.QUERY_STARTED, e);
        }
    }

    private void publishQuerySucceeded(SemanticQueryReq queryReq, SemanticQueryResp queryResp,
            User user, String rawSql, long queryStart, boolean cacheHit) {
        try {
            auditEventPublisher.publishBestEffort(AuditEvent.builder()
                    .eventType(AuditEventType.QUERY_SUCCEEDED).outcome(AuditOutcome.SUCCESS)
                    .resourceType("SEMANTIC_QUERY").resourceId(queryResourceId(queryReq))
                    .rawSql(rawSql).metricCodes(queryMetricCodes(queryReq, queryResp))
                    .maskingSummary(maskingSummary(queryResp)).durationMs(elapsedMillis(queryStart))
                    .metadata(queryMetadata(queryReq, queryResp, "SUCCEEDED", cacheHit, null))
                    .build(), user);
        } catch (RuntimeException e) {
            logAuditFailure(AuditEventType.QUERY_SUCCEEDED, e);
        }
    }

    private void publishQueryFailed(SemanticQueryReq queryReq, User user, String rawSql,
            long queryStart, String reasonCode, String exceptionType) {
        try {
            auditEventPublisher
                    .publishBestEffort(AuditEvent.builder().eventType(AuditEventType.QUERY_FAILED)
                            .outcome(AuditOutcome.FAILURE).resourceType("SEMANTIC_QUERY")
                            .resourceId(queryResourceId(queryReq)).reasonCode(reasonCode)
                            .rawSql(rawSql).metricCodes(queryMetricCodes(queryReq, null))
                            .durationMs(elapsedMillis(queryStart))
                            .metadata(queryMetadata(queryReq, null, "FAILED", false, exceptionType))
                            .build(), user);
        } catch (RuntimeException e) {
            logAuditFailure(AuditEventType.QUERY_FAILED, e);
        }
    }

    private void logAuditFailure(AuditEventType eventType, RuntimeException failure) {
        log.error("Best-effort query audit failed: eventType={}, errorType={}", eventType,
                failure.getClass().getSimpleName());
    }

    private Map<String, Object> queryMetadata(SemanticQueryReq queryReq,
            SemanticQueryResp queryResp, String stage, boolean cacheHit, String exceptionType) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("stage", stage);
        metadata.put("entryPoint", "queryByReq");
        metadata.put("queryMode", queryReq == null ? null : queryReq.getClass().getSimpleName());
        metadata.put("modelIds", queryReq == null ? List.of() : queryReq.getModelIds());
        metadata.put("dataSetId", queryReq == null ? null : queryReq.getDataSetId());
        metadata.put("needAuth", queryReq != null && queryReq.isNeedAuth());
        metadata.put("cacheHit", cacheHit);
        if (queryResp != null) {
            metadata.put("rowCount", safeSize(queryResp.getResultList()));
            metadata.put("columnCount", safeSize(queryResp.getColumns()));
            metadata.put("maskedFields", queryResp.getMaskedColumns());
        }
        if (StringUtils.isNotBlank(exceptionType)) {
            metadata.put("exceptionType", exceptionType);
        }
        return metadata;
    }

    private Collection<String> queryMetricCodes(SemanticQueryReq queryReq,
            SemanticQueryResp queryResp) {
        Set<String> metricCodes = new LinkedHashSet<>();
        if (queryReq instanceof QueryStructReq structReq) {
            metricCodes.addAll(structReq.getMetrics());
        } else if (queryReq instanceof QueryTagReq tagReq) {
            metricCodes.addAll(tagReq.getMetrics());
        } else if (queryReq instanceof QueryMultiStructReq multiStructReq
                && multiStructReq.getQueryStructReqs() != null) {
            multiStructReq.getQueryStructReqs().stream().filter(Objects::nonNull)
                    .flatMap(req -> req.getMetrics().stream()).forEach(metricCodes::add);
        }
        if (queryResp != null && queryResp.getColumns() != null) {
            queryResp.getMetricColumns().stream().map(QueryColumn::getBizName)
                    .filter(StringUtils::isNotBlank).forEach(metricCodes::add);
        }
        return metricCodes;
    }

    private String getRequestSql(SemanticQueryReq queryReq) {
        if (queryReq instanceof QuerySqlReq querySqlReq) {
            return querySqlReq.getSql();
        }
        if (queryReq != null && queryReq.getSqlInfo() != null) {
            return queryReq.getSqlInfo().getQuerySQL();
        }
        return null;
    }

    private String queryResourceId(SemanticQueryReq queryReq) {
        if (queryReq == null) {
            return null;
        }
        if (queryReq.getDataSetId() != null) {
            return String.valueOf(queryReq.getDataSetId());
        }
        return queryReq.getModelIds().stream().map(String::valueOf).sorted()
                .collect(Collectors.joining(","));
    }

    private String maskingSummary(SemanticQueryResp queryResp) {
        int maskedFieldCount = safeSize(queryResp.getMaskedColumns());
        return queryResp.isDataMasked() ? "MASKED_FIELDS:" + maskedFieldCount : "NONE";
    }

    private int safeSize(Collection<?> values) {
        return values == null ? 0 : values.size();
    }

    private long elapsedMillis(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    @Override
    public SemanticQueryResp queryDimensionValue(DimensionValueReq dimensionValueReq, User user) {
        SemanticQueryResp semanticQueryResp = new SemanticQueryResp();
        DimensionResp dimensionResp = getDimension(dimensionValueReq);
        Set<Long> dataSetIds = dimensionValueReq.getDataSetIds();
        dimensionValueReq.setModelId(dimensionResp.getModelId());

        List<String> dimensionValues = getDimensionValuesFromDict(dimensionValueReq, dataSetIds);

        // try to query dimensionValue from the database.
        if (CollectionUtils.isEmpty(dimensionValues)) {
            return getDimensionValuesFromDb(dimensionValueReq, user);
        }

        List<QueryColumn> columns = createQueryColumns(dimensionValueReq);
        List<Map<String, Object>> resultList = createResultList(dimensionValueReq, dimensionValues);

        semanticQueryResp.setColumns(columns);
        semanticQueryResp.setResultList(resultList);
        return semanticQueryResp;
    }

    private List<String> getDimensionValuesFromDict(DimensionValueReq dimensionValueReq,
            Set<Long> dataSetIds) {
        if (StringUtils.isBlank(dimensionValueReq.getValue())) {
            return SearchService.getDimensionValue(dimensionValueReq);
        }

        Map<Long, List<Long>> modelIdToDataSetIds = new HashMap<>();
        modelIdToDataSetIds.put(dimensionValueReq.getModelId(), new ArrayList<>(dataSetIds));

        List<HanlpMapResult> hanlpMapResultList = knowledgeBaseService
                .prefixSearch(dimensionValueReq.getValue(), 2000, modelIdToDataSetIds, dataSetIds);

        HanlpHelper.transLetterOriginal(hanlpMapResultList);

        return hanlpMapResultList.stream()
                .filter(o -> o.getNatures().stream().map(NatureHelper::getElementID)
                        .anyMatch(elementID -> dimensionValueReq.getElementID().equals(elementID)))
                .map(MapResult::getName).collect(Collectors.toList());
    }

    private SemanticQueryResp getDimensionValuesFromDb(DimensionValueReq queryDimValueReq,
            User user) {
        QuerySqlReq querySqlReq = new QuerySqlReq();
        List<ModelResp> modelResps =
                schemaService.getModelList(Lists.newArrayList(queryDimValueReq.getModelId()));
        DimensionResp dimensionResp = schemaService.getDimension(queryDimValueReq.getBizName(),
                queryDimValueReq.getModelId());
        ModelResp modelResp = modelResps.get(0);
        String sql = String.format("select distinct %s from %s where 1=1", dimensionResp.getName(),
                modelResp.getName());
        List<Dimension> timeDims = modelResp.getTimeDimension();
        if (CollectionUtils.isNotEmpty(timeDims)) {
            sql = String.format("%s and %s >= '%s' and %s <= '%s'", sql,
                    queryDimValueReq.getDateInfo().getDateField(),
                    queryDimValueReq.getDateInfo().getStartDate(),
                    queryDimValueReq.getDateInfo().getDateField(),
                    queryDimValueReq.getDateInfo().getEndDate());
        }
        if (StringUtils.isNotBlank(queryDimValueReq.getValue())) {
            sql += " AND " + queryDimValueReq.getBizName() + " LIKE '%"
                    + queryDimValueReq.getValue() + "%'";
        }
        querySqlReq.setModelIds(Sets.newHashSet(queryDimValueReq.getModelId()));
        querySqlReq.setSql(sql);

        return queryByReq(querySqlReq, user);
    }

    private List<QueryColumn> createQueryColumns(DimensionValueReq dimensionValueReq) {
        QueryColumn queryColumn = new QueryColumn();
        queryColumn.setBizName(dimensionValueReq.getBizName());
        queryColumn.setShowType(SemanticType.CATEGORY.name());
        queryColumn.setAuthorized(true);
        queryColumn.setType("CHAR");

        List<QueryColumn> columns = new ArrayList<>();
        columns.add(queryColumn);
        return columns;
    }

    private List<Map<String, Object>> createResultList(DimensionValueReq dimensionValueReq,
            List<String> dimensionValues) {
        return dimensionValues.stream().map(value -> {
            Map<String, Object> map = new HashMap<>();
            map.put(dimensionValueReq.getBizName(), value);
            return map;
        }).collect(Collectors.toList());
    }

    private DimensionResp getDimension(DimensionValueReq dimensionValueReq) {
        Long elementID = dimensionValueReq.getElementID();
        DimensionResp dimensionResp = schemaService.getDimension(elementID);
        if (dimensionResp == null) {
            String bizName = dimensionValueReq.getBizName();
            Long modelId = dimensionValueReq.getModelId();
            return schemaService.getDimension(bizName, modelId);
        }
        return dimensionResp;
    }

    @Override
    public List<ItemResp> getDomainDataSetTree(User user) {
        List<Long> domainsWithAuth = domainService.getDomainAuthSet(user, AuthType.VIEWER).stream()
                .map(DomainResp::getId).toList();
        return schemaService.getDomainDataSetTree().stream()
                .filter(item -> domainsWithAuth.contains(item.getId())).toList();
    }

    @Override
    public List<DimensionResp> getDimensions(MetaFilter metaFilter) {
        return dimensionService.getDimensions(metaFilter);
    }

    @Override
    public List<MetricResp> getMetrics(MetaFilter metaFilter) {
        return metricService.getMetrics(metaFilter);
    }

    private QueryStatement buildQueryStatement(SemanticQueryReq semanticQueryReq, User user) {
        QueryStatement queryStatement = null;
        if (semanticQueryReq instanceof QuerySqlReq) {
            queryStatement = buildSqlQueryStatement((QuerySqlReq) semanticQueryReq, user);
        }
        if (semanticQueryReq instanceof QueryStructReq) {
            queryStatement = buildStructQueryStatement(semanticQueryReq);
        }
        if (semanticQueryReq instanceof QueryMultiStructReq) {
            queryStatement = buildMultiStructQueryStatement((QueryMultiStructReq) semanticQueryReq);
        }
        if (Objects.nonNull(queryStatement) && Objects.nonNull(semanticQueryReq.getSqlInfo())
                && StringUtils.isNotBlank(semanticQueryReq.getSqlInfo().getQuerySQL())) {
            queryStatement.setSql(semanticQueryReq.getSqlInfo().getQuerySQL());
            queryStatement.setIsTranslated(true);
        }
        if (queryStatement != null) {
            queryStatement.setUser(user);
        }
        return queryStatement;
    }

    private QueryStatement buildQueryStatement(SemanticQueryReq queryReq) {
        SchemaFilterReq schemaFilterReq = new SchemaFilterReq();
        schemaFilterReq.setDataSetId(queryReq.getDataSetId());
        schemaFilterReq.setModelIds(queryReq.getModelIds());
        SemanticSchemaResp semanticSchemaResp = schemaService.fetchSemanticSchema(schemaFilterReq);

        QueryStatement queryStatement = new QueryStatement();
        queryStatement.setEnableOptimize(queryUtils.enableOptimize());
        queryStatement.setLimit(Integer.parseInt(
                translatorConfig.getParameterValue(TranslatorConfig.TRANSLATOR_RESULT_LIMIT)));
        queryStatement.setDataSetId(queryReq.getDataSetId());
        queryStatement.setDataSetName(queryReq.getDataSetName());
        queryStatement.setSemanticSchema(semanticSchemaResp);
        queryStatement.setOntology(semanticSchemaManager.buildOntology(semanticSchemaResp));
        return queryStatement;
    }

    private QueryStatement buildSqlQueryStatement(QuerySqlReq querySqlReq, User user) {
        QueryStatement queryStatement = buildQueryStatement(querySqlReq);
        queryStatement.setIsS2SQL(true);

        SqlQuery sqlQuery = new SqlQuery();
        sqlQuery.setSql(querySqlReq.getSql());
        queryStatement.setSqlQuery(sqlQuery);

        // If dataSetId or DataSetName is empty, parse dataSetId from the SQL
        if (querySqlReq.needGetDataSetId()) {
            Long dataSetId = dataSetService.getDataSetIdFromSql(querySqlReq.getSql(), user);
            querySqlReq.setDataSetId(dataSetId);
        }
        if (querySqlReq.getDataSetId() != null) {
            DataSetResp dataSetResp = dataSetService.getDataSet(querySqlReq.getDataSetId());
            queryStatement.setDataSetId(dataSetResp.getId());
            queryStatement.setDataSetName(dataSetResp.getName());
            sqlQuery.setTable(Constants.TABLE_PREFIX + dataSetResp.getId());
        }
        return queryStatement;
    }

    private QueryStatement buildStructQueryStatement(SemanticQueryReq queryReq) {
        QueryStatement queryStatement = buildQueryStatement(queryReq);
        StructQuery structQuery = new StructQuery();
        BeanUtils.copyProperties(queryReq, structQuery);
        queryStatement.setStructQuery(structQuery);
        queryStatement.setIsS2SQL(false);
        return queryStatement;
    }

    private QueryStatement buildMultiStructQueryStatement(QueryMultiStructReq queryMultiStructReq) {
        List<QueryStatement> queryStatements = new ArrayList<>();
        for (QueryStructReq queryStructReq : queryMultiStructReq.getQueryStructReqs()) {
            QueryStatement queryStatement = buildStructQueryStatement(queryStructReq);
            try {
                semanticTranslator.translate(queryStatement);
            } catch (Exception e) {
                log.warn("Failed to translate semantic query [{}]",
                        SensitiveLogUtils.summarize(queryStructReq));
            }
            queryStatements.add(queryStatement);
        }
        log.info("Unioned query statements [{}]", SensitiveLogUtils.summarize(queryStatements));
        return queryUtils.unionAll(queryMultiStructReq, queryStatements);
    }

}
