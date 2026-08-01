# FE-06B 受控分享前端

## 1. 实施状态

- 状态：已完成模块实现、路由注册、`DashboardEditor` 接入和后端联调收口。
- 前端目录：`webapp/packages/supersonic-fe/src/pages/ControlledShare/`
- 数据来源：仅调用正式后端接口，不包含模拟数据和前端权限兜底。
- 后端依赖：创建、管理和撤销依赖 BE-13；分享内容访问依赖
  `POST /api/chat/query/sharedDashboardData`。

## 2. 正式接口契约

### 2.1 创建分享

`POST /api/semantic/share`

请求体与 BE-13 `ShareCreateReq` 一致：

```json
{
  "dashboardId": 10,
  "identityPolicy": "AUTHENTICATED | ORGANIZATION | USERS",
  "allowedUsers": ["user1"],
  "expiresAt": "2026-08-08T00:00:00.000Z",
  "maxAccessCount": 100,
  "watermarkEnabled": true
}
```

约束：有效期必须在未来 30 天内；访问上限为 1 至 100000；`USERS` 模式必须提供
1 至 100 个用户；原始 Token 仅在创建响应中返回一次。

### 2.2 管理分享

- `GET /api/semantic/share?pageNum={n}&pageSize={n}`：分页读取当前用户可管理的分享。
- `GET /api/semantic/share/{shareId}`：读取单条可管理分享。
- `DELETE /api/semantic/share/{shareId}`：撤销分享，立即生效。

管理状态严格来自 `ShareResp.status`、`expiresAt`、`accessCount` 和
`maxAccessCount`。前端展示 `ACTIVE`、`REVOKED`、`EXPIRED` 及派生的
`EXHAUSTED`，不修改服务端状态。

### 2.3 访问分享内容

`POST /api/chat/query/sharedDashboardData`

```json
{
  "token": "一次性创建响应返回的原始 Token"
}
```

响应契约：

```json
{
  "shareId": "share-id",
  "dashboard": {},
  "watermarkUser": "当前访问者",
  "watermarkOrganization": "当前机构",
  "accessedAt": "2026-08-01T00:00:00.000Z",
  "componentData": {
    "component-id": {
      "queryColumns": [],
      "queryResults": []
    }
  },
  "componentErrors": {
    "component-id": "错误信息"
  }
}
```

该接口在一次分享访问计数内，以当前查看者身份重放全部组件。前端不调用旧的
`GET /api/semantic/share/access/{token}`，也不调用普通
`dashboardQueryData`。当前仓库 BE-13 没有 `ShareAccessReq` 类，旧访问接口的 Token
是路径参数；本模块已按补充后的正式 POST 契约实现。

## 3. 前端实现

对外导出：

- `ShareCreateDialog`：可复用创建对话框，支持身份模式、用户白名单、有效期、访问上限和水印。
- `ShareManagementDialog`：可复用管理对话框，支持分页、状态、访问余量和撤销。
- `ControlledShareAccessPage`：Token 分享访问页，展示服务端水印并按组件隔离结果或错误。
- `SharedDashboardComponent`：表格、指标卡、柱状图、折线图和饼图渲染器。
- `model.ts`：请求构造、边界校验、状态推导、Token 路径和组件响应适配纯函数。
- `service.ts`：正式接口服务层。

可复用组件从 `pages/ControlledShare` 统一导出；路由、`DashboardEditor` 和服务端受控访问已完成接入。

## 4. 安全边界

- 原始分享 Token 仅保存在创建对话框的 React 内存状态中，关闭时立即清除。
- 不把分享 Token 写入 `localStorage`、日志、错误信息或管理列表。
- 分享数据请求使用 POST body、`cache: no-store` 和 `referrerPolicy: no-referrer`，Token
  不进入数据接口 URL。
- 页面只展示服务端返回的 `watermarkUser`、`watermarkOrganization` 和脱敏标记。
- 组件数据只来自 `componentData`；单组件失败按 `componentErrors` 隔离展示。
- 服务端对过期、撤销、次数耗尽和身份拒绝可统一返回非泄露型 403。此时访问页展示
  通用“分享不可用”，仅在服务端明确返回原因码时细分状态。
- 前端不重放语义查询、不提交原始 SQL，也不绕过服务端对象权限、数据权限或脱敏策略。

## 5. 移动端

- 创建表单在窄屏切换为单列。
- 管理记录的状态、元数据和撤销操作自动换行。
- 分享页面在移动端取消外围边框和留白，组件改为单列并保留横向表格滚动。
- 链接输入、长标题、用户清单和水印均设置溢出约束。

## 6. 测试与验证

执行命令：

```bash
cd webapp/packages/supersonic-fe
node ../chat-sdk/node_modules/jest/bin/jest.js \
  --config src/pages/ControlledShare/jest.config.js --runInBand --coverage=false

node ../../node_modules/.pnpm/typescript@5.9.3/node_modules/typescript/bin/tsc \
  --project src/pages/ControlledShare/tsconfig.check.json --pretty false

pnpm exec prettier --check "src/pages/ControlledShare/**/*.{ts,tsx,less,js}"
```

结果：

- Jest：3 个测试套件、13 个用例全部通过。
- 隔离严格类型检查：通过。
- Prettier：通过。
- 仓库全量 `tsc`：在业务源码检查前被既有 TypeScript 4.9 与
  `@ant-design/pro-form` 声明语法不兼容阻断；输出中无 `ControlledShare` 错误。
- 定向 ESLint：仓库当前缺少 `eslint-plugin-react`，无法启动规则检查。

覆盖内容包括正式接口 URL/方法/请求体、Token 不持久化、有效期、三种身份模式、访问上限、
过期/撤销/403/次数耗尽状态、分页响应、组件数据与错误映射、一次性链接构建，以及全部公共导出。

## 7. 后续接入

1. 在 `DashboardEditor` 的已发布看板操作区接入 `ShareCreateDialog` 和
   `ShareManagementDialog`。
2. 注册 Token 分享路由并渲染 `ControlledShareAccessPage`。
3. 与新增的 `sharedDashboardData` 后端实现完成联调，重点验证一次访问计数、并发耗尽、
   跨机构拒绝、白名单拒绝、水印和脱敏数据。

## 8. 集成收口（2026-08-01）

- 上述路由、发布看板创建/管理入口和 `sharedDashboardData` 后端已全部接入。
- 后端在一次受控访问内按查看者身份批量重查组件，单组件失败隔离；页面兼容 `dataMasked`、`masked` 和 `maskingApplied` 脱敏标记。
- 已完成发布看板深链接入，`/dashboard/:id/edit` 可正确加载持久化看板并显示导出、分享和分享管理操作。
- 受控分享页在 1440x900 和 390x844 视口完成浏览器检查，水印、脱敏标记和组件数据可见，无横向溢出。
