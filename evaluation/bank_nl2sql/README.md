# 银行业 NL2SQL 基准数据

此目录包含 DATA-02 的可复现产物：

- `db/build_database.py`：将比赛工作簿转换为标准基准库；
- `build_dataset.py`：冻结官方题、意图标注和模板隔离后的评测切分；
- `train.jsonl`、`dev.jsonl`、`test.jsonl`：200 道官方题；
- `augmentation.jsonl`：12 条隔离增强题，禁止参与官方评分；
- `manifest.json`：源工作簿哈希、切分数量和逐题调整记录；
- `schema.json`：JSONL 字段契约。

`db/build_database.py` 将比赛工作簿转换为可重复生成的标准基准库：

- `bank_organization`：机构维表；
- `bank_metric_definition`：指标与单位维表；
- `bank_metric_daily`：按日期、机构、指标唯一的事实表。

## 生成标准库

```powershell
evaluation\.venv\Scripts\python.exe evaluation/bank_nl2sql/db/build_database.py <workbook.xlsx> `
  --sqlite-output .local-dev/bank-nl2sql/bank_benchmark.sqlite `
  --h2-script-output .local-dev/bank-nl2sql/bank_benchmark_h2.sql
```

需要生成本地 H2 文件库时，再传入 `--h2-database-output`、`--java-path` 和 `--h2-jar-path`。生成的数据库文件只放在 `.local-dev`，不提交二进制产物。

## 校验标准库

```powershell
evaluation\.venv\Scripts\python.exe evaluation/bank_nl2sql/db/validate_database.py `
  .local-dev/bank-nl2sql/bank_benchmark.sqlite
```

校验器检查机构、指标和事实表数量，联合键、外键、完整日期序列以及每天完整的 `机构 × 指标` 立方体。

## 构建标注数据集

```powershell
evaluation\.venv\Scripts\python.exe evaluation/bank_nl2sql/build_dataset.py <workbook.xlsx> `
  --intent-root evaluation/bank_intent `
  --output evaluation/bank_nl2sql

evaluation\.venv\Scripts\python.exe evaluation/bank_nl2sql/validate_dataset.py `
  evaluation/bank_nl2sql
```

当前冻结版本基于源文件 SHA-256 `c3b810a4938fefc77a5c834c4c6857bec7f67162c4160abd9f66d9dd6018703c`：官方题来源划分为 train/dev/test = 120/40/40，模板隔离后的实际评测划分为 115/36/49。9 道题的调整可在 `manifest.json` 中逐题追溯，三个官方评测切分之间没有模板重叠。

## 生成并验证金标

```powershell
evaluation\.venv\Scripts\python.exe evaluation/bank_nl2sql/build_gold.py `
  evaluation/bank_nl2sql .local-dev/bank-nl2sql/bank_benchmark.sqlite

evaluation\.venv\Scripts\python.exe evaluation/bank_nl2sql/validate_gold.py `
  evaluation/bank_nl2sql .local-dev/bank-nl2sql/bank_benchmark.sqlite
```

`gold_manifest.json` 记录金标依赖的 SQLite 文件哈希。物理 SQL 已在 SQLite 与 H2 各执行 200 次且全部通过；`s2sql` 目前保存与物理 SQL 对应的可审计 SQL 模板。将其交给 SuperSonic 的正式语义翻译器前，需要先在运行环境注册银行 H2 数据源及语义 Dataset。

## 冻结与盲测

```powershell
evaluation\.venv\Scripts\python.exe evaluation/bank_nl2sql/freeze_dataset.py `
  evaluation/bank_nl2sql .local-dev/bank-nl2sql/bank_benchmark.sqlite

evaluation\.venv\Scripts\python.exe evaluation/bank_nl2sql/evaluate_predictions.py `
  evaluation/bank_nl2sql <predictions.jsonl> .local-dev/bank-nl2sql/bank_benchmark.sqlite `
  --report .local-dev/bank-nl2sql/evaluation-report.json
```

预测文件每行只含 `id` 与 `sql`。评测器只读取 `test.jsonl` 作为评测金标，拒绝任何写 SQL，并报告解析成功率、执行成功率、结果一致率、难度与 SQL 能力分布。代码中的 `dataset_access.load_records(..., purpose="training")` 只会返回 train/dev，读取测试金标必须显式传入 `allow_test_gold=True`。
