# DATA-02 NL2SQL 标注数据集实施方案

## 1. 目标与边界

以比赛 Excel 中的 200 道问题、13 家机构、21 项指标和 132,678 条日指标事实为唯一官方基准，构建可复现、可执行、可审计的银行 NL2SQL 标注数据集，供 `BE-05` 复杂 NL2SQL、`BE-06` SQL 安全网关和 `QA-01` 自动评测直接消费。

本任务交付数据、标准库、校验器和评测适配，不实现 NL2SQL 模型/解析器优化，不修改线上查询流程，也不把测试集标准 SQL 暴露给模型提示词或训练输入。

## 2. 已冻结的输入

- 比赛工作簿：5 个工作表，机构 13、指标 21、衍生口径 10、事实 132,678、问题 200。
- 官方问题划分：训练 120、验证 40、测试 40；简单 40、普通 100、复杂 60。
- `DATA-01`：已提供标准化意图、指标、机构、时间和过滤条件标注，可作为 DATA-02 的语义输入。
- `BE-01`：已约定 `bank_data_date`、`bank_organization`、`bank_indicator` 以及 `zb001`～`zb021` 的语义资源。
- 源文件 SHA-256：`c3b810a4938fefc77a5c834c4c6857bec7f67162c4160abd9f66d9dd6018703c`。

官方核心集严格保留原 200 道题及其 `sourceSplit`（120/40/40）。由于原始分割存在 4 组模板跨越训练/测试或验证/测试，`split` 使用 DATA-01 已冻结的无模板泄漏评测分割（核心集 115/36/49）；被调整的 9 条题保留原 `sourceSplit` 和调整原因。`DATA-01` 的 12 条增强样本单独进入 `augmentation.jsonl`，只用于开发和鲁棒性测试，不计入官方 SQL 准确率，也不进入官方验证集或测试集。

## 3. 标准基准库

### 3.1 逻辑模型

采用最小星型模型，既保持原始事实粒度，也为多表关联提供真实路径：

| 表 | 主键 | 关键字段 | 用途 |
| --- | --- | --- | --- |
| `bank_organization` | `org_code` | `org_name` | 机构维表 |
| `bank_metric_definition` | `metric_code` | `metric_name`、`metric_meaning`、`metric_unit` | 指标口径维表 |
| `bank_metric_daily` | `data_date, org_code, metric_code` | `metric_value` | 日指标事实表 |

事实表使用 `DATE`、`DECIMAL` 和外键约束，不把机构名、指标名重复写入事实表。生成器从工作簿构建本地 H2 基准库，同时输出兼容现有离线评测器的 SQLite 基准库；标准 SQL采用两种数据库都支持的 ANSI 子集。

### 3.2 数据不变量

- 日期范围必须为 `2024-12-31`～`2026-04-30`，连续 486 天。
- 每天必须有 `13 × 21 = 273` 条事实，总数必须为 132,678。
- 联合主键无重复，指标与机构外键全部有效。
- 指标名称、单位和口径必须与指标清单一致。
- 生成产物记录源文件、脚本版本、schema 和数据内容哈希；重复生成结果必须一致。

## 4. 单条标注契约

每条 JSONL 至少包含：

```json
{
  "id": "TRAIN-S-01",
  "split": "train",
  "question": "...",
  "difficulty": "简单",
  "normalizedIntent": {},
  "expectedAction": "EXECUTE",
  "s2sql": "...",
  "sql": "...",
  "sqlFeatures": ["FILTER", "AGGREGATION"],
  "expected": {
    "columns": [],
    "rows": [],
    "answerText": "42.02亿元",
    "unit": "亿元",
    "numericTolerance": 0.01,
    "orderSensitive": false
  },
  "errorCategory": null,
  "source": {}
}
```

关键规则：

- 官方 200 题均已有标准答案，必须标为 `EXECUTE` 并提供可执行 S2SQL、物理 SQL 和预期结果；不得因生成困难静默改成澄清或排除。
- 增强集允许 `expectedAction=CLARIFY`，此时 `s2sql/sql` 为空，并提供结构化澄清原因和候选项。
- 金额、比率、人数、户数分别声明单位、精度和比较容差；原始值与展示文本分开保存。
- 排名必须记录升降序口径：不良贷款率、逾期贷款率、成本收入比越低越好，其余指标越高越好。
- “较年初”在本比赛数据中严格按源口径使用 `2024-12-31`，不得自行改成自然年度年初。
- 比率问题区分“直接读取比率指标”和“由两个指标计算比率”，避免把口径相近当成结果等价。

