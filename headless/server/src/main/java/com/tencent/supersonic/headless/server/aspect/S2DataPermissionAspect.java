package com.tencent.supersonic.headless.server.aspect;

import com.google.common.collect.Sets;
import com.tencent.supersonic.auth.api.authorization.pojo.AuthRes;
import com.tencent.supersonic.auth.api.authorization.context.AuthorizationContext;
import com.tencent.supersonic.auth.api.authorization.pojo.ColumnAccessMode;
import com.tencent.supersonic.auth.api.authorization.pojo.DimensionFilter;
import com.tencent.supersonic.auth.api.authorization.pojo.PolicyEffect;
import com.tencent.supersonic.auth.api.authorization.pojo.ResourcePermission;
import com.tencent.supersonic.auth.api.authorization.request.QueryAuthResReq;
import com.tencent.supersonic.auth.api.authorization.response.AuthorizedResourceResp;
import com.tencent.supersonic.auth.api.authorization.service.AuthService;
import com.tencent.supersonic.common.jsqlparser.SqlAddHelper;
import com.tencent.supersonic.common.jsqlparser.SqlReplaceHelper;
import com.tencent.supersonic.common.pojo.Filter;
import com.tencent.supersonic.common.pojo.QueryAuthorization;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.AuthType;
import com.tencent.supersonic.common.pojo.enums.FilterOperatorEnum;
import com.tencent.supersonic.common.pojo.enums.SensitiveLevelEnum;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import com.tencent.supersonic.common.util.SensitiveLogUtils;
import com.tencent.supersonic.headless.api.pojo.MetaFilter;
import com.tencent.supersonic.headless.api.pojo.request.QuerySqlReq;
import com.tencent.supersonic.headless.api.pojo.request.QueryStructReq;
import com.tencent.supersonic.headless.api.pojo.request.SchemaFilterReq;
import com.tencent.supersonic.headless.api.pojo.request.SemanticQueryReq;
import com.tencent.supersonic.headless.api.pojo.response.DimSchemaResp;
import com.tencent.supersonic.headless.api.pojo.response.MetricSchemaResp;
import com.tencent.supersonic.headless.api.pojo.response.ModelResp;
import com.tencent.supersonic.headless.api.pojo.response.SemanticQueryResp;
import com.tencent.supersonic.headless.api.pojo.response.SemanticSchemaResp;
import com.tencent.supersonic.headless.server.security.DataMaskingService;
import com.tencent.supersonic.headless.server.security.audit.AuditEventPublisher;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEvent;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEventType;
import com.tencent.supersonic.headless.server.security.audit.model.AuditOutcome;
import com.tencent.supersonic.headless.server.service.ModelService;
import com.tencent.supersonic.headless.server.service.SchemaService;
import com.tencent.supersonic.headless.server.utils.QueryStructUtils;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import org.apache.commons.lang3.StringUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@Aspect
@Slf4j
public class S2DataPermissionAspect {

    private static final Pattern UNSAFE_PERMISSION_EXPRESSION = Pattern.compile(
            "(?is)(;|--|/\\*|\\*/|\\b(select|insert|update|delete|drop|alter|truncate|grant|revoke|call|merge)\\b)");

    @Autowired
    private QueryStructUtils queryStructUtils;
    @Autowired
    private ModelService modelService;
    @Autowired
    private SchemaService schemaService;
    @Autowired
    private AuthService authService;
    @Autowired
    private DataMaskingService dataMaskingService;
    @Autowired
    private AuditEventPublisher auditEventPublisher;

    @Pointcut("@annotation(com.tencent.supersonic.headless.server.annotation.S2DataPermission)")
    private void s2PermissionCheck() {}

