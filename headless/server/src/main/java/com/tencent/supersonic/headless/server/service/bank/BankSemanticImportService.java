package com.tencent.supersonic.headless.server.service.bank;

import com.google.common.collect.Lists;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.DataTypeEnums;
import com.tencent.supersonic.common.pojo.enums.StatusEnum;
import com.tencent.supersonic.common.pojo.enums.TypeEnums;
import com.tencent.supersonic.headless.api.pojo.DataSetDetail;
import com.tencent.supersonic.headless.api.pojo.DataSetModelConfig;
import com.tencent.supersonic.headless.api.pojo.DimValueMap;
import com.tencent.supersonic.headless.api.pojo.DimensionTimeTypeParams;
import com.tencent.supersonic.headless.api.pojo.DrillDownDimension;
import com.tencent.supersonic.headless.api.pojo.MetaFilter;
import com.tencent.supersonic.headless.api.pojo.MetricDefineByFieldParams;
import com.tencent.supersonic.headless.api.pojo.RelateDimension;
import com.tencent.supersonic.headless.api.pojo.enums.DimensionType;
import com.tencent.supersonic.headless.api.pojo.enums.MetricDefineType;
import com.tencent.supersonic.headless.api.pojo.enums.SemanticType;
import com.tencent.supersonic.headless.api.pojo.request.DataSetReq;
import com.tencent.supersonic.headless.api.pojo.request.DictItemFilter;
import com.tencent.supersonic.headless.api.pojo.request.DictItemReq;
import com.tencent.supersonic.headless.api.pojo.request.DimensionReq;
import com.tencent.supersonic.headless.api.pojo.request.MetricReq;
import com.tencent.supersonic.headless.api.pojo.request.TermReq;
import com.tencent.supersonic.headless.api.pojo.response.DataSetResp;
import com.tencent.supersonic.headless.api.pojo.response.DictItemResp;
import com.tencent.supersonic.headless.api.pojo.response.DimensionResp;
import com.tencent.supersonic.headless.api.pojo.response.MetricResp;
import com.tencent.supersonic.headless.api.pojo.response.ModelResp;
import com.tencent.supersonic.headless.api.pojo.response.TermResp;
import com.tencent.supersonic.headless.server.pojo.bank.BankImportError;
import com.tencent.supersonic.headless.server.pojo.bank.BankSemanticImportConfig;
import com.tencent.supersonic.headless.server.pojo.bank.BankSemanticImportReport;
import com.tencent.supersonic.headless.server.pojo.bank.BankWorkbookData;
import com.tencent.supersonic.headless.server.service.DataSetService;
import com.tencent.supersonic.headless.server.service.DictConfService;
import com.tencent.supersonic.headless.server.service.DimensionService;
import com.tencent.supersonic.headless.server.service.MetricService;
import com.tencent.supersonic.headless.server.service.ModelService;
import com.tencent.supersonic.headless.server.service.TermService;
import com.tencent.supersonic.headless.server.utils.NameCheckUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
public class BankSemanticImportService {

    static final String DATE_DIMENSION = "bank_data_date";
    static final String ORGANIZATION_DIMENSION = "bank_organization";
    static final String INDICATOR_DIMENSION = "bank_indicator";

    private final BankWorkbookParser parser;
    private final ModelService modelService;
    private final DimensionService dimensionService;
    private final MetricService metricService;
    private final DataSetService dataSetService;
    private final TermService termService;
    private final DictConfService dictConfService;

    public BankSemanticImportService(BankWorkbookParser parser, ModelService modelService,
            DimensionService dimensionService, MetricService metricService,
            DataSetService dataSetService, TermService termService,
            DictConfService dictConfService) {
        this.parser = parser;
        this.modelService = modelService;
        this.dimensionService = dimensionService;
        this.metricService = metricService;
        this.dataSetService = dataSetService;
        this.termService = termService;
        this.dictConfService = dictConfService;
    }

    public BankSemanticImportReport validate(byte[] content, String fileName) {
        BankSemanticImportReport report = toReport(parser.parse(content, fileName));
        report.setDryRun(true);
        report.setSuccess(report.getErrors().isEmpty());
        return report;
    }

