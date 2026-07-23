package com.tencent.supersonic.headless.server.security;

import com.tencent.supersonic.common.pojo.QueryColumn;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.SensitiveLevelEnum;
import com.tencent.supersonic.headless.api.pojo.SchemaItem;
import com.tencent.supersonic.headless.api.pojo.response.SemanticQueryResp;
import com.tencent.supersonic.headless.api.pojo.response.SemanticSchemaResp;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
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

    public DataMaskingService(@Value("${s2.security.masking.raw-users:}") String rawUsers,
            @Value("${s2.security.masking.raw-roles:}") String rawRoles) {
        this.rawUsers = Arrays.stream(StringUtils.defaultString(rawUsers).split(","))
                .map(String::trim).filter(StringUtils::isNotBlank).collect(Collectors.toSet());
        this.rawRoles = Arrays.stream(StringUtils.defaultString(rawRoles).split(","))
                .map(String::trim).filter(StringUtils::isNotBlank).collect(Collectors.toSet());
    }

    public void mask(SemanticQueryResp response, SemanticSchemaResp schema, User user) {
        if (response == null || schema == null || canViewRawData(user)) {
            return;
        }
        Set<String> sensitiveFields = getSensitiveFields(schema);
        if (sensitiveFields.isEmpty() || response.getResultList() == null) {
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
                || !java.util.Collections.disjoint(rawRoles, user.getRoles()));
    }

    private Set<String> getSensitiveFields(SemanticSchemaResp schema) {
        Set<String> fields = new HashSet<>();
        Stream.<SchemaItem>concat(schema.getDimensions().stream(), schema.getMetrics().stream())
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

    private String maskRange(String value, int prefixLength, int suffixStart) {
        int prefix = Math.min(prefixLength, value.length());
        int suffix = Math.max(prefix, Math.min(suffixStart, value.length()));
        return value.substring(0, prefix) + "****" + value.substring(suffix);
    }
}
