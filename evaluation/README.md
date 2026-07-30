# 银行业统一评测

QA-01A 使用一个命令统一执行意图、SQL、结果一致性、十轮上下文、图表推荐和业务解释评测：

```powershell
evaluation/.venv/Scripts/python.exe evaluation/run_qa01a.py `
  --workbook task/基于大模型与NL2SQL的银行业智能问数系统构建与应用_数据集.xlsx
```

执行器生成 CI 可读 JSON 报告，并在命令失败、套件缺失或阈值不达标时返回退出码 `1`。完整参数、预测文件模式和真实 SuperSonic 模式见 `evaluation/QA-01A_README.md`。

# 原有 DuSQL 评测流程

1. 正常启动项目(必须包括LLM服务)
2. 执行evalution.sh脚本，主要包括构建表数据、数据建模、获取模型预测结果，执行对比逻辑。可以在命令行看到执行准确率，错误case会写到同目录的error_case.json文件中。

# 评测意义

制定评测工具方便supersonic快速对接其他大模型、更改参数配置，对于评估提示词、代码更改所带来的影响至关重要，可以帮助我们了解这些变化是否会提高或降低准确率、响应速度。
