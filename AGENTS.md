# AGENTS.md

本文件约束在本仓库内工作的编码助手（Grok / Claude / Codex 等）。  
与 `CLAUDE.md` 并存：`CLAUDE.md` 侧重构建与模块地图；**本文件侧重银行 NL2SQL（bank path）优化准则与反作弊红线**。冲突时：评测诚实性与反作弊优先。

---

## 1. 优化总则：成熟语义编译，不是裸 Text2SQL

银行问数优化**必须**对齐成熟语义层 / semantic compiler 做法：

```text
自然语言
  → 意图与槽位（机构 / 指标 / 时间 / 对比类型）
  → 白名单校验（BankQueryPlanValidator）
  → 查询族模板编译（BankQueryPlanCompiler + BankS2SqlTemplateFactory）
  → 固定 S2SQL / Struct（禁止模型自造可执行 SQL）
  → 结果投影契约（BankResultProjector：列序、org_name 等）
  → （可选）摘要文案
```

| 要做 | 不要做 |
|------|--------|
| 模型只产出受约束 **plan JSON** | 模型直接写最终 SQL / free-SQL 当主路径 |
| 每种问法对应一种**查询族模板** | 按 train 题号 / 题面硬编码 builder |
| 编译失败则 repair / 拒答 / soft-fallback（有开关） | 假成功、脏 SQL 进执行器 |
| 结果表有固定契约，对齐 tableEX / gold **表形态** | 列随便长，靠碰巧过评测 |
| 扩展 `BankS2SqlTemplateFactory` 等编译层 | 在 prompt 里贴 gold SQL 或标准答案数字 |

SQL 是**编译产物**，不是模型作文。可审计标识优先：`planSource`、`templateCategory`、fingerprint、projector contract。

---

## 2. 反作弊红线（硬禁止）

以下行为视为**作弊 / 泄漏答案**，默认禁止：

1. **按 train / dev / test 样本 id 或完整题面写死 plan/SQL**（如 `VAL-*` / `TRAIN-H-04` 专用 builder）。
2. **把训练集 / 验证集 NL 或 gold SQL 当 few-shot 塞进 plan / free-SQL prompt**。
3. **把 gold 行、标准答案数字、官方 SQL 字符串写进运行时逻辑或提示词**。
4. **为刷分默认打开「预模型规则短路」**，用表面句式匹配代替模型+编译路径（见 §4）。
5. **改评测协议骗人**：向 parse/execute 请求夹带 gold 字段；覆盖官方 `train/dev/test.jsonl` 金标；把 SQL 字符串相似度当主分。

允许且推荐：

- **抽象骨架 / 查询族**（与具体题号无关）：如「单机构 + 多指标 + 日点 → aggregation summary 模板」。
- **结构化多步 repair**：校验错误回灌模型，改 plan JSON，不贴答案。
- **cold-replan / 受控 soft-fallback**（白名单规则 plan，仍不开放 unconstrained free-SQL）。
- 单测用**合成**问句与 hints，不依赖冻结集原题全文当唯一路径。

用户明确要求「不要作弊、不要训练集当样例」时，**优先执行本红线**，不得用「提分」绕过。

---

## 3. 查询族与结果契约（先表后话）

优化前先对齐**结果契约**，再改路由：

1. **tableEX / 结果表**：列集合与行语义必须由 compiler + projector **稳定产出**。  
   - 例：H-04 族 gold 表形态为多指标机构日汇总  
     `org_code, org_name, metric_code, aggregate_value, min_value, max_value, observation_count`  
   - 应落入已有 **multi-metric aggregation summary** 一类模板，而不是自由 UNION gap SQL。
2. **answerExact / 文案槽位**（全省差值、较年初等）可在**摘要层或投影扩展**第二阶段做，**不得**为文案破坏可执行表契约。
3. 问句措辞（如「与全省均值对比」）与 gold 表形态冲突时：  
   - **第一刀**：保证可执行 + tableEX（表契约）。  
   - **第二刀**：再补全省叙述/槽位。  
   - 禁止：模型自由写 gap SQL 导致 Calcite/执行失败却自称「更语义」。

