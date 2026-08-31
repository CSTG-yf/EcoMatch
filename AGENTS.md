# AGENTS.md

## 当前银行 NL2SQL 链路

```text
自然语言问题
  → 模型一次返回 requirements + plan
  → 通用 JSON、Schema 与跨字段一致性校验
  → 编译为受控结构查询 / S2SQL
  → 执行与结果投影
```

- 已删除旧的机构/指标/时间/意图规则识别器、Mapper、REST 接口和前端澄清 UI。
- 不恢复题面关键词分类、问题专用路由、动态样例、无约束自由 SQL 旁路或确定性短路。受控 free-SQL fallback 是独立架构决策，默认关闭；只有白名单、列契约与 trial probe 全部通过时才可启用。
- 语义目录只提供当前数据集的可用能力白名单；模型自行理解问题并选择字段。编译器可用通用高置信证据守卫验证问题中显式出现的机构、指标与时间，但不得按题号、完整问句或答案硬编码，也不得改写模型计划。
- `BankIntentType` 是模型计划中的查询操作枚举，供通用编译器选择计算形态；它不是独立的题面意图识别步骤。

## 评测

- 正式入口为 `evaluation/bank_nl2sql/Run-OfficialBankEvaluation.ps1`。
- 每个分割独立运行。尤其 `-Mode test` 不得再要求先执行 `smoke`、`train` 或 `dev`。
- 冻结 `test` 仍需显式确认并写入本地运行登记；该审计不引入任何云端缓存或远端前置条件。
- 只运行用户点名的分割；不得把其他分割、Maven 测试或前端测试作为隐式前置步骤。
- 分数只使用 Fact v3 `caseAccuracy`；运行时阶段信息只用于诊断。

## 工程约束

- 未经用户明确要求，不 commit、push、PR 或删除未归属的文件。
- 变更源码后先检查 diff；只有用户允许时再运行相应验证命令。
- 本项目 JDK、Maven、缓存与评测依赖保留在 `.local-dev`，不修改系统 PATH 或全局配置。

## 基本结构

- `headless/chat`：模型计划、校验、编译和执行协调。
- `headless/core`：语义翻译与执行。
- `headless/server`：语义资产与开放接口。
- `chat`：会话编排。
- `launchers/standalone`：组合服务，默认端口 9080。
- `webapp`：React/Umi 前端。