    @Around("s2PermissionCheck()")
    public Object doAround(ProceedingJoinPoint joinPoint) throws Throwable {
        Object[] objects = joinPoint.getArgs();
        boolean needQueryData = true;
        SemanticQueryReq queryReq = null;
        User user = null;
        SemanticSchemaResp semanticSchemaResp = null;
        Set<Long> modelIds = Sets.newHashSet();
        boolean authorizationDecisionFinalized = false;
        String denialReasonCode = "AUTH_REQUEST_INVALID";

        try {
            // 1. check args
            if (objects != null && objects.length > 0 && objects[0] instanceof SemanticQueryReq) {
                queryReq = (SemanticQueryReq) objects[0];
                if (queryReq instanceof QuerySqlReq) {
                    QuerySqlReq sqlReq = (QuerySqlReq) queryReq;
                    if (sqlReq.getDataSetName() != null) {
                        String escapedTable =
                                SqlReplaceHelper.escapeTableName(sqlReq.getDataSetName());
                        sqlReq.setSql(sqlReq.getSql().replaceAll(
                                String.format(" %s ", sqlReq.getDataSetName()),
                                String.format(" %s ", escapedTable)));
                    }
                }
            }
            if (queryReq == null) {
                throw new InvalidArgumentException("queryReq is not Invalid");
            }
            if (objects.length > 1 && objects[1] instanceof User) {
                user = (User) objects[1];
            }

            denialReasonCode = "AUTH_SCHEMA_UNAVAILABLE";
            semanticSchemaResp = getSemanticSchemaResp(queryReq);
            if (!queryReq.isNeedAuth()) {
                log.info(
                        "needAuth is false, authorization checks are skipped but masking remains.");
                authorizationDecisionFinalized = true;
                publishAuthorizationDecision(queryReq, modelIds, user, true, "AUTH_NOT_REQUIRED",
                        null);
                return proceedAndMask(joinPoint, semanticSchemaResp, queryReq, modelIds, user,
                        null);
            }
            denialReasonCode = "AUTH_USER_MISSING";
            if (Objects.isNull(user) || StringUtils.isEmpty(user.getName())) {
                throw new RuntimeException("please provide user information");
            }

            denialReasonCode = "AUTH_MODEL_SCOPE_UNRESOLVED";
            modelIds = getModelIdInQuery(queryReq, semanticSchemaResp);
            if (CollectionUtils.isEmpty(modelIds)) {
                throw new InvalidArgumentException(
                        "Unable to determine the model scope for an authorized query");
            }

            // 2. determine whether admin of the model
            denialReasonCode = "AUTH_MODEL_ADMIN_CHECK_FAILED";
            if (checkModelAdmin(user, modelIds)) {
                authorizationDecisionFinalized = true;
                publishAuthorizationDecision(queryReq, modelIds, user, true, "AUTH_MODEL_ADMIN",
                        null);
                return proceedAndMask(joinPoint, semanticSchemaResp, queryReq, modelIds, user,
                        null);
            }
            // 3. determine whether the model is visible to cur user
            denialReasonCode = "AUTH_MODEL_NOT_VISIBLE";
            checkModelVisible(user, modelIds);

            // 4. get permissions auth to cur user
            denialReasonCode = "AUTH_POLICY_RESOLUTION_FAILED";
            AuthorizedResourceResp authorizedResource = getAuthorizedResource(user, modelIds);

            // 5. check col permission
            denialReasonCode = "AUTH_SENSITIVE_COLUMN_DENIED";
            if (needQueryData) {
                checkColPermission(queryReq, authorizedResource, modelIds, semanticSchemaResp);
            }
            // 6. check row permission
            denialReasonCode = "AUTH_ROW_POLICY_DENIED";
            checkRowPermission(queryReq, authorizedResource, modelIds, semanticSchemaResp);

            authorizationDecisionFinalized = true;
            publishAuthorizationDecision(queryReq, modelIds, user, true, "AUTH_POLICY_ALLOWED",
                    authorizedResource);

            // 7. add hint to user
            Object result = proceedAndMask(joinPoint, semanticSchemaResp, queryReq, modelIds, user,
                    authorizedResource);
            if (result instanceof SemanticQueryResp) {
                SemanticQueryResp queryResp = (SemanticQueryResp) result;
                addHint(modelIds, queryResp, authorizedResource);
            }
            return result;
        } catch (Throwable throwable) {
            if (!authorizationDecisionFinalized) {
                try {
                    publishAuthorizationDecision(queryReq, modelIds, user, false, denialReasonCode,
                            null);
                } catch (RuntimeException auditFailure) {
                    throwable.addSuppressed(auditFailure);
                }
            }
            throw throwable;
        }
    }

