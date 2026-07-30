# FE-08B 审计告警后台实现说明

## 1. 完成范围

- 新增安全运营入口，按安全角色控制菜单访问，服务端继续作为最终权限边界。
- 审计检索支持按事件类型、状态、操作者、资源、机构、时间范围和追踪编号筛选。
- 审计详情展示哈希链状态和已清洗事件字段，可按追踪编号复现操作链路。
- 告警列表支持机构隔离后的查询、证据查看、处置历史和乐观锁版本。
- 安全管理员可按后端状态机执行确认、解决、关闭和驳回；终态处置必须填写说明。
- 异常规则支持查询、新建、编辑、启停、阈值、时间窗、严重级别和事件类型配置。
- 页面覆盖加载、空数据、接口失败和只读角色状态，不缓存或展示原始敏感载荷。

## 2. 接口映射

| 页面能力 | 后端接口 |
| --- | --- |
| 审计事件列表与详情 | `GET /api/security/audit/events`、`GET /api/security/audit/events/{id}` |
| 操作链路 | `GET /api/security/audit/traces/{traceId}` |
| 异常规则 | `GET/POST /api/security/audit/rules`、`PUT /api/security/audit/rules/{id}` |
| 告警列表与详情 | `GET /api/security/alerts`、`GET /api/security/alerts/{alertId}` |
| 告警处置 | `PUT /api/security/alerts/{alertId}/status` |

## 3. 权限边界

- `SECURITY_ADMIN`：查询、规则维护和告警处置。
- `SECURITY_AUDITOR`、`RISK_AUDITOR`：只读查询。
- 超级管理员：完整访问。
- 机构隔离、事件清洗、规则写入和状态迁移均由 BE-09 再次校验，前端权限仅控制交互入口。

## 4. 验证结果

- `pnpm --filter supersonic-fe build:os`：生产构建通过并生成 `/security` 页面。
- FE-08B 状态模型覆盖状态迁移、处置说明、分页响应、文件大小和读写角色测试。
- 仓库 `supersonic-fe test` 的既有 `pretest` 指向缺失的 `tests/beforeTest`，无法作为当前执行入口；BE-09 的 QA-02B 已有 10 个测试类、50 个用例通过。

## 5. 后续依赖

FE-08B 已解除 QA-02C 的页面依赖。最终安全门禁仍等待 FE-06、正式身份属性和生产规则。
