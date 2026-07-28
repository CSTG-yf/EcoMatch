# BE-06 SQL 安全与性能网关实现说明

## 已实现

- 默认危险函数集合补充 H2、SQLite、PostgreSQL、SQL Server 和 DuckDB 的跨库/外部文件入口，包括 `CSVREAD`、`readfile`、`dblink`、`OPENROWSET`、`read_xlsx` 等，避免只读 `SELECT` 绕过数据边界。
- 默认拒绝集进一步覆盖 SQLite 扩展加载、H2 远程 Schema 链接、PostgreSQL 后端/配置/WAL 状态函数、DuckDB 动态查询及 PostgreSQL/MySQL/SQLite 扫描器、ClickHouse/StarRocks 外部 URL、对象存储、集群和文件表函数，以及 SQL Server 命令扩展；合法 `SELECT` 不能借数据库函数绕过数据源边界或只读约束。
- 数据库 catalog、schema、table 和 SQL 字段探测入口在访问适配器前统一校验数据源对象权限；动态元数据标识符仅接受安全字符，阻断通过 `SHOW TABLES`、`SET CATALOG` 等适配器语句注入额外 SQL。
- 数据库连接测试、新增和更新仅允许超级管理员执行，阻断普通登录用户通过自定义 JDBC 地址触发服务端任意连接；不存在的数据源统一 fail-closed。
- 语义模型 Schema 构建仅允许超级管理员执行，避免未授权请求借助模型构建流程连接数据源或调用外部模型。
- 结构化查询的普通比较、集合、区间和模糊过滤值统一规范化为转义后的 SQL 字符串字面量，不再信任客户端预先添加的首尾引号，阻断通过过滤值闭合字面量并注入布尔表达式。
- 结构化查询的 WHERE/HAVING 解析失败统一 fail-closed；同时捕获 JSqlParser 语法异常和词法异常，只记录异常类型并返回通用参数错误，不再静默丢弃指标过滤条件、返回空 SQL 或输出解析器堆栈。
- `innerLayerNative` 原生层模式仅允许可信服务端代码设置，SQL、数据集、结构化和指标请求均禁止通过外部 JSON 选择执行及缓存安全域。
- 批量 SQL 接口统一限制为 1–100 条非空语句；普通批量和严格批量任一子查询失败时整批 fail-closed，不再以空响应伪装部分成功，也不解包回显底层 JDBC/SQL 异常。
- 使用 JSqlParser 强制单条只读 `SELECT`，基于解析后的规范 SQL 拦截写操作、多语句、`SELECT INTO`、`FOR SHARE/UPDATE` 行锁、状态变更函数、危险函数和文件写入，避免注释分隔绕过。
- 拦截 PostgreSQL `pg_read_file`、`pg_read_binary_file`、`pg_ls_dir` 和 `pg_stat_file` 等服务端文件读取/探测函数，避免只读 SQL 被用于读取数据库主机文件系统。
- 默认拦截 DuckDB `read_parquet`、`read_csv_auto`、`read_text`、`glob` 等文件读取及扫描函数，覆盖投影和表函数位置，避免只读 SELECT 读取数据库主机文件。
- 危险函数同时执行 AST 级校验，覆盖带引号或 schema 限定的函数名，以及投影、WHERE、HAVING、QUALIFY、JOIN、GROUP BY、ORDER BY、DISTINCT ON、TOP、LIMIT/OFFSET/FETCH、层级查询、命名窗口、PIVOT、LATERAL VIEW 和表函数位置，避免文本变体绕过。
- FromItem 与 JOIN 树递归展开括号表项，括号内表函数、嵌套 JOIN 右侧及 ON 条件执行同一 AST 校验，阻断 `FROM ("pg_ls_dir"(...))` 等带引号函数绕过。
- 对顶层、CTE、集合分支、派生表和表达式子查询中的 `VALUES` 递归执行同一危险函数校验，阻断通过非 `PlainSelect` AST 执行带引号文件读取或状态变更函数。
- 禁止 `TABLE table_name` 这类绕过投影及范围约束的 Select 子类型；未显式支持的新 Select AST 类型统一 fail-closed，普通常量 `VALUES` 仍可执行。
- 除 `nextval(...)` 外，同时拦截 Oracle `sequence.NEXTVAL` 和 SQL Server `NEXT VALUE FOR sequence`，避免只读外观查询推进数据库序列状态。
- 内置危险函数集合不可被配置移除，并支持通过 `s2.query-gateway.denied-functions` 追加目标数据库的有副作用 UDF；非法函数标识在启动时直接拒绝。
- 内置及追加危险函数在策略构造期排序并编译为单一不可变匹配器，查询热路径不再为每个函数重复创建正则对象；同一策略可被并发查询安全复用，扩充跨方言 denylist 不再线性放大正则编译成本。
- 对 CTE、UNION 分支和嵌套子查询逐级检查无界 `SELECT *`，任一直接读取基础表的分支缺少 `WHERE`、`LIMIT` 或 `FETCH` 均拒绝执行；允许外层只投影已受限 CTE 或子查询的安全写法。
- 对 SELECT、CTE、集合分支和派生子查询执行 AST 层级计数，默认超过 16 层即拒绝，避免深层查询在安全遍历、改写或数据库规划阶段形成栈和 CPU 资源耗尽。
- SQL 解析复用 JSqlParser 可取消超时机制并开放独立解析预算，超时后中断解析任务并按策略拒绝处理，避免复杂 SQL 长时间占用网关线程。
- 执行前运行 `EXPLAIN`，递归兼容结构化、嵌套 JSON 和文本计划中的估算行数，超过阈值时拒绝查询；支持在目标数据库确认格式后开启“缺失估算即拒绝”。
- EXPLAIN 已识别估算字段中的负数、`NaN`、无穷值和非数字内容统一拒绝；估算阈值、并发数、许可等待、SQL 长度、查询超时和结果行数上限必须为正数，错误配置不再被静默修正或退化为无限制执行。
- 通过公平信号量限制并发，等待超时后快速失败，并记录接收数、拒绝数和累计执行耗时。
- 提供网关运行快照，包含最大并发、可用许可、活动查询、接收、拒绝、成功、失败和平均执行耗时。
- 统一采集解析、模型、翻译、执行和解释五个阶段的调用次数、累计耗时、平均耗时、最大耗时及最近 2,048 次样本的 P50/P95/P99。
- 采集语义结果缓存命中、未命中、请求总数和命中率。
- 聚合指标查询进入独立热点指标缓存，默认保留 60 分钟，与普通 10 分钟结果缓存隔离，并单独统计命中率。
- 提供仅超级管理员可访问的 `GET /api/semantic/query/gateway/stats` 监控接口，同时返回网关运行快照、五阶段耗时和缓存命中率。
- JDBC 层统一设置查询超时、最大结果行数和 Fetch Size；超时由驱动取消执行。
- JDBC 结果迭代增加应用层行数上限，即使目标驱动忽略 `setMaxRows` 也会在超限时 fail-closed；查询加速器和其他执行实现返回的结果同样在网关执行闭包内二次校验。
- 结果集迭代期间的驱动异常和读取超时不再被吞掉；异常向网关传播并计入失败，禁止以成功状态返回不完整数据。
- 策略拒绝保留可操作的安全原因，其他 JDBC/驱动异常统一返回通用查询失败信息，避免把物理 SQL、库表结构或连接细节暴露给调用方。
- JDBC、查询加速器和数据库管理 SQL 均在实际执行前进入统一网关，避免加速器命中或管理接口绕过只读策略、限流和性能监控。
- 数据库管理 SQL 的非策略异常统一转换为通用查询失败信息，日志仅保留异常类型和不可逆摘要；策略拒绝原因仍原样保留。
- JDBC 驱动解析、连接测试、重试和资源关闭日志不再记录完整 URL、驱动异常正文或堆栈；不支持的数据源类型和连接失败响应统一使用通用消息。
- JDBC 连接池初始化、连接有效性检查和数据库元数据适配器仅记录 URL/异常不可逆摘要；Flight SQL 的查询、预编译句柄和关闭句柄仅记录摘要，创建或执行失败统一返回固定错误，避免回显 SQL、认证头及驱动细节。
- 语义查询解析、改写、纠错和翻译日志统一记录不可逆摘要；翻译失败响应不再回显 S2SQL 或底层异常消息。
- 复用现有语义结果缓存、Schema 元数据缓存和语义模型缓存，并将查询结果缓存键隔离到用户粒度、鉴权开关和内部原生执行模式，避免权限结果跨用户或跨安全模式复用。
- 结果缓存写入和读取均使用响应快照，隔离结果行、列定义、授权信息和脱敏元数据，防止调用方修改共享缓存对象。
- 提供显式启用的 `QueryGatewayTargetDatabaseIT` 目标数据库验收工具，通过环境变量读取连接和测试 SQL，自动执行预热、平均/P50/P95/P99 采样、至少 5 分钟并发稳定性、JDBC 超时取消、取消后健康检查和数据库端残留语句探针；按场景输出不包含 JDBC URL、凭据或 SQL 的 JSON 报告，并记录客户端 JVM 资源峰值。
- DATA-02 真实接口评测器增加解析、执行、解释和端到端成功链路的平均/P50/P95/P99/最大耗时分布；新增语义缓存 QA-03 运行器，使用一次性缓存键验证冷请求、异步物化和连续热命中，并用超级管理员监控快照复核缓存、物理执行和阶段计数增量。