## 5. SQL 能力与错误分类

数据集必须标注并统计以下能力：

- 单点查询、时间/机构/指标过滤；
- 聚合、分组、跨机构比较；
- 多表 JOIN、子查询或 CTE；
- TopN、升降序排名和窗口函数；
- 同比、较上月、较上季、较年初、增量和增幅；
- 比率计算、阈值筛选、全省均值和表现较好/较差；
- 趋势和连续日期范围。

失败案例统一归入：

`MAPPING_ERROR`、`METRIC_DEFINITION_ERROR`、`JOIN_ERROR`、`FILTER_ERROR`、`SYNTAX_ERROR`、`EXECUTION_ERROR`。

额外保留 `RESULT_MISMATCH` 和 `CLARIFICATION_MISMATCH` 供 `QA-01` 统计，但不改变主计划约定的六类生成/执行错误。

## 6. 交付目录

新增 `evaluation/bank_nl2sql/`：

```text
README.md
schema.json
manifest.json
train.jsonl
dev.jsonl
test.jsonl
augmentation.jsonl
build_dataset.py
validate_dataset.py
evaluate_gold.py
db/schema.sql
db/build_database.py
tests/
```

生成的 H2/SQLite 数据库文件放在 `.local-dev/bank-nl2sql/`，不提交二进制数据库；提交生成脚本、schema、JSONL、manifest 和小型固定测试夹具。

现有 `evaluation` 的请求采集、SQL 结构评分和错误案例输出可复用，但不得复用其 DuSQL 表结构、样例 SQL 或默认 SQLite schema 假设。`bank_nl2sql` 通过适配器接入，保持原流程兼容。

## 7. 实施任务图

拆分理由：标准库、标注契约、金标 SQL 和评测适配是独立交付物，并存在严格的先后依赖；金标冻结后还需要独立验收，避免后续优化污染测试集。

Critical Path：`Task 1 → Task 2 → Task 3 → Task 4 → Task 5`。共享 schema、manifest 和 JSONL，全部串行实施；本任务不需要并行修改业务代码。

### Task 1：可复现银行基准库

- 状态：已完成（2026-07-23）。实际工作簿已生成 SQLite、H2 初始化脚本和 H2 文件库；校验通过 13 家机构、21 项指标、132,678 条事实、486 个连续日期和每天 273 条完整立方体。
- Owner/Boundary：`evaluation/bank_nl2sql/db`；只处理 Excel 到标准库的结构、加载和数据质量。
- Dependency：比赛工作簿和 BE-01 字段约定。
- Mode：`BDD_TDD`。
- Verification/Stop：三张表、132,678 条事实、486 个连续日期、联合键和外键校验全部通过；任一源数据不变量不满足即停止。

### Task 2：标注 schema 与划分冻结

- 状态：已完成（2026-07-23）。已生成 `schema.json`、`manifest.json` 和官方/增强 JSONL；官方题来源划分保持 train/dev/test = 120/40/40，模板隔离后的评测划分为 115/36/49，9 道调整均在 manifest 中可追溯，12 条增强题单独存放且三组官方题模板无重叠。
- Owner/Boundary：`schema.json`、基础 JSONL 生成器、manifest；不生成最终 SQL。
- Dependency：Task 1 的 schema 和 DATA-01 标注。
- Mode：`BDD_TDD`。
- Verification/Stop：`sourceSplit` 的 120/40/40 保持不变；模板隔离后的 `split` 为 115/36/49，ID 唯一，官方与增强样本隔离，`split` 的 train/dev/test 模板无泄漏。

### Task 3：S2SQL、物理 SQL 与标准结果

- 状态：物理 SQL 与结构化结果已完成（2026-07-23）。200 条 SQL 在 SQLite 和 H2 各执行 200 次均通过，结果已写入官方 JSONL，`gold_manifest.json` 记录基准库哈希。银行 H2 数据源与语义 Dataset 已在 SuperSonic 注册；运行时冒烟已验证“2026-03-31 各机构的各项存款余额，按余额从高到低取前三名”可解析为银行指标与机构维度并成功执行（查询阶段 203ms）。`s2sql` 当前仍以与物理 SQL 对应的可审计模板为主；全量正式 S2SQL 翻译验证尚未完成，不能据此宣称语义层全量验收已完成。
- Owner/Boundary：200 道官方题的金标生成、人工复核和执行结果；不调用待评测模型自动充当金标。
- Dependency：Task 1 标准库、Task 2 契约、BE-01 语义名称。
- Mode：`BDD_TDD`，先用代表性失败用例冻结每类口径，再批量生成。
- Verification/Stop：200 条 SQL 全部可解析、可执行并与工作簿答案一致；任何不一致进入 adjudication 清单，未裁决前不得冻结测试集。

