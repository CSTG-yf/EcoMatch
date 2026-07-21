# BE-02 银行指标治理增强实现说明

## 实现范围

- 指标治理档案：版本号、治理状态、责任部门、来源系统、业务口径和生效区间。
- 版本流程：创建版本、提交审批、批准/驳回、发布、停用、历史查询和版本回滚。
- 跨机构映射：维护机构指标编码、名称、口径、状态和生效区间。
- 冲突检测：识别机构编码碰撞、业务口径不一致和机构指标名称不一致。
- 指标血缘：返回派生指标上下游、关联维度、数据集引用和机构映射。

## 数据模型

| 表 | 用途 | 关键约束 |
| --- | --- | --- |
| `s2_metric_governance` | 指标当前治理档案 | `metric_id` 唯一 |
| `s2_metric_version` | 不可覆盖的版本快照 | `(metric_id, version_no)` 唯一 |
| `s2_metric_approval` | 提交、审批、发布、停用和回滚审计 | 指标和创建时间索引 |
| `s2_metric_org_mapping` | 跨机构指标映射 | 指标、机构、外部编码联合唯一 |

新环境会通过 launcher 的初始化 schema 自动建表。存量环境按数据库执行：

- `task/sql/BE-02_METRIC_GOVERNANCE_MYSQL.sql`
- `task/sql/BE-02_METRIC_GOVERNANCE_POSTGRESQL.sql`
- `task/sql/BE-02_METRIC_GOVERNANCE_H2.sql`

## 状态与版本规则

```text
DRAFT -> PENDING -> APPROVED -> PUBLISHED -> INACTIVE
                    \-> REJECTED
```

- 首次治理或信息变更会保存完整指标快照并生成新草稿版本。
- 提交审批前必须填写责任部门、来源系统和业务口径。
- 只有已批准版本允许发布；发布同步调用现有指标发布能力。
- 停用同步取消现有指标发布状态并保留审计记录。
- 回滚读取历史快照恢复指标定义，然后生成新的草稿版本，历史版本不被覆盖。

## 接口

接口前缀：`/api/semantic/metric-governance`

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| POST | `/models/{modelId}/bootstrap` | 为模型内已有指标初始化治理档案 |
| PUT | `/metrics/{metricId}` | 更新指标治理信息 |
| GET | `/metrics/{metricId}` | 查询档案、版本、审批和映射详情 |
| GET | `/models/{modelId}/metrics` | 按状态、责任部门、来源系统检索 |
| POST | `/metrics/{metricId}/versions` | 创建当前指标快照版本 |
| POST | `/metrics/{metricId}/versions/{versionId}/submit` | 提交版本审批 |
| POST | `/approvals/{approvalId}/approve` | 批准版本 |
| POST | `/approvals/{approvalId}/reject` | 驳回版本 |
| POST | `/metrics/{metricId}/versions/{versionId}/publish` | 发布已批准版本 |
| POST | `/metrics/{metricId}/deactivate` | 停用指标 |
| POST | `/metrics/{metricId}/versions/{versionNo}/rollback` | 回滚并生成新草稿版本 |
| PUT | `/mappings` | 新增或更新机构映射 |
| GET | `/metrics/{metricId}/mappings` | 查询机构映射 |
| GET | `/models/{modelId}/conflicts` | 检测模型内跨机构口径冲突 |
| GET | `/metrics/{metricId}/lineage` | 查询指标血缘和引用关系 |

## 验收建议

1. 先调用模型初始化接口，为 BE-01 导入的指标补齐治理档案。
2. 批量查询模型指标，核对 300 项指标均可按部门、系统和状态检索。
3. 选取标准指标完成“修改、提交、批准、发布、停用、回滚”全流程。
4. 导入跨机构映射，检查三类冲突输出并人工复核口径一致性。
5. 抽查派生指标的上下游、维度、数据集和机构映射引用。

当前自动化测试覆盖版本快照、版本回滚和三类冲突规则。95% 口径一致性指标需在比赛标准用例集上通过数据评测任务统计。
