# 正式评测运行记录（RUN RECORDS）

本文件只登记通过唯一入口
[`Run-OfficialBankEvaluation.ps1`](./Run-OfficialBankEvaluation.ps1)
执行的官方运行及其环境要点。主指标恒为 Fact v3 `caseAccuracy`（全分母，
等于 `resultExact`）；最终回答与 SQL 文本不参与计分。

- 数据集冻结版本：v2.0.6（official manifest sha `469E1A58A3B5949D5545E57EB5A16D9BA328ED9D5340DE5596E3B2D7C31FA77D`）
- bootstrap receipt：`.local-dev/bank-nl2sql/official-v3/bootstrap-receipt.json`
  （agentProfileSha256 `66dd1100417…`，语义导入 facts=132678 / indicators=21 / organizations=13）

---

## 2026-08-27｜重建分支 codex/rebuild-official-main-20260827（PR #34）｜dev

**结论：dev 40/40 全对，caseAccuracy = 1.0。**

### 运行标识

| 项 | 值 |
|----|----|
| RunId | `rebuild-dsv4flash-dev-20260827-r2` |
| 报告 | `.local-dev/bank-nl2sql/official-v3/rebuild-dsv4flash-dev-20260827-r2/dev.json`（+ dev.md） |
| 源码 | `149cdea` fix(bank): strict-schema nullability and per-model prefix warm keys |
| 服务 | standalone（本机新部署目录，port **9081**；9080 为坏分支保留实例） |
| 协议 | fact-contract-v3，perRecordConversation isolated，串行 concurrency=1 |
| protocolProfileSha256 | `823af317beef8731643c8fa1193d065b48f640ea7dafaf9941114344f800ab4f` |

### 模型与环境

| 项 | 值 |
|----|----|
| ChatModelId / 模型 | **1409** `deepseek-v4-flash`（OPEN_AI 兼容，opencode.ai/zen 端点） |
| 传输路径 | OpenAI 结构化传输 + json_object 响应格式（非 llama.cpp prefix-KV） |
| Agent | 33，`bind_runtime_chat_model` 运行时重绑并回读校验 |
| JVM 系统属性 | `-Ds2.parser.bank.plan.thinking.enable=false -Ds2.parser.bank.plan.deterministic-short-circuit.enable=false -Ds2.parser.bank.plan.soft-fallback.enable=true` |
| H2 系统参数 | best_bank_on.json（constrained-plan=true、max-candidates=1、soft-fallback=true、短路=false），经 `apply_best_bank_on.py --agent-id 33` 写入副本库 |

> 注意：局域网 Qwen 主机（192.168.20.66 / .115:8080）当日不可达（ping 100% 丢包），
> 无法复现 qwen66 口径；本次为**云模型口径**，与「p2-comparison-limit-qwen66-20260823」
> 的 dev 40/40 基线不构成同口径对比。

### 结果事实

- caseAccuracy = 1.0（分母 40，hits 40）；resultFactAccuracy = 1.0（40/40 resultExact）
- contractReadyRate = 1.0；parseSuccessRate = 1.0；executionSuccessRate = 1.0
- errorCategories 全 NONE；每题独立会话全部清理成功
- 时延：parse p50 ≈ 10.7s / p95 ≈ 35s；execute p50 ≈ 0.81s；
  endToEnd p50 ≈ 11.5s / p95 ≈ 36s / max ≈ 169s（单例冷启动尾延）
- 总时长约 716s（不含预热；warmup 会话 parseState=FAILED 但被协议隔离，不影响任何计分题）

### 同日前置事件（不参与成绩，仅存证）

RunId `rebuild-dsv4flash-dev-20260827`（r1，已中止）：VAL-H-01 在编译 READY 后
执行翻译阶段报 `Column "enabled" not found`（SQL：
`select config from s2_auth_groups where enabled = 1 …`）。

根因：本机桌面 state 元数据库为旧 schema，`s2_auth_groups` 只有
group_id/config 两列，缺 origin/main 运行期所需 8 列
（model_id/policy_code/enabled/policy_version/valid_from/valid_to/updated_at/updated_by）。
启动时 `CREATE TABLE IF NOT EXISTS` 不补旧表列。

