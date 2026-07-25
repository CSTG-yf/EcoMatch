# BE-09 审计与异常行为告警实现说明

## 状态

- 后端开发完成（2026-07-25）。
- 已具备审计事件采集、安全清洗、独立事务持久化、链路完整性校验、异常规则、告警去重、告警处置和管理接口。
- BE-09 后端已经解锁 FE-08 页面联调和 QA-02 后端安全测试；最终验收仍需 FE-08、QA-02、正式身份属性接入和生产规则确认。

## 核心流程

```text
业务事件
  -> AuditSanitizer 安全清洗
  -> AuditEventMutationService 独立事务追加
  -> traceId 级 SHA-256 哈希链
  -> AuditAnomalyEngine 规则判断
  -> 指纹去重 + CAS 告警新增或更新
  -> 告警状态流转 + AlertAction 处置留痕
```

- 普通查询使用 `publishBestEffort`：审计故障会记录安全错误，但不影响普通查询主流程。
- 导出、审计后台访问、规则修改、告警处置和权限拒绝使用 `publishRequired`：审计事件无法持久化时操作失败；告警计算异常不会回滚已经写入的审计事件。
- 审计事件和告警证据变更使用 `REQUIRES_NEW` 独立事务，避免业务事务回滚同时抹掉安全记录。

## 已实现

### 1. 审计事件采集

- 语义查询记录 `QUERY_STARTED`、`QUERY_SUCCEEDED` 和 `QUERY_FAILED`，包含用户、机构、链路、指标、耗时、结果状态以及 SQL 和问题摘要。
- 下载导出记录 `EXPORT_STARTED`、`EXPORT_SUCCEEDED` 和 `EXPORT_FAILED`，包含导出行数、文件类型和文件大小。
- 事件模型同时支持解析、执行、授权、脱敏、对象访问、分享、审计访问、规则变更和告警状态变更等事件类型，后续模块可复用同一发布接口接入。
- 审计列表、事件详情和完整 trace 在读取时重新校验事件哈希；trace 按前驱哈希校验整条链路。

### 2. 安全清洗与防泄露

- `AuditSanitizer` 清洗 Authorization Bearer Token、JSON 中的密码/Token/Cookie 字段、常见 Token 变体以及中文“密码、口令、令牌”。
- 手机号、身份证、银行卡/账号和邮箱只保存脱敏值或不可逆摘要。
- SQL 只保存语句类型和 SHA-256 摘要，不保存物理 SQL 明文。
- 自然语言问题保存脱敏文本和 SHA-256 摘要；IP 与 User-Agent 只保存摘要。
- metadata 采用白名单字段，不允许调用方把任意原始对象写入审计表。

### 3. 完整性与并发控制

- 每个 `traceId` 使用固定根哈希和 `previous_hash/event_hash` 形成 SHA-256 追加链。
- 进程内使用 64 段锁降低同一 trace 并发分叉概率，数据库唯一约束负责最终冲突检测，追加冲突最多重试 5 次。
- 告警按照规则、用户、机构、资源和时间桶生成 SHA-256 fingerprint，相同异常窗口合并为同一告警。
- 并发新增捕获 fingerprint 唯一键冲突；证据更新使用 version CAS，最多重试 5 次。
- 单条告警最多保留最近 50 个审计事件 ID，并维护首次发生、最近发生和累计次数。

### 4. 异常规则与告警处置

启动时幂等初始化以下默认规则：

| 规则 | 默认条件 | 严重度 |
| --- | --- | --- |
| `HIGH_FREQUENCY_QUERY` | 60 秒内查询达到 60 次 | `MEDIUM` |
| `BULK_EXPORT` | 300 秒内导出达到 5 次，或单次导出不少于 10,000 行 | `HIGH` |
| `REPEATED_AUTH_DENIAL` | 300 秒内权限拒绝达到 3 次 | `HIGH` |
| `OFF_HOURS_ACCESS` | 07:00–22:00 之外访问 | `MEDIUM` |

- 引擎还支持 `SENSITIVE_RESOURCE_ACCESS`，但不创建默认规则，需要按生产敏感资源清单配置。
- 告警状态允许以下流转：

```text
NEW -> ACKNOWLEDGED -> RESOLVED -> CLOSED
NEW/ACKNOWLEDGED -> CLOSED 或 DISMISSED
```

- 除 `ACKNOWLEDGED` 外的处置必须填写说明；说明写入前也会脱敏。
- 每次确认、解决、关闭或忽略均写入 `s2_alert_action`，保存操作者、前后状态、处置动作和时间。

## 权限与机构范围

| 操作 | 允许身份 |
| --- | --- |
| 读取审计、规则和告警 | `SECURITY_ADMIN`、`SECURITY_AUDITOR`、`RISK_AUDITOR` |
| 修改规则和处置告警 | `SECURITY_ADMIN` |

