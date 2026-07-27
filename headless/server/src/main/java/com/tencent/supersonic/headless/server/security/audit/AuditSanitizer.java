package com.tencent.supersonic.headless.server.security.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AuditSanitizer {

    private static final int QUESTION_LIMIT = 1000;
    private static final int SANITIZATION_INPUT_LIMIT = 10000;
    private static final int LABEL_LIMIT = 255;
    private static final String SECRET_FIELD_NAME =
            "(?<![\\p{L}\\p{N}_-])(?:password|passwd|pwd|set-cookie|"
                    + "(?:access[_-]?|refresh[_-]?|id[_-]?|auth[_-]?)?token|cookie|密码|口令|令牌)";
    private static final String SECRET_ASSIGNMENT = "\\s*(?:[:=：＝]|是)\\s*";
    private static final Pattern AUTHORIZATION_BEARER = Pattern
            .compile("(?i)((?:[\"']?authorization[\"']?)\\s*[:=：＝]\\s*(?:[\"']?\\s*))bearer\\s+"
                    + "([^\\s\"',;，；}\\]]+)");
    private static final Pattern QUOTED_SECRET_FIELD =
            Pattern.compile("(?i)((?:[\"']?" + SECRET_FIELD_NAME + "[\"']?)" + SECRET_ASSIGNMENT
                    + ")([\"'])(?:\\\\.|(?!\\2)[^\\r\\n])*\\2");
    private static final Pattern UNQUOTED_SECRET_FIELD = Pattern.compile("(?i)((?:[\"']?"
            + SECRET_FIELD_NAME + "[\"']?)" + SECRET_ASSIGNMENT + ")(?![\"'])([^\\s,;，；}\\]]+)");
    private static final Pattern PHONE =
            Pattern.compile("(?<!\\d)(1[3-9]\\d)\\d{4}(\\d{4})(?!\\d)");
    private static final Pattern ID_CARD =
            Pattern.compile("(?<!\\d)(\\d{6})\\d{8}(\\d{3}[0-9Xx])(?!\\d)");
    private static final Pattern LONG_ACCOUNT =
            Pattern.compile("(?<!\\d)(\\d{4})(\\d{4,16})(\\d{4})(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile(
            "(?i)(?<![a-z0-9._%+-])([a-z0-9])[a-z0-9._%+-]*(@[a-z0-9.-]+\\.[a-z]{2,})(?![a-z0-9._%+-])");
    private static final Pattern LEADING_SQL_COMMENT =
            Pattern.compile("(?is)^\\s*(?:(?:--[^\\r\\n]*(?:\\r?\\n|$))|(?:/\\*.*?\\*/))*\\s*");
    private static final Pattern SQL_VERB = Pattern.compile("(?i)^([a-z]+)");
    private static final Set<String> SAFE_METADATA_KEYS =
            Set.of("stage", "modelIds", "dataSetId", "rowCount", "columnCount", "maskedFields",
                    "cacheHit", "queryState", "queryMode", "filterCount", "policyCount",
                    "batchSize", "sheetCount", "exceptionType", "entryPoint", "needAuth");

    private final ObjectMapper objectMapper;

    public AuditSanitizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String sanitizeQuestion(String question) {
        if (StringUtils.isBlank(question)) {
            return null;
        }
        String input = limit(question, SANITIZATION_INPUT_LIMIT);
        String sanitized = AUTHORIZATION_BEARER.matcher(input).replaceAll("$1***");
        sanitized = QUOTED_SECRET_FIELD.matcher(sanitized).replaceAll("$1$2***$2");
        sanitized = UNQUOTED_SECRET_FIELD.matcher(sanitized).replaceAll("$1***");
        sanitized = PHONE.matcher(sanitized).replaceAll("$1****$2");
        sanitized = ID_CARD.matcher(sanitized).replaceAll("$1********$2");
        sanitized = maskLongAccounts(sanitized);
        sanitized = EMAIL.matcher(sanitized).replaceAll("$1***$2");
        return limit(stripControlCharacters(sanitized), QUESTION_LIMIT);
    }

    public String digest(String value) {
        return StringUtils.isBlank(value) ? null : DigestUtils.sha256Hex(value);
    }

    public String detectSqlType(String sql) {
        if (StringUtils.isBlank(sql)) {
            return null;
        }
        String normalized = LEADING_SQL_COMMENT.matcher(sql).replaceFirst("");
        Matcher matcher = SQL_VERB.matcher(normalized);
        if (!matcher.find()) {
            return "OTHER";
        }
        return switch (matcher.group(1).toUpperCase()) {
            case "SELECT", "WITH", "SHOW", "DESC", "DESCRIBE", "EXPLAIN" -> matcher.group(1)
                    .toUpperCase();
            case "INSERT", "UPDATE", "DELETE", "MERGE" -> "DML";
            case "CREATE", "ALTER", "DROP", "TRUNCATE", "GRANT", "REVOKE" -> "DDL";
            default -> "OTHER";
        };
    }

    public String safeLabel(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        return limit(
                stripControlCharacters(sanitizeQuestion(limit(value, SANITIZATION_INPUT_LIMIT))),
                LABEL_LIMIT);
    }

    public String safeLabel(String value, int maximumLength) {
        return limit(safeLabel(value), Math.max(1, Math.min(maximumLength, LABEL_LIMIT)));
    }

    public String joinSafe(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return limit(values.stream().filter(StringUtils::isNotBlank).map(this::safeLabel).distinct()
                .sorted().reduce((left, right) -> left + "," + right).orElse(null), 4000);
    }

    public String safeMetadataJson(Map<String, ?> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        metadata.forEach((key, value) -> {
            if (SAFE_METADATA_KEYS.contains(key)) {
                safe.put(key, safeMetadataValue(value));
            }
        });
        if (safe.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(safe);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Audit metadata cannot be serialized", e);
        }
    }

    private Object safeMetadataValue(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().limit(100).map(item -> safeLabel(String.valueOf(item)))
                    .toList();
        }
        return safeLabel(String.valueOf(value));
    }

    private String maskLongAccounts(String value) {
        Matcher matcher = LONG_ACCOUNT.matcher(value);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(buffer,
                    matcher.group(1) + "*".repeat(matcher.group(2).length()) + matcher.group(3));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String stripControlCharacters(String value) {
        return value.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "");
    }

    private String limit(String value, int maximum) {
        if (value == null || value.length() <= maximum) {
            return value;
        }
        return value.substring(0, maximum);
    }
}
