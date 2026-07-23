package com.tencent.supersonic.headless.chat.parser.llm.validation;

import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.api.pojo.SqlEvaluation;
import com.tencent.supersonic.headless.api.pojo.enums.SqlErrorType;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMReq;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic validation and capability matching for generated S2SQL candidates. */
public class ComplexSqlValidator {

    private static final Pattern DATE_LITERAL =
            Pattern.compile("'\\d{4}[-/]\\d{1,2}(?:[-/]\\d{1,2})?'");
    private static final Pattern JOIN = Pattern.compile("\\bjoin\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern JOIN_CONDITION =
            Pattern.compile("\\b(?:on|using)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern AGGREGATE =
            Pattern.compile("\\b(?:sum|avg|count|min|max)\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern WINDOW = Pattern.compile(
            "\\b(?:row_number|rank|dense_rank|lag|lead)\\s*\\([^)]*\\)\\s*over\\s*\\(",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LIMIT =
            Pattern.compile("\\blimit\\s+\\d+", Pattern.CASE_INSENSITIVE);

    public ComplexSqlValidationResult validate(String sql, LLMReq.LLMSchema schema,
            String queryText) {
        SqlEvaluation evaluation = new SqlEvaluation();
        EnumSet<ComplexSqlFeature> features = detectFeatures(sql, queryText);
        evaluation.setFeatures(features.stream().map(Enum::name).toList());
        List<String> issues = new ArrayList<>();
        SqlErrorType errorType = SqlErrorType.NONE;

        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(sql);
        } catch (Exception e) {
            return invalid(
                    SqlErrorType.SYNTAX_ERROR, "SQL syntax validation failed: " + StringUtils
                            .defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()),
                    features);
        }
        if (!(statement instanceof Select)) {
            return invalid(SqlErrorType.SYNTAX_ERROR, "Only SELECT or WITH queries are allowed.",
                    features);
        }

        String normalizedSql = sql.toLowerCase(Locale.ROOT);
        String normalizedQuestion = StringUtils.defaultString(queryText).toLowerCase(Locale.ROOT);
        if (schema != null && StringUtils.isNotBlank(schema.getDataSetName())
                && !containsIdentifier(normalizedSql, schema.getDataSetName())) {
            issues.add("The semantic dataset is missing from the query.");
            errorType = SqlErrorType.MAPPING_ERROR;
        }
        if (schema != null && !containsAnySemanticField(normalizedSql, schema)) {
            issues.add("The query does not reference any available semantic field.");
            errorType = SqlErrorType.MAPPING_ERROR;
        }
        if (count(JOIN, sql) > count(JOIN_CONDITION, sql)) {
            issues.add("Every JOIN must provide an ON or USING condition.");
            errorType = SqlErrorType.JOIN_ERROR;
        }

        Set<ComplexSqlFeature> expected = expectedFeatures(normalizedQuestion);
        if (expected.contains(ComplexSqlFeature.TOP_N)
                && !(normalizedSql.contains("order by") && (LIMIT.matcher(sql).find()
                        || features.contains(ComplexSqlFeature.WINDOW_FUNCTION)))) {
            issues.add("TopN requires deterministic ordering and a row limit or ranking window.");
            errorType = SqlErrorType.DEFINITION_ERROR;
        }
        if ((expected.contains(ComplexSqlFeature.YOY) || expected.contains(ComplexSqlFeature.MOM))
                && count(DATE_LITERAL, sql) < 2
                && !features.contains(ComplexSqlFeature.WINDOW_FUNCTION)) {
            issues.add("Period comparison requires current and comparison periods.");
            errorType = SqlErrorType.FILTER_ERROR;
        }
        if (containsAny(normalizedQuestion, "均值", "平均")
                && containsAny(normalizedQuestion, "高于", "超过", "之上")
                && !(features.contains(ComplexSqlFeature.AGGREGATION)
                        && features.contains(ComplexSqlFeature.NESTED_QUERY))) {
            issues.add("Average comparison requires an aggregate subquery or CTE.");
            errorType = SqlErrorType.DEFINITION_ERROR;
        }
        if (containsTimeIntent(normalizedQuestion) && !hasTimeFilter(normalizedSql)) {
            issues.add("The question contains a time constraint but SQL has no time filter.");
            errorType = SqlErrorType.FILTER_ERROR;
        }

        evaluation.setIsValidated(issues.isEmpty());
        evaluation.setErrorType(errorType);
        evaluation.setRetryable(!issues.isEmpty());
        evaluation.setValidateMsg(String.join(" ", issues));
        double coverage = expected.isEmpty() ? 1D
                : expected.stream().filter(features::contains).count() / (double) expected.size();
        evaluation.setSemanticScore(coverage);
        return ComplexSqlValidationResult.builder().evaluation(evaluation).features(features)
                .rankingScore(coverage + Math.min(features.size(), 5) * 0.01).build();
    }

    private ComplexSqlValidationResult invalid(SqlErrorType type, String message,
            EnumSet<ComplexSqlFeature> features) {
        SqlEvaluation evaluation = new SqlEvaluation();
        evaluation.setIsValidated(false);
        evaluation.setErrorType(type);
        evaluation.setRetryable(true);
        evaluation.setValidateMsg(message);
        evaluation.setFeatures(features.stream().map(Enum::name).toList());
        evaluation.setSemanticScore(0D);
        return ComplexSqlValidationResult.builder().evaluation(evaluation).features(features)
                .rankingScore(0D).build();
    }

    private EnumSet<ComplexSqlFeature> detectFeatures(String sql, String queryText) {
        EnumSet<ComplexSqlFeature> features = EnumSet.noneOf(ComplexSqlFeature.class);
        String normalizedSql = StringUtils.defaultString(sql).toLowerCase(Locale.ROOT);
        String normalizedQuestion = StringUtils.defaultString(queryText).toLowerCase(Locale.ROOT);
        int joins = count(JOIN, sql);
        features.add(joins > 0 ? ComplexSqlFeature.MULTI_TABLE : ComplexSqlFeature.SINGLE_TABLE);
        if (normalizedSql.contains("select")
                && (normalizedSql.indexOf("select") != normalizedSql.lastIndexOf("select")
                        || normalizedSql.startsWith("with "))) {
            features.add(ComplexSqlFeature.NESTED_QUERY);
        }
        if (AGGREGATE.matcher(sql).find()) {
            features.add(ComplexSqlFeature.AGGREGATION);
        }
        if (WINDOW.matcher(sql).find()) {
            features.add(ComplexSqlFeature.WINDOW_FUNCTION);
        }
        if (containsAny(normalizedQuestion, "同比", "上年同期", "去年同期")) {
            features.add(ComplexSqlFeature.YOY);
        }
        if (containsAny(normalizedQuestion, "环比", "上月", "上季")) {
            features.add(ComplexSqlFeature.MOM);
        }
        if (containsAny(normalizedQuestion, "top", "前", "最高", "最低", "排名")
                && (LIMIT.matcher(sql).find()
                        || features.contains(ComplexSqlFeature.WINDOW_FUNCTION))) {
            features.add(ComplexSqlFeature.TOP_N);
        }
        if (containsAny(normalizedQuestion, "全省", "机构", "农商行", "对比", "比较")) {
            features.add(ComplexSqlFeature.CROSS_ORGANIZATION);
        }
        return features;
    }

    private Set<ComplexSqlFeature> expectedFeatures(String question) {
        Set<ComplexSqlFeature> expected = new HashSet<>();
        if (containsAny(question, "同比", "上年同期", "去年同期")) {
            expected.add(ComplexSqlFeature.YOY);
        }
        if (containsAny(question, "环比", "上月", "上季")) {
            expected.add(ComplexSqlFeature.MOM);
        }
        if (containsAny(question, "top", "前", "最高", "最低", "排名")) {
            expected.add(ComplexSqlFeature.TOP_N);
        }
        if (containsAny(question, "平均", "均值", "合计", "总计")) {
            expected.add(ComplexSqlFeature.AGGREGATION);
        }
        if (containsAny(question, "全省", "哪些机构", "哪家", "农商行", "对比", "比较")) {
            expected.add(ComplexSqlFeature.CROSS_ORGANIZATION);
        }
        return expected;
    }

    private boolean containsAnySemanticField(String normalizedSql, LLMReq.LLMSchema schema) {
        List<SchemaElement> elements = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(schema.getMetrics())) {
            elements.addAll(schema.getMetrics());
        }
        if (CollectionUtils.isNotEmpty(schema.getDimensions())) {
            elements.addAll(schema.getDimensions());
        }
        for (SchemaElement element : elements) {
            if (containsIdentifier(normalizedSql, element.getName())
                    || containsIdentifier(normalizedSql, element.getBizName())) {
                return true;
            }
            if (CollectionUtils.isNotEmpty(element.getAlias())) {
                for (String alias : element.getAlias()) {
                    if (containsIdentifier(normalizedSql, alias)) {
                        return true;
                    }
                }
            }
        }
        return elements.isEmpty();
    }

    private boolean containsIdentifier(String normalizedSql, String identifier) {
        return StringUtils.isNotBlank(identifier)
                && normalizedSql.contains(identifier.toLowerCase(Locale.ROOT));
    }

    private boolean containsTimeIntent(String question) {
        return DATE_LITERAL.matcher(question).find() || question.matches(".*\\d{4}年.*")
                || containsAny(question, "本月", "本年", "今年", "去年", "上月", "上季", "截至");
    }

    private boolean hasTimeFilter(String normalizedSql) {
        return normalizedSql.contains(" where ")
                && (DATE_LITERAL.matcher(normalizedSql).find() || normalizedSql.contains("date")
                        || normalizedSql.contains("日期") || normalizedSql.contains("时间"));
    }

    private int count(Pattern pattern, String value) {
        int result = 0;
        Matcher matcher = pattern.matcher(StringUtils.defaultString(value));
        while (matcher.find()) {
            result++;
        }
        return result;
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }
}
