# 银行业统一评测

QA-01A 使用一个命令统一执行意图、SQL、结果一致性、十轮上下文、图表推荐和业务解释评测：

```powershell
evaluation/.venv/Scripts/python.exe evaluation/run_qa01a.py `
  --workbook task/基于大模型与NL2SQL的银行业智能问数系统构建与应用_数据集.xlsx
```

执行器生成 CI 可读 JSON 报告，并在命令失败、套件缺失或阈值不达标时返回退出码 `1`。完整参数、预测文件模式和真实 SuperSonic 模式见 `evaluation/QA-01A_README.md`。

QA-01B 将通过的 QA-01A 报告固化为版本基线，并比较当前版本的核心指标、阶段耗时和失败案例：

```powershell
python evaluation/run_qa01b.py compare `
  --baseline .local-dev/bank-evaluation/qa01b-baseline.json `
  --current .local-dev/bank-evaluation/qa01a-report.json `
  --current-version v1.1.0
```

准确率或通过率下降、响应时间超出容差、评测源不一致以及当前评测失败都会阻断发布。基线命令、策略和退出码说明见 `evaluation/QA-01B_README.md`。

QA-02A 聚合后端权限、越权、脱敏、缓存、历史、模型解释和敏感日志测试：

```powershell
python evaluation/run_qa02a.py
```

控制项和报告说明见 `evaluation/QA-02A_README.md`。

QA-02B 聚合审计完整性、哈希链、异常规则、告警去重、机构隔离和告警处置测试：

```powershell
python evaluation/run_qa02b.py
```

控制项和报告说明见 `evaluation/QA-02B_README.md`。

# 原有 DuSQL 评测流程

1. 正常启动项目(必须包括LLM服务)
2. 执行evalution.sh脚本，主要包括构建表数据、数据建模、获取模型预测结果，执行对比逻辑。可以在命令行看到执行准确率，错误case会写到同目录的error_case.json文件中。

# 评测意义

制定评测工具方便supersonic快速对接其他大模型、更改参数配置，对于评估提示词、代码更改所带来的影响至关重要，可以帮助我们了解这些变化是否会提高或降低准确率、响应速度。
