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
                    “从某季度末到另一季度末的逐季变化”必须使用 intent=TREND，保留完整起止日期，
                    time.comparison=NONE，dimensions 必须包含 "bank_data_date"，calculation.type=DIRECT；
                    不得压缩为起点与终点的 CHANGE、同比或环比比较。
                    “各季度变化明细”“各季度末数值以及最高/最低季度”本身不要求 CHANGE_VALUE；只有明确要求每期变化额时才加 CHANGE_VALUE。
                    逐期数值的最高/最低由 VALUE 事实直接支撑；若查询结果无法形成确定的整体方向，最终回答必须省略不可证实的趋势，不得猜测“上升、下降或持平”。
                    “最高/最低、最高日/最低日”不是 answerFactTypes 枚举；它们只能填写 VALUE，
                    由查询结果中的 min_value、max_value 事实支持。禁止填写 MINIMUM_VALUE、MAXIMUM_VALUE。
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
                    机构间普通比较与局部排名必须严格区分：
                    - “机构A比机构B多多少/少多少/相差多少”表示两个明确机构在同一日期的数值差，
                      REQUIREMENTS 和 PLAN 都必须使用 intent=COMPARISON、time.comparison=NONE、
                      dimensions=["bank_organization"]、calculation.type=DIRECT，保留两个机构和一个指标；
                      answerFactTypes 必须包含 VALUE、GAP_VALUE。不得把它退化为 POINT_QUERY 或 AGGREGATION，
                      也不得使用 COMPARISON_VALUE；差额由结果事实 value_difference 支撑。
                    - “机构A、机构B、机构C三家谁/哪家某指标最多/最少/最高/最低”表示所列机构集合内的局部排名，
                      REQUIREMENTS 和 PLAN 都必须使用 intent=RANKING，保留题干列出的全部机构、单一指标、
                      dimensions=["bank_organization"]，并只在题干明确要求名次时加入 RANK；只问“谁最多/最少”
                      时至少填写 VALUE。不得把“谁最多”理解为 COMPARISON，也不得因为机构不是全省范围而澄清。
                    需求判定的执行边界：如果用户已经明确给出目录中的具体指标、合法日期或日期范围，
                    并且机构范围可以从题干直接确定（例如“全省”“各家银行”表示 organizationCodes=[]），
                    必须 action=EXECUTE；不要因为“全年”、跨年、相对当前日期、字母城市占位名或“哪家”而 action=CLARIFY。
                    只有指标、机构或时间确实无法从权威目录和题干唯一确定时，才允许 action=CLARIFY。
                    “某机构在YYYY-MM-DD的存贷比是多少”已经明确给出唯一机构、完整日期和目录派生指标，
                    必须 action=EXECUTE 且 intent=RATIO：metricCodes=["ZB002","ZB001"]，derivedMetrics 只填写目录中的
                    DERIVED_ZB002_DIV_ZB001，time 使用该日且 granularity=DAY，answerFactTypes=["RATIO_VALUE"]。
                    这类问题禁止要求用户再次提供银行、指标或时间，也禁止返回通用澄清文案。
                    “某机构某日的存款中，对公和个人分别占比多少”是存款结构双分项查询：必须
                    action=EXECUTE、intent=POINT_QUERY，metricCodes 严格按 ["ZB003","ZB004","ZB001"] 填写，
                    derivedMetrics=[]，answerFactTypes=["VALUE","RATIO_VALUE"]。ZB001 只作为共同分母；结果必须
                    分别返回 ZB003、ZB004，不得缩成单个 ZB003/ZB001 比率。
                    “某机构某日的人均利润/人均净利润”是目录派生指标：必须 action=EXECUTE、intent=RATIO，
                    metricCodes=["ZB011","ZB018"]，derivedMetrics 逐字段填写目录中的
                    DERIVED_ZB011_DIV_ZB018，answerFactTypes=["RATIO_VALUE"]；人均利润=净利润/员工人数，
                    单位为万元/人，不乘以100。
                    最高优先级执行合同：用户显式枚举了非空的封闭指标清单，并同时给出唯一机构和明确
                    日期/日期范围时，必须将清单逐项映射为 metricCodes/derivedMetrics 并 action=EXECUTE。
                    标题、分类名称、排名或表现判定说明只能约束该封闭清单的组织和输出方式，不能成为
                    CLARIFY 的理由，也不能触发目录指标扩展。每个输出代码必须能回指清单中的原始短语，
                    或是清单中派生指标明确声明的分子/分母；否则必须删除。
                    对“全年/期间均值排名”“平均值排名”，底层事实按日存储，time.granularity 必须为 DAY，
                    以完整日期范围计算 AVG；不要使用 MONTH 产生 yyyy-MM 字符串日期条件。
                    下面是需求阶段的语义判定优先规则；它们要求你完成自然语言理解，不是让后端替你猜测：
                    - “从某年某季度末到另一季度末的逐季变化/各季度末数值”已经给出起止范围。
                      将每个季度解析为对应季度末日期，并直接 action=EXECUTE、intent=TREND；time.comparison=NONE，
                      baselineStartDate/baselineEndDate=null；不得要求用户重新提供起止日期，也不得改写为 CHANGE。
                    - “从某个基期到当前期，全省某指标增幅排名前N/后N”已经给出机构范围、指标、两个日期和名次限制。
                      直接 action=EXECUTE，intent=CHANGE，organizationCodes=[]，requiredLimit=N；不得因没有目标机构而澄清。
                    - 只要 time.comparison 不是 NONE（包括同比、环比、较年初或两个明确时点的变动），
                      REQUIREMENTS 的 intent 必须为 CHANGE；“评估”“分析”“结构”等业务标题不改变这一执行意图。
                    - “某机构某指标全年/期间日均值，以及最高日和最低日”已经给出单机构、指标和期间。
                      直接 action=EXECUTE，intent=AGGREGATION；不得把“日均值”理解成缺少指标或时间。
                      如果只问日均值，PLAN 的 output.aggregationMode="AVERAGE_ONLY"；如果还明确询问最高值、
                      最低值或相应水平，则 output.aggregationMode="WITH_EXTREMA"。其他查询填 null。
                    - “某机构某指标在某日与全省均值比，是高还是低/差多少”已经给出唯一机构、指标、日期和
                      比较口径，必须 action=EXECUTE，intent=AGGREGATION，并填写 benchmark=COMPARE/PROVINCE_AVERAGE；
                      不得用泛化澄清替代完整的省均值比较计划。
                    - “上季度末/上季度末相比”必须按自然季度末解析：当前日期所在季度的上一季度末，
                      例如当前期 2025-10-31 的上季度末是 2025-09-30，不是简单减三个月得到 2025-07-31。
                      该语义使用 intent=CHANGE、comparison=PERIOD_OVER_PERIOD，并将 baselineStartDate 和
                      baselineEndDate 都设为该自然季度末。
                    - “收入结构”是权威目录定义的复合业务语义：必须同时选择中间业务收入（ZB007）和
                      净利息收入（ZB008）。它不是无代码的描述性类别；用户明确请求收入结构时，不得遗漏
                      任一项、不得因此 action=CLARIFY，也不得扩展为营业收入、营业支出或目录中的其他指标。
                    - “某机构某日的逾期贷款率比不良贷款率高/低/相差多少”已经给出两个目录指标、机构和日期，
                      必须 action=EXECUTE、intent=POINT_QUERY，metricCodes=["ZB013","ZB017"]，
                      answerFactTypes=["VALUE","GAP_VALUE"]；这是同一时点两个基础指标的绝对差值，
                      PLAN 必须 calculation.type=DIRECT，不得使用 calculation.type=RATIO 或 CHANGE；
                      不得再次澄清，也不得生成自由差额 SQL。
                    - 题干明确问“某机构某指标是多少、全省排第几”时必须 intent=RANKING，
                      answerFactTypes=["VALUE","RANK"]；机构保留为目标机构，但编译器会在全省总体上先排名。
                    如果题干显式列出非空的封闭指标集合，
                    且每一项都能在目录中命中，同时机构和日期已明确，必须把集合中的全部指标写入 metricCodes
                    并 action=EXECUTE；不要因“主要经营指标”“盈利能力”等宽泛标题再次要求用户拆分。
                    分类标签与其右侧指标列表构成封闭映射时，metricCodes 只能来自右侧所列指标
                    （及其中明确的目录派生指标），即使同一句出现宽泛标题也不得扩展到全目录。
                    当映射右侧包含派生指标（例如“存贷比”）时，只添加该派生指标所需的分子、分母和派生项；
                    不得因为“各项指标”或维度名称擅自加入映射之外的目录指标。
                    封闭映射解析算法（优先级高于任何分类常识）：只读取每个分类标签右侧明确列出的
                    指标短语；逐一在权威目录中映射这些短语，得到的
                    直接 ZB### 代码并集就是唯一 metricCodes 集合。若右侧短语命中目录派生指标，metricCodes
                    还必须加入其明确的分子、分母，derivedMetrics 加入该派生项。左侧的“规模/资产质量/盈利能力”
                    只是分组标签，没有任何默认指标含义；“各项指标”也只能指右侧集合。输出前逐项自检：
                    每个 metricCodes 都能指出对应的右侧短语或派生项依赖，找不到对应短语的代码必须删除，
                    不得按维度、业务常识或“主要指标”补全目录。
                    宽泛标题旁若同时出现了目录未提供、且无法映射为任何目录指标的描述性类别，
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
                      "output":{"columns":["<已选维度或指标>"],"orderSensitive":false,"aggregationMode":null}
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
                       当问题询问“有多少家农商行高于/低于全省均值”时，这是逐机构阈值计数：
                       REQUIREMENTS 与 PLAN 都必须 intent=THRESHOLD，REQUIREMENTS 的 organizationCodes=[]，
                       PLAN 的 organizations=[]，dimensions 必须包含
                       "bank_organization"，calculation.type=DIRECT，answerFactTypes 必须包含 COUNT；filters 必须同时
                       保留上述 benchmark 对象和对应 GT/GTE/LT/LTE 方向对象。查询结果必须保留 meets_condition，
                       再据此计数；不得把它改写为单机构省均值比较或普通排名。
                    2a. 绝对阈值判断的精确计划合同：当问题是“某机构某日某指标是否超过/低于某个明确数值、
                       是否达标”且没有“全省均值”基准时，REQUIREMENTS 与 PLAN 的 intent 都必须为 THRESHOLD，
                       即 JSON 中必须精确写入 "intent":"THRESHOLD"。
                       metricCodes/metrics 只选择该一个指标，organizationCodes/organizations 只选择该一个机构，
                       time 使用题目明确日期且 comparison=NONE。PLAN 的 dimensions 必须精确为 ["bank_organization"]，
                       filters 必须精确为
                       [{"field":"metric_value","operator":"GT 或 GTE 或 LT 或 LTE 或 EQ","value":"<题目阈值>","values":[]}]
                       （operator 必须替换为其中一个枚举，value 可写数字或百分数字符串），calculation.type=DIRECT，
                       orderBy=[]、limit=null，output.columns 必须精确为 ["bank_organization","<所选 ZB###>"]。
                       answerFactTypes 应包含 VALUE 和 COMPARISON_VALUE。
                       不得省略机构维度，不得把阈值字段写成指标代码，也不得加入 benchmark 或其他指标。
                    3. REQUIREMENTS 使用目录中的派生指标时，metricCodes 必须同时列出分子和分母，
                       derivedMetrics 必须逐字段照目录填写。PLAN 对普通派生指标仍遵循相同规则；但“某机构某日
                       的存贷比/两个基础指标之比”属于确定性点值比率例外：PLAN 的 metrics 必须按“分子、分母”
                       顺序列出两个基础指标，derivedMetrics=[]，calculation.type=RATIO，calculation.baseline
                       必须是分母代码，orderBy=[]、limit=null。若 requirements_contract.organizationCodes 非空，
                       organizations 必须逐项保留这些机构，dimensions 必须包含 bank_organization，output.columns
                       也必须先包含 bank_organization 再列两个基础指标；只有全省范围 organizationCodes=[] 时，
                       organizations 才能为 []，且不得凭空添加目标机构。
                       不得把点值比率写成 DIRECT，也不得在 PLAN 重复派生项；编译器会计算 RATIO_VALUE。
                       例如目录中的存贷比对象只能写为：
                       {"metricCode":"DERIVED_ZB002_DIV_ZB001","numerator":"ZB002","denominator":"ZB001","name":"存贷比"}
                       不得用中文名称、别名或自行拼造派生指标代码。
                       人均利润的 PLAN 使用同一受控点值比率形状：metrics 按 ZB011、ZB018 排列，
                       derivedMetrics=[]，calculation.type=RATIO、baseline="ZB018"，dimensions 包含
                       bank_organization，output.columns=["bank_organization","ZB011","ZB018"]。
                       存款结构双分项不使用 RATIO 计划：metrics 按 ZB003、ZB004、ZB001 排列，
                       calculation.type=DIRECT，output.orderSensitive=true；投影器会以 ZB001 为共同分母。
                       同一时点两个基础指标的绝对差值也不使用 RATIO 或 CHANGE：metrics 保留题干中的两个指标，
                       intent=POINT_QUERY、calculation.type=DIRECT，answerFactTypes 使用 VALUE、GAP_VALUE；
                       不得使用 calculation.type=RATIO，也不得将其中一个指标当成基期。
                    3a. 逐季序列的精确计划合同：intent=TREND，time 保留完整起止日期、granularity=DAY、
                        comparison=NONE、baselineStartDate/baselineEndDate=null；dimensions 必须包含 "bank_data_date"，
                        calculation.type=DIRECT，orderBy 按 bank_data_date ASC，output.columns 包含日期和所选指标。
                        不得压缩为起点与终点的 CHANGE，也不得只返回两个端点。
                    3b. “从某个明确期末到另一个明确期末，多个指标的变动方向分别是什么”是端点变化，
                        不是逐日趋势序列。REQUIREMENTS 必须 intent=CHANGE、comparison=PERIOD_OVER_PERIOD，
                        当前端点写 startDate=endDate，较早端点写 baselineStartDate=baselineEndDate；PLAN 必须
                        calculation.type=CHANGE，dimensions=["bank_organization"]，保留全部明示指标。
                    3c. “不良贷款余额占贷款总额的比重/比例”是点值比率：REQUIREMENTS 必须 intent=RATIO、
                        metricCodes=["ZB014","ZB002"]、derivedMetrics=[]、answerFactTypes=["RATIO_VALUE"]；
                        PLAN 必须 metrics 按 ZB014、ZB002 排列，calculation.type=RATIO、baseline="ZB002"，
                        dimensions=["bank_organization"]，不得退化为两个基础指标的 DIRECT 查询。
                    3d. 多个明确机构“加起来/合计/总和”的查询，PLAN 必须保留所有 organizations，并使用
                        dimensions=["bank_organization"]、output.columns 先保留 bank_organization；查询结果逐机构
                        返回可核验加数，由结果事实层计算总和，不得提前汇成一个失去机构身份的匿名标量。
                    4. 全省排名不等于全省均值比较。若你理解为 RANKING，且用户要求按全省名次判断表现，
                       不要使用 benchmark/COMPARE/PROVINCE_AVERAGE；应使用 filters:[]。混合直接指标和派生指标的
                       排名计划形状为：
                       "intent":"RANKING",
                       "dimensions":["bank_organization"],
                       "filters":[],
                       "calculation":{"type":"DIRECT","baseline":null}
                       并在 metrics 写出全部直接指标、在 derivedMetrics 写出目录中的派生指标；organizations 保留被评价机构。
                       “某机构在全省13家里排第几”必须 organizations 保留该机构、limit=13，orderBy 使用该指标
                       的目录排名方向，output.columns=["bank_organization","ZB###"]；不得在修复时删除目标机构。
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
                       写入 output.columns 或 orderBy。CHANGE 的结果排序由编译器负责，因此 orderBy 必须为 []；
                       题目要求前N时可以保留 requiredLimit 对应的 limit。题目询问全省/全部机构前N或后N时，
                       dimensions 必须为 ["bank_organization"]，以便返回每家机构的变化事实；不得用 RANKING
                       的 rank/rank_from_bottom 过滤器替代 CHANGE。
                    9. “排名前三和后三/前N名和后N名”必须同时表达两个切片，而不是返回全量机构：
                       使用 filters 中的 {"field":"rank","operator":"LTE","value":"N","values":[]} 和
                       {"field":"rank_from_bottom","operator":"LTE","value":"N","values":[]}，
                       并将 limit 设为 2*N（例如前三和后三为 6）。只问单侧前N或后N时只填写对应一个过滤器，
                       limit 设为 N。排名过滤器只能用于 RANKING，不能用来代替机构或指标过滤。
                       “排最后一名/倒数第一”就是后1名：只填写 rank_from_bottom 过滤器，必须
                       operator=LTE、value="1"、values=[]，并令 limit=1；不得使用 GTE、EQ 或总机构数。
                     10. “某机构某指标在一段期间有多少天高于全省均值”是逐日比较后计数，不是把全期
                       聚合成一个值再比较。REQUIREMENTS 必须使用 intent=AGGREGATION、单一机构、单一指标、
                       granularity=DAY、comparison=NONE、answerFactTypes=["COUNT"]，并包含精确的
                       benchmark/COMPARE/PROVINCE_AVERAGE filter。PLAN 必须保留上述范围，使用
                       dimensions=["bank_organization"]、calculation.type=COUNT_DAYS_ABOVE_PROVINCE_AVERAGE、
                       orderBy=[]、limit=null，output.columns 只写 ["bank_organization","ZB###"]；
                        编译器会返回 DAYS_ABOVE_AVERAGE、TOTAL_COUNT 和 RATIO_VALUE 对应的事实列。
                        不得先对全年求和或平均后只比较一次，也不得用 COMPARISON_VALUE 代替逐日计数。
                     10a. “某机构某指标全年/期间有多少天高于全省均值”是评测事实库中的日粒度计数：
                         REQUIREMENTS 与 PLAN 必须 action=EXECUTE、intent=AGGREGATION，保留该机构、该指标和
                         完整日期范围，filters 必须包含 benchmark/COMPARE/PROVINCE_AVERAGE，PLAN 的
                         calculation.type=COUNT_DAYS_ABOVE_PROVINCE_AVERAGE、dimensions=["bank_organization"]、
                         answerFactTypes 至少包含 COUNT。不要因为指标通常按季度披露、题目年份晚于当前日期、
                         或机构名称使用字母城市占位符而澄清；这些都是本权威评测目录中的有效槽位。
                     11. “某机构某指标在明确日期与全省均值比较”属于已确定的 AGGREGATION 省均值合同：
                        使用 benchmark/COMPARE/PROVINCE_AVERAGE，保留单机构、单指标和日期；不得输出 CLARIFY。
                    12. “上季度末”是自然季度边界，不是固定回退三个月：2025-10-31→2025-09-30、
                        2025-07-31→2025-06-30、2025-04-30→2025-03-31、2025-01-31→2024-12-31。
                     13. 题干出现完整 YYYY-MM-DD（包括“截至YYYY-MM-DD”和月末日期）时，PLAN 必须原样保留
                         startDate=endDate=该日期且 time.granularity=DAY；不得因为日期恰好是月末就改为 MONTH，
                         也不得截断成 YYYY-MM。只有用户明确询问按月分组时才允许 MONTH。
                     14. “某机构某日的个人贷款和对公贷款分别占各项贷款的比例/占比”是贷款结构双分项查询：
                         REQUIREMENTS 必须 action=EXECUTE、intent=POINT_QUERY，metricCodes 严格按
                         ["ZB006","ZB005","ZB002"]（个人贷款、对公贷款、各项贷款共同分母）填写，
                         derivedMetrics=[]，answerFactTypes=["VALUE","RATIO_VALUE"]；PLAN 使用相同指标顺序、
                         dimensions=["bank_organization"]、calculation.type=DIRECT、output.orderSensitive=true。
                         结果事实层会分别计算两个分项相对 ZB002 的比例；不得退化为单一 RATIO，也不得因日期为
                         2026 年或“分别”而返回 CLARIFY。

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
                       如果题干同时给出分类标签和对应指标清单，右侧指标即为封闭清单；宽泛标题只修饰
                       该清单中的各项，绝不表示查询 21 个目录指标。最终检查必须满足：metricCodes
                       的每个代码都可回指“=”右侧的一个短语或右侧派生指标的分子/分母；不能回指的额外代码
                       属于合同错误，必须移除后再输出。
                    3. 日期只能写 YYYY-MM-DD；比较基期必须用 baselineStartDate 和 baselineEndDate 明确表达。
                       对 comparison 非 NONE 且非 MOM_AND_YOY 的比较，当前期只能写在 startDate/endDate，
                       基期只能写在 baselineStartDate/baselineEndDate，且 baselineEndDate 必须早于 startDate。
                       绝不可把“从基期到当前期”误写成 startDate=基期、endDate=当前期；点对点比较时，
                       startDate=endDate=当前点，baselineStartDate=baselineEndDate=较早点。
                       “较年初”必须使用 comparison=START_OF_YEAR：当前期写题目给出的截至日期，
                       baselineStartDate=baselineEndDate=当前期前一年的 12-31；当年 01-01 不是“较年初”基期。
                       REQUIREMENTS 中 comparison 非 NONE 时 intent 必须为 CHANGE；PLAN 中也必须保留该 intent，
                       不得因题干含有“评估”“分析”或“结构”等词改成 AGGREGATION、COMPARISON 或 POINT_QUERY。
                       comparison 只要不是 NONE（YEAR_OVER_YEAR、PERIOD_OVER_PERIOD、START_OF_YEAR 或 MOM_AND_YOY），
                       calculation.type 必须为 CHANGE；DIRECT 只允许 comparison=NONE。不要把已经声明的
                       比较基期当作普通当前值查询。
                    4. “全省均值”只能使用目录允许的 benchmark/COMPARE/PROVINCE_AVERAGE 合同，不能写 SQL 或自行估算。
                    5. 收到 <repair> 时，只根据 tool_result、previous_plan、requirements_contract 和目录修正；
                       只改正 error 指出的非法槽位，并保留 previous_plan 中其余已合法、仍满足 requirements_contract
                       的槽位。仍只输出当前阶段要求的一份完整 JSON，不输出补丁或解释。
                    """
                    .replace("{{SEMANTIC_REGISTRY}}", BankSemanticRegistry.promptCatalog()).strip();

    public static final String PREFIX_VERSION = "bank-plan-sys-v48-query-family-contracts";

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
        String clarificationRecheck = buildClarificationRecheck(stage, error);
        String content = "%s\n\n<stage>%s</stage>%s\n<repair>\n<%s>%s</%s>\n"
                + "<previous_candidate>\n%s\n</previous_candidate>\n" + "%s"
                + "<instruction>只输出修正后的完整当前阶段 JSON；不输出解释、补丁或 SQL。</instruction>\n</repair>";
        String requirements = requirementsJson == null ? ""
                : "\n<requirements_contract>\n" + requirementsJson.strip()
                        + "\n</requirements_contract>";
        String result = content.formatted(buildDynamicUserContent(queryText), stage, requirements,
                repairTag, error, repairTag,
                previousCandidate == null ? "" : previousCandidate.strip(), clarificationRecheck);
        assertQuestionOnlyUserContent(result, stage + " repair user");
        return result;
    }

    private static String buildClarificationRecheck(String stage, String error) {
        if (!"REQUIREMENTS".equals(stage) || !error.contains("model selected CLARIFY")) {
            return "";
        }
        return """
                <clarification_recheck>
                这不是新的用户回合，而是合同纠错：题干已有明确目录命中时必须重新理解并执行，不要再次返回泛化澄清。
                通用归一化示例（仅在题干出现对应语义时采用，不要凭空添加指标）：
                - 从2024年末到2026-03-31表示 baselineStartDate=2024-12-31、baselineEndDate=2024-12-31、startDate=2026-03-31、endDate=2026-03-31。
                - 全省/各家银行表示查询全部机构，organizationCodes=[]，不要因为没有目标机构而澄清。
                - 一家唯一目录机构、至少一个目录指标和明确的起止日期已经构成完整请求；季度末
                  （一/二/三/四季度末）分别是 03-31、06-30、09-30、12-31。不得因为“逐季变化”、
                  “各季度末数值”或“哪个季度最高”再要求用户补充数据范围或具体指标。
                 - 增幅排名前三表示 intent=CHANGE、comparison=PERIOD_OVER_PERIOD、requiredLimit=3，并包含 CHANGE_RATE 事实类型。
                 - “机构A、机构B、机构C三家谁某指标最多/最少”表示局部 RANKING，保留全部列出的机构；
                   “机构A比机构B多多少/相差多少”表示 COMPARISON，保留两个机构并包含 GAP_VALUE，不能混用。
                - 目录别名：各项存款余额/存款余额 -> ZB001；各项贷款余额/贷款余额 -> ZB002；净利润 -> ZB011。
                - “某机构在YYYY-MM-DD的存贷比是多少”槽位已经完整：必须 action=EXECUTE，直接指标为
                  ZB002、ZB001，派生指标为 DERIVED_ZB002_DIV_ZB001，事实类型为 RATIO_VALUE；不得再次澄清。
                 - 某机构全年日均值、最高日、最低日表示该机构和指标的 AGGREGATION 查询，覆盖该年度完整日期范围。
                 - “某机构某指标全年有多少天高于全省均值”必须执行 AGGREGATION 的逐日计数合同：
                   benchmark=COMPARE/PROVINCE_AVERAGE、calculation.type=COUNT_DAYS_ABOVE_PROVINCE_AVERAGE、
                   answerFactTypes 至少包含 COUNT；不能因为字母城市名、日频或未来年份而澄清。
                 - “个人贷款和对公贷款分别占各项贷款的比例”必须执行 POINT_QUERY，metricCodes 按
                   ["ZB006","ZB005","ZB002"]，answerFactTypes=["VALUE","RATIO_VALUE"]；不能退化为单一 RATIO。
                - “某机构某指标在明确日期和全省均值比，是高还是低/差多少”已经具备完整槽位，必须输出
                  action=EXECUTE、intent=AGGREGATION、benchmark=COMPARE/PROVINCE_AVERAGE；不得再次要求
                  用户补充指标、日期或数据来源。
                - “上季度末”按自然季度末解析：2025-10-31 的上季度末是 2025-09-30，不能写成 2025-07-31。
                - 用户显式枚举非空目录指标并给出机构和日期时，这是可直接执行的封闭集合；必须逐项映射
                  并 action=EXECUTE。分类标题、排名、表现判定或排序方向只是输出约束，不能触发 CLARIFY。
                  只输出该集合的直接指标及其明确派生指标的分子、分母，不得加入清单之外的任何 ZB###。
                - 全年/期间均值排名必须使用完整日期范围与 time.granularity=DAY；底层是日事实，禁止 MONTH 或 yyyy-MM 日期条件。
                若指标、机构或时间确实无法从题干和权威目录唯一确定，只能保留 CLARIFY；否则输出 action=EXECUTE 的完整 JSON。
                </clarification_recheck>
                """;
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