处置：以参照库 diff 确认漂移仅此一处后，对**副本库**执行 ALTER ADD COLUMN ×8 +
回填 enabled=1 + 建索引 idx_auth_group_model_enabled。桌面原库未改动，
后续直接对它起新版实例前需同样补列。

---

---

## 2026-08-27｜同分支｜frozen test（**作废：云端 key 中途失效，成绩不可晋升**）

**结论：caseAccuracy = 30%（12/40），但其中 27 题失分由云端模型服务中断造成，
不构成对链路能力的有效测量。此轮只作工程故障记录。**

### 运行标识

| 项 | 值 |
|----|----|
| RunId | `rebuild-dsv4flash-test-20260827` |
| 报告 | `.local-dev/bank-nl2sql/official-v3/rebuild-dsv4flash-test-20260827/test.json`（+ test.md） |
| 环境参数 | 与上一节 dev 完全一致（实例、agent、参数、receipt） |
| 总时长 | ≈ 7592s；endToEnd p50 ≈ 14s 但 p95 ≈ 900s（重试风暴） |

### 逐题归因分桶

| 分桶 | 数量 | 判据 |
|------|------|------|
| 通过 | **12** | resultExact=true（复杂段 8/12，普通段 4/13，简单段尚未进入故障即已中断的部分为 0） |
| 真·事实不匹配 | **1**（TST-M-02） | errorCategory=RESULT_FACT_MISMATCH：执行成功、产出事实与金标不等价 |
| 服务中断 | **27** | errorCategory=ENVIRONMENT_TRANSPORT(5)，或 PARSE_ERROR 且最终文案为「银行指标查询服务暂时不可用」(22) |

### 根因证据链

1. 故障窗从测试中段开始呈**间歇抖动**（通过/失败在 H 段交错），17:22 起恶化为全灭；
2. 17:22 后服务端日志每题均出现同一异常哈希（chars=372）的
   `EmbeddingMatchStrategy - Error in LLM detection` ×60 次——连 embedding 辅助检测通道也在抛错，
   说明是所有出站 LLM 调用失败而非 bank 解析逻辑问题；
3. 赛后立即复测端点：`POST /v1/chat/completions` 返回 `AuthError: Invalid API key`
   （`GET /v1/models` 为公开接口仍 200）。判定为 opencode.ai key 额度耗尽/失效，
   失效时刻约在 test 进行到中段；
4. dev 整轮完成于故障之前，故 dev 40/40 不受影响。

### 有效结论与遗留

- 健康窗口内的证据（dev 40/40 + test 复杂段 8/12、普通段前段连续通过）
  支持链路功能正常；真·语义错配目前仅 TST-M-02 一例。
- 本轮 test 分数不作任何能力结论。待云 key 更换或局域网 Qwen 恢复后，
  以相同 RunId 重跑（runner 支持 resume 兼容报告，已通过的 12 题不必重测）。

### 记录纪律附注

本次首轮监控中曾按中盘形态把 H-06/M-01 等预判为"plan 合法但语义落错族"——
逐题归因证明其中多数实为传输层故障（该判断仅对 TST-M-02 一类成立）。
据此修正：**result_mismatch 不能直接等同于语义错配，必须先核对
errorCategory 与执行字段再归因。**

---

## 2026-08-27｜GLM-5.3-flash low-effort｜dev（glm53low-dev-20260827-r1）

**结论：dev 32/40，caseAccuracy = 0.80。30 题链路健康；6 题传输超时查明为
旁路 LLM 增强通道（`use-llm-enhance`）× 全量思考模型的超时叠加；2 题真·事实错配。**

### 运行标识

| 项 | 值 |
|----|----|
| RunId | `glm53low-dev-20260827-r1` |
| 报告 | `.local-dev/bank-nl2sql/official-v3/glm53low-dev-20260827-r1/dev.json` |
| 源码 | PR #34 `7949b20` + **未提交**的 reasoning_effort 接线（ChatModelConfig / LlamaCppPrefixChatClient / FixedSystemPrefixLlmCache），运行时 jar 于 19:38 由工作区构建 |
| 服务 | `.local-dev/runtime-glm53low` 独立部署，port 9081，agent 33，`bind_runtime_chat_model` 重绑并回读 |
| 协议 | fact-contract-v3，串行；总时长 11827s |

