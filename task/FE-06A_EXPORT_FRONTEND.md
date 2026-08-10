# FE-06A 导出前端实现说明

## 1. 范围

本模块提供导出中心页面、可复用创建组件、正式接口服务层、纯模型函数及 Jest 单元测试，并已接入路由、菜单、问数 SDK 和看板模块。实现不依赖模拟数据。

导出中心展示当前登录账号的历史任务，不依赖浏览器会话缓存；服务端只返回任务创建者本人的记录。

## 2. BE-13 正式契约

基础路径：`/api/semantic/export`

| 操作     | 方法与路径                                       | 请求/响应                                |
| -------- | ------------------------------------------------ | ---------------------------------------- |
| 历史列表 | `GET /api/semantic/export?pageNum=1&pageSize=20` | `PageInfo<ExportTaskResp>`，仅当前创建者 |
| 创建     | `POST /api/semantic/export`                      | `ExportCreateReq -> ExportTaskResp`      |
| 查询状态 | `GET /api/semantic/export/{taskId}`              | `ExportTaskResp`                         |
| 下载     | `GET /api/semantic/export/{taskId}/download`     | XLSX/PDF 二进制流                        |

`ExportCreateReq`：

- `resourceType`: `QUERY` 或 `DASHBOARD`。
- `format`: `XLSX` 或 `PDF`。
- QUERY 必须包含且仅包含一个 `QueryStructReq`。
- DASHBOARD 必须包含有效 `dashboardId`，`queries` 为要重新执行的结构化查询数组。
- `charts` 支持 `BAR`、`LINE`，必须引用有效查询序号和字段。
- 不允许使用原始 SQL 或前端结果快照代替结构化查询。

`ExportTaskResp.status`：`PENDING`、`RUNNING`、`SUCCEEDED`、`FAILED`、`EXPIRED`。仅当 `status=SUCCEEDED` 且 `downloadable=true` 时启用下载。

服务端限制：最多 20 个查询、总计 10,000 行、PDF 500 行、文件 25 MB；成功文件保留 24 小时。任务查询和下载仅允许创建者访问。服务端按当前用户重新执行查询并应用权限与脱敏策略。

## 3. 实现

### 服务与类型

- `types.ts` 对齐 `ExportCreateReq`、`ExportChartReq`、`ExportTaskResp`、分页列表和全部枚举。
- `service.ts` 封装历史列表、创建、状态查询和下载 API，并导出可复用 `exportApi`。
- 下载使用仓库共享 `request` 客户端的 `responseType: 'blob'` 和 `getResponse: true`，因此请求拦截器会携带当前 `Authorization`/`auth` Token。未使用裸 `<a href>` 下载接口。
- 文件名从服务端 `Content-Disposition` 解析，Blob 仅通过临时对象 URL 保存。

### 纯模型

`model.ts` 提供：

- 正式请求校验和 QUERY/DASHBOARD JSON 解析。
- 请求构造器 `buildExportRequest`，可供问数和看板后续复用。
- 轮询、下载、重试状态判断。
- 数据范围、脱敏摘要、文件大小和状态展示。
- API 包装响应归一化、任务合并及 401/403/过期/参数错误分类。
- `integration.ts` 将 `DashboardQuerySource` 或已持久化看板组件转换成正式 `QueryStructReq`。转换器只投影 `dataSetId`、`modelIds`、维度分组、指标聚合、维度过滤、日期范围和行数上限，拒绝缺少数据集、模型或指标/维度的来源。
- `buildQueryExportRequest(source, format)` 和 `buildDashboardExportRequest(dashboard, format)` 分别供 ChatPage 与 DashboardEditor 后续接入。转换过程不会透传 SQL、Token、凭据、查询结果或结果快照；看板全局过滤会合并进各组件查询。
- 看板转换器对受支持的柱状图/折线图始终生成 `BAR`/`LINE` 图表定义，不依赖初始文件格式。XLSX 由后端忽略图表，lockedSource 从 XLSX 切换为 PDF 时则可直接复用原图表定义。

### UI 与复用入口

- `ExportCenter`：初次加载当前账号最近 20 个任务，支持手动刷新；创建任务、按任务 ID 查询、每 2 秒轮询 PENDING/RUNNING、安全下载、失败/过期重试、明确错误和空状态。
- `CreateExport`：从 `ExportCenter/index.tsx` 具名导出。调用方可传入正式 `initialRequest` 并设置 `lockedSource`，用于问数结果或看板页发起导出。
- `lockedSource=true` 时不渲染底层结构化 JSON、看板 ID 或来源编辑控件，只展示来源名称、数据范围及脱敏说明。提交始终使用 `initialRequest` 的 `queries`、`dashboardId`、`charts`，仅允许选择输出格式，避免可信集成入口被表单值篡改。
- 页面在桌面和移动端使用稳定的单列/多列响应式布局；任务操作在窄屏下保持可点击且不溢出。

复用示例：

```tsx
<CreateExport
  lockedSource
  initialRequest={{
    resourceType: "QUERY",
    format: "XLSX",
    title: question,
    queries: [queryStructReq],
    charts: [],
  }}
  onCreate={createExportTask}
/>
```

## 4. 验证

- 定向 Jest：`4` 个测试套件、`22` 个用例通过，覆盖模型、正式接口服务、集成转换器、locked 请求不可篡改、XLSX/PDF 图表保留及公共模块加载。
- Prettier：ExportCenter 全目录与本文档检查通过。
- Umi 生产构建：`pnpm build:os` 编译成功。
- 仓库全量 `tsc` 未能执行到业务源码：项目固定 TypeScript 4.9.5 无法解析当前 `@ant-design/pro-form` 声明；使用已安装 TypeScript 5.9.3 时又被仓库 `tsconfig.json` 中已移除的 `suppressImplicitAnyIndexErrors` 选项阻断。两项均为既有工具链问题，本任务未按约束修改依赖或 tsconfig。
- 定向 ESLint 被仓库混用 ESLint 7.32.0/8.57.1 导致的 `getScope` 异常阻断；非本模块规则错误。

## 5. 集成收口与任务历史（2026-08-01）

- 已注册 `/exports` 路由和导航菜单，Chat 查询结果与发布看板均可以锁定的结构化语义查询发起导出。
- 看板导出由服务端对照持久化组件校验数据集、模型、分组、聚合、筛选和日期配置，防止前端替换查询。
- 历史任务接口统一校验登录态和分页范围，查询条件固定为 `owner = currentUser.name`，按创建时间和任务 ID 倒序返回；过期成功任务在列表中同步转为不可下载的 `EXPIRED` 状态。
- 当前增量的后端服务测试 `9/9`、前端联合竞赛套件 `10/10`（`55/55`）以及 ExportCenter 格式检查通过。
- 联合竞赛测试与 FE-06B 共 9 个套件、48 个用例通过；Chat SDK 9 个套件、36 个用例通过；主应用生产构建通过。
- `/exports` 在 1440x900 和 390x844 视口完成浏览器检查，无横向溢出。
