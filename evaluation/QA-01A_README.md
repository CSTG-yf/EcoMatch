# QA-01A 统一评测执行器

`run_qa01a.py` 用一个命令统一执行以下五类验收：

1. 金融意图识别、指标集合识别和澄清判断；
2. 200 条银行 SQL 的解析与物理执行；
3. SQL 结果与冻结结构化金标的一致性；
4. 最近十轮上下文、操作识别、过期、截断和会话隔离；
5. 图表推荐准确率和业务解释覆盖率。

首次运行先安装评测依赖：

```powershell
python -m venv evaluation/.venv
evaluation/.venv/Scripts/python.exe -m pip install -r evaluation/requirements.txt
```

默认 `gold` 模式用于验证评测基础设施和冻结基线。它会校验 DATA-01/02/03，自动生成或复用本地 SQLite 基准库，执行 Java 冻结集测试，并将结果聚合到一个 JSON 报告。

```powershell
evaluation/.venv/Scripts/python.exe evaluation/run_qa01a.py `
  --workbook task/基于大模型与NL2SQL的银行业智能问数系统构建与应用_数据集.xlsx `
  --output .local-dev/bank-evaluation/qa01a-report.json
```

报告包含五个必需套件的指标、阈值、失败样本 ID/类别、各阶段退出码和耗时。任一命令失败、套件缺失或指标低于阈值时，执行器仍会尽量完成其他套件、写出诊断报告，并以退出码 `1` 结束。报告不保存 SQL、查询结果行、服务凭据或完整子进程输出。

## 预测文件模式

使用 `predictions` 模式评估真实模型生成的 `{"id", "sql"}` JSONL。此时 SQL 执行成功率和结果一致率使用预测结果，而金标 200 条重放仍作为基础设施基线执行。

```powershell
python evaluation/run_qa01a.py `
  --workbook <比赛数据集.xlsx> `
  --runtime-mode predictions `
  --predictions .local-dev/bank-nl2sql/predictions.jsonl
```

## SuperSonic 模式

`supersonic` 模式复用真实 `/openapi` 问数链路。开发阶段默认使用 `dev`；冻结 `test` 必须显式确认并提供本地运行登记文件。

```powershell
python evaluation/run_qa01a.py `
  --workbook <比赛数据集.xlsx> `
  --runtime-mode supersonic `
  --base-url http://127.0.0.1:9080 `
  --agent-id 33 `
  --split dev
```

CI 中可直接将脚本退出码作为发布门禁。阈值可通过 `--min-intent-accuracy`、`--min-execution-success-rate`、`--min-result-consistency-rate`、`--min-chart-accuracy` 等参数调整。
