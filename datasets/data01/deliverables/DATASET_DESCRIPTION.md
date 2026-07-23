# DATA-01 问题—意图数据集说明

## 1. 交付内容

本目录是 DATA-01 的最终交付目录，只包含：

1. `data01.question-intent.v1.jsonl`：问题、意图、完整语义帧和联调预期响应。
2. `DATASET_DESCRIPTION.md`：本说明文件。

数据集格式为 JSONL，每行一条独立 JSON 记录，UTF-8 编码、LF 换行。

## 2. 用途和状态

本数据集用于银行自然语言问数系统的：

- 主意图识别；
- 指标、机构、时间、过滤、排序和计算槽位解析；
- 对话澄清判断；
- 语义解析到 EcoMatch 查询结构的联调；
- 官方训练、验证、测试口径下的离线评测。

当前状态为“维护者确认候选数据”，不是人工双人仲裁金标。官方 Excel 的公开再分发许可未确认，因此当前只允许内部使用，不应直接公开发布。

## 3. 数据来源与完整性

- 官方源文件：`基于大模型与NL2SQL的银行业智能问数系统构建与应用_数据集.xlsx`
- 官方源文件 SHA-256：`C3B810A4938FEFC77A5C834C4C6857BEC7F67162C4160ABD9F66D9DD6018703C`
- 最终数据集 SHA-256：`E1EDD2AF9883CF51A7486BF33DB0AC9B7DBBA69138F9B88B8F7CF785859188D0`
- 官方问题数：200
- 指标事实数：132,678
- 事实日期范围：2024-12-31 至 2026-04-30

## 4. 数据分布

### 官方划分

| 值 | 数量 |
| --- | ---: |
| `train` | 120 |
| `validation` | 40 |
| `test` | 40 |

数据严格使用官方 120/40/40 划分，没有自定义重分区。跨官方分区的近似模板仍作为评测风险保留在质量报告中。

### 难度

| 值 | 数量 |
| --- | ---: |
| `simple` | 40 |
| `medium` | 100 |
| `complex` | 60 |

### 主意图

| 值 | 数量 |
| --- | ---: |
| `METRIC_QUERY` | 46 |
| `CLARIFICATION_REQUIRED` | 1 |
| `RANKING` | 46 |
| `COMPARISON` | 64 |
| `CONDITION_FILTER` | 12 |
| `TREND` | 13 |
| `PROFILE_ANALYSIS` | 18 |

### 业务场景

| 值 | 数量 |
| --- | ---: |
| `OPERATIONS_MANAGEMENT` | 144 |
| `RISK_CONTROL` | 54 |
| `CUSTOMER_MARKETING` | 2 |

### 预期响应

| 值 | 数量 |
| --- | ---: |
| `ANSWER` | 198 |
| `CLARIFY` | 1 |
| `DATA_UNAVAILABLE` | 1 |

## 5. 单条记录示例

```json
{
  "schema_version": "data01.question-intent.v1",
  "case_id": "TRAIN-S-01",
  "split": "train",
  "difficulty": "simple",
  "question": "江苏省A市农商行在2025年6月15日，各项存款余额是多少？",
  "intent": "METRIC_QUERY",
  "scenario": "OPERATIONS_MANAGEMENT",
  "semantic_frame": {
    "query_type": "AGGREGATE",
    "dataset_biz_name": "bank_indicator_dataset",
    "operations": [
      "SELECT_METRIC"
    ],
    "metrics": [
      {
        "code": "ZB001",
        "biz_name": "zb001",
        "roles": [
          "TARGET"
        ]
      }
    ],
    "organizations": [
      {
        "code": "ORG001",
        "name": "江苏省A市农商行"
      }
    ],
    "time": {
      "mode": "POINT",
      "raw": "2025年6月15日",
      "target_date": "2025-06-15",
      "start_date": null,
      "end_date": null,
      "grain": "DAY",
      "comparisons": []
    },
    "result_shape": "SCALAR",
    "needs_clarification": false
  },
  "expected_response": {
    "outcome": "ANSWER",
    "text": "42.02亿元",
    "eligible_for_nl2sql_answer": true,
    "derivation": "OFFICIAL_AS_IS"
  },
  "provenance": {
    "source_type": "OFFICIAL_EXCEL",
    "workbook_sha256": "C3B810A4938FEFC77A5C834C4C6857BEC7F67162C4160ABD9F66D9DD6018703C",
    "sheet": "问题答案清单",
    "row": 2,
    "label_assurance": "MAINTAINER_CONFIRMED_CANDIDATE_NOT_HUMAN_ADJUDICATED"
  }
}
```

## 6. 顶层字段

