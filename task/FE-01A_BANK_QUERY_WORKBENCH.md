# FE-01A 银行问数工作台主流程

## 完成状态

- 状态：已完成
- 完成日期：2026-07-30
- 下游任务：FE-01B、FE-02、FE-03、FE-04

## 实现内容

1. 接入 `POST /api/semantic/bank/intent/recognize`，在问数消息中展示标准化意图、业务场景、置信度、指标、维度、机构、时间范围和来源模型。
2. 新增统一工作流状态机，覆盖理解问题、查询数据、生成解释、完成、失败、超时和无权限状态。
3. 将原有无限解释轮询改为最多 60 次、每次间隔 500 ms 的可取消轮询；消息重置、组件卸载和新查询会终止旧轮询。
4. 接入查询响应中的 `recommendedChart`、`candidateCharts`、`businessExplanation`、`dataMasked` 和 `maskedColumns` 协议。
5. 展示图表类型、推荐理由、推荐置信度、业务摘要、证据、指标定义、时间范围和风险提示。
6. 保留现有查询结果、图表、SQL、相似问题、筛选和导出能力，历史消息也会恢复标准化意图与完成状态。

## 主要文件

- `webapp/packages/chat-sdk/src/components/ChatItem/index.tsx`
- `webapp/packages/chat-sdk/src/components/ChatItem/BankQueryOverview.tsx`
- `webapp/packages/chat-sdk/src/components/ChatItem/BusinessInsightPanel.tsx`
- `webapp/packages/chat-sdk/src/components/ChatItem/QueryStageStatus.tsx`
- `webapp/packages/chat-sdk/src/components/ChatItem/workflow.ts`
- `webapp/packages/chat-sdk/src/service/index.ts`
- `webapp/packages/chat-sdk/src/common/type.ts`

## 验收结果

```text
pnpm --filter supersonic-chat-sdk test -- --runInBand --watchAll=false
Test Suites: 3 passed, 3 total
Tests:       7 passed, 7 total

pnpm --filter supersonic-fe run build:os
Webpack: Compiled successfully
```

SDK 独立 `build-ts` 仍受仓库既有 TypeScript 4.9 与当前依赖声明兼容问题影响；正式主应用通过 Umi alias 直接编译 SDK 源码，生产构建已通过。该基线问题不影响 FE-01A 集成，但应在前端工程治理任务中单独升级 TypeScript 和依赖锁定策略。

## 剩余边界

FE-01A 只关闭 PC 主流程。移动端布局、会话切换、历史续问和异常恢复由 FE-01B 完成；上下文澄清、SQL 可信度解释、图表下钻分别由 FE-02、FE-03、FE-04 完成。