    @Transactional(rollbackFor = Exception.class)
    public BankSemanticImportReport importWorkbook(byte[] content, String fileName,
            BankSemanticImportConfig config, User user) {
        BankWorkbookData workbook = parser.parse(content, fileName);
        BankSemanticImportReport report = toReport(workbook);
        report.setModelId(config.getModelId());
        validateTarget(config, workbook, report);
        if (!report.getErrors().isEmpty()) {
            return report;
        }

        try {
            ModelResp model = modelService.getModel(config.getModelId());
            Map<String, DimensionResp> dimensions =
                    upsertDimensions(workbook, config, user, report);
            Map<String, MetricResp> metrics =
                    upsertMetrics(workbook, config, user, report, dimensions);
            upsertKnowledge(dimensions, user, report);
            upsertTerms(workbook, model.getDomainId(), metrics, user, report);
            DataSetResp dataSet =
                    upsertDataSet(workbook, config, model, dimensions, metrics, user, report);
            report.setDataSetId(dataSet.getId());
            report.setSuccess(true);
            log.info("Bank semantic import succeeded: file={}, checksum={}, modelId={}, counts={}",
                    fileName, workbook.getChecksum(), config.getModelId(), report.getCreated());
            return report;
        } catch (Exception e) {
            report.getErrors().add(new BankImportError("import", 0, "", "IMPORT_ROLLED_BACK",
                    "导入失败，事务已回滚: " + rootMessage(e), ""));
            report.setSuccess(false);
            log.error("Bank semantic import rolled back: file={}, checksum={}, modelId={}",
                    fileName, workbook.getChecksum(), config.getModelId(), e);
            throw new BankSemanticImportException("Bank semantic import failed", e, report);
        }
    }

    private void validateTarget(BankSemanticImportConfig config, BankWorkbookData workbook,
            BankSemanticImportReport report) {
        if (config.getModelId() == null) {
            addTargetError(report, "modelId", "MODEL_REQUIRED", "modelId 不能为空", "");
            return;
        }
        ModelResp model = modelService.getModel(config.getModelId());
        if (model == null) {
            addTargetError(report, "modelId", "MODEL_NOT_FOUND", "目标语义模型不存在",
                    String.valueOf(config.getModelId()));
            return;
        }
        if (StringUtils.isBlank(config.getDataSetName())
                || StringUtils.isBlank(config.getDataSetBizName())) {
            addTargetError(report, "dataset", "DATASET_REQUIRED", "数据集名称和业务名称不能为空", "");
        }
        List<String> fields = Arrays.asList(config.getDateField(), config.getOrganizationField(),
                config.getIndicatorCodeField(), config.getIndicatorValueField());
        for (String field : fields) {
            if (!NameCheckUtils.isValidIdentifier(field)) {
                addTargetError(report, "fieldMapping", "INVALID_FIELD", "物理字段名只能包含字母、数字和下划线",
                        field);
            } else if (!model.getFieldList().contains(field)) {
                addTargetError(report, "fieldMapping", "MODEL_FIELD_NOT_FOUND", "目标模型中不存在物理字段",
                        field);
            }
        }
        if (workbook.getIndicators().size() > 3000) {
            addTargetError(report, "indicators", "TOO_MANY_INDICATORS", "单次导入指标数不能超过 3000",
                    String.valueOf(workbook.getIndicators().size()));
        }
    }

    private Map<String, DimensionResp> upsertDimensions(BankWorkbookData workbook,
            BankSemanticImportConfig config, User user, BankSemanticImportReport report)
            throws Exception {
        MetaFilter filter = new MetaFilter(Lists.newArrayList(config.getModelId()));
        Map<String, DimensionResp> existing = dimensionService.getDimensions(filter).stream()
                .collect(Collectors.toMap(DimensionResp::getBizName, Function.identity(),
                        (left, right) -> left, LinkedHashMap::new));
        List<DimensionReq> requests = Arrays.asList(dateDimension(config),
                organizationDimension(workbook, config), indicatorDimension(workbook, config));
        Map<String, DimensionResp> result = new LinkedHashMap<>();
        for (DimensionReq request : requests) {
            DimensionResp current = existing.get(request.getBizName());
            if (current == null) {
                DimensionResp created = dimensionService.createDimension(request, user);
                result.put(request.getBizName(), created);
                report.increment(report.getCreated(), "dimensions");
            } else if (sameDimension(current, request)) {
                result.put(request.getBizName(), current);
                report.increment(report.getSkipped(), "dimensions");
            } else {
                request.setId(current.getId());
                dimensionService.updateDimension(request, user);
                result.put(request.getBizName(), dimensionService.getDimension(current.getId()));
                report.increment(report.getUpdated(), "dimensions");
            }
        }
        return result;
    }

