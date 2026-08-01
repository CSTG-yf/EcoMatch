# BE-11 外部系统标准化集成

## 当前状态

通用集成底座已完成（2026-08-01），目标系统适配和正式环境联调待外部接口文档、网络白名单及联调账号到位后执行。当前状态不能等同于 BE-11 最终验收完成。

## 已交付

- 版本化请求和响应协议、稳定错误码、链路 ID 与机构边界。
- HMAC-SHA256 请求签名，签名覆盖方法、路径、系统、时间戳、随机数、幂等键和请求体摘要。
- 入站回调与出站网关，支持操作白名单、重放防护、令牌桶限流、原子幂等和超时控制。
- Java HTTP 传输层禁止自动重定向，限制连接时间、请求时间和响应体大小。
- 外部调用开始、成功、失败审计；审计事件不记录业务载荷、签名或密钥。
- 配置开关默认关闭；HTTPS 为默认要求，仅显式配置时允许本机 HTTP 联调。
- 数据中台和营销中台两个模拟系统的双向契约测试。

实现目录：`headless/server/src/main/java/com/tencent/supersonic/headless/server/integration/`

无密钥配置模板：`evaluation/be11_integration.template.yaml`

## 验证结果

执行命令：

```bash
mvn -pl headless/server -am "-Dtest=IntegrationSecurityTest,ExternalIntegrationGatewayContractTest,InboundIntegrationServiceContractTest,JdkIntegrationTransportTest,IntegrationConfigurationTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dspotless.skip=true" test
```

结果：5 个测试类、10 个用例通过，失败 0、错误 0、跳过 0。

## 剩余任务与依赖

| 任务 | 上游依赖 | 输出 | 下游依赖 |
| --- | --- | --- | --- |
| 冻结目标系统契约 | 目标系统 OpenAPI、认证方式、字段字典、错误码 | 契约映射表和操作清单 | 目标适配器开发 |
| 实现目标适配器 | 已冻结契约、网络地址、测试账号 | 数据/风控/营销/报表适配器 | 沙箱联调 |
| 沙箱双向联调 | 网络白名单、联调密钥、样例数据 | 请求响应证据和失败重试报告 | 安全回归 |
| 安全与审计回归 | QA-02 清单、正式审计规则 | 签名、重放、幂等、限流、脱敏证据 | BE-11 最终验收 |
| 正式环境验收 | 前述任务全部通过 | 至少两个真实外部系统双向联调报告 | OPS-01、最终集成验收 |

## 接入约束

- 密钥只通过环境变量或密钥管理系统注入，不写入仓库和验收报告。
- 目标系统必须使用 HTTPS；证书和主机名校验不得关闭。
- 每个业务操作使用唯一幂等键，重试沿用原键和原始业务载荷。
- 新增业务处理器必须校验机构、对象和操作边界，并纳入 QA-02 回归清单。
- 正式环境报告只能记录摘要、状态和链路 ID，不记录金融数据、令牌、签名或密钥。
