package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMReq;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Bank free-SQL (ONE_PASS) prompts split for llama.cpp prefix caching.
 *
 * <p>{@link #FIXED_SYSTEM_PREFIX} (+ frozen schema catalog) is the system message.
 * User turns are question-only via {@link #buildQuestionOnlyUserContent}; never re-embed schema.
 */
public final class BankFreeSqlPromptComposer {

    /**
     * Version tag for logs / ablation. Bump when {@link #FIXED_SYSTEM_PREFIX} text changes.
     * Runtime prefix version also appends a stable-schema fingerprint (see
     * {@link #prefixVersion(String)}).
     */
    public static final String PROMPT_VERSION = "bank-free-sql-sys-v7-fields-examples";

    private static final Pattern FORBIDDEN_LONG_TABLE = Pattern.compile(
            "(?i)(\\b指标\\b\\s*=|\\bmetric_code\\b|\\bmetric_value\\b|\\borg_code\\b|"
                    + "\\b指标值\\b|\\bAS\\s+值\\b|,\\s*值\\b|\\b值\\s+FROM|SELECT\\s+指标\\b|"
                    + "bank_metric_daily|bank_organization\\b)");

    private static final Pattern FORBIDDEN_ZB_AS_COLUMN =
            Pattern.compile("(?i)(`?ZB\\d{3}`?\\s*[*+/]|\\bZB\\d{3}\\b\\s*[*+/]|`ZB\\d{3}`)");

    /**
     * Fixed system prefix (prefix-cached). No per-question placeholders.
     *
     * <p>Emphasizes SuperSonic S2SQL contract: metrics are columns, dimensions are filters — never
     * invent physical long-table columns like 指标/值.
     */
    public static final String FIXED_SYSTEM_PREFIX = """
            你是银行问数 S2SQL 生成器。把中文问题写成语义层可执行的一条 S2SQL。
            先对齐：意图、指标中文名、机构码、时间、排序（不良率/成本收入比/逾期率越小越好→ASC）。
            最终只输出 JSON（无 Markdown）：{"thought":"一句结论","sql":"一条完整S2SQL"}

            ════════════════════════════════
            一、可输出 / 可引用字段（语义层）
            ════════════════════════════════
            【表】以文末【语义目录】Table 为准，常见：银行日指标数据集

            【度量列（SELECT 中写中文名，不要写 ZB 当列名）】
            各项存款余额 —— 总存款规模（ZB001，亿元）
            各项贷款余额 —— 总贷款规模（ZB002，亿元）
            对公存款余额 / 对公存款 —— 对公存款（ZB003）
            个人存款余额 / 个人存款 —— 个人存款（ZB004）
            对公贷款余额 / 对公贷款 —— 对公贷款（ZB005）
            个人贷款余额 / 个人贷款 —— 个人贷款（ZB006）
            中间业务收入（ZB007） 净利息收入（ZB008） 营业收入（ZB009） 营业支出（ZB010）
            净利润（ZB011，万元）
            成本收入比（ZB012，%，越小越好）
            不良贷款率（ZB013，%，越小越好）
            不良贷款余额（ZB014）
            拨备覆盖率（ZB015，%）
            资本充足率（ZB016，%）
            逾期贷款率（ZB017，%，越小越好）
            员工人数（ZB018，人） 网点数量（ZB019，个）
            个人客户数（ZB020） 对公客户数（ZB021）

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

            【样例9 阈值】B市 2025-12-31 拨备覆盖率是否超过150%
            {"thought":"阈值比较","sql":"SELECT `机构`,`拨备覆盖率`, CASE WHEN `拨备覆盖率`>150 THEN '是' ELSE '否' END AS `是否超过150` FROM `银行日指标数据集` WHERE `数据日期`='2025-12-31' AND `机构`='ORG002'"}

            【样例10 全省存款第一】2025-12-31 谁存款最多
            {"thought":"全省排名无机构过滤","sql":"SELECT `机构`,`各项存款余额` FROM `银行日指标数据集` WHERE `数据日期`='2025-12-31' ORDER BY `各项存款余额` DESC LIMIT 1"}
            """.strip();

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
     * User turn for bank free-SQL: natural question + per-request SideInfo/Values only.
     * Schema catalogs and SQL rules stay in {@link #composeSystemPrefix} — never restated here.
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
