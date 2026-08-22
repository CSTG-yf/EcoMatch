# SuperSonic 细粒度数据权限与安全审计落地方案

## 1. 文档信息

| 项目 | 内容 |
|---|---|
| 文档版本 | V1.0 |
| 编制日期 | 2026-08-20 |
| 基线分支 | `origin/main` |
| 基线提交 | `50b9eda29` |
| 实施方式 | 任务相关性高，由一位同学端到端统一完成 |
| 预计工期 | 20 个工作日，包含开发、迁移、联调、测试和灰度上线 |

## 2. 建设背景

SuperSonic 已具备权限主体匹配、行列权限校验、查询结果脱敏、审计哈希链和异常告警等基础能力。本次建设不重新实现一套平行权限系统，而是在现有能力上统一身份上下文、权限策略、授权决策、动态脱敏、缓存隔离和审计告警链路，形成可配置、可执行、可追溯、可回滚的数据安全闭环。

建设范围包括：

- 机构级权限：按机构树控制用户可访问的数据组织范围。
- 岗位级权限：按岗位或角色控制模型、指标、维度和原值访问能力。
- 行级权限：在查询执行前强制注入机构和业务数据过滤条件。
- 列级权限：为每个指标或维度配置禁止、脱敏、原值三种访问模式。
- 动态脱敏：覆盖 API、Chat、缓存、导出、看板、分享和 LLM 摘要。
- 审计告警：记录全操作链路，检测异常访问并形成处置闭环。

## 3. 建设原则

1. 默认拒绝。身份、模型、权限、字段血缘或行规则无法确认时禁止执行。
2. 机构边界强制生效。岗位权限不得放大到用户所属机构范围之外。
3. 显式拒绝优先。`DENY` 的优先级高于任何允许策略。
4. 一次授权、全程复用。每次查询只生成一个不可变授权决策。
5. 脱敏前置。敏感原值不得进入缓存、导出、分享或 LLM。
6. 全程留痕。权限判断、SQL 执行、脱敏、导出和告警处置均关联同一 `traceId`。
7. 增量迁移。兼容已有 `s2_auth_groups.config`，通过双读逐步迁移到新策略。

## 4. 总体架构

```text
用户登录
  → 解析机构、岗位和用户属性
  → 生成统一安全上下文
  → 计算授权决策
      ├─ 模型访问权限
      ├─ 机构强制边界
      ├─ 岗位/用户授权
      ├─ 行过滤规则
      └─ 列访问模式：DENY / MASKED / RAW
  → SQL AST 注入行权限
  → 执行查询
  → 动态脱敏
  → 缓存 / Chat / 导出 / 分享 / LLM
  → 审计事件
  → 异常规则检测
  → 告警通知与处置
```

最终权限计算规则：

```text
最终行范围 = 机构强制边界
           AND（岗位允许策略 OR 用户特授策略）
           AND NOT 显式拒绝范围

列权限冲突优先级：DENY > RAW > MASKED > 默认策略

默认策略：
- 高敏字段：拒绝访问；
- 中敏字段：允许查询但必须脱敏；
- 普通字段：允许访问。
```

## 5. 现有能力与改造方向

| 能力 | 现有实现 | 本期改造 |
|---|---|---|
| 用户、机构、岗位、属性匹配 | `AuthGroup`、`AuthGroupMatcher` | 补充策略状态、有效期、机构范围、拒绝策略和统一岗位语义 |
| 行列权限 | `S2DataPermissionAspect` | 结构化行规则、字段白名单、列访问模式和统一授权决策 |
| 动态脱敏 | `DataMaskingService` | 按字段策略脱敏，并前移到缓存和下游消费之前 |
| 查询缓存 | `DefaultQueryCache` | 加入策略版本，禁止缓存未脱敏敏感结果 |
| 审计留痕 | `AuditEvent*`、哈希链 | 补充策略、行过滤和列权限事件，增加完整性巡检 |
| 异常告警 | `AuditAnomalyEngine`、`SecurityAlertService` | 增加跨机构、敏感访问和策略变更规则，补充通知 SPI |
| 管理前端 | `Permission`、`SecurityOperations` | 结构化策略编辑、权限预览和完整 trace 时间线 |

## 6. 详细改造方案

