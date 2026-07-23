# BE-06 SQL 安全与性能网关实现说明

## 已实现

- 使用 JSqlParser 强制单条只读 `SELECT`，基于解析后的规范 SQL 拦截写操作、多语句、危险函数、锁语句和文件写入，避免注释分隔绕过。
- 对 CTE、UNION 分支和嵌套子查询逐级检查无界 `SELECT *`，任一直接读取基础表的分支缺少 `WHERE`、`LIMIT` 或 `FETCH` 均拒绝执行；允许外层只投影已受限 CTE 或子查询的安全写法。
- 执行前运行 `EXPLAIN`，兼容结构化行数和 PostgreSQL 文本计划，超过阈值时拒绝查询。
- 通过公平信号量限制并发，等待超时后快速失败，并记录接收数、拒绝数和累计执行耗时。
- 提供网关运行快照，包含最大并发、可用许可、活动查询、接收、拒绝、成功、失败和平均执行耗时。
- 统一采集解析、模型、翻译、执行和解释五个阶段的调用次数、累计耗时、平均耗时、最大耗时及最近 2,048 次样本的 P50/P95/P99。
- 采集语义结果缓存命中、未命中、请求总数和命中率。
- 聚合指标查询进入独立热点指标缓存，默认保留 60 分钟，与普通 10 分钟结果缓存隔离，并单独统计命中率。
- 提供仅超级管理员可访问的 `GET /api/semantic/query/gateway/stats` 监控接口，同时返回网关运行快照、五阶段耗时和缓存命中率。
- JDBC 层统一设置查询超时、最大结果行数和 Fetch Size；超时由驱动取消执行。
- 结果集迭代期间的驱动异常和读取超时不再被吞掉；异常向网关传播并计入失败，禁止以成功状态返回不完整数据。
- 策略拒绝保留可操作的安全原因，其他 JDBC/驱动异常统一返回通用查询失败信息，避免把物理 SQL、库表结构或连接细节暴露给调用方。
- JDBC、查询加速器和数据库管理 SQL 均在实际执行前进入统一网关，避免加速器命中或管理接口绕过只读策略、限流和性能监控。
- 复用现有语义结果缓存、Schema 元数据缓存和语义模型缓存，并将查询结果缓存键隔离到用户粒度，避免权限结果跨用户复用。

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
| `s2.cache.hot-metric.expire.after.write` | `60` | 热点指标缓存写入后过期分钟数 |
| `s2.cache.hot-metric.max.size` | `1000` | 热点指标缓存最大条目数 |

## 验证

- `SqlSafetyPolicyTest`：只读、危险函数、多语句和无界查询。
- `SqlSafetyPolicyAdvancedTest`：注释拆分危险函数、UNION/CTE/嵌套子查询中的无界 `SELECT *` 绕过，以及受限派生查询的兼容性。
- `JdbcExecutorGatewayCoverageTest`：校验危险 SQL 在进入 JDBC 或查询加速器前被统一网关拒绝。
- `DatabaseServiceGatewayCoverageTest`：校验数据库管理查询接口不能绕过统一网关。
- `SqlUtilsResultReadTest`：校验结果集读取异常向上抛出，不返回静默截断的部分结果。
- `ExplainCostPolicyTest`：结构化及文本执行计划、超阈值拒绝。
- `QueryExecutionGatewayTest`：并发许可耗尽时快速拒绝，并校验接收和拒绝计数。
- `QueryExecutionGatewayTest`：校验策略拒绝、执行失败、活动查询和平均耗时快照。
- `QueryPerformanceMonitorTest`：校验五阶段耗时聚合、平均值、最大值、P50/P95/P99 和缓存命中率。
- `DefaultQueryCacheTest`：校验结构化指标查询和聚合 SQL 的热点识别。
- `CaffeineCacheManagerTest`：校验普通结果与热点指标使用独立缓存空间。
- `QueryGatewayMonitorServiceTest`：校验超级管理员访问和普通用户拒绝。
- `QueryGatewayH2IntegrationTest`：基于真实 H2 JDBC 执行验证安全策略、`EXPLAIN`、结果行数限制和并发稳定性。
- `QueryGatewayH2IntegrationTest`：1 秒超时取消长查询，取消后立即执行轻量查询验证资源释放。
- 14 个关联 Maven 模块在 JDK 21 下完成干净编译和定向测试。

## 本地性能基线

- 状态：已完成（2026-07-23）。
- 数据规模：H2 内存数据库，`bank_account` 表 10,000 行。
- 测试规模：20 次预热、200 次串行采样、8 线程 200 次并发查询。
- 最新实测结果：平均 `7.70 ms`、P95 `10 ms`、P99 `15 ms`，并发查询无拒绝。
- 验收结论：本地标准测试环境满足“单轮查询平均响应时间不高于 3 秒”的性能门槛。
- 完整报告：`task/BE-06_PERFORMANCE_REPORT.md`。

## 待环境验收

目标数据库下的 P95/P99、长时间稳定性和对应 JDBC 驱动的超时取消效果依赖稳定压测环境，继续纳入 QA-03 执行。