    private Object proceedAndMask(ProceedingJoinPoint joinPoint,
            SemanticSchemaResp semanticSchemaResp, SemanticQueryReq queryReq, Set<Long> modelIds,
            User user, AuthorizedResourceResp authorizedResource) throws Throwable {
        AuthorizationContext.install(
                authorizedResource == null ? List.of() : authorizedResource.getResourcePermissions(),
                authorizedResource == null ? 0L : authorizedResource.getPolicyVersion(), user,
                authorizedResource == null ? Set.of()
                        : authorizedResource.getEffectiveOrganizationIds());
        try {
            Object result = joinPoint.proceed();
            if (result instanceof SemanticQueryResp) {
                SemanticQueryResp queryResp = (SemanticQueryResp) result;
                dataMaskingService.mask(queryResp, semanticSchemaResp, user,
                        authorizedResource == null ? List.of()
                                : authorizedResource.getResourcePermissions());
                queryResp.setMaskingPolicyVersion(authorizedResource == null ? 0L
                        : authorizedResource.getPolicyVersion());
                if (queryResp.isDataMasked()) {
                    int maskedColumnCount = queryResp.getMaskedColumns() == null ? 0
                            : queryResp.getMaskedColumns().size();
                    publishAuditEvent(
                            AuditEvent.builder().eventType(AuditEventType.MASK_APPLIED)
                                    .outcome(AuditOutcome.SUCCESS).reasonCode("SENSITIVE_RESULT_MASKED")
                                    .resourceType(resolveAuditResourceType(queryReq))
                                    .resourceId(resolveAuditResourceId(queryReq, modelIds))
                                    .policyIds(policyIds(authorizedResource))
                                    .metadata(policyMetadata(authorizedResource))
                                    .maskingSummary("maskedColumnCount=" + maskedColumnCount).build(),
                            user);
                }
            }
            return result;
        } finally {
            AuthorizationContext.clear();
        }
    }

    private void publishAuthorizationDecision(SemanticQueryReq queryReq, Set<Long> modelIds,
            User user, boolean allowed, String reasonCode,
            AuthorizedResourceResp authorizedResource) {
        publishAuditEvent(AuditEvent.builder()
                .eventType(allowed ? AuditEventType.AUTH_ALLOWED : AuditEventType.AUTH_DENIED)
                .outcome(allowed ? AuditOutcome.SUCCESS : AuditOutcome.DENIED)
                .reasonCode(reasonCode).resourceType(resolveAuditResourceType(queryReq))
                .resourceId(resolveAuditResourceId(queryReq, modelIds))
                .policyIds(policyIds(authorizedResource)).metadata(policyMetadata(authorizedResource))
                .build(), user);
    }

    private List<String> policyIds(AuthorizedResourceResp authorizedResource) {
        if (authorizedResource == null || CollectionUtils.isEmpty(authorizedResource.getMatchedGroupIds())) {
            return List.of();
        }
        return authorizedResource.getMatchedGroupIds().stream().sorted().map(String::valueOf).toList();
    }

    private Map<String, Object> policyMetadata(AuthorizedResourceResp authorizedResource) {
        if (authorizedResource == null || authorizedResource.getPolicyVersion() <= 0) {
            return null;
        }
        return Map.of("policyVersion", authorizedResource.getPolicyVersion());
    }

    private void publishAuditEvent(AuditEvent event, User user) {
        auditEventPublisher.publishRequired(event, user);
    }

    private String resolveAuditResourceType(SemanticQueryReq queryReq) {
        return queryReq != null && queryReq.getDataSetId() != null ? "DATASET" : "MODEL_SCOPE";
    }

