# BE-06 本地性能基线报告

## 结论

BE-06 已通过本地 H2 标准测试环境的安全、限流、结果集限制、超时取消和性能基线验证。2026-07-23 最新一轮 200 次串行采样的平均响应时间为 `9.84 ms`，低于赛题要求的 3 秒；8 线程执行 200 次并发查询时未发生拒绝或执行失败。

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
| 平均响应时间 | 9.84 ms | 通过，低于 3 秒 |
| P95 | 17 ms | 记录 |
| P99 | 26 ms | 记录 |
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