### 模型与低推理配置

| 项 | 值 |
|----|----|
| ChatModelId / 模型 | **1985** `GLM-5.3-flash`（OPEN_AI 兼容，AutoDL 端点；modelLabel 仅展示） |
| 低推理 | 模型行注入 `reasoning_effort=low`（该网关始终思考，禁用参数全被 400 拒绝；low 为实测唯一有效档） |
| 传输 | 主 bank-plan 通道**全部**改走 LlamaCppPrefixChatClient 直连（含 jsonFormat=false），参数到达线上已验证 |
| JVM / H2 | 同 best_bank_on（constrained-plan=true、max-candidates=1、soft-fallback=true、短路=false、thinking=false） |

### 结果与分桶

- caseAccuracy = **32/40**；errorCategories：NONE 32 / ENVIRONMENT_TRANSPORT 6 / RESULT_FACT_MISMATCH 2
- parse 成功率 0.85（34/40）；parse p50≈156s、p95≈599s、max≈812s；execute 平均 942ms

| 分桶 | 数量 | 判据 |
|------|------|------|
| 通过 | **32** | resultExact=true |
| 传输超时（环境类） | **6**（VAL-H-03/04/06/11/12、VAL-M-08） | 全部 RANKING+WINDOW_RANK（±MULTI_METRIC/TOP_BOTTOM/AVERAGE）族，e2e≈902s 撞评测单题天花板，parse 未产出（queryId=null、evidence=MISSING） |
| 真·事实错配 | **2**（VAL-M-02 CHANGE/BASELINE_COMPARISON；VAL-M-14 RATIO/DERIVED_METRIC） | 执行成功、行已捕获，结果事实与金标不等价 |

### 根因证据链（重要发现：旁路巨上下文增强调用）

1. 低推理配置非失败源：直连 A/B 探测（4KB 与大 prompt）证明 `reasoning_effort=low` 生效
   （reasoning_tokens=0，1.4s 回包），探测尺寸内网关健康。
2. 实例日志 19:59–20:04 四条 `EmbeddingMatchStrategy:112 - Error in LLM detection`
   （context chars≈7.2 万；RuntimeException×3 / JSONException×1；间隔 48–101s），与 VAL-H-03 的 901s 窗口重合。
3. 代码定位：`EmbeddingMatchStrategy.detectWithLLM` 在
   `s2.mapper.embedding.use-llm-enhance=true` 时，把全部向量召回结果（该模式下跳过相似度过滤，
   上限 10×batch=500 条 ≈ 7 万字符）整体 JSON 序列化塞进单个 LLM_FILTER_PROMPT；
   调用经 `ModelProvider.getChatModel(REWRITE_MULTI_TURN 绑定=1985)` → **stock langchain4j 客户端，
   不经过 LlamaCppPrefixChatClient，不吃 reasoning_effort=low** → 全量思考 × 7 万字符 prompt →
   读超时/连接重置（RuntimeException），或思考文本混入 content 致 fastjson 解析失败（JSONException）。
4. 异常被 catch 吞掉并兜底回纯向量召回（功能不受损），但每个 parse 轮次白付 60–100s；
   排名族分词片段多、内部轮次多，累计拖过 901s 天花板形成六连环境失败；
   通过题 parse p50=156s 也被同一因素抬高。
5. 对照：deepseek dev r2（40/40）同一开关在位但该模型非全量思考，增强调用小而快，无放大效应。
6. 排除 demo 兜底：本机至 api.openai.com 不可达（10s 连接超时）；dev.json protocol 记录
   `REWRITE_MULTI_TURN=1985`，证实走 AutoDL 而非 `DEMO_CHAT_MODEL`。

### 结论、遗留与后续选项

- `use-llm-enhance` 不在 best_bank_on.json 复现包内；赛后停机核实：s2_system_config 中**无此键**，
  真实来源是运行时部署配置 `conf/s2-config.yaml`（`s2.mapper.embedding.use-llm-enhance: true`，
  经 Spring Environment 第二优先级生效）。
- 后续选项（未经用户批准不动代码/不提交）：
  ① 关闭 `s2.mapper.embedding.use-llm-enhance`（回到纯向量召回）；
  ② 让 ModelProvider/工厂层旁路客户端同样注入 reasoning_effort；
  ③ 给 LLM_FILTER_PROMPT 的召回 dump 加上限（按相似度取 top-K）。
