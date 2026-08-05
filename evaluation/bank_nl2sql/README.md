# 银行业 NL2SQL 基准数据

此目录包含 DATA-02 的可复现产物：

- `db/build_database.py`：将比赛工作簿转换为标准基准库；
- `build_dataset.py`：冻结官方题、意图标注和模板隔离后的评测切分；
- `train.jsonl`、`dev.jsonl`、`test.jsonl`：200 道官方题；
- `augmentation.jsonl`：12 条隔离增强题，禁止参与官方评分；
- `manifest.json`：源工作簿哈希、切分数量和逐题调整记录；
- `schema.json`：JSONL 字段契约。

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

```powershell
evaluation\.venv\Scripts\python.exe evaluation/bank_nl2sql/build_dataset.py <workbook.xlsx> `
  --intent-root evaluation/bank_intent `
  --output evaluation/bank_nl2sql

evaluation\.venv\Scripts\python.exe evaluation/bank_nl2sql/validate_dataset.py `
  evaluation/bank_nl2sql
```

当前冻结版本基于源文件 SHA-256 `c3b810a4938fefc77a5c834c4c6857bec7f67162c4160abd9f66d9dd6018703c`：官方题来源划分为 train/dev/test = 120/40/40，模板隔离后的实际评测划分为 115/36/49。9 道题的调整可在 `manifest.json` 中逐题追溯，三个官方评测切分之间没有模板重叠。

## 生成并验证金标

```powershell
evaluation\.venv\Scripts\python.exe evaluation/bank_nl2sql/build_gold.py `
  evaluation/bank_nl2sql .local-dev/bank-nl2sql/bank_benchmark.sqlite

evaluation\.venv\Scripts\python.exe evaluation/bank_nl2sql/validate_gold.py `
  evaluation/bank_nl2sql .local-dev/bank-nl2sql/bank_benchmark.sqlite
```

`gold_manifest.json` 记录金标依赖的 SQLite 文件哈希。物理 SQL 已在 SQLite 与 H2 各执行 200 次且全部通过；`s2sql` 目前保存与物理 SQL 对应的可审计 SQL 模板。将其交给 SuperSonic 的正式语义翻译器前，需要先在运行环境注册银行 H2 数据源及语义 Dataset。

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

样本之间并发，单条样本内部保持上述顺序。默认并发数为 4，`--concurrency` 可调整；网络错误会按指数退避重试，模型、解析、SQL、执行和结果错误不会被掩盖。解析成功必须同时满足 API `state=COMPLETED` 与存在可执行的 `selectedParses`；候选解析仍处于 `PENDING` 或已 `FAILED` 时不会调用执行接口。每完成一条就更新输出 checkpoint，默认可从同一报告续跑：仅成功项或已经获得最终业务结果的项会跳过；HTTP/传输重试耗尽、服务端失败、会话创建失败和解释阶段失败会重新执行。若服务会话可能长期挂起，可显式传入 `--record-timeout-seconds 90`：该模式每条记录使用一个受控进程，超时仅终止该进程并记录可重试的 `HTTP_RECORD_TIMEOUT`，从而释放并发槽；未传入时仍使用原有线程执行模式。金标 SQL、标准结果和答案文本只在本地评分，不会发送给服务端。

```powershell
evaluation\.venv\Scripts\python.exe evaluation/bank_nl2sql/run_supersonic_eval.py `
  evaluation/bank_nl2sql `
  --split train `
  --base-url http://127.0.0.1:9080 `
  --agent-id 33 `
  --concurrency 4 `
  --record-timeout-seconds 90 `
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

报告包含解析、执行、结果一致率、按难度、SQL 能力、编译模板类别、选用 SQL 生成策略、LLM 路由尝试策略、执行候选来源和失败层级分组的指标、标准错误类别；每题还记录 save、parse、execute、summary 的终态、耗时和不含服务端原文的异常/重试分类。银行受约束链路只保留枚举遥测：生成器、计划意图、时间比较、计算类型、编译路由、模板类别；策略路由还只保留受约束开关、数据集资格两个布尔值和选用 SQL 生成策略枚举。顶层 LLM 路由尝试遥测只保留同样三项路由事实及“是否已创建 LLM 候选”的布尔值，即使最终候选列表没有 LLM 候选也可用于诊断。候选观测只保留首选候选是否为内部 LLM 的固定枚举、具备完整路由遥测的候选数和候选总数；不保留候选 properties、题目、答案、SQL 或行值。执行遥测只保留 SQL 安全策略/网关/JDBC 的失败层级和修复布尔值。严格结果不一致会额外标记为 `COLUMN_PROJECTION`、`ROW_COUNT`、`ORDER_ONLY` 或 `ROW_VALUE`，不保留行值、模型原始输出或 SQL。解析、执行、解释和“解析开始至解释完成”的端到端耗时分别输出样本数、平均值、P50、P95、P99 和最大值，并单列完整成功链路，失败请求不会混入成功链路性能门禁。报告不会写出题目、金标答案、实际查询行、解释文本、SQL 或服务端原始错误。

该运行器覆盖的是并发 OpenAPI 前端会话链路，不会调用浏览器侧意图交互，也不替代已登录浏览器的 UI smoke；后者仍须作为独立验收执行。

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
