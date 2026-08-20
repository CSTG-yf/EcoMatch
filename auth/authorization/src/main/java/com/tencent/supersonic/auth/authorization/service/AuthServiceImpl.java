package com.tencent.supersonic.auth.authorization.service;

import com.google.gson.Gson;
import com.tencent.supersonic.auth.api.authentication.service.UserService;
import com.tencent.supersonic.auth.api.authorization.pojo.AuthGroup;
import com.tencent.supersonic.auth.api.authorization.pojo.AuthRes;
import com.tencent.supersonic.auth.api.authorization.pojo.AuthRule;
import com.tencent.supersonic.auth.api.authorization.pojo.ColumnAccessMode;
import com.tencent.supersonic.auth.api.authorization.pojo.DimensionFilter;
import com.tencent.supersonic.auth.api.authorization.pojo.PolicyEffect;
import com.tencent.supersonic.auth.api.authorization.pojo.ResourcePermission;
import com.tencent.supersonic.auth.api.authorization.pojo.RowFilterRule;
import com.tencent.supersonic.auth.api.authorization.request.QueryAuthResReq;
import com.tencent.supersonic.auth.api.authorization.response.AuthorizedResourceResp;
import com.tencent.supersonic.auth.api.authorization.service.AuthService;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.util.SensitiveLogUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
public class AuthServiceImpl implements AuthService {

    private static final int MAX_STORED_GROUPS = 10_000;
    private static final int MAX_CONFIG_LENGTH = 262_144;
    private static final int MAX_TOTAL_CONFIG_LENGTH = 33_554_432;
    private static final int MAX_MODEL_IDS = 1_000;
    private static final int MAX_RULES_PER_GROUP = 1_000;
    private static final int MAX_IDENTIFIERS_PER_FIELD = 1_000;
    private static final int MAX_RESOURCES_PER_GROUP = 10_000;
    private static final int MAX_MATCHED_GROUPS = 1_000;
    private static final int MAX_AUTHORIZED_RESOURCES = 10_000;
    private static final int MAX_AUTHORIZED_FILTER_EXPRESSIONS = 10_000;
    private static final int MAX_AUTHORIZED_FILTER_TEXT_LENGTH = 1_048_576;
    private static final int MAX_ATTRIBUTES_PER_GROUP = 100;
    private static final int MAX_AUTH_TEXT_LENGTH = 4_096;
    private static final int MAX_STRUCTURED_ROW_RULES = 1_000;
    private static final int MAX_RESOURCE_PERMISSIONS = 10_000;

    private JdbcTemplate jdbcTemplate;

    private UserService userService;
    private final AuthGroupMatcher authGroupMatcher = new AuthGroupMatcher();
    private final Set<String> invalidConfigWarnings = ConcurrentHashMap.newKeySet();

    public AuthServiceImpl(JdbcTemplate jdbcTemplate, UserService userService) {
        this.jdbcTemplate = jdbcTemplate;
        this.userService = userService;
    }

    private List<AuthGroup> load() {
        List<String> rows =
                jdbcTemplate.queryForList("select config from s2_auth_groups "
                        + "where enabled = 1 and (valid_from is null or valid_from <= CURRENT_TIMESTAMP) "
                        + "and (valid_to is null or valid_to >= CURRENT_TIMESTAMP)", String.class);
        if (rows.size() > MAX_STORED_GROUPS) {
            throw new IllegalStateException(
                    "Authorization group count exceeds maximum: " + MAX_STORED_GROUPS);
        }
        long totalConfigLength =
                rows.stream().filter(Objects::nonNull).mapToLong(String::length).sum();
        if (totalConfigLength > MAX_TOTAL_CONFIG_LENGTH) {
            throw new IllegalStateException("Authorization config text exceeds maximum total "
                    + "length: " + MAX_TOTAL_CONFIG_LENGTH);
        }
        Gson g = new Gson();
        List<AuthGroup> groups = new ArrayList<>();
        for (String row : rows) {
            try {
                if (row == null || row.length() > MAX_CONFIG_LENGTH) {
                    throw new IllegalArgumentException(
                            "Authorization group config exceeds maximum length");
                }
                AuthGroup group = g.fromJson(row, AuthGroup.class);
                normalizePolicyDefaults(group);
                validateAuthGroup(group);
                groups.add(group);
            } catch (RuntimeException e) {
                String configMetadata = configMetadata(row);
                if (invalidConfigWarnings.add(configMetadata)) {
                    log.warn("Ignoring invalid authorization group: config=[{}], errorType={}",
                            configMetadata, e.getClass().getSimpleName());
                }
            }
        }
        return groups;
    }

