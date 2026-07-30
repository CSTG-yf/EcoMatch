# FE-08A 权限策略后台

## 完成状态

- 状态：已完成
- 完成日期：2026-07-30
- 下游任务：FE-08B、QA-02C

## 前端实现

1. 在模型权限组中补齐用户、组织、角色和用户属性四类授权主体。
2. 用户属性采用键值条件编辑器，保存为 BE-08 的 `attributeConditions`；所有属性条件同时满足时策略生效。
3. 保留敏感维度和指标的列授权、行过滤表达式与说明，并在权限组表格展示角色、属性和行权限摘要。
4. 新增生效范围预览，统一展示主体、属性条件、维度数、指标数和行过滤范围；无有效主体时在前端阻止保存。
5. 权限组列表和编辑入口仅对超级管理员展示，普通模型管理员不能访问权限组管理能力。
6. 系统设置新增“数据安全策略”模块，可编辑原值访问用户、原值访问角色以及 `FULL`、`LAST4`、`FIRST_LAST` 字段策略。

## 后端补强

1. 脱敏配置复用 `s2_system_config` 持久化，不新增数据库表。
2. `DataMaskingService` 在首次脱敏请求时校验配置，并按配置版本缓存及热更新脱敏策略，配置修改后无需重启。
3. 系统参数 GET/POST 在服务端统一要求超级管理员身份；缺少安全守卫时 fail-closed。
4. QA-02A 新增系统配置控制器和管理员守卫测试，并将动态配置热更新纳入脱敏测试。

## 验收结果

```text
pnpm --filter supersonic-fe run build:os
Webpack: Compiled successfully

python evaluation/run_qa02a.py --output task/QA-02A_ACCEPTANCE_REPORT.json
Controls:     7/7
Test classes: 29/29
Test cases:   148/148
Failures:     0

mvn -pl launchers/standalone -am -Dtest=SchemaAuthTest test
Tests:        7/7
Failures:     0
```

## 剩余边界

FE-08A 不包含审计事件、哈希链状态、异常规则、告警列表和告警处置页面，这些由 FE-08B 完成。正式身份属性值和生产脱敏规则仍需在目标环境配置后由 QA-02C 验收。