    private Map<String, MetricResp> upsertMetrics(BankWorkbookData workbook,
            BankSemanticImportConfig config, User user, BankSemanticImportReport report,
            Map<String, DimensionResp> dimensions) throws Exception {
        MetaFilter filter = new MetaFilter(Lists.newArrayList(config.getModelId()));
        Map<String, MetricResp> existing = metricService.getMetrics(filter).stream()
                .collect(Collectors.toMap(MetricResp::getBizName, Function.identity(),
                        (left, right) -> left, LinkedHashMap::new));
        Map<String, MetricResp> result = new LinkedHashMap<>();
        List<Long> relatedDimensionIds = Arrays.asList(dimensions.get(DATE_DIMENSION).getId(),
                dimensions.get(ORGANIZATION_DIMENSION).getId());
        for (BankWorkbookData.Indicator indicator : workbook.getIndicators()) {
            MetricReq request = metric(indicator, config, relatedDimensionIds);
            MetricResp current = existing.get(request.getBizName());
            if (current == null) {
                MetricResp created = metricService.createMetric(request, user);
                result.put(indicator.getCode(), created);
                report.increment(report.getCreated(), "metrics");
            } else if (sameMetric(current, request)) {
                result.put(indicator.getCode(), current);
                report.increment(report.getSkipped(), "metrics");
            } else {
                request.setId(current.getId());
                MetricResp updated = metricService.updateMetric(request, user);
                result.put(indicator.getCode(), updated);
                report.increment(report.getUpdated(), "metrics");
            }
        }
        return result;
    }

    private void upsertKnowledge(Map<String, DimensionResp> dimensions, User user,
            BankSemanticImportReport report) {
        for (String key : Arrays.asList(ORGANIZATION_DIMENSION, INDICATOR_DIMENSION)) {
            DimensionResp dimension = dimensions.get(key);
            DictItemFilter filter = DictItemFilter.builder().type(TypeEnums.DIMENSION)
                    .itemId(dimension.getId()).build();
            List<DictItemResp> existing = dictConfService.queryDictConf(filter, user);
            if (existing.isEmpty()) {
                dictConfService.addDictConf(DictItemReq.builder().type(TypeEnums.DIMENSION)
                        .itemId(dimension.getId()).status(StatusEnum.ONLINE).build(), user);
                report.increment(report.getCreated(), "knowledgeConfigs");
            } else if (!StatusEnum.ONLINE.equals(existing.get(0).getStatus())) {
                DictItemResp current = existing.get(0);
                dictConfService.editDictConf(DictItemReq.builder().id(current.getId())
                        .type(TypeEnums.DIMENSION).itemId(dimension.getId())
                        .status(StatusEnum.ONLINE).config(current.getConfig()).build(), user);
                report.increment(report.getUpdated(), "knowledgeConfigs");
            } else {
                report.increment(report.getSkipped(), "knowledgeConfigs");
            }
        }
    }

    private void upsertTerms(BankWorkbookData workbook, Long domainId,
            Map<String, MetricResp> metrics, User user, BankSemanticImportReport report) {
        Map<String, TermResp> existing = termService.getTerms(domainId, null).stream()
                .collect(Collectors.toMap(TermResp::getName, Function.identity(),
                        (left, right) -> left, LinkedHashMap::new));
        List<TermReq> terms = new ArrayList<>();
        for (BankWorkbookData.Indicator indicator : workbook.getIndicators()) {
            TermReq term = new TermReq();
            term.setDomainId(domainId);
            term.setName(indicator.getName());
            term.setDescription(indicator.getDescription() + "；单位：" + indicator.getUnit());
            term.setAlias(Collections.singletonList(indicator.getCode()));
            term.setRelatedMetrics(
                    Collections.singletonList(metrics.get(indicator.getCode()).getId()));
            terms.add(term);
        }
        for (BankWorkbookData.DerivedRule rule : workbook.getDerivedRules()) {
            TermReq term = new TermReq();
            term.setDomainId(domainId);
            term.setName(rule.getName());
            term.setDescription(rule.getDescription());
            terms.add(term);
        }
        for (TermReq term : terms) {
            TermResp current = existing.get(term.getName());
            if (current != null && sameTerm(current, term)) {
                report.increment(report.getSkipped(), "terms");
                continue;
            }
            if (current != null) {
                term.setId(current.getId());
                report.increment(report.getUpdated(), "terms");
            } else {
                report.increment(report.getCreated(), "terms");
            }
            termService.saveOrUpdate(term, user);
        }
    }

