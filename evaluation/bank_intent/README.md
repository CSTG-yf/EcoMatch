# DATA-01 金融意图数据集

## 数据构成

数据集由比赛工作簿的 200 条问题和 12 条定向增强问题组成。增强问题覆盖机构简称、银行术语简称、口语、错别字、相对/模糊时间和歧义条件。

| split | 样本数 | 模板数 | 用途 |
| --- | ---: | ---: | --- |
| train | 121 | 72 | 规则和模型开发 |
| dev | 39 | 27 | 参数与阈值选择 |
| test | 52 | 32 | 冻结验收，不参与规则调整 |

源工作簿原始标签为训练 120、验证 40、测试 40。生成器按标准化模板指纹检查泄漏，并将 9 条重合模板样本整组迁移到同一 split；最终三个 split 的模板交集为 0。

## 标注协议

- `scene`：`OPERATION_ANALYSIS`、`RISK_CONTROL`、`CUSTOMER_MARKETING`。
- `intent`：单点查询、机构比较、排名、趋势、变化、比率、阈值和聚合八类。
- `metrics`：指标编码、标准名称和原问题命中词。
- `dimensions`：日期和机构标准维度。
- `time`、`organizations`、`filters`：时间、机构和统计条件槽位。
- `linguisticFeatures`：标准表达、简称、口语、错别字、模糊时间和歧义。
- `clarificationExpected`：问题是否应进入澄清流程。
- `templateGroup`：去除机构、指标、日期和数值后的模板指纹。

字段约束见 `schema.json`，来源校验和统计见 `manifest.json`。

## 重建与校验

```bash
python evaluation/bank_intent/build_dataset.py \
  task/基于大模型与NL2SQL的银行业智能问数系统构建与应用_数据集.xlsx
python evaluation/bank_intent/validate_dataset.py
```

冻结集由 `BankIntentFrozenDatasetTest` 直接读取。当前结果：意图准确率 98.08%、指标集合准确率 100%、澄清判断准确率 100%，详见 `evaluation_report.json`。
