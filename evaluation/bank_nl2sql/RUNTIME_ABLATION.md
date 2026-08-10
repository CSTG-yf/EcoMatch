# 银行受约束计划运行时对照实验（Fact v3）

这是工程消融流程，不替代固定配置下的正式成绩。两侧都必须使用
[`Run-OfficialBankEvaluation.ps1`](Run-OfficialBankEvaluation.ps1) 产出 Fact v3 报告；
唯一比较分数是 `caseAccuracy`。

| 模式 | 系统参数 | 期望路由 |
| --- | --- | --- |
| `bank-off` | `s2.parser.bank.constrained-plan.enable=false` | `ONE_PASS_SELF_CONSISTENCY` |
| `bank-on` | `s2.parser.bank.constrained-plan.enable=true` | `BANK_CONSTRAINED_PLAN`（银行数据集） |

基线策略保持 `s2.parser.s2sql.strategy=ONE_PASS_SELF_CONSISTENCY`。运行前应记录实际
配置切换；`--left-label` 和 `--right-label` 只用于比较报告标注，不会改服务端参数。

## 硬边界

- 不删除 `BankQueryPlan`、编译器、字段映射或 `BankResultProjector`。
- 不改 v2.0.1 数据、任何 gold SQL、答案文本或冻结 test。
- 不比较 SQL 字符串、AST、列顺序或展示表形态。
- 两边必须使用同一 Agent、模型标签、数据版本、代码版本、启动回执和固定 smoke。
- 解析、执行、摘要和路由 telemetry 仅作诊断，不作为独立分数。

## 操作步骤

### 1. 共同前置条件

完成 README 中的数据库导入和 Agent bootstrap，得到同一份不含密钥的
`bootstrap-receipt.json`。在干净 Git 工作树中启动服务；每题都会创建独立会话。

### 2. bank-off 固定 smoke

1. 设置 `s2.parser.bank.constrained-plan.enable=false`，必要时重启或热加载并确认生效。
2. 运行固定五题 smoke：

```powershell
powershell -ExecutionPolicy Bypass -File evaluation/bank_nl2sql/Run-OfficialBankEvaluation.ps1 `
  -Mode smoke -RunId bank-off-20260810 `
  -BaseUrl http://127.0.0.1:9080 -AgentId <AGENT_ID> `
  -ModelLabel '<MODEL_LABEL>' `
  -BootstrapReceipt .local-dev/bank-nl2sql/official-v3/bootstrap-receipt.json `
  -EvidenceRoot .local-dev/bank-nl2sql/ablation
```

输出为 `.local-dev/bank-nl2sql/ablation/bank-off-20260810/smoke.json`。若 smoke 未全绿，
命令会非零退出；报告仍保留用于定位失败，不能进入全量对照。

### 3. bank-on 固定 smoke

将参数改为 `true` 并确认生效，使用新的运行 ID 重复同一命令：

```powershell
powershell -ExecutionPolicy Bypass -File evaluation/bank_nl2sql/Run-OfficialBankEvaluation.ps1 `
  -Mode smoke -RunId bank-on-20260810 `
  -BaseUrl http://127.0.0.1:9080 -AgentId <AGENT_ID> `
  -ModelLabel '<MODEL_LABEL>' `
  -BootstrapReceipt .local-dev/bank-nl2sql/official-v3/bootstrap-receipt.json `
  -EvidenceRoot .local-dev/bank-nl2sql/ablation
```

### 4. 生成唯一对照报告

```powershell
evaluation\.venv\Scripts\python.exe evaluation/bank_nl2sql/compare_runtime_ablation.py `
  .local-dev/bank-nl2sql/ablation/bank-off-20260810/smoke.json `
  .local-dev/bank-nl2sql/ablation/bank-on-20260810/smoke.json `
  --left-label bank-off --right-label bank-on `
  --output .local-dev/bank-nl2sql/ablation/smoke-comparison.json
```

检查：

1. `routingLooksConsistent`：路由与配置相符；否则本次对照无效。
2. `deltas.caseAccuracy`：唯一比较分数差异。
3. `onlyLeftCasePass` / `onlyRightCasePass`：逐题变化，配合失败类别和路由诊断定位。
4. `recommendation.doNotDeleteCode`：必须保持 `true`。

### 5. 全量训练集（可选）

只有两侧 smoke 都全绿时，才分别在各自已有 smoke 的同一 `RunId` 下运行 `-Mode train`。
不得使用任意题号过滤、局部样本数或并发参数代替完整 train。对比两个 `train.json` 的方法与
上一步相同；其结论仍是工程配置选择，不替代固定配置的正式评测记录。

## 如何解读结果

| 结果 | 动作 |
| --- | --- |
| bank-on 更高 | 主路径继续演进银行链；通用链保留为回归基线 |
| bank-off 更高 | 不删代码；逐题定位 bank-on 失败的 plan、编译或投影问题 |
| 持平 | 扩展到完整 train 或查看运行时诊断，再决策 |
| 路由 telemetry 与模式不符 | 配置未生效，本次对照无效，先修配置再重跑 |
