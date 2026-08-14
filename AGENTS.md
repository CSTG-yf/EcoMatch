# AGENTS.md

本文件约束在本仓库内工作的编码助手（Grok / Claude / Codex 等）。  
与 `CLAUDE.md` 并存：`CLAUDE.md` 侧重构建与模块地图；**本文件侧重银行 NL2SQL（bank path）优化准则与反作弊红线**。冲突时：评测诚实性与反作弊优先。

---

## 索引

| 章节 | 内容 |
|------|------|
| [§1](#1-优化总则成熟语义编译不是裸-text2sql) | 语义编译总则（plan → 校验 → 模板 → 投影） |
| [§2](#2-反作弊红线硬禁止) | 反作弊红线 |
| [§3](#3-查询族与结果契约先事实后话) | 查询族与结果契约 |
| [§4](#4-路由与消融默认hard20-已证) | 路由 / 消融默认开关 |
| [§5](#5-实现落点优先改哪里) | 实现落点 |
| [§6](#6-验证与交付) | 验证与交付 |
| [§7](#7-工程复现索引不计正式成绩) | 工程复现索引（不计正式成绩） |
| [§8](#8-一句话备忘) | 一句话备忘 |

### 正式评测唯一入口

正式成绩只能使用
[`evaluation/bank_nl2sql/Run-OfficialBankEvaluation.ps1`](evaluation/bank_nl2sql/Run-OfficialBankEvaluation.ps1)，
它固定 v2.0.6、Fact v3 `caseAccuracy`、串行执行、Agent 启动回执和 smoke → train → dev →
冻结 test 的门禁。正式评测的 execute 请求带 `resultOnly=true`，只保留查询和结构化
结果事实处理；最终回答只作非计分诊断。不得以其他脚本、历史报告或消融结果替代。

### 工程复现包（非正式）

目录：[`evaluation/bank_nl2sql/repro/`](evaluation/bank_nl2sql/repro/)

| 路径 | 说明 |
|------|------|
| [`evaluation/bank_nl2sql/repro/BEST_BANK_ON.md`](evaluation/bank_nl2sql/repro/BEST_BANK_ON.md) | 人话：参数表、证据水位、启动与评测步骤 |
| [`evaluation/bank_nl2sql/repro/best_bank_on.json`](evaluation/bank_nl2sql/repro/best_bank_on.json) | 机器可读：系统参数 / JVM / agent 应用 / 证据 |
| [`evaluation/bank_nl2sql/repro/apply_best_bank_on.py`](evaluation/bank_nl2sql/repro/apply_best_bank_on.py) | 停机后写入本地 H2（系统参数 + agent 33） |
| [`evaluation/bank_nl2sql/repro/ids-train-hard20.txt`](evaluation/bank_nl2sql/repro/ids-train-hard20.txt) | hard20 复现 id（TRAIN-H-01…20） |
| [`evaluation/bank_nl2sql/repro/ids-train-h04-family.txt`](evaluation/bank_nl2sql/repro/ids-train-h04-family.txt) | H-04 族 smoke（H-04…06） |
| [`evaluation/bank_nl2sql/repro/ids-train-reg21.txt`](evaluation/bank_nl2sql/repro/ids-train-reg21.txt) | reg21 回归 id（v48 弱项 + H-04 族） |
| [`evaluation/bank_nl2sql/RUNTIME_ABLATION.md`](evaluation/bank_nl2sql/RUNTIME_ABLATION.md) | bank-on / bank-off 消融操作说明 |

仅在工程调试或消融时读 **§7** 与 `BEST_BANK_ON.md`；参数以 `best_bank_on.json` 为准，
但不得把其输出称为正式成绩。

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
| 结果事实与实体绑定可审计，对齐 `resultExact` | 列随便长，靠碰巧出现正确数字 |
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

## 3. 查询族与结果契约（先事实后话）

优化前先对齐**结果契约**，再改路由：

1. **结果事实**：数值、机构、指标和日期身份必须由 compiler + projector **稳定产出**，并可被 `resultExact` 证明。
   - 例：H-04 族应落入已有 **multi-metric aggregation summary** 模板，而不是自由 UNION gap SQL。
2. **最终回答**：只作为用户可见的非计分展示；不得为文案破坏可执行查询或凭空补充日期/数字。
3. 问句措辞（如「与全省均值对比」）与中间投影形态冲突时：
   - **第一刀**：保证可执行且结果事实、实体绑定正确。
   - **第二刀**：再生成简洁、可校验的最终回答。
   - 禁止：模型自由写 gap SQL 导致 Calcite/执行失败却自称「更语义」。

新增能力时：

1. 定义 plan 形状与 output 契约（单测锁定列序）。  
2. 在 `BankS2SqlTemplateFactory` / compiler 注册查询族。  
3. validator / normalizer **把可识别槽位路由到该族**，拒掉非法形态进入执行。  
4. 小集 smoke（如 H-04~06）→ hard20 / train 回归。

---

## 4. 路由与消融默认（hard20 已证）

完整清单与一键应用见 **[§7](#7-最佳-bank-on-复现索引)**。

| 开关 | 推荐默认 | 说明 |
|------|----------|------|
| `s2.parser.bank.plan.deterministic-short-circuit.enable` | **false** | 预模型规则短路：提分有限、掩盖模型路径、泛化差；仅消融/时延实验可开 |
| `s2.parser.bank.plan.soft-fallback.enable` | **true** | 模型候选与 cold-replan 全拒后，白名单规则兜底；关闭会造成已知运行时回归 |
| `s2.parser.bank.max-candidates` | **1** | 多候选未证明正式评分收益，且时延近倍增；仅诊断失败模式时再升 |
| `s2.parser.bank.plan.thinking.enable` | **false**（除非专项 A/B） | 短路开启时 thinking 无效；主线不默认开 |
| `s2.parser.bank.constrained-plan.enable` | bank-on 评测时 **true** | 与 free-SQL 路径消融对照时显式切换 |

消融要求：

- 报告记录 `planSource`（DETERMINISTIC / MODEL / MODEL_COLD_REPLAN / SOFT_FALLBACK）。  
- 对比用同一 `ids-file`、同一 agent、同一数据集冻结版本。  
- 脚本与产物可放 `.local-dev/bank-nl2sql/ablation/`（不提交大日志亦可）。  
- 主指标：Fact v3 `caseAccuracy`（全分母，等于 `resultExact`）；最终回答不参与计分；不把 SQL 文本或表形态当分数。

---

## 5. 实现落点（优先改哪里）

按优先级：

1. **Compiler / Template / Projector**（可执行 + 结果事实/实体契约）
2. **Validator / AliasNormalizer / plan 规范化**（路由到正确查询族）  
3. **Prompt 抽象骨架 + repair**（无题号、无 gold、无 train NL few-shot）  
4. **摘要 / 最终回答**（不挡结果事实契约）
5. 最后才考虑 free-SQL 或提高候选数

复用现有类，避免平行再写一套「题库路由器」：

- `BankPlanGenStrategy`、`BankPlanPromptComposer`  
- `BankQueryPlanCompiler`、`BankS2SqlTemplateFactory`  
- `BankResultProjector`、`BankNl2SqlExecutionCoordinator`  
- 评测：`evaluation/bank_nl2sql/Run-OfficialBankEvaluation.ps1`

---

## 6. 验证与交付

- 单测：模板列契约、validator 路由、repair 不泄漏 schema 题库。  
- 运行时：改 jar 后重启 standalone；H2 参数与 `-D` 系统属性一致。  
- 回归：相关 fail 子集 smoke → 约定 train/dev 集；使用 `Run-OfficialBankEvaluation.ps1` 记录 Fact v3 报告。
- **未经用户明确要求：不 commit、不 push、不 PR。**  
- 不改对话产品流（多轮 rewrite 等），除非任务明确点名。

---

## 7. 工程复现索引（不计正式成绩）

**目录：** [`evaluation/bank_nl2sql/repro/`](evaluation/bank_nl2sql/repro/)。其中内容只用于工程
消融与故障复现，不能生成或替代正式成绩；正式评测唯一入口是
[`Run-OfficialBankEvaluation.ps1`](evaluation/bank_nl2sql/Run-OfficialBankEvaluation.ps1)。

### 7.1 文档与清单

| 文件 | 用途 |
|------|------|
| [`BEST_BANK_ON.md`](evaluation/bank_nl2sql/repro/BEST_BANK_ON.md) | 参数表、证据水位、启动/评测命令、不推荐配置 |
| [`best_bank_on.json`](evaluation/bank_nl2sql/repro/best_bank_on.json) | 权威参数：`systemParameters` / `jvmSystemProperties` / `agentChatApps` / `evidence` |
| [`apply_best_bank_on.py`](evaluation/bank_nl2sql/repro/apply_best_bank_on.py) | 停机写 H2：`s2_system_config` + agent 33 chat apps；`--dry-run` 只打印 |

### 7.2 评测 id 列表

| 文件 | 用途 |
|------|------|
| [`ids-train-hard20.txt`](evaluation/bank_nl2sql/repro/ids-train-hard20.txt) | hard20（TRAIN-H-01…20） |
| [`ids-train-h04-family.txt`](evaluation/bank_nl2sql/repro/ids-train-h04-family.txt) | H-04 族 smoke |
| [`ids-train-reg21.txt`](evaluation/bank_nl2sql/repro/ids-train-reg21.txt) | reg21（v48 弱项 + H-04 族） |

### 7.3 关键参数（摘要，以 JSON 为准）

```text
s2.parser.bank.constrained-plan.enable              = true
s2.parser.bank.max-candidates                       = 1
s2.parser.bank.plan.deterministic-short-circuit.enable = false
s2.parser.bank.plan.soft-fallback.enable            = true
s2.parser.bank.plan.thinking.enable                 = false
agent 33: BANK_CONSTRAINED_PLAN=on; EXECUTION_SQL_CORRECTOR=on（建议）
```

JVM 建议：

```text
-Ds2.parser.bank.plan.deterministic-short-circuit.enable=false
-Ds2.parser.bank.plan.soft-fallback.enable=true
-Ds2.parser.bank.plan.thinking.enable=false
```

### 7.4 工程复现入口

```powershell
# 停服务后对齐 H2
.local-dev\eval-venv\Scripts\python.exe evaluation/bank_nl2sql/repro/apply_best_bank_on.py

# 启动 standalone（带上 §7.3 JVM）后，只运行固定官方 smoke。
powershell -ExecutionPolicy Bypass -File evaluation/bank_nl2sql/Run-OfficialBankEvaluation.ps1 `
  -Mode smoke -RunId <RUN_ID> -BaseUrl http://127.0.0.1:9080 -AgentId <AGENT_ID> `
  -ModelLabel '<MODEL_LABEL>' `
  -BootstrapReceipt .local-dev/bank-nl2sql/official-v3/bootstrap-receipt.json
```

### 7.5 相关说明

- 消融操作：[`evaluation/bank_nl2sql/RUNTIME_ABLATION.md`](evaluation/bank_nl2sql/RUNTIME_ABLATION.md)  
- 本地 ablation 报告默认在 `.local-dev/bank-nl2sql/ablation/`（可不入库），只比较 Fact v3 `caseAccuracy`。
- 改默认参数时：同步更新 `best_bank_on.json` + `BEST_BANK_ON.md` + 本 §7.3  

---

## 8. 一句话备忘

> **提分靠：抽象 plan → 白名单校验 → 查询族编译 → 结果契约。**  
> **不靠：背题、贴 gold、train few-shot、预短路刷分。**

以后凡 bank NL2SQL「优化 / 修题 / 消融」：先读 **§1–§3** 与 **§7（复现参数）**，再动代码。
