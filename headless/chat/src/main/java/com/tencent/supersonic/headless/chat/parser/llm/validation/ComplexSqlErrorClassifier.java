package com.tencent.supersonic.headless.chat.parser.llm.validation;

import com.tencent.supersonic.headless.api.pojo.enums.SqlErrorType;
import org.apache.commons.lang3.StringUtils;

import java.util.Locale;

public final class ComplexSqlErrorClassifier {

    private ComplexSqlErrorClassifier() {}

    public static SqlErrorType classifyExecutionError(String message) {
        if (StringUtils.isBlank(message)) {
            return SqlErrorType.NONE;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "syntax", "parse error", "unexpected token", "语法")) {
            return SqlErrorType.SYNTAX_ERROR;
        }
        if (containsAny(normalized, "unknown column", "column not found", "unknown field", "不存在的列",
                "字段不存在")) {
            return SqlErrorType.MAPPING_ERROR;
        }
        if (containsAny(normalized, "join", "ambiguous column", "关联")) {
            return SqlErrorType.JOIN_ERROR;
        }
        if (containsAny(normalized, "where", "filter", "invalid date", "过滤", "日期格式")) {
            return SqlErrorType.FILTER_ERROR;
        }
        return SqlErrorType.EXECUTION_ERROR;
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }
}