    @Override
    public List<AuthGroup> queryAuthGroups(String modelId, Integer groupId) {
        return load().stream()
                .filter(group -> (Objects.isNull(groupId) || groupId.equals(group.getGroupId()))
                        && modelId.equals(group.getModelId().toString()))
                .collect(Collectors.toList());
    }

    @Override
    public void addOrUpdateAuthGroup(AuthGroup group) {
        normalizePolicyDefaults(group);
        validateAuthGroup(group);
        Gson g = new Gson();
        boolean creating = group.getGroupId() == null;
        if (creating) {
            int nextGroupId = 1;
            String sql = "select max(group_id) as group_id from s2_auth_groups";
            Integer obj = jdbcTemplate.queryForObject(sql, Integer.class);
            if (obj != null) {
                nextGroupId = obj + 1;
            }
            group.setGroupId(nextGroupId);
        } else {
            Long currentVersion = jdbcTemplate.queryForObject(
                    "select policy_version from s2_auth_groups where group_id = ?", Long.class,
                    group.getGroupId());
            if (currentVersion != null && currentVersion >= group.getPolicyVersion()) {
                group.setPolicyVersion(currentVersion == Long.MAX_VALUE ? Long.MAX_VALUE
                        : currentVersion + 1);
            }
        }
        group.setPolicyCode(effectivePolicyCode(group));
        String config = g.toJson(group);
        if (creating) {
            jdbcTemplate.update("insert into s2_auth_groups (group_id, config) values (?, ?)",
                    group.getGroupId(), config);
            updatePolicyMetadata(group, group.getGroupId());
        } else {
            jdbcTemplate.update("update s2_auth_groups set config = ? where group_id = ?",
                    config, group.getGroupId());
            updatePolicyMetadata(group, group.getGroupId());
        }
    }

    @Override
    public void removeAuthGroup(AuthGroup group) {
        if (group == null || group.getGroupId() == null || group.getGroupId() <= 0) {
            throw new IllegalArgumentException("Authorization group id must be positive");
        }
        jdbcTemplate.update("delete from s2_auth_groups where group_id = ?", group.getGroupId());
    }

