# QA-01B 版本对比与发布门禁实现说明

## 完成范围

- 新增 `evaluation/run_qa01b.py`，支持固化通过的 QA-01A 报告为带完整性校验的可追溯版本基线。
- 新增 `evaluation/qa01b_policy.json`，集中配置必需套件、评测源身份、最低指标和允许退化范围。
- 比较意图准确率、指标集合准确率、澄清准确率、SQL 执行成功率、结果一致率、多轮通过率、图表准确率和解释覆盖率。
- 比较 SQL 平均响应时间和 P95，默认相对基线增长超过 20% 时阻断发布。
- 输出版本、评测源、指标差异、阶段耗时差异、退化项、门禁违规和失败样本清单。
- 门禁通过返回退出码 `0`，业务门禁失败返回 `1`，输入或策略错误返回 `2`。

## 交付物

| 交付物 | 路径 |
| --- | --- |
| 门禁执行器 | `evaluation/run_qa01b.py` |
| 版本化门禁策略 | `evaluation/qa01b_policy.json` |
| 使用说明 | `evaluation/QA-01B_README.md` |
| 自动化测试 | `evaluation/tests/test_run_qa01b.py` |
| 验收基线 | `task/QA-01B_BASELINE.json` |
| 机器可读验收报告 | `task/QA-01B_ACCEPTANCE_REPORT.json` |

## 验收结果

2026-07-30 使用 `task/QA-01A_ACCEPTANCE_REPORT.json` 完成基线固化和正向版本门禁：

- 10 个受控指标全部通过。
- 无来源不一致、最低阈值失败或相对基线退化。
- 发布决策为 `ALLOW`，进程退出码为 `0`。
- 自动化测试覆盖相同版本通过、准确率退化、P95 退化、数据源不一致、当前评测失败、失败样本输出、基线准入和 CLI 阻断退出码。

负向 CLI 测试将结果一致率从 `0.95` 降至 `0.94`，门禁正确输出 `BLOCK` 并返回退出码 `1`。

## 后续依赖

QA-01B 已解锁 FE-09。FE-09 可直接消费报告中的 `metricComparison`、`stageTimingComparison`、`violations` 和 `errorCases`。

实际版本发布前应使用相同评测模式和冻结数据生成新的 QA-01A 报告，再与已批准基线执行 QA-01B；不得使用 `gold` 模式结果代替真实模型效果结论。