- 记录纪律：本轮虽走正式入口，但运行时 jar 含未提交改动与 H2 遗留开关，
  32/40 仅作工程参考基线，不作为能力结论引用。

---

## 2026-08-28｜GLM-5.3-flash low-effort｜关 use-llm-enhance 复测｜dev（glm53low-dev-20260827-r2）

**结论：dev 36/40，caseAccuracy = 0.90（r1 同口径 32/40）。环境类失败 6→0，parse p50 156s→23s，
总时长 11827s→1137s。四道失败全部为真·事实错配且全部带完整结果行，语义问题首次完全裸露。**

| 项 | 值 |
|----|----|
| RunId | `glm53low-dev-20260827-r2` |
| 报告 | `.local-dev/bank-nl2sql/official-v3/glm53low-dev-20260827-r2/dev.json` |
| 变更点 | 仅一处：`conf/s2-config.yaml` `s2.mapper.embedding.use-llm-enhance` true→false（重启生效）；其余与 r1 完全一致（jar、agent 33、model 1985 low-effort、best_bank_on 参数） |
| 协议 | fact-contract-v3，串行，chatModelId 1985 重绑回读一致 |

### 结果对比

| 指标 | r1（增强开） | r2（增强关） |
|------|----|----|
| caseAccuracy | 32/40 = 0.80 | **36/40 = 0.90** |
| ENVIRONMENT_TRANSPORT | 6 | **0** |
| RESULT_FACT_MISMATCH | 2 | 4 |
| parse 成功率 | 0.85 | **1.00** |
| parse p50 / max | 156s / 812s | **23s / 53s** |
| 总时长 | 11827s | **1137s** |
| r1 六道排名族 902s 超时（H-03/04/06/11/12、M-08） | 全失败 | **全通过** |

### 四道错配的病灶分型

- **同值重复型 ×2（M-15、S-04）**：结果行形如 `[38.29, 38.29, 38.29, 1]`——当前值/基线值两列
  绑定到同一表达式，派生比率恒为 1。RATIO/DERIVED 与多指标对比族的投影绑定 bug 指纹，修复优先级最高。
- **基线比较分母/口径型 ×2（M-01、M-03）**：CHANGE/BASELINE_COMPARISON 族，两期值正确、
  差值正确，比率 0.1549 / -3.9635 与金标口径不合（分母取基期还是当期、是否百分比化待核对）。
- 注：r1 的 M-02（同族）本轮通过、r1 通过的 S-04 本轮失败——同族内存在逐题方差，
  归因以族为单位，不以单题成败为准。

---

## 2026-08-28｜GLM-5.3-flash｜族级闸门 + 关闭多轮重写｜dev r3/r4 与 test

**结论：dev r4 = 40/40（caseAccuracy = 1.0），总根因查明为 `REWRITE_MULTI_TURN` 重写层
对题面的语义破坏。test 分割同配置重跑见下节。**

### 渐进对照（全部走官方入口、同实例口径）

| 轮 | 配置增量 | dev | parse p50 | 总时长 |
|----|----------|-----|-----------|--------|
| r1 | low-effort + 增强开 | 32/40（6 传输超时） | 156s | 11827s |
| r2 | + use-llm-enhance=false | 36/40 | 23s | 1137s |
| r3 | + requirements 族闸门（年初基期/比率形状/自比率/方向/scale 统一等八项） | 37/40 | 24s | 1036s |
| r4 | + **REWRITE_MULTI_TURN 关闭** + plan 层双闸门 | **40/40** | **8s** | **424s** |

### r3 残余 3 失败引出的根因（重要）

r3 后核查 H2 `s2_chat_parse.query_text` 发现：**题面在到达 bank 解析器前已被
`REWRITE_MULTI_TURN`（绑定 1985 号 GLM）改写**——

- VAL-M-02「从年初到2025-05-31变化了多少」→「请查询…**2025年1月1日至**2025年5月31日期间…变化情况」
  （重写层把"年初"解析成当年 01-01，正是基期错误的源头）；
