# QA-01A 统一评测执行器实现说明

## 完成范围

- 新增 `evaluation/run_qa01a.py`，用单一命令统一执行意图、SQL 执行、结果一致性、十轮上下文、图表推荐和业务解释验收。
- 整合 DATA-01、DATA-02、DATA-03 的 Python 数据契约，以及 `BankIntentFrozenDatasetTest`、`BankNl2SqlDatasetValidationTest`、`MultiTurnContextEngineTest` 和 `BusinessInsightProcessorTest`。
- 自动构建或复用本地 SQLite 银行业基准库，重放 200 条冻结 SQL，并统计平均、P50、P95、P99 和最大执行耗时。
- 支持 `gold`、`predictions` 和 `supersonic` 三种运行模式。默认 `gold` 验证冻结基线和评测基础设施；后两种模式用于真实模型预测或 SuperSonic `/openapi` 链路。
- 对五个必需套件分别执行阈值判定。命令失败、套件缺失或阈值未满足时仍生成 JSON 报告，并返回退出码 `1`。
- 报告仅保留聚合指标、阶段退出码以及失败样本 ID/类别，不保存 SQL、结果行、凭据或源文件绝对路径。
- Windows 下自动识别并通过 `cmd.exe` 启动 Maven `.cmd`；子进程不存在时转换为阶段退出码 `127`，不会丢失其他阶段诊断。

## 验收结果

2026-07-30 使用比赛 Excel 数据集和 JDK 21 完成默认 `gold` 模式验收：

| 套件 | 样本或用例 | 指标 | 结果 |
| --- | ---: | --- | --- |
| 意图识别 | 52 | 意图 98.08%，指标集合 96.15%，澄清判断 92.31% | 通过 |
| SQL 执行 | 200 | 执行成功率 100%，平均 2.502 ms，P95 5.203 ms | 通过 |
| 结果一致性 | 200 | 结果一致率 100% | 通过 |
| 十轮上下文 | 5 个测试 | 通过率 100%，最大上下文轮数 10 | 通过 |
| 图表与解释 | 30 | 图表准确率 100%，解释覆盖率 100% | 通过 |

五个必需套件全部通过，无缺失套件和阶段失败。机器可读结果见 `task/QA-01A_ACCEPTANCE_REPORT.json`。

负向门禁将最低意图准确率临时提高为 100%，执行器正确标记 `intent` 失败、记录 `intentAccuracy=0.9808 below 1.0`，并返回退出码 `1`。

## 使用方法

完整参数及真实预测、SuperSonic 模式示例见 `evaluation/QA-01A_README.md`。默认验收命令：

```powershell
python evaluation/run_qa01a.py `
  --workbook task/基于大模型与NL2SQL的银行业智能问数系统构建与应用_数据集.xlsx `
  --output .local-dev/bank-evaluation/qa01a-report.json
```

`.local-dev/` 已加入 Git 忽略规则，基准数据库、临时 Surefire 文件和运行报告不会污染仓库。

## 后续依赖

QA-01A 已完成并解锁 QA-01B。QA-01B 需要在本执行器报告协议之上增加版本基线存储、指标差异、退化分类和发布阻断策略。真实模型效果结论必须使用 `predictions` 或 `supersonic` 模式，不能将默认金标重放结果等同于线上 NL2SQL 准确率。
