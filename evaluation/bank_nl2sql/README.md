# 银行业 NL2SQL 基准数据

此目录包含 DATA-02 的可复现产物：

- `official/`：版本化正式评估库目录，`official/CURRENT.json` 指向当前正式版本；
- `db/build_database.py`：将比赛工作簿转换为标准基准库；
- `build_dataset.py`：冻结官方题、意图标注和来源评测切分；
- `train.jsonl`、`dev.jsonl`、`test.jsonl`：199 条官方题（当前 2.0.5 正式评估库，唯一正式评分依据）；
- `augmentation.jsonl`：12 条隔离增强题，禁止参与官方评分；
- `manifest.json`：源工作簿哈希、切分数量和逐题调整记录；
- `schema.json`：JSONL 字段契约。

## Windows 环境准备

首次在干净克隆中运行评测前，在仓库根目录执行：

```powershell
py -3 -m venv evaluation\.venv
evaluation\.venv\Scripts\python.exe -m pip install --upgrade pip
evaluation\.venv\Scripts\python.exe -m pip install -r evaluation\requirements.txt
```

后续所有数据校验、导入和 `Run-OfficialBankEvaluation.ps1` 均使用这个项目内虚拟环境。
模型服务地址、管理员 Token 和目标环境生成的 Agent ID 属于本地部署配置，不提交到仓库；
请按“导入正式数据库与初始化 Agent”章节执行并使用脚本输出的 Agent ID。

## 正式评估库 2.0.5

`evaluation/bank_nl2sql/official/CURRENT.json` 当前指向
`evaluation/bank_nl2sql/official/2.0.5/`。2.0.5 是**唯一当前正式评分依据**，
2.0.3、2.0.4 作为不可变历史版本继续保留。

2.0.5 包含：

- `bank-nl2sql-ground-truth-v2.0.4.xlsx`：仅修正 5 条 train 答案、事实区域不变的 ground-truth 工作簿（199 题）；
- `official-manifest.json`：`datasetVersion=2.0.5`、`canonicalReady=true`、
  `officialCount=199`、来源切分 train/dev/test = 119/40/40、`removedIds`、
  父版本、事实区哈希与 answer-fact 发布证据；
- `contract-change-ledger.json`：从原始工作簿到2.0.0的既有题目/答案契约账本；
- `answer-fact-ledger.json`：32 条 train/dev 类型化答案事实与必要的可执行结果查询；
- `answer-fact-audit-summary.json`：父版本答案修正与本版本事实契约的审计摘要；
- 以上各产物的 SHA-256 sidecar（`<UPPER_SHA256>  <文件名>\n`）。

正式库相对冻结原始工作簿（`source.xlsx`，只读、永不修改）只包含账本声明的变更：

- 5 个答案修正（`ANSWER_CORRECTION`）；
- 10 个题目澄清（`QUESTION_CLARIFICATION`）；
- 1 个训练题删除（`QUESTION_REMOVAL`）：该训练题因缺失同比基期证据而无法复核，
  已从正式库移除（`removedIds` 仅含账本声明的这一条）；
- 0 个契约错误，事实区域哈希不变。

2.0.5 不修改数据库事实；它在 2.0.4 中修正 5 条题意与答案不一致的 train 答案，
并为 32 条 train/dev 答案提供完整类型化 `expected.answerFacts`。其中综合排名完整评分
9 项当前值与 9 项排名，“较年初”统一使用当前期前一年 12-31。Fact v3 发布门禁为
train+dev 159/159 READY，test.jsonl 与 2.0.3 逐字节一致。

正式统计：`officialCount=199`，来源与正式评测均为 train/dev/test =
119/40/40（199 条，原始 200 条减去 1 条账本声明的训练题删除），增强样本
12 条（不参与官方评分）。`manifest.json` 的 `templateOverlap` 仅披露
模板重叠风险，不改变任何题目归属。

### 生成增量正式评估库

```powershell
evaluation\.venv\Scripts\python.exe evaluation/bank_nl2sql/amend_official_answer_facts.py `
  --parent evaluation/bank_nl2sql/official/2.0.4 `
  --spec evaluation/bank_nl2sql/answer-facts/2.0.5.json `
  --output evaluation/bank_nl2sql/official/2.0.5 `
  --update-current
```