### 6.1 统一身份安全上下文

新增 `UserSecurityContextResolver`，在认证完成后统一生成安全上下文，并供权限、缓存、审计、导出和分享复用。

安全上下文至少包含：

```java
class UserSecurityContext {
    String userName;
    String primaryOrganizationId;
    Set<String> effectiveOrganizationIds;
    Set<String> roles;
    Map<String, String> attributes;
}
```

修改要求：

- 使用 `UserService.getUserAllOrgId()` 获取有效机构集合。
- Token 中的 `roles` 作为岗位编码，统一大小写、空白和重复值。
- 兼容 `organizationId`、`organizationCode`、`orgId`、`departmentId` 等历史字段，但内部只传播标准字段。
- 普通用户缺少机构身份时按无权限处理。
- 外部系统调用必须构造同样的安全上下文，禁止通过管理员用户绕过权限。

### 6.2 扩展权限策略模型

修改 `AuthGroup`，增加：

```java
String policyCode;
Boolean enabled;
Integer priority;
PolicyEffect effect;       // ALLOW、DENY
Long version;
Date validFrom;
Date validTo;
OrgScopeType orgScope;     // CURRENT、CURRENT_AND_CHILDREN、CUSTOM
List<RowFilterRule> rowFilterRules;
List<ResourcePermission> resourcePermissions;
```

新增字段权限模型：

```java
class ResourcePermission {
    String resourceType;       // METRIC、DIMENSION
    String resourceName;
    ColumnAccessMode accessMode; // DENY、MASKED、RAW
    String maskingStrategy;      // FULL、LAST4、FIRST_LAST、HASH
}
```

新增结构化行规则：

```java
class RowFilterRule {
    String field;
    String operator;       // EQ、IN、BETWEEN、LIKE
    List<String> values;
    String valueSource;    // CONSTANT、USER_ATTRIBUTE、ORG_SCOPE
}
```

保留旧的 `authRules` 和 `dimensionFilters` 作为兼容字段：

- 旧配置继续读取，并转换为统一授权决策。
- 旧资源授权解释为“允许查询”，原值权限仍按旧脱敏配置判断。
- 新配置只写新结构。
- 旧自由 SQL 行表达式只允许查看和迁移，不再允许新增。

### 6.3 标准化授权决策

扩展 `AuthorizedResourceResp`，或者新增不可变的 `AuthorizationDecision`：

```java
class AuthorizationDecision {
    Map<ResourceKey, ColumnAccessMode> columnAccessModes;
    List<CompiledRowFilter> rowFilters;
    Set<Long> matchedPolicyIds;
    long policyVersion;
    Set<String> effectiveOrganizationIds;
    String decisionReason;
}
```

`AuthServiceImpl` 按以下顺序计算：

1. 加载请求模型对应的启用策略。
2. 去除无效或已过期策略。
3. 匹配用户、机构、岗位和属性。
4. 计算机构强制边界。
5. 合并岗位和用户允许策略。
6. 应用显式拒绝策略。
7. 生成字段访问模式和行规则。
8. 生成策略 ID 集合和 `policyVersion`。

### 6.4 数据库改造

在 `s2_auth_groups` 增加可检索和可控制字段：

```sql
ALTER TABLE s2_auth_groups ADD COLUMN model_id BIGINT;
ALTER TABLE s2_auth_groups ADD COLUMN policy_code VARCHAR(128);
ALTER TABLE s2_auth_groups ADD COLUMN enabled SMALLINT DEFAULT 1;
ALTER TABLE s2_auth_groups ADD COLUMN policy_version BIGINT DEFAULT 1;
ALTER TABLE s2_auth_groups ADD COLUMN valid_from TIMESTAMP;
ALTER TABLE s2_auth_groups ADD COLUMN valid_to TIMESTAMP;
ALTER TABLE s2_auth_groups ADD COLUMN updated_at TIMESTAMP;
ALTER TABLE s2_auth_groups ADD COLUMN updated_by VARCHAR(128);

CREATE INDEX idx_auth_group_model_enabled
ON s2_auth_groups(model_id, enabled);
```

同步修改：

