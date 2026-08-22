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

## Agent 评测入口

`run_eval.py` 复用工程中已有的 SuperSonic parse/execute 对话链，但数据域独立于官方 21 项。
模型只接收 `questions_blind.jsonl` 中的 `id` 和 `question`；评测器使用本目录的结构化 expected rows
判断指标绑定、执行成功和结果正确，最终自然语言摘要不参与主评分。

先做 5 题 smoke：

```powershell
python evaluation/bank_nl2sql/synthetic_360/run_eval.py `
  --release-dir evaluation/bank_nl2sql/synthetic_360/releases/0.1.0-synthetic `
  --split dev --max-records 5 --agent-id <已配置的Agent ID> `
  --base-url http://127.0.0.1:9080 --output .local-dev/synthetic-360-smoke.json
```

运行成功后再用 `--split dev` 跑 72 题，确认错误分类后再跑 `--split test`。结构化结果评分：

```powershell
python evaluation/bank_nl2sql/synthetic_360/evaluate_results.py `
  --gold evaluation/bank_nl2sql/synthetic_360/releases/0.1.0-synthetic/questions.jsonl `
  --capture .local-dev/synthetic-360-smoke.json --split dev `
  --output .local-dev/synthetic-360-smoke-report.json
```

合成域报告只能用于展示 360 项泛化能力，不能替代官方 21 项 Fact v3 成绩，也不能表述为真实银行生产效果。
