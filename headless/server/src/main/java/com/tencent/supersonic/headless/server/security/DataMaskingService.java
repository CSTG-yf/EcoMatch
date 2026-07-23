package com.tencent.supersonic.headless.server.security;

import com.tencent.supersonic.common.pojo.QueryColumn;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.SensitiveLevelEnum;
import com.tencent.supersonic.headless.api.pojo.SchemaItem;
import com.tencent.supersonic.headless.api.pojo.response.SemanticQueryResp;
import com.tencent.supersonic.headless.api.pojo.response.SemanticSchemaResp;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

    private final Set<String> rawUsers;
    private final Set<String> rawRoles;
    private final Map<String, MaskingStrategy> fieldStrategies;

    @Autowired
    public DataMaskingService(@Value("${s2.security.masking.raw-users:}") String rawUsers,
            @Value("${s2.security.masking.raw-roles:}") String rawRoles,
            @Value("${s2.security.masking.field-strategies:}") String fieldStrategies) {
        this.rawUsers = Arrays.stream(StringUtils.defaultString(rawUsers).split(","))
                .map(String::trim).filter(StringUtils::isNotBlank).collect(Collectors.toSet());
        this.rawRoles = Arrays.stream(StringUtils.defaultString(rawRoles).split(","))
                .map(String::trim).filter(StringUtils::isNotBlank).collect(Collectors.toSet());
        this.fieldStrategies = parseFieldStrategies(fieldStrategies);
    }

    public DataMaskingService(String rawUsers, String rawRoles) {
        this(rawUsers, rawRoles, "");
    }

    public void mask(SemanticQueryResp response, SemanticSchemaResp schema, User user) {
        if (response == null || schema == null || canViewRawData(user)) {
            return;
        }
        Set<String> sensitiveFields = getSensitiveFields(schema);
        if (sensitiveFields.isEmpty() || response.getResultList() == null
                || response.getColumns() == null) {
            return;
        }

        Set<String> maskedColumns = new LinkedHashSet<>();
        for (QueryColumn column : response.getColumns()) {
            if (!isSensitive(column, sensitiveFields)) {
                continue;
            }
            Set<String> resultKeys =
                    Stream.of(column.getBizName(), column.getNameEn(), column.getName())
                            .filter(StringUtils::isNotBlank)
                            .collect(Collectors.toCollection(LinkedHashSet::new));
            for (Map<String, Object> row : response.getResultList()) {
                for (String key : resultKeys) {
                    if (row.containsKey(key) && row.get(key) != null) {
                        row.put(key, maskValue(key, row.get(key)));
                        maskedColumns.add(key);
                    }
                }
            }
        }
        response.setDataMasked(!maskedColumns.isEmpty());
        response.setMaskedColumns(maskedColumns);
    }

    private boolean canViewRawData(User user) {
        return user != null && (user.isSuperAdmin() || rawUsers.contains(user.getName())
                || !Collections.disjoint(rawRoles,
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
        MaskingStrategy strategy = fieldStrategies.get(fieldName.toLowerCase(Locale.ROOT));
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
        return strategies;
    }

    private String maskRange(String value, int prefixLength, int suffixStart) {
        int prefix = Math.min(prefixLength, value.length());
        int suffix = Math.max(prefix, Math.min(suffixStart, value.length()));
        return value.substring(0, prefix) + "****" + value.substring(suffix);
    }

    private enum MaskingStrategy {
        FULL, LAST4, FIRST_LAST
    }
}
