# 国内银行 360 项指标候选目录

本目录用于证明赛题“支持至少 300 项银行常用指标”的**指标元数据容量与治理能力**。当前版本是
`0.1.0-candidate`，不是正式金标评估库，也不参与现有 Fact v3 成绩计算。

## 边界

- 保留 `evaluation/bank_nl2sql/` 现有 21 项正式指标、题目、金标和数据库包，不做覆盖或扩写。
- 只包含指标元数据和来源台账，不包含真实客户数据、银行生产数据、付费数据库内容、问题集或金标 SQL。
- 后续事实值只能使用合成数据或已授权脱敏数据，`valuePolicy` 固定为
  `SYNTHETIC_OR_DESENSITIZED_ONLY`。
- 所有指标均为 `CANDIDATE`，逐项来源定位和业务语义通过人工复核后才能晋升。

## 规模与配额

候选目录共 360 项：经营分析 150、风险管理 120、客户营销 90。14 个二级领域的配额由
`catalog_source.py` 和 `validate_catalog.py` 同时锁定；同一指标的机构、日期、渠道或客户维度不会被
重复计数。派生指标必须引用目录中已有指标，禁止悬空引用、自引用和循环依赖。

这里的“划分”是指标元数据的业务场景和领域分类，不是训练集、验证集、测试集划分。本目录不包含问题、
事实值和标准答案，因此不得从这里计算模型准确率；未来若建设 300+ 指标评测集，必须另行生成带来源版本、
事实数据、题目和冻结 split 的评测发布包。

每项指标至少提供两个不与其他指标冲突的中文别名。`legacyCodes` 用于把现有正式评估库的
`ZB001` 至 `ZB021` 一对一关联到候选目录，保持旧链路可解释；它不改变现有正式库中的代码或数据。

## 官方来源范围

来源台账只登记人民银行/全国金融标准化技术委员会、财政部、国家金融监督管理总局、国家标准平台，
以及工商银行、建设银行官方投资者关系披露。主要覆盖：

- JR/T 0134 存款统计分类及编码、JR/T 0135 贷款统计分类及编码；
- JR/T 0076 支付业务统计指标系列；
- 财政部《商业银行绩效评价指标体系》；
- 金融监管总局主要监管指标、流动性风险和资本管理规则；
- JR/T 0169 金融消费者投诉统计分类及编码；
- JR/T 0297 银行产品服务内部过程与活动管理；
- 工商银行、建设银行公开年度报告中的客户和数字渠道披露口径。

`sources.json` 仅记录来源标识、官方 URL、用途和复用边界，源码中的定义均为短篇原创转述。项目不复制
标准正文、监管附件、年报表格或其中的数值。

曾调研的商业数据库虽然字段较多，但许可限制复制、抓取、衍生和第三方共享，因此明确拒绝进入本目录。

## 文件

- `catalog_source.py`：人工可审查的指标名称、领域、来源主题和候选公式源。
- `schema.json`：单条指标的机器可读结构契约。
- `build_catalog.py`：确定性生成版本资产和 SHA-256 manifest。
- `validate_catalog.py`：数量、配额、来源、去重、公式依赖和完整性 fail-closed 校验。
- `releases/0.1.0-candidate/metrics.jsonl`：360 项候选指标。
- `releases/0.1.0-candidate/sources.json`：官方来源台账。
- `releases/0.1.0-candidate/review.csv`：供业务人员逐条填写审查结论和意见的 UTF-8 CSV。
- `releases/0.1.0-candidate/manifest.json`：版本、计数和文件哈希。

## 构建和验证

在项目根目录使用项目虚拟环境：

```powershell
.local-dev\eval-venv\Scripts\python.exe evaluation/bank_metric_catalog/build_catalog.py `
  --output-dir evaluation/bank_metric_catalog/releases/0.1.0-candidate

.local-dev\eval-venv\Scripts\python.exe evaluation/bank_metric_catalog/validate_catalog.py `
  --catalog-dir evaluation/bank_metric_catalog/releases/0.1.0-candidate

.local-dev\eval-venv\Scripts\python.exe -m unittest `
  evaluation.bank_metric_catalog.tests.test_validate_catalog -v
```

构建结果应固定为 360 项以及 150/120/90 三类配额；任一文件被修改而未重建 manifest，校验必须失败。
校验还会拒绝百分比指标使用 `SUM`、旧 21 指标映射缺失或重复、别名不足或冲突等可确定的元数据错误。

## 人工审查门禁

进入事实数据生成前必须逐项确认：中文名称与定义是否同义重复、单位和聚合方式、派生公式分子分母、
适用机构和时间粒度、来源文档中的精确章节，以及该指标是否确实属于银行常用经营语义。审查完成前不得
将本目录称为“完全正确金标”或用它计算比赛准确率。
