# 银行业 NL2SQL 基准数据

此目录包含 DATA-02 的可复现产物：

- `official/`：版本化正式评估库目录，`official/CURRENT.json` 指向当前正式版本；
- `db/build_database.py`：将比赛工作簿转换为标准基准库；
- `build_dataset.py`：冻结官方题、意图标注和模板隔离后的评测切分；
- `train.jsonl`、`dev.jsonl`、`test.jsonl`：199 条官方题（2.0.0 正式评估库，唯一正式评分依据）；
- `augmentation.jsonl`：12 条隔离增强题，禁止参与官方评分；
- `manifest.json`：源工作簿哈希、切分数量和逐题调整记录；
- `schema.json`：JSONL 字段契约。

## 正式评估库 2.0.0

`evaluation/bank_nl2sql/official/2.0.0/` 是**唯一正式评分依据**，包含：

- `bank-nl2sql-ground-truth-v2.0.0.xlsx`：正式 ground-truth 工作簿（199 题）；
- `official-manifest.json`：`datasetVersion=2.0.0`、`canonicalReady=true`、
  `officialCount=199`、来源切分 train/dev/test = 119/40/40、`removedIds`、
  源/候选/事实区哈希与变更计数；
- `contract-change-ledger.json`：全部变更的逐条账本（含文本哈希）；
- `final-audit-summary.json`：199 条全量 VERIFIED 审查证据摘要；
- 以上各产物的 SHA-256 sidecar（`<UPPER_SHA256>  <文件名>\n`）。

正式库相对冻结原始工作簿（`source.xlsx`，只读、永不修改）只包含账本声明的变更：

- 5 个答案修正（`ANSWER_CORRECTION`）；
- 10 个题目澄清（`QUESTION_CLARIFICATION`）；
- 1 个训练题删除（`QUESTION_REMOVAL`）：该训练题因缺失同比基期证据而无法复核，
  已从正式库移除（`removedIds` 仅含账本声明的这一条）；
- 0 个契约错误，事实区域哈希不变。

正式统计：`officialCount=199`，来源切分 119/40/40，模板隔离后的评测切分
114/36/49（test 仍为 49），增强样本 12 条（不参与官方评分）。三个官方评测
切分之间没有模板重叠。

### 生成正式评估库（promote）

```powershell
evaluation\.venv\Scripts\python.exe evaluation/bank_nl2sql/promote_ground_truth.py `
  --candidate-dir .local-dev/gt-audit/codex-hash-contract `
  --audit-dir .local-dev/gt-audit/codex-hash-audit-two `
  --version 2.0.0 `
  --output evaluation/bank_nl2sql/official/2.0.0
```

promote 对候选 manifest/sidecar、候选工作簿/sidecar、变更账本/sidecar、
源工作簿哈希、199 条全量 VERIFIED 审查证据、空 correction ledger、事实区
哈希以及账本声明的变更逐项 fail-closed 验证，输出确定性且不含时间戳；
任何一项不匹配都不产出任何文件。

## 标准基准库

`db/build_database.py` 将比赛工作簿转换为可重复生成的标准基准库：

- `bank_organization`：机构维表；
- `bank_metric_definition`：指标与单位维表；
- `bank_metric_daily`：按日期、机构、指标唯一的事实表。

## 生成标准库

```powershell
evaluation\.venv\Scripts\python.exe evaluation/bank_nl2sql/db/build_database.py <workbook.xlsx> `
  --sqlite-output .local-dev/bank-nl2sql/bank_benchmark.sqlite `
  --h2-script-output .local-dev/bank-nl2sql/bank_benchmark_h2.sql
```

需要生成本地 H2 文件库时，再传入 `--h2-database-output`、`--java-path` 和 `--h2-jar-path`。生成的数据库文件只放在 `.local-dev`，不提交二进制产物。

## 校验标准库

```powershell
evaluation\.venv\Scripts\python.exe evaluation/bank_nl2sql/db/validate_database.py `
  .local-dev/bank-nl2sql/bank_benchmark.sqlite
```

校验器检查机构、指标和事实表数量，联合键、外键、完整日期序列以及每天完整的 `机构 × 指标` 立方体。

## 构建标注数据集

正式版必须使用 2.0.0 官方工作簿与 `official-manifest.json`（manifest 严格
匹配工作簿名称/SHA-256/题数/来源切分/`canonicalReady` 后，才允许忽略
`removedIds`；无 manifest 时保持原严格未知意图行为；除 `removedIds` 外
任何未知/缺失 ID 一律失败）：

```powershell
evaluation\.venv\Scripts\python.exe evaluation/bank_nl2sql/build_dataset.py `
  evaluation/bank_nl2sql/official/2.0.0/bank-nl2sql-ground-truth-v2.0.0.xlsx `
  --intent-root evaluation/bank_intent `
  --official-manifest evaluation/bank_nl2sql/official/2.0.0/official-manifest.json `
  --output evaluation/bank_nl2sql

