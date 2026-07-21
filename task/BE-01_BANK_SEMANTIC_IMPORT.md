# BE-01 银行语义资源导入框架

## 1. 实现范围

后端新增银行语义资源 Excel 导入模块，直接支持赛题数据集的五张工作表：

| 工作表 | 用途 | 导入结果 |
| --- | --- | --- |
| 机构信息表 | 机构编号、机构名称 | 机构维度值映射、Knowledge 字典配置 |
| 指标清单表 | 指标编号、名称、含义、单位 | Metric、指标维度值映射、关联术语 |
| 衍生维度说明 | 同比、环比、排名等衍生口径 | Term 术语知识 |
| 指标数据表 | 日期、指标、机构、指标值 | 导入前完整性校验，不写入 SuperSonic 元数据库 |
| 问题答案清单 | 训练、验证、测试问题及答案 | 数据集质量统计，不作为 Text2SQL SQL 样本写入 |

原始事实数据应先落入业务数据库，并在 SuperSonic 中建立对应语义模型。导入器负责将工作簿资源幂等转换为现有 `Dimension`、`Metric`、`Knowledge`、`Term` 和 `Dataset` 对象。

## 2. 赛题数据核验基线

| 项目 | 结果 |
| --- | ---: |
| 机构 | 13 |
| 指标 | 21 |
| 衍生口径 | 10 |
| 日期范围 | 2024-12-31 至 2026-04-30 |
| 日期数 | 486 |
| 指标事实 | 132,678（13 × 21 × 486） |
| 问答样本 | 200（训练 120、验证 40、测试 40） |
| 难度分布 | 简单 40、普通 100、复杂 60 |

当前数据不存在空值、事实联合键重复、无效机构/指标引用或指标名称错配。

## 3. 接口

### 3.1 仅校验

`POST /api/semantic/bank/resources/validate`

```bash
curl -X POST "http://localhost:9080/api/semantic/bank/resources/validate" \
  -F "file=@基于大模型与NL2SQL的银行业智能问数系统构建与应用_数据集.xlsx"
```

该接口不访问语义模型，也不写入数据库。报告包含 SHA-256、各类资源数量、日期范围、问题分布和逐行错误。

### 3.2 导入语义资源

`POST /api/semantic/bank/resources/import`

```bash
curl -X POST "http://localhost:9080/api/semantic/bank/resources/import" \
  -F "file=@基于大模型与NL2SQL的银行业智能问数系统构建与应用_数据集.xlsx" \
  -F "modelId=100" \
  -F "dataSetName=银行业智能问数数据集" \
  -F "dataSetBizName=bank_indicator_dataset" \
  -F "dateField=data_date" \
  -F "organizationField=organization_code" \
  -F "indicatorCodeField=metric_code" \
  -F "indicatorValueField=metric_value"
```

目标模型必须包含四个映射字段。字段名只允许字母、数字和下划线；导入前会验证字段是否存在，防止生成不可执行的指标表达式。

## 4. 转换规则

固定创建或更新三个维度：

- `bank_data_date`：日期分区维度。
- `bank_organization`：机构维度，机构编号和名称写入 `dimValueMaps`。
- `bank_indicator`：指标维度，指标编号和名称写入 `dimValueMaps`。

每个指标按指标编号生成稳定业务键，例如 `ZB001` 对应 `zb001`，指标表达式为：

```sql
SUM(CASE WHEN metric_code = 'ZB001' THEN metric_value ELSE 0 END)
```

指标编号和单位保存到 `ext`，指标关联日期、机构两个下钻维度。机构和指标维度自动启用 Knowledge 字典配置；指标口径和衍生口径写入术语库；Dataset 关联本次导入的维度和指标。

## 5. 幂等、回滚和错误报告

- Dimension、Metric、Term、Dataset 均按稳定业务键查重。
- 内容未变化时计入 `skipped`，内容变化时增量更新，不重复新增。
- Excel 结构或数据质量不通过时直接返回错误，不产生写入。
- 语义对象写入运行在同一 Spring 事务中；任一步失败会返回 `IMPORT_ROLLED_BACK` 并回滚整批数据库变更。
- 错误包含 `sheet`、`row`、`column`、`code`、`message` 和截断后的问题值，最多返回 500 条。
- 服务日志记录文件名、SHA-256、目标模型和创建统计，不记录事实明细。

## 6. 校验规则

- 五张工作表和表头必须完整匹配。
- 机构编号/名称、指标编号/名称、衍生口径、问题编号不可重复。
- 所有必填单元格非空，日期符合 `yyyy-MM-dd`，指标值为数值。
- 指标事实引用的机构和指标必须存在，指标编号与名称必须一致。
- `数据日期 + 指标编号 + 机构编号` 联合键不可重复。
- 指标事实必须构成完整的“日期 × 指标 × 机构”数据立方体。
- 单次最多导入 3,000 项指标，覆盖 BE-01 的 300 项验收容量。

## 7. 测试

```bash
mvn -pl headless/server -am \
  "-Dtest=BankWorkbookParserTest" \
  "-Dsurefire.failIfNoSpecifiedTests=false" \
  "-Dspotless.skip=true" \
  "-Dbank.dataset.path=F:/money/supersonic/task/基于大模型与NL2SQL的银行业智能问数系统构建与应用_数据集.xlsx" \
  test
```

测试覆盖标准工作簿解析、重复事实行定位，以及真实赛题数据集的全量数量和日期范围回归。
