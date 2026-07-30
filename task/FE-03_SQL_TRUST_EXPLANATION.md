# FE-03 SQL 与可信度解释实现说明

## 1. 完成范围

- 在每条问数结果中展示“原始问题 -> 语义映射 -> 查询条件 -> SQL 校验”转换链路。
- 展示 Schema 映射置信度、SQL 校验状态、指标和维度口径、机构/时间/筛选条件。
- 置信度仅使用后端返回且位于 `[0, 1]` 的映射相似度计算，不使用前端模拟值。
- SQL 校验状态直接消费 `sqlEvaluation`，区分已通过、未通过和未返回校验结果。
- 根据生成、语义修正、执行及执行优化 SQL 的差异生成转换与修正记录。
- 管理员诊断抽屉展示错误阶段、错误类型、Schema 映射证据、复杂 SQL 特征和各阶段 SQL。
- 普通用户只看到业务口径与条件，不显示 SQL、错误消息或技术诊断。
- 主页面和 Copilot 入口统一按管理员标记或治理角色判断诊断权限，移除硬编码开发者权限。

## 2. 正式协议映射

| 前端能力 | 后端字段 |
| --- | --- |
| 指标、维度及口径 | `SemanticParseInfo.metrics`、`dimensions` |
| 查询条件 | `filterSql`、`dateInfo`、`queryMode` |
| Schema 映射证据 | `elementMatches` |
| 映射置信度 | `elementMatches[].similarity` |
| SQL 校验状态 | `sqlEvaluation.isValidated`、`validateMsg`、`errorType` |
| SQL 复杂特征 | `sqlEvaluation.complexSqlFeatures` |
| 转换与修正记录 | `sqlInfo.parsedS2SQL`、`correctedS2SQL`、`querySQL`、`correctedQuerySQL` |
| 管理员权限 | `currentUser.superAdmin`、`isAdmin`、治理角色 |

## 3. 权限与安全

- 普通用户无法渲染诊断入口，既有 SQL 明细也由同一个 `isDeveloper` 权限关闭。
- 诊断权限只授予超级管理员、管理员及 `DATA_ADMIN`、`BI_ADMIN`、`DEVELOPER` 角色。
- 属性诊断只展示 `bank.nl2sql.*` 和复杂 SQL 特征白名单，不展示请求、提示词、凭据或任意扩展属性。
- SQL 和执行错误仅在管理员抽屉中展示，业务解释面板不泄露底层技术信息。

## 4. 验证结果

- Chat SDK：6 个测试套件、22 个用例全部通过。
- 新增测试覆盖置信度边界、SQL 校验状态、修正阶段、筛选条件解释及普通用户/管理员权限隔离。
- `pnpm --filter supersonic-fe build:os`：生产构建通过。
- 构建成功生成 PC、移动端、外部嵌入、Copilot、治理及安全管理入口。

## 5. 下游关系

FE-03 已满足业务口径确认和管理员错误阶段定位要求。当前本地产品主链剩余 `FE-04 -> FE-05 -> FE-06A || FE-06B -> QA-02C`。
