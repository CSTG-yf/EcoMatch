# BE-06 SQL 安全与性能网关实现说明

## 已实现

- 使用 JSqlParser 强制单条只读 `SELECT`，拦截写操作、多语句、危险函数、锁语句、文件写入和无界 `SELECT *`。
- 执行前运行 `EXPLAIN`，兼容结构化行数和 PostgreSQL 文本计划，超过阈值时拒绝查询。
- 通过公平信号量限制并发，等待超时后快速失败，并记录接收数、拒绝数和累计执行耗时。
- JDBC 层统一设置查询超时、最大结果行数和 Fetch Size；超时由驱动取消执行。
- 复用现有语义结果缓存，并将缓存键隔离到用户粒度，避免权限结果跨用户复用。

## 配置

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `s2.query-gateway.max-concurrency` | `20` | 最大并发物理查询数 |
| `s2.query-gateway.acquire-timeout-ms` | `1000` | 获取执行许可的最长等待时间 |
| `s2.query-gateway.max-sql-length` | `100000` | SQL 最大字符数 |
| `s2.source.query-timeout-seconds` | `30` | JDBC 查询超时 |
| `s2.source.result-limit` | `1000000` | 最大返回行数 |
| `s2.source.explain-cost-check-enabled` | `true` | 是否执行 EXPLAIN 成本检查 |
| `s2.source.explain-max-estimated-rows` | `1000000` | 计划最大估算扫描行数 |

## 验证

- `SqlSafetyPolicyTest`：只读、危险函数、多语句和无界查询。
- `ExplainCostPolicyTest`：结构化及文本执行计划、超阈值拒绝。
- 14 个关联 Maven 模块在 JDK 21 下完成干净编译和定向测试。

## 待环境验收

平均响应不高于 3 秒、P95/P99、并发稳定性和数据库取消效果依赖标准数据库及压测环境，纳入 QA-03 执行。