新增能力时：

1. 定义 plan 形状与 output 契约（单测锁定列序）。  
2. 在 `BankS2SqlTemplateFactory` / compiler 注册查询族。  
3. validator / normalizer **把可识别槽位路由到该族**，拒掉非法形态进入执行。  
4. 小集 smoke（如 H-04~06）→ hard20 / train 回归。

---

## 4. 路由与消融默认（hard20 已证）

**复现最佳 bank-on 参数（清单 + 应用脚本 + hard20/reg21 ids）：**  
[`evaluation/bank_nl2sql/repro/BEST_BANK_ON.md`](evaluation/bank_nl2sql/repro/BEST_BANK_ON.md)  
[`evaluation/bank_nl2sql/repro/best_bank_on.json`](evaluation/bank_nl2sql/repro/best_bank_on.json)

| 开关 | 推荐默认 | 说明 |
|------|----------|------|
| `s2.parser.bank.plan.deterministic-short-circuit.enable` | **false** | 预模型规则短路：提分有限、掩盖模型路径、泛化差；仅消融/时延实验可开 |
| `s2.parser.bank.plan.soft-fallback.enable` | **true** | 模型候选与 cold-replan 全拒后，白名单规则兜底；关则 pure-model 消融（hard20 parse/tableEX 会显著掉） |
| `s2.parser.bank.max-candidates` | **1** | cand=2 未抬 tableEX，时延近倍增；仅诊断失败模式时再升 |
| `s2.parser.bank.plan.thinking.enable` | **false**（除非专项 A/B） | 短路开启时 thinking 无效；主线不默认开 |
| `s2.parser.bank.constrained-plan.enable` | bank-on 评测时 **true** | 与 free-SQL 路径消融对照时显式切换 |

消融要求：

- 报告记录 `planSource`（DETERMINISTIC / MODEL / MODEL_COLD_REPLAN / SOFT_FALLBACK）。  
- 对比用同一 `ids-file`、同一 agent、同一数据集冻结版本。  
- 脚本与产物可放 `.local-dev/bank-nl2sql/ablation/`（不提交大日志亦可）。  
- 主指标：**官方 AE（GOLD_OK 分母）** + 软指标 **tableEX**；不把 SQL 文本相似度当主分。

---

## 5. 实现落点（优先改哪里）

按优先级：

1. **Compiler / Template / Projector**（可执行 + 表契约）  
2. **Validator / AliasNormalizer / plan 规范化**（路由到正确查询族）  
3. **Prompt 抽象骨架 + repair**（无题号、无 gold、无 train NL few-shot）  
4. **摘要 / AE 文案**（不挡表契约）  
5. 最后才考虑 free-SQL 或提高候选数

复用现有类，避免平行再写一套「题库路由器」：

- `BankPlanGenStrategy`、`BankPlanPromptComposer`  
- `BankQueryPlanCompiler`、`BankS2SqlTemplateFactory`  
- `BankResultProjector`、`BankNl2SqlExecutionCoordinator`  
- 评测：`evaluation/bank_nl2sql/run_supersonic_eval.py`

---

## 6. 验证与交付

- 单测：模板列契约、validator 路由、repair 不泄漏 schema 题库。  
- 运行时：改 jar 后重启 standalone；H2 参数与 `-D` 系统属性一致。  
- 回归：相关 fail 子集 smoke → hard20 或约定 train 子集；保留 ablation 报告。  
- **未经用户明确要求：不 commit、不 push、不 PR。**  
- 不改对话产品流（多轮 rewrite 等），除非任务明确点名。

---

## 7. 一句话备忘

> **提分靠：抽象 plan → 白名单校验 → 查询族编译 → 结果契约。**  
> **不靠：背题、贴 gold、train few-shot、预短路刷分。**

以后凡 bank NL2SQL「优化 / 修题 / 消融」，先读本文件 §1–§3，再动代码。