- `launchers/standalone/src/main/resources/db/schema-mysql.sql`
- `launchers/standalone/src/main/resources/db/schema-postgres.sql`
- `launchers/standalone/src/main/resources/db/schema-h2.sql`
- `launchers/standalone/src/main/resources/config.update/sql-update-mysql.sql`
- Headless 和 Chat 独立启动器使用的 H2 初始化脚本。

迁移过程：

1. 新字段先允许为空，读取时回退解析 `config`。
2. 启动迁移任务回填 `model_id`、`policy_code` 和 `policy_version`。
3. 回填完成后改为按 `model_id + enabled` 查询，避免扫描全部权限组。
4. 权限修改采用乐观锁，更新成功后递增 `policyVersion`。
5. 至少保留一个版本的旧配置兼容能力。

### 6.5 机构级和岗位级权限

修改 `AuthGroupMatcher` 和 `AuthServiceImpl`：

- 用户、机构、岗位主体之间使用 OR。
- `attributeConditions` 与主体匹配结果使用 AND。
- 禁用或过期策略不参与计算。
- 机构范围支持当前机构、当前机构及下级、指定机构集合。
- 机构强制边界与岗位授权使用 AND。
- 同一模型多个允许策略的业务行范围使用 OR。
- 显式拒绝优先于任何允许策略。

### 6.6 行级权限

修改 `S2DataPermissionAspect`，将新行规则统一编译为 JSqlParser AST：

1. 校验过滤字段属于当前语义模型。
2. 操作符必须在白名单内。
3. 常量值统一转义，禁止直接拼接用户输入。
4. `USER_ATTRIBUTE` 和 `ORG_SCOPE` 只能从服务端安全上下文取值。
5. 机构边界与业务行范围使用 AND。
6. 同一模型多个允许范围使用 OR。
7. SQL 查询和结构化查询使用相同的编译结果。
8. 解析失败、字段不存在或模型作用域异常时拒绝执行。

示例：

```sql
(org_id IN ('A', 'A01', 'A02'))
AND
(customer_level IN ('VIP', 'KEY'))
```

### 6.7 列级权限

查询执行前按字段访问模式处理：

| 访问模式 | 行为 |
|---|---|
| `DENY` | SQL 执行前直接拒绝 |
| `MASKED` | 允许执行，但字段必须进入脱敏集合 |
| `RAW` | 允许返回原值，并记录高敏原值访问审计 |

同时执行以下默认策略：

- 未配置的高敏字段默认拒绝。
- 未配置的中敏字段默认脱敏。
- 普通字段默认允许。
- 字段血缘缺失时默认全遮蔽或直接拒绝，禁止按普通字段放行。

### 6.8 动态脱敏

修改 `DataMaskingService`：

- 优先使用本次 `AuthorizationDecision` 的字段访问模式。
- 全局 `raw-users/raw-roles` 仅作为兼容和超级管理员兜底配置。
- 支持 `FULL`、`LAST4`、`FIRST_LAST`、`HASH`。
- 支持手机号、证件号、银行卡、账号和邮箱专用策略。
- 对未知字段、别名碰撞和缺失血缘采用 fail-closed。
- 返回 `dataMasked`、`maskedColumns` 和 `maskingPolicyVersion`。

必须保证执行顺序为：

```text
查询执行 → 动态脱敏 → 写缓存 → Chat/导出/分享/LLM
```

严禁未脱敏敏感结果进入缓存或发送给大模型。

### 6.9 缓存和下游链路

修改 `DefaultQueryCache`，缓存键至少包含：

```text
查询命令
+ 用户名
+ 机构集合
+ 岗位集合
+ 用户属性
+ policyVersion
```

落地要求：

- 权限修改后通过 `policyVersion` 自动失效旧缓存。
- 敏感查询只允许缓存脱敏完成后的结果。
- 导出任务必须按任务执行时身份重新鉴权。
- 浏览器不能上传结果快照作为导出数据源。
- Chat 摘要、同比环比、业务洞察和分享页面只消费完成权限处理的结果。

### 6.10 审计留痕

复用现有审计表和哈希链，以 `traceId` 关联：

