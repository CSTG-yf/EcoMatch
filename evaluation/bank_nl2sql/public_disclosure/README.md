# 公开披露事实域

本目录补充一组来自公开年报的最小可追溯事实，用于验证 360 项候选指标能否从真实公开资料落库并被 SQL 查询。当前来源为中国建设银行股份有限公司 2024 年度报告，覆盖 18 个候选指标、1 家公开披露机构和 1 个报告日期。

## 数据边界

- `dataOrigin` 固定为 `PUBLIC_DISCLOSURE`，与官方 21 项数据和 `synthetic_360` 完全隔离。
- 360 项目录仍是 `CANDIDATE`，名称、业务口径、别名、单位、公式和公开字段映射仍需银行业务专家复核。
- 仅保存必要事实、原始单位、页码、来源 URL 和转换规则，不复制完整年报或整张表格。
- 金额原始单位是人民币百万元，目录单位是万元，统一按 `sourceValue * 100` 转换；比例不换算。
- `CNB001`（各项存款余额）和 `CNB016`（各项贷款余额）使用年报“吸收存款”和“发放贷款和垫款净额”的候选映射，明确标记为待业务复核。

来源台账见 `sources.json`，事实见 `facts.jsonl`，验证查询见 `queries.jsonl`。

## 校验和查询测试

在仓库根目录运行：

```powershell
python evaluation/bank_nl2sql/public_disclosure/validate_public_facts.py `
  --release-dir evaluation/bank_nl2sql/public_disclosure `
  --write-manifest

python evaluation/bank_nl2sql/public_disclosure/run_public_fact_tests.py `
  --release-dir evaluation/bank_nl2sql/public_disclosure `
  --output .local-dev/public-disclosure/public-fact-validation-report.json

python -m unittest evaluation.bank_nl2sql.public_disclosure.tests.test_public_facts -v
```

预期结果：18 条事实通过字段、单位换算、来源、重复键和目录代码校验；3 组 SQL 全部解析、执行并返回与事实一致的结果，且每条结果保留来源定位。

这组测试证明“公开披露数据可以被指标查询链路使用”，不等同于模型在 360 项上的识别准确率，也不能替代官方 21 项 Fact v3 评测或真实生产库验收。

## Qwen 自然语言盲测

`qwen_blind_eval.py` 提供与上述预置 SQL 测试独立的最小 Agent 闭环：千问仅接收中文问题、公开数据表结构、可用机构、18 项候选指标及可用日期。预置 SQL、标准结果、事实数值和来源定位保留在本地评测器，绝不发送给模型。

推荐从仓库根目录执行本地启动脚本，它会隐藏输入 DashScope Key，且不会把 Key 写入报告：

```powershell
powershell -ExecutionPolicy Bypass -File .local-dev\Run-PublicDisclosureQwenBlindEval.ps1
```

报告将写入 `.local-dev/public-disclosure/qwen-blind-<timestamp>.json`，并记录 SQL 解析、执行、结果正确、来源可追溯、请求错误和响应延迟。`structuralResultCorrect` 记录是否严格返回预置的“指标代码、数值、单位”行格式；`resultCorrect` 同时接受指标代码与数值均正确的等价宽表结果。该盲测仍是隔离的 `PUBLIC_DISCLOSURE` 数据域，既不修改也不计入官方 21 项评测。
