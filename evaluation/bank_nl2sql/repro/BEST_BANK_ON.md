# 银行 NL2SQL 运行配置

机器可读配置：[`best_bank_on.json`](best_bank_on.json)。当前实现固定使用模型驱动的
`BANK_CONSTRAINED_PLAN`：模型一次返回 requirements 与 plan，随后由通用 Schema/JSON
校验、编译与执行链路处理。

已移除的旧能力包括：题面规则识别、机构/指标/时间预抽取、问题专用路由、动态样例、
自由 SQL 旁路、确定性短路，以及双模式对照开关。

## 当前参数

| 参数 | 值 | 作用 |
| --- | --- | --- |
| 银行路由 | 固定 | 具备银行语义维度的数据集走 `BANK_CONSTRAINED_PLAN` |
| `s2.parser.bank.max-candidates` | **1** | 默认单个模型计划 |
| `s2.parser.bank.plan.thinking.enable` | **false** | 关闭额外思考输出 |
| Agent `BANK_CONSTRAINED_PLAN` | **enable** | 启用模型计划入口 |
| Agent `EXECUTION_SQL_CORRECTOR` | **enable**（建议） | 执行失败时一次受控修复 |

## 评测

正式入口为 `evaluation/bank_nl2sql/Run-OfficialBankEvaluation.ps1`，主指标为 Fact v3
`caseAccuracy`。每个分割可独立运行；`test` 不以 `smoke`、`train` 或 `dev` 为前置条件。
只运行用户明确指定的分割。