    private String resolveAuditResourceId(SemanticQueryReq queryReq, Set<Long> resolvedModelIds) {
        if (queryReq != null && queryReq.getDataSetId() != null) {
            return "id=" + queryReq.getDataSetId();
        }
        Set<Long> modelScope = new HashSet<>();
        if (!CollectionUtils.isEmpty(resolvedModelIds)) {
            modelScope.addAll(resolvedModelIds);
        } else if (queryReq != null && !CollectionUtils.isEmpty(queryReq.getModelIdSet())) {
            modelScope.addAll(queryReq.getModelIdSet());
        }
        if (modelScope.isEmpty()) {
            return "unresolved";
        }
        return "ids=" + modelScope.stream().sorted().map(String::valueOf)
                .collect(Collectors.joining(","));
    }

    private void checkColPermission(SemanticQueryReq semanticQueryReq,
            AuthorizedResourceResp authorizedResource, Set<Long> modelIds,
            SemanticSchemaResp semanticSchemaResp) {
        // get high sensitive fields in query
        Set<String> bizNamesInQueryReq = getBizNameInQueryReq(semanticQueryReq, semanticSchemaResp);
        Set<String> normalizedQueryFields = bizNamesInQueryReq.stream().filter(Objects::nonNull)
                .map(this::normalizeResourceName).collect(Collectors.toSet());
        Set<ModelResourceKey> sensitiveResources =
                highSensitiveResources(semanticSchemaResp, modelIds, normalizedQueryFields);
        Set<ModelResourceKey> authorizedResources =
                authorizedResource.getAuthResList().stream().filter(Objects::nonNull)
                        .filter(resource -> resource.getModelId() != null)
                        .filter(resource -> StringUtils.isNotBlank(resource.getName()))
                        .map(resource -> new ModelResourceKey(resource.getModelId(),
                                normalizeResourceName(resource.getName())))
                        .collect(Collectors.toSet());
        Set<ModelResourceKey> explicitlyDenied = authorizedResource.getResourcePermissions().stream()
                .filter(Objects::nonNull)
                .filter(permission -> permission.getAccessMode() == ColumnAccessMode.DENY)
                .filter(permission -> permission.getModelId() != null
                        && StringUtils.isNotBlank(permission.getResourceName()))
                .map(permission -> new ModelResourceKey(permission.getModelId(),
                        normalizeResourceName(permission.getResourceName())))
                .collect(Collectors.toSet());
        Set<ModelResourceKey> deniedResources = new HashSet<>(explicitlyDenied);
        deniedResources.addAll(sensitiveResources.stream().filter(resource -> !authorizedResources.contains(resource))
                .collect(Collectors.toSet()));
        if (!deniedResources.isEmpty()) {
            Set<String> sensitiveResNames = deniedResources.stream()
                    .map(ModelResourceKey::resourceName).collect(Collectors.toSet());
            List<String> modelAdmin = modelService.getModelAdmin(modelIds.iterator().next());
            String message =
                    String.format("存在以下敏感资源:%s您暂无权限，请联系管理员%s申请", sensitiveResNames, modelAdmin);
            throw new InvalidPermissionException(message);
        }
    }

    private Set<Long> getModelIdInQuery(SemanticQueryReq semanticQueryReq,
            SemanticSchemaResp semanticSchemaResp) {
        if (semanticQueryReq instanceof QuerySqlReq) {
            QuerySqlReq querySqlReq = (QuerySqlReq) semanticQueryReq;
            return queryStructUtils.getModelIdFromSql(querySqlReq, semanticSchemaResp);
        }
        if (semanticQueryReq instanceof QueryStructReq) {
            QueryStructReq queryStructReq = (QueryStructReq) semanticQueryReq;
            return queryStructUtils.getModelIdsFromStruct(queryStructReq, semanticSchemaResp);
        }
        return Sets.newHashSet();
    }

