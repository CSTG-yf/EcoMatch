# 国内银行 360 项指标候选目录

本目录用于证明赛题“支持至少 300 项银行常用指标”的**指标元数据容量、治理能力和泛化评测能力**。
指标目录版本是 `0.1.0-candidate`，配套 QA 版本是 `1.1.0-candidate`；二者都不是正式数值金标库，
也不参与现有 Fact v3 成绩计算。

## 边界

- 保留 `evaluation/bank_nl2sql/` 现有 21 项正式指标、题目、金标和数据库包，不做覆盖或扩写。
- 不包含真实客户数据、银行生产数据、付费数据库内容、数值答案或金标 SQL。
- `metric_qa.jsonl` 是独立的指标识别与治理问答集，只评测名称、别名、业务场景和指标口径映射；
  不将候选指标包装成已具备事实数据的正式问数题。
- 后续事实值只能使用合成数据或已授权脱敏数据，`valuePolicy` 固定为
  `SYNTHETIC_OR_DESENSITIZED_ONLY`。
- 所有指标均为 `CANDIDATE`，逐项来源定位和业务语义通过人工复核后才能晋升。

## 规模与配额

候选目录共 360 项：经营分析 150、风险管理 120、客户营销 90。14 个二级领域的配额由
`catalog_source.py` 和 `validate_catalog.py` 同时锁定；同一指标的机构、日期、渠道或客户维度不会被
重复计数。派生指标必须引用目录中已有指标，禁止悬空引用、自引用和循环依赖。

指标元数据本身的“划分”是业务场景和领域分类。配套 QA 另行采用冻结的 train/dev/test 划分，但只计算
**指标识别与治理问答准确率**，不得把该结果称为数值问数、SQL 执行或 Fact v3 准确率。若要评测 300+
指标的数值结果与 SQL，仍须补充经授权脱敏或明确标注为合成的事实数据及冻结金标。

每项指标至少提供两个不与其他指标冲突的业务别名（中文常用说法或行业通用缩写）。`legacyCodes` 用于把现有正式评估库的
`ZB001` 至 `ZB021` 一对一关联到候选目录，保持旧链路可解释；它不改变现有正式库中的代码或数据。

本版本已执行 360 项技术清理：删除“指标/口径/统计”机械后缀，按银行业务常用表达补充别名，检查规范化名称和别名冲突，按名称语义复核单位与聚合方式，并核对 24 个派生指标的分子、分母、表达式和循环依赖。清理结果记录在
`metric_cleanup_report.json`，其中 `humanReviewRequiredCount` 仍会保留候选指标的人工复核数量；这份报告证明自动化一致性检查已完成，不等于业务专家签字。

## 360 指标 QA 泛化集

`metric_qa.jsonl` 共 1080 题，每个指标固定三题：

- `CANONICAL_QUERY`：标准指标名、机构和时间表达的问数路由；
- `ALIAS_QUERY`：别名、业务场景和领域表达的泛化路由；
- `GOVERNANCE_QA`：指标定义、单位与聚合方式问答。

同一指标的三道题只进入一个 split。按指标代码模 5 固定划分后，train/dev/test 分别为
648/216/216 题，对应 216/72/72 个指标，避免同一指标的问法跨集合泄漏。每条标准答案只保留指标代码、
名称、场景、领域、单位、聚合方式及候选定义，不包含事实值。

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
- `metric_cleanup_report.json`：360 项名称、别名、单位、聚合方式、派生公式和人工复核边界的清理审计报告。
- `qa_schema.json`：单条 300+ 指标泛化 QA 的结构契约。
- `generate_metric_qa.py`：从 360 项目录确定性生成 1080 条 QA、冻结 split 和 QA manifest。
- `evaluate_metric_qa.py`：评测模型输出的指标代码与动作，并按 split、题型、场景和领域汇总错误。
- `run_metric_qa_model.py`：从盲测文件调用 OpenAI 兼容模型，支持 5 题 smoke、断点续跑和脱敏运行元数据。
- `releases/0.1.0-candidate/metrics.jsonl`：360 项候选指标。
- `releases/0.1.0-candidate/sources.json`：官方来源台账。
- `releases/0.1.0-candidate/review.csv`：供业务人员逐条填写审查结论和意见的 UTF-8 CSV。
- `releases/0.1.0-candidate/manifest.json`：版本、计数和文件哈希。
- `releases/0.1.0-candidate/metric_cleanup_report.json`：技术清理审计结果和未决人工复核清单。
- `releases/0.1.0-candidate/metric_qa.jsonl`：1080 条指标识别与治理 QA。
- `releases/0.1.0-candidate/metric_qa_blind.jsonl`：供模型读取的盲测输入，只含不透明 `id` 和问题文本。
- `releases/0.1.0-candidate/metric_qa_manifest.json`：QA 规模、split、边界和 SHA-256 完整性记录。
- `releases/0.1.0-candidate/metric_qa_baseline_report.json`：词典基线的可复现链路自检报告。

## 构建和验证

在项目根目录使用项目虚拟环境：

```powershell
.local-dev\eval-venv\Scripts\python.exe evaluation/bank_metric_catalog/build_catalog.py `
  --output-dir evaluation/bank_metric_catalog/releases/0.1.0-candidate

.local-dev\eval-venv\Scripts\python.exe evaluation/bank_metric_catalog/validate_catalog.py `
  --catalog-dir evaluation/bank_metric_catalog/releases/0.1.0-candidate

.local-dev\eval-venv\Scripts\python.exe -m unittest `
  evaluation.bank_metric_catalog.tests.test_validate_catalog -v