- VAL-M-13「存贷比？」→「各项贷款余额和各项存款余额**分别**是多少？」
  （命名比率被拆成两指标点查措辞，"分别"还命中族闸门的排除词，主动推向退化形状）。

族闸门与交叉校验在合成测试中全部工作，但它们看到的是改写后文本、触发词已被抹掉；
三轮评测同题忽过忽败 = 重写模型的非确定性。r1–r3 的"逐轮方差"实为重写层方差。
附带收益：重写每题多烧一次 LLM 调用，关闭后 parse p50 23s→8s、总时长再降 60%。

### r4 变更清单

1. H2 `s2_agent(33).chat_model_config`：`REWRITE_MULTI_TURN.enable` true→false（回读验证；
   best_bank_on 复现包本就不含该 app，此举即回归基线）。
2. plan 层双闸门（BankPlanGenStrategy）：`validateStartOfYearPlanContract` /
   `validateRatioPlanContract`——直接校验编译输入 plan 的 time.comparison/baseline 与
   intent/calculation，不依赖 requirements（堵 requirements↔plan 分叉与题面被改写后的漏拦）；
   requirements 层闸门保留。BankPlanGenStrategyTest 56/56 绿。
3. 其余与 r3 完全一致（jar 含此前八项族级修复 + reasoning_effort 接线）。

### test 分割（glm53low-test-20260828-r1，与 dev r4 同配置同 jar）

**35/40 = 0.875**；parse p50 8s，总时长 421s；errorCategories：NONE 35 / PARSE_ERROR 2 / RESULT_FACT_MISMATCH 3。
五处失败族级归因（对照 test.jsonl 金标）：

| 题 | 归因 | 说明 |
|----|------|------|
| TST-H-04 | 既有族执行缺陷 | 多指标"前三/后三"排名，列契约正确但**空行集**（rank 切片过滤产出 0 行） |
| TST-H-10 | 族契约分歧 | 双条件省均值阈值（不良率低于均值**且**拨备高于均值）：金标=全机构宽表+meets_condition 标志，我们=长格式且仅满足者 |
| TST-H-11 | **族缺口** | "排名变化了多少"（current_rank/baseline_rank/rank_change）：无 rank-change-over-time 族，落入值 CHANGE |
| TST-M-19 | **族缺口** | 复合派生率（不良率+逾期率合计占贷款比）：无两率相加族，全部尝试被拒 → PARSE_ERROR |
| TST-S-07 | 待查 | "哪家农商行存款最少？" 基础底部排名题 PARSE_ERROR，成因需查 H2 解析记录 |

**纪律声明**：test 为冻结保留集。上述归因仅用于族级缺口识别；后续修复必须以通用查询族语义
设计与验证（dev + 合成单测），不得针对 test 题面调参；test 重跑仅作最终验证而非调参回路。


## 2026-08-28｜GLM-5.3-flash｜v58 提示词 + 全链路可修复 + 兜底通道上线｜dev r5

**结论：dev r5 = 39/40（caseAccuracy = 0.975）。受控自由 SQL 兜底通道上线（开关开）但
dev 全程零触发（dev 无未支撑形状，符合预期）；兜底通道对主路径零干扰。唯一失败
VAL-H-03 归因进行中（见下）。**

### 配置增量（相对 r4）

1. v58 单测前缀（提交 c26ac14）：limit 一般规则、aggregationMode 可见契约、
   COUNT_DAYS 负向约束、比较 operator 用法块、YOY/SOY 判例、REQUIREMENTS↔PLAN 映射表、
   日期基期规则去重、BOTTOM_RANKING 文案统一、granularity 指引、repairMessage 双态指令。
2. 全链路可修复（提交 7949b20）：compilationCorrectionHints 12-Reason 全覆盖、编译原始
   异常透传、allowedValues 白名单填充、翻译失败黑洞修补（TRANSLATION_FAILED 进 REPAIRABLE）、
   环境级故障快速终态（BankEnvironmentFaultClassifier：AUTH/429/TIMEOUT → 不烧轮次）。
3. 受控自由 SQL 兜底（未提交，工作区）：QueryShape 路由表 + UNSUPPORTED_QUERY_SHAPE、
   JSqlParser AST 白名单、规范列契约、{sql,columns,confidence} 双输出、planSource=FREE_SQL
   遥测；开关 `-Ds2.parser.bank.free-sql-fallback.enable=true`（默认 false）。
