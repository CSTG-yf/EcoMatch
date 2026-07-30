package com.tencent.supersonic.headless.server.security;

import com.tencent.supersonic.common.pojo.QueryColumn;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.SensitiveLevelEnum;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import com.tencent.supersonic.headless.api.pojo.SchemaItem;
import com.tencent.supersonic.headless.api.pojo.response.SemanticQueryResp;
import com.tencent.supersonic.headless.api.pojo.response.SemanticSchemaResp;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Masks sensitive result values before they are returned, exported, or sent to an LLM. */
@Component
public class DataMaskingService {

    private final MaskingParameterConfig parameterConfig;
    private final MaskingPolicy fixedPolicy;
    private volatile String cachedPolicyKey;
    private volatile MaskingPolicy cachedPolicy;

    @Autowired
    public DataMaskingService(MaskingParameterConfig parameterConfig) {
        this.parameterConfig = parameterConfig;
        this.fixedPolicy = null;
    }

    public DataMaskingService(String rawUsers, String rawRoles) {
        this(rawUsers, rawRoles, "");
    }

    public DataMaskingService(String rawUsers, String rawRoles, String fieldStrategies) {
        this.parameterConfig = null;
        this.fixedPolicy = parsePolicy(rawUsers, rawRoles, fieldStrategies);
    }

