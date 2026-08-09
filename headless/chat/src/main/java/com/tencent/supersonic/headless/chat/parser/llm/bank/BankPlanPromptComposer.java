package com.tencent.supersonic.headless.chat.parser.llm.bank;

/**
 * 银行受约束计划提示词（llama.cpp 系统前缀可缓存）。
 *
 * <p>系统提示承载完整契约、字段枚举与样例；用户侧仅自然语言问题，修复时附加 {@code <repair>}。
 */
public final class BankPlanPromptComposer {

    /**
     * 固定系统前缀。任意改动须同步提升 {@link #PREFIX_VERSION} 并重新预热前缀缓存。
     */
    public static final String FIXED_SYSTEM_PREFIX = """
            你是银行问数「查询计划」生成器。只输出一个 JSON 计划对象，不要解释、不要 Markdown、不要 SQL。
            用户消息只有问题；若含 <repair>，根据 error 与 previous_candidate 修正后再输出 JSON。

            {{SEMANTIC_REGISTRY}}

            ════════════════════════════════
            计划填写规则
            ════════════════════════════════
            只能输出注册表中的顶层字段和值；禁止 time_range、zb_id、org_id、sql、sort、period、org_name 等自造字段。
            metrics[].bizName 使用注册表指标代码；aggregation=DEFAULT 表示时点取数，AVG 表示日均/均值，SUM 表示求和。
            organizations[].code 使用注册表机构代码；「全省/各家/哪家」通常用 []，题面指定机构时才填写对应代码。
            time.startDate/endDate 与 baselineStartDate/baselineEndDate 使用 YYYY-MM-DD；非 NONE、非 MOM_AND_YOY 的 comparison 必须给出完整基期。
            RATIO 的 calculation.baseline 填分母指标代码；其他 calculation 通常为 null。
            orderBy.field 只能引用已选指标或维度；低值更优指标的“最好”使用 ASC。
            output.columns 只能包含已选 dimensions、metrics.bizName 以及声明的 derivedMetrics；不得填写结果事实列或中文列名。
            filters 必须遵循注册表 field/operator/value 契约；无过滤时为 []。

            ════════════════════════════════
            七、意图配方（必遵）
            ════════════════════════════════
            POINT_QUERY：单机构+绝对日；calculation=DIRECT；dimensions=[]；comparison=NONE；limit=null
              · 可多指标（列出/含 A、B、C）—— metrics 只收题面点名的指标，勿塞无关码
              · 「对公存款」=ZB003、「个人存款」=ZB004，勿附带各项存款 ZB001
              · 「拨备」未写全称时按拨备覆盖率 ZB015
            CHANGE：calculation=CHANGE
              · 较上年末/和YYYY年末相比：startDate=endDate=当日；comparison=PERIOD_OVER_PERIOD；baseline=年末
              · 环比且同比：comparison=MOM_AND_YOY
              · 较年初：comparison=START_OF_YEAR
            TREND 逐季：dimensions=["bank_data_date"]；granularity=QUARTER；calculation=DIRECT
            RANKING：dimensions 含 bank_organization；orderBy 必填
              · 全省排名/表现较好较差：organizations 填被评价机构，系统会在全省内算 rank
              · 题面给「待评价指标集合：…」时：metrics/derivedMetrics 严格只覆盖集合项
              · 「存贷比」用 derivedMetrics（DERIVED_ZB002_DIV_ZB001, num=ZB002, den=ZB001），
                除非集合里还点名了各项存/贷款，否则不要把 ZB001/ZB002 放进 metrics 单独排名
              · 括号释义「规模（贷款）/质量（不良率）/效益（净利润）」→ 映射括号内业务指标
            AGGREGATION 日均：aggregation=AVG
            RATIO：metrics[0]=分子；calculation.type=RATIO；baseline=分母
            THRESHOLD 与全省均值比（单日、是高还是低/差多少/比怎么样）：
              · intent=THRESHOLD；filters 含 benchmark COMPARE PROVINCE_AVERAGE
              · calculation=DIRECT（不要 COUNT_DAYS_ABOVE_PROVINCE_AVERAGE）
              · COUNT_DAYS 仅当题面是「全年…有多少天高于全省均值」

            ════════════════════════════════
            八、结构骨架（只学字段形状，禁止把下列 JSON 当题库答案背）
            ════════════════════════════════
            说明：下列仅为 intent/calculation/derived 的字段骨架；日期、机构、指标须由当前问题自行推断。
            禁止在提示中复述、禁止在输出中照抄任何评测/训练集原题。

            POINT 单指标：
            {"version":"1.0","intent":"POINT_QUERY","metrics":[{"bizName":"ZB###","aggregation":"DEFAULT"}],"dimensions":[],"organizations":[{"code":"ORG###"}],"time":{"startDate":"YYYY-MM-DD","endDate":"YYYY-MM-DD","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},"filters":[],"calculation":{"type":"DIRECT","baseline":null},"orderBy":[],"limit":null,"output":{"columns":["ZB###"],"orderSensitive":false}}

            POINT 多指标（题面列出的多个 ZB，勿多塞）：
            {"version":"1.0","intent":"POINT_QUERY","metrics":[{"bizName":"ZB###","aggregation":"DEFAULT"},{"bizName":"ZB###","aggregation":"DEFAULT"}],"dimensions":[],"organizations":[{"code":"ORG###"}],"time":{"startDate":"YYYY-MM-DD","endDate":"YYYY-MM-DD","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},"filters":[],"calculation":{"type":"DIRECT","baseline":null},"orderBy":[],"limit":null,"output":{"columns":["ZB###","ZB###"],"orderSensitive":false}}

            CHANGE 较基期：
            {"version":"1.0","intent":"CHANGE","metrics":[{"bizName":"ZB###","aggregation":"DEFAULT"}],"dimensions":[],"organizations":[{"code":"ORG###"}],"time":{"startDate":"YYYY-MM-DD","endDate":"YYYY-MM-DD","granularity":"DAY","comparison":"PERIOD_OVER_PERIOD","baselineStartDate":"YYYY-MM-DD","baselineEndDate":"YYYY-MM-DD"},"filters":[],"calculation":{"type":"CHANGE","baseline":null},"orderBy":[],"limit":null,"output":{"columns":["ZB###"],"orderSensitive":true}}

            CHANGE 环比+同比：
            {"version":"1.0","intent":"CHANGE","metrics":[{"bizName":"ZB###","aggregation":"DEFAULT"}],"dimensions":[],"organizations":[{"code":"ORG###"}],"time":{"startDate":"YYYY-MM-DD","endDate":"YYYY-MM-DD","granularity":"DAY","comparison":"MOM_AND_YOY","baselineStartDate":null,"baselineEndDate":null},"filters":[],"calculation":{"type":"CHANGE","baseline":null},"orderBy":[],"limit":null,"output":{"columns":["ZB###"],"orderSensitive":true}}

            TREND 序列：
            {"version":"1.0","intent":"TREND","metrics":[{"bizName":"ZB###","aggregation":"DEFAULT"}],"dimensions":["bank_data_date"],"organizations":[{"code":"ORG###"}],"time":{"startDate":"YYYY-MM-DD","endDate":"YYYY-MM-DD","granularity":"QUARTER","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},"filters":[],"calculation":{"type":"DIRECT","baseline":null},"orderBy":[{"field":"bank_data_date","direction":"ASC"}],"limit":null,"output":{"columns":["bank_data_date","ZB###"],"orderSensitive":true}}

            RANKING 全省 TopN：
            {"version":"1.0","intent":"RANKING","metrics":[{"bizName":"ZB###","aggregation":"DEFAULT"}],"dimensions":["bank_organization"],"organizations":[],"time":{"startDate":"YYYY-MM-DD","endDate":"YYYY-MM-DD","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},"filters":[],"calculation":{"type":"DIRECT","baseline":null},"orderBy":[{"field":"ZB###","direction":"DESC"}],"limit":3,"output":{"columns":["bank_organization","ZB###"],"orderSensitive":true}}

            RANKING 多指标+存贷比派生（metrics 仅含题面直接指标；存贷比只进 derivedMetrics）：
            {"version":"1.0","intent":"RANKING","metrics":[{"bizName":"ZB###","aggregation":"DEFAULT"}],"derivedMetrics":[{"metricCode":"DERIVED_ZB002_DIV_ZB001","numerator":"ZB002","denominator":"ZB001","name":"存贷比"}],"dimensions":["bank_organization"],"organizations":[{"code":"ORG###"}],"time":{"startDate":"YYYY-MM-DD","endDate":"YYYY-MM-DD","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},"filters":[],"calculation":{"type":"DIRECT","baseline":null},"orderBy":[{"field":"ZB###","direction":"DESC"}],"limit":null,"output":{"columns":["bank_organization","ZB###"],"orderSensitive":true}}

            RATIO：
            {"version":"1.0","intent":"RATIO","metrics":[{"bizName":"ZB###","aggregation":"DEFAULT"}],"dimensions":[],"organizations":[{"code":"ORG###"}],"time":{"startDate":"YYYY-MM-DD","endDate":"YYYY-MM-DD","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},"filters":[],"calculation":{"type":"RATIO","baseline":"ZB###"},"orderBy":[],"limit":null,"output":{"columns":["ZB###"],"orderSensitive":false}}

            THRESHOLD 单日 vs 全省均值（非「多少天」）：
            {"version":"1.0","intent":"THRESHOLD","metrics":[{"bizName":"ZB###","aggregation":"DEFAULT"}],"dimensions":[],"organizations":[{"code":"ORG###"}],"time":{"startDate":"YYYY-MM-DD","endDate":"YYYY-MM-DD","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},"filters":[{"field":"benchmark","operator":"COMPARE","value":"PROVINCE_AVERAGE"}],"calculation":{"type":"DIRECT","baseline":null},"orderBy":[],"limit":null,"output":{"columns":["ZB###"],"orderSensitive":false}}

            AGGREGATION 全年高于全省均值天数：
            {"version":"1.0","intent":"AGGREGATION","metrics":[{"bizName":"ZB###","aggregation":"DEFAULT"}],"dimensions":["bank_organization"],"organizations":[{"code":"ORG###"}],"time":{"startDate":"YYYY-01-01","endDate":"YYYY-12-31","granularity":"DAY","comparison":"NONE","baselineStartDate":null,"baselineEndDate":null},"filters":[{"field":"benchmark","operator":"COMPARE","value":"PROVINCE_AVERAGE"}],"calculation":{"type":"COUNT_DAYS_ABOVE_PROVINCE_AVERAGE","baseline":null},"orderBy":[],"limit":null,"output":{"columns":["bank_organization","ZB###"],"orderSensitive":false}}
            """.replace("{{SEMANTIC_REGISTRY}}", BankSemanticRegistry.promptCatalog()).strip();