    @Override
    public AuthorizedResourceResp queryAuthorizedResources(QueryAuthResReq req, User user) {
        if (req == null) {
            throw new IllegalArgumentException("Authorization resource request is required");
        }
        if (user == null || !StringUtils.hasText(user.getName())) {
            throw new IllegalArgumentException("Authorization user identity is required");
        }
        if (CollectionUtils.isEmpty(req.getModelIds())) {
            return new AuthorizedResourceResp();
        }
        if (req.getModelIds().size() > MAX_MODEL_IDS) {
            throw new IllegalArgumentException(
                    "Authorization model id count exceeds maximum: " + MAX_MODEL_IDS);
        }
        if (req.getModelIds().stream().anyMatch(modelId -> modelId == null || modelId <= 0)) {
            throw new IllegalArgumentException("Authorization model ids must be positive");
        }
        List<Long> requestedModelIds = req.getModelIds().stream().distinct().toList();
        Set<String> userOrgIds = userService.getUserAllOrgId(user.getName());
        List<AuthGroup> groups =
                getAuthGroups(requestedModelIds, user, new ArrayList<>(userOrgIds));
        if (groups.size() > MAX_MATCHED_GROUPS) {
            throw new IllegalStateException(
                    "Matched authorization group count exceeds maximum: " + MAX_MATCHED_GROUPS);
        }
        AuthorizedResourceResp resource = new AuthorizedResourceResp();
        resource.setEffectiveOrganizationIds(new LinkedHashSet<>(userOrgIds));
        Map<Long, List<AuthGroup>> authGroupsByModelId =
                groups.stream().sorted((left, right) -> Integer.compare(
                        right.getPriority() == null ? 0 : right.getPriority(),
                        left.getPriority() == null ? 0 : left.getPriority()))
                        .collect(Collectors.groupingBy(AuthGroup::getModelId,
                                LinkedHashMap::new, Collectors.toList()));
        Map<String, ResourcePermission> permissions = new LinkedHashMap<>();
        for (Long modelId : requestedModelIds) {
            if (authGroupsByModelId.containsKey(modelId)) {
                List<AuthGroup> authGroups = authGroupsByModelId.get(modelId);
                for (AuthGroup authRuleGroup : authGroups) {
                    PolicyEffect effect = effectiveEffect(authRuleGroup);
                    addLegacyResourcePermissions(permissions, modelId, authRuleGroup, effect);
                    addExplicitResourcePermissions(permissions, modelId, authRuleGroup, effect);
                    resource.getMatchedGroupIds().add(authRuleGroup.getGroupId());
                    resource.setPolicyVersion(nextPolicyVersion(resource.getPolicyVersion(),
                            authRuleGroup));
                }
            }
        }
        for (ResourcePermission permission : permissions.values()) {
            resource.getResourcePermissions().add(permission);
            if (permission.getAccessMode() == ColumnAccessMode.DENY) {
                continue;
            }
            resource.getAuthResList().add(new AuthRes(permission.getModelId(),
                    permission.getResourceName()));
        }
        Set<Map.Entry<Long, List<AuthGroup>>> entries = authGroupsByModelId.entrySet();
        long filterExpressionCount = 0;
        long filterTextLength = 0;
        for (Map.Entry<Long, List<AuthGroup>> entry : entries) {
            List<AuthGroup> authGroups = entry.getValue();
            for (AuthGroup authGroup : authGroups) {
                List<String> expressions =
                        CollectionUtils.isEmpty(authGroup.getDimensionFilters()) ? List.of()
                                : authGroup.getDimensionFilters();
                filterExpressionCount += expressions.size();
                filterTextLength += expressions.stream().mapToLong(String::length).sum();
                filterTextLength += authGroup.getDimensionFilterDescription() == null ? 0
                        : authGroup.getDimensionFilterDescription().length();
                if (filterExpressionCount > MAX_AUTHORIZED_FILTER_EXPRESSIONS) {
                    throw new IllegalStateException(
                            "Authorized row filter expression count exceeds maximum: "
                                    + MAX_AUTHORIZED_FILTER_EXPRESSIONS);
                }
                if (filterTextLength > MAX_AUTHORIZED_FILTER_TEXT_LENGTH) {
                    throw new IllegalStateException(
                            "Authorized row filter text exceeds maximum length: "
                                    + MAX_AUTHORIZED_FILTER_TEXT_LENGTH);
                }
                DimensionFilter df = new DimensionFilter();
                df.setModelId(authGroup.getModelId());
                df.setDescription(authGroup.getDimensionFilterDescription());
                df.setExpressions(List.copyOf(expressions));
                df.setEffect(effectiveEffect(authGroup));
                resource.getFilters().add(df);

                for (RowFilterRule rule : authGroup.getRowFilterRules()) {
                    DimensionFilter structured = new DimensionFilter();
                    structured.setModelId(authGroup.getModelId());
                    structured.setDescription(authGroup.getDimensionFilterDescription());
                    structured.setEffect(effectiveEffect(authGroup));
                    structured.setStructured(true);
                    structured.setExpressions(List.of(compileRowRule(rule, user, userOrgIds)));
                    resource.getFilters().add(structured);
                }
            }
        }
        return resource;
    }

    private List<AuthGroup> getAuthGroups(List<Long> modelIds, User user,
            List<String> departmentIds) {
        Set<Long> requestedModelIds = new HashSet<>(modelIds);
        List<AuthGroup> groups = load().stream().filter(group -> {
            if (!requestedModelIds.contains(group.getModelId())) {
                return false;
            }
            if (!isPolicyActive(group)) {
                return false;
            }
            return authGroupMatcher.matches(group, user, departmentIds);
        }).collect(Collectors.toList());
        log.debug(
                "Authorization groups resolved: user=[{}], departments=[{}], roles=[{}], count={}",
                SensitiveLogUtils.summarize(user.getName()),
                SensitiveLogUtils.summarize(departmentIds),
                SensitiveLogUtils.summarize(user.getRoles()), groups.size());
        return groups;
    }

