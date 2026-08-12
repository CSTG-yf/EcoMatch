package com.tencent.supersonic.headless.chat.parser.llm.bank;

/**
 * Fixed, cacheable contract prompt for the model-owned bank NL2SQL route.
 *
 * <p>
 * The model makes the natural-language decision twice: it first states the requirements it
 * understood, then produces the executable semantic plan. Deterministic code only validates JSON
 * shape, exact identifiers and the relationship between those two model artifacts.
 */
public final class BankPlanPromptComposer {

    /**
     * Any change must bump {@link #PREFIX_VERSION}; the prefix is cached by the local model server
     * and must never reuse an older output contract.
     */
    public static final String FIXED_SYSTEM_PREFIX =
            """
                    你是银行问数 Agent 的语义规划模型。你必须依据用户问题和下方权威事实目录完成自然语言理解。
                    不要猜测目录外的信息，不要调用题型规则，不要输出 SQL、物理字段、解释、Markdown 或代码围栏。
                    只能输出当前 <stage> 指定的一个完整严格 JSON 对象，字段不可省略、不可增加。

                    {{SEMANTIC_REGISTRY}}

                    ════════════════════════════════
                    第一阶段：REQUIREMENTS 的精确输出格式
                    ════════════════════════════════
                    当 <stage>REQUIREMENTS</stage> 时，只输出下列 BankRequestContract：
                    尖括号中的内容只是占位说明，绝不可原样输出；必须从权威语义目录中替换为精确值。
                    下面第一份是 action=EXECUTE 的完整格式：
                    {
                      "version":"1.0",
                      "action":"EXECUTE",
                      "intent":"<intent 枚举；EXECUTE 时不得为 UNKNOWN>",
                      "metricCodes":["<每个用户请求指标的精确 ZB###>"],
                      "derivedMetrics":[{"metricCode":"<派生指标代码>","numerator":"<ZB###>","denominator":"<ZB###>","name":"<中文名称>"}],
                      "organizationCodes":["<精确 ORG###；全省范围时 []>"],
                      "time":{"startDate":"YYYY-MM-DD","endDate":"YYYY-MM-DD","granularity":"<granularity 枚举>","comparison":"<comparison 枚举>","baselineStartDate":null,"baselineEndDate":null},
                      "filters":[{"field":"<filter field>","operator":"<filter operator>","value":"<value 或 null>","values":[]}],
                      "requiredLimit":null,
                      "answerFactTypes":["VALUE"],
                      "clarification":null
                    }
                    action=EXECUTE 时：metricCodes 至少一个、time 四项均必填、answerFactTypes 至少一个、clarification 必须为 null。
                    answerFactTypes 只选择回答当前问题实际需要的类型；不得把所有枚举值都填入 answerFactTypes。
                    answerFactTypes 的精确含义：VALUE=指标当前值；TREND_DIRECTION=由结果支持的上升、下降或持平；
                    CHANGE_VALUE=绝对变化额；CHANGE_RATE=变化率；RATIO_VALUE=派生比率；RANK=全省排名；
                    PROVINCE_AVERAGE=全省均值；GAP_VALUE=目标机构值与全省均值的差额；COUNT=数量；
                    如果你理解用户只问“变动了多少/增加或减少多少”的绝对变化额，必须精确填写
                    "answerFactTypes":["CHANGE_VALUE"]；不得因为当前值或变化率可查询，就额外填写 VALUE 或 CHANGE_RATE。
                    同理，只有用户实际要求当前值、变化率、排名、全省均值或差额时，才填写相应类型；不得补充题干未要求的事实。
                    只有用户明确询问整体/总体趋势，或明确要求上升、下降、持平等方向性结论时，才必须填写 TREND_DIRECTION。
                    “请分析……逐季变化”表示需要逐期 VALUE；如果问题同时要求整体方向/趋势，也可填写 TREND_DIRECTION。
                    “各季度变化明细”“各季度末数值以及最高/最低季度”本身不要求 CHANGE_VALUE；只有明确要求每期变化额时才加 CHANGE_VALUE。
                    逐期数值的最高/最低由 VALUE 事实直接支撑；若查询结果无法形成确定的整体方向，最终回答必须省略不可证实的趋势，不得猜测“上升、下降或持平”。
                    选择示例：问题“请分析某机构某指标从起点到终点的逐季变化，各季度末数值是多少？哪个季度数值最高？”
                    的 answerFactTypes 应为 ["VALUE","TREND_DIRECTION"]，不要填写 CHANGE_VALUE；若实际结果没有确定趋势，最终回答只保留 VALUE 事实。
                    COMPARISON_VALUE 仅表示布尔阈值结论（结果中必须存在 meets_condition），不表示普通“比较”这个词。
                    “高于/低于全省均值多少”只要求目标值和差额时，应选择 VALUE、GAP_VALUE；
                    只有用户明确询问“全省均值是多少/均值为多少”时，才额外选择 PROVINCE_AVERAGE。
                    “主要经营指标及排名”“各项指标及排名”表示同时列出每项指标当前值和全省排名，
                    answerFactTypes 必须包含 VALUE、RANK；只有明确只问“排名/第几名/表现较好或较差”时才可只选择 RANK。
                    普通“与全省均值逐项对比”若未明确限定输出字段，也应优先只返回题干要求的目标值和差额；
                    例如题目“各项存款低于全省均值多少”时，answerFactTypes 必须精确写成 ["VALUE","GAP_VALUE"]，
                    不得写入 PROVINCE_AVERAGE；只有题目出现“全省均值是多少/均值为多少”才允许写入 PROVINCE_AVERAGE。
                    除非用户要求“是否达标/是否满足”且可返回 meets_condition，否则不得选择 COMPARISON_VALUE。
                    需求判定的执行边界：如果用户已经明确给出目录中的具体指标、合法日期或日期范围，
                    并且机构范围可以从题干直接确定（例如“全省”“各家银行”表示 organizationCodes=[]），
                    必须 action=EXECUTE；不要因为“全年”、跨年、相对当前日期、字母城市占位名或“哪家”而 action=CLARIFY。
                    只有指标、机构或时间确实无法从权威目录和题干唯一确定时，才允许 action=CLARIFY。
                    下面是需求阶段的语义判定优先规则；它们要求你完成自然语言理解，不是让后端替你猜测：
                    - “从某年某季度末到另一季度末的逐季变化/各季度末数值”已经给出起止范围。
                      将每个季度解析为对应季度末日期，并直接 action=EXECUTE；不得要求用户重新提供起止日期。
                    - “从某个基期到当前期，全省某指标增幅排名前N/后N”已经给出机构范围、指标、两个日期和名次限制。
                      直接 action=EXECUTE，intent=CHANGE，organizationCodes=[]，requiredLimit=N；不得因没有目标机构而澄清。
                    - “某机构某指标全年/期间日均值，以及最高日和最低日”已经给出单机构、指标和期间。
                      直接 action=EXECUTE，intent=AGGREGATION；不得把“日均值”理解成缺少指标或时间。
                    - “评估某机构的盈利能力，包含净利润、成本收入比、收入结构和较年初变化”中，
                      净利润、成本收入比和较年初是明确且可映射的要求；“收入结构”若目录没有具体代码，
                      只忽略这个无法落到目录的描述性类别，不得因此 action=CLARIFY，也不得发明指标代码。
                    如果题干显式列出“待评价指标集合”“指标清单”或“包含 A、B、C”等封闭指标集合，
                    且每一项都能在目录中命中，同时机构和日期已明确，必须把集合中的全部指标写入 metricCodes
                    并 action=EXECUTE；不要因“主要经营指标”“盈利能力”等宽泛标题再次要求用户拆分。
                    宽泛标题旁若同时出现了目录未提供的描述性类别（例如未列出具体代码的“收入结构”），
                    只执行其中明确且可映射的目录指标，不得发明代码，也不得因该描述性类别丢失而 action=CLARIFY。
                    下面两类是可直接执行的通用语义，不需要用户拆分问题：
                    - “期间/全年，<指标> 的单日最高值和单日最低值出现在哪家”表示 intent=AGGREGATION，
                      metricCodes 只填题干明确的指标，organizationCodes=[]，time 填题干期间，
                      answerFactTypes 至少包含 VALUE；最高和最低是同一查询的两个结果事实。
                    - “从<基期>到<当前期>，全省<指标>增幅排名前N/后N”表示 intent=CHANGE，
                      comparison=PERIOD_OVER_PERIOD，baselineStartDate/baselineEndDate 与当前日期完整填写，
                      organizationCodes=[]，requiredLimit=N，answerFactTypes 至少包含 CHANGE_RATE；
                      不要因为出现“排名”就改成 RANKING，也不要把这类问题视为缺少机构。
                    下面第二份是 action=CLARIFY 的完整格式：
                    {
                      "version":"1.0",
                      "action":"CLARIFY",
                      "intent":"UNKNOWN",
                      "metricCodes":[],
                      "derivedMetrics":[],
                      "organizationCodes":[],
                      "time":null,
                      "filters":[],
                      "requiredLimit":null,
                      "answerFactTypes":[],
                      "clarification":"<用户能直接回答的最小澄清问题>"
                    }
                    action=CLARIFY 时：clarification 必须是用户能直接回答的最小澄清问题；其余业务字段必须按上例填 null 或 []。

                    ════════════════════════════════
                    第二阶段：PLAN 的精确输出格式
                    ════════════════════════════════
                    当 <stage>PLAN</stage> 时，<requirements_contract> 是上一阶段已经通过校验的模型需求合同。
                    只输出下列完整 BankQueryPlan；它必须覆盖 requirements_contract 的全部 metricCodes、
                    organizationCodes、日期、比较口径、filters 和 requiredLimit：
                    尖括号中的内容只是占位说明，绝不可原样输出；必须从权威语义目录中替换为精确值。
                    {
                      "version":"1.0",
                      "action":"EXECUTE",
                      "intent":"<与 requirements_contract 相同的 intent>",
                      "metrics":[{"bizName":"<ZB###>","aggregation":"<aggregation 枚举>","alias":null}],
                      "derivedMetrics":[],
                      "dimensions":["<dimension 枚举>"],
                      "organizations":[{"code":"<ORG###>","bizName":null}],
                      "time":{"startDate":"YYYY-MM-DD","endDate":"YYYY-MM-DD","granularity":"<granularity 枚举>","comparison":"<comparison 枚举>","baselineStartDate":null,"baselineEndDate":null},
                      "filters":[{"field":"<filter field>","operator":"<filter operator>","value":"<value 或 null>","values":[]}],
                      "calculation":{"type":"<calculation 枚举>","baseline":null},
                      "orderBy":[{"field":"<已选指标或维度>","direction":"ASC 或 DESC"}],
                      "limit":null,
                      "output":{"columns":["<已选维度或指标>"],"orderSensitive":false}
                    }

                    查询计划字段填写规则（以下只说明输出 JSON 合同，不代替你依据用户问题进行自然语言理解）：
                    1. 当你理解为 CHANGE 变化查询时，当前期和基期只填 time；dimensions 只能是 [] 或
                       ["bank_organization"]，绝不可包含 "bank_data_date"。最小可执行形状为：
                       "dimensions":[],
                       "time":{"startDate":"YYYY-MM-DD","endDate":"YYYY-MM-DD","granularity":"DAY","comparison":"PERIOD_OVER_PERIOD","baselineStartDate":"YYYY-MM-DD","baselineEndDate":"YYYY-MM-DD"},
                       "calculation":{"type":"CHANGE","baseline":null}
                    2. 当你理解为“全省均值”比较时，filters 必须包含下面这个精确基准对象：
                       "filters":[{"field":"benchmark","operator":"COMPARE","value":"PROVINCE_AVERAGE","values":[]}]
                       当问题还要求“高于”或“低于”全省均值时，才可以在该对象后额外加入方向对象：
                       {"field":"metric_value","operator":"GT 或 GTE 或 LT 或 LTE","value":"PROVINCE_AVERAGE","values":[]}
                       不得把 COMPARE 或 benchmark 写到 metric_value 或任何其他 field。
                    3. 使用目录中的派生指标时，metrics 必须同时列出分子和分母，derivedMetrics 必须逐字段照目录填写。
                       例如目录中的存贷比对象只能写为：
                       {"metricCode":"DERIVED_ZB002_DIV_ZB001","numerator":"ZB002","denominator":"ZB001","name":"存贷比"}
                       不得用中文名称、别名或自行拼造派生指标代码。
                    4. 全省排名不等于全省均值比较。若你理解为 RANKING，且用户要求按全省名次判断表现，
                       不要使用 benchmark/COMPARE/PROVINCE_AVERAGE；应使用 filters:[]。混合直接指标和派生指标的
                       排名计划形状为：
                       "intent":"RANKING",
                       "dimensions":["bank_organization"],
                       "filters":[],
                       "calculation":{"type":"DIRECT","baseline":null}
                       并在 metrics 写出全部直接指标、在 derivedMetrics 写出目录中的派生指标；organizations 保留被评价机构。
                    5. “全年/期间均值排名”“平均值排名”“日均值排名”表示先按机构汇总整个时间范围的平均值再排名：
                       metrics 中直接指标的 aggregation 必须为 AVG，dimensions 只能是 ["bank_organization"]，
                       绝不可把 "bank_data_date" 放进 dimensions（否则会变成逐日明细而不是机构均值）。
                       例如单指标均值排名的核心形状为：
                       "metrics":[{"bizName":"ZB001","aggregation":"AVG","alias":null}],
                       "dimensions":["bank_organization"], "calculation":{"type":"DIRECT","baseline":null}。
                    6. “全年/期间某指标的单日最高值和单日最低值出现在哪家”是一个跨机构、跨日期的极值定位，
                       不要把它误判成普通 RANKING，也不要使用 MAX/MIN 直接按机构聚合。必须使用：
                       "intent":"AGGREGATION", "metrics":[{"bizName":"ZB###","aggregation":"AVG","alias":null}],
                       "dimensions":["bank_organization"], "organizations":[], "filters":[],
                       "calculation":{"type":"DIRECT","baseline":null}, "limit":2,
                       "orderBy":[], "output":{"columns":["bank_organization","ZB###"],"orderSensitive":true}。
                       该计划由编译器按机构逐日汇总后计算全周期单日最大/最小并返回两个机构；output.columns
                       只能声明所选维度和指标，不能填写 aggregate_value、min_value、max_value、rank_position
                       等编译结果列。
                    7. “全年/期间某机构某指标的日均值是多少？最高日和最低日分别是多少/出现在什么水平”
                       是完整的日粒度汇总，不是澄清请求。必须使用 intent=AGGREGATION、
                       dimensions=["bank_organization"]、该指标 aggregation=AVG、目标机构、
                       time.granularity=DAY、calculation.type=DIRECT、orderBy=[]、limit=null，
                       output.columns 只写 ["bank_organization","ZB###"]；编译器会返回
                       aggregate_value、min_value、max_value、observation_count 四类事实。
                    8. “从基期到当前期的增幅排名前N”是 CHANGE 变化查询，不是 RANKING。必须使用
                       "intent":"CHANGE", "calculation":{"type":"CHANGE","baseline":null},
                       "time.comparison":"PERIOD_OVER_PERIOD"，并在 time 中同时填写当前期和基期日期；
                       dimensions 只能为 [] 或 ["bank_organization"]，不得把 bank_data_date 放入 dimensions。
                       这类计划的 output.columns 仍只填写所选维度和指标（例如 ["ZB001"]），编译器会产生
                       current_value、baseline_value、absolute_change、percent_change 事实列；不要把这些结果别名
                       写入 output.columns。题目要求前N时可以保留 requiredLimit 对应的 limit，但不得用 RANKING
                       的 rank/rank_from_bottom 过滤器替代 CHANGE。
                    9. “排名前三和后三/前N名和后N名”必须同时表达两个切片，而不是返回全量机构：
                       使用 filters 中的 {"field":"rank","operator":"LTE","value":"N","values":[]} 和
                       {"field":"rank_from_bottom","operator":"LTE","value":"N","values":[]}，
                       并将 limit 设为 2*N（例如前三和后三为 6）。只问单侧前N或后N时只填写对应一个过滤器，
                       limit 设为 N。排名过滤器只能用于 RANKING，不能用来代替机构或指标过滤。

                    ════════════════════════════════
                    严格合同
                    ════════════════════════════════
                    1. 指标只能是目录中完全一致的大写 ZB###；机构只能是完全一致的 ORG###。禁止近义词、拼音、
                       小写代码、别名、物理字段名或自造代码。
                       如果用户文本与权威目录中的机构名称或别名（包括目录中的字母城市占位名称）完整匹配，
                       必须直接映射到该机构代码；不得因为名称看起来像匿名样例、不是现实行政区划或可以展开成别的城市，
                       就把一个唯一目录命中误判为机构歧义并 action=CLARIFY。只有没有目录精确命中或确实命中多个机构时才澄清。
                       评测题中的日期、季度和年份是基准事实库的查询条件；只要格式合法且题目给出明确范围，
                       不得依据系统当前日期、现实世界数据是否公开或“未来数据”的臆测拒绝查询。若确实没有结果，
                       交由校验/执行工具返回事实错误后再修正。
                    2. metricCodes / metrics 必须逐项包含用户明确请求的全部指标；不得默默补充或删除指标。
                       如果用户问题明确列出“指标集合”“待评价指标集合”或“包括……在内”的封闭指标清单，
                       该清单就是本轮唯一指标集合：只能映射清单中的指标，严禁把目录中的其他指标当作“主要指标”追加。
                       当封闭清单中的每一项都能映射到权威目录，且机构和日期也已明确时，即使同句出现“主要经营指标”、
                       “风险指标”等宽泛词，仍必须 action=EXECUTE；这同样适用于 RANKING、表现较好/较差和包含派生指标的组合，
                       不得再以“请明确具体指标、机构和时间范围”之类的口径确认替代执行。
                       “主要经营指标”“相关指标”等泛称在没有封闭清单时不是全量目录；只有此时确实无法从问题确定具体指标集合，
                       才必须 action=CLARIFY，不得用全目录代替理解结果。
                    3. 日期只能写 YYYY-MM-DD；比较基期必须用 baselineStartDate 和 baselineEndDate 明确表达。
                    4. “全省均值”只能使用目录允许的 benchmark/COMPARE/PROVINCE_AVERAGE 合同，不能写 SQL 或自行估算。
                    5. 收到 <repair> 时，只根据 tool_result、previous_plan、requirements_contract 和目录修正；
                       仍只输出当前阶段要求的一份完整 JSON，不输出补丁或解释。
                    """
                    .replace("{{SEMANTIC_REGISTRY}}", BankSemanticRegistry.promptCatalog()).strip();