    private DataSetResp upsertDataSet(BankWorkbookData workbook, BankSemanticImportConfig config,
            ModelResp model, Map<String, DimensionResp> dimensions, Map<String, MetricResp> metrics,
            User user, BankSemanticImportReport report) {
        List<DataSetResp> dataSets = dataSetService.getDataSetList(model.getDomainId(),
                Arrays.asList(StatusEnum.ONLINE.getCode(), StatusEnum.OFFLINE.getCode()));
        DataSetResp current = dataSets.stream()
                .filter(item -> config.getDataSetBizName().equals(item.getBizName())).findFirst()
                .orElse(null);
        DataSetReq request = new DataSetReq();
        request.setName(config.getDataSetName());
        request.setBizName(config.getDataSetBizName());
        request.setDomainId(model.getDomainId());
        request.setDescription("由银行指标工作簿导入，包含 " + workbook.getIndicators().size() + " 项指标、"
                + workbook.getOrganizations().size() + " 家机构");
        request.setStatus(StatusEnum.ONLINE.getCode());
        request.setTypeEnum(TypeEnums.DATASET);
        List<String> admins =
                current == null ? new ArrayList<>() : new ArrayList<>(current.getAdmins());
        if (!admins.contains(user.getName())) {
            admins.add(user.getName());
        }
        request.setAdmins(admins);
        DataSetModelConfig modelConfig = new DataSetModelConfig(model.getId(),
                dimensions.values().stream().map(DimensionResp::getId).collect(Collectors.toList()),
                metrics.values().stream().map(MetricResp::getId).collect(Collectors.toList()));
        DataSetDetail detail = new DataSetDetail();
        detail.setDataSetModelConfigs(Collections.singletonList(modelConfig));
        request.setDataSetDetail(detail);
        if (current == null) {
            report.increment(report.getCreated(), "datasets");
            return dataSetService.save(request, user);
        }
        request.setId(current.getId());
        if (sameDataSet(current, request)) {
            report.increment(report.getSkipped(), "datasets");
            return current;
        }
        report.increment(report.getUpdated(), "datasets");
        return dataSetService.update(request, user);
    }

    private DimensionReq dateDimension(BankSemanticImportConfig config) {
        DimensionReq request = baseDimension(config, DATE_DIMENSION, "数据日期", config.getDateField());
        request.setType(DimensionType.partition_time.name());
        request.setSemanticType(SemanticType.DATE.name());
        request.setDataType(DataTypeEnums.DATE);
        request.setTypeParams(new DimensionTimeTypeParams());
        return request;
    }

    private DimensionReq organizationDimension(BankWorkbookData workbook,
            BankSemanticImportConfig config) {
        DimensionReq request =
                baseDimension(config, ORGANIZATION_DIMENSION, "机构", config.getOrganizationField());
        request.setDescription("农商银行机构编号及名称");
        request.setDimValueMaps(workbook.getOrganizations().stream()
                .map(item -> valueMap(item.getCode(), item.getName()))
                .collect(Collectors.toList()));
        return request;
    }

    private DimensionReq indicatorDimension(BankWorkbookData workbook,
            BankSemanticImportConfig config) {
        DimensionReq request =
                baseDimension(config, INDICATOR_DIMENSION, "指标", config.getIndicatorCodeField());
        request.setDescription("银行指标编号及名称");
        request.setDimValueMaps(workbook.getIndicators().stream()
                .map(item -> valueMap(item.getCode(), item.getName()))
                .collect(Collectors.toList()));
        return request;
    }

    private DimensionReq baseDimension(BankSemanticImportConfig config, String bizName, String name,
            String expr) {
        DimensionReq request = new DimensionReq();
        request.setModelId(config.getModelId());
        request.setBizName(bizName);
        request.setName(name);
        request.setDescription(name);
        request.setExpr(expr);
        request.setType(DimensionType.categorical.name());
        request.setSemanticType(SemanticType.CATEGORY.name());
        request.setDataType(DataTypeEnums.VARCHAR);
        request.setStatus(StatusEnum.ONLINE.getCode());
        return request;
    }

    private DimValueMap valueMap(String code, String name) {
        DimValueMap valueMap = new DimValueMap();
        valueMap.setTechName(code);
        valueMap.setBizName(name);
        valueMap.setValue(code);
        valueMap.setAlias(Arrays.asList(code, name));
        return valueMap;
    }

