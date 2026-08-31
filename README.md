# SuperSonic

SuperSonic 是一个融合对话式商业智能与无头商业智能的新一代智能数据分析平台。平台以统一语义层为基础，让业务用户能够使用自然语言查询数据，也让分析工程师能够集中定义和治理指标、维度、实体、标签及其关系。

通过语义模型增强自然语言转 SQL，SuperSonic 可以减少模型幻觉和复杂 SQL 生成负担；同一套语义模型也可通过开放接口服务于报表、应用和智能助手。

<img src="https://github.com/supersonicbi/supersonic-website/blob/main/static/img/supersonic_ideas.png" alt="SuperSonic 设计理念" width="75%" />

## 核心能力

- 对话式问数：支持自然语言查询、结果可视化、多轮对话、输入联想和后续问题推荐。
- 统一语义层：集中管理指标、维度、实体、标签、业务术语及关联关系。
- 语义增强解析：通过知识检索、模式映射、语义解析与修正生成可靠查询。
- 开放查询接口：使用统一数据语义为外部系统提供查询能力。
- 细粒度权限：支持数据集级、列级和行级访问控制。
- 可插拔扩展：基于 Java SPI 扩展解析器、插件及其他核心能力。
- 多数据源支持：支持 MySQL、PostgreSQL、ClickHouse、StarRocks、Presto、Trino、DuckDB 等数据库。

<img src="https://github.com/supersonicbi/supersonic-website/blob/main/static/img/supersonic_demo.gif" alt="SuperSonic 使用演示" width="100%" />

## 工作原理

SuperSonic 将自然语言问数与统一语义模型结合，主要处理流程如下：

1. 模型知识库定期从语义模型中提取结构、业务术语和字段值，构建词典与索引。
2. 模式映射器识别问题中的指标、维度、实体和值，并匹配相关语义信息。
3. 语义解析器理解查询意图，生成语义查询语句。
4. 语义修正器校验并修正不合法或不完整的语义信息。
5. 语义翻译器将语义查询转换为可在物理数据源执行的 SQL。
6. 查询结果通过对话界面或开放接口返回，并以合适的图表展示。

<img src="https://github.com/supersonicbi/supersonic-website/blob/main/static/img/supersonic_components.png" alt="SuperSonic 组件架构" width="65%" />

## 快速开始

### 使用发行包

1. 从 [发行页面](https://github.com/tencentmusic/supersonic/releases) 下载预构建发行包。
2. 解压后启动独立服务：

```bash
./assembly/bin/supersonic-daemon.sh start
```

3. 浏览器访问 [http://localhost:9080](http://localhost:9080)。

### 使用容器部署

请先安装 Docker 与 Docker Compose，然后执行：

```bash
wget https://raw.githubusercontent.com/tencentmusic/supersonic/master/docker/docker-compose.yml
docker compose up -d
```

服务启动后访问 [http://localhost:9080](http://localhost:9080)。

### 从源码一键启动

开发环境要求：

- Java 21
- Maven
- Node.js 16 或更高版本
- pnpm 9.12.3 或更高版本

在 Windows 项目根目录执行：

```powershell
.\start-all.bat
```

脚本会安装前端依赖，并分别启动后端与前端开发服务器。默认使用本地文件型 H2 数据库：

- 前端开发服务：[http://localhost:9000](http://localhost:9000)
- 后端服务：[http://localhost:9080](http://localhost:9080)

使用 PostgreSQL 作为源码开发数据库：

```powershell
.\start-all.bat postgres
```

启动完整的 Docker Compose 服务：

```powershell
.\start-all.bat docker
```

停止本地服务或容器服务：

```powershell
.\start-all.bat stop
.\start-all.bat docker stop
```

Linux 与 macOS 可使用同目录下的 `start-all.sh`。

## 构建与测试

### 后端

```bash
# 构建发行包并跳过测试
mvn clean package -DskipTests -Dspotless.skip=true

# 运行全部后端测试
mvn test

# 运行指定测试类
mvn test -Dtest=测试类名
```

### 前端

```bash
cd webapp
pnpm install
pnpm dev
pnpm test
pnpm build
```

### 构建完整发行版

```bash
./assembly/bin/supersonic-build.sh standalone
```

## 项目结构

```text
supersonic/
├── auth/           身份认证与访问控制
├── chat/           智能问答与自然语言解析
├── common/         公共组件与基础能力
├── headless/       语义层与开放查询接口
├── launchers/      独立、问答和语义层启动器
├── webapp/         前端应用
├── evaluation/     自然语言转 SQL 评测工具
├── assembly/       构建、打包与运行脚本
└── docker/         容器部署配置
```

## 技术栈

- 后端：Java 21、Spring Boot、MyBatis-Plus、LangChain4j、Apache Calcite、JSqlParser
- 前端：React、UmiJS、Ant Design、ECharts、AntV
- 数据库：MySQL、PostgreSQL、H2、ClickHouse、StarRocks、Presto、Trino、DuckDB

