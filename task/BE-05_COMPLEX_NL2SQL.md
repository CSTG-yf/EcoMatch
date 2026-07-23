# BE-05 复杂 NL2SQL 增强实现说明

## 实现范围

- 在 LLM 候选进入语义解析前执行只读语法、语义数据集、字段、关联条件、时间过滤和复杂能力校验。
- 识别单表、多表、嵌套、聚合、窗口、同比、环比、TopN 和跨机构查询能力。
- 按模型权重与问题能力覆盖度联合排序；等价 SQL 固定保留得分最高候选。
- 将失败原因回灌 `priorExts`，下一轮生成不得通过删除过滤条件规避错误。
- 统一映射、口径、关联、过滤、语法和执行错误类型，并在解析结果中返回可重试状态和能力标签。
- 数据库执行失败时记录结构化反馈；仅当 `EXECUTION_SQL_CORRECTOR` 显式启用时执行一次修复重试。
- 执行修复必须通过确定性安全门禁，保持原表、选择字段、过滤字段、操作符和字面量不变。

## 关键接口

`SqlEvaluation` 新增以下诊断字段：

- `errorType`：稳定错误分类。
- `retryable`：是否允许重新生成或修复。
- `semanticScore`：问题要求能力的覆盖比例。
- `features`：SQL 已识别复杂能力。

执行失败或修复成功记录在 `SemanticParseInfo.properties.sqlExecutionFeedback`，包含错误类型、原始错误、最终错误、是否尝试修复和修复结果。

## 配置

`EXECUTION_SQL_CORRECTOR` 默认关闭。开启后只允许一次基于数据库错误的物理 SQL 修复，不开启时仅分类和记录错误，不增加模型调用。

## 验收

冻结 DATA-02 共 96 条 S2SQL 全部通过复杂校验，通过率 100%；对应 96 条物理 SQL 仍全部执行成功。机器可读结果见 `evaluation/bank_nl2sql/be05_evaluation_report.json`。

```bash
mvn -pl headless/chat -am \
  "-Dtest=ComplexSqlValidatorTest,SqlExecutionRepairServiceTest,BankNl2SqlDatasetValidationTest,LLMResponseServiceTest" \
  "-Dsurefire.failIfNoSpecifiedTests=false" \
  "-Dspotless.skip=true" test

python evaluation/bank_nl2sql/validate_dataset.py
```
