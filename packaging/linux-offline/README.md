# Linux 离线一键包元数据

此目录保存 Linux 离线一键包的可复现元数据种子和启动验收脚本。

- `assets/semantic-seed.mv.db` 是服务器一键包使用的完整 H2 元数据库，包含银行问数平台元数据、Agent 33 和本地模型绑定。
- `assets/bank_benchmark.mv.db` 是服务器一键包使用的完整银行数据 H2 数据库。
- 组包时将种子复制为包内 `data/semantic.mv.db`。
- `verify-agent.sh` 必须在启动完成后执行。只有 Agent 33 以在线状态从真实 HTTP 接口返回，部署才算成功。
- `generate-sha256sums.sh` 生成离线包清单时排除日志、PID 文件和 HanLP 运行时生成的自定义词典缓存；这些文件会在首次运行后变化，不能作为不可变发行文件校验。

模型权重和运行时二进制不提交到 Git 仓库，由发行包组装阶段注入。