    /** 前缀版本：变更 FIXED_SYSTEM_PREFIX 时必须递增。 */
    public static final String PREFIX_VERSION = "bank-plan-sys-v10-semantic-registry";

    private BankPlanPromptComposer() {}

    /** 用户消息：仅自然语言问题。禁止拼 schema / 目录 / 字段枚举。 */
    public static String buildDynamicUserContent(String queryText) {
        if (queryText == null || queryText.isBlank()) {
            throw new IllegalArgumentException("query text is required");
        }
        String user = queryText.strip();
        assertQuestionOnlyUserContent(user, "plan dynamic user");
        return user;
    }

    /**
     * 修复用户消息：问题 + 错误事实 + 上一版 JSON。不在此复述契约 / 目录。
     */
    public static String buildRepairUserContent(String queryText, String previousCandidate,
            String validationMessage) {
        String error = validationMessage == null ? "" : validationMessage.strip();
        // Repair may mention field paths from the validator, but must never re-embed catalogs.
        if (looksLikeCatalogDump(error)) {
            error = "plan validation failed; emit a valid BankQueryPlan JSON only";
        }
        String repair = """
                %s

                <repair>
                <error>%s</error>
                <previous_candidate>
                %s
                </previous_candidate>
                </repair>
                """.formatted(buildDynamicUserContent(queryText), error,
                previousCandidate == null ? "" : previousCandidate.strip()).strip();
        assertQuestionOnlyUserContent(repair, "plan repair user");
        return repair;
    }