- 用户、机构和岗位。
- 匹配策略 ID 和策略版本。
- 模型、指标和维度。
- 授权允许/拒绝及原因码。
- SQL 类型和摘要，不记录敏感 SQL 明文。
- 行权限摘要和脱敏字段数量。
- 导出行数、文件类型、文件大小。
- 客户端 IP、User-Agent 哈希。
- 操作耗时和结果状态。

新增事件类型：

```text
POLICY_CREATED
POLICY_UPDATED
POLICY_DISABLED
POLICY_PREVIEWED
ROW_FILTER_APPLIED
COLUMN_ACCESS_DENIED
MASK_APPLIED
```

新增审计哈希链定时巡检，发现断链或事件内容被修改时生成最高级别告警。

### 6.11 异常行为告警

在现有异常检测引擎中增加：

- 跨机构访问探测。
- 单用户短时间高频查询。
- 连续权限拒绝。
- 高频访问敏感字段。
- 大批量或频繁导出脱敏数据。
- 非工作时间访问。
- 权限策略频繁修改。
- 审计哈希链异常。

新增通知扩展接口：

```java
public interface SecurityAlertNotifier {
    void notify(SecurityAlert alert);
}
```

首期实现站内告警和通用 Webhook，后续可扩展企业微信和邮件。告警继续支持去重指纹、累计次数、确认、解决、关闭和处置意见。

### 6.12 管理端改造

权限配置页面增加：

- 机构范围选择器。
- 岗位可搜索多选。
- 结构化行权限编辑器。
- 字段级禁止、脱敏、原值选择。
- 字段脱敏策略选择。
- 策略有效期、优先级和启停状态。
- 按用户预览最终机构、行和列权限。

安全运营页面增加：

- 按机构、用户、策略和事件类型筛选。
- 展示策略版本、行权限和脱敏摘要。
- 按 `traceId` 展示完整操作时间线。
- 展示审计哈希链校验结果。
- 支持告警确认、解决、关闭和处置意见。

## 7. 代码改造清单

| 模块 | 主要文件或目录 | 修改内容 |
|---|---|---|
| 权限 API | `auth/api/.../authorization` | 扩展 `AuthGroup`、`AuthRule`、`AuthorizedResourceResp`，新增行规则和列访问模式 |
| 权限计算 | `AuthGroupMatcher`、`AuthServiceImpl` | 机构/岗位/属性匹配、拒绝优先、有效期、策略版本、双读迁移 |
| 查询拦截 | `S2DataPermissionAspect` | 统一授权决策、AST 行过滤、列权限和审计事件 |
| 动态脱敏 | `DataMaskingService` | 按字段访问模式脱敏，增加策略，缺失血缘时 fail-closed |
| 查询缓存 | `DefaultQueryCache` | 加入策略版本，脱敏后缓存，隔离不同身份结果 |
| 审计告警 | `headless/server/.../security/audit` | 事件补齐、哈希巡检、异常规则和通知 SPI |
| 数据库 | Standalone、Headless、Chat 数据库脚本 | 权限索引字段、策略版本字段和兼容迁移 |
| 前端 | `Permission`、`SecurityOperations` | 结构化策略编辑、权限预览、审计与告警运营 |

## 8. 单人实施计划

任务各环节共享身份上下文、策略版本和查询执行链路，建议由同一位同学完整负责，减少跨人交接形成安全缺口。

| 阶段 | 时间 | 主要工作 | 交付物 |
|---|---:|---|---|
| 需求与基线 | 第 1-2 天 | 冻结权限语义、兼容规则和验收矩阵 | 设计确认稿、测试矩阵 |
| 策略与迁移 | 第 3-6 天 | 扩展模型、数据库字段、双读和回填 | 迁移脚本、策略 V2 API |
| 权限执行 | 第 7-10 天 | 机构/岗位计算、行 AST、列模式、缓存版本 | 授权决策引擎、集成测试 |
| 脱敏与下游 | 第 11-13 天 | 脱敏策略及 Chat、缓存、导出、分享链路 | 端到端脱敏闭环 |
| 审计与告警 | 第 14-16 天 | 事件补齐、规则、通知和运营页面 | 审计告警闭环 |
| 验收与上线 | 第 17-20 天 | 前端联调、性能测试、安全测试和灰度 | 验收报告、上线回滚手册 |

## 9. 测试与验收

