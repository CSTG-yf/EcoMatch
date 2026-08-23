# 360 项合成事实库

本目录是 360 项候选指标的隔离测试数据域，用于验证指标泛化后的 SQL 生成、实际执行和结构化结果对齐。

- `dataOrigin` 固定为 `SYNTHETIC`，机构 `SYN-ORG-001` 至 `SYN-ORG-013` 均为虚拟机构。
- 事实日期为 2025-01-31 至 2026-05-31 的 17 个自然月末。
- 指标定义来自 `evaluation/bank_metric_catalog` 的候选目录；候选目录仍需银行业务专家复核。
- 派生指标只按目录中的 `formula` 计算，不能把派生值当作独立随机事实。
- 本目录不得写入官方 21 项成绩，也不得用于声称真实银行经营结果。

## 生成与校验

```powershell
python evaluation/bank_nl2sql/synthetic_360/build_synthetic_facts.py `
  --catalog-dir evaluation/bank_metric_catalog/releases/0.1.0-candidate `
  --output-dir evaluation/bank_nl2sql/synthetic_360/releases/0.1.0-synthetic

python evaluation/bank_nl2sql/synthetic_360/validate_synthetic_facts.py `
  --release-dir evaluation/bank_nl2sql/synthetic_360/releases/0.1.0-synthetic
```

预期规模为 360 项指标、13 家虚拟机构、17 个日期和 79,560 条事实。SQLite 仅供本地测试使用；没有接入真实银行生产库。

## 运行时导入与数据库绑定

`bank-h2.sql` 必须先由部署者加载到一个独立的 H2 数据库；导入脚本不会覆盖或复制
SuperSonic 的物理数据库。随后确认已有语义模型的 `modelId` 确实绑定该数据库，运行：

```powershell
python evaluation/bank_nl2sql/synthetic_360/bootstrap_synthetic_agent.py `
  evaluation/bank_nl2sql/synthetic_360/releases/0.1.0-synthetic `
  --model-id <SYNTHETIC_MODEL_ID> --database-id <SYNTHETIC_DATABASE_ID> `
  --chat-model-id <CHAT_MODEL_ID> --base-url http://127.0.0.1:9080 `
  --output .local-dev/synthetic-360-runtime-receipt.json
```

该命令会校验模型与数据库绑定，生成并导入 360 指标语义工作簿，创建/更新独立的
`银行问数-SYNTHETIC-360` Agent，并输出不含凭据的运行回执。回执只证明导入计数和
绑定元数据，不把合成数据当成官方成绩。

## Agent 评测入口

`run_eval.py` 复用工程中已有的 SuperSonic parse/execute 对话链，但数据域独立于官方 21 项。
模型只接收 `questions_blind.jsonl` 中的 `id` 和 `question`；评测器使用本目录的结构化 expected rows
判断指标绑定、执行成功和结果正确，最终自然语言摘要不参与主评分。

先做 5 题 smoke：

```powershell
python evaluation/bank_nl2sql/synthetic_360/run_eval.py `
  --release-dir evaluation/bank_nl2sql/synthetic_360/releases/0.1.0-synthetic `
  --split dev --max-records 5 --agent-id <已配置的Agent ID> `
  --runtime-receipt .local-dev/synthetic-360-runtime-receipt.json `
  --base-url http://127.0.0.1:9080 --output .local-dev/synthetic-360-smoke.json
```

非 dry-run 必须提供与当前 release、Agent、模型和数据库绑定一致的运行回执；这样不会
误把官方 Agent 或旧数据库当成 360 指标评测结果。`--dry-run` 只检查盲题字段，不访问服务。

运行成功后再用 `--split dev` 跑 72 题，确认错误分类后再跑 `--split test`。结构化结果评分：

```powershell
python evaluation/bank_nl2sql/synthetic_360/evaluate_results.py `
  --gold evaluation/bank_nl2sql/synthetic_360/releases/0.1.0-synthetic/questions.jsonl `
  --capture .local-dev/synthetic-360-smoke.json --split dev `
  --output .local-dev/synthetic-360-smoke-report.json
```

合成域报告只能用于展示 360 项泛化能力，不能替代官方 21 项 Fact v3 成绩，也不能表述为真实银行生产效果。