| 字段 | 类型 | 是否必填 | 来源 | 说明 |
| --- | --- | --- | --- | --- |
| `schema_version` | string | 是 | DATA-01设计 | 固定为 `data01.question-intent.v1`。 |
| `case_id` | string | 是 | DATA-01派生 | 根据官方划分、难度和题目顺序生成的稳定编号。 |
| `split` | enum | 是 | 官方Excel | `train`、`validation`、`test`。 |
| `difficulty` | enum | 是 | 官方Excel | `simple`、`medium`、`complex`。 |
| `question` | string | 是 | 官方Excel | 用户自然语言问题原文。 |
| `intent` | enum | 是 | DATA-01设计 | 面向对话编排层的主意图标签。 |
| `scenario` | enum | 是 | DATA-01设计 | 经营分析、风险管控或客户营销场景。 |
| `semantic_frame` | object | 是 | 混合来源 | 完整语义解析目标，字段对齐项目查询结构，扩展操作由DATA-01定义。 |
| `expected_response` | object | 是 | 官方答案/事实重算/维护者政策 | 系统联调的预期响应，不是意图分类模型的必选训练标签。 |
| `provenance` | object | 是 | 官方Excel与DATA-01流程 | 源文件、工作表、行号及标签可信状态。 |

## 7. `semantic_frame` 字段

| 字段 | 来源 | 说明 |
| --- | --- | --- |
| `primary_intent` | DATA-01设计 | 与顶层 `intent` 相同，保留完整语义帧自包含性。 |
| `operations` | DATA-01设计，语义依据来自官方规则 | 查询、比较、聚合、排名等可组合操作。 |
| `query_type` | EcoMatch项目原生 | `AGGREGATE`、`DETAIL` 或 `null`。项目原生 `QueryType` 只定义前两项。 |
| `dataset_biz_name` | EcoMatch银行语义导入模块 | 固定为 `bank_indicator_dataset`。 |
| `metrics` | 官方指标目录 + DATA-01角色 | 指标编码和业务名来自官方；角色枚举由DATA-01定义。 |
| `dimensions` | EcoMatch银行语义导入模块 | `bank_data_date`、`bank_organization`、`bank_indicator`。 |
| `organizations` | 官方机构目录 | 机构编码及名称。 |
| `time` | 官方衍生规则 + DATA-01结构化 | 目标日期、范围、时间粒度和比较基线。 |
| `filters` | EcoMatch查询结构对齐 | 字段、操作符和值。 |
| `orders` | EcoMatch查询结构对齐 + DATA-01语义扩展 | 排序字段、方向以及按数值或业务表现排序。 |
| `limit` | EcoMatch查询结构对齐 | 返回行数或排名阈值。 |
| `calculations` | DATA-01设计，公式依据来自官方规则 | 比例、差额、百分点、增幅、排名、计数等结构化计算。 |
| `result_shape` | DATA-01设计 | 预期结果形态。 |
| `needs_clarification` | DATA-01设计 | 是否需要对话层澄清。 |
| `clarification` | DATA-01设计 | 歧义槽位、原因、候选解释和澄清问题。 |
| `runtime_support` | DATA-01设计 | 当前运行层对该语义帧的支持程度。 |

## 8. 枚举说明

### 主意图 `intent`

| 枚举值 | 含义 |
| --- | --- |
| `METRIC_QUERY` | 查询一个时点或期间的一个或多个指标或派生结果 |
| `COMPARISON` | 比较机构、时间基线、全省均值或监管阈值 |
| `RANKING` | 查询名次、前后若干名、最好最差或名次变化 |
| `TREND` | 查询时间序列、阶段变化方向及极值 |
| `CONDITION_FILTER` | 筛选或统计满足一个或多个条件的机构或日期 |
| `PROFILE_ANALYSIS` | 围绕经营、风险或盈利主题输出多指标综合画像 |
| `DRILL_DOWN` | 沿产品、客户、地区等维度继续下钻 |
| `CLARIFICATION_REQUIRED` | 缺少必要信息或存在多个合法解释，需要用户澄清 |
| `OUT_OF_SCOPE` | 问题不属于当前银行问数语义范围 |

当前官方200题实际出现7类；`DRILL_DOWN` 和 `OUT_OF_SCOPE` 已定义但没有官方样本，不能用本数据集证明这两类的识别能力。

### 业务场景 `scenario`

| 枚举值 | 含义 |
| --- | --- |
| `OPERATIONS_MANAGEMENT` | 经营分析：规模、收入、成本、利润、人员、网点及综合经营表现 |
| `RISK_CONTROL` | 风险管控：不良、逾期、拨备、资本充足及监管阈值 |
| `CUSTOMER_MARKETING` | 客户营销：个人或对公客户规模、结构、分群及营销分析 |