4. reasoning_effort=low 接线（未提交）：ChatModelConfig.reasoningEffort 字段 + 直连客户端
   透传；H2 s2_chat_model(1985) 已含 reasoningEffort:"low"（本次读库核实）。

### 结果与通道拆分

| 指标 | 值 |
|------|-----|
| caseAccuracy | 0.975（39/40） |
| planSource 通道 | MODEL 39/39 全过；FREE_SQL 0 触发（无编译终态形状到达） |
| parse p50 / 总时长 | 8.5s / ~7min |
| 相对 r4 | -1 题：VAL-H-03（r4 过 → r5 PARSE_ERROR） |

### VAL-H-03 失败链（已归因：传输层截断 + 修复轮采样方差，非代码回归）

多指标前三/后四排名分类题（6 指标含派生存贷比），r5 全程唯一 fail。取证含 sha256 反解
验证（子 agent 离线复现 + 日志比对 r3/r4/r5 三个 runtime）：

- 第 1 轮 `MALFORMED_JSON`（completion_tokens=613）——**根因：plan 调用从不发
  `max_tokens`**。`FixedSystemPrefixLlmCache.resolveOptions` 的 `safetyMaxTokens=2048`
  只对本地 llama.cpp 端点生效，远端 AutoDL OpenAI 端点走 `defaults()`（不发该字段），
  输出上限由服务端默认值决定。r4 同题第 1 轮同样截断（1114 tokens），第 2 轮修复成功；
  r5 第 2 轮采样到缺 derivedMetrics + 空 orderBy 的 plan 被拒 → "bank query plan
  remained invalid after one structured repair" → fail-closed。r4 过 r5 挂 = 单次修复
  预算下的采样方差。
- 离线单测证实编译器无辜：canonical plan 经工作区编译器产出的 SQL 与 r4 通过时**逐字节
  一致**；limit=7（v58 的 2N 规则）不进该族 SQL；全部 scale 变体通过 Calcite 等价校验链
  （DiagH03CalciteReproTest 4/4）。
- **勘误**：首报曾把 `SemanticNode:425` 的 CalciteContextException 当根因。经查该异常
  在 r3/r4/r5 每轮 parse 都出现（40/78/39 次），被 SqlBuilder 外层 catch 且源码注释明言
  不影响查询结果，属良性噪音；VAL-H-03 的失败 trace 里没有任何编译活动。
- 修复（通用、传输层）：thinking 关闭时对远端 OpenAI 兼容端点也发送显式
  max_tokens=2048（`SINGLE_PASS_MAX_OUTPUT_TOKENS`，高于实测最大输出 ~1.1k）。
  thinking 端点保持豁免（reasoning_content 会消耗 decode 预算）。

### 修复验证：dev r6 = 40/40（显式 max_tokens）

**配置增量（相对 r5）**：`FixedSystemPrefixLlmCache.resolveOptions` 改为 config 感知——模型
配置带 `reasoningEffort`（推理已被外部约束）时，远端 OpenAI 兼容端点也发送显式
`max_tokens`（plan 通道 `SINGLE_PASS_MAX_OUTPUT_TOKENS=2048`；free-SQL 兜底缓存新增
`FREE_FALLBACK_MAX_OUTPUT_TOKENS=2048`）。隐式 reasoning 远端（DeepSeek 类）保持原豁免。
新增 2 个单测锁定语义（有界 reasoning→发上限；无界→保持豁免），bank 包 429/429 绿。

| 指标 | r5 | r6 |
|------|----|----|
| caseAccuracy | 0.975（39/40） | **1.000（40/40）** |
| planSource 通道 | MODEL 39/39，FREE_SQL 0 | MODEL 40/40，FREE_SQL 0 |
| parse p50 | 8.5s | 8.1s |
| 失败 | VAL-H-03（截断链） | 无 |

验证链：单题 replay 确认 attempt=1 不再 MALFORMED_JSON、attempt=2 修复被接受、产出与
r4 通过时逐字节同长（3509 字符）的族模板 SQL、执行返回正确机构行集；随后官方 r6 全量
确认。v58 提示词 + 全链路修复 + 兜底通道 + 显式 max_tokens 全部在位且 dev 满标。