## 配置

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `s2.query-gateway.max-concurrency` | `20` | 最大并发物理查询数 |
| `s2.query-gateway.acquire-timeout-ms` | `1000` | 获取执行许可的最长等待时间 |
| `s2.query-gateway.max-sql-length` | `100000` | SQL 最大字符数 |
| `s2.query-gateway.max-select-depth` | `16` | SELECT、CTE、集合分支和派生子查询最大嵌套深度 |
| `s2.query-gateway.max-parse-time-ms` | `5000` | SQL 解析最大耗时，超时后取消解析任务 |
| `s2.query-gateway.denied-functions` | 空 | 追加禁止执行的数据库函数，使用逗号分隔，可配置 schema 限定名 |
| `s2.source.query-timeout-seconds` | `30` | JDBC 查询超时 |
| `s2.source.result-limit` | `1000000` | 最大返回行数 |
| `s2.source.explain-cost-check-enabled` | `true` | 是否执行 EXPLAIN 成本检查 |
| `s2.source.explain-max-estimated-rows` | `1000000` | 计划最大估算扫描行数 |
| `s2.source.explain-require-estimate` | `false` | EXPLAIN 无法提取估算行数时是否拒绝查询；目标数据库验证格式后建议设为 `true` |
| `s2.cache.hot-metric.expire.after.write` | `60` | 热点指标缓存写入后过期分钟数 |
| `s2.cache.hot-metric.max.size` | `1000` | 热点指标缓存最大条目数 |

