# BE-03 金融意图识别与语义标准化实现说明

## 实现范围

- 21 项银行指标及其简称、口语别名和常见错别字标准化。
- 13 家机构全称以及 `A行`、`A市农商行`、`A农商行`等简称映射。
- 八类意图识别：单点查询、机构比较、排名、趋势、变化、比率、阈值和聚合。
- 经营分析、风险管控、客户营销三类业务场景识别。
- 指标、机构、绝对/相对时间、阈值、全省均值和排名条件抽取。
- 输出前三意图候选、指标候选、置信度及可审计的选择原因。
- 对宽泛指标、模糊/缺失时间、缺失机构和模糊统计条件返回结构化澄清选项。

## 查询接口

接口前缀：`/api/semantic/bank/intent`

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| POST | `/recognize` | 单问题识别与标准化 |
| POST | `/recognize/batch` | 批量识别，用于评测和离线标注 |

请求示例：

```json
{
  "queryText": "帮我看下A行今年的不良货款率",
  "referenceDate": "2026-07-22"
}
```

响应包含 `normalizedText`、`scene`、`intent`、`intentCandidates`、`metrics`、`organizations`、`time`、`filters`、`confidence`、`reasons` 和 `clarifications`。

## NL2SQL 接入

`BankFinancialMapper` 已注册为首个 `SchemaMapper` SPI，在通用 Embedding/Keyword Mapper 前执行：

1. 标准化机构简称、指标简称和错别字。
2. 将标准指标编码匹配到现有 Metric `bizName`。
3. 将机构编码匹配到 `bank_organization` 维度值。
4. 将完整识别结果保存到 `ChatQueryContext.bankIntentResult`，供后续解析、上下文和前端解释使用。

## 验收结果

冻结测试集 52 条，Java 21 下实测：

| 指标 | 结果 | 门槛 |
| --- | ---: | ---: |
| 意图准确率 | 98.08% | ≥94% |
| 指标集合准确率 | 100% | ≥94% |
| 澄清判断准确率 | 100% | ≥90% |

自动测试同时覆盖规范词不重复扩展、机构简称、错别字、相对时间、宽泛指标澄清和 Mapper 语义元素写入。
