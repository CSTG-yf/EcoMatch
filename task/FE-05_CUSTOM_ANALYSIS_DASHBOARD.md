# FE-05 自定义分析看板实现说明

## 1. 完成范围

- 从一次正式问数结果创建看板，自动带入主题域、查询口径、图表类型和结构化语义查询。
- 提供看板列表、编辑器和展示页。
- 支持组件拖动排序、半宽/通栏布局、名称和图表类型调整。
- 支持指标卡、表格、折线图、柱状图、饼图和组合图。
- 支持全局筛选、手动刷新以及 30 秒、1 分钟、5 分钟周期刷新。
- 支持看板复制、发布、停用、删除和访问范围配置。
- 接入 BE-12 正式持久化接口和语义查询接口，不使用本地模拟数据作为运行链路。

## 2. 配置协议

看板配置使用版本化 JSON，包含：

- `schemaVersion`：当前为 1。
- `refreshInterval`：看板刷新周期。
- `globalFilters`：全局语义维度筛选。
- `components`：组件标题、图表类型、布局和结构化语义查询。

问数转换只保留数据集、维度、聚合器、过滤条件、日期条件、查询类型和来源查询标识。配置不保存 SQL、Token、凭据或查询结果快照。

## 3. 正式接口

- `GET /api/semantic/dashboard`：按主题域、状态分页查询。
- `GET /api/semantic/dashboard/{id}`：查询详情。
- `POST /api/semantic/dashboard`：创建看板。
- `PUT /api/semantic/dashboard/{id}`：按版本更新。
- `POST /api/semantic/dashboard/{id}/copy`：复制。
- `POST /api/semantic/dashboard/{id}/publish`：发布。
- `POST /api/semantic/dashboard/{id}/disable`：停用。
- `DELETE /api/semantic/dashboard/{id}`：删除。
- `POST /api/semantic/query/dataSet`：按当前查看者权限重新执行结构化语义查询。

身份、主题域、对象、机构边界、乐观锁和生命周期审计由服务端执行。查询刷新使用当前查看者身份重新应用行列权限和脱敏规则。

## 4. 验证结果

- Chat SDK：10 个测试套件、39 个用例通过，覆盖问数草稿转换、敏感字段排除、配置降级、布局排序和全局筛选覆盖。
- BE-12：`DashboardControllerTest` 1 个、`DashboardServiceTest` 12 个用例通过。
- 主应用 `supersonic-fe build:os` 生产构建通过。
- 1440px 桌面检查：双列组件各 690px，页面无横向溢出，ECharts 画布正常生成。
- 390px 移动端检查：组件切换为 366px 单列，工具栏换行，组件和文字无重叠。

主应用独立 `tsc` 仍受仓库现有 TypeScript 4 与 ProForm 第三方声明语法不兼容影响；错误位于 `node_modules`，生产构建未受影响。

## 5. 后续依赖

- FE-05 已满足 FE-06A 导出中心和 FE-06B 受控分享的前置条件。
- FE-06A、FE-06B 可并行实施。
- 两项完成后进入 QA-02C 全链路安全门禁。
