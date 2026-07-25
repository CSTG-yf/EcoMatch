# DATA-03 图表与业务解释数据集

## 数据内容

- `train.jsonl`、`dev.jsonl`、`test.jsonl`：各 30 条，共 90 条图表与解释标注。
- `business_questionnaire.jsonl`：30 条标准业务理解度问卷，满分 10 分，9 分通过。
- `schema.json`：样本字段协议。
- `manifest.json`：来源哈希、规模、图表类型和场景分布。
- `validation_report.json`：自动验收结果。

六类推荐图表为 `KPI_CARD`、`LINE`、`BAR`、`PIE`、`COMBO` 和 `TABLE`，每类 15 条。样本覆盖经营分析、风险管控和客户营销场景。

## 标注协议

每条样本包含真实查询结果及 SHA-256、数据结构画像、推荐图表、候选图表、不推荐原因和置信度。业务解释标注包含：

- 必须引用的真实数值和单位；
- 比赛指标字典中的指标定义与版本；
- 查询时间范围和机构范围；
- 必须出现的风险提示、禁止结论及低置信度策略；
- 可直接用于基线评测的参考解释。

饼图只用于同单位、正值且不超过 6 项的所选范围构成，并明确禁止将所选项占比解释为全量业务占比。

## 构建与验收

```bash
python evaluation/bank_chart_explanation/build_dataset.py
python evaluation/bank_chart_explanation/validate_dataset.py
```

校验器会验证来源文件哈希、训练集隔离、图表适配规则、结果哈希、解释数值落地、指标口径、时间与风险提示，以及问卷评分协议。报告中的 `ruleBaselineChartAccuracy` 和 `oracleBusinessUnderstandingScore` 是规则/标准答案基线，不代表模型实测成绩。