    private void validateAuthGroup(AuthGroup group) {
        if (group == null) {
            throw new IllegalArgumentException("Authorization group is required");
        }
        if (group.getModelId() == null || group.getModelId() <= 0) {
            throw new IllegalArgumentException("Authorization group modelId must be positive");
        }
        if (!StringUtils.hasText(group.getName())) {
            throw new IllegalArgumentException("Authorization group name is required");
        }
        validateText(group.getName(), "group name");
        validateText(group.getDimensionFilterDescription(), "dimension filter description");
        if (group.getGroupId() != null && group.getGroupId() <= 0) {
            throw new IllegalArgumentException("Authorization group id must be positive");
        }

        boolean hasSubject = validateIdentifiers(group.getAuthorizedUsers(), "user")
                | validateIdentifiers(group.getAuthorizedDepartmentIds(), "department")
                | validateIdentifiers(group.getAuthorizedRoles(), "role");
        if (!CollectionUtils.isEmpty(group.getAttributeConditions())) {
            if (group.getAttributeConditions().size() > MAX_ATTRIBUTES_PER_GROUP) {
                throw new IllegalArgumentException(
                        "Authorization attribute condition count exceeds " + "maximum: "
                                + MAX_ATTRIBUTES_PER_GROUP);
            }
            group.getAttributeConditions().forEach((key, value) -> {
                if (!StringUtils.hasText(key) || !StringUtils.hasText(value)) {
                    throw new IllegalArgumentException(
                            "Authorization attribute keys and values must not be blank");
                }
                validateText(key, "attribute key");
                validateText(value, "attribute value");
            });
            hasSubject = true;
        }
        if (!hasSubject) {
            throw new IllegalArgumentException(
                    "Authorization group must define at least one effective subject");
        }

        boolean hasLegacyRules = !CollectionUtils.isEmpty(group.getAuthRules());
        boolean hasExplicitRules = !CollectionUtils.isEmpty(group.getResourcePermissions());
        if (!hasLegacyRules && !hasExplicitRules) {
            throw new IllegalArgumentException(
                    "Authorization group must define at least one resource rule");
        }
        if (hasLegacyRules && group.getAuthRules().size() > MAX_RULES_PER_GROUP) {
            throw new IllegalArgumentException(
                    "Authorization resource rule count exceeds maximum: " + MAX_RULES_PER_GROUP);
        }
        long resourceCount = 0;
        for (AuthRule rule : hasLegacyRules ? group.getAuthRules() : List.<AuthRule>of()) {
            if (rule == null) {
                throw new IllegalArgumentException("Authorization resource rule must not be null");
            }
            validateText(rule.getName(), "resource rule name");
            validateText(rule.getDescription(), "resource rule description");
            boolean hasResource = validateIdentifiers(rule.getMetrics(), "metric")
                    | validateIdentifiers(rule.getDimensions(), "dimension");
            if (!hasResource) {
                throw new IllegalArgumentException(
                        "Authorization resource rule must contain a metric or dimension");
            }
            resourceCount += rule.resourceNames().size();
            if (resourceCount > MAX_RESOURCES_PER_GROUP) {
                throw new IllegalArgumentException(
                        "Authorization resource count exceeds maximum per group: "
                                + MAX_RESOURCES_PER_GROUP);
            }
        }
        validateIdentifiers(group.getDimensionFilters(), "dimension filter");
        if (group.getResourcePermissions().size() > MAX_RESOURCE_PERMISSIONS) {
            throw new IllegalArgumentException("Authorization resource permission count exceeds maximum: "
                    + MAX_RESOURCE_PERMISSIONS);
        }
        for (ResourcePermission permission : group.getResourcePermissions()) {
            if (permission == null || !StringUtils.hasText(permission.getResourceName())) {
                throw new IllegalArgumentException("Authorization resource permission is invalid");
            }
            if (permission.getAccessMode() == null) {
                throw new IllegalArgumentException("Authorization resource access mode is required");
            }
            validateText(permission.getResourceName(), "resource permission");
            validateText(permission.getMaskingStrategy(), "masking strategy");
            validateMaskingStrategy(permission.getMaskingStrategy());
        }
        if (group.getRowFilterRules().size() > MAX_STRUCTURED_ROW_RULES) {
            throw new IllegalArgumentException("Structured row filter count exceeds maximum: "
                    + MAX_STRUCTURED_ROW_RULES);
        }
        for (RowFilterRule rule : group.getRowFilterRules()) {
            validateRowRule(rule);
        }
    }