    private void checkRowPermission(SemanticQueryReq queryReq,
            AuthorizedResourceResp authorizedResource, Set<Long> modelIds,
            SemanticSchemaResp semanticSchemaResp) {
        if (queryReq instanceof QuerySqlReq) {
            doRowPermission((QuerySqlReq) queryReq, authorizedResource, modelIds, semanticSchemaResp);
        }
        if (queryReq instanceof QueryStructReq) {
            doRowPermission((QueryStructReq) queryReq, authorizedResource, modelIds, semanticSchemaResp);
        }
    }

    private Set<String> getBizNameInQueryReq(SemanticQueryReq queryReq,
            SemanticSchemaResp semanticSchemaResp) {
        if (queryReq instanceof QuerySqlReq) {
            return queryStructUtils.getBizNameFromSql((QuerySqlReq) queryReq, semanticSchemaResp);
        }
        if (queryReq instanceof QueryStructReq) {
            return queryStructUtils.getBizNameFromStruct((QueryStructReq) queryReq);
        }
        throw new InvalidArgumentException("Unsupported semantic query request");
    }

    private SemanticSchemaResp getSemanticSchemaResp(SemanticQueryReq semanticQueryReq) {
        SchemaFilterReq filter = new SchemaFilterReq();
        filter.setModelIds(semanticQueryReq.getModelIds());
        filter.setDataSetId(semanticQueryReq.getDataSetId());
        return schemaService.fetchSemanticSchema(filter);
    }

    private void doRowPermission(QuerySqlReq querySqlReq, AuthorizedResourceResp authorizedResource,
            Set<Long> modelIds, SemanticSchemaResp semanticSchemaResp) {
        log.debug("Start doRowPermission logic");

        String rowPermissionExpression = buildRowPermissionExpression(authorizedResource, modelIds,
                semanticSchemaResp);
        if (StringUtils.isBlank(rowPermissionExpression)) {
            log.debug("No effective row permission filters");
            return;
        }

        try {
            Expression expression = CCJSqlParserUtil.parseCondExpression(rowPermissionExpression);
            String originalSql = querySqlReq.getSql();
            String modifiedSql = SqlAddHelper.addWhere(originalSql, expression);
            log.debug("Applying model-scoped row permission to SQL [{}]",
                    SensitiveLogUtils.summarize(originalSql));
            querySqlReq.setSql(modifiedSql);
            log.debug("Row permission applied to SQL [{}]",
                    SensitiveLogUtils.summarize(modifiedSql));
        } catch (JSQLParserException e) {
            log.warn("Failed to apply row permission filter: type={}, error=[{}]",
                    e.getClass().getSimpleName(), SensitiveLogUtils.summarize(e.getMessage()));
            throw new InvalidPermissionException(
                    "Row permission filter is invalid; query execution was denied");
        }
    }

    private void doRowPermission(QueryStructReq queryStructReq,
            AuthorizedResourceResp authorizedResource, Set<Long> modelIds,
            SemanticSchemaResp semanticSchemaResp) {
        log.debug("start doRowPermission logic");

        String rowPermissionExpression = buildRowPermissionExpression(authorizedResource, modelIds,
                semanticSchemaResp);
        if (StringUtils.isBlank(rowPermissionExpression)) {
            log.debug("No effective row permission filters");
            return;
        }
        log.debug("Applying model-scoped row permission to structured query [{}]",
                SensitiveLogUtils.summarize(queryStructReq));
        Filter filter = new Filter("", FilterOperatorEnum.SQL_PART, rowPermissionExpression);
        List<Filter> filters =
                Optional.ofNullable(queryStructReq.getOriginalFilter()).orElseGet(ArrayList::new);
        filters.add(filter);
        queryStructReq.setDimensionFilters(filters);
        log.debug("Row permission applied to structured query [{}]",
                SensitiveLogUtils.summarize(queryStructReq));
    }

