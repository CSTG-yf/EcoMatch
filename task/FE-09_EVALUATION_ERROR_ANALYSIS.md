# FE-09 评测与错误分析页面

## 完成状态

- 状态：已完成
- 完成日期：2026-07-30
- 页面路由：`/evaluation`

## 后端结果接口

1. 新增管理员只读接口 `GET /api/semantic/evaluation/dashboard`。
2. 固定加载 QA-01A、QA-01B、QA-02A、QA-02B 四份报告，不接受客户端文件名或任意路径。
3. 报告目录通过 `s2.evaluation.report-dir` 配置，默认使用仓库 `task` 目录。
4. 文件读取限制在规范化报告目录内，拒绝符号链接、空文件、超过 4 MiB 的文件以及任务标识不匹配的报告。
5. 接口不返回文件系统路径；四份报告齐全时状态为 `READY`，缺失时为 `PARTIAL`。
6. 接口访问先经过超级管理员守卫，并纳入 QA-02A 身份与垂直权限门禁。

## 前端实现

1. 新增管理员路由“评测分析”，展示发布决策、业务套件、安全用例和退化指标摘要。
2. 评测总览覆盖意图识别、SQL 执行、结果一致性、多轮上下文、图表与解释、权限与脱敏、审计与告警。
3. 版本对比展示基线值、当前值、变化量和门禁状态，并展示评测来源一致性和可用的阶段耗时对比。
4. 错误案例支持按套件、错误类型、业务场景和难度筛选；场景和难度字段缺失时不推断或伪造。
5. 错误案例支持待复核、已确认、修复中和已关闭状态，当前浏览器会话间通过本地存储保留。
6. 页面提供桌面与窄屏响应式布局，普通用户无法通过菜单或后端接口访问。

## 验收结果

```text
mvn -pl headless/server -am \
  -Dtest=EvaluationReportServiceTest,EvaluationReportControllerTest test
Tests:    4/4
Failures: 0

python evaluation/run_qa02a.py --output task/QA-02A_ACCEPTANCE_REPORT.json
Controls:     7/7
Test classes: 29/29
Test cases:   148/148

pnpm --filter supersonic-fe run build:os
Webpack: Compiled successfully
Route:   evaluation/index.html
```

## 剩余边界

人工复核状态当前保存在浏览器本地，不作为发布门禁输入；多人共享复核工作流需要后续接入持久化任务系统。真实版本结论仍必须由 `predictions` 或 `supersonic` 模式生成，不得将 `gold` 模式结果等同于线上模型效果。