### 操作 `operations`

| 枚举值 | 含义 |
| --- | --- |
| `SELECT_METRIC` | 查询指标值 |
| `SUM` | 求和或合计 |
| `AVG` | 期间或分组均值 |
| `COUNT` | 计数 |
| `MAX` | 最大值 |
| `MIN` | 最小值 |
| `RATIO` | 两个或多个指标构成比例 |
| `ABSOLUTE_DELTA` | 目标值减比较值的原单位差额，保留上升或下降方向 |
| `PERCENTAGE_POINT_DELTA` | 目标百分比减比较百分比的百分点差，保留方向 |
| `GROWTH_RATE` | 相对基线的增幅或降幅 |
| `COMPARE_ENTITY` | 机构之间比较 |
| `COMPARE_BASELINE` | 目标日期和基线日期比较 |
| `COMPARE_PROVINCE_MEAN` | 与13家机构当日算术平均值比较 |
| `THRESHOLD_CHECK` | 与显式或监管阈值比较 |
| `MOM` | 较上月月末 |
| `QOQ` | 较上季度末 |
| `YOY` | 较去年同期 |
| `YEAR_BEGIN` | 较官方定义的年初基线 |
| `GROUP_BY_ORGANIZATION` | 按机构分组 |
| `GROUP_BY_TIME` | 按时间粒度分组 |
| `RANK` | 计算名次 |
| `TOP_N` | 取前N名 |
| `BOTTOM_N` | 取后N名 |
| `RANK_CHANGE` | 比较两个时点的名次 |
| `TIME_SERIES` | 返回多个时点的序列 |
| `PERFORMANCE_BUCKET` | 按表现较好或较差规则分类 |
| `COMPOSITE_PROFILE` | 输出预定义的多指标主题画像 |
| `MULTI_CONDITION_FILTER` | 同时应用多个筛选条件 |

### 结果形态 `result_shape`

| 枚举值 | 含义 |
| --- | --- |
| `SCALAR` | 单个数值 |
| `BOOLEAN` | 是否满足条件 |
| `COUNT` | 机构数或日期数 |
| `ENTITY` | 单个机构及其指标 |
| `ENTITY_LIST` | 多个机构及其指标 |
| `METRIC_LIST` | 同一机构的多个指标 |
| `TIME_SERIES` | 按时间排列的指标序列 |
| `PROFILE` | 多指标、多比较或多结论的综合结果 |
| `CLARIFICATION` | 返回澄清问题和候选解释，不执行查询 |
| `NO_RESULT` | 超范围问题，不产生查询结果 |

### 运行支持 `runtime_support`

| 枚举值 | 含义 |
| --- | --- |
| `SUPPORTED_SEMANTIC` | 现有语义指标和日期、机构维度可直接表达 |
| `DERIVED_EXPRESSION_REQUIRED` | 需要比例、差值、增幅或其他派生表达式 |
| `MULTI_STEP_ANALYSIS_REQUIRED` | 需要多次聚合、排名、比较或综合分析 |
| `SCHEMA_PENDING` | 当前模型缺少完成该问题所需的维度或实体 |
| `NOT_APPLICABLE` | 需要澄清或超范围，尚不进入查询执行 |

### 其他结构枚举

| 字段 | 枚举值 |
| --- | --- |
| `query_type` | `AGGREGATE`、`DETAIL`、`null` |
| 指标角色 | `TARGET`、`NUMERATOR`、`DENOMINATOR`、`MINUEND`、`SUBTRAHEND`、`FILTER`、`ORDER`、`PROFILE` |
| 时间模式 | `POINT`、`RANGE`、`SERIES`、`COMPARISON` |
| 时间粒度 | `DAY`、`MONTH`、`QUARTER`、`YEAR`、`null` |
| 比较基线 | `YEAR_BEGIN`、`PREVIOUS_MONTH`、`PREVIOUS_QUARTER`、`SAME_PERIOD_LAST_YEAR`、`CUSTOM` |
| 过滤操作符 | `=`、`!=`、`>`、`>=`、`<`、`<=`、`IN`、`BETWEEN` |
| 排序方向 | `ASC`、`DESC` |
| 排序语义 | `VALUE`、`PERFORMANCE` |
| 响应结果 | `ANSWER`、`CLARIFY`、`DATA_UNAVAILABLE` |

## 9. 字段来源依赖

### 官方Excel直接提供

- 问题、官方答案、难度、训练/验证/测试划分；
- 13家机构的编码和名称；
- 21项指标的编码、名称、业务名、说明和单位；
- 132,678条指标事实；
- 较年初、环比、同比、全省均值、排名、增量、增幅、前三和后四等衍生规则。

