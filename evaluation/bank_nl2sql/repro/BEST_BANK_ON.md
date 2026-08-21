# 建议 bank-on 问数参数（工程配置）

机器可读清单：[`best_bank_on.json`](best_bank_on.json)。准则与反作弊边界见仓库根目录
[`AGENTS.md`](../../../AGENTS.md)。本文件只说明建议运行参数；任何模型效果都必须重新经过
Fact v3 官方运行时评测，历史局部实验不构成当前成绩。

## 参数一览

| 参数 | 值 | 作用 |
|------|-----|------|
| `s2.parser.bank.constrained-plan.enable` | **true** | 银行数据集走 `BANK_CONSTRAINED_PLAN` |
| `s2.parser.bank.max-candidates` | **1** | 默认单候选，便于稳定复现 |
| `s2.parser.bank.plan.deterministic-short-circuit.enable` | **false** | 已停用：model-led 重构后无代码消费者，仅为兼容保留声明 |
| `s2.parser.bank.plan.soft-fallback.enable` | **true** | 已停用：同上，规则软回退路径已随 model-led 重构移除 |
| `s2.parser.bank.plan.thinking.enable` | **false** | plan 路径默认不思考 |
| Agent `BANK_CONSTRAINED_PLAN` | **enable** | 对目标 Agent 开启 |
| Agent `EXECUTION_SQL_CORRECTOR` | **enable**（建议） | 执行失败时一次受控修复 |

JVM 覆盖应与 H2 系统参数保持一致：

```text
-Ds2.parser.bank.plan.deterministic-short-circuit.enable=false
-Ds2.parser.bank.plan.soft-fallback.enable=true
-Ds2.parser.bank.plan.thinking.enable=false
```

## 停用说明（2026-08-21 核对）

`deterministic-short-circuit` 与 `soft-fallback` 两个开关自 model-led 重构（464bcd7 起）后
在代码中不再有任何消费者：`ParserConfig` 仅保留参数声明，写入 H2 不会改变行为。历史上
hard20 消融中「关闭 soft-fallback 掉 0.5」的证据对应已删除的旧路径，不能再用于解释当前
系统。保留声明的目的是不动启动回执中的 systemParameter 清单与哈希。

## 一键对齐 H2 参数

服务**停机**后执行（只写本地 metadata H2，不改正式 v2.0.6 数据包）：

```powershell
# 仓库根目录
.local-dev\eval-venv\Scripts\python.exe evaluation/bank_nl2sql/repro/apply_best_bank_on.py
```

脚本会更新 bank 相关系统参数，并尽量开启目标 Agent 的受约束计划和执行修复能力。

## 验收

1. 用 v2.0.6 数据导入包和 `bootstrap_bank_agent.py` 重新生成当前 Agent 的启动回执。
2. 在干净工作树中只运行固定 Fact v3 smoke；未全绿不得进行完整集合评测。
3. smoke 全绿后，使用同一 `RunId` 顺序运行 train、dev；最终 test 还必须显式确认并记录。

```powershell
powershell -ExecutionPolicy Bypass -File evaluation/bank_nl2sql/Run-OfficialBankEvaluation.ps1 `
  -Mode smoke -RunId <RUN_ID> -BaseUrl http://127.0.0.1:9080 -AgentId <AGENT_ID> `
  -ModelLabel '<MODEL_LABEL>' `
  -BootstrapReceipt .local-dev/bank-nl2sql/official-v3/bootstrap-receipt.json
```

唯一正式成绩是 Fact v3 `caseAccuracy`；`resultExact` 与运行时阶段状态只用于
解释失败。若做 bank-on/bank-off 对照，按 [`../RUNTIME_ABLATION.md`](../RUNTIME_ABLATION.md)
比较两个同协议报告。

## 不推荐配置（仅对照实验）

| 开关 | 不推荐值 | 原因 |
|------|----------|------|
| short-circuit | true | 会掩盖模型路径，且泛化不足 |
| max-candidates | 2 | 增加时延，应先用标准流程证明必要性 |
| soft-fallback | false | 容易扩大可恢复执行失败 |
