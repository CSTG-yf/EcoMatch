package com.tencent.supersonic.chat.server.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.chat.api.pojo.request.ChatQueryDataReq;
import com.tencent.supersonic.chat.api.pojo.request.DashboardQueryDataReq;
import com.tencent.supersonic.chat.server.service.ChatManageService;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.FilterOperatorEnum;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import com.tencent.supersonic.headless.api.pojo.request.QueryFilter;
import com.tencent.supersonic.headless.api.pojo.response.DashboardResp;
import com.tencent.supersonic.headless.api.pojo.response.DataSetResp;
import com.tencent.supersonic.headless.server.service.DashboardService;
import com.tencent.supersonic.headless.server.service.DataSetService;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Service
public class DashboardQueryService {

    private static final int MAX_COMPONENTS = 100;

    private final DashboardService dashboardService;
    private final ChatQueryServiceImpl chatQueryService;
    private final ChatManageService chatManageService;
    private final DataSetService dataSetService;
    private final ObjectMapper objectMapper;

    public DashboardQueryService(DashboardService dashboardService,
            ChatQueryServiceImpl chatQueryService, ChatManageService chatManageService,
            DataSetService dataSetService, ObjectMapper objectMapper) {
        this.dashboardService = dashboardService;
        this.chatQueryService = chatQueryService;
        this.chatManageService = chatManageService;
        this.dataSetService = dataSetService;
        this.objectMapper = objectMapper;
    }

    public Object queryData(DashboardQueryDataReq request, User user) throws Exception {
        validateRequest(request);
        DashboardResp dashboard = dashboardService.get(request.getDashboardId(), user);
        return queryData(dashboard, request.getComponentId(), user);
    }

    public Object queryData(DashboardResp dashboard, String componentId, User user)
            throws Exception {
        JsonNode config = readConfig(dashboard);
        JsonNode component = findComponent(config.path("components"), componentId);
        return queryData(dashboard, config, component, user);
    }

    public BatchQueryResult queryAll(DashboardResp dashboard, User user) {
        JsonNode config = readConfig(dashboard);
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, String> errors = new LinkedHashMap<>();
        JsonNode components = config.path("components");
        if (!components.isArray()) {
            throw new InvalidArgumentException("dashboard components are invalid");
        }
        if (components.size() > MAX_COMPONENTS) {
            throw new InvalidArgumentException("dashboard contains too many components");
        }
        for (JsonNode component : components) {
            String componentId = component.path("id").asText("").trim();
            if (componentId.isEmpty()) {
                continue;
            }
            try {
                data.put(componentId, queryData(dashboard, config, component, user));
            } catch (InvalidPermissionException e) {
                errors.put(componentId, "FORBIDDEN");
            } catch (Exception e) {
                errors.put(componentId, "QUERY_FAILED");
            }
        }
        return new BatchQueryResult(data, errors);
    }

    private Object queryData(DashboardResp dashboard, JsonNode config, JsonNode component,
            User user) throws Exception {
        ChatQueryDataReq query = readQuery(component.path("query"));
        requireMatchingDomain(dashboard, query);
        mergeGlobalFilters(query, config.path("globalFilters"));
        query.setUser(user);
        return chatQueryService.queryDataForDashboard(query, user);
    }

    private void validateRequest(DashboardQueryDataReq request) {
        if (request == null || request.getDashboardId() == null || request.getDashboardId() <= 0
                || isBlank(request.getComponentId())) {
            throw new InvalidArgumentException("dashboardId and componentId are required");
        }
    }

    private JsonNode readConfig(DashboardResp dashboard) {
        if (dashboard == null || isBlank(dashboard.getConfig())) {
            throw new InvalidArgumentException("dashboard config is empty");
        }
        try {
            JsonNode config = objectMapper.readTree(dashboard.getConfig());
            if (config == null || !config.isObject()) {
                throw new InvalidArgumentException("dashboard config is invalid");
            }
            return config;
        } catch (InvalidArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidArgumentException("dashboard config is invalid");
        }
    }

    private JsonNode findComponent(JsonNode components, String componentId) {
        if (components.isArray()) {
            for (JsonNode component : components) {
                if (componentId.equals(component.path("id").asText())) {
                    return component;
                }
            }
        }
        throw new InvalidArgumentException("dashboard component does not exist");
    }

    private ChatQueryDataReq readQuery(JsonNode queryNode) {
        if (!queryNode.isObject()) {
            throw new InvalidArgumentException("dashboard component query is invalid");
        }
        try {
            ChatQueryDataReq query = objectMapper.treeToValue(queryNode, ChatQueryDataReq.class);
            if (query.getQueryId() == null || query.getParseId() == null) {
                throw new InvalidArgumentException("dashboard component query is incomplete");
            }
            return query;
        } catch (InvalidArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidArgumentException("dashboard component query is invalid");
        }
    }

    private void requireMatchingDomain(DashboardResp dashboard, ChatQueryDataReq query) {
        SemanticParseInfo parseInfo =
                chatManageService.getParseInfo(query.getQueryId(), query.getParseId());
        if (parseInfo == null || parseInfo.getDataSetId() == null) {
            throw new InvalidArgumentException("dashboard component query dataset is unavailable");
        }
        DataSetResp dataSet = dataSetService.getDataSet(parseInfo.getDataSetId());
        if (dataSet == null || dataSet.getDomainId() == null
                || !dataSet.getDomainId().equals(dashboard.getDomainId())) {
            throw new InvalidArgumentException(
                    "dashboard component query does not belong to the dashboard domain");
        }
    }

    private void mergeGlobalFilters(ChatQueryDataReq query, JsonNode globalFilters) {
        Set<QueryFilter> filters = query.getDimensionFilters() == null ? new HashSet<>()
                : new HashSet<>(query.getDimensionFilters());
        if (globalFilters.isArray()) {
            for (JsonNode filterNode : globalFilters) {
                String field = filterNode.path("field").asText("").trim();
                if (field.isEmpty()) {
                    continue;
                }
                String rawOperator = filterNode.path("operator").asText("=");
                FilterOperatorEnum operator = FilterOperatorEnum.getSqlOperator(rawOperator);
                if (operator == null) {
                    throw new InvalidArgumentException(
                            "unsupported dashboard filter operator: " + rawOperator);
                }
                QueryFilter filter = new QueryFilter();
                filter.setName(field);
                filter.setBizName(field);
                filter.setOperator(operator);
                filter.setValue(objectMapper.convertValue(filterNode.get("value"), Object.class));
                filters.add(filter);
            }
        }
        query.setDimensionFilters(filters);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public record BatchQueryResult(Map<String, Object> data, Map<String, String> errors) {}
}