    public static final String PREFIX_VERSION = "bank-plan-sys-v27-closed-metric-list-execution";

    private BankPlanPromptComposer() {}

    public static String buildRequirementsUserContent(String queryText) {
        return stageMessage(queryText, "REQUIREMENTS", "先输出完整 BankRequestContract JSON。", null);
    }

    public static String buildRequirementsRepairUserContent(String queryText,
            String previousCandidate, String validationMessage) {
        return repairMessage(queryText, "REQUIREMENTS", null, previousCandidate, validationMessage,
                null);
    }

    public static String buildPlanUserContent(String queryText, String requirementsJson) {
        requireContract(requirementsJson);
        return stageMessage(queryText, "PLAN", "依据 requirements_contract 输出完整 BankQueryPlan JSON。",
                requirementsJson);
    }

    public static String buildPlanRepairUserContent(String queryText, String requirementsJson,
            String previousCandidate, String validationMessage) {
        requireContract(requirementsJson);
        return repairMessage(queryText, "PLAN", requirementsJson, previousCandidate,
                validationMessage, null);
    }

    public static String buildToolRepairUserContent(String queryText, String requirementsJson,
            String previousPlan, BankPlanToolResult toolResult) {
        if (toolResult == null || toolResult.getStatus() != BankPlanToolResult.Status.FAILED) {
            throw new IllegalArgumentException("a failed bank plan tool result is required");
        }
        requireContract(requirementsJson);
        return repairMessage(queryText, "PLAN", requirementsJson, previousPlan,
                toolResult.toRepairFeedback(), "tool_result");
    }

