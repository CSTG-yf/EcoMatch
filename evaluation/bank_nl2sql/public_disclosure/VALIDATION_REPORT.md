# 公开披露数据验证记录

## 结论

已完成“360 项新增指标补充公开真实数据并验证可用性”的第一版闭环：

- 来源：建设银行《2024 年度报告》官方公开 PDF。
- 数据域：`PUBLIC_DISCLOSURE`，与官方 21 项数据和 `SYNTHETIC` 合成事实库隔离。
- 覆盖：18 个候选指标、1 家公开披露机构、2024-12-31 一个报告日期、18 条最小事实。
- 金额统一从“人民币百万元”换算为目录单位“万元”，换算规则显式写入每条事实。
- `CNB001`、`CNB016` 的“吸收存款”“发放贷款和垫款净额”映射已标记为待业务复核。

## 验证结果

运行 `run_public_fact_tests.py` 的结果：

| 项目 | 结果 |
| --- | ---: |
| 事实字段、代码、单位和来源校验 | 18/18 通过 |
| SQL 查询组 | 3/3 |
| SQL 解析 | 3/3 |
| SQL 执行 | 3/3 |
| 查询结果与公开事实一致 | 3/3 |
| 查询结果来源可追溯 | 3/3 |

报告文件位于本地忽略目录：

`.local-dev/public-disclosure/public-fact-validation-report.json`

## 运行命令

```powershell
python evaluation/bank_nl2sql/public_disclosure/validate_public_facts.py `
  --release-dir evaluation/bank_nl2sql/public_disclosure `
  --catalog-dir evaluation/bank_metric_catalog/releases/0.1.0-candidate

python evaluation/bank_nl2sql/public_disclosure/run_public_fact_tests.py `
  --release-dir evaluation/bank_nl2sql/public_disclosure `
  --catalog-dir evaluation/bank_metric_catalog/releases/0.1.0-candidate `
  --output .local-dev/public-disclosure/public-fact-validation-report.json
```

## 未完成边界

这不是银行生产数据，也不是模型识别准确率，更不能替代官方 21 项 Fact v3 结果。360 项目录的名称、别名、业务口径、单位、公式及公开字段映射仍需银行业务专家最终复核；当前公开数据只覆盖 18 项，其他指标仍保持候选目录或合成数据验证状态。