生成器验证父包全部 sidecar、父答案哈希、修正ID/split、工作簿逐单元格差异、
事实区哈希和test不变性；任何异常都在切换 `CURRENT.json` 前失败。

### 历史2.0.0一次性生成流程（promote）

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

需要生成本地 H2 文件库时，再传入 `--h2-database-output`、`--java-path` 和 `--h2-jar-path`。普通本地生成物只放在 `.local-dev`；唯一例外是经完整发布门禁后、由生成器写入的 `db/releases/<version>/` 官方伴随包。

## 校验标准库

```powershell
evaluation\.venv\Scripts\python.exe evaluation/bank_nl2sql/db/validate_database.py `
  .local-dev/bank-nl2sql/bank_benchmark.sqlite
```

校验器检查机构、指标和事实表数量，联合键、外键、完整日期序列以及每天完整的 `机构 × 指标` 立方体。

## 官方数据库伴随导入包（companion import package）

`db/releases/2.0.5/` 是基于当前 2.0.5 官方工作簿冻结的**伴随导入包**，**不是运行时
`semantic.mv.db`**：它只包含不可变产物与导入器，运行时数据库由导入器按需生成。
包内文件：

- `bank.sqlite`：SQLite 标准基准库（13 家机构、21 个指标、132678 条事实）；
- `bank-h2.sql`：H2 脚本（`bank_organization`、`bank_metric_definition`、
  `bank_metric_daily` 三张表及 `bank_benchmark.*` 三个兼容视图）；
- `database-manifest.json`：`schemaVersion`、官方版本/路径/SHA-256、来源日期范围
  （2024-12-31 至 2026-04-30）、每个产物的 SHA-256 与字节数、精确行数
  （organizations=13、metrics=21、facts=132678）。

`db/Import-OfficialBankData.ps1`（及双击包装 `Import-OfficialBankData.cmd`）把该包
导入本地 H2：默认目标为仓库内 `.local-dev/state/semantic`（与运行时
`S2_METADATA_DB_PATH=.local-dev/state/semantic` 一致），可显式传入
`-TargetDatabase`/`-JavaPath`/`-H2JarPath`，缺省时自动发现项目本地 JDK/H2 jar
（`JAVA_HOME`、`.local-dev/jdk`、`ECOMATCH_H2_JAR`、`.local-dev` Maven 仓库、
`~/.m2`）。导入前校验 manifest 与全部产物哈希；目标库被占用/锁定（
`<base>.lock.db`）时直接拒绝并退出（不会停止任何进程、不删除任何文件）；只通过
`org.h2.tools.RunScript` 以项目标准 `root`/`semantic` 凭据应用包内 H2 脚本（仅
bank 基准表/视图，幂等）；导入后再次校验三个精确行数。它不会删除或覆盖任意数据库
文件，也不会触碰 Agent/model/chat 配置或会话。

一键导入（默认目标）：

```powershell
powershell -ExecutionPolicy Bypass -File evaluation/bank_nl2sql/db/Import-OfficialBankData.ps1
```

或直接双击 `evaluation/bank_nl2sql/db/Import-OfficialBankData.cmd`。指定自定义目标：

```powershell
powershell -ExecutionPolicy Bypass -File evaluation/bank_nl2sql/db/Import-OfficialBankData.ps1 `
  -TargetDatabase .local-dev/state/semantic `
  -JavaPath C:\path\to\java.exe -H2JarPath C:\path\to\h2-2.2.224.jar
