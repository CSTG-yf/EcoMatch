# DATA-02 NL2SQL 标注数据集实现说明

## 交付范围

- 从比赛 Excel 构建标准银行星型样例库，事实字段与 BE-01 保持一致。
- 生成 96 条正样本，字段包含问题、标准化意图、S2SQL、物理 SQL、预期结果摘要及结果哈希。
- 覆盖单表、多表、嵌套、聚合、同比、环比、TopN 和跨机构查询。
- 生成 96 条错误样本，映射、口径、关联、过滤、语法和执行错误各 16 条。
- 训练、验证和测试集各 32 条，问法模板组完全隔离。

## 自动验收

`evaluation/bank_nl2sql/validate_dataset.py` 会重新加载比赛 Excel 到内存 SQLite，逐条执行正样本物理 SQL 并比对结果哈希；同时验证字段完整性、模板隔离、八类能力覆盖和六类错误行为。

```bash
python evaluation/bank_nl2sql/build_dataset.py
python evaluation/bank_nl2sql/validate_dataset.py
```

生成物、数据协议和详细说明见 `evaluation/bank_nl2sql/README.md`。

校验器支持通过 `--workbook` 显式传入仓库外的比赛 Excel；参数缺省时才查找 `task/*.xlsx`。这保证官方文件无需复制进 Git，也可以重复执行完整 SQL 验收。
