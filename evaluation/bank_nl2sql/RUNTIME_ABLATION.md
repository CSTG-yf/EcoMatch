# 银行受约束计划运行时对照实验（不删代码）

目标：在**不删除** `BankQueryPlan` / 编译器 / AST 字段映射 / `BankResultProjector` 的前提下，用同一套题目对比：

| 模式 | 系统参数 | 期望路由 |
| --- | --- | --- |
| `bank-off` | `s2.parser.bank.constrained-plan.enable=false` | `ONE_PASS_SELF_CONSISTENCY` |
| `bank-on` | `s2.parser.bank.constrained-plan.enable=true` | `BANK_CONSTRAINED_PLAN`（银行数据集） |

基线策略保持 `s2.parser.s2sql.strategy=ONE_PASS_SELF_CONSISTENCY`。银行路径只由上述 bool 开关切入。

## 硬边界

- 不物理删除银行运行时类。
- 不运行 `build_gold.py` 覆盖官方 `train/dev/test.jsonl`。
- 不比较 SQL 字符串结构；主指标是 `resultAccuracy`。
- 解析/执行失败只作诊断。
- `--runtime-mode` 只是报告标签，**不会**改服务端开关；先改配置再跑评测。

固定 smoke 题见 `runtime_ablation_manifest.json`。

## 操作步骤

### 1. 启动服务并确认 Agent

本地 SuperSonic 已启动，且 Agent 已绑定银行语义数据集（例如历史对话中的「银行问数」）。

### 2. bank-off smoke

1. 系统配置中设置 `s2.parser.bank.constrained-plan.enable=false`（默认即为 false）。
2. 如配置走持久化参数表，保存后确认生效（必要时重启）。
3. 运行：

```powershell
evaluation\.venv\Scripts\python.exe evaluation/bank_nl2sql/run_supersonic_eval.py `
  evaluation/bank_nl2sql `
  --split train `
  --base-url http://127.0.0.1:9080 `
  --agent-id <AGENT_ID> `
  --runtime-mode bank-off `
  --ids-file evaluation/bank_nl2sql/runtime_ablation_manifest.json `
  --concurrency 2 `
  --no-resume `
  --output .local-dev/bank-nl2sql/ablation/smoke-bank-off.json
```

`--ids-file` 支持：每行一个 id 的文本文件、JSON 数组、`{recordIds:[...]}`，或本实验的 `runtime_ablation_manifest.json`（读取 `smoke.recordIds`）。

### 3. bank-on smoke

1. 将 `s2.parser.bank.constrained-plan.enable` 改为 `true` 并确认生效。
2. 同一 5 题：

```powershell
evaluation\.venv\Scripts\python.exe evaluation/bank_nl2sql/run_supersonic_eval.py `
  evaluation/bank_nl2sql `
  --split train `
  --base-url http://127.0.0.1:9080 `
  --agent-id <AGENT_ID> `
  --runtime-mode bank-on `
  --ids-file evaluation/bank_nl2sql/runtime_ablation_manifest.json `
  --concurrency 2 `
  --no-resume `
  --output .local-dev/bank-nl2sql/ablation/smoke-bank-on.json
```

### 4. 对比

```powershell
evaluation\.venv\Scripts\python.exe evaluation/bank_nl2sql/compare_runtime_ablation.py `
  .local-dev/bank-nl2sql/ablation/smoke-bank-off.json `
  .local-dev/bank-nl2sql/ablation/smoke-bank-on.json `
  --left-label bank-off `
  --right-label bank-on `
  --output .local-dev/bank-nl2sql/ablation/smoke-comparison.json
```

检查项：

1. `routingLooksConsistent`：bank-off 应为 `ONE_PASS_SELF_CONSISTENCY`，bank-on 应为 `BANK_CONSTRAINED_PLAN`。
2. `deltas.resultAccuracy` 与 `onlyLeftMatch` / `onlyRightMatch`。
3. `recommendation.doNotDeleteCode` 必须保持 true。

### 5. 小样本 train（可选）

仅当两边 smoke 的解析/执行链路可跑通后：

```powershell
# 两边各自改开关后，使用相同 --max-records 20，勿混用 checkpoint
evaluation\.venv\Scripts\python.exe evaluation/bank_nl2sql/run_supersonic_eval.py `
  evaluation/bank_nl2sql `
  --split train `
  --base-url http://127.0.0.1:9080 `
  --agent-id <AGENT_ID> `
  --runtime-mode bank-off `
  --max-records 20 `
  --concurrency 2 `
  --no-resume `
  --output .local-dev/bank-nl2sql/ablation/train20-bank-off.json
```

再对 bank-on 重复，并用 `compare_runtime_ablation.py` 对比。

## 如何解读结果

| 结果 | 动作 |
| --- | --- |
| bank-on 更高 | 主路径继续演进银行链；通用链仅作回归基线 |
| bank-off 更高 | **不删代码**；逐题看 bank-on 独有失败，修计划/编译/投影 |
| 持平 | 扩样本或看延迟/错误类别，再决策 |
| 路由 telemetry 与模式不符 | 开关未生效，本次分数无效，先修配置再重跑 |

## 本实验不做什么

- 不回滚 PR #1 整包。
- 不删除自定义语义字段 AST 映射。
- 不把 gold SQL 生成器重新接入正式评分。
