# Agent 33 发布包

`agent-33.json` 是脱敏后的“银行问数” Agent 配置，包含数据集绑定、受约束计划开关和模型 ID；API key、模型 endpoint 等连接信息不进入仓库。

在目标 SuperSonic 实例关闭后执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\evaluation\bank_nl2sql\agent\Import-Agent33.ps1
```

脚本只对 H2 的 `s2_agent` 做幂等 `MERGE`，不会导入或删除评测数据库，也不会覆盖 `semantic.mv.db`。默认使用模型 ID `1`；如果目标实例的 LAN 模型对应其他 ID，可传 `-ChatModelId` 覆盖。

导入完成后，在 Agent 管理页面确认：Agent ID 为 `33`、名称为“银行问数”、状态启用、公开，并绑定数据集 `33、65`。模型连接需要在目标实例单独配置。