- 超级管理员以及 `s2.security.audit.admin-users`、`s2.security.audit.auditor-users` 中的显式用户名可按对应权限访问。
- 非全局身份必须从可信用户属性中携带 `organizationId`、`organizationCode`、`orgId` 或 `departmentId`；列表查询会强制覆盖为本机构，事件、trace 和告警详情逐条校验机构范围。
- 审计事件和告警列表在 SQL 机构过滤后还会逐条执行 Java 精确比较并失败关闭，避免 MySQL 不区分大小写的排序规则把不同机构编码误判为相同值；机构属性写入前统一去除首尾空格。
- 只有超级管理员、显式配置的全局用户名或携带 `auditScope=GLOBAL` 的身份可以跨机构；审计规则修改只允许全局安全管理员。
- 当前默认 JWT 链路尚未完整传播 `roles/attributes`，因此现阶段运行时主要依赖超级管理员或显式配置的全局用户名。接入正式 JWT/SSO 后，才能启用完整角色和机构 ABAC。

## 管理接口

| 方法 | 接口 | 用途 |
| --- | --- | --- |
| `GET` | `/api/security/audit/events` | 分页查询审计事件 |
| `GET` | `/api/security/audit/events/{eventId}` | 查询并校验单条审计事件 |
| `GET` | `/api/security/audit/traces/{traceId}` | 查询并校验完整审计链路 |
| `GET` | `/api/security/audit/rules` | 查询告警规则 |
| `POST` | `/api/security/audit/rules` | 新增告警规则 |
| `PUT` | `/api/security/audit/rules/{id}` | 按版本更新告警规则 |
| `GET` | `/api/security/alerts` | 分页查询安全告警 |
| `GET` | `/api/security/alerts/{alertId}` | 查询告警、证据和处置记录 |
| `PUT` | `/api/security/alerts/{alertId}/status` | 确认、解决、关闭或忽略告警 |

分页接口支持按用户、机构、事件类型、结果状态、告警规则、告警状态、严重度、资源和时间范围筛选，页大小和 trace 最大返回量受配置限制。

## 数据表

| 表 | 用途 |
| --- | --- |
| `s2_audit_event` | 追加保存安全清洗后的审计事件和哈希链字段 |
| `s2_audit_rule` | 保存异常检测规则、阈值、时间窗、工作时间和版本 |
| `s2_security_alert` | 保存去重后的告警、证据索引、状态和发生次数 |
| `s2_alert_action` | 保存告警状态变更和处置说明 |

H2、MySQL、PostgreSQL 初始化脚本和 MySQL 升级脚本均已包含四张表；chat、headless、standalone 三种 H2 启动方式均可初始化。三份 H2 的 BE-09 DDL 已由 H2 2.2.224 实际加载，完整 PostgreSQL Schema 已由 PostgreSQL 17 一次性数据库验证通过；MySQL 初始化与升级块已逐字段和索引复核一致。

## 配置

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `s2.security.audit.enabled` | `true` | 审计总开关；关闭时 required 操作失败、best-effort 事件跳过 |
| `s2.security.audit.zone-id` | `Asia/Shanghai` | 工作时间规则使用的时区 |
| `s2.security.audit.maximum-page-size` | `100` | 审计和告警列表最大页大小 |
| `s2.security.audit.maximum-trace-events` | `500` | 单次 trace 查询最大事件数 |
| `s2.security.audit.admin-users` | 空 | 显式全局安全管理员用户名，逗号分隔 |
| `s2.security.audit.auditor-users` | 空 | 显式全局审计员用户名，逗号分隔 |

## 验证

- 核心单元测试覆盖 `AuditSanitizerTest`、`AuditEventServiceTest`、`AuditAnomalyEngineTest`、`AuditRuleServiceTest`、`AuditRuleInitializerTest`、`SecurityAlertServiceTest` 和 `AuditAccessGuardTest`。
- 控制器测试 `AuditManagementControllerTest` 覆盖读写角色、机构隔离、规则管理和告警处置。
- 链路测试 `S2SemanticLayerServiceAuditTest`、`DownloadServiceAuditTest` 和 `StatUtilsSecurityTest` 覆盖查询、required 导出审计及统计信息防泄露。
- Headless 统一回归 71/71 通过，覆盖外部 `needAuth=false` 绕过、MySQL 机构大小写碰撞、哈希链、告警 CAS、规则并发初始化、管理接口、查询、导出、脱敏和日志防泄露。
- Chat Server 现有 6 组测试共 32/32 通过；Common 安全/MDC 测试 9/9、Auth Token 测试 1/1 通过。
- Chat Server 连同 12 个依赖模块编译成功；8 个相关 Maven 模块的 Spotless 检查通过。

## 最终验收前待完成

- FE-08 接入上述审计检索、规则管理和告警处置接口。
- QA-02 完成越权、敏感数据外泄、提示词注入、审计完整性和告警触发的全链路安全回归。
- 正式 JWT/SSO 传播 `roles` 和 `attributes`，并验证机构级 ABAC。
- 按生产机构、岗位、敏感等级和资源清单确认阈值、工作时间及 `SENSITIVE_RESOURCE_ACCESS` 规则。
- 在目标数据库执行生产并发压测、容量和保留周期验证。
- 后续增强事务型 outbox，以及外部 HMAC 或不可篡改存储锚点；当前 SHA-256 哈希链用于发现库内篡改，不能替代外部可信时间戳或 WORM 存储。