    public void mask(SemanticQueryResp response, SemanticSchemaResp schema, User user) {
        MaskingPolicy policy = currentPolicy();
        if (response == null || canViewRawData(user, policy) || response.getResultList() == null
                || response.getResultList().isEmpty()) {
            return;
        }
        requireMaskingMetadata(response, schema);
        Set<String> sensitiveFields = getSensitiveFields(schema);
        if (sensitiveFields.isEmpty()) {
            return;
        }
        Set<String> schemaFields = getSchemaFields(schema);

        Set<String> maskedColumns =
                Stream.ofNullable(response.getMaskedColumns()).flatMap(java.util.Collection::stream)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> declaredResultKeys = new HashSet<>();
        for (QueryColumn column : response.getColumns()) {
            if (column == null) {
                throw new InvalidPermissionException(
                        "Data masking column lineage is unavailable; query result was denied");
            }
            Set<String> resultKeys =
                    Stream.of(column.getBizName(), column.getNameEn(), column.getName())
                            .filter(StringUtils::isNotBlank)
                            .collect(Collectors.toCollection(LinkedHashSet::new));
            Set<String> normalizedResultKeys = resultKeys.stream()
                    .map(key -> key.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
            declaredResultKeys.addAll(normalizedResultKeys);
            boolean sensitive = isSensitive(column, sensitiveFields);
            boolean unknownLineage = Collections.disjoint(normalizedResultKeys, schemaFields);
            if (!sensitive && !unknownLineage) {
                continue;
            }
            String sensitiveField = resultKeys.stream()
                    .filter(key -> sensitiveFields.contains(key.toLowerCase(Locale.ROOT)))
                    .findFirst().orElse(column.getBizName());
            for (Map<String, Object> row : response.getResultList()) {
                if (row == null) {
                    continue;
                }
                Set<String> matchingKeys = row.keySet().stream().filter(StringUtils::isNotBlank)
                        .filter(key -> normalizedResultKeys.contains(key.toLowerCase(Locale.ROOT)))
                        .collect(Collectors.toCollection(LinkedHashSet::new));
                for (String key : matchingKeys) {
                    maskedColumns.add(key);
                    if (row.get(key) != null) {
                        row.put(key, unknownLineage ? "****"
                                : maskValue(key, sensitiveField, row.get(key), policy));
                    }
                }
            }
        }
        maskUndeclaredResultKeys(response, declaredResultKeys, maskedColumns);
        response.setDataMasked(!maskedColumns.isEmpty());
        response.setMaskedColumns(maskedColumns);
    }

    private void maskUndeclaredResultKeys(SemanticQueryResp response,
            Set<String> declaredResultKeys, Set<String> maskedColumns) {
        for (Map<String, Object> row : response.getResultList()) {
            if (row == null) {
                continue;
            }
            for (String key : new HashSet<>(row.keySet())) {
                if (StringUtils.isNotBlank(key)
                        && !declaredResultKeys.contains(key.toLowerCase(Locale.ROOT))) {
                    maskedColumns.add(key);
                    if (row.get(key) != null) {
                        row.put(key, "****");
                    }
                }
            }
        }
    }

    private void requireMaskingMetadata(SemanticQueryResp response, SemanticSchemaResp schema) {
        if (schema == null || schema.getDimensions() == null || schema.getMetrics() == null
                || (schema.getDimensions().isEmpty() && schema.getMetrics().isEmpty())
                || response.getColumns() == null || response.getColumns().isEmpty()) {
            throw new InvalidPermissionException(
                    "Data masking metadata is unavailable; query result was denied");
        }
    }

    private Object maskValue(String resultField, String sensitiveField, Object value,
            MaskingPolicy policy) {
        MaskingStrategy aliasStrategy =
                policy.fieldStrategies.get(resultField.toLowerCase(Locale.ROOT));
        if (aliasStrategy != null) {
            return applyStrategy(aliasStrategy, String.valueOf(value));
        }
        return maskValue(StringUtils.defaultIfBlank(sensitiveField, resultField), value, policy);
    }

    private boolean canViewRawData(User user, MaskingPolicy policy) {
        return user != null && (user.isSuperAdmin() || policy.rawUsers.contains(user.getName())
                || !Collections.disjoint(policy.rawRoles,
                        user.getRoles() == null ? Collections.emptySet() : user.getRoles()));
    }

    private Set<String> getSensitiveFields(SemanticSchemaResp schema) {
        Set<String> fields = new HashSet<>();
        Stream.<SchemaItem>concat(
                Stream.ofNullable(schema.getDimensions()).flatMap(java.util.Collection::stream),
                Stream.ofNullable(schema.getMetrics()).flatMap(java.util.Collection::stream))
                .filter(item -> item.getSensitiveLevel() != null
                        && item.getSensitiveLevel() >= SensitiveLevelEnum.MID.getCode())
                .forEach(item -> {
                    addIfPresent(fields, item.getBizName());
                    addIfPresent(fields, item.getName());
                });
        return fields;
    }

    private Set<String> getSchemaFields(SemanticSchemaResp schema) {
        Set<String> fields = new HashSet<>();
        Stream.<SchemaItem>concat(schema.getDimensions().stream(), schema.getMetrics().stream())
                .forEach(item -> {
                    addIfPresent(fields, item.getBizName());
                    addIfPresent(fields, item.getName());
                });
        return fields;
    }

    private void addIfPresent(Set<String> fields, String value) {
        if (StringUtils.isNotBlank(value)) {
            fields.add(value.toLowerCase(Locale.ROOT));
        }
    }

    private boolean isSensitive(QueryColumn column, Set<String> sensitiveFields) {
        return Stream.of(column.getBizName(), column.getNameEn(), column.getName())
                .filter(StringUtils::isNotBlank).map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(sensitiveFields::contains);
    }

    Object maskValue(String fieldName, Object value) {
        return maskValue(fieldName, value, currentPolicy());
    }

    private Object maskValue(String fieldName, Object value, MaskingPolicy policy) {
        MaskingStrategy strategy = policy.fieldStrategies.get(fieldName.toLowerCase(Locale.ROOT));
        if (strategy != null) {
            return applyStrategy(strategy, String.valueOf(value));
        }
        if (value instanceof Number) {
            return "****";
        }
        String text = String.valueOf(value);
        String lowerName = fieldName.toLowerCase(Locale.ROOT);
        if (text.contains("@")) {
            int separator = text.indexOf('@');
            return maskRange(text, 1, Math.max(1, separator - 1));
        }
        if (lowerName.matches(".*(phone|mobile|tel|手机号|电话).*") && text.length() >= 7) {
            return maskRange(text, 3, text.length() - 4);
        }
        if (lowerName.matches(".*(idcard|id_card|证件|身份证).*") && text.length() >= 10) {
            return maskRange(text, 6, text.length() - 4);
        }
        if (lowerName.matches(".*(account|card|acct|账号|卡号).*") && text.length() >= 8) {
            return maskRange(text, 4, text.length() - 4);
        }
        if (text.length() <= 1) {
            return "*";
        }
        return text.charAt(0) + "***";
    }

    private Object applyStrategy(MaskingStrategy strategy, String value) {
        switch (strategy) {
            case FULL:
                return "****";
            case LAST4:
                return value.length() <= 4 ? "****" : "****" + value.substring(value.length() - 4);
            case FIRST_LAST:
                return value.length() <= 2 ? "****"
                        : value.charAt(0) + "***" + value.charAt(value.length() - 1);
            default:
                return value;
        }
    }

    private MaskingPolicy currentPolicy() {
        if (parameterConfig == null) {
            return fixedPolicy;
        }
        String rawUsers = StringUtils.defaultString(parameterConfig.rawUsers());
        String rawRoles = StringUtils.defaultString(parameterConfig.rawRoles());
        String fieldStrategies = StringUtils.defaultString(parameterConfig.fieldStrategies());
        String policyKey = rawUsers + '\u0000' + rawRoles + '\u0000' + fieldStrategies;
        MaskingPolicy policy = cachedPolicy;
        if (policy != null && policyKey.equals(cachedPolicyKey)) {
            return policy;
        }
        synchronized (this) {
            if (cachedPolicy == null || !policyKey.equals(cachedPolicyKey)) {
                cachedPolicy = parsePolicy(rawUsers, rawRoles, fieldStrategies);
                cachedPolicyKey = policyKey;
            }
            return cachedPolicy;
        }
    }

    private MaskingPolicy parsePolicy(String rawUsers, String rawRoles, String fieldStrategies) {
        return new MaskingPolicy(parseIdentifiers(rawUsers), parseIdentifiers(rawRoles),
                parseFieldStrategies(fieldStrategies));
    }

    private Set<String> parseIdentifiers(String configuredIdentifiers) {
        return Arrays.stream(StringUtils.defaultString(configuredIdentifiers).split(","))
                .map(String::trim).filter(StringUtils::isNotBlank)
                .collect(Collectors.toUnmodifiableSet());
    }

    private Map<String, MaskingStrategy> parseFieldStrategies(String configuredStrategies) {
        Map<String, MaskingStrategy> strategies = new LinkedHashMap<>();
        for (String item : StringUtils.defaultString(configuredStrategies).split(",")) {
            if (StringUtils.isBlank(item)) {
                continue;
            }
            String[] pair = item.split("=", 2);
            if (pair.length != 2 || StringUtils.isAnyBlank(pair[0], pair[1])) {
                throw new IllegalArgumentException(
                        "Invalid masking field strategy, expected field=STRATEGY: " + item);
            }
            strategies.put(pair[0].trim().toLowerCase(Locale.ROOT),
                    MaskingStrategy.valueOf(pair[1].trim().toUpperCase(Locale.ROOT)));
        }
        return Collections.unmodifiableMap(strategies);
    }

    private String maskRange(String value, int prefixLength, int suffixStart) {
        int prefix = Math.min(prefixLength, value.length());
        int suffix = Math.max(prefix, Math.min(suffixStart, value.length()));
        return value.substring(0, prefix) + "****" + value.substring(suffix);
    }

    private enum MaskingStrategy {
        FULL, LAST4, FIRST_LAST
    }

    private static final class MaskingPolicy {
        private final Set<String> rawUsers;
        private final Set<String> rawRoles;
        private final Map<String, MaskingStrategy> fieldStrategies;

        private MaskingPolicy(Set<String> rawUsers, Set<String> rawRoles,
                Map<String, MaskingStrategy> fieldStrategies) {
            this.rawUsers = rawUsers;
            this.rawRoles = rawRoles;
            this.fieldStrategies = fieldStrategies;
        }
    }
}
