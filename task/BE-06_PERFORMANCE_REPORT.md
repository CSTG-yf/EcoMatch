# BE-06 本地性能基线报告

## 结论

BE-06 已通过本地 H2 标准测试环境的安全、限流、结果集限制、超时取消和性能基线验证。2026-07-25 最新一轮 200 次串行采样的平均响应时间为 `9.64 ms`，低于赛题要求的 3 秒；8 线程执行 200 次并发查询时未发生拒绝或执行失败。

本报告只关闭本地性能基线，不替代 QA-03 在目标数据库和稳定压测环境中的最终验收。

## 测试环境

| 项目 | 配置 |
| --- | --- |
| 操作系统 | Windows |
| JDK | Eclipse Adoptium 21.0.11 |
| 数据库 | H2 内存数据库 |
| 数据表 | `bank_account` |
| 数据量 | 10,000 行，100 个机构 |
| 查询类型 | 分机构余额聚合、排序和结果限制 |

## 测试方法

1. 创建 10,000 行银行账户样例数据。
2. 验证只读 SQL 策略和真实 JDBC `EXPLAIN`。
3. 验证 JDBC 最大返回 50 行的结果集限制。
4. 预热 20 次后，串行执行 200 次并统计平均值、P95 和 P99。
5. 使用 8 个线程并发执行 200 次，验证执行成功率和网关拒绝计数。
6. 使用单并发许可验证许可耗尽时的快速拒绝和计数器。
7. 对高计算量查询设置 1 秒 JDBC 超时，验证 5 秒内取消并在取消后立即执行轻量查询。

## 测试结果

| 指标 | 实测值 | 判定 |
| --- | ---: | --- |
| 串行采样数 | 200 | 通过 |
| 平均响应时间 | 9.64 ms | 通过，低于 3 秒 |
| P95 | 16 ms | 记录 |
| P99 | 29 ms | 记录 |
| 并发线程数 | 8 | 通过 |
| 并发查询数 | 200 | 通过 |
| 并发执行失败数 | 0 | 通过 |
| 容量内拒绝数 | 0 | 通过 |
| 容量耗尽快速拒绝 | 生效 | 通过 |
| 最大结果行数 | 50 行 | 通过 |
| JDBC 查询超时 | 1 秒内触发 | 通过 |
| 超时后连接可用性 | 立即执行 `SELECT 1` | 通过 |

## 自动化用例

- `headless/core/src/test/java/com/tencent/supersonic/headless/core/gateway/QueryExecutionGatewayTest.java`
- `headless/core/src/test/java/com/tencent/supersonic/headless/core/gateway/SqlSafetyPolicyAdvancedTest.java`
- `headless/core/src/test/java/com/tencent/supersonic/headless/core/executor/JdbcExecutorGatewayCoverageTest.java`
- `headless/core/src/test/java/com/tencent/supersonic/headless/core/utils/SqlUtilsResultReadTest.java`
- `headless/server/src/test/java/com/tencent/supersonic/headless/server/gateway/QueryGatewayH2IntegrationTest.java`
- `headless/server/src/test/java/com/tencent/supersonic/headless/server/service/DatabaseServiceGatewayCoverageTest.java`

执行命令：

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
mvn -pl headless/server -am `
  "-Dtest=QueryExecutionGatewayTest,QueryGatewayH2IntegrationTest,SqlSafetyPolicyTest,SqlSafetyPolicyAdvancedTest,ExplainCostPolicyTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" `
  "-Dspotless.skip=true" test
