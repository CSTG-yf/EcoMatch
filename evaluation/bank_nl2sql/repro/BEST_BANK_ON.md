# 最佳 bank-on 问数参数（复现）

机器可读清单：[`best_bank_on.json`](best_bank_on.json)。  
准则与反作弊：仓库根目录 [`AGENTS.md`](../../../AGENTS.md)。

**不要**用 train/gold few-shot 或题号硬编码刷分；本配置对应的是**语义编译 + 查询族模板**路径上的最优运行时开关。

## 参数一览（最佳效果）

| 参数 | 值 | 作用 |
|------|-----|------|
| `s2.parser.bank.constrained-plan.enable` | **true** | 银行数据集走 `BANK_CONSTRAINED_PLAN` |
| `s2.parser.bank.max-candidates` | **1** | 单候选；2 无 tableEX 增益、更慢 |
| `s2.parser.bank.plan.deterministic-short-circuit.enable` | **false** | 关闭预模型规则短路 |
| `s2.parser.bank.plan.soft-fallback.enable` | **true** | 模型全拒后白名单规则兜底 |
| `s2.parser.bank.plan.thinking.enable` | **false** | plan 路径默认不思考 |
| Agent `BANK_CONSTRAINED_PLAN` | **enable** | agent 33 上打开 |
| Agent `EXECUTION_SQL_CORRECTOR` | **enable**（建议） | 执行失败时一次修复 |
| `--runtime-mode` | `bank-on` | 仅报告标签，不改服务端 |

JVM 覆盖（与 H2 系统参数一致，优先写进启动参数便于对齐代码默认）：

```text
-Ds2.parser.bank.plan.deterministic-short-circuit.enable=false
-Ds2.parser.bank.plan.soft-fallback.enable=true
-Ds2.parser.bank.plan.thinking.enable=false
```

## 证据水位（本地 ablation）

| 集合 | tableEX | AE | exec | 报告（本地，可不入库） |
|------|---------|-----|------|------------------------|
| hard20 | 1.0 | 14/14 | 1.0 | `.local-dev/bank-nl2sql/ablation/train-hard20-ablation-sc0-fb1-c1-post-h04.json` |
| reg21（v48 弱项+H04） | 1.0 | 15/15 | 1.0 | `.local-dev/bank-nl2sql/ablation/train-reg-v48weak-post-fix2.json` |

消融对照：cand=2 无增益；关 soft-fallback 会腰斩；开短路只省时延不抬 tableEX。

## 一键对齐 H2 参数

服务**停机**后执行（写本地 metadata H2，不改 gold）：

```powershell
# 仓库根目录
.local-dev\eval-venv\Scripts\python.exe evaluation/bank_nl2sql/repro/apply_best_bank_on.py
```

脚本会：

1. 将 `s2_system_config` 中 bank 相关参数写成上表最佳值  
2. 尽量开启 agent 33 的 `BANK_CONSTRAINED_PLAN` 与 `EXECUTION_SQL_CORRECTOR`（若 chat_model_config 可解析）  
3. 打印推荐 JVM 参数与 hard20 复现命令  

## 复现 hard20

1. 使用含本分支 bank 编译/路由修复的 jar 启动 standalone（端口 9080）。  
2. 应用本清单参数并重启。  
3. 评测：

```powershell
.local-dev\eval-venv\Scripts\python.exe evaluation/bank_nl2sql/run_supersonic_eval.py `
  evaluation/bank_nl2sql `
  --split train `
  --base-url http://127.0.0.1:9080 `
  --agent-id 33 `
  --ids-file evaluation/bank_nl2sql/repro/ids-train-hard20.txt `
  --runtime-mode bank-on `
  --concurrency 1 `
  --timeout-seconds 300 `
  --no-resume `
  --output .local-dev/bank-nl2sql/ablation/repro-hard20.json
```

快速 smoke：

- H-04 族：`ids-train-h04-family.txt`  
- reg21：`ids-train-reg21.txt`  

## 验收

- `parseSuccessRate` / `executionSuccessRate` / `tableEX` 接近 1.0（hard20）  
- 官方 `answerExact` 在 GOLD_OK 分母上保持满（hard20 为 14/14）  
- 报告 `bankRouting.planSource` 可观测（非全 DETERMINISTIC 时说明模型路径在跑）  

## 明确不推荐（对比实验用）

| 开关 | 错误值 | 现象 |
|------|--------|------|
| short-circuit | true | 难测模型能力；H-04 类易假成功 |
| max-candidates | 2 | 时延≈×2，tableEX 无增益 |
| soft-fallback | false | hard20 parse/tableEX 显著下降 |