### EcoMatch项目代码提供

- 数据集业务名 `bank_indicator_dataset`；
- 维度业务名 `bank_data_date`、`bank_organization`、`bank_indicator`；
- `QueryType.AGGREGATE/DETAIL`；
- `metrics`、`dimensions`、`filters`、`orders`、`limit`、`dateInfo` 等查询结构。

### DATA-01新增设计

- `intent` 主意图体系和 `scenario` 场景体系；
- 可组合 `operations`；
- 指标角色、结果形态、澄清结构和运行支持状态；
- 审计溯源、候选答案状态和质量门禁。

因此，`intent` 不是 EcoMatch 当前代码中的原生枚举，而是对话编排和评测层协议；它必须通过适配器转换为项目查询对象。

## 10. 到EcoMatch运行结构的映射

| DATA-01字段 | EcoMatch目标 | 处理方式 |
| --- | --- | --- |
| `semantic_frame.query_type` | `QueryType` | `AGGREGATE/DETAIL`直接映射。 |
| `semantic_frame.metrics` | `SemanticParseInfo.metrics` / `QueryStructReq.aggregators` | 使用指标 `biz_name`。 |
| `semantic_frame.dimensions` | `SemanticParseInfo.dimensions` / `QueryStructReq.groups` | 使用维度业务名。 |
| `semantic_frame.filters` | `dimensionFilters` 或 `metricFilters` | 按字段类型拆分。 |
| `semantic_frame.orders` | `orders` | 映射字段和 `ASC/DESC`。 |
| `semantic_frame.time` | `dateInfo` | 转换目标日期、范围和比较基线。 |
| `semantic_frame.limit` | `limit` | 普通查询作为行数；排名查询按执行策略处理并列。 |
| `intent=CLARIFICATION_REQUIRED` | 对话编排层 | 不创建执行请求，直接返回澄清。 |
| 复合计算、排名和画像 | 多步执行层 | 不能直接压缩成单个复合 `ORDER BY`；各排名分支独立执行。 |

## 11. 预期响应

`expected_response` 包含：

- `outcome`：回答、澄清或数据不可用；
- `text`：维护者确认的候选响应；
- `eligible_for_nl2sql_answer`：是否可以进入执行和答案联调；
- `derivation`：沿用官方、官方事实重算、政策决策或源数据缺失响应。

200条中171条沿用官方答案，29条使用独立候选结果；官方错误答案没有被覆盖，完整官方快照保留在审计母数据中。

## 12. 已确认政策

- “大不大”没有阈值时返回公式结果并说明无法判定高低；
- 盈利画像“较年初变化”只作用于净利润；
- 未列明范围的表现评价采用净利润、成本收入比、不良贷款率、拨备覆盖率四项；
- 主要经营指标采用8个直接指标加存贷比，共9项独立排名；
- 前三为表现较好、后四为表现较差，排序遵循官方业务方向；
- 排名使用 `RANK WITH TIES`，边界并列全部保留；
- 缺少官方事实基线时返回数据不可用，不猜测数值。

## 13. 已知限制

- 客户营销场景只有2题，覆盖不足；
- 没有官方 `DRILL_DOWN` 和 `OUT_OF_SCOPE` 样本；
- 官方数据以完整、规范正例为主，对错别字、口语、省略和多轮表达覆盖不足；
- 官方划分存在跨分区近似模板，评测结果应披露该风险；
- `TRAIN-M-13` 缺少2024-11-30同比基线；
- 该候选层没有业务双人复核，不能声明为正式金标；
- 官方Excel的公开再分发许可尚未确认。

## 14. 使用建议

- 意图分类训练：使用 `question` 作为输入，`intent` 作为标签；
- 联合语义解析：使用 `question` 作为输入，`semantic_frame` 作为结构化目标；
- 系统联调：同时使用 `semantic_frame` 和 `expected_response`；
- 评测时严格按 `split` 划分，不得将验证或测试题放入训练、Few-shot、记忆库或规则样例。

## 15. 重建与验证

```powershell
python datasets/data01/scripts/export_question_intent_dataset.py `
  --data01-dir datasets/data01 `
  --dataset-output datasets/data01/deliverables/data01.question-intent.v1.jsonl `
  --description-output datasets/data01/deliverables/DATASET_DESCRIPTION.md

python datasets/data01/scripts/validate_official.py `
  --official-dir datasets/data01/official `
  --repo-root . `
  --output datasets/data01/official/validation_report.json

python -m unittest datasets/data01/scripts/test_official_pipeline.py -v
```