    private void normalizePolicyDefaults(AuthGroup group) {
        if (group == null) {
            return;
        }
        if (group.getEnabled() == null) {
            group.setEnabled(true);
        }
        if (group.getPriority() == null) {
            group.setPriority(0);
        }
        if (group.getEffect() == null) {
            group.setEffect(PolicyEffect.ALLOW);
        }
        if (group.getPolicyVersion() == null || group.getPolicyVersion() <= 0) {
            group.setPolicyVersion(1L);
        }
        if (group.getAuthRules() == null) {
            group.setAuthRules(new ArrayList<>());
        }
        if (group.getDimensionFilters() == null) {
            group.setDimensionFilters(new ArrayList<>());
        }
        if (group.getRowFilterRules() == null) {
            group.setRowFilterRules(new ArrayList<>());
        }
        if (group.getResourcePermissions() == null) {
            group.setResourcePermissions(new ArrayList<>());
        }
    }

    private String effectivePolicyCode(AuthGroup group) {
        return StringUtils.hasText(group.getPolicyCode()) ? group.getPolicyCode()
                : "AUTH_GROUP_" + group.getGroupId();
    }

    private void updatePolicyMetadata(AuthGroup group, Integer groupId) {
        jdbcTemplate.update("update s2_auth_groups set model_id = ?, policy_code = ?, enabled = ?, "
                + "policy_version = ?, valid_from = ?, valid_to = ?, updated_at = CURRENT_TIMESTAMP, "
                + "updated_by = ? where group_id = ?", group.getModelId(), effectivePolicyCode(group),
                group.getEnabled(), group.getPolicyVersion(), group.getValidFrom(), group.getValidTo(),
                "system", groupId);
    }

    private boolean isPolicyActive(AuthGroup group) {
        if (group.getEnabled() != null && !group.getEnabled()) {
            return false;
        }
        Date now = new Date();
        return (group.getValidFrom() == null || !now.before(group.getValidFrom()))
                && (group.getValidTo() == null || !now.after(group.getValidTo()));
    }

    private PolicyEffect effectiveEffect(AuthGroup group) {
        return group.getEffect() == null ? PolicyEffect.ALLOW : group.getEffect();
    }

    private void addLegacyResourcePermissions(Map<String, ResourcePermission> permissions,
            Long modelId, AuthGroup group, PolicyEffect effect) {
        if (CollectionUtils.isEmpty(group.getAuthRules())) {
            return;
        }
        for (AuthRule rule : group.getAuthRules()) {
            for (String name : rule.resourceNames()) {
                ResourcePermission permission = new ResourcePermission();
                permission.setModelId(modelId);
                permission.setResourceName(name);
                permission.setAccessMode(effect == PolicyEffect.DENY ? ColumnAccessMode.DENY
                        : ColumnAccessMode.MASKED);
                mergePermission(permissions, permission);
            }
        }
    }

    private void addExplicitResourcePermissions(Map<String, ResourcePermission> permissions,
            Long modelId, AuthGroup group, PolicyEffect effect) {
        for (ResourcePermission source : group.getResourcePermissions()) {
            ResourcePermission permission = new ResourcePermission();
            permission.setModelId(modelId);
            permission.setResourceType(source.getResourceType());
            permission.setResourceName(source.getResourceName());
            permission.setMaskingStrategy(source.getMaskingStrategy());
            permission.setAccessMode(effect == PolicyEffect.DENY ? ColumnAccessMode.DENY
                    : source.getAccessMode());
            mergePermission(permissions, permission);
        }
    }

    private void mergePermission(Map<String, ResourcePermission> permissions,
            ResourcePermission candidate) {
        String key = candidate.getModelId() + "\u0000"
                + candidate.getResourceName().toLowerCase(Locale.ROOT);
        ResourcePermission current = permissions.get(key);
        if (current == null || accessRank(candidate.getAccessMode()) > accessRank(current.getAccessMode())
                || candidate.getAccessMode() == ColumnAccessMode.DENY) {
            permissions.put(key, candidate);
        }
        if (permissions.size() > MAX_AUTHORIZED_RESOURCES) {
            throw new IllegalStateException("Authorized resource count exceeds maximum: "
                    + MAX_AUTHORIZED_RESOURCES);
        }
    }

    private int accessRank(ColumnAccessMode mode) {
        return switch (mode) {
            case MASKED -> 1;
            case RAW -> 2;
            case DENY -> 3;
        };
    }

    private long nextPolicyVersion(long current, AuthGroup group) {
        long groupVersion = group.getPolicyVersion() == null ? 1L : group.getPolicyVersion();
        return Math.max(1L, 31L * current + 31L * groupVersion
                + (group.getGroupId() == null ? 0 : group.getGroupId()));
    }

