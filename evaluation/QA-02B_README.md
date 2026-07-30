# QA-02B 审计与告警接口测试

QA-02B 聚合审计完整性、哈希链、异常规则、告警去重、机构隔离和处置权限测试，生成可归档的后端安全门禁报告。

```powershell
python evaluation/run_qa02b.py `
  --output .local-dev/bank-evaluation/qa02b-report.json
```

执行器读取 `evaluation/qa02b_manifest.json`，复用 QA-02A 的 Maven 和 Surefire 门禁协议。Maven 失败、声明测试类缺失、任一用例失败或跳过、任一控制项未通过时返回退出码 `1`。

清单当前覆盖：

1. 审计完整性和敏感信息清洗；
2. 哈希链完整性及篡改检测；
3. 异常规则校验和触发；
4. 告警去重和证据累计；
5. 机构隔离和管理权限；
6. 告警状态流转和处置记录。

执行器错误或清单无效返回退出码 `2`。报告不保存原始 SQL、问题、凭据、金融标识或测试输出。