```

## QA-03 待验收项

- 在比赛目标数据库及数据规模上复测平均响应时间、P95 和 P99。
- 覆盖缓存命中与未命中、简单查询、复杂查询和跨库查询。
- 执行长时间并发稳定性测试并记录资源占用。
- 复测目标 JDBC 驱动在查询超时后的数据库端取消效果。

## QA-03 目标数据库验收工具

`QueryGatewayTargetDatabaseIT` 不属于默认测试匹配范围，只有显式指定测试类且存在 `QA03_JDBC_URL` 时才连接外部数据库。测试至少运行 5 分钟稳定性阶段，默认运行 30 分钟；报告写入 `headless/server/target/qa03-<scenario>-report.json`，不包含 JDBC URL、用户名、密码或测试 SQL。

必填环境变量：

| 环境变量 | 说明 |
| --- | --- |
| `QA03_JDBC_URL` | 目标数据库 JDBC 地址 |
| `QA03_BENCHMARK_SQL` | 通过网关执行的代表性只读查询 |
| `QA03_TIMEOUT_SQL` | 明确长于取消超时的只读查询 |
| `QA03_CANCELLATION_PROBE_SQL` | 返回数据库端残留超时语句数量的查询 |

常用可选环境变量：

| 环境变量 | 默认值 | 说明 |
| --- | ---: | --- |
| `QA03_SCENARIO` | `benchmark` | 报告场景名，仅允许字母、数字、下划线和连字符 |
| `QA03_JDBC_USER` | 空 | 只读压测账号 |
| `QA03_JDBC_PASSWORD` | 空 | 只读压测密码 |
| `QA03_HEALTH_SQL` | `SELECT 1` | 超时取消后的连接健康检查 |
| `QA03_WARMUP_QUERIES` | `20` | 预热次数 |
| `QA03_LATENCY_SAMPLES` | `200` | 串行延迟样本数 |
| `QA03_CONCURRENCY` | `8` | 稳定性并发数 |
| `QA03_STABILITY_SECONDS` | `1800` | 稳定性持续时间，最小 300 秒 |
| `QA03_QUERY_TIMEOUT_SECONDS` | `30` | 普通查询超时 |
| `QA03_CANCEL_TIMEOUT_SECONDS` | `1` | 取消验证超时 |
| `QA03_CANCEL_GRACE_SECONDS` | `10` | 驱动取消和线程退出宽限 |
| `QA03_MAX_RESULT_ROWS` | `10000` | 单次基准查询最大读取行数 |
| `QA03_MAX_AVERAGE_MS` | `3000` | 平均响应时间门禁 |

PowerShell 执行命令：

```powershell
$env:QA03_SCENARIO='complex'
$env:QA03_JDBC_URL='<由环境管理员提供>'
$env:QA03_JDBC_USER='<只读账号>'
$env:QA03_JDBC_PASSWORD='<通过安全渠道提供>'
$env:QA03_BENCHMARK_SQL='<代表性只读查询>'
$env:QA03_TIMEOUT_SQL='<长耗时只读查询>'
$env:QA03_CANCELLATION_PROBE_SQL='<返回残留语句数量的查询>'

mvn -pl headless/server -am `
  "-Dtest=QueryGatewayTargetDatabaseIT" `
  "-Dsurefire.failIfNoSpecifiedTests=false" `
  test
```

简单查询、复杂查询和跨库查询分别设置不同 `QA03_SCENARIO` 运行并留存报告。数据库服务端 CPU、内存、连接数和慢查询由目标环境监控同步留证；本工具记录客户端 JVM 堆内存、线程峰值和进程 CPU 使用率，不能替代数据库端监控。

## QA-03 应用端验收工具

完整 NL2SQL 链路使用 `run_supersonic_eval.py`。报告对解析、执行、解释以及解析开始至解释完成的端到端耗时输出平均值、P50、P95、P99 和最大值，并单列执行及解释均成功的样本，避免失败请求稀释成功链路性能结论。执行方法见 `evaluation/bank_nl2sql/README.md`。

语义缓存使用 `run_qa03_cache_eval.py`。运行器：

1. 从本地 JSON 读取一条代表性只读 `QuerySqlReq`。
2. 移除外部请求不得控制的 `needAuth` 和 `innerLayerNative`。
3. 增加一次性 SQL 注释生成未使用过的缓存键，首请求必须返回 `useCache=false`。
4. 等待异步写入完成，随后所有采样必须返回 `useCache=true`。
5. 对比运行前后的超级管理员网关快照，至少记录一次未命中和全部已验证命中。
6. 输出冷请求耗时、热请求平均/P50/P95/P99/最大耗时，以及缓存、物理网关和五阶段计数增量。

```powershell
$env:QA03_AUTH_TOKEN='<超级管理员令牌>'

evaluation\.venv\Scripts\python.exe evaluation/bank_nl2sql/run_qa03_cache_eval.py `
  --base-url http://127.0.0.1:9080 `
  --query-template .local-dev/bank-nl2sql/qa03-cache-query.json `
  --scenario aggregate-cache `
  --warm-samples 200 `
  --output .local-dev/bank-nl2sql/qa03-cache-report.json
```

认证令牌只允许通过环境变量传入，HTTP 重定向统一拒绝，避免管理员凭据被转发到非预期地址。报告不保存服务 URL、Token、Cookie、模板 SQL、查询响应或结果数据。缓存运行器验证应用语义缓存，`QueryGatewayTargetDatabaseIT` 验证目标 JDBC 和物理执行；两类报告均通过后才能关闭对应 QA-03 验收项。