### Task 4：数据冻结与防泄漏门禁

- 状态：已完成（2026-07-23）。`release_manifest.json` 固化 7 个数据产物哈希；`dataset_access.py` 默认训练范围为 train/dev，测试金标读取需要显式授权，增强样本不进入默认训练或盲测范围。
- Owner/Boundary：JSONL、manifest、哈希、金标访问边界。
- Dependency：Task 3 的全量执行报告。
- Mode：`SIMPLE`。
- Verification/Stop：重复构建哈希一致；测试金标不进入训练、提示词、示例检索或开发报告正文；冻结后修改必须提升数据集版本。

### Task 5：QA-01 评测适配

- 状态：基础实现及首次真实模型盲测已完成（2026-07-23）。`evaluate_predictions.py` 已实现只读 SQL 门禁、盲测结果比较和按难度/SQL 能力/错误类别统计；金标回放 49 条保留题的解析、执行和结果一致率均为 100%。局域网 `Qwen3.6-35B-A3B-UD-Q4_K_M.gguf` 在温度 0 的全量保留题盲测中生成 49/49 条预测，解析成功率 100%，执行成功率 91.8367%（45/49），结果一致率 0%（0/49）；该结果为失败基线，尚不能通过 QA-01 验收。`run_model_blind_eval.py` 确保模型请求只含题号、题目和 schema 元数据，预测和报告保存在 `.local-dev/bank-nl2sql/`。
- Owner/Boundary：`evaluate_gold.py` 和现有 `evaluation` 适配层；不实现 BE-05 优化。
- Dependency：Task 4 冻结数据。
- Mode：`BDD_TDD`。
- Verification/Stop：输出 SQL 解析率、执行成功率、结果一致率、按难度/能力/错误类别分布和耗时；等价 SQL 以执行结果为主，结构匹配为辅。

## 8. 验收标准

- 官方样本数、`sourceSplit`、问题文本和答案与源工作簿一致率 100%；`split` 的调整可由 manifest 逐题追溯且不存在模板泄漏。
- 金标 S2SQL/SQL 解析成功率 100%，在标准库执行成功率 100%。
- 金标执行结果与标准答案一致率 100%；容差、单位和排序规则必须显式记录。
- 单表、多表、嵌套/CTE、聚合、同比环比、TopN、窗口排名和跨机构查询均有可审计覆盖统计。
- 数据生成可重复，manifest 能追溯源文件和全部产物哈希。
- 错误报告能定位到样本 ID、阶段、错误类别、预测 SQL、金标 SQL 和结果差异。
- `BE-05` 可直接读取 train/dev；`QA-01` 可在不读取测试金标提示内容的情况下完成盲测评分。

## 9. 风险与决策

- **答案等价而 SQL 不同**：主指标使用执行结果一致率，SQL 结构匹配只作为诊断指标。
- **单位和舍入导致误判**：按指标单位配置 Decimal 精度与容差，禁止直接比较格式化字符串。
- **长表无法覆盖 JOIN**：使用事实表加机构/指标维表的标准星型模型，保留真实外键关联。
- **口径歧义**：以“衍生维度说明”为最高优先级；无法从源文件裁决的样本停止冻结并进入 adjudication。
- **测试集泄漏**：测试 SQL 和结果只由评测器读取，不进入模型请求、训练样本或 few-shot 示例。
- **旧评测器误用 DuSQL 假设**：新增适配层，不在原脚本中硬编码银行 schema。

回滚方式：DATA-02 只新增评测数据与脚本，不修改业务库；删除新增目录并移除主计划中的状态链接即可回到当前状态。生成数据库位于 `.local-dev`，可安全重新生成。

## 10. 完成定义

上述五个 Task 全部通过各自验证，且验收报告记录 200 道官方题的金标执行一致率、能力覆盖、错误分类和产物哈希后，DATA-02 才能标记“已完成”。仅生成 JSONL、仅生成 SQL 或仅跑通少量样例均不算完成。
