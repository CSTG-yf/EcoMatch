# DATA-02 银行业 NL2SQL 标注数据集

## 数据内容

本目录从比赛 Excel 的 13 家机构、21 项指标和 132,678 条事实数据生成可执行 NL2SQL 样本。每条正样本包含问题、标准化意图、S2SQL、SQLite 物理 SQL、复杂度标签、预期结果摘要和完整结果哈希。

- `train.jsonl`、`dev.jsonl`、`test.jsonl`：96 条正样本，三个分片各 32 条。
- `error_cases.jsonl`：96 条错误样本，六类错误各 16 条。
- `schema.sql`：由事实表、机构维表和指标维表组成的标准星型样例库。
- `schema.json`：正样本的机器可读字段协议。
- `manifest.json`：来源哈希、数量、覆盖率和执行统计。
- `validation_report.json`：重新执行 SQL 后的验收结果。

## 覆盖范围

三个分片使用不同问法模板，模板组无交集。整体覆盖单表、多表、嵌套、聚合、同比、环比、TopN 和跨机构查询。错误样本覆盖映射错误、口径错误、关联错误、过滤错误、语法错误和执行错误。

S2SQL 使用 BE-01 导入后的语义数据集 `bank_indicator_dataset`、维度业务名 `bank_data_date`/`bank_organization` 和指标业务名 `zb001` 至 `zb021`。物理 SQL 使用比赛长表映射 `bank_indicator_fact(data_date, organization_code, metric_code, metric_value)`。

## 复现与校验

```bash
python evaluation/bank_nl2sql/build_dataset.py
python evaluation/bank_nl2sql/validate_dataset.py
```

如需生成本地标准样例数据库：

```bash
python evaluation/bank_nl2sql/build_dataset.py --database evaluation/bank_nl2sql/bank_sample.sqlite
```

SQLite 文件为本地衍生物，不提交仓库。生成和校验依赖 `openpyxl`，其版本要求沿用 `evaluation/requirements.txt`。