    /**
     * Full-stage repair message: the model receives one sanitized tool result and the previous
     * complete plan, then must emit another complete plan instead of a JSON patch or physical SQL.
     */
    public static String buildToolRepairUserContent(String queryText, String previousPlan,
            BankPlanToolResult toolResult) {
        if (toolResult == null || toolResult.getStatus() != BankPlanToolResult.Status.FAILED) {
            throw new IllegalArgumentException("a failed bank plan tool result is required");
        }
        String repair = """
                %s

                <repair>
                <tool_result>
                %s
                </tool_result>
                <previous_plan>
                %s
                </previous_plan>
                <instruction>只根据工具返回的失败阶段、错误码、允许值和修正提示调整计划；必须输出修正后的完整 BankQueryPlan JSON，不要输出补丁、解释或 SQL。</instruction>
                </repair>
                """.formatted(buildDynamicUserContent(queryText), toolResult.toRepairFeedback(),
                previousPlan == null ? "" : previousPlan.strip()).strip();
        assertQuestionOnlyUserContent(repair, "plan tool repair user");
        return repair;
    }

    /**
     * Guardrail: user turns stay question (+ optional &lt;repair&gt;), never field catalogs.
     */
    public static void assertQuestionOnlyUserContent(String userContent, String where) {
        if (looksLikeCatalogDump(userContent)) {
            throw new IllegalArgumentException(
                    "bank plan user content must not contain schema/catalog dumps (" + where + ")");
        }
    }

    static boolean looksLikeCatalogDump(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return text.contains("可填写值目录") || text.contains("【语义目录】")
                || text.contains("Metrics=[") || text.contains("Dimensions=[")
                || text.contains("metrics/*/bizName") || text.contains("/metrics/*/bizName")
                || text.contains("ZB001 各项存款") && text.contains("ORG001 A市");
    }
}
