package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.chat.intent.BankFinancialLexicon;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMReq;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Bank free-SQL (ONE_PASS) prompts split for llama.cpp prefix caching.
 *
 * <p>
 * {@link #FIXED_SYSTEM_PREFIX} (+ frozen schema catalog) is the system message. User turns are
 * question-only via {@link #buildQuestionOnlyUserContent}; never re-embed schema.
 */
public final class BankFreeSqlPromptComposer {

    /**
     * Version tag for logs / ablation. Bump when {@link #FIXED_SYSTEM_PREFIX} text changes. Runtime
     * prefix version also appends a stable-schema fingerprint (see {@link #prefixVersion(String)}).
     */
    public static final String PROMPT_VERSION = "bank-free-sql-sys-v7-fields-examples";

    private static final Pattern FORBIDDEN_LONG_TABLE = Pattern
            .compile("(?i)(\\b指标\\b\\s*=|\\bmetric_code\\b|\\bmetric_value\\b|\\borg_code\\b|"
                    + "\\b指标值\\b|\\bAS\\s+值\\b|,\\s*值\\b|\\b值\\s+FROM|SELECT\\s+指标\\b|"
                    + "bank_metric_daily|bank_organization\\b)");

    private static final Pattern FORBIDDEN_ZB_AS_COLUMN =
            Pattern.compile("(?i)(`?ZB\\d{3}`?\\s*[*+/]|\\bZB\\d{3}\\b\\s*[*+/]|`ZB\\d{3}`)");

    private static final Pattern SYNTHETIC_DATE_LITERAL = Pattern.compile("\\b20\\d{2}-\\d{2}-\\d{2}\\b");

    private static final Pattern SYNTHETIC_METRIC_CODE_FILTER = Pattern.compile(
            "(?i)(?:`?指标`?|`?bank_indicator`?)\\s*(?:IN\\s*\\(\\s*'([^']+)'|=\\s*'([^']+)')");

    private static final Pattern NON_POINT_QUERY = Pattern.compile(
            "环比|同比|较上月|较去年|较年初|较上季|较同期|趋势|走势|变动|变化|增长|下降|排名|最高|最低|最多|最少|平均|合计|累计|区间");

    /**
     * Fixed system prefix (prefix-cached). No per-question placeholders.
     *
     * <p>
     * Emphasizes SuperSonic S2SQL contract: metrics are columns, dimensions are filters — never
     * invent physical long-table columns like 指标/值.
     */
    public static final String FIXED_SYSTEM_PREFIX =
            """
                                        你是银行问数 S2SQL 生成器。把中文问题写成语义层可执行的一条 S2SQL。
                                        先对齐：意图、指标中文名、机构码、时间、排序（不良率/成本收入比/逾期率越小越好→ASC）。
                                        最终只输出 JSON（无 Markdown）：{"thought":"一句结论","sql":"一条完整S2SQL"}

                                        ════════════════════════════════
                                        一、可输出 / 可引用字段（语义层）
                                        ════════════════════════════════
                                        【表】以文末【语义目录】Table 为准，常见：银行日指标数据集

                                        【度量列（SELECT 中写中文名，不要写 ZB 当列名）】
                    %s

                                        【维度列】
                                        机构 —— 机构过滤/分组；取值 'ORG001'…'ORG013'（A市…M市）
                                        数据日期 —— 日期过滤/分组；字面量 'YYYY-MM-DD'

                                        【派生表达式（非独立列，在 SELECT 中计算）】
                                        存贷比 = `各项贷款余额`*100/NULLIF(`各项存款余额`,0)
                                        净利润率 = `净利润`*100/NULLIF(`营业收入`,0)
                                        对公贷款占比 = `对公贷款余额`*100/NULLIF(`各项贷款余额`,0)
                                        个人贷款占比 = `个人贷款余额`*100/NULLIF(`各项贷款余额`,0)
                                        对公存款占比 = `对公存款余额`*100/NULLIF(`各项存款余额`,0)

                                        【机构码】ORG001=A市 … ORG013=M市。「全省/哪家/各家」→ 不要写单一机构等值条件

                                        禁止：WHERE 指标=…；列名 值/指标值/metric_code/metric_value；物理表；SELECT 里写 `ZB013` 当列

                                        ════════════════════════════════
                                        二、时间规则
                                        ════════════════════════════════
                                        月末→该月最后一天；年末→12-31；全年→01-01～12-31
                                        一季末03-31 二季末06-30 三季末09-30 四季末12-31
                                        环比=上月末；同比=去年同月末；较年初=上年12-31
                                        日期一律字面量，禁止用函数推「上个月」

                                        ════════════════════════════════
                                        三、精确样例（字段以【语义目录】为准；下列表名/列名最常见）
                                        ════════════════════════════════

                                        【样例1 点查】A市 2025-06-15 各项存款余额
                                        {"thought":"点查ORG001存款","sql":"SELECT `各项存款余额` FROM `银行日指标数据集` WHERE `数据日期`='2025-06-15' AND `机构`='ORG001'"}

                                        【样例2 不良率时点】E市 2026-03-31 不良贷款率
                                        {"thought":"点查ORG005不良率","sql":"SELECT `不良贷款率` FROM `银行日指标数据集` WHERE `数据日期`='2026-03-31' AND `机构`='ORG005'"}

                                        【样例3 占比】C市 2025-09-30 对公贷款占各项贷款
                                        {"thought":"比率对公贷款/各项贷款","sql":"SELECT `对公贷款余额`*100.0/NULLIF(`各项贷款余额`,0) AS `占比` FROM `银行日指标数据集` WHERE `数据日期`='2025-09-30' AND `机构`='ORG003'"}

                                        【样例4 净利润率】G市 2026-01-31
                                        {"thought":"比率净利润/营业收入","sql":"SELECT `净利润`*100.0/NULLIF(`营业收入`,0) AS `净利润率` FROM `银行日指标数据集` WHERE `数据日期`='2026-01-31' AND `机构`='ORG007'"}

                                        【样例5 三家谁不良率最低】B/F/J 2026-03-31（越小越好 ASC）
                                        {"thought":"三家比较不良率升序取1","sql":"SELECT `机构`,`不良贷款率` FROM `银行日指标数据集` WHERE `数据日期`='2026-03-31' AND `机构` IN ('ORG002','ORG006','ORG010') ORDER BY `不良贷款率` ASC LIMIT 1"}

                                        【样例6 三家谁存款最多】A/E/I 2025-12-31
                                        {"thought":"三家比较存款降序取1","sql":"SELECT `机构`,`各项存款余额` FROM `银行日指标数据集` WHERE `数据日期`='2025-12-31' AND `机构` IN ('ORG001','ORG005','ORG009') ORDER BY `各项存款余额` DESC LIMIT 1"}

                                        【样例7 环比变动】C市存款 2025-07-31 较上月末
                                        {"thought":"当期减上月末","sql":"WITH cur AS (SELECT `各项存款余额` AS current_value FROM `银行日指标数据集` WHERE `数据日期`='2025-07-31' AND `机构`='ORG003'), base AS (SELECT `各项存款余额` AS baseline_value FROM `银行日指标数据集` WHERE `数据日期`='2025-06-30' AND `机构`='ORG003') SELECT current_value, baseline_value, current_value-baseline_value AS absolute_change, CASE WHEN baseline_value=0 THEN NULL ELSE (current_value-baseline_value)*100.0/baseline_value END AS percent_change FROM cur CROSS JOIN base"}

                                        【样例8 较上年末】A市存款截至2025-03-31较2024年末
                                        {"thought":"当期减上年末","sql":"WITH cur AS (SELECT `各项存款余额` AS current_value FROM `银行日指标数据集` WHERE `数据日期`='2025-03-31' AND `机构`='ORG001'), base AS (SELECT `各项存款余额` AS baseline_value FROM `银行日指标数据集` WHERE `数据日期`='2024-12-31' AND `机构`='ORG001') SELECT current_value, baseline_value, current_value-baseline_value AS absolute_change, CASE WHEN baseline_value=0 THEN NULL ELSE (current_value-baseline_value)*100.0/baseline_value END AS percent_change FROM cur CROSS JOIN base"}

                                        【样例9 阈值】B市 2025-12-31 拨备覆盖率是否超过150%%
                                        {"thought":"阈值比较","sql":"SELECT `机构`,`拨备覆盖率`, CASE WHEN `拨备覆盖率`>150 THEN '是' ELSE '否' END AS `是否超过150` FROM `银行日指标数据集` WHERE `数据日期`='2025-12-31' AND `机构`='ORG002'"}

                                        【样例10 全省存款第一】2025-12-31 谁存款最多
                                        {"thought":"全省排名无机构过滤","sql":"SELECT `机构`,`各项存款余额` FROM `银行日指标数据集` WHERE `数据日期`='2025-12-31' ORDER BY `各项存款余额` DESC LIMIT 1"}
                                        """
                    .formatted(freeSqlMetricCatalogLines()).strip();

    /** Metric lines rendered from the lexicon so the free-SQL prompt never re-types the catalog. */
    private static String freeSqlMetricCatalogLines() {
        return BankFinancialLexicon.metrics().values().stream().map(metric -> {
            String unit = metric.getUnit().isEmpty() ? "" : "，" + metric.getUnit();
            String direction =
                    metric.getDirection() == BankFinancialLexicon.MetricDirection.LOWER_BETTER
                            ? "，越小越好"
                            : "";
            return "%s —— %s（%s%s%s）".formatted(metric.getName(), metric.getDescription(),
                    metric.getCode(), unit, direction);
        }).collect(Collectors.joining("\n"));
    }

    /**
     * Legacy single-blob template kept for tests/selectPromptTemplate detection only. Live bank
     * traffic must use {@link #composeSystemPrefix} + {@link #buildQuestionOnlyUserContent}; this
     * string intentionally has <b>no</b> {@code {{schema}}} slot so schema cannot be re-injected
     * into the user turn if someone wires the blob path by mistake.
     */
    public static final String BANK_FREE_SQL_INSTRUCTION = FIXED_SYSTEM_PREFIX + """

            【示例】
            {{exemplar}}

            【查询】
            问题：{{question}}
            附加：{{information}}
            """.stripIndent().strip();

    private BankFreeSqlPromptComposer() {}

    public static boolean isBankSchema(LLMReq.LLMSchema schema) {
        if (schema == null || schema.getDimensions() == null) {
            return false;
        }
        return isBankDimensions(schema.getDimensions());
    }

    public static boolean isBankDimensions(List<SchemaElement> dimensions) {
        if (dimensions == null || dimensions.isEmpty()) {
            return false;
        }
        var biz = dimensions.stream().filter(Objects::nonNull).map(SchemaElement::getBizName)
                .filter(Objects::nonNull).map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        return biz.contains("bank_organization") && biz.contains("bank_data_date");
    }

    public static boolean isBankSchema(String schemaStr) {
        if (schemaStr == null || schemaStr.isBlank()) {
            return false;
        }
        String lower = schemaStr.toLowerCase(Locale.ROOT);
        return lower.contains("bank_organization") && lower.contains("bank_data_date");
    }

    public static String selectPromptTemplate(LLMReq.LLMSchema schema, String defaultPrompt) {
        if (isBankSchema(schema)) {
            return BANK_FREE_SQL_INSTRUCTION;
        }
        return defaultPrompt;
    }

    public static String selectPromptTemplate(String schemaStr, String defaultPrompt) {
        if (isBankSchema(schemaStr)) {
            return BANK_FREE_SQL_INSTRUCTION;
        }
        return defaultPrompt;
    }

    /**
     * Stable schema for prefix KV: table + metrics + dimensions only. Excludes per-question
     * {@code Values} so the system message stays byte-stable across queries.
     */
    public static String buildStableSchemaBlock(LLMReq.LLMSchema schema) {
        if (schema == null) {
            return "Table=[], Metrics=[], Dimensions=[]";
        }
        String metrics = formatElements(schema.getMetrics());
        String dimensions = formatElements(schema.getDimensions());
        return String.format(
                "DatabaseType=[%s], DatabaseVersion=[%s], Table=[%s], Metrics=[%s], Dimensions=[%s]",
                nullToEmpty(schema.getDatabaseType()), nullToEmpty(schema.getDatabaseVersion()),
                nullToEmpty(schema.getDataSetName()), metrics, dimensions);
    }

    /**
     * System message = fixed bank rules + frozen schema catalog (llama.cpp prefix-cached).
     */
    public static String composeSystemPrefix(String stableSchemaBlock) {
        return FIXED_SYSTEM_PREFIX + """

                【语义目录】（写 SQL 必须用下列表名/度量/维度）
                %s
                """.formatted(nullToEmpty(stableSchemaBlock)).strip();
    }

    /** Prefix cache key: prompt version + short fingerprint of stable schema. */
    public static String prefixVersion(String stableSchemaBlock) {
        return PROMPT_VERSION + ":" + shortFingerprint(stableSchemaBlock);
    }

    /**
     * User turn for bank free-SQL: natural question + per-request SideInfo/Values only. Schema
     * catalogs and SQL rules stay in {@link #composeSystemPrefix} — never restated here.
     */
    public static String buildQuestionOnlyUserContent(String question, String sideInfo,
            String valuesHint) {
        String values = valuesHint == null ? "" : valuesHint.strip();
        // Refuse accidental schema catalog leakage into the user turn.
        if (looksLikeSchemaCatalog(values) || looksLikeSchemaCatalog(sideInfo)) {
            throw new IllegalArgumentException(
                    "bank free-SQL user content must not carry schema catalogs (Metrics=/Dimensions=)");
        }
        StringBuilder sb = new StringBuilder();
        sb.append(nullToEmpty(question).strip());
        if (sideInfo != null && !sideInfo.isBlank()) {
            sb.append("\n\n附加信息：").append(sideInfo.strip());
        }
        if (!values.isEmpty()) {
            sb.append("\n取值：").append(values);
        }
        return sb.toString().strip();
    }

    /**
     * @deprecated Bank free-SQL must not put schema into the user turn. Use
     *             {@link #buildQuestionOnlyUserContent} + {@link #composeSystemPrefix}.
     */
    @Deprecated
    public static String buildDynamicUserContent(CharSequence exemplars, String question,
            String schema, String sideInfo) {
        if (schema != null && !schema.isBlank()) {
            throw new IllegalArgumentException(
                    "schema must live in system prefix; do not pass schema into user content");
        }
        return buildQuestionOnlyUserContent(question, sideInfo, "");
    }

    /** True when text looks like a full schema dump rather than Values=/SideInfo. */
    static boolean looksLikeSchemaCatalog(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("metrics=") || lower.contains("dimensions=")
                || lower.contains("table=") && lower.contains("databasetype=")
                || text.contains("【语义目录】") || text.contains("可填写值目录");
    }

    public static String freeSqlWarmProbe() {
        return """
                前缀预热占位

                附加信息：CurrentDate=[1970-01-01]
                """.strip();
    }

    private static String formatElements(List<SchemaElement> elements) {
        if (elements == null || elements.isEmpty()) {
            return "";
        }
        return elements.stream().filter(Objects::nonNull).map(el -> {
            String name = el.getName() == null ? "" : el.getName();
            String biz = el.getBizName();
            if (biz != null && !biz.isBlank() && !biz.equals(name)) {
                return "<" + name + " BIZ '" + biz + "'>";
            }
            return "<" + name + ">";
        }).collect(Collectors.joining(","));
    }

    private static String shortFingerprint(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(nullToEmpty(value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(nullToEmpty(value).hashCode());
        }
    }

    /**
     * True when SQL still looks like the failed long-table / physical style.
     */
    public static boolean looksInvalidBankS2Sql(String sql) {
        if (sql == null || sql.isBlank()) {
            return true;
        }
        if (FORBIDDEN_LONG_TABLE.matcher(sql).find()) {
            return true;
        }
        // ZB as column / arithmetic operand (not inside comments — we ban outright)
        if (FORBIDDEN_ZB_AS_COLUMN.matcher(sql).find()) {
            return true;
        }
        // bare WHERE ... 指标
        String compact = sql.replace("`", "");
        if (compact.contains("指标 =") || compact.contains("指标=") || compact.contains("指标值")) {
            return true;
        }
        return false;
    }

    /**
     * Repairs model over-expansion for a synthetic-360 single-metric point query.
     *
     * <p>The synthetic fact table is deliberately a wide semantic table. When a question names
     * one metric, one organization, and one date, the result contract is one metric cell. This
     * narrow normalizer removes model-added related metrics without changing official bank data,
     * multi-metric questions, comparisons, or non-synthetic datasets.
     */
    public static String normalizeSynthetic360PointQuerySql(String question, String sql,
            LLMReq.LLMSchema schema) {
        if (sql == null || sql.isBlank() || !isSynthetic360Schema(schema)
                || question == null || question.isBlank() || NON_POINT_QUERY.matcher(question).find()) {
            return sql;
        }
        SchemaElement requestedMetric = resolveRequestedMetric(question, sql, schema.getMetrics());
        if (requestedMetric == null) {
            return sql;
        }
        Set<String> dates = findAll(SYNTHETIC_DATE_LITERAL, question);
        if (dates.size() != 1) {
            return sql;
        }
        SchemaElement organization = findDimension(schema, "bank_organization", "机构");
        SchemaElement date = findDimension(schema, "bank_data_date", "数据日期");
        if (organization == null || date == null || !containsIdentifier(sql, date.getName())) {
            return sql;
        }
        Set<String> organizations = extractDimensionValues(sql, organization);
        if (organizations.size() != 1) {
            return sql;
        }
        return "SELECT " + quoteIdentifier(semanticMetricName(requestedMetric)) + " FROM "
                + quoteIdentifier(schema.getDataSetName()) + " WHERE "
                + quoteIdentifier(date.getName()) + " = '" + dates.iterator().next() + "' AND "
                + quoteIdentifier(organization.getName()) + " = '" + organizations.iterator().next()
                + "'";
    }

    private static boolean isSynthetic360Schema(LLMReq.LLMSchema schema) {
        return schema != null && schema.getDataSetName() != null
                && schema.getDataSetName().toLowerCase(Locale.ROOT).contains("synthetic_360");
    }

    private static List<SchemaElement> matchingMetrics(String question,
            List<SchemaElement> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            return List.of();
        }
        List<SchemaElement> matched = new ArrayList<>();
        for (SchemaElement metric : metrics) {
            if (metric == null || !hasMetricTerm(question, metric.getName())
                    && (metric.getAlias() == null
                            || metric.getAlias().stream().noneMatch(alias -> hasMetricTerm(question, alias)))) {
                continue;
            }
            matched.add(metric);
        }
        return matched;
    }

    /** Resolve the requested metric from the model's first metric code when available. */
    private static SchemaElement resolveRequestedMetric(String question, String sql,
            List<SchemaElement> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            return null;
        }
        Matcher codeMatcher = SYNTHETIC_METRIC_CODE_FILTER.matcher(sql);
        if (codeMatcher.find()) {
            String code = codeMatcher.group(1) != null ? codeMatcher.group(1) : codeMatcher.group(2);
            for (SchemaElement metric : metrics) {
                if (metric == null) {
                    continue;
                }
                if (code.equalsIgnoreCase(metric.getBizName())
                        || code.equalsIgnoreCase(metric.getName())) {
                    return metric;
                }
            }
        }
        List<SchemaElement> matchedMetrics = matchingMetrics(question, metrics);
        if (matchedMetrics.size() == 1) {
            return matchedMetrics.get(0);
        }
        return matchedMetrics.stream().filter(metric -> hasExactMetricTerm(question, metric))
                .findFirst().orElse(null);
    }

    private static boolean hasExactMetricTerm(String question, SchemaElement metric) {
        if (metric == null) {
            return false;
        }
        if (question.contains(metric.getName())) {
            return true;
        }
        return metric.getAlias() != null && metric.getAlias().stream()
                .anyMatch(alias -> question.contains(alias));
    }

    private static String semanticMetricName(SchemaElement metric) {
        String name = metric.getName();
        if (name == null || name.isBlank()) {
            return metric.getBizName();
        }
        if (!name.matches("(?i)CNB\\d{3}") || metric.getBizName() == null
                || metric.getBizName().isBlank()) {
            return name;
        }
        return metric.getBizName();
    }

    private static boolean hasMetricTerm(String question, String term) {
        return term != null && !term.isBlank() && question.contains(term.trim());
    }

    private static SchemaElement findDimension(LLMReq.LLMSchema schema, String... identifiers) {
        if (schema.getDimensions() == null) {
            return null;
        }
        for (SchemaElement dimension : schema.getDimensions()) {
            if (dimension == null) {
                continue;
            }
            for (String identifier : identifiers) {
                if (identifier.equalsIgnoreCase(dimension.getName())
                        || identifier.equalsIgnoreCase(dimension.getBizName())) {
                    return dimension;
                }
            }
        }
        return null;
    }

    private static Set<String> extractDimensionValues(String sql, SchemaElement dimension) {
        Set<String> values = new LinkedHashSet<>();
        List<String> identifiers = new ArrayList<>();
        identifiers.add(dimension.getName());
        identifiers.add(dimension.getBizName());
        for (String identifier : identifiers) {
            if (identifier == null || identifier.isBlank()) {
                continue;
            }
            String field = "(?:`" + Pattern.quote(identifier) + "`|" + Pattern.quote(identifier)
                    + ")";
            Matcher matcher = Pattern.compile("(?i)" + field
                    + "\\s*(?:=\\s*'([^']+)'|IN\\s*\\(([^)]*)\\))").matcher(sql);
            while (matcher.find()) {
                if (matcher.group(1) != null) {
                    values.add(matcher.group(1));
                }
                if (matcher.group(2) != null) {
                    Matcher listValue = Pattern.compile("'([^']+)'").matcher(matcher.group(2));
                    while (listValue.find()) {
                        values.add(listValue.group(1));
                    }
                }
            }
        }
        return values;
    }

    private static boolean containsIdentifier(String sql, String identifier) {
        return identifier != null && !identifier.isBlank()
                && (sql.contains("`" + identifier + "`") || sql.contains(identifier));
    }

    private static Set<String> findAll(Pattern pattern, String text) {
        Set<String> matches = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            matches.add(matcher.group());
        }
        return matches;
    }

    private static String quoteIdentifier(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    /**
     * Extract SQL from model output: prefers JSON {@code sql} field, then fenced code, else raw.
     */
    public static String extractSql(String modelOutput) {
        if (modelOutput == null || modelOutput.isBlank()) {
            return "";
        }
        String text = LlamaCppPrefixChatClient.stripThinking(modelOutput);
        if (text.isBlank()) {
            text = modelOutput.strip();
        } else {
            text = text.strip();
        }
        int sqlKey = indexOfIgnoreCase(text, "\"sql\"");
        if (sqlKey >= 0) {
            int colon = text.indexOf(':', sqlKey);
            if (colon > 0) {
                String fromColon = text.substring(colon + 1).strip();
                if (fromColon.startsWith("\"")) {
                    String extracted = unquoteJsonString(fromColon);
                    if (!extracted.isBlank()) {
                        return extracted.strip();
                    }
                }
            }
        }
        int fence = text.indexOf("```");
        if (fence >= 0) {
            int start = text.indexOf('\n', fence);
            int end = text.indexOf("```", fence + 3);
            if (start > 0 && end > start) {
                String block = text.substring(start + 1, end).strip();
                if (block.regionMatches(true, 0, "sql", 0, 3)) {
                    block = block.substring(3).strip();
                }
                if (!block.isBlank()) {
                    return block;
                }
            }
        }
        return text;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static int indexOfIgnoreCase(String haystack, String needle) {
        return haystack.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT));
    }

    private static String unquoteJsonString(String fromQuote) {
        if (fromQuote == null || fromQuote.isEmpty() || fromQuote.charAt(0) != '"') {
            return "";
        }
        StringBuilder out = new StringBuilder();
        boolean escape = false;
        for (int i = 1; i < fromQuote.length(); i++) {
            char c = fromQuote.charAt(i);
            if (escape) {
                switch (c) {
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'u' -> {
                        if (i + 4 < fromQuote.length()) {
                            String hex = fromQuote.substring(i + 1, i + 5);
                            out.append((char) Integer.parseInt(hex, 16));
                            i += 4;
                        }
                    }
                    default -> out.append(c);
                }
                escape = false;
                continue;
            }
            if (c == '\\') {
                escape = true;
                continue;
            }
            if (c == '"') {
                break;
            }
            out.append(c);
        }
        return out.toString();
    }
}
