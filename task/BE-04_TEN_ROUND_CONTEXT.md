# BE-04 十轮上下文引擎实现说明

## 实现范围

- 将 NL2SQL 改写历史窗口由上一轮扩展为当前会话最近 10 个成功轮次，并按时间正序输入模型。
- 保存每轮问题、S2SQL、指标、维度、机构等筛选条件、时间条件、排序和查询粒度。
- 识别追加、替换、撤销、下钻和清空操作，并通过约束提示词控制条件继承与修改。
- 增加 30 分钟上下文失效、8000 字符摘要上限和超限时从最旧轮次开始淘汰的策略。
- 在引擎层按 `chatId` 二次隔离历史记录，避免会话串扰。
- 在解析响应中返回 `multiTurnContext`，供前端展示来源轮次、操作类型、过期状态和改写结果。

## 上下文协议

`ChatParseResp.multiTurnContext` 包含：

- `maxRounds`、`usedRounds`：窗口上限和实际使用轮数。
- `operation`：`APPEND`、`REPLACE`、`REMOVE`、`DRILL_DOWN` 或 `RESET`。
- `expired`、`truncated`：上下文是否过期或因长度被截断。
- `sourceQueryIds`、`turns`：来源问题 ID 与结构化轮次。
- `rewrittenQuery`：送入 NL2SQL 的完整改写问题。

## 配置与行为

多轮改写沿用 `REWRITE_MULTI_TURN` Chat App 配置。启用后，系统仅使用当前会话 30 分钟内最近 10 个成功查询；清空操作、无有效历史或历史过期时不调用改写模型。

## 验收

测试覆盖最近 10 轮窗口、时间顺序、完整结构化字段、追加/替换/撤销/下钻/清空操作、过期、摘要截断和跨会话隔离。

```bash
mvn -pl chat/server -am test \
  "-Dtest=MultiTurnContextEngineTest" \
  "-Dsurefire.failIfNoSpecifiedTests=false"
```

当前结果：5 个用例全部通过，失败 0，错误 0。
