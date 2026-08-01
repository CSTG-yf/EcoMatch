# BE-07 跨库联邦查询

## 1. 当前状态

- SuperSonic 已具备 Trino/Presto JDBC 驱动、数据库类型、元数据适配器和 SQL 方言改写能力。
- 2026-08-01 已补齐正式联邦环境的无密钥配置契约、显式集成测试、失败封闭校验和脱敏报告。
- 配置契约单元测试已通过；正式 BE-07 仍需 Trino/Presto、两个异构 catalog、专用测试数据和权限账号执行，不能用本地配置测试替代。

## 2. 执行链

```text
Trino/Presto 部署 || 两个异构数据源接入 || 测试账号和资源组配置
  -> 准备专用跨源关联数据和口径校验 SQL
  -> 配置 SuperSonic 模型到 catalog.schema.table
  -> 执行 FederatedQueryTargetEnvironmentIT
  -> 7/7 环境控制项通过
  -> BE-07 完成
  -> BE-11 和 OPS-01 解锁
  -> QA-03 增加 federated 场景并关闭 BE-06
```

## 3. 测试资源

至少提供两个不同 catalog，并由 Trino/Presto 分别连接两种异构数据源。测试表只使用专用模拟银行数据，不使用真实客户数据。

| 资源 | 要求 |
| --- | --- |
| 主测试账号 | 可读取两个指定 catalog，仅允许只读查询 |
| 受限测试账号 | 可正常连接并执行 `SELECT 1`，但无权读取至少一个指定源 |
| 跨源 SQL | 显式引用两个 `catalog.schema.table`，返回列数和最少行数确定 |
| 口径 SQL | 显式引用两个源，只返回一行一列；`0` 表示无口径差异 |
| 资源限制 SQL | 确定会触发目标资源组的时间、内存或数据量限制 |
| 权限拒绝 SQL | 读取受保护源，并匹配环境管理员提供的预期拒绝错误模式 |

## 4. 环境变量

配置字段清单同时保存在 `evaluation/be07_environment.template.json`。连接地址、账号、密码和 SQL 只通过进程环境注入，不写入模板、命令参数或版本库。

| 环境变量 | 必填 | 说明 |
| --- | --- | --- |
| `BE07_JDBC_URL` | 是 | `jdbc:trino:` 或 `jdbc:presto:` 地址 |
| `BE07_JDBC_USER`、`BE07_JDBC_PASSWORD` | 否 | 主只读账号；密码通过安全渠道注入 |
| `BE07_SOURCE_A`、`BE07_SOURCE_B` | 是 | 两个不同 catalog，格式为 `catalog.schema` |
| `BE07_FEDERATED_SQL` | 是 | 跨源关联查询，必须同时引用两个源 |
| `BE07_VALIDATION_SQL` | 是 | 口径差异计数查询，预期返回 `0` |
| `BE07_DENIED_JDBC_URL` | 否 | 受限账号地址，默认复用主地址 |
| `BE07_DENIED_JDBC_USER`、`BE07_DENIED_JDBC_PASSWORD` | 用户名必填 | 可连接但不能读取受保护源的账号，密码可为空 |
| `BE07_DENIED_SQL` | 是 | 受限账号访问受保护源的查询 |
| `BE07_DENIED_ERROR_CONTAINS` | 是 | 权限拒绝的 SQLState/错误消息字面片段；多个候选用 `|` 分隔 |
| `BE07_RESOURCE_LIMIT_SQL` | 是 | 应被服务端资源组拒绝的只读查询 |
| `BE07_RESOURCE_LIMIT_ERROR_CONTAINS` | 是 | 资源组拒绝的 SQLState/错误消息字面片段 |
| `BE07_EXPECTED_COLUMN_COUNT` | 是 | 跨源查询固定输出列数 |
| `BE07_EXPECTED_MIN_ROWS` | 否 | 最少结果行数，默认 `1` |
| `BE07_MAX_RESULT_ROWS` | 否 | 最大读取行数，默认 `10000` |
| `BE07_QUERY_TIMEOUT_SECONDS` | 否 | 普通查询超时，默认 `60` |
| `BE07_RESOURCE_LIMIT_TIMEOUT_SECONDS` | 否 | 资源限制探针超时，默认 `120` |
| `BE07_HEALTH_SQL` | 否 | 受限账号连接健康查询，默认 `SELECT 1` |

## 5. 执行命令

在当前 PowerShell 会话安全设置环境变量后执行：

```powershell
mvn -pl headless/server -am `
  "-Dtest=FederatedQueryTargetEnvironmentIT" `
  "-Dsurefire.failIfNoSpecifiedTests=false" `
  "-Dspotless.skip=true" test
```

该 `*IT` 类不属于 Maven 默认单元测试匹配范围，只有显式指定时才连接外部环境；显式执行但缺少任一必填变量会直接失败，不会产生跳过式绿灯。正式运行成功后，将 `headless/server/target/be07-federated-report.json` 作为 BE-07 环境验收报告归档。

## 6. 通过条件

- 数据库元数据明确识别为 Trino 或 Presto。
- 跨源 SQL 和口径 SQL均为单条只读 `SELECT`，并同时引用两个不同 catalog。
- `EXPLAIN` 和真实跨源查询成功，列契约、最少行数和最大行数符合配置。
- 口径校验返回 `0`。
- 资源限制探针按预期错误模式被服务端拒绝，拒绝后连接健康。
- 受限账号可连接但访问受保护源失败，且错误匹配权限规则。
- 跨源查询、口径校验、资源限制和权限探针均经过 SuperSonic `QueryExecutionGateway`；最终活动查询为 `0`，并发许可全部释放。
- 报告 7/7 控制项通过，且不包含 JDBC 地址、账号、密码、SQL、catalog 名称或查询数据。