## 验证

- `SqlSafetyPolicyTest`：只读、危险函数、多语句和无界查询。
- `SqlSafetyPolicyAdvancedTest`：注释拆分危险函数、UNION/CTE/嵌套子查询中的无界 `SELECT *`、`TABLE` 全表读取、`VALUES`/PIVOT/LATERAL VIEW/括号 FromItem 与嵌套 JOIN 非标准表达式位置、`SELECT INTO`、行锁和 Oracle/SQL Server/PostgreSQL 序列、会话及 advisory lock 状态变更绕过，覆盖 PostgreSQL 服务端文件及数据库状态函数、SQLite 扩展加载、DuckDB/H2/SQLite/PostgreSQL 外部数据与动态查询函数、ClickHouse/StarRocks 外部连接表函数、SQL Server 命令扩展、DISTINCT ON、TOP、层级查询和命名窗口中的带引号危险函数、目标数据库追加 denylist、并发复用预编译策略、超深 SQL 拒绝、非法配置拒绝及安全常量 `VALUES` 兼容性。
- `JdbcExecutorGatewayCoverageTest`：校验危险 SQL 在进入 JDBC 或查询加速器前被统一网关拒绝，并校验加速器或执行器超大结果被网关计为失败。
- `DatabaseServiceGatewayCoverageTest`：校验数据库管理查询接口不能绕过统一网关，且 JDBC 原始异常不会泄露给调用方。
- `SensitiveQueryLoggingTest`：校验 JDBC URL、结构化查询、语义纠错 SQL、Flight 查询与预编译句柄和异常正文仅以摘要进入日志，且不附带异常堆栈；Flight 创建或查找预编译语句失败时对外仅返回固定错误。
- `DatabaseServicePermissionTest`：校验普通用户不能测试或变更数据库连接，VIEWER 读取数据源详情时不返回密码，数据源管理员仍可读取受控凭据。
- `ModelControllerAccessTest`：校验语义模型详情、批量读取、关联数据源和 Schema 构建均按模型权限或超级管理员身份 fail-closed。
- `SqlFilterUtilsSecurityTest`：校验比较、IN、LIKE 和普通含撇号值均被规范化到单一字符串字面量，不能通过预加引号逃逸过滤条件。
- `QueryStructReqSecurityTest`：校验 WHERE/HAVING 的语法与词法解析失败均转换为通用参数错误并 fail-closed。
- `SemanticQueryRequestSecurityTest`：校验外部 JSON 不能关闭鉴权或开启原生层模式，可信服务端代码仍可显式设置内部模式。
- `SqlQueryApiControllerSecurityTest`：校验批量数量、空语句、普通批量及严格批量失败均 fail-closed，并仅返回通用错误。
- `SqlUtilsResultReadTest`：校验结果集读取异常向上抛出，不返回静默截断的部分结果；目标驱动忽略最大行数时仍由应用层拒绝超限结果；禁止通过零值或负值配置取消超时、结果和成本边界。
- `ExplainCostPolicyTest`：结构化、嵌套 JSON、文本执行计划、数字字符串、超阈值拒绝、异常估算值拒绝及缺失估算 fail-closed。
- `QueryExecutionGatewayTest`：并发许可耗尽时快速拒绝，非法并发/等待/SQL 长度配置 fail-fast，并校验接收和拒绝计数。
- `QueryExecutionGatewayTest`：校验策略拒绝、执行失败、活动查询和平均耗时快照。
- `QueryPerformanceMonitorTest`：校验五阶段耗时聚合、平均值、最大值、P50/P95/P99 和缓存命中率。
- `DefaultQueryCacheTest`：校验结构化指标查询和聚合 SQL 的热点识别、鉴权模式键隔离及缓存响应快照隔离。
- `CaffeineCacheManagerTest`：校验普通结果与热点指标使用独立缓存空间。
- `QueryGatewayMonitorServiceTest`：校验超级管理员访问和普通用户拒绝。
- `QueryGatewayH2IntegrationTest`：基于真实 H2 JDBC 执行验证安全策略、`EXPLAIN`、结果行数限制和并发稳定性。
- `QueryGatewayH2IntegrationTest`：1 秒超时取消长查询，取消后立即执行轻量查询验证资源释放。
- `QueryGatewayTargetDatabaseIT`：显式连接目标数据库，验证真实驱动延迟分位数、长时间并发稳定性、超时取消、连接恢复和数据库端取消探针；默认测试不会连接外部数据库。
- `test_run_supersonic_eval.py`、`test_run_qa03_cache_eval.py`：验证真实 NL2SQL 链路分位数、成功链路隔离、唯一冷缓存键、连续热命中、监控计数和无 SQL/结果数据报告。
- `common`、`auth/authentication`、`auth/authorization`、`headless/core`、`headless/chat`、`headless/server`、`chat/server` 七个目标模块及其上游依赖在 JDK 21 下回归通过，共执行 559 项默认测试（3 项按环境条件跳过），无失败或错误；另有 1 项显式 QA-03 工具运行时自测通过。

## 本地性能基线

- 状态：已完成（2026-07-23）。
- 数据规模：H2 内存数据库，`bank_account` 表 10,000 行。
- 测试规模：20 次预热、200 次串行采样、8 线程 200 次并发查询。
- 最新实测结果：平均 `9.64 ms`、P95 `16 ms`、P99 `29 ms`，并发查询无拒绝。
- 验收结论：本地标准测试环境满足“单轮查询平均响应时间不高于 3 秒”的性能门槛。
- 完整报告：`task/BE-06_PERFORMANCE_REPORT.md`。

## 待环境验收

目标数据库下的 P95/P99、长时间稳定性和对应 JDBC 驱动的超时取消效果依赖稳定压测环境，继续纳入 QA-03 执行。仓库已提供可复现验收工具，但在取得目标 JDBC 地址、只读压测账号、代表性查询和数据库活动会话读取权限前，不将工具存在视为验收通过。
