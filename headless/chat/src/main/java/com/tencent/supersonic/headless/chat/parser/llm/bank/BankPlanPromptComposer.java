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

            ════════════════════════════════
            一、可输出顶层字段（只能用这些）
            ════════════════════════════════
            version（字符串，固定 "1.0"）—— 计划版本
            intent（枚举）—— 查询意图，取值见下
            metrics（数组）—— 要用的指标列表
            dimensions（数组）—— 分组/序列维度，只能是 bank_organization 或 bank_data_date 或 []
            organizations（数组）—— 机构过滤，元素为 {"code":"ORG###"}
            time（对象）—— 查询时间窗与对比基期
            filters（数组）—— 附加过滤，无则 []
            calculation（对象）—— 计算方式
            orderBy（数组）—— 排序，无则 []
            limit（整数或 null）—— TopN；非排名题用 null
            output（对象）—— 输出列声明
            可选：derivedMetrics（派生指标）、action（EXECUTE|CLARIFY，默认 EXECUTE）
            禁止：time_range、zb_id、org_id、sql、sort、period、org_name 等自造字段

            ════════════════════════════════
            二、intent 枚举与含义
            ════════════════════════════════
            POINT_QUERY —— 单点查询（某机构某日某指标是多少）
            COMPARISON —— 机构间对比
            RANKING —— 排名/前N/后N
            TREND —— 时间序列趋势（逐日/逐月/逐季）
            CHANGE —— 相对变化（较上月末/上年末/年初/同比环比）
            RATIO —— 比率（分子/分母）
            THRESHOLD —— 阈值判断（是否超过某值）
            AGGREGATION —— 聚合（日均、合计等）

            ════════════════════════════════
            三、嵌套字段详表
            ════════════════════════════════
            metrics[]：
              bizName —— 指标代码，必须是 ZB###（见指标表）
              aggregation —— DEFAULT|SUM|AVG|MAX|MIN|COUNT
                · DEFAULT：时点取数；AVG：日均/均值；SUM：求和
              alias —— 可选别名

            organizations[]：
              code —— 机构代码 ORG001～ORG013
              bizName —— 可选，一般不填

            time：
              startDate/endDate —— 查询期起止，格式 YYYY-MM-DD
              granularity —— DAY|MONTH|QUARTER|YEAR|RANGE（时间粒度）
              comparison —— 对比类型：
                NONE —— 无对比
                PERIOD_OVER_PERIOD —— 相对另一绝对日期（如较上年末）
                YEAR_OVER_YEAR —— 同比
                START_OF_YEAR —— 较年初
                MOM_AND_YOY —— 同时要环比和同比
              baselineStartDate/baselineEndDate —— 基期；comparison 为 PERIOD_OVER_PERIOD 等时必填（MOM_AND_YOY 可 null）

            calculation：
              type —— DIRECT（直接取数）| CHANGE（变化）| RATIO（比率）| COUNT_DAYS_ABOVE_PROVINCE_AVERAGE（高于全省均值天数）
              baseline —— RATIO 时填分母指标代码 ZB###，否则 null

            orderBy[]：
              field —— 排序字段（ZB### 或维度名）
              direction —— ASC|DESC（不良率/成本收入比/逾期率「越好」用 ASC）

            output：
              columns —— 输出列，只能是：已选 dimensions + metrics.bizName（及 derived 码），禁止中文
              orderSensitive —— 是否关心行序（排名/前后三为 true）

            filters[]：
              field / operator / value —— 如阈值题 field=metric_value

            ════════════════════════════════
            四、指标代码全集（bizName → 含义）
            ════════════════════════════════
            ZB001 各项存款余额（亿元）
            ZB002 各项贷款余额（亿元）
            ZB003 对公存款余额（亿元）
            ZB004 个人存款余额（亿元）
            ZB005 对公贷款余额（亿元）
            ZB006 个人贷款余额（亿元）
            ZB007 中间业务收入（亿元）
            ZB008 净利息收入（亿元）
            ZB009 营业收入（亿元）
            ZB010 营业支出（亿元）
            ZB011 净利润（亿元）
            ZB012 成本收入比（%）
            ZB013 不良贷款率（%，越小越好）
            ZB014 不良贷款余额（亿元）
            ZB015 拨备覆盖率（%）
            ZB016 资本充足率（%）
            ZB017 逾期贷款率（%，越小越好）
            ZB018 员工人数（人）
            ZB019 网点数量（个）
            ZB020 个人客户数（户）
            ZB021 对公客户数（户）
            派生（仅理解）：存贷比≈ZB002/ZB001；净利润率≈ZB011/ZB009

            ════════════════════════════════
            五、机构代码全集（code → 含义）
            ════════════════════════════════
            ORG001 A市农商行  ORG002 B市农商行  ORG003 C市农商行  ORG004 D市农商行
            ORG005 E市农商行  ORG006 F市农商行  ORG007 G市农商行  ORG008 H市农商行
            ORG009 I市农商行  ORG010 J市农商行  ORG011 K市农商行  ORG012 L市农商行
            ORG013 M市农商行
            「全省/各家/哪家」→ organizations 常为 [] 或多人，不要只绑一家（除非题面指定）

            ════════════════════════════════
            六、维度与 output.columns 可用取值
            ════════════════════════════════
            bank_organization —— 机构维度（排名/分机构输出时用）
            bank_data_date —— 数据日期维度（趋势/逐日序列时用）
            ZB001～ZB021 —— 与 metrics 中已选指标一致
            禁止：中文列名、deposit_balance、period、org_name 等

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
            """.strip();

    /** 前缀版本：变更 FIXED_SYSTEM_PREFIX 时必须递增。 */
    public static final String PREFIX_VERSION = "bank-plan-sys-v9-abstract-skeletons";

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
