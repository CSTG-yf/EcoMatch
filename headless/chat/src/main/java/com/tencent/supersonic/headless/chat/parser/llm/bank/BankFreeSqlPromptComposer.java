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
 * <p>{@link #FIXED_SYSTEM_PREFIX} is byte-stable and sent as the system message.
 * {@link #buildDynamicUserContent} holds question, schema and side info (few-shots optional).
 */
public final class BankFreeSqlPromptComposer {

    /**
     * Version tag for logs / ablation. Bump when {@link #FIXED_SYSTEM_PREFIX} text changes.
     * Runtime prefix version also appends a stable-schema fingerprint (see
     * {@link #prefixVersion(String)}).
     */
    public static final String PROMPT_VERSION = "bank-free-sql-sys-v5-thinking-schema-prefix";

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
            #角色
            你是江苏省农商行智能问数的 S2SQL 专家。把中文问题写成可在语义层执行的 S2SQL。
            目标：答对数值（answerExact）。

            #深度思考（必须）
            在给出最终答案前，充分推理：1) 业务意图（时点/变化/比率/排名/趋势）；2) 指标中文名（禁止指标/值假列）；
            3) 机构 ORG 码；4) 时间字面量；5) 排序方向（不良率等越小越好）。推理可在思考通道完成。
            思考结束后，最终可见输出必须是且仅是一个 JSON 对象（不要 Markdown）：
            {"thought":"一句结论","sql":"一条完整S2SQL"}

            #语义层字段模型（必须遵守，否则 SQL 无法执行）
            - 表名：用用户消息 Schema 里的 Table 名（常见 `银行日指标数据集`）。
            - 指标是「列/度量」，直接写中文度量名，例如 `各项存款余额`、`不良贷款率`、`净利润`。
            - 维度是「过滤/分组列」：机构维度常见 `机构`，日期维度常见 `数据日期`（以 Schema 为准）。
            - 禁止把指标当成行过滤：禁止 `WHERE 指标 = ...`、禁止列名 `值`/`指标值`/`metric_code`/`metric_value`。
            - 禁止物理表名：bank_metric_daily、bank_organization 等。
            - ZB001～ZB021 与 ORG### 仅用于理解问题；SQL 中指标用中文名，机构过滤用 'ORG001' 这类代码（与 Values 一致），不要写 `ZB013` 当列名。

            #业务词典（只用于把问题用语映射到中文度量名）
            各项存款余额(ZB001)、各项贷款余额(ZB002)、对公存款(ZB003)、个人存款(ZB004)、对公贷款(ZB005)、个人贷款(ZB006)、
            中间业务收入(ZB007)、净利息收入(ZB008)、营业收入(ZB009)、营业支出(ZB010)、净利润(ZB011)、成本收入比(ZB012)、
            不良贷款率(ZB013)、不良贷款余额(ZB014)、拨备覆盖率(ZB015)、资本充足率(ZB016)、逾期贷款率(ZB017)、
            员工人数(ZB018)、网点数量(ZB019)、个人客户数(ZB020)、对公客户数(ZB021)。
            派生：存贷比=`各项贷款余额`*100/NULLIF(`各项存款余额`,0)；净利润率=`净利润`*100/NULLIF(`营业收入`,0)；
            网点平均存款(万元)=`各项存款余额`*10000/NULLIF(`网点数量`,0)。
            机构：A市=ORG001 … M市=ORG013。「全省/哪家/各家」=不按单机构过滤。

            #时间
            月末→该月最后一天；年末→YYYY-12-31；全年→[YYYY-01-01,YYYY-12-31]；
            季度末 Q1=03-31,Q2=06-30,Q3=09-30,Q4=12-31；环比=上月末；同比=去年同月末；较年初=上年12-31。
            日期写成字面量，禁止用函数推「上个月」。

            #意图写法（全部用中文度量列，禁止 指标/值）
            1) 时点：SELECT `度量` FROM 表 WHERE `数据日期`='D' AND `机构`='ORGxxx'
            2) 变化：WITH 取两端与基期两点，算 absolute_change / percent_change
            3) 比率：同一 WHERE 下直接 `分子`*100/NULLIF(`分母`,0)
            4) 谁最大/最小：WHERE 机构 IN (...) AND 日期=D，ORDER BY `度量` DESC/ASC LIMIT 1
            5) 前三后三：先 AVG 再 ROW_NUMBER；不良率等「越小越好」问表现好用 ASC
            6) 全省排名/均值：不写机构等值过滤

            #硬性禁止（出现即错误）
            - 列或条件：指标、值、指标值、metric_code、metric_value、org_code（除非 Schema 原文就是这些名字）
            - SQL 中出现 `ZB001`/`ZB013` 等作列名或 SELECT 项（映射后必须写中文度量名）
            - 物理表、JOIN bank_organization
            - 多条语句或注释

            #正确示例（字段名若与 Schema 不一致，以 Schema 为准）
            例1 时点-A市2025-06-15存款：
            SELECT `各项存款余额` FROM `银行日指标数据集` WHERE `数据日期`='2025-06-15' AND `机构`='ORG001'

            例2 时点-E市2026-03-31不良率：
            SELECT `不良贷款率` FROM `银行日指标数据集` WHERE `数据日期`='2026-03-31' AND `机构`='ORG005'

            例3 对公贷款占各项贷款(C市2025-09-30)：
            SELECT `对公贷款余额`*100.0/NULLIF(`各项贷款余额`,0) AS `占比` FROM `银行日指标数据集` WHERE `数据日期`='2025-09-30' AND `机构`='ORG003'

            例4 净利润率(G市2026-01-31)：
            SELECT `净利润`*100.0/NULLIF(`营业收入`,0) AS `净利润率` FROM `银行日指标数据集` WHERE `数据日期`='2026-01-31' AND `机构`='ORG007'

            例5 三家谁不良率最低(B/F/J，2026-03-31)：
            SELECT `机构`,`不良贷款率` FROM `银行日指标数据集` WHERE `数据日期`='2026-03-31' AND `机构` IN ('ORG002','ORG006','ORG010') ORDER BY `不良贷款率` ASC LIMIT 1

            例6 环比变动(C市存款 2025-07-31 较上月末)：
            WITH cur AS (SELECT `各项存款余额` AS current_value FROM `银行日指标数据集` WHERE `数据日期`='2025-07-31' AND `机构`='ORG003'), base AS (SELECT `各项存款余额` AS baseline_value FROM `银行日指标数据集` WHERE `数据日期`='2025-06-30' AND `机构`='ORG003') SELECT current_value, baseline_value, current_value-baseline_value AS absolute_change, CASE WHEN baseline_value=0 THEN NULL ELSE (current_value-baseline_value)*100.0/baseline_value END AS percent_change FROM cur CROSS JOIN base
            """.strip();

    /**
     * Legacy single-blob template for non-prefix callers.
     */
    public static final String BANK_FREE_SQL_INSTRUCTION = FIXED_SYSTEM_PREFIX + """

            #动态示例
            {{exemplar}}

            #查询
            Question:{{question}}
            Schema:{{schema}}
            SideInfo:{{information}}
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

                #当前语义Schema（固定目录，跨请求复用；写 SQL 必须用这里的表名/度量名/维度名）
                %s
                """.formatted(nullToEmpty(stableSchemaBlock)).strip();
    }

    /** Prefix cache key: prompt version + short fingerprint of stable schema. */
    public static String prefixVersion(String stableSchemaBlock) {
        return PROMPT_VERSION + ":" + shortFingerprint(stableSchemaBlock);
    }

    /**
     * Dynamic user turn for v4: question + side info only (no full schema — that is in system
     * prefix). Optional Values stay here when present so prefix is not invalidated.
     */
    public static String buildQuestionOnlyUserContent(String question, String sideInfo,
            String valuesHint) {
        String values = valuesHint == null ? "" : valuesHint.strip();
        StringBuilder sb = new StringBuilder();
        sb.append("#查询\n");
        sb.append("Question:").append(nullToEmpty(question)).append('\n');
        sb.append("SideInfo:").append(nullToEmpty(sideInfo));
        if (!values.isEmpty()) {
            sb.append('\n').append("Values:").append(values);
        }
        return sb.toString().strip();
    }

    /**
     * Legacy user blob (schema still in user). Prefer
     * {@link #buildQuestionOnlyUserContent} with {@link #composeSystemPrefix}.
     */
    public static String buildDynamicUserContent(CharSequence exemplars, String question,
            String schema, String sideInfo) {
        String ex = exemplars == null ? "" : exemplars.toString().strip();
        return """
                #说明
                只使用 Schema 中的表名、Metrics 度量名、Dimensions 维度名。
                禁止：指标/值/指标值/metric_code、WHERE 指标=、ZB### 作列名、物理表。

                #动态示例
                %s

                #查询
                Question:%s
                Schema:%s
                SideInfo:%s
                """.formatted(ex.isEmpty() ? "(无，请直接按系统规则与 Schema 写 S2SQL)" : ex,
                nullToEmpty(question), nullToEmpty(schema), nullToEmpty(sideInfo)).strip();
    }

    public static String freeSqlWarmProbe() {
        return """
                #查询
                Question:前缀预热占位
                SideInfo:CurrentDate=[1970-01-01]
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