    private MetricReq metric(BankWorkbookData.Indicator indicator, BankSemanticImportConfig config,
            List<Long> relatedDimensionIds) {
        MetricReq request = new MetricReq();
        request.setModelId(config.getModelId());
        request.setName(indicator.getName());
        request.setBizName(indicator.getCode().toLowerCase());
        request.setAlias(indicator.getCode());
        request.setDescription(indicator.getDescription());
        request.setStatus(StatusEnum.ONLINE.getCode());
        request.setClassifications(Collections.singletonList("银行指标"));
        Map<String, Object> ext = new LinkedHashMap<>();
        ext.put("indicatorCode", indicator.getCode());
        ext.put("unit", indicator.getUnit());
        request.setExt(ext);
        MetricDefineByFieldParams params = new MetricDefineByFieldParams();
        params.setExpr("SUM(CASE WHEN " + config.getIndicatorCodeField() + " = '"
                + sqlLiteral(indicator.getCode()) + "' THEN " + config.getIndicatorValueField()
                + " ELSE 0 END)");
        request.setMetricDefineByFieldParams(params);
        request.setMetricDefineType(MetricDefineType.FIELD);
        RelateDimension relateDimension = new RelateDimension();
        relatedDimensionIds.forEach(
                id -> relateDimension.getDrillDownDimensions().add(new DrillDownDimension(id)));
        request.setRelateDimension(relateDimension);
        return request;
    }

    private boolean sameDimension(DimensionResp current, DimensionReq request) {
        return Objects.equals(current.getName(), request.getName())
                && Objects.equals(current.getDescription(), request.getDescription())
                && Objects.equals(current.getExpr(), request.getExpr())
                && Objects.equals(current.getType().name(), request.getType())
                && Objects.equals(current.getSemanticType(), request.getSemanticType())
                && Objects.equals(current.getDataType(), request.getDataType())
                && Objects.equals(current.getTypeParams(), request.getTypeParams())
                && Objects.equals(current.getDimValueMaps(), request.getDimValueMaps())
                && Objects.equals(current.getStatus(), request.getStatus());
    }

    private boolean sameMetric(MetricResp current, MetricReq request) {
        return Objects.equals(current.getName(), request.getName())
                && Objects.equals(current.getDescription(), request.getDescription())
                && Objects.equals(current.getAlias(), request.getAlias())
                && Objects.equals(current.getMetricDefineType(), request.getMetricDefineType())
                && Objects.equals(current.getExpr(),
                        request.getMetricDefineByFieldParams().getExpr())
                && Objects.equals(current.getExt(), request.getExt())
                && Objects.equals(current.getRelaDimensionIdKey(), relationKey(request))
                && Objects.equals(current.getStatus(), request.getStatus());
    }

    private String relationKey(MetricReq request) {
        return request.getRelateDimension().getDrillDownDimensions().stream()
                .map(DrillDownDimension::getDimensionId).map(String::valueOf)
                .collect(Collectors.joining(","));
    }

    private boolean sameTerm(TermResp current, TermReq request) {
        return Objects.equals(current.getDescription(), request.getDescription())
                && Objects.equals(current.getAlias(), request.getAlias())
                && Objects.equals(current.getRelatedMetrics(), request.getRelatedMetrics())
                && Objects.equals(current.getRelateDimensions(), request.getRelateDimensions());
    }

    private boolean sameDataSet(DataSetResp current, DataSetReq request) {
        return Objects.equals(current.getName(), request.getName())
                && Objects.equals(current.getDescription(), request.getDescription())
                && Objects.equals(current.getDataSetDetail(), request.getDataSetDetail())
                && Objects.equals(current.getAdmins(), request.getAdmins())
                && Objects.equals(current.getStatus(), request.getStatus());
    }

    private BankSemanticImportReport toReport(BankWorkbookData data) {
        BankSemanticImportReport report = new BankSemanticImportReport();
        report.setFileName(data.getFileName());
        report.setChecksum(data.getChecksum());
        report.setOrganizationCount(data.getOrganizations().size());
        report.setIndicatorCount(data.getIndicators().size());
        report.setDerivedRuleCount(data.getDerivedRules().size());
        report.setFactCount(data.getFactCount());
        report.setQuestionCount(data.getQuestionCount());
        report.setMinDate(data.getMinDate());
        report.setMaxDate(data.getMaxDate());
        report.setQuestionTypeCounts(new LinkedHashMap<>(data.getQuestionTypeCounts()));
        report.setDifficultyCounts(new LinkedHashMap<>(data.getDifficultyCounts()));
        report.setErrors(new ArrayList<>(data.getErrors()));
        return report;
    }

    private void addTargetError(BankSemanticImportReport report, String column, String code,
            String message, String value) {
        report.getErrors().add(new BankImportError("target", 0, column, code, message, value));
    }

    private String sqlLiteral(String value) {
        return value.replace("'", "''");
    }

    private String rootMessage(Exception error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName()
                : current.getMessage();
    }
}