evaluation\.venv\Scripts\python.exe evaluation/bank_nl2sql/validate_dataset.py `
  evaluation/bank_nl2sql
```

正式版基于源文件 SHA-256
`c3b810a4938fefc77a5c834c4c6857bec7f67162c4160abd9f66d9dd6018703c`
（冻结原始工作簿 `source.xlsx`，只读、永不修改）。官方题来源划分为
train/dev/test = 119/40/40，模板隔离后的实际评测划分为 114/36/49
（test 仍为 49），增强样本 12 条。`manifest.json` 记录逐题调整，
三个官方评测切分之间没有模板重叠。

## 生成并验证金标

```powershell
evaluation\.venv\Scripts\python.exe evaluation/bank_nl2sql/build_gold.py `
  evaluation/bank_nl2sql .local-dev/bank-nl2sql/bank_benchmark.sqlite

evaluation\.venv\Scripts\python.exe evaluation/bank_nl2sql/validate_gold.py `
  evaluation/bank_nl2sql .local-dev/bank-nl2sql/bank_benchmark.sqlite
```

`gold_manifest.json` 记录金标依赖的 SQLite 文件哈希与数据集版本
（2.0.0 从 `manifest.json` 继承；无 manifest 的旧兼容路径仍为 0.1.0）。
物理 SQL 已在 SQLite 与 H2 各执行 199 次且全部通过；`s2sql` 目前保存与
物理 SQL 对应的可审计 SQL 模板。将其交给 SuperSonic 的正式语义翻译器前，
需要先在运行环境注册银行 H2 数据源及语义 Dataset。

## 冻结与盲测

```powershell
evaluation\.venv\Scripts\python.exe evaluation/bank_nl2sql/freeze_dataset.py `
  evaluation/bank_nl2sql .local-dev/bank-nl2sql/bank_benchmark.sqlite

evaluation\.venv\Scripts\python.exe evaluation/bank_nl2sql/evaluate_predictions.py `
  evaluation/bank_nl2sql <predictions.jsonl> .local-dev/bank-nl2sql/bank_benchmark.sqlite `
  --report .local-dev/bank-nl2sql/evaluation-report.json
```

预测文件每行只含 `id` 与 `sql`。评测器只读取 `test.jsonl` 作为评测金标，拒绝任何写 SQL，并报告解析成功率、执行成功率、结果一致率、难度与 SQL 能力分布。代码中的 `dataset_access.load_records(..., purpose="training")` 只会返回 train/dev，读取测试金标必须显式传入 `allow_test_gold=True`。

## 真实模型盲测

`run_model_blind_eval.py` 只将保留题的 `id`、`question` 和 SQLite schema/机构/指标元数据发送给 OpenAI 兼容模型；它不会把金标 SQL、标准结果或答案文本放入请求。预测生成与评分必须分两步执行：

```powershell
evaluation\.venv\Scripts\python.exe evaluation/bank_nl2sql/run_model_blind_eval.py `
  evaluation/bank_nl2sql .local-dev/bank-nl2sql/bank_benchmark.sqlite `
  --base-url http://<model-host>:<port>/v1 --model <model-id> `
  --output .local-dev/bank-nl2sql/model-blind-predictions.jsonl `
  --metadata-output .local-dev/bank-nl2sql/model-blind-run.json

evaluation\.venv\Scripts\python.exe evaluation/bank_nl2sql/evaluate_predictions.py `
  evaluation/bank_nl2sql .local-dev/bank-nl2sql/model-blind-predictions.jsonl `
  .local-dev/bank-nl2sql/bank_benchmark.sqlite `
  --report .local-dev/bank-nl2sql/model-blind-report.json
```

2026-07-23 的首个真实模型基线使用局域网 `Qwen3.6-35B-A3B-UD-Q4_K_M.gguf`、温度 0，对 49 道保留题完成了全量预测：解析成功率 100%，执行成功率 91.8367%，结果一致率 0%。这是失败基线而非验收通过；在改进语义翻译/提示词链路前不得将其用于发布结论。生成的预测、运行元数据和报告仅存放在 `.local-dev/bank-nl2sql/`。

## SuperSonic 端到端评测

`run_supersonic_eval.py` 直接调用本地开发环境的 `/openapi` 接口，不需要浏览器、Bearer Token 或 Cookie。每道题使用独立会话，按前端真实顺序执行：

1. `POST /openapi/chat/manage/save`；
2. `POST /openapi/chat/query/parse`；
3. `POST /openapi/chat/query/execute`；
4. 轮询 `POST /openapi/chat/query/getExecuteSummary`；
5. 结果匹配时删除临时会话，失败会话保留用于排查。

样本之间并发，单条样本内部保持上述顺序。默认并发数为 4，`--concurrency` 可调整；网络错误会按指数退避重试，模型、解析、SQL、执行和结果错误不会被掩盖。每完成一条就更新输出 checkpoint，默认可从同一报告续跑。金标 SQL、标准结果和答案文本只在本地评分，不会发送给服务端。

```powershell
evaluation\.venv\Scripts\python.exe evaluation/bank_nl2sql/run_supersonic_eval.py `
  evaluation/bank_nl2sql `
  --split train `
  --base-url http://127.0.0.1:9080 `
  --agent-id 33 `
  --concurrency 4 `
  --max-records 5 `
  --output .local-dev/bank-nl2sql/api-train-smoke.json
```

