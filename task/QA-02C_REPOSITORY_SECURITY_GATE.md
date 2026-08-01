# QA-02C 仓库级全链路安全门禁

## 1. 状态

- 仓库级门禁：已完成（2026-08-01）。
- 最终环境门禁：待正式身份属性、生产权限/脱敏/审计规则和目标部署环境。
- 机器可读报告：`task/QA-02C_REPOSITORY_ACCEPTANCE_REPORT.json`。

## 2. 交付

| 交付物 | 路径 |
| --- | --- |
| 全链路执行器 | `evaluation/run_qa02c.py` |
| 证据与控制项清单 | `evaluation/qa02c_manifest.json` |
| 执行说明 | `evaluation/QA-02C_README.md` |
| 执行器单元测试 | `evaluation/tests/test_run_qa02c.py` |
| 仓库验收报告 | `task/QA-02C_REPOSITORY_ACCEPTANCE_REPORT.json` |

## 3. 执行链

```text
QA-02A 当前权限/脱敏门禁
  -> QA-02B 当前审计/告警门禁
  -> 导出/分享/历史/模型输入/日志 Java 跨链路回归
  -> FE-06/FE-08 前端安全回归
  -> Umi 生产构建
  -> QA-02C 仓库报告
```

每次 QA-02C 都重新执行 QA-02A/B，不信任仓库中历史绿灯报告。前置子报告写入 `.local-dev/bank-evaluation/qa02c-evidence/`，只归档脱敏后的 QA-02C 总报告。

## 4. 验收结果

2026-08-01 在 Java 21、Maven 3.9.11、Node.js 和 pnpm 9.12.3 环境执行：

```powershell
python evaluation/run_qa02c.py `
  --output task/QA-02C_REPOSITORY_ACCEPTANCE_REPORT.json
```

结果：

- QA-02A：`166` 个用例通过。
- QA-02B：`50` 个用例通过。
- QA-02C 追加 Java 跨链路：`10` 个测试类、`54` 个用例通过。
- FE-06/FE-08 前端：`10` 个套件、`54` 个用例通过。
- 前端开源版生产构建：通过。
- QA-02C 控制项：`7/7` 通过。
- QA-02C 证据：`14/14` 通过，零失败。

## 5. 失败封闭

- 清单任务、类型、Java 类或控制项引用不合法时拒绝执行。
- QA-02A/B 子报告缺失、任务标识不符或状态非 `PASS` 时失败。
- Maven 全局失败会覆盖已产生的单类绿灯，防止用部分 Surefire 报告误判通过。
- 任一用例失败、跳过或类未执行时失败。
- 前端回归或生产构建失败时失败。
- 任一声明证据未绑定到控制项时拒绝清单。

## 6. 剩余边界

当前报告显式标记 `scope=REPOSITORY` 和 `environmentGateRequired=true`。因此：

- 可以证明当前仓库安全回归和发布产物已通过。
- 不能提前声明比赛部署环境的正式身份、机构、岗位、脱敏规则和审计告警规则已验收。
- 上述环境配置到位后，执行 QA-02C 环境门禁才能关闭 BE-09 最终验收。