.local-dev\eval-venv\Scripts\python.exe evaluation/bank_metric_catalog/generate_metric_qa.py `
  --catalog-dir evaluation/bank_metric_catalog/releases/0.1.0-candidate

.local-dev\eval-venv\Scripts\python.exe evaluation/bank_metric_catalog/evaluate_metric_qa.py `
  --qa-dir evaluation/bank_metric_catalog/releases/0.1.0-candidate `
  --lexicon-baseline `
  --report evaluation/bank_metric_catalog/releases/0.1.0-candidate/metric_qa_baseline_report.json

.local-dev\eval-venv\Scripts\python.exe -m unittest discover `
  -s evaluation/bank_metric_catalog/tests -v
```

构建结果应固定为 360 项以及 150/120/90 三类配额；任一文件被修改而未重建 manifest，校验必须失败。
校验还会拒绝百分比指标使用 `SUM`、金额指标使用 `COUNT`/`RATIO` 或计数单位、旧 21 指标映射缺失或重复、
别名不足、机械后缀、别名冲突、单位/聚合推断不一致、派生公式表达式与源定义不一致等可确定的元数据错误。

词典基线会在当前生成集上得到 100%，其用途只是证明标准名/别名映射、1080 题读取和评测汇总链路可运行，
**不代表大模型泛化效果**。预测必须从 `metric_qa_blind.jsonl` 读取问题，不能读取含有 `expected` 的金标
`metric_qa.jsonl`。样本 ID 是不透明哈希，不携带指标码、split 或题型。

真实模型预测文件为 JSONL，每行必须包含完整结构化字段：

```json
{"id":"BMQ-c18d052a02c73af35dd3","metricCode":"CNB001","action":"ROUTE_TO_DATA_QUERY","metricName":"各项存款余额","matchedText":"各项存款余额","scene":"OPERATIONS","domain":"assets_liabilities_deposits_loans","unit":"万元","aggregation":"SNAPSHOT","definition":null}
```

缺少任一字段会被判为 `INVALID_PREDICTION_CONTRACT`，不会再允许只提交指标码和动作通过。评测会分别报告
指标代码、动作、元数据（名称/命中词/场景/领域/单位/聚合方式）和治理定义准确率，以及综合准确率。
将 `--lexicon-baseline` 替换为 `--predictions <模型预测文件>` 即可获得真实模型的整体、split、题型、场景、
领域准确率，以及缺失预测、未知指标、字段错配和动作错配清单。

统一模型配置到位后，先运行 5 题 smoke（密钥只保存在当前 PowerShell 环境变量，不写入仓库，也不作为命令行参数传递）：

```powershell
$env:ECOMATCH_MODEL_BASE_URL = '<OpenAI 兼容地址，包含 /v1>'
$env:ECOMATCH_MODEL_ID = '<团队统一模型 ID>'
$env:ECOMATCH_MODEL_API_KEY = '<密钥>'

python evaluation/bank_metric_catalog/run_metric_qa_model.py `
  --blind evaluation/bank_metric_catalog/releases/0.1.0-candidate/metric_qa_blind.jsonl `
  --output .local-dev/metric-qa-smoke/predictions.jsonl `
  --metadata-output .local-dev/metric-qa-smoke/run.json `
  --base-url $env:ECOMATCH_MODEL_BASE_URL --model $env:ECOMATCH_MODEL_ID `
  --api-key $env:ECOMATCH_MODEL_API_KEY --limit 5

Remove-Item Env:ECOMATCH_MODEL_API_KEY
```

本地 DeepSeek 测试可使用 `.local-dev/Run-DeepSeekMetricQa.ps1`。脚本按模式隔离结果，smoke
仅按实际 5 题作为分母；请求失败会阻止继续 dev/test。dev/test 通过本地金标文件只读取对应 split 的
不透明 ID，真正发送给模型的输入仍只有 `id` 和 `question`：

```powershell
powershell -ExecutionPolicy Bypass -File .local-dev/Run-DeepSeekMetricQa.ps1 -Mode smoke
# smoke 无请求或协议错误后再运行：
powershell -ExecutionPolicy Bypass -File .local-dev/Run-DeepSeekMetricQa.ps1 -Mode dev
```

模型运行记录会保存每题 `latencyMs`，评测报告汇总平均、P50、P95 和 P99。断点续跑会保留有效预测，
并自动重试此前的超时或协议错误行。smoke 分母为 5，dev/test 分母分别为 216；test 模式要求先存在
无请求错误的 dev 运行报告。未经 dev 错误分析和配置冻结，不运行 test。

模型输出通过协议检查后，使用 `evaluate_metric_qa.py --predictions` 评分。360 项合成事实库和第一版
可执行单点 SQL 测试集位于 `evaluation/bank_nl2sql/synthetic_360/`：它包含 13 家虚拟机构、17 个
月末、79,560 条明确标注为 `SYNTHETIC` 的事实，以及每项指标 1 道可执行单点题。该数据域与官方 21 项
完全隔离，只用于展示和泛化测试，不进入正式成绩。

## 人工审查门禁

进入事实数据生成前必须逐项确认：中文名称与定义是否同义重复、单位和聚合方式、派生公式分子分母、
适用机构和时间粒度、来源文档中的精确章节，以及该指标是否确实属于银行常用经营语义。审查完成前不得
将本目录称为“完全正确金标”或用它计算比赛准确率。
