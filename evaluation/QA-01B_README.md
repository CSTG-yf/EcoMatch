# QA-01B 版本对比与发布门禁

QA-01B 消费 QA-01A 的统一 JSON 报告，提供可审计的版本基线、指标差异、退化判断、错误案例清单和 CI 发布阻断。

## 固化基线

只有状态为 `PASS` 的 QA-01A 报告可以成为基线：

```powershell
python evaluation/run_qa01b.py baseline `
  --current .local-dev/bank-evaluation/qa01a-report.json `
  --baseline .local-dev/bank-evaluation/qa01b-baseline.json `
  --version v1.0.0
```

基线保存 QA-01A 报告、版本、保存时间、源报告 SHA-256 和嵌入报告完整性哈希，方便追溯并阻止被修改的基线参与门禁。

## 执行版本门禁

```powershell
python evaluation/run_qa01b.py compare `
  --baseline .local-dev/bank-evaluation/qa01b-baseline.json `
  --current .local-dev/bank-evaluation/qa01a-report.json `
  --current-version v1.1.0 `
  --output .local-dev/bank-evaluation/qa01b-report.json
```

退出码含义：

| 退出码 | 含义 |
| ---: | --- |
| `0` | 所有规则通过，允许发布 |
| `1` | 有退化、阈值失败或评测失败，阻断发布 |
| `2` | 输入、策略或命令参数无效，阻断发布 |

## 默认门禁策略

`evaluation/qa01b_policy.json` 是版本化策略文件：

- 意图、指标集合、澄清、SQL 执行、结果一致、多轮、图表和解释指标不得低于最低阈值。
- 上述准确率和通过率不得低于基线。
- SQL 平均响应时间和 P95 相对基线增长不得超过 20%。
- 基线与当前报告必须使用相同数据集版本、比赛工作簿和评测模式。
- 当前 QA-01A 报告或任一必需套件失败时直接阻断发布。

报告包括 `metricComparison`、`stageTimingComparison`、`violations` 和 `errorCases`，可直接供 FE-09 使用。阶段耗时只展示差异；机器环境波动较大，因此默认只对 QA-01A 输出的平均响应时间和 P95 设置门禁。