    private String buildRowPermissionExpression(AuthorizedResourceResp authorizedResource,
            Set<Long> modelIds, SemanticSchemaResp semanticSchemaResp) {
        if (authorizedResource == null
                || CollectionUtils.isEmpty(authorizedResource.getFilters())) {
            return null;
        }
        Map<Long, List<DimensionFilter>> filtersByModel = new LinkedHashMap<>();
        for (DimensionFilter filter : authorizedResource.getFilters()) {
            if (filter == null || filter.getModelId() == null
                    || !modelIds.contains(filter.getModelId())) {
                throw new InvalidPermissionException(
                        "Row permission filter has an invalid model scope; query execution was denied");
            }
            if (filter.getExpressions() == null) {
                throw new InvalidPermissionException(
                        "Row permission filter is invalid; query execution was denied");
            }
            filtersByModel.computeIfAbsent(filter.getModelId(), key -> new ArrayList<>())
                    .add(filter);
        }

        List<String> modelClauses = new ArrayList<>();
        for (Map.Entry<Long, List<DimensionFilter>> entry : filtersByModel.entrySet()) {
            Set<String> allowExpressions = entry.getValue().stream()
                    .filter(filter -> filter.getEffect() != PolicyEffect.DENY)
                    .flatMap(filter -> filter.getExpressions().stream())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            Set<String> denyExpressions = entry.getValue().stream()
                    .filter(filter -> filter.getEffect() == PolicyEffect.DENY)
                    .flatMap(filter -> filter.getExpressions().stream())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (allowExpressions.stream().anyMatch(StringUtils::isBlank)
                    || denyExpressions.stream().anyMatch(StringUtils::isBlank)) {
                throw new InvalidPermissionException(
                        "Row permission filter is invalid; query execution was denied");
            }
            validateDimensionFilters(new ArrayList<>(allowExpressions));
            validateDimensionFilters(new ArrayList<>(denyExpressions));
            entry.getValue().stream().filter(DimensionFilter::isStructured)
                    .forEach(filter -> validateStructuredFilter(filter, semanticSchemaResp));
            String allowClause = allowExpressions.stream().map(expression -> "( " + expression + " )")
                    .collect(Collectors.joining(" OR "));
            String denyClause = denyExpressions.stream().map(expression -> "( " + expression + " )")
                    .collect(Collectors.joining(" OR "));
            if (StringUtils.isBlank(allowClause) && StringUtils.isBlank(denyClause)) {
                continue;
            }
            if (StringUtils.isBlank(allowClause)) {
                modelClauses.add("( NOT ( " + denyClause + " ) )");
            } else if (StringUtils.isBlank(denyClause)) {
                modelClauses.add("( " + allowClause + " )");
            } else {
                modelClauses.add("( ( " + allowClause + " ) AND NOT ( " + denyClause + " ) )");
            }
        }
        return modelClauses.isEmpty() ? null : String.join(" AND ", modelClauses);
    }

    private void validateDimensionFilters(List<String> dimensionFilters) {
        for (String expression : dimensionFilters) {
            if (UNSAFE_PERMISSION_EXPRESSION.matcher(expression).find()) {
                throw new InvalidPermissionException(
                        "Unsafe row permission filter; query execution was denied");
            }
            try {
                CCJSqlParserUtil.parseCondExpression(expression);
            } catch (JSQLParserException e) {
                log.warn("Failed to parse row permission filter: type={}, error=[{}]",
                        e.getClass().getSimpleName(), SensitiveLogUtils.summarize(e.getMessage()));
                throw new InvalidPermissionException(
                        "Row permission filter is invalid; query execution was denied");
            }
        }
    }

