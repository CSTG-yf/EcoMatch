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
| 部署 yaml `s2.mapper.embedding.use-llm-enhance` | **false** | 关闭向量召回后的 LLM 二次筛选（`conf/s2-config.yaml`，重启生效）。该调用会把全部召回塞进单个 prompt 且走旁路 langchain4j 客户端：全量思考模型（如 GLM-5.3-flash）下每次 parse 白付 60–100s 超时，曾致 6 道题撞 901s 天花板（见 RUN_RECORDS 2026-08-28 r1/r2 对照） |
| Agent `REWRITE_MULTI_TURN` | **disable** | 多轮重写 app 会把题面语义改写坏（实测：「从年初」→「2025年1月1日至」、「存贷比」→「两指标分别是多少」），破坏族闸门触发词并引入逐轮方差；单轮隔离评测协议下零价值，且每题多烧一次 LLM 调用（parse p50 23s→8s）。关闭后 dev 40/40（RUN_RECORDS 2026-08-28 r4） |

## 评测

正式入口为 `evaluation/bank_nl2sql/Run-OfficialBankEvaluation.ps1`，主指标为 Fact v3
`caseAccuracy`。每个分割可独立运行；`test` 不以 `smoke`、`train` 或 `dev` 为前置条件，
但仍需显式确认并写入本地运行登记。
只运行用户明确指定的分割。