```

`db/releases/**` 已在 `.gitattributes` 标记为 `-text`，保证包内产物校验和在
跨平台检出时保持稳定。

从 v2.0.5 唯一事实源重建该包时，必须由生成器同时写入 SQLite、H2 脚本和
`database-manifest.json`，不得手工修改其中任一产物：

```powershell
evaluation\.venv\Scripts\python.exe evaluation/bank_nl2sql/db/build_database.py `
  evaluation/bank_nl2sql/official/2.0.5/bank-nl2sql-ground-truth-v2.0.4.xlsx `
  --sqlite-output evaluation/bank_nl2sql/db/releases/2.0.5/bank.sqlite `
  --h2-script-output evaluation/bank_nl2sql/db/releases/2.0.5/bank-h2.sql `
  --database-manifest-output evaluation/bank_nl2sql/db/releases/2.0.5/database-manifest.json `
  --official-version 2.0.5 `
  --source-relative-path evaluation/bank_nl2sql/official/2.0.5/bank-nl2sql-ground-truth-v2.0.4.xlsx
```

## 可移植「银行问数」Agent 导入

事实表导入包不会复制本机 `semantic.mv.db`，因此也不会夹带用户、会话、历史自增 ID
或密钥。完成事实表导入并启动服务后，使用 `bootstrap_bank_agent.py` 将当前正式工作簿
导入一个已经创建好的银行语义模型，并按接口返回的真实 `dataSetId` 创建或更新
「银行问数」Agent。脚本同时应用 `repro/best_bank_on.json` 中的系统参数；Agent 不含
训练题示例或 gold 内容，模型和数据集 ID 都由目标环境显式指定/动态发现。

前置条件：

1. 停止 standalone，运行上面的 `Import-OfficialBankData.ps1`，再启动服务；
2. 在目标环境创建指向 `bank_metric_daily` 的语义模型，字段至少包含
   `data_date`、`org_code`、`metric_code`、`metric_value`；
3. 已配置一个可用的聊天模型，并准备管理员 Token。Token 只通过环境变量读取，
   不会写入文件或报告。

先做零写入检查：

```powershell
evaluation\.venv\Scripts\python.exe evaluation/bank_nl2sql/bootstrap_bank_agent.py `
  evaluation/bank_nl2sql --model-id 1 --chat-model-id 1 --dry-run
```

正式导入：

```powershell
$env:ECOMATCH_AUTH_TOKEN = '<管理员 Token>'
evaluation\.venv\Scripts\python.exe evaluation/bank_nl2sql/bootstrap_bank_agent.py `
  evaluation/bank_nl2sql --base-url http://127.0.0.1:9080 `
  --model-id 1 --chat-model-id 1
Remove-Item Env:ECOMATCH_AUTH_TOKEN
```

Windows 队友也可以直接双击 `evaluation/bank_nl2sql/Bootstrap-BankAgent.cmd`，按提示
输入语义模型 ID、聊天模型 ID 和管理员 Token；脚本退出前会清空进程内 Token。

导入是幂等的：语义资源按稳定业务键更新，Agent 按名称更新；目标环境生成的 Agent ID
不要求等于本机历史 ID 33。命令输出最终 `modelId`、`dataSetId`、`agentId`、正式版本和
manifest 哈希，队友应使用输出的 `agentId` 打开页面或运行评测。

## 构建标注数据集

正式版必须使用 `official/CURRENT.json` 指向的2.0.5工作簿与
`official-manifest.json`（manifest严格
匹配工作簿名称/SHA-256/题数/来源切分/`canonicalReady` 后，才允许忽略
`removedIds`；无 manifest 时保持原严格未知意图行为；除 `removedIds` 外
任何未知/缺失 ID 一律失败）：

```powershell
evaluation\.venv\Scripts\python.exe evaluation/bank_nl2sql/build_dataset.py `
  evaluation/bank_nl2sql/official/2.0.5/bank-nl2sql-ground-truth-v2.0.4.xlsx `
  --intent-root evaluation/bank_intent `
  --official-manifest evaluation/bank_nl2sql/official/2.0.5/official-manifest.json `
  --output .local-dev/bank-nl2sql/rebuild-2.0.5

evaluation\.venv\Scripts\python.exe evaluation/bank_nl2sql/validate_dataset.py `
  .local-dev/bank-nl2sql/rebuild-2.0.5
```

正式版基于 v2.0.5 唯一事实源 SHA-256
`50159f20de30ef1a4a9cd436aa16965c55725bf226bd0de4b932d3d94a981ee0`
（工作簿只读、永不手工修改）。199 条官方题的来源
与正式评测均为 train/dev/test = 119/40/40，增强样本 12 条。
`manifest.json` 记录 v2.0.5 的 answer-fact 溯源与 `templateOverlap` 风险披露，
绝不改变题目归属。

## 发布完整性校验

```powershell
evaluation\.venv\Scripts\python.exe evaluation/bank_nl2sql/build_gold.py `
  evaluation/bank_nl2sql evaluation/bank_nl2sql/db/releases/2.0.5/bank.sqlite

evaluation\.venv\Scripts\python.exe evaluation/bank_nl2sql/validate_gold.py `
  evaluation/bank_nl2sql evaluation/bank_nl2sql/db/releases/2.0.5/bank.sqlite

evaluation\.venv\Scripts\python.exe evaluation/bank_nl2sql/freeze_dataset.py `
  evaluation/bank_nl2sql evaluation/bank_nl2sql/db/releases/2.0.5/bank.sqlite
```

`validate_gold.py` 是发布完整性门禁：199 条生成 SQL 必须全部可执行，且查询结果与
结构化 rows 一致。它不参与模型得分，也不比较 SQL 文本；模型效果只以结果事实合同为准，
最终回答文本仅作非计分诊断。`freeze_dataset.py` 同时记录事实合同与上述完整性结果。

## 官方运行时评测（唯一入口）

所有成员只能通过 `Run-OfficialBankEvaluation.ps1` 产出可比较的成绩。它使用
`official_runtime_evaluation_v3.json` 固定：v2.0.5 数据库包、Fact v3 评分、独立会话、
串行执行、结果-only 执行模式、固定 smoke 和完整分母。它仍按前端真实顺序调用：新建会话
→ 解析 → 执行 → 轮询结果；评测请求显式携带 `resultOnly=true`，服务端保留查询编译、
结果投影和结构化事实处理，跳过最终回答/通用摘要模型。金标 SQL、结果和答案文本始终只在
本地评分，绝不发送给服务端。

每次存在待测题目时，runner 会先用一次可丢弃的预热会话触发银行计划模型的固定前缀/KV
缓存，然后删除该会话；预热耗时单独记录在报告 `runtimeDiagnostics.warmup`，不计入任何
题目的 `parseMs`、`endToEndMs` 或运行时成绩。预热失败会停止本轮评测，避免把冷启动成本
隐含计入第一题。

正式单题判定为：

```text
casePass = resultExact
```

- `resultExact`：SQL 执行结果可证明源答案要求的全部结果事实及其实体绑定；不比较 SQL
  文本、AST、列顺序或展示表形态。
- 最终回答文本：仅作为用户可见的非计分展示；最终回答处理状态只进入运行时诊断，
  不影响 `casePass`。
- `caseAccuracy`：唯一正式成绩，分母永远是当前所选集合的全部题目。

### 0. 一次性准备

1. 停止 standalone，使用上文 `Import-OfficialBankData.ps1` 导入 v2.0.5；再启动服务。
2. 创建目标环境的语义模型与聊天模型。
3. 导入 Agent，并保存不含密钥的启动回执：

```powershell
$env:ECOMATCH_AUTH_TOKEN = '<管理员 Token>'
evaluation\.venv\Scripts\python.exe evaluation/bank_nl2sql/bootstrap_bank_agent.py `
  evaluation/bank_nl2sql --base-url http://127.0.0.1:9080 `
  --model-id 1 --chat-model-id 1 `
  --output .local-dev/bank-nl2sql/official-v3/bootstrap-receipt.json
Remove-Item Env:ECOMATCH_AUTH_TOKEN
```

也可以双击 `evaluation/bank_nl2sql/Bootstrap-BankAgent.cmd`；成功后它会在相同位置生成
`bootstrap-receipt.json`。回执只记录 Agent、模型 ID 和配置指纹，不含 Token、模型地址或
密钥。正式评测会将该回执与当前官方 manifest 及导入计数（13 家机构、21 个指标、132678 条
事实）逐项校验；不一致时拒绝运行。

### 1. 固定 smoke

使用干净、且包含正式评测基线的 Git 工作树。`RunId` 在同一轮 smoke、train、dev、test 中
必须保持相同，模型标签也必须保持相同。

```powershell
powershell -ExecutionPolicy Bypass -File evaluation/bank_nl2sql/Run-OfficialBankEvaluation.ps1 `
  -Mode smoke -RunId qwen66-20260810 `
  -BaseUrl http://127.0.0.1:9080 -AgentId 33 `
  -ModelLabel 'Qwen3.6@192.168.20.66:8080' `
  -BootstrapReceipt .local-dev/bank-nl2sql/official-v3/bootstrap-receipt.json
```

smoke 固定为 5 道分层 train 题，并强制 `caseAccuracy = 1.0`。未全绿时命令以非零退出，
不会允许随后运行 train、dev 或 test。

### 2. Train 与 Dev

```powershell
powershell -ExecutionPolicy Bypass -File evaluation/bank_nl2sql/Run-OfficialBankEvaluation.ps1 `
  -Mode train -RunId qwen66-20260810 `
  -BaseUrl http://127.0.0.1:9080 -AgentId 33 `
  -ModelLabel 'Qwen3.6@192.168.20.66:8080' `
  -BootstrapReceipt .local-dev/bank-nl2sql/official-v3/bootstrap-receipt.json

powershell -ExecutionPolicy Bypass -File evaluation/bank_nl2sql/Run-OfficialBankEvaluation.ps1 `
  -Mode dev -RunId qwen66-20260810 `
  -BaseUrl http://127.0.0.1:9080 -AgentId 33 `
  -ModelLabel 'Qwen3.6@192.168.20.66:8080' `
  -BootstrapReceipt .local-dev/bank-nl2sql/official-v3/bootstrap-receipt.json
```

每题都有独立会话；只有 `casePass=true` 的临时会话会被删除，所有失败会话保留。中断后重跑
相同命令会从同一 `run-id` 的兼容 checkpoint 续跑。正式运行固定为串行，不能通过命令改成
并发，因此模型服务负载不会改变可比较成绩。

### 3. 冻结 Test

Test 只能在 smoke 全绿、train 和 dev 已完成后显式运行；它必须写入本地运行登记：

```powershell
powershell -ExecutionPolicy Bypass -File evaluation/bank_nl2sql/Run-OfficialBankEvaluation.ps1 `
  -Mode test -RunId qwen66-20260810 `
  -BaseUrl http://127.0.0.1:9080 -AgentId 33 `
  -ModelLabel 'Qwen3.6@192.168.20.66:8080' `
  -BootstrapReceipt .local-dev/bank-nl2sql/official-v3/bootstrap-receipt.json `
  -AcknowledgeFinalTest `
  -RunRegistry .local-dev/bank-nl2sql/official-v3/final-test-runs.json
```

### 4. 唯一报告格式

输出固定在 `.local-dev/bank-nl2sql/official-v3/<RunId>/`，每个模式同时生成 JSON 与 Markdown。
报告必须记录代码提交、数据版本与哈希、数据库包哈希、Agent 配置指纹、模型标签、端点指纹、
串行策略和完整题目清单。正式计分只包含 `caseAccuracy` 和与其等价的结果事实准确率；最终回答
处理状态只能进入非计分运行时诊断，不得输出旧的最终回答准确率。页面采集、离线实验和消融
工具只能用于排障，不能产出或替代正式成绩。
仓库内既有 `reports/` 快照仅作历史排障留档，标准运行器不会读取或续跑其中任何文件。

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

页面采集器只保留为 UI/渲染排障工具，不能计算或展示任何效果分数。需要排查页面独有问题时，
`run_ui_chat_capture.mjs` 连接一个已登录、已打开银行问数页面的 Chromium 调试会话，在
`#chatInput` 输入 dev 问题，并从页面渲染的表格读取表头、所有分页和终态；它不会自行调用
`/api/chat/query/*`。

页面采集运行器只允许 `train` 或 `dev`，因此不会因误操作读取冻结 test。需要验证单一已知开发题
时可传入 `--record-id <ID>`；先启动专用浏览器并在其中登录一次：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/Start-Ui-Evaluation-Browser.ps1 -AgentId 33
```

随后运行采集并人工检查渲染状态；若需要可比较的结果，重新从“官方运行时评测（唯一入口）”的
固定 smoke 开始：

```powershell
node evaluation/bank_nl2sql/run_ui_chat_capture.mjs `
  --browser-debug-url http://127.0.0.1:9222 `
  --dataset evaluation/bank_nl2sql `
  --split dev `
  --page-url http://127.0.0.1:9000/webapp/chat?agentId=33 `
  --agent-id 33 `
  --output .local-dev/bank-nl2sql/ui-dev-capture.json
```

页面表格使用 `data-testid="ui-chat-result-table"` 和列语义标识供采集器定位；这些属性不改变用户界面或查询行为。