smoke 通过后，去掉 `--max-records 5` 即可运行完整训练集。重复相同命令会从输出 checkpoint 续跑；需要从头重跑时显式传入 `--no-resume`。测试集只能用于冻结后的最终验收，命令必须同时传入 `--acknowledge-final-test` 和本地运行登记文件；每次运行会写入递增的 `runNumber`。

```powershell
evaluation\.venv\Scripts\python.exe evaluation/bank_nl2sql/run_supersonic_eval.py `
  evaluation/bank_nl2sql `
  --split test --acknowledge-final-test `
  --base-url http://127.0.0.1:9080 `
  --agent-id <绑定银行数据集的助理ID> `
  --concurrency 4 `
  --run-registry .local-dev/bank-nl2sql/supersonic-final-test-runs.json `
  --output .local-dev/bank-nl2sql/supersonic-final-report.json
```

报告包含解析、执行、结果一致率、按难度和 SQL 能力分组的指标、标准错误类别、S2SQL 与物理 SQL 摘要；解析、执行、解释和“解析开始至解释完成”的端到端耗时分别输出样本数、平均值、P50、P95、P99 和最大值，并单列完整成功链路，失败请求不会混入成功链路性能门禁。报告不会写出实际查询行或金标答案。

## QA-03 语义缓存验收

`run_qa03_cache_eval.py` 在已部署实例上验证真实语义查询缓存。运行器给模板 SQL 增加一次性注释形成全新缓存键，要求首请求明确返回 `useCache=false`，等待异步缓存写入后要求连续热请求全部返回 `useCache=true`，并通过超级管理员网关监控接口复核命中、未命中、物理执行和阶段计数增量。

查询模板只保存在本地，例如 `.local-dev/bank-nl2sql/qa03-cache-query.json`：

```json
{
  "sql": "SELECT branch_id, SUM(balance) FROM bank_account WHERE biz_date = '2026-07-28' GROUP BY branch_id",
  "modelIds": [1]
}
```

Token 只通过环境变量传入，HTTP 客户端拒绝重定向，避免管理员凭据被转发到非预期地址；输出报告不包含服务 URL、Token、Cookie、SQL、查询响应或结果行：

```powershell
$env:QA03_AUTH_TOKEN='<超级管理员令牌>'

evaluation\.venv\Scripts\python.exe evaluation/bank_nl2sql/run_qa03_cache_eval.py `
  --base-url http://127.0.0.1:9080 `
  --query-template .local-dev/bank-nl2sql/qa03-cache-query.json `
  --scenario aggregate-cache `
  --warm-samples 200 `
  --output .local-dev/bank-nl2sql/qa03-cache-report.json
```

模板账号必须是目标环境提供的只读压测账号；运行器会移除模板中的 `needAuth` 和 `innerLayerNative`，不能通过验收工具关闭鉴权或选择内部执行模式。

## 页面问答诊断（手工备用）

页面采集器保留为手工 UI/渲染诊断工具，不再作为批量效果评测的默认入口。需要排查页面独有问题时分两步：

1. `run_ui_chat_capture.mjs` 连接一个已登录、已打开银行问数页面的 Chromium 调试会话，在 `#chatInput` 输入 dev 问题，并从页面渲染的表格读取表头、所有分页和终态；它不会自行调用 `/api/chat/query/*`。
2. `evaluate_ui_capture.py` 将页面采集报告与本地 dev 金标比较。展示中的千位分隔符、空值和后续分页会在评分前归一化，输出是有效 JSON，且不包含金标行。

页面采集运行器只允许 `train` 或 `dev`，因此不会因误操作读取冻结 test。需要验证单一已知开发题时可传入 `--record-id <ID>`；先启动专用浏览器并在其中登录一次：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/Start-Ui-Evaluation-Browser.ps1 -AgentId 33
```

随后运行采集和评分：

```powershell
node evaluation/bank_nl2sql/run_ui_chat_capture.mjs `
  --browser-debug-url http://127.0.0.1:9222 `
  --dataset evaluation/bank_nl2sql `
  --split dev `
  --page-url http://127.0.0.1:9000/webapp/chat?agentId=33 `
  --agent-id 33 `
  --output .local-dev/bank-nl2sql/ui-dev-capture.json

evaluation\.venv\Scripts\python.exe evaluation/bank_nl2sql/evaluate_ui_capture.py `
  evaluation/bank_nl2sql .local-dev/bank-nl2sql/ui-dev-capture.json `
  --split dev `
  --output .local-dev/bank-nl2sql/ui-dev-score.json
```

页面表格使用 `data-testid="ui-chat-result-table"` 和列语义标识供采集器定位；这些属性不改变用户界面或查询行为。