    private void validateStructuredFilter(DimensionFilter filter,
            SemanticSchemaResp semanticSchemaResp) {
        if (semanticSchemaResp == null || filter.getExpressions() == null
                || filter.getExpressions().size() != 1) {
            throw new InvalidPermissionException(
                    "Structured row permission metadata is unavailable; query execution was denied");
        }
        String expression = filter.getExpressions().get(0);
        java.util.regex.Matcher matcher = Pattern.compile(
                "(?is)^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s+(?:=|IN|BETWEEN|LIKE)\\b")
                .matcher(expression);
        if (!matcher.find()) {
            throw new InvalidPermissionException(
                    "Structured row permission field is invalid; query execution was denied");
        }
        String field = matcher.group(1).toLowerCase(java.util.Locale.ROOT);
        boolean exists = java.util.stream.Stream.concat(
                semanticSchemaResp.getDimensions() == null ? java.util.stream.Stream.empty()
                        : semanticSchemaResp.getDimensions().stream(),
                semanticSchemaResp.getMetrics() == null ? java.util.stream.Stream.empty()
                        : semanticSchemaResp.getMetrics().stream())
                // SemanticSchemaResp is already resolved for the query's model scope;
                // SchemaItem itself does not carry a modelId.
                .filter(java.util.Objects::nonNull)
                .flatMap(item -> java.util.stream.Stream.of(item.getBizName(), item.getName()))
                .filter(StringUtils::isNotBlank)
                .map(value -> value.toLowerCase(java.util.Locale.ROOT))
                .anyMatch(field::equals);
        if (!exists) {
            throw new InvalidPermissionException(
                    "Structured row permission field is outside the semantic model");
        }
    }

    public boolean checkModelAdmin(User user, Set<Long> modelIds) {
        List<ModelResp> modelListAdmin =
                modelService.getModelListWithAuth(user, null, AuthType.ADMIN);
        if (CollectionUtils.isEmpty(modelListAdmin)) {
            return false;
        } else {
            Set<Long> modelAdmins =
                    modelListAdmin.stream().map(ModelResp::getId).collect(Collectors.toSet());
            return !CollectionUtils.isEmpty(modelAdmins) && modelAdmins.containsAll(modelIds);
        }
    }

    public void checkModelVisible(User user, Set<Long> modelIds) {
        List<Long> modelListVisible = modelService.getModelListWithAuth(user, null, AuthType.VIEWER)
                .stream().map(ModelResp::getId).collect(Collectors.toList());
        List<Long> modelIdCopied = new ArrayList<>(modelIds);
        modelIdCopied.removeAll(modelListVisible);
        if (!CollectionUtils.isEmpty(modelIdCopied)) {
            MetaFilter metaFilter = new MetaFilter();
            metaFilter.setIds(modelIdCopied);
            List<ModelResp> modelResps = modelService.getModelList(metaFilter);
            ModelResp modelResp = modelResps.stream().findFirst().orElse(null);
            if (modelResp == null) {
                throw new InvalidArgumentException("查询的模型不存在");
            }
            String message = String.format("您没有模型[%s]权限，请联系管理员%s开通", modelResp.getName(),
                    modelResp.getAdmins());
            throw new InvalidPermissionException(message);
        }
    }

    public Set<String> getHighSensitiveBizNamesByModelId(SemanticSchemaResp semanticSchemaResp) {
        Set<String> highSensitiveCols = new HashSet<>();
        if (!CollectionUtils.isEmpty(semanticSchemaResp.getDimensions())) {
            semanticSchemaResp.getDimensions().stream()
                    .filter(dimSchemaResp -> SensitiveLevelEnum.HIGH.getCode()
                            .equals(dimSchemaResp.getSensitiveLevel()))
                    .forEach(dim -> highSensitiveCols.add(dim.getBizName()));
        }
        if (!CollectionUtils.isEmpty(semanticSchemaResp.getMetrics())) {
            semanticSchemaResp.getMetrics().stream()
                    .filter(metricSchemaResp -> SensitiveLevelEnum.HIGH.getCode()
                            .equals(metricSchemaResp.getSensitiveLevel()))
                    .forEach(metric -> highSensitiveCols.add(metric.getBizName()));
        }
        return highSensitiveCols;
    }