| 验收场景 | 预期结果 |
|---|---|
| A 机构用户查询 B 机构 | 无数据或明确拒绝，SQL、Chat、导出、分享结果一致 |
| 总行岗位访问下级机构 | 只返回授权机构树范围内的数据 |
| 柜员查询手机号或卡号 | 允许查询，但所有输出通道保持一致脱敏 |
| 风控岗位访问授权高敏字段 | 按字段策略返回原值，并记录原值访问审计 |
| 未授权高敏列 | SQL 执行前拒绝，不触达数据源 |
| 多岗位权限叠加 | 允许范围取并集 |
| 存在显式拒绝策略 | 拒绝优先 |
| 权限修改后查询缓存 | `policyVersion` 不同，不返回旧结果 |
| Chat、导出和分享 | 脱敏状态和字段内容一致 |
| 连续权限拒绝或跨机构探测 | 触发告警、完成去重和累计，并可处置 |
| 审计记录被修改 | 哈希校验失败并生成高危告警 |
| 性能门禁 | P95 查询额外开销不超过 10% |

建议新增端到端测试 `S2DataSecurityIntegrationTest`，构造至少两个机构、三个岗位、五个用户以及高、中、低敏字段，完整验证授权、SQL 注入、脱敏、缓存、导出、审计和告警链路。

## 10. 上线与回滚

增加配置：

```properties
s2.security.policy-v2.enabled=false
s2.security.policy-v2.mode=SHADOW
```

上线步骤：

1. 发布数据库兼容字段和双读代码。
2. 执行旧权限策略回填。
3. 开启 `SHADOW`，新旧引擎同时计算，但仍使用旧结果。
4. 将新旧决策差异写入审计，处理完全部差异。
5. 选择测试机构开启 `ENFORCE`。
6. 按机构逐步扩大灰度范围。
7. 全量启用。
8. 保留旧引擎开关一个版本，稳定后再删除旧路径。

回滚时只需关闭策略 V2 或切回旧引擎，不回退已经兼容新增字段的数据库结构。

上线门禁：

- 权限安全用例全部通过。
- 无跨机构、缓存、导出和 LLM 数据泄露。
- 审计事件完整率为 100%。
- 默认异常规则能够触发并完成处置。
- 数据库迁移脚本可重复执行。
- P95 查询额外开销不超过 10%。
- 具备一键切换旧引擎的回退能力。

## 11. 风险与控制

| 风险 | 控制措施 |
|---|---|
| 旧行权限 SQL 存在注入或语义偏差 | 旧配置只读兼容并生成迁移告警；新配置强制使用结构化 DSL |
| 脱敏发生在缓存之后 | 调整执行顺序，并增加敏感结果缓存门禁测试 |
| 机构属性存在多个历史字段 | 统一 `UserSecurityContextResolver`，内部只传播标准字段 |
| 策略变更导致缓存脏读 | `policyVersion` 进入缓存键，策略更新时版本原子递增 |
| 审计同步写入影响性能 | 关键安全事件可靠写入；压测后对普通事件引入受控队列或 Outbox |
| 单人任务范围较大 | 按六个里程碑交付，每阶段形成可独立回归的完整闭环 |

## 12. 最终交付物

- 权限策略 V2 数据模型、API 和数据库迁移脚本。
- 机构级、岗位级、行级、列级授权决策引擎。
- 覆盖 Chat、API、缓存、导出、看板、分享和 LLM 的动态脱敏闭环。
- 全操作链路审计、哈希完整性校验和异常行为告警处置能力。
- 权限配置、安全运营前端和用户权限预览。
- 单元测试、端到端安全测试和性能测试报告。
- 权限迁移手册、上线手册和回滚手册。

## 13. 完成定义

本需求只有在以下环节全部通过验收后才视为完成：

1. 机构、岗位、行、列四级权限均能配置和执行。
2. Chat、查询、缓存、导出、看板、分享和 LLM 均无法绕过权限与脱敏。
3. 所有关键操作均能通过 `traceId` 还原完整链路。
4. 异常行为能够生成告警并完成处置闭环。
5. 兼容旧权限配置并完成灰度迁移。
6. 具备性能门禁、上线开关和安全回滚能力。

不以单个后端接口、单个权限页面或单条审计记录完成作为需求结项标准。
