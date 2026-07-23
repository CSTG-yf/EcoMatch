# BE-10 业务化解释与图表推荐服务实现说明

## 已实现

- 新增确定性业务分析处理器，随 Chat 查询结果返回主推荐图、候选图、推荐理由、置信度和字段映射。
- 提供独立的图表推荐接口 `POST /api/chat/insight/recommend` 和业务解释接口 `POST /api/chat/insight/explain`，接口访问前统一校验登录态。
- 支持 `KPI_CARD`、`LINE`、`BAR`、`PIE`、`COMBO`、`TABLE` 六类图表。
- 图表规则约束饼图分类数、非负值、多指标组合图和多维明细表，避免误导性可视化。
- 业务解释仅从真实结果生成数值范围、首末变化、时间范围、统计异常候选和分类贡献度。
- 趋势及组合查询显式引用首条和末条记录值，避免只给变化率而缺少原始证据。
- 接入指标名称、描述和单位作为口径说明，并保留原始问题作为分析范围。
- 少于 3 条、空值、脱敏值和不可解析值不会输出确定性趋势结论。
- 所有解释附带范围外推限制，风险指标附加监管、授信和风险处置免责声明。
- 风险类指标自动附加监管报送、授信审批和风险处置免责声明。
- 确定性解释先于可选大模型解释执行，确保关键数值和结论可校验。

## 请求字段

- `queryText`：原始业务问题。
- `queryColumns`：查询结果字段及类型。
- `queryResults`：已完成权限过滤和脱敏的查询结果。
- `metrics`：命中的指标定义、描述和单位。

## 配置

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `s2.business-insight.small-sample-threshold` | `3` | 小样本警告阈值 |
| `s2.business-insight.pie-max-categories` | `6` | 饼图最大分类数 |
| `s2.business-insight.anomaly-z-score` | `2.0` | 异常点 Z-Score 阈值 |
| `s2.business-insight.low-confidence` | `0.65` | 低置信度 |
| `s2.business-insight.evidence-confidence` | `0.82` | 存在可校验证据时的置信度 |
| `s2.business-insight.high-confidence` | `0.95` | 高质量结果置信度 |

## 响应字段

- `recommendedChart`：主推荐图。
- `candidateCharts`：候选图列表。
- `businessExplanation`：摘要、证据、指标口径、时间范围、警告和置信度。

## 验证

- `BusinessInsightProcessorTest` 覆盖六类图表、真实数值引用、小样本、贡献度和风险提示。
- `BusinessInsightServiceTest` 覆盖独立推荐/解释接口服务、可配置分类阈值和空输入拒绝。
- 测试直接读取 DATA-03 冻结测试集，图表匹配准确率门禁不低于 90%。
- 冻结测试集自动验证必需数值、时间范围、问题范围和风险声明，解释覆盖率门禁不低于 90%。
- DATA-03 校验器通过 90 条样本、30 条问卷、来源哈希和数据隔离检查。