    private Set<ModelResourceKey> highSensitiveResources(SemanticSchemaResp semanticSchemaResp,
            Set<Long> modelIds, Set<String> normalizedQueryFields) {
        Set<ModelResourceKey> resources = new HashSet<>();
        if (!CollectionUtils.isEmpty(semanticSchemaResp.getDimensions())) {
            for (DimSchemaResp dimension : semanticSchemaResp.getDimensions()) {
                addSensitiveResource(resources, dimension.getModelId(), dimension.getBizName(),
                        dimension.getSensitiveLevel(), modelIds, normalizedQueryFields);
            }
        }
        if (!CollectionUtils.isEmpty(semanticSchemaResp.getMetrics())) {
            for (MetricSchemaResp metric : semanticSchemaResp.getMetrics()) {
                addSensitiveResource(resources, metric.getModelId(), metric.getBizName(),
                        metric.getSensitiveLevel(), modelIds, normalizedQueryFields);
            }
        }
        return resources;
    }

    private void addSensitiveResource(Set<ModelResourceKey> resources, Long modelId,
            String resourceName, Integer sensitiveLevel, Set<Long> modelIds,
            Set<String> normalizedQueryFields) {
        String normalizedName = normalizeResourceName(resourceName);
        if (!SensitiveLevelEnum.HIGH.getCode().equals(sensitiveLevel)
                || !normalizedQueryFields.contains(normalizedName)) {
            return;
        }
        if (modelId == null) {
            throw new InvalidPermissionException(
                    "Sensitive resource has no model scope; query execution was denied");
        }
        if (modelIds.contains(modelId)) {
            resources.add(new ModelResourceKey(modelId, normalizedName));
        }
    }

    private String normalizeResourceName(String resourceName) {
        return StringUtils.defaultString(resourceName).toLowerCase(java.util.Locale.ROOT);
    }

    public AuthorizedResourceResp getAuthorizedResource(User user, Set<Long> modelIds) {
        QueryAuthResReq queryAuthResReq = new QueryAuthResReq();
        queryAuthResReq.setModelIds(new ArrayList<>(modelIds));
        AuthorizedResourceResp authorizedResource = fetchAuthRes(queryAuthResReq, user);
        log.debug("Authorization resolved for {} models: resources={}, filters={}", modelIds.size(),
                authorizedResource.getAuthResList().size(), authorizedResource.getFilters().size());
        return authorizedResource;
    }

    private AuthorizedResourceResp fetchAuthRes(QueryAuthResReq queryAuthResReq, User user) {
        log.debug("Querying authorization resources [{}]",
                SensitiveLogUtils.summarize(queryAuthResReq));
        return authService.queryAuthorizedResources(queryAuthResReq, user);
    }

    public void addHint(Set<Long> modelIds, SemanticQueryResp queryResultWithColumns,
            AuthorizedResourceResp authorizedResource) {
        List<DimensionFilter> filters = authorizedResource.getFilters();
        if (CollectionUtils.isEmpty(filters)) {
            return;
        }
        List<DimensionFilter> restrictedFilters = filters.stream().filter(Objects::nonNull)
                .filter(filter -> !CollectionUtils.isEmpty(filter.getExpressions())).toList();
        if (restrictedFilters.isEmpty()) {
            return;
        }
        List<String> admins = modelService.getModelAdmin(modelIds.iterator().next());

        if (!CollectionUtils.isEmpty(restrictedFilters)) {
            ModelResp modelResp = modelService.getModel(modelIds.iterator().next());
            List<String> exprList = new ArrayList<>();
            List<String> descList = new ArrayList<>();
            restrictedFilters.forEach(filter -> {
                if (StringUtils.isNotEmpty(filter.getDescription())) {
                    descList.add(filter.getDescription());
                }
                exprList.add(filter.getExpressions().toString());
            });
            if (!CollectionUtils.isEmpty(exprList)) {
                String promptInfo = "当前结果已经过行权限过滤，详细过滤条件如下:%s, 申请权限请联系管理员%s";
                String message = String.format(promptInfo,
                        CollectionUtils.isEmpty(descList) ? exprList : descList, admins);
                queryResultWithColumns.setQueryAuthorization(
                        new QueryAuthorization(modelResp.getName(), exprList, descList, message));
            }
        }
    }

    private record ModelResourceKey(Long modelId, String resourceName) {}
}
