package com.tencent.supersonic.headless.server.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.tencent.supersonic.common.pojo.Aggregator;
import com.tencent.supersonic.common.pojo.DataEvent;
import com.tencent.supersonic.common.pojo.DataItem;
import com.tencent.supersonic.common.pojo.DateConf;
import com.tencent.supersonic.common.pojo.Filter;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.AuthType;
import com.tencent.supersonic.common.pojo.enums.EventType;
import com.tencent.supersonic.common.pojo.enums.QueryType;
import com.tencent.supersonic.common.pojo.enums.StatusEnum;
import com.tencent.supersonic.common.pojo.enums.TypeEnums;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.common.util.BeanMapper;
import com.tencent.supersonic.headless.api.pojo.DrillDownDimension;
import com.tencent.supersonic.headless.api.pojo.Measure;
import com.tencent.supersonic.headless.api.pojo.MetaFilter;
import com.tencent.supersonic.headless.api.pojo.MetricParam;
import com.tencent.supersonic.headless.api.pojo.MetricQueryDefaultConfig;
import com.tencent.supersonic.headless.api.pojo.SchemaElementMatch;
import com.tencent.supersonic.headless.api.pojo.SchemaElementType;
import com.tencent.supersonic.headless.api.pojo.SchemaItem;
import com.tencent.supersonic.headless.api.pojo.enums.MapModeEnum;
import com.tencent.supersonic.headless.api.pojo.enums.MetricDefineType;
import com.tencent.supersonic.headless.api.pojo.request.MetaBatchReq;
import com.tencent.supersonic.headless.api.pojo.request.MetricBaseReq;
import com.tencent.supersonic.headless.api.pojo.request.MetricReq;
import com.tencent.supersonic.headless.api.pojo.request.PageMetricReq;
import com.tencent.supersonic.headless.api.pojo.request.QueryMapReq;
import com.tencent.supersonic.headless.api.pojo.request.QueryMetricReq;
import com.tencent.supersonic.headless.api.pojo.request.QueryStructReq;
import com.tencent.supersonic.headless.api.pojo.response.DataSetMapInfo;
import com.tencent.supersonic.headless.api.pojo.response.DataSetResp;
import com.tencent.supersonic.headless.api.pojo.response.DimensionResp;
import com.tencent.supersonic.headless.api.pojo.response.MapInfoResp;
import com.tencent.supersonic.headless.api.pojo.response.MetricResp;
import com.tencent.supersonic.headless.api.pojo.response.ModelResp;
import com.tencent.supersonic.headless.server.facade.service.ChatLayerService;
import com.tencent.supersonic.headless.server.persistence.dataobject.CollectDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.MetricDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.MetricQueryDefaultConfigDO;
import com.tencent.supersonic.headless.server.persistence.mapper.MetricDOMapper;
import com.tencent.supersonic.headless.server.persistence.repository.MetricRepository;
import com.tencent.supersonic.headless.server.pojo.DimensionsFilter;
import com.tencent.supersonic.headless.server.pojo.MetricFilter;
import com.tencent.supersonic.headless.server.pojo.MetricsFilter;
import com.tencent.supersonic.headless.server.pojo.ModelCluster;
import com.tencent.supersonic.headless.server.pojo.ModelFilter;
import com.tencent.supersonic.headless.server.service.CollectService;
import com.tencent.supersonic.headless.server.service.DataSetService;
import com.tencent.supersonic.headless.server.service.DimensionService;
import com.tencent.supersonic.headless.server.service.MetricService;
import com.tencent.supersonic.headless.server.service.ModelService;
import com.tencent.supersonic.headless.server.utils.AliasGenerateHelper;
import com.tencent.supersonic.headless.server.utils.MetricCheckUtils;
import com.tencent.supersonic.headless.server.utils.MetricConverter;
import com.tencent.supersonic.headless.server.utils.ModelClusterBuilder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
public class MetricServiceImpl extends ServiceImpl<MetricDOMapper, MetricDO>
        implements MetricService {

    private final MetricRepository metricRepository;

    private final ModelService modelService;

    private final DimensionService dimensionService;

    private final AliasGenerateHelper aliasGenerateHelper;

    private final CollectService collectService;

    private final DataSetService dataSetService;

    private final ApplicationEventPublisher eventPublisher;

    private final ChatLayerService chatLayerService;

    public MetricServiceImpl(MetricRepository metricRepository, ModelService modelService,
            AliasGenerateHelper aliasGenerateHelper, CollectService collectService,
            DataSetService dataSetService, ApplicationEventPublisher eventPublisher,
            DimensionService dimensionService, @Lazy ChatLayerService chatLayerService) {
        this.metricRepository = metricRepository;
        this.modelService = modelService;
        this.aliasGenerateHelper = aliasGenerateHelper;
        this.eventPublisher = eventPublisher;
        this.collectService = collectService;
        this.dataSetService = dataSetService;
        this.dimensionService = dimensionService;
        this.chatLayerService = chatLayerService;
    }

    @Override
    @Transactional
    public MetricResp createMetric(MetricReq metricReq, User user) {
        requireModelAdmin(metricReq == null ? null : metricReq.getModelId(), user);
        checkExist(Lists.newArrayList(metricReq));
        MetricCheckUtils.checkParam(Lists.newArrayList(metricReq));
        metricReq.createdBy(user.getName());
        MetricDO metricDO = MetricConverter.convert2MetricDO(metricReq);
        metricRepository.createMetric(metricDO);
        sendEventBatch(Lists.newArrayList(metricDO), EventType.ADD, user);
        // should update modelDetail as well
        modelService.updateModelByDimAndMetric(metricReq.getModelId(), null,
                Lists.newArrayList(metricReq), user);


        return MetricConverter.convert2MetricResp(metricDO);
    }

    @Override
    @Transactional
    public void createMetricBatch(List<MetricReq> metricReqs, User user) {
        requireMetricRequestAccess(metricReqs, user);
        List<MetricDO> metricDOS =
                metricReqs.stream().peek(metric -> metric.createdBy(user.getName()))
                        .map(MetricConverter::convert2MetricDO).collect(Collectors.toList());
        metricRepository.createMetricBatch(metricDOS);
        // should update modelDetail as well
        modelService.updateModelByDimAndMetric(metricReqs.get(0).getModelId(), null, metricReqs,
                user);

        sendEventBatch(metricDOS, EventType.ADD, user);
    }

    @Override
    public void alterMetricBatch(List<MetricReq> metricReqs, Long modelId, User user) {
        requireModelAdmin(modelId, user);
        List<MetricResp> metricResps = getMetrics(new MetaFilter(Lists.newArrayList(modelId)));
        // get all metric in model, only use bizname, because name can be changed to everything
        Map<String, MetricResp> bizNameMap = metricResps.stream()
                .collect(Collectors.toMap(MetricResp::getBizName, a -> a, (k1, k2) -> k1));

        List<MetricReq> metricToInsert = Lists.newArrayList();
        metricReqs.forEach(metric -> {
            if (!bizNameMap.containsKey(metric.getBizName())) {
                metricToInsert.add(metric);
            }
        });

        // insert
        if (!CollectionUtils.isEmpty(metricToInsert)) {
            createMetricBatch(metricToInsert, user);
        }

    }

    @Override
    @Transactional
    public MetricResp updateMetric(MetricReq metricReq, User user) {
        if (metricReq == null || metricReq.getId() == null) {
            throw new InvalidArgumentException("Metric id is required");
        }
        MetricDO metricDO = metricRepository.getMetricById(metricReq.getId());
        requirePersistedMetricAccess(metricDO, metricReq.getModelId(), user);
        MetricCheckUtils.checkParam(Lists.newArrayList(metricReq));
        checkExist(Lists.newArrayList(metricReq));
        metricReq.updatedBy(user.getName());
        String oldName = metricDO.getName();
        MetricConverter.convert(metricDO, metricReq);
        metricRepository.updateMetric(metricDO);
        if (!oldName.equals(metricDO.getName())) {
            DataItem dataItem = getDataItem(metricDO);
            dataItem.setName(oldName);
            dataItem.setNewName(metricDO.getName());
            sendEvent(dataItem, EventType.UPDATE, user);
        }
        // should update modelDetail as well
        modelService.updateModelByDimAndMetric(metricReq.getModelId(), null,
                Lists.newArrayList(metricReq), user);
        return MetricConverter.convert2MetricResp(metricDO);
    }

    @Override
    @Transactional
    public void updateMetricBatch(List<MetricReq> metricReqs, User user) {
        requireMetricUpdateAccess(metricReqs, user);
        MetricCheckUtils.checkParam(metricReqs);
        checkExist(metricReqs);
        List<MetricDO> metricDOS = metricReqs.stream().map(MetricConverter::convert2MetricDO)
                .collect(Collectors.toList());
        metricRepository.batchUpdateMetric(metricDOS);
        // should update modelDetail as well
        modelService.updateModelByDimAndMetric(metricReqs.get(0).getModelId(), null, metricReqs,
                user);
        sendEventBatch(metricDOS, EventType.UPDATE, user);
    }


    @Override
    @Transactional
    public void batchUpdateStatus(MetaBatchReq metaBatchReq, User user) {
        if (CollectionUtils.isEmpty(metaBatchReq.getIds())) {
            return;
        }
        List<MetricDO> metricDOS = getMetrics(metaBatchReq.getIds());
        requireCompleteMetricBatch(metaBatchReq.getIds(), metricDOS);
        requireModelAdmins(metricDOS.stream().map(MetricDO::getModelId).collect(Collectors.toSet()),
                user);
        metricDOS = metricDOS.stream().peek(metricDO -> {
            metricDO.setStatus(metaBatchReq.getStatus());
            metricDO.setUpdatedAt(new Date());
            metricDO.setUpdatedBy(user.getName());
        }).collect(Collectors.toList());
        metricRepository.batchUpdateStatus(metricDOS);
        if (StatusEnum.OFFLINE.getCode().equals(metaBatchReq.getStatus())
                || StatusEnum.DELETED.getCode().equals(metaBatchReq.getStatus())) {
            sendEventBatch(metricDOS, EventType.DELETE, user);
        } else if (StatusEnum.ONLINE.getCode().equals(metaBatchReq.getStatus())) {
            sendEventBatch(metricDOS, EventType.ADD, user);
        }
    }

    @Override
    @Transactional
    public void batchPublish(List<Long> metricIds, User user) {
        List<MetricDO> metrics = getMetrics(metricIds);
        requireCompleteMetricBatch(metricIds, metrics);
        requireModelAdmins(metrics.stream().map(MetricDO::getModelId).collect(Collectors.toSet()),
                user);
        for (MetricDO metricDO : metrics) {
            metricDO.setUpdatedAt(new Date());
            metricDO.setUpdatedBy(user.getName());
        }
        metricRepository.batchPublish(metrics);
    }

    @Override
    @Transactional
    public void batchUnPublish(List<Long> metricIds, User user) {
        List<MetricDO> metrics = getMetrics(metricIds);
        requireCompleteMetricBatch(metricIds, metrics);
        requireModelAdmins(metrics.stream().map(MetricDO::getModelId).collect(Collectors.toSet()),
                user);
        for (MetricDO metricDO : metrics) {
            metricDO.setUpdatedAt(new Date());
            metricDO.setUpdatedBy(user.getName());
        }
        metricRepository.batchUnPublish(metrics);
    }

    @Override
    @Transactional
    public void batchUpdateClassifications(MetaBatchReq metaBatchReq, User user) {
        List<MetricDO> metrics = getMetrics(metaBatchReq.getIds());
        requireCompleteMetricBatch(metaBatchReq.getIds(), metrics);
        requireModelAdmins(metrics.stream().map(MetricDO::getModelId).collect(Collectors.toSet()),
                user);
        for (MetricDO metricDO : metrics) {
            metricDO.setUpdatedAt(new Date());
            metricDO.setUpdatedBy(user.getName());
            fillClassifications(metaBatchReq, metricDO);
        }
        metricRepository.updateClassificationsBatch(metrics);
    }

    private void fillClassifications(MetaBatchReq metaBatchReq, MetricDO metricDO) {
        String classificationStr = metricDO.getClassifications();
        Set<String> classificationsList;
        if (StringUtils.isBlank(classificationStr)) {
            classificationsList = new HashSet<>();
        } else {
            classificationsList = new HashSet<>(Arrays.asList(classificationStr.split(",")));
        }

        if (EventType.ADD.equals(metaBatchReq.getType())) {
            classificationsList.addAll(metaBatchReq.getClassifications());
        }
        if (EventType.DELETE.equals(metaBatchReq.getType())) {
            classificationsList.removeAll(metaBatchReq.getClassifications());
        }
        String classifications = "";
        if (!CollectionUtils.isEmpty(classificationsList)) {
            classifications = StringUtils.join(classificationsList, ",");
        }
        metricDO.setClassifications(classifications);
    }

    @Override
    @Transactional
    public void batchUpdateSensitiveLevel(MetaBatchReq metaBatchReq, User user) {
        List<MetricDO> metrics = getMetrics(metaBatchReq.getIds());
        requireCompleteMetricBatch(metaBatchReq.getIds(), metrics);
        requireModelAdmins(metrics.stream().map(MetricDO::getModelId).collect(Collectors.toSet()),
                user);
        for (MetricDO metricDO : metrics) {
            metricDO.setUpdatedAt(new Date());
            metricDO.setUpdatedBy(user.getName());
            metricDO.setSensitiveLevel(metaBatchReq.getSensitiveLevel());
        }
        updateBatchById(metrics);
    }

    @Override
    @Transactional
    public void deleteMetric(Long id, User user) {
        MetricDO metricDO = metricRepository.getMetricById(id);
        if (metricDO == null) {
            throw new RuntimeException(String.format("the metric %s not exist", id));
        }
        requireModelAdmin(metricDO.getModelId(), user);
        metricDO.setStatus(StatusEnum.DELETED.getCode());
        metricDO.setUpdatedAt(new Date());
        metricDO.setUpdatedBy(user.getName());
        metricRepository.updateMetric(metricDO);
        // should update modelDetail
        modelService.deleteModelDetailByDimAndMetric(metricDO.getModelId(), null,
                Lists.newArrayList(metricDO), user);
        sendEventBatch(Lists.newArrayList(metricDO), EventType.DELETE, user);
    }

    @Override
    @Transactional
    public void deleteMetricBatch(List<Long> idList, User user) {
        MetricsFilter metricsFilter = new MetricsFilter();
        metricsFilter.setMetricIds(idList);
        List<MetricDO> metricDOList = metricRepository.getMetrics(metricsFilter);
        requireCompleteMetricBatch(idList, metricDOList);
        requireModelAdmins(
                metricDOList.stream().map(MetricDO::getModelId).collect(Collectors.toSet()), user);
        metricDOList.forEach(metricDO -> {
            metricDO.setStatus(StatusEnum.DELETED.getCode());
            metricDO.setUpdatedAt(new Date());
            metricDO.setUpdatedBy(user.getName());
        });
        metricRepository.batchUpdateStatus(metricDOList);
        // should update modelDetail
        metricDOList.stream().collect(Collectors.groupingBy(MetricDO::getModelId))
                .forEach((modelId, metrics) -> modelService.deleteModelDetailByDimAndMetric(modelId,
                        null, metrics, user));
        sendEventBatch(metricDOList, EventType.DELETE, user);
    }

    @Override
    public PageInfo<MetricResp> queryMetricMarket(PageMetricReq pageMetricReq, User user) {
        // search by whole text
        PageInfo<MetricResp> metricRespPageInfo = queryMetric(pageMetricReq, user);
        if (metricRespPageInfo.hasContent() || StringUtils.isBlank(pageMetricReq.getKey())) {
            return metricRespPageInfo;
        }
        // search by text split
        QueryMapReq queryMapReq = new QueryMapReq();
        queryMapReq.setQueryText(pageMetricReq.getKey());
        queryMapReq.setUser(user);
        queryMapReq.setMapModeEnum(MapModeEnum.LOOSE);
        MapInfoResp mapMeta = chatLayerService.map(queryMapReq);
        Map<String, DataSetMapInfo> dataSetMapInfoMap = mapMeta.getDataSetMapInfo();
        if (CollectionUtils.isEmpty(dataSetMapInfoMap)) {
            return metricRespPageInfo;
        }
        Map<Long, Double> result =
                dataSetMapInfoMap.values().stream().map(DataSetMapInfo::getMapFields)
                        .filter(Objects::nonNull).flatMap(Collection::stream)
                        .filter(schemaElementMatch -> SchemaElementType.METRIC
                                .equals(schemaElementMatch.getElement().getType()))
                        .collect(Collectors.toMap(
                                schemaElementMatch -> schemaElementMatch.getElement().getId(),
                                SchemaElementMatch::getSimilarity,
                                (existingValue, newValue) -> existingValue));
        List<Long> metricIds = new ArrayList<>(result.keySet());
        if (CollectionUtils.isEmpty(result.keySet())) {
            return metricRespPageInfo;
        }
        pageMetricReq.setIds(metricIds);
        pageMetricReq.setKey("");
        PageInfo<MetricResp> metricPage = queryMetric(pageMetricReq, user);
        for (MetricResp metricResp : metricPage.getList()) {
            metricResp.setSimilarity(result.get(metricResp.getId()));
        }
        metricPage.getList().sort(Comparator.comparingDouble(MetricResp::getSimilarity).reversed());
        return metricPage;
    }

    @Override
    public PageInfo<MetricResp> queryMetric(PageMetricReq pageMetricReq, User user) {
        if (pageMetricReq == null) {
            throw new InvalidArgumentException("Metric query is required");
        }
        MetricFilter metricFilter = new MetricFilter();
        metricFilter.setUserName(user.getName());
        BeanUtils.copyProperties(pageMetricReq, metricFilter);

        // If dataSetId is provided, get models directly from the dataset
        if (pageMetricReq.getDataSetId() != null) {
            DataSetResp dataSetResp = dataSetService.getDataSet(pageMetricReq.getDataSetId());
            if (dataSetResp != null) {
                pageMetricReq.getModelIds().addAll(dataSetResp.getAllModels());
            }
        } else if (!CollectionUtils.isEmpty(pageMetricReq.getDomainIds())) {
            // Only check domainIds when dataSetId is not provided
            List<ModelResp> modelResps =
                    modelService.getAllModelByDomainIds(pageMetricReq.getDomainIds());
            List<Long> modelIds =
                    modelResps.stream().map(ModelResp::getId).collect(Collectors.toList());
            pageMetricReq.getModelIds().addAll(modelIds);
        }
        metricFilter.setModelIds(restrictToAccessibleModels(pageMetricReq.getModelIds(), user));
        List<Long> collectIds = getCollectIds(pageMetricReq, user);
        List<Long> idsToFilter = getIdsToFilter(pageMetricReq, collectIds);
        metricFilter.setIds(idsToFilter);
        PageInfo<MetricDO> metricDOPageInfo =
                PageHelper.startPage(pageMetricReq.getCurrent(), pageMetricReq.getPageSize())
                        .doSelectPageInfo(() -> queryMetric(metricFilter));
        PageInfo<MetricResp> pageInfo = new PageInfo<>();
        BeanUtils.copyProperties(metricDOPageInfo, pageInfo);
        List<MetricResp> metricResps = convertList(metricDOPageInfo.getList(), collectIds);
        fillAdminRes(metricResps, user);
        pageInfo.setList(metricResps);
        return pageInfo;
    }

    protected List<MetricDO> queryMetric(MetricFilter metricFilter) {
        return metricRepository.getMetric(metricFilter);
    }

    private List<MetricDO> getMetrics(List<Long> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return Lists.newArrayList();
        }
        MetricFilter metricFilter = new MetricFilter();
        metricFilter.setIds(ids);
        return metricRepository.getMetric(metricFilter);
    }

    @Override
    public List<MetricResp> getMetrics(MetaFilter metaFilter) {
        MetricFilter metricFilter = new MetricFilter();
        BeanUtils.copyProperties(metaFilter, metricFilter);
        List<MetricResp> metricResps = convertList(queryMetric(metricFilter));

        if (!CollectionUtils.isEmpty(metaFilter.getFieldsDepend())) {
            return filterByField(metricResps, metaFilter.getFieldsDepend());
        }
        if (metaFilter.getDataSetId() != null) {
            DataSetResp dataSetResp = dataSetService.getDataSet(metaFilter.getDataSetId());
            return MetricConverter.filterByDataSet(metricResps, dataSetResp);
        }
        return metricResps;
    }

    @Override
    public List<MetricResp> getMetrics(MetaFilter metaFilter, User user) {
        if (metaFilter == null) {
            throw new InvalidArgumentException("Metric filter is required");
        }
        MetaFilter authorized = new MetaFilter();
        BeanUtils.copyProperties(metaFilter, authorized);
        authorized.setModelIds(restrictToAccessibleModels(metaFilter.getModelIds(), user));
        return getMetrics(authorized);
    }

    private List<Long> getCollectIds(PageMetricReq pageMetricReq, User user) {
        List<CollectDO> collectList =
                collectService.getCollectionList(user.getName(), TypeEnums.METRIC);
        List<Long> collectIds =
                collectList.stream().map(CollectDO::getCollectId).collect(Collectors.toList());
        if (pageMetricReq.isHasCollect()) {
            if (CollectionUtils.isEmpty(collectIds)) {
                return Lists.newArrayList(-1L);
            } else {
                return collectIds;
            }
        }
        return Lists.newArrayList();
    }

    private List<Long> getIdsToFilter(PageMetricReq pageMetricReq, List<Long> collectIds) {
        if (CollectionUtils.isEmpty(pageMetricReq.getIds())) {
            return collectIds;
        }
        if (CollectionUtils.isEmpty(collectIds)) {
            return pageMetricReq.getIds();
        }
        List<Long> idsToFilter = new ArrayList<>(collectIds);
        idsToFilter.retainAll(pageMetricReq.getIds());
        idsToFilter.add(-1L);
        return idsToFilter;
    }

    private List<MetricResp> filterByField(List<MetricResp> metricResps, List<String> fields) {
        Set<MetricResp> metricRespFiltered = Sets.newHashSet();
        for (MetricResp metricResp : metricResps) {
            filterByField(metricResps, metricResp, fields, metricRespFiltered);
        }
        return new ArrayList<>(metricRespFiltered);
    }

    private boolean filterByField(List<MetricResp> metricResps, MetricResp metricResp,
            List<String> fields, Set<MetricResp> metricRespFiltered) {
        if (MetricDefineType.METRIC.equals(metricResp.getMetricDefineType())) {
            List<Long> ids = metricResp.getMetricDefineByMetricParams().getMetrics().stream()
                    .map(MetricParam::getId).collect(Collectors.toList());
            List<MetricResp> metricById = metricResps.stream()
                    .filter(metric -> ids.contains(metric.getId())).collect(Collectors.toList());
            for (MetricResp metric : metricById) {
                if (filterByField(metricResps, metric, fields, metricRespFiltered)) {
                    metricRespFiltered.add(metricResp);
                    return true;
                }
            }
        } else if (MetricDefineType.FIELD.equals(metricResp.getMetricDefineType())) {
            if (fields.stream().anyMatch(field -> metricResp.getExpr().contains(field))) {
                metricRespFiltered.add(metricResp);
                return true;
            }
        } else if (MetricDefineType.MEASURE.equals(metricResp.getMetricDefineType())) {
            List<Measure> measures = metricResp.getMetricDefineByMeasureParams().getMeasures();
            List<String> fieldNameDepended = measures.stream().map(Measure::getName)
                    // measure bizName = model bizName_fieldName
                    .map(name -> name.replaceFirst(metricResp.getModelBizName() + "_", ""))
                    .collect(Collectors.toList());
            if (fields.stream().anyMatch(fieldNameDepended::contains)) {
                metricRespFiltered.add(metricResp);
                return true;
            }
        }
        return false;
    }

    @Override
    public List<MetricResp> getMetricsToCreateNewMetric(Long modelId) {
        MetricFilter metricFilter = new MetricFilter();
        metricFilter.setModelIds(Lists.newArrayList(modelId));
        List<MetricResp> metricResps = getMetrics(metricFilter);
        return metricResps.stream().filter(
                metricResp -> MetricDefineType.FIELD.equals(metricResp.getMetricDefineType())
                        || MetricDefineType.MEASURE.equals(metricResp.getMetricDefineType()))
                .collect(Collectors.toList());
    }

    @Override
    public List<MetricResp> getMetricsToCreateNewMetric(Long modelId, User user) {
        modelService.requireModelAdmin(modelId, user);
        return getMetricsToCreateNewMetric(modelId);
    }

    private void fillAdminRes(List<MetricResp> metricResps, User user) {
        List<ModelResp> modelResps = modelService.getModelListWithAuth(user, null, AuthType.ADMIN);
        if (CollectionUtils.isEmpty(modelResps)) {
            return;
        }
        Set<Long> modelIdSet =
                modelResps.stream().map(ModelResp::getId).collect(Collectors.toSet());
        for (MetricResp metricResp : metricResps) {
            if (modelIdSet.contains(metricResp.getModelId())) {
                metricResp.setHasAdminRes(true);
            }
        }
    }

    @Deprecated
    @Override
    public MetricResp getMetric(Long modelId, String bizName) {
        MetricFilter metricFilter = new MetricFilter();
        metricFilter.setBizName(bizName);
        metricFilter.setModelIds(Lists.newArrayList(modelId));
        List<MetricResp> metricResps = getMetrics(metricFilter);
        if (CollectionUtils.isEmpty(metricResps)) {
            return null;
        }
        return metricResps.get(0);
    }

    @Override
    public MetricResp getMetric(Long modelId, String bizName, User user) {
        modelService.requireModelViewer(modelId, user);
        return getMetric(modelId, bizName);
    }

    @Override
    public MetricResp getMetric(Long id, User user) {
        MetricDO metricDO = metricRepository.getMetricById(id);
        if (metricDO == null) {
            return null;
        }
        modelService.requireModelViewer(metricDO.getModelId(), user);
        ModelFilter modelFilter = new ModelFilter(true, Lists.newArrayList(metricDO.getModelId()));
        Map<Long, ModelResp> modelMap = modelService.getModelMap(modelFilter);
        List<CollectDO> collectList =
                collectService.getCollectionList(user.getName(), TypeEnums.METRIC);
        List<Long> collect =
                collectList.stream().map(CollectDO::getCollectId).collect(Collectors.toList());
        MetricResp metricResp = MetricConverter.convert2MetricResp(metricDO, modelMap, collect);
        fillAdminRes(Lists.newArrayList(metricResp), user);
        return metricResp;
    }

    @Override
    public MetricResp getMetric(Long id) {
        MetricDO metricDO = metricRepository.getMetricById(id);
        if (metricDO == null) {
            return null;
        }
        return MetricConverter.convert2MetricResp(metricDO, new HashMap<>(), Lists.newArrayList());
    }

    @Override
    public List<String> mockAlias(MetricBaseReq metricReq, String mockType, User user) {
        requireModelAdmin(metricReq == null ? null : metricReq.getModelId(), user);
        String mockAlias = aliasGenerateHelper.generateAlias(mockType, metricReq.getName(),
                metricReq.getBizName(), "", metricReq.getDescription());
        String ret = mockAlias.replaceAll("`", "").replace("json", "").replace("\n", "")
                .replace(" ", "");
        return JSONObject.parseObject(ret, new TypeReference<List<String>>() {});
    }

    @Override
    public Set<String> getMetricTags() {
        List<MetricResp> metricResps = getMetrics(new MetaFilter());
        if (CollectionUtils.isEmpty(metricResps)) {
            return new HashSet<>();
        }
        return metricResps.stream().flatMap(metricResp -> metricResp.getClassifications().stream())
                .collect(Collectors.toSet());
    }

    @Override
    public Set<String> getMetricTags(User user) {
        return getMetrics(new MetaFilter(), user).stream()
                .flatMap(metric -> metric.getClassifications().stream())
                .collect(Collectors.toSet());
    }

    @Override
    public List<DrillDownDimension> getDrillDownDimension(Long metricId) {
        List<DrillDownDimension> drillDownDimensions = Lists.newArrayList();
        MetricResp metricResp = getMetric(metricId);
        if (metricResp == null) {
            return drillDownDimensions;
        }
        if (metricResp.getRelateDimension() != null && !CollectionUtils
                .isEmpty(metricResp.getRelateDimension().getDrillDownDimensions())) {
            for (DrillDownDimension drillDownDimension : metricResp.getRelateDimension()
                    .getDrillDownDimensions()) {
                if (drillDownDimension.isInheritedFromModel()
                        && !drillDownDimension.isNecessary()) {
                    continue;
                }
                drillDownDimensions.add(drillDownDimension);
            }
        }
        ModelResp modelResp = modelService.getModel(metricResp.getModelId());
        if (modelResp.getDrillDownDimensions() == null) {
            return drillDownDimensions;
        }
        for (DrillDownDimension drillDownDimension : modelResp.getDrillDownDimensions()) {
            if (!drillDownDimensions.stream().map(DrillDownDimension::getDimensionId)
                    .collect(Collectors.toList()).contains(drillDownDimension.getDimensionId())) {
                drillDownDimension.setInheritedFromModel(true);
                drillDownDimensions.add(drillDownDimension);
            }
        }
        return drillDownDimensions;
    }

    @Override
    public List<DrillDownDimension> getDrillDownDimension(Long metricId, User user) {
        MetricDO metric = metricRepository.getMetricById(metricId);
        if (metric == null) {
            throw new InvalidArgumentException("Metric does not exist");
        }
        modelService.requireModelViewer(metric.getModelId(), user);
        return getDrillDownDimension(metricId);
    }

    @Override
    public void saveMetricQueryDefaultConfig(MetricQueryDefaultConfig defaultConfig, User user) {
        if (defaultConfig == null || defaultConfig.getMetricId() == null) {
            throw new InvalidArgumentException("Metric id is required");
        }
        MetricDO metric = metricRepository.getMetricById(defaultConfig.getMetricId());
        if (metric == null) {
            throw new InvalidArgumentException("Metric does not exist");
        }
        modelService.requireModelViewer(metric.getModelId(), user);
        MetricQueryDefaultConfigDO defaultConfigDO =
                metricRepository.getDefaultQueryConfig(defaultConfig.getMetricId(), user.getName());
        if (defaultConfigDO == null) {
            defaultConfigDO = new MetricQueryDefaultConfigDO();
            defaultConfig.createdBy(user.getName());
            BeanMapper.mapper(defaultConfig, defaultConfigDO);
            metricRepository.saveDefaultQueryConfig(defaultConfigDO);
        } else {
            defaultConfig.setId(defaultConfigDO.getId());
            defaultConfig.updatedBy(user.getName());
            BeanMapper.mapper(defaultConfig, defaultConfigDO);
            metricRepository.updateDefaultQueryConfig(defaultConfigDO);
        }
    }

    @Override
    public MetricQueryDefaultConfig getMetricQueryDefaultConfig(Long metricId, User user) {
        MetricDO metric = metricRepository.getMetricById(metricId);
        if (metric == null) {
            throw new InvalidArgumentException("Metric does not exist");
        }
        modelService.requireModelViewer(metric.getModelId(), user);
        MetricQueryDefaultConfigDO metricQueryDefaultConfigDO =
                metricRepository.getDefaultQueryConfig(metricId, user.getName());
        MetricQueryDefaultConfig metricQueryDefaultConfig = new MetricQueryDefaultConfig();
        BeanMapper.mapper(metricQueryDefaultConfigDO, metricQueryDefaultConfig);
        return metricQueryDefaultConfig;
    }

    private void checkExist(List<MetricReq> metricReqs) {
        Long modelId = metricReqs.get(0).getModelId();
        MetaFilter metaFilter = new MetaFilter();
        metaFilter.setModelIds(Lists.newArrayList(modelId));
        List<MetricResp> metricResps = getMetrics(metaFilter);
        Map<String, MetricResp> bizNameMap = metricResps.stream()
                .collect(Collectors.toMap(MetricResp::getBizName, a -> a, (k1, k2) -> k1));
        Map<String, MetricResp> nameMap = metricResps.stream()
                .collect(Collectors.toMap(MetricResp::getName, a -> a, (k1, k2) -> k1));
        for (MetricBaseReq metricReq : metricReqs) {
            if (bizNameMap.containsKey(metricReq.getBizName())) {
                MetricResp metricResp = bizNameMap.get(metricReq.getBizName());
                if (!metricResp.getId().equals(metricReq.getId())) {
                    throw new RuntimeException(String.format("该模型下存在相同的指标字段名:%s 创建人:%s",
                            metricReq.getBizName(), metricResp.getCreatedBy()));
                }
            }
            if (nameMap.containsKey(metricReq.getName())) {
                MetricResp metricResp = nameMap.get(metricReq.getName());
                if (!metricResp.getId().equals(metricReq.getId())) {
                    throw new RuntimeException(String.format("该模型下存在相同的指标名:%s 创建人:%s",
                            metricReq.getName(), metricResp.getCreatedBy()));
                }
            }
        }
    }

    private void requireMetricRequestAccess(List<MetricReq> requests, User user) {
        if (CollectionUtils.isEmpty(requests)) {
            throw new InvalidArgumentException("Metric requests must not be empty");
        }
        Set<Long> modelIds =
                requests.stream().map(MetricReq::getModelId).collect(Collectors.toSet());
        requireSingleModelBatch(modelIds, "Metric");
        requireModelAdmins(modelIds, user);
    }

    private void requireMetricUpdateAccess(List<MetricReq> requests, User user) {
        if (CollectionUtils.isEmpty(requests) || requests.stream()
                .anyMatch(request -> request == null || request.getId() == null)) {
            throw new InvalidArgumentException("Metric ids are required");
        }
        Set<Long> modelIds = new HashSet<>();
        for (MetricReq request : requests) {
            MetricDO persisted = metricRepository.getMetricById(request.getId());
            requirePersistedMetricAccess(persisted, request.getModelId(), user);
            modelIds.add(persisted.getModelId());
        }
        requireSingleModelBatch(modelIds, "Metric");
    }

    private void requirePersistedMetricAccess(MetricDO persisted, Long requestedModelId,
            User user) {
        if (persisted == null) {
            throw new InvalidArgumentException("Metric does not exist");
        }
        if (!Objects.equals(persisted.getModelId(), requestedModelId)) {
            throw new InvalidArgumentException("Metric model cannot be changed");
        }
        requireModelAdmin(persisted.getModelId(), user);
    }

    private void requireCompleteMetricBatch(List<Long> requestedIds, List<MetricDO> metrics) {
        Set<Long> uniqueIds = positiveIds(requestedIds, "Metric");
        Set<Long> foundIds = CollectionUtils.isEmpty(metrics) ? Set.of()
                : metrics.stream().map(MetricDO::getId).collect(Collectors.toSet());
        if (!foundIds.containsAll(uniqueIds)) {
            throw new InvalidArgumentException("One or more metrics do not exist");
        }
    }

    private Set<Long> positiveIds(List<Long> ids, String field) {
        if (CollectionUtils.isEmpty(ids) || ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new InvalidArgumentException(field + " ids must be positive");
        }
        return Set.copyOf(ids);
    }

    private void requireModelAdmins(Collection<Long> modelIds, User user) {
        if (CollectionUtils.isEmpty(modelIds)) {
            throw new InvalidArgumentException("Model ids must not be empty");
        }
        modelIds.forEach(modelId -> requireModelAdmin(modelId, user));
    }

    private void requireModelAdmin(Long modelId, User user) {
        modelService.requireModelAdmin(modelId, user);
    }

    private void requireSingleModelBatch(Set<Long> modelIds, String itemType) {
        if (modelIds.size() != 1) {
            throw new InvalidArgumentException(itemType + " batch must belong to one model");
        }
    }

    private List<Long> restrictToAccessibleModels(List<Long> requestedModelIds, User user) {
        Set<Long> accessible = modelService.getAccessibleModelIds(user, AuthType.VIEWER);
        if (CollectionUtils.isEmpty(requestedModelIds)) {
            return accessible.isEmpty() ? Lists.newArrayList(-1L) : new ArrayList<>(accessible);
        }
        Set<Long> requested = new HashSet<>(positiveIds(requestedModelIds, "Model"));
        requested.retainAll(accessible);
        return requested.isEmpty() ? Lists.newArrayList(-1L) : new ArrayList<>(requested);
    }

    private List<MetricResp> convertList(List<MetricDO> metricDOS) {
        return convertList(metricDOS, Lists.newArrayList());
    }

    private List<MetricResp> convertList(List<MetricDO> metricDOS, List<Long> collect) {
        List<MetricResp> metricResps = Lists.newArrayList();
        List<Long> modelIds =
                metricDOS.stream().map(MetricDO::getModelId).collect(Collectors.toList());
        ModelFilter modelFilter = new ModelFilter(false, modelIds);
        Map<Long, ModelResp> modelMap = modelService.getModelMap(modelFilter);
        if (!CollectionUtils.isEmpty(metricDOS)) {
            metricResps = metricDOS.stream().map(
                    metricDO -> MetricConverter.convert2MetricResp(metricDO, modelMap, collect))
                    .collect(Collectors.toList());
        }
        return metricResps;
    }

    @Override
    public void sendMetricEventBatch(List<Long> modelIds, EventType eventType, User user) {
        MetricFilter metricFilter = new MetricFilter();
        metricFilter.setModelIds(modelIds);
        List<MetricDO> metricDOS = queryMetric(metricFilter);
        sendEventBatch(metricDOS, eventType, user);
    }

    @Override
    public List<MetricResp> queryMetrics(MetricsFilter metricsFilter) {
        List<MetricDO> metricDOS = metricRepository.getMetrics(metricsFilter);
        return convertList(metricDOS, new ArrayList<>());
    }

    @Override
    public DataEvent getDataEvent() {
        MetricsFilter metricsFilter = new MetricsFilter();
        List<MetricDO> metricDOS = metricRepository.getMetrics(metricsFilter);
        return getDataEvent(metricDOS, EventType.ADD, User.getDefaultUser());
    }

    private DataEvent getDataEvent(List<MetricDO> metricDOS, EventType eventType, User user) {
        List<DataItem> dataItems = metricDOS.stream().map(this::getDataItem)
                .filter(Objects::nonNull).collect(Collectors.toList());
        return new DataEvent(this, dataItems, eventType, user.getName());
    }

    private void sendEventBatch(List<MetricDO> metricDOS, EventType eventType, User user) {
        DataEvent dataEvent = getDataEvent(metricDOS, eventType, user);
        eventPublisher.publishEvent(dataEvent);
    }

    private void sendEvent(DataItem dataItem, EventType eventType, User user) {
        eventPublisher.publishEvent(
                new DataEvent(this, Lists.newArrayList(dataItem), eventType, user.getName()));
    }

    private DataItem getDataItem(MetricDO metricDO) {
        ModelResp modelResp = modelService.getModel(metricDO.getModelId());
        if (modelResp == null) {
            return null;
        }
        MetricResp metricResp = MetricConverter.convert2MetricResp(metricDO,
                ImmutableMap.of(modelResp.getId(), modelResp), Lists.newArrayList());
        fillDefaultAgg(metricResp);
        return DataItem.builder().id(metricResp.getId().toString()).name(metricResp.getName())
                .bizName(metricResp.getBizName()).modelId(metricResp.getModelId().toString())
                .domainId(metricResp.getDomainId().toString()).type(TypeEnums.METRIC)
                .defaultAgg(metricResp.getDefaultAgg()).build();
    }

    @Override
    public void batchFillMetricDefaultAgg(List<MetricResp> metricResps,
            List<ModelResp> modelResps) {
        Map<Long, ModelResp> modelRespMap =
                modelResps.stream().collect(Collectors.toMap(ModelResp::getId, m -> m));
        for (MetricResp metricResp : metricResps) {
            fillDefaultAgg(metricResp, modelRespMap.get(metricResp.getModelId()));
        }
    }

    private void fillDefaultAgg(MetricResp metricResp) {
        if (MetricDefineType.MEASURE.equals(metricResp.getMetricDefineType())) {
            Long modelId = metricResp.getModelId();
            ModelResp modelResp = modelService.getModel(modelId);
            fillDefaultAgg(metricResp, modelResp);
        }
    }

    private void fillDefaultAgg(MetricResp metricResp, ModelResp modelResp) {
        metricResp.setDefaultAgg(getDefaultAgg(metricResp, modelResp));
    }

    private String getDefaultAgg(MetricResp metricResp, ModelResp modelResp) {
        if (modelResp == null || (Objects.nonNull(metricResp.getDefaultAgg())
                && !metricResp.getDefaultAgg().isEmpty())) {
            return metricResp.getDefaultAgg();
        }
        // Measure define will get from first measure
        if (MetricDefineType.MEASURE.equals(metricResp.getMetricDefineType())) {
            List<Measure> measures = modelResp.getModelDetail().getMeasures();
            List<Measure> measureParams = metricResp.getMetricDefineByMeasureParams().getMeasures();
            if (CollectionUtils.isEmpty(measureParams)) {
                return null;
            }
            Measure firstMeasure = measureParams.get(0);

            for (Measure measure : measures) {
                if (measure.getBizName().equalsIgnoreCase(firstMeasure.getBizName())) {
                    return measure.getAgg();
                }
            }
        }

        return null;
    }

    @Override
    public QueryStructReq convert(QueryMetricReq queryMetricReq) {
        // 1. If a domainId exists, the modelIds obtained from the domainId.
        Set<Long> modelIdsByDomainId = getModelIdsByDomainId(queryMetricReq);

        // 2. get metrics and dimensions
        List<MetricResp> metricResps = getMetricResps(queryMetricReq, modelIdsByDomainId);

        List<DimensionResp> dimensionResps = getDimensionResps(modelIdsByDomainId);
        Map<Long, DimensionResp> dimensionRespMap =
                dimensionResps.stream().collect(Collectors.toMap(DimensionResp::getId, d -> d));

        // 3. choose ModelCluster
        Set<Long> modelIds = getModelIds(modelIdsByDomainId, metricResps, dimensionResps);
        ModelCluster modelCluster = getModelCluster(metricResps, modelIds);
        if (modelCluster == null) {
            throw new IllegalArgumentException(
                    "Invalid input parameters, unable to obtain valid metrics");
        }
        if (!modelCluster.isContainsPartitionDimensions()) {
            queryMetricReq.setDateInfo(null);
        } else {
            // set date field
            DimensionResp partitionDimension = dimensionResps.stream()
                    .filter(entry -> modelCluster.getModelIds().contains(entry.getModelId()))
                    .filter(entry -> entry.getStatus().equals(StatusEnum.ONLINE.getCode()))
                    .filter(entry -> entry.isPartitionTime()).findFirst().orElse(null);
            if (partitionDimension != null) {
                queryMetricReq.getDateInfo().setDateField(partitionDimension.getName());
            }
        }
        // 4. set groups
        List<String> dimensionNames = dimensionResps.stream()
                .filter(entry -> modelCluster.getModelIds().contains(entry.getModelId()))
                .filter(entry -> queryMetricReq.getDimensionNames().contains(entry.getName())
                        || queryMetricReq.getDimensionNames().contains(entry.getBizName())
                        || queryMetricReq.getDimensionIds().contains(entry.getId()))
                .map(SchemaItem::getName).collect(Collectors.toList());

        QueryStructReq queryStructReq = new QueryStructReq();
        DateConf dateInfo = queryMetricReq.getDateInfo();
        if (Objects.nonNull(dateInfo) && dateInfo.isGroupByDate()) {
            queryStructReq.getGroups().add(dateInfo.getDateField());
        }
        if (!CollectionUtils.isEmpty(dimensionNames)) {
            queryStructReq.getGroups().addAll(dimensionNames);
        }
        // 5. set aggregators
        List<String> metricNames = metricResps.stream()
                .filter(entry -> modelCluster.getModelIds().contains(entry.getModelId()))
                .map(SchemaItem::getName).collect(Collectors.toList());
        if (CollectionUtils.isEmpty(metricNames)) {
            throw new IllegalArgumentException(
                    "Invalid input parameters, unable to obtain valid metrics");
        }
        List<Aggregator> aggregators = new ArrayList<>();
        for (String metricBizName : metricNames) {
            Aggregator aggregator = new Aggregator();
            aggregator.setColumn(metricBizName);
            aggregators.add(aggregator);
        }
        queryStructReq.setAggregators(aggregators);
        queryStructReq.setLimit(queryMetricReq.getLimit());
        // 6. set modelIds
        for (Long modelId : modelCluster.getModelIds()) {
            queryStructReq.addModelId(modelId);
        }
        List<Filter> filters = queryMetricReq.getFilters();
        for (Filter filter : filters) {
            if (StringUtils.isBlank(filter.getBizName())) {
                DimensionResp dimensionResp = dimensionRespMap.get(filter.getId());
                if (dimensionResp != null) {
                    filter.setBizName(dimensionResp.getBizName());
                }
            }
        }
        queryStructReq.setDimensionFilters(filters);
        // 7. set dateInfo
        queryStructReq.setDateInfo(dateInfo);
        queryStructReq.setQueryType(QueryType.AGGREGATE);
        return queryStructReq;
    }

    private ModelCluster getModelCluster(List<MetricResp> metricResps, Set<Long> modelIds) {
        Map<String, ModelCluster> modelClusterMap =
                ModelClusterBuilder.buildModelClusters(new ArrayList<>(modelIds));

        Map<String, List<SchemaItem>> modelClusterToMatchCount = new HashMap<>();
        for (ModelCluster modelCluster : modelClusterMap.values()) {
            for (MetricResp metricResp : metricResps) {
                if (modelCluster.getModelIds().contains(metricResp.getModelId())) {
                    modelClusterToMatchCount
                            .computeIfAbsent(modelCluster.getKey(), k -> new ArrayList<>())
                            .add(metricResp);
                }
            }
        }
        String keyWithMaxSize = modelClusterToMatchCount.entrySet().stream()
                .max(Comparator.comparingInt(entry -> entry.getValue().size()))
                .map(Map.Entry::getKey).orElse(null);

        return modelClusterMap.get(keyWithMaxSize);
    }

    private Set<Long> getModelIds(Set<Long> modelIdsByDomainId, List<MetricResp> metricResps,
            List<DimensionResp> dimensionResps) {
        Set<Long> result = new HashSet<>();
        if (org.apache.commons.collections.CollectionUtils.isNotEmpty(modelIdsByDomainId)) {
            result.addAll(modelIdsByDomainId);
            return result;
        }
        Set<Long> metricModelIds =
                metricResps.stream().map(entry -> entry.getModelId()).collect(Collectors.toSet());
        result.addAll(metricModelIds);

        Set<Long> dimensionModelIds = dimensionResps.stream().map(entry -> entry.getModelId())
                .collect(Collectors.toSet());
        result.addAll(dimensionModelIds);
        return result;
    }

    private List<DimensionResp> getDimensionResps(Set<Long> modelIds) {
        DimensionsFilter dimensionsFilter = new DimensionsFilter();
        dimensionsFilter.setModelIds(new ArrayList<>(modelIds));
        return dimensionService.queryDimensions(dimensionsFilter);
    }

    private List<MetricResp> getMetricResps(QueryMetricReq queryMetricReq, Set<Long> modelIds) {
        MetricsFilter metricsFilter = new MetricsFilter();
        BeanUtils.copyProperties(queryMetricReq, metricsFilter);
        metricsFilter.setModelIds(new ArrayList<>(modelIds));
        return queryMetrics(metricsFilter);
    }

    private Set<Long> getModelIdsByDomainId(QueryMetricReq queryMetricReq) {
        List<ModelResp> modelResps = modelService
                .getAllModelByDomainIds(Collections.singletonList(queryMetricReq.getDomainId()));
        return modelResps.stream().map(ModelResp::getId).collect(Collectors.toSet());
    }

    private boolean isChange(MetricReq metricReq, MetricResp metricResp) {
        boolean isNameChange = !metricReq.getName().equals(metricResp.getName());
        boolean isBizNameChange = !Objects.equals(metricReq.getMetricDefineByMeasureParams(),
                metricResp.getMetricDefineByMeasureParams());
        return isNameChange || isBizNameChange;
    }
}