    private void validateRowRule(RowFilterRule rule) {
        if (rule == null || !StringUtils.hasText(rule.getField())
                || !StringUtils.hasText(rule.getOperator())) {
            throw new IllegalArgumentException("Structured row filter is invalid");
        }
        if (rule.getValues() == null) {
            rule.setValues(new ArrayList<>());
        }
        if (!rule.getField().matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Structured row filter field is invalid");
        }
        Set<String> operators = Set.of("EQ", "IN", "BETWEEN", "LIKE");
        if (!operators.contains(rule.getOperator().toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Structured row filter operator is invalid");
        }
        String source = rule.getValueSource() == null ? "" : rule.getValueSource();
        if (!Set.of("CONSTANT", "USER_ATTRIBUTE", "ORG_SCOPE")
                .contains(source.toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Structured row filter value source is invalid");
        }
        if (!source.equalsIgnoreCase("ORG_SCOPE") && CollectionUtils.isEmpty(rule.getValues())) {
            throw new IllegalArgumentException("Structured row filter values are required");
        }
        if (source.equalsIgnoreCase("USER_ATTRIBUTE") && rule.getValues().size() != 1) {
            throw new IllegalArgumentException(
                    "USER_ATTRIBUTE row filter requires exactly one attribute key");
        }
        rule.getValues().forEach(value -> validateText(value, "row filter value"));
    }

    private void validateMaskingStrategy(String strategy) {
        if (!StringUtils.hasText(strategy)) {
            return;
        }
        if (!Set.of("FULL", "LAST4", "FIRST_LAST", "HASH")
                .contains(strategy.trim().toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Authorization masking strategy is invalid");
        }
    }

    private String compileRowRule(RowFilterRule rule, User user, Set<String> orgIds) {
        validateRowRule(rule);
        String operator = rule.getOperator().toUpperCase(Locale.ROOT);
        List<String> values = resolveRowValues(rule, user, orgIds);
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Structured row filter resolved to no values");
        }
        String field = rule.getField();
        if (operator.equals("EQ")) {
            return field + " = " + sqlLiteral(values.get(0));
        }
        if (operator.equals("LIKE")) {
            return field + " LIKE " + sqlLiteral(values.get(0));
        }
        if (operator.equals("BETWEEN")) {
            if (values.size() != 2) {
                throw new IllegalArgumentException("BETWEEN requires two values");
            }
            return field + " BETWEEN " + sqlLiteral(values.get(0)) + " AND "
                    + sqlLiteral(values.get(1));
        }
        return field + " IN (" + values.stream().map(this::sqlLiteral)
                .collect(Collectors.joining(", ")) + ")";
    }

    private List<String> resolveRowValues(RowFilterRule rule, User user, Set<String> orgIds) {
        String source = (rule.getValueSource() == null ? "" : rule.getValueSource())
                .toUpperCase(Locale.ROOT);
        if (source.equals("ORG_SCOPE")) {
            return new ArrayList<>(orgIds);
        }
        if (source.equals("USER_ATTRIBUTE")) {
            if (user == null || CollectionUtils.isEmpty(user.getAttributes())) {
                return List.of();
            }
            String key = rule.getValues().get(0);
            String value = user.getAttributes().get(key);
            return StringUtils.hasText(value) ? List.of(value) : List.of();
        }
        return rule.getValues();
    }

    private String sqlLiteral(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private boolean validateIdentifiers(List<String> values, String type) {
        if (CollectionUtils.isEmpty(values)) {
            return false;
        }
        if (values.stream().anyMatch(value -> !StringUtils.hasText(value))) {
            throw new IllegalArgumentException(
                    "Authorization " + type + " values must not be blank");
        }
        if (values.size() > MAX_IDENTIFIERS_PER_FIELD) {
            throw new IllegalArgumentException("Authorization " + type
                    + " value count exceeds maximum: " + MAX_IDENTIFIERS_PER_FIELD);
        }
        values.forEach(value -> validateText(value, type));
        return true;
    }

    private void validateText(String value, String type) {
        if (value != null && value.length() > MAX_AUTH_TEXT_LENGTH) {
            throw new IllegalArgumentException(
                    "Authorization " + type + " exceeds maximum length: " + MAX_AUTH_TEXT_LENGTH);
        }
    }

    private String configMetadata(String row) {
        if (row == null) {
            return "null";
        }
        if (row.length() > MAX_CONFIG_LENGTH) {
            return "oversized,chars=" + row.length();
        }
        return SensitiveLogUtils.summarize(row);
    }
}
