# QA-02C 正式环境安全门禁

该门禁在已通过 QA-02C 仓库门禁的前提下，对目标部署环境执行黑盒安全验收。执行器不会把 Token、资源地址、金融数据或应用日志写入仓库。

## 执行链

```text
QA-02C 仓库报告 PASS
  -> 配置正式身份和生产权限/脱敏/审计规则
  -> 创建专用看板、导出、分享、会话测试资源
  -> 截取本轮测试应用日志
  -> 运行环境门禁
  -> 8/8 控制项和 14/14 场景 PASS
  -> 关闭 QA-02C 环境验收和 BE-09 最终验收
```

## 环境变量

| 类型 | 环境变量 | 要求 |
| --- | --- | --- |
| 服务 | `BANK_QA_BASE_URL` | 正式环境 HTTPS 根地址 |
| 身份 | `BANK_QA_AUDITOR_TOKEN` | 有审计查询权限的测试身份 |
| 身份 | `BANK_QA_MASKED_USER_TOKEN` | 命中动态脱敏规则的测试身份 |
| 身份 | `BANK_QA_ORG_A_OWNER_TOKEN` | A 机构资源所有者 |
| 身份 | `BANK_QA_ORG_B_USER_TOKEN` | B 机构普通用户，用于反向越权测试 |
| 身份断言 | `BANK_QA_ORG_A_OWNER_NAME`、`BANK_QA_ORG_A_ID` | 与所有者正式身份属性一致 |
| 资源 | `BANK_QA_DASHBOARD_ID` | A 机构所有者创建的看板 |
| 资源 | `BANK_QA_EXPORT_TASK_ID` | A 机构所有者创建且可下载的导出任务 |
| 资源 | `BANK_QA_MASKED_SHARE_TOKEN` | maskedUser 可访问、B 机构不可访问的分享 |
| 资源 | `BANK_QA_ALLOWED_SHARE_TOKEN` | A 机构所有者可访问、B 机构不可访问的分享 |
| 资源 | `BANK_QA_ORG_A_QUERY_ID`、`BANK_QA_ORG_A_CHAT_ID` | A 机构所有者的查询和会话 |
| 探针 | `BANK_QA_RAW_SENSITIVE_VALUE` | 脱敏前测试值，仅用于泄漏断言 |
| 探针 | `BANK_QA_TEST_ACCOUNT_NO` | 专用测试账号，仅用于泄漏断言 |
| 日志 | `BANK_QA_APPLICATION_LOG` | 覆盖本轮 13 个 HTTP 场景的应用日志文件 |

测试资源必须使用专用测试数据，不得使用真实客户凭据或生产客户金融数据。Token 和探针值只通过进程环境注入，不得写入配置文件、命令参数或版本库。

## 执行

先确认 `task/QA-02C_REPOSITORY_ACCEPTANCE_REPORT.json` 为当前提交生成的 `PASS` 报告，再执行：

```powershell
python evaluation/run_qa02c_environment.py `
  --config evaluation/qa02c_environment.template.json `
  --repository-report task/QA-02C_REPOSITORY_ACCEPTANCE_REPORT.json `
  --output task/QA-02C_ENVIRONMENT_ACCEPTANCE_REPORT.json
```

正式环境只接受 HTTPS。`--allow-http` 仅供执行器的本机隔离测试使用，且只允许 `localhost` 或 `127.0.0.1`。

## 通过条件

- 身份属性与预期机构一致。
- 同机构资源所有者访问成功，跨机构访问返回 `401`、`403` 或 `404`。
- 动态脱敏响应不包含原始敏感值。
- 审计查询仅向审计身份开放，响应和日志不包含 Token 或敏感探针值。
- 导出、分享、历史和模型输入均执行服务端身份及机构边界校验。
- 8 个控制项、14 个场景全部通过；任一失败均返回非零退出码。

输出报告只包含控制项、场景状态、HTTP 状态、耗时和脱敏错误摘要，不包含服务地址、请求路径、Token、请求体、日志内容或敏感探针值。
