package com.tencent.supersonic.headless.server.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.common.pojo.Aggregator;
import com.tencent.supersonic.common.pojo.Filter;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.headless.api.pojo.request.QueryStructReq;
import com.tencent.supersonic.headless.api.pojo.response.DashboardResp;
import com.tencent.supersonic.headless.api.pojo.response.DataSetResp;
import com.tencent.supersonic.headless.server.service.DataSetService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class DashboardExportQueryValidator {

    private final DataSetService dataSetService;
    private final ObjectMapper objectMapper;

    public DashboardExportQueryValidator(DataSetService dataSetService, ObjectMapper objectMapper) {
        this.dataSetService = dataSetService;
        this.objectMapper = objectMapper;
    }

    public void validate(DashboardResp dashboard, List<QueryStructReq> queries) {
        JsonNode config = readConfig(dashboard);
        JsonNode components = config.path("components");
        if (!components.isArray() || components.size() != queries.size()) {
            reject();
        }
        for (int index = 0; index < components.size(); index++) {
            validateComponent(dashboard, config, components.get(index).path("query"),
                    queries.get(index));
        }
    }

    private void validateComponent(DashboardResp dashboard, JsonNode config, JsonNode stored,
            QueryStructReq requested) {
        if (!stored.isObject() || requested == null) {
            reject();
        }
        Long dataSetId = positiveLong(stored.path("dataSetId"));
        Long modelId = positiveLong(stored.path("modelId"));
        if (dataSetId == null || modelId == null
                || !Objects.equals(dataSetId, requested.getDataSetId())
                || !Set.of(modelId).equals(requested.getModelIdSet())) {
            reject();
        }
        DataSetResp dataSet = dataSetService.getDataSet(dataSetId);
        if (dataSet == null || !Objects.equals(dashboard.getDomainId(), dataSet.getDomainId())) {
            reject();
        }
        if (!semanticFields(stored.path("dimensions"))
                .equals(new LinkedHashSet<>(requested.getGroups()))) {
            reject();
        }
        if (!semanticAggregators(stored.path("metrics"))
                .equals(requestedAggregators(requested.getAggregators()))) {
            reject();
        }
        List<String> expectedFilters = filters(stored.path("dimensionFilters"));
        expectedFilters.addAll(globalFilters(config.path("globalFilters")));
        expectedFilters.sort(String::compareTo);
        List<String> actualFilters = requestedFilters(requested.getDimensionFilters());
        actualFilters.sort(String::compareTo);
        if (!expectedFilters.equals(actualFilters) || !canonicalDate(stored.path("dateInfo"))
                .equals(canonicalDate(objectMapper.valueToTree(requested.getDateInfo())))) {
            reject();
        }
    }

    private JsonNode readConfig(DashboardResp dashboard) {
        if (dashboard == null || StringUtils.isBlank(dashboard.getConfig())) {
            reject();
        }
        try {
            JsonNode config = objectMapper.readTree(dashboard.getConfig());
            if (config == null || !config.isObject()) {
                reject();
            }
            return config;
        } catch (InvalidArgumentException e) {
            throw e;
        } catch (Exception e) {
            reject();
            return objectMapper.createObjectNode();
        }
    }

    private Set<String> semanticFields(JsonNode fields) {
        Set<String> values = new LinkedHashSet<>();
        if (fields.isArray()) {
            fields.forEach(field -> values.add(fieldName(field)));
        }
        values.remove("");
        return values;
    }

    private Map<String, String> semanticAggregators(JsonNode metrics) {
        Map<String, String> values = new LinkedHashMap<>();
        if (metrics.isArray()) {
            metrics.forEach(metric -> {
                String field = fieldName(metric);
                if (!field.isEmpty()) {
                    values.put(field, metric.path("defaultAgg").asText("SUM").toUpperCase());
                }
            });
        }
        return values;
    }

    private Map<String, String> requestedAggregators(List<Aggregator> aggregators) {
        Map<String, String> values = new LinkedHashMap<>();
        if (aggregators != null) {
            aggregators.forEach(aggregator -> {
                if (aggregator != null && StringUtils.isNotBlank(aggregator.getColumn())) {
                    values.put(aggregator.getColumn(), aggregator.getFunc().name());
                }
            });
        }
        return values;
    }

    private List<String> filters(JsonNode filters) {
        List<String> values = new ArrayList<>();
        if (filters.isArray()) {
            filters.forEach(filter -> values.add(canonicalFilter(filter, null)));
        }
        return values;
    }

    private List<String> globalFilters(JsonNode filters) {
        List<String> values = new ArrayList<>();
        if (filters.isArray()) {
            filters.forEach(
                    filter -> values.add(canonicalFilter(filter, filter.path("field").asText())));
        }
        return values;
    }

    private List<String> requestedFilters(List<Filter> filters) {
        List<String> values = new ArrayList<>();
        if (filters != null) {
            filters.forEach(
                    filter -> values.add(canonicalFilter(objectMapper.valueToTree(filter), null)));
        }
        return values;
    }

    private String canonicalFilter(JsonNode filter, String fallbackField) {
        String field = fieldName(filter);
        if (field.isEmpty()) {
            field = StringUtils.defaultString(fallbackField);
        }
        String operator = filter.path("operator").asText("").toUpperCase();
        JsonNode value = filter.get("value");
        return field + "\u0000" + operator + "\u0000" + (value == null ? "null" : value.toString());
    }

    private String canonicalDate(JsonNode date) {
        if (date == null || !date.isObject()) {
            return "";
        }
        return List.of("dateMode", "period", "startDate", "endDate", "unit", "dateList").stream()
                .map(key -> key + "=" + date.path(key).toString()).reduce((a, b) -> a + "|" + b)
                .orElse("");
    }

    private String fieldName(JsonNode field) {
        String bizName = field.path("bizName").asText("").trim();
        return bizName.isEmpty() ? field.path("name").asText("").trim() : bizName;
    }

    private Long positiveLong(JsonNode value) {
        return value.canConvertToLong() && value.asLong() > 0 ? value.asLong() : null;
    }

    private void reject() {
        throw new InvalidArgumentException(
                "Dashboard export query does not match the persisted dashboard");
    }
}