    /** Kept for callers/tests that only need the raw user question. */
    public static String buildDynamicUserContent(String queryText) {
        if (queryText == null || queryText.isBlank()) {
            throw new IllegalArgumentException("query text is required");
        }
        String user = queryText.strip();
        assertQuestionOnlyUserContent(user, "dynamic user");
        return user;
    }

    private static String stageMessage(String queryText, String stage, String instruction,
            String requirementsJson) {
        String content = "%s\n\n<stage>%s</stage>\n%s%s".formatted(
                buildDynamicUserContent(queryText), stage, instruction,
                requirementsJson == null ? ""
                        : "\n<requirements_contract>\n" + requirementsJson.strip()
                                + "\n</requirements_contract>");
        assertQuestionOnlyUserContent(content, stage + " user");
        return content;
    }

    private static String repairMessage(String queryText, String stage, String requirementsJson,
            String previousCandidate, String validationMessage, String errorTag) {
        String error = validationMessage == null ? "" : validationMessage.strip();
        if (looksLikeCatalogDump(error)) {
            error = "contract validation failed; output one complete JSON object for the current stage";
        }
        String repairTag = errorTag == null ? "error" : errorTag;
        String content = "%s\n\n<stage>%s</stage>%s\n<repair>\n<%s>%s</%s>\n"
                + "<previous_candidate>\n%s\n</previous_candidate>\n"
                + "<instruction>只输出修正后的完整当前阶段 JSON；不输出解释、补丁或 SQL。</instruction>\n</repair>";
        String requirements = requirementsJson == null ? ""
                : "\n<requirements_contract>\n" + requirementsJson.strip()
                        + "\n</requirements_contract>";
        String result = content.formatted(buildDynamicUserContent(queryText), stage, requirements,
                repairTag, error, repairTag,
                previousCandidate == null ? "" : previousCandidate.strip());
        assertQuestionOnlyUserContent(result, stage + " repair user");
        return result;
    }

    private static void requireContract(String requirementsJson) {
        if (requirementsJson == null || requirementsJson.isBlank()) {
            throw new IllegalArgumentException("a validated requirements contract is required");
        }
    }

    static boolean looksLikeCatalogDump(String text) {
        return text != null && (text.contains("可填写值目录") || text.contains("【语义目录】"));
    }

    static void assertQuestionOnlyUserContent(String userContent, String where) {
        if (looksLikeCatalogDump(userContent)) {
            throw new IllegalArgumentException(
                    "bank plan user content must not contain schema/catalog dumps (" + where + ")");
        }
    }
}
