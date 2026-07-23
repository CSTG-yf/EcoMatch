# DATA-01 金融意图数据集交付说明

## 交付物

- `evaluation/bank_intent/train.jsonl`：训练集 121 条。
- `evaluation/bank_intent/dev.jsonl`：开发集 39 条。
- `evaluation/bank_intent/test.jsonl`：冻结测试集 52 条。
- `evaluation/bank_intent/schema.json`：标注字段协议。
- `evaluation/bank_intent/manifest.json`：来源 SHA-256、分布和模板隔离统计。
- `evaluation/bank_intent/build_dataset.py`：从比赛 Excel 可重复生成数据集。
- `evaluation/bank_intent/validate_dataset.py`：结构、重复 ID、模板泄漏和测试集容量校验。
- `evaluation/bank_intent/evaluation_report.json`：BE-03 冻结集结果。

## 覆盖情况

数据覆盖经营分析、风险管控和客户营销，包含标准表达、简称、口语、错别字、模糊时间和歧义问题。每条样本均标注意图、指标、日期维度、机构维度、时间、机构、筛选条件、语言特征和是否需要澄清。

三个 split 按标准化问题模板隔离，模板交集为 0。比赛原始答案作为 `answer` 保留，便于后续 DATA-02 和 QA-01 复用。
