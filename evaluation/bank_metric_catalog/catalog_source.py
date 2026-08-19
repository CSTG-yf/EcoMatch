"""Curated candidate metadata used to build the 360-metric release.

This module contains no fact values.  Names and definitions are original,
short paraphrases derived from the official-source topics recorded below.
Every record remains CANDIDATE until a human verifies its precise locator.
"""

from __future__ import annotations

import re
from typing import Any


VERSION = "0.1.0-candidate"
RETRIEVED_AT = "2026-08-18"

SCENE_QUOTAS = {"OPERATIONS": 150, "RISK": 120, "CUSTOMER_MARKETING": 90}
DOMAIN_QUOTAS = {
    "assets_liabilities_deposits_loans": 45,
    "income_profit_cost_efficiency": 35,
    "payments_accounts_transactions": 35,
    "product_channel_operations": 35,
    "credit_asset_quality": 35,
    "capital_solvency": 25,
    "liquidity_alm": 25,
    "market_interest_fx_risk": 20,
    "concentration_operational_compliance": 15,
    "customer_base_structure": 20,
    "acquisition_activation_retention": 20,
    "penetration_cross_sell_aum": 20,
    "digital_channel_service_usage": 20,
    "complaint_satisfaction_service_quality": 10,
}


SOURCES: list[dict[str, Any]] = [
    {
        "sourceId": "PBC-JRT-0134-2016",
        "authority": "中国人民银行",
        "title": "JR/T 0134-2016 存款统计分类及编码",
        "identifier": "JR/T 0134-2016",
        "url": "https://cfstc.pbc.gov.cn/bzgk/detail/?bzId=1644",
        "sourceType": "OFFICIAL_STANDARD",
        "role": "存款分类、统计对象和编码语义参考",
        "retrievedAt": RETRIEVED_AT,
        "reusePolicy": "REFERENCE_ONLY",
        "notes": "仅记录来源与原创转述，不再分发标准正文或原始数据。",
    },
    {
        "sourceId": "SAMR-JRT-0135-2016",
        "authority": "中国人民银行/全国金融标准化技术委员会",
        "title": "JR/T 0135-2016 贷款统计分类及编码",
        "identifier": "JR/T 0135-2016",
        "url": "https://std.samr.gov.cn/hb/search/stdHBDetailed?id=8B1827F20B62BB19E05397BE0A0AB44A",
        "sourceType": "OFFICIAL_STANDARD",
        "role": "贷款分类、统计对象和编码语义参考",
        "retrievedAt": RETRIEVED_AT,
        "reusePolicy": "REFERENCE_ONLY",
        "notes": "仅记录来源与原创转述，不再分发标准正文或原始数据。",
    },
    {
        "sourceId": "PBC-JRT-0076-SERIES",
        "authority": "中国人民银行/全国金融标准化技术委员会",
        "title": "JR/T 0076 支付业务统计指标系列",
        "identifier": "JR/T 0076.1-2013 至 JR/T 0076.7-2013",
        "url": "https://cfstc.pbc.gov.cn/bzgk/detail/?bzId=1601&id=0",
        "sourceType": "OFFICIAL_STANDARD",
        "role": "支付环境、服务组织、结算账户、支付工具和支付系统指标参考",
        "retrievedAt": RETRIEVED_AT,
        "reusePolicy": "REFERENCE_ONLY",
        "notes": "链接为系列中的官方标准入口；具体分册定位须在人工审查阶段复核。",
    },
    {
        "sourceId": "MOF-BANK-PERFORMANCE-2020",
        "authority": "中华人民共和国财政部",
        "title": "商业银行绩效评价指标体系",
        "identifier": "财金〔2020〕124号附件2",
        "url": "https://jrs.mof.gov.cn/gongzuotongzhi/202101/P020210104389633044160.pdf",
        "sourceType": "OFFICIAL_REGULATION",
        "role": "发展质量、风险防控和经营效益指标及公式参考",
        "retrievedAt": RETRIEVED_AT,
        "reusePolicy": "REFERENCE_ONLY",
        "notes": "只保留指标元数据的原创转述，不复制附件正文。",
    },
    {
        "sourceId": "NFRA-MAJOR-INDICATORS-2025",
        "authority": "国家金融监督管理总局",
        "title": "2025年商业银行主要监管指标情况表",
        "identifier": "NFRA docId 1208453",
        "url": "https://www.nfra.gov.cn/cn/view/pages/ItemDetail.html?docId=1208453&itemId=954",
        "sourceType": "OFFICIAL_REGULATION",
        "role": "资产质量、资本、流动性和盈利监管指标参考",
        "retrievedAt": RETRIEVED_AT,
        "reusePolicy": "REFERENCE_ONLY",
        "notes": "不打包监管附件数据，只记录来源与候选定义。",
    },
    {
        "sourceId": "NFRA-LIQUIDITY-RISK-MEASURES",
        "authority": "国家金融监督管理总局",
        "title": "商业银行流动性风险管理办法",
        "identifier": "NFRA docId 180252",
        "url": "https://www.nfra.gov.cn/cn/view/pages/ItemDetail.html?docId=180252",
        "sourceType": "OFFICIAL_REGULATION",
        "role": "流动性覆盖率、净稳定资金比例和流动性监测指标参考",
        "retrievedAt": RETRIEVED_AT,
        "reusePolicy": "REFERENCE_ONLY",
        "notes": "监管阈值和精确条款须由人工逐项复核后方可晋升。",
    },
    {
        "sourceId": "NFRA-CAPITAL-MEASURES-2023",
        "authority": "国家金融监督管理总局",
        "title": "商业银行资本管理办法",
        "identifier": "NFRA docId 1134197",
        "url": "https://www.nfra.gov.cn/cn/view/pages/rulesDetail.html?docId=1134197&itemId=4214",
        "sourceType": "OFFICIAL_REGULATION",
        "role": "资本充足、风险加权资产和杠杆率指标参考",
        "retrievedAt": RETRIEVED_AT,
        "reusePolicy": "REFERENCE_ONLY",
        "notes": "不复制监管附件，候选公式需在人工审查阶段与现行口径核对。",
    },
    {
        "sourceId": "SAMR-JRT-0169-2018",
        "authority": "中国人民银行/全国金融标准化技术委员会",
        "title": "JR/T 0169-2018 金融消费者投诉统计分类及编码 银行业金融机构",
        "identifier": "JR/T 0169-2018",
        "url": "https://hbba.sacinfo.org.cn/stdDetail/65423dd9f629e818e4192fea8d4143eb",
        "sourceType": "OFFICIAL_STANDARD",
        "role": "投诉渠道、业务类别、原因及投诉分析指标参考",
        "retrievedAt": RETRIEVED_AT,
        "reusePolicy": "REFERENCE_ONLY",
        "notes": "仅使用分类主题形成原创指标定义，不再分发标准正文。",
    },
    {
        "sourceId": "NFRA-COMPLAINT-MANAGEMENT-2020",
        "authority": "国家金融监督管理总局",
        "title": "银行业保险业消费投诉处理管理办法",
        "identifier": "原银保监会令2020年第3号",
        "url": "https://www.nfra.gov.cn/cn/view/pages/rulesDetail.html?docId=886173",
        "sourceType": "OFFICIAL_REGULATION",
        "role": "投诉受理、处理、统计分析和整改管理指标参考",
        "retrievedAt": RETRIEVED_AT,
        "reusePolicy": "REFERENCE_ONLY",
        "notes": "处理时限和责任要求需在正式发布前复核现行规则。",
    },
    {
        "sourceId": "SAMR-JRT-0297-2024",
        "authority": "中国人民银行/全国金融标准化技术委员会",
        "title": "JR/T 0297-2024 银行产品服务内部基本过程与活动管理指南",
        "identifier": "JR/T 0297-2024",
        "url": "https://std.samr.gov.cn/hb/search/stdHBDetailed?id=1F8EACB6AE235B20E06397BE0A0A3B1B",
        "sourceType": "OFFICIAL_STANDARD",
        "role": "产品创设、创新、运营和退出生命周期指标框架参考",
        "retrievedAt": RETRIEVED_AT,
        "reusePolicy": "REFERENCE_ONLY",
        "notes": "该标准提供过程框架而非可直接复制的完整数值指标包。",
    },
    {
        "sourceId": "CCB-ANNUAL-REPORT-2024",
        "authority": "中国建设银行股份有限公司",
        "title": "中国建设银行股份有限公司2024年度报告",
        "identifier": "2024年度报告",
        "url": "https://image2.ccb.com/chn/attachDir/2025/04/2025040308532585226.pdf",
        "sourceType": "OFFICIAL_DISCLOSURE",
        "role": "客户规模、手机银行、线上交易和渠道运营披露口径参考",
        "retrievedAt": RETRIEVED_AT,
        "reusePolicy": "REFERENCE_ONLY",
        "notes": "只提炼公开披露口径；不复制报告表格，也不使用其数值作为事实数据。",
    },
    {
        "sourceId": "ICBC-ANNUAL-REPORT-2024",
        "authority": "中国工商银行股份有限公司",
        "title": "中国工商银行股份有限公司2024年度报告（A股）",
        "identifier": "2024年度报告",
        "url": "https://www.icbc-ltd.com/page/1079179569833807872.html",
        "sourceType": "OFFICIAL_DISCLOSURE",
        "role": "客户、移动金融、交易、养老金和渠道运营披露口径参考",
        "retrievedAt": RETRIEVED_AT,
        "reusePolicy": "REFERENCE_ONLY",
        "notes": "只提炼公开披露口径；不复制报告表格，也不使用其数值作为事实数据。",
    },
]


def _lines(value: str) -> tuple[str, ...]:
    return tuple(line.strip() for line in value.strip().splitlines() if line.strip())


GROUPS: tuple[dict[str, Any], ...] = (
    {
        "scene": "OPERATIONS",
        "domain": "assets_liabilities_deposits_loans",
        "purpose": "观察银行资产负债结构、资金来源和信贷投向",
        "sourceIds": ["PBC-JRT-0134-2016", "SAMR-JRT-0135-2016", "NFRA-MAJOR-INDICATORS-2025"],
        "locator": "存贷款分类与主要监管指标主题，具体条目待人工核验",
        "dimensions": ["organization", "date", "customer_type", "product_type"],
        "names": _lines("""
            各项存款余额
            对公存款余额
            个人存款余额
            活期存款余额
            定期存款余额
            单位活期存款余额
            单位定期存款余额
            个人活期存款余额
            个人定期存款余额
            财政性存款余额
            同业存款余额
            保证金存款余额
            通知存款余额
            结构性存款余额
            大额存单余额
            各项贷款余额
            对公贷款余额
            个人贷款余额
            短期贷款余额
            中长期贷款余额
            流动资金贷款余额
            固定资产贷款余额
            个人住房贷款余额
            个人消费贷款余额
            个人经营贷款余额
            普惠型小微企业贷款余额
            涉农贷款余额
            绿色贷款余额
            制造业贷款余额
            科技型企业贷款余额
            信用贷款余额
            保证贷款余额
            抵押贷款余额
            质押贷款余额
            票据融资余额
            贴现余额
            贷款承诺余额
            债券投资余额
            同业资产余额
            金融投资余额
            生息资产余额
            计息负债余额
            总资产
            总负债
            存贷比
        """),
    },
    {
        "scene": "OPERATIONS",
        "domain": "income_profit_cost_efficiency",
        "purpose": "分析收入结构、成本投入、盈利能力和人均产出",
        "sourceIds": ["MOF-BANK-PERFORMANCE-2020", "NFRA-MAJOR-INDICATORS-2025"],
        "locator": "发展质量、经营效益与财务指标主题，具体条目待人工核验",
        "dimensions": ["organization", "date", "business_line"],
        "names": _lines("""
            营业收入
            利息收入
            利息支出
            利息净收入
            手续费及佣金收入
            手续费及佣金支出
            手续费及佣金净收入
            投资收益
            公允价值变动收益
            汇兑收益
            中间业务收入
            营业支出
            业务及管理费
            人员费用
            折旧及摊销费用
            信用减值损失
            其他资产减值损失
            营业利润
            利润总额
            净利润
            归母净利润
            税前利润
            所得税费用
            净息差
            净利差
            成本收入比
            资产利润率
            净资产收益率
            人均净利润
            人工成本利润率
            经济增加值
            每股收益
            总资产周转率
            手续费及佣金净收入占比
            利息净收入占比
        """),
    },
    {
        "scene": "OPERATIONS",
        "domain": "payments_accounts_transactions",
        "purpose": "衡量账户基础、支付规模、渠道交易和支付系统质量",
        "sourceIds": ["PBC-JRT-0076-SERIES"],
        "locator": "JR/T 0076结算账户、支付工具与支付系统主题，具体分册条目待人工核验",
        "dimensions": ["organization", "date", "account_type", "payment_channel"],
        "names": _lines("""
            人民币结算账户总数
            单位银行结算账户数
            个人银行结算账户数
            基本存款账户数
            一般存款账户数
            专用存款账户数
            临时存款账户数
            新开结算账户数
            销户结算账户数
            非现金支付业务笔数
            非现金支付业务金额
            银行卡交易笔数
            银行卡交易金额
            借记卡交易笔数
            借记卡交易金额
            信用卡交易笔数
            信用卡交易金额
            移动支付交易笔数
            移动支付交易金额
            网上支付交易笔数
            网上支付交易金额
            ATM交易笔数
            ATM交易金额
            POS交易笔数
            POS交易金额
            柜面支付交易笔数
            柜面支付交易金额
            跨行支付交易笔数
            跨行支付交易金额
            实时支付交易笔数
            实时支付交易金额
            支付业务成功率
            支付业务平均处理时长
            支付系统可用率
            支付业务差错率
        """),
    },
    {
        "scene": "OPERATIONS",
        "domain": "product_channel_operations",
        "purpose": "衡量产品生命周期、渠道资源、作业效率和运营质量",
        "sourceIds": ["SAMR-JRT-0297-2024", "CCB-ANNUAL-REPORT-2024", "ICBC-ANNUAL-REPORT-2024"],
        "locator": "产品生命周期与渠道运营披露主题，具体章节待人工核验",
        "dimensions": ["organization", "date", "product_type", "channel"],
        "names": _lines("""
            在售存款产品数
            在售贷款产品数
            在售理财产品数
            新上线产品数
            退出产品数
            产品审批通过数
            产品审批平均时长
            产品存续规模
            产品销售额
            产品赎回额
            产品到期兑付额
            产品销售完成率
            网点数量
            员工人数
            社区支行数量
            自助银行数量
            ATM设备数量
            智能柜员机数量
            POS商户终端数量
            客户经理人数
            人均管户数
            人均存款增量
            人均贷款投放
            柜面业务笔数
            线上业务笔数
            业务线上化率
            网点日均客流量
            网点日均交易笔数
            单笔业务平均处理时长
            业务自动化处理率
            运营差错笔数
            运营差错率
            集中运营业务量
            集中运营覆盖率
            单位运营成本
        """),
    },
    {
        "scene": "RISK",
        "domain": "credit_asset_quality",
        "purpose": "监测贷款五级分类、逾期、不良处置、拨备和授信风险",
        "sourceIds": ["SAMR-JRT-0135-2016", "NFRA-MAJOR-INDICATORS-2025", "MOF-BANK-PERFORMANCE-2020"],
        "locator": "贷款分类、资产质量和风险防控主题，具体条目待人工核验",
        "dimensions": ["organization", "date", "loan_category", "customer_type", "industry"],
        "names": _lines("""
            正常类贷款余额
            关注类贷款余额
            次级类贷款余额
            可疑类贷款余额
            损失类贷款余额
            不良贷款余额
            不良贷款率
            关注类贷款占比
            逾期贷款余额
            逾期贷款率
            逾期90天以上贷款余额
            逾期90天以上贷款占不良贷款比例
            新生成不良贷款额
            不良贷款迁徙率
            不良贷款处置额
            不良贷款核销额
            不良贷款现金清收额
            贷款损失准备余额
            拨备覆盖率
            贷款拨备率
            重组贷款余额
            重组贷款占比
            信用风险加权资产
            预期信用损失
            违约客户数
            违约贷款余额
            违约概率
            违约损失率
            风险暴露金额
            大额风险暴露余额
            问题授信余额
            授信审批否决率
            贷后检查完成率
            风险预警客户数
            风险预警处置率
        """),
    },
    {
        "scene": "RISK",
        "domain": "capital_solvency",
        "purpose": "衡量资本构成、资本充足水平、杠杆约束和经济资本使用",
        "sourceIds": ["NFRA-CAPITAL-MEASURES-2023", "NFRA-MAJOR-INDICATORS-2025", "MOF-BANK-PERFORMANCE-2020"],
        "locator": "资本定义、资本充足率和杠杆率主题，具体条款待人工核验",
        "dimensions": ["organization", "date", "capital_tier", "risk_type"],
        "names": _lines("""
            核心一级资本净额
            其他一级资本净额
            一级资本净额
            二级资本净额
            总资本净额
            核心一级资本充足率
            一级资本充足率
            资本充足率
            风险加权资产
            市场风险加权资产
            操作风险加权资产
            资本底线附加风险加权资产
            杠杆率
            杠杆率暴露总额
            资本缓冲要求
            储备资本要求
            逆周期资本要求
            系统重要性银行附加资本要求
            资本充足率监管余量
            核心一级资本充足率监管余量
            一级资本充足率监管余量
            内部资本充足评估值
            经济资本占用额
            经济资本回报率
            资本补充工具余额
        """),
    },
    {
        "scene": "RISK",
        "domain": "liquidity_alm",
        "purpose": "监测短期流动性、稳定资金、期限错配和融资集中风险",
        "sourceIds": ["NFRA-LIQUIDITY-RISK-MEASURES", "NFRA-MAJOR-INDICATORS-2025"],
        "locator": "流动性监管指标与监测工具主题，具体条款待人工核验",
        "dimensions": ["organization", "date", "maturity_bucket", "currency", "funding_source"],
        "names": _lines("""
            流动性资产余额
            流动性负债余额
            流动性比例
            优质流动性资产
            未来30日现金净流出量
            流动性覆盖率
            可用稳定资金
            所需稳定资金
            净稳定资金比例
            流动性匹配率
            高流动性资产余额
            高流动性资产充足率
            存款稳定率
            核心负债依存度
            流动性缺口
            30日流动性缺口率
            90日流动性缺口率
            一年内到期资产
            一年内到期负债
            同业融入余额
            同业融出余额
            融资集中度
            前十大资金来源占比
            未质押优质流动性资产
            流动性应急融资额度
        """),
    },
    {
        "scene": "RISK",
        "domain": "market_interest_fx_risk",
        "purpose": "衡量利率、汇率、交易账簿和限额使用风险",
        "sourceIds": ["NFRA-CAPITAL-MEASURES-2023", "NFRA-LIQUIDITY-RISK-MEASURES"],
        "locator": "市场风险、银行账簿利率风险与重要币种流动性主题，具体条款待人工核验",
        "dimensions": ["organization", "date", "currency", "book", "maturity_bucket"],
        "names": _lines("""
            银行账簿利率风险暴露
            交易账簿市场风险暴露
            利率敏感性缺口
            累计利率敏感性缺口
            净利息收入利率敏感度
            经济价值利率敏感度
            外汇敞口头寸
            累计外汇敞口头寸比例
            交易账簿利率风险资本
            汇率风险资本
            股票风险资本
            商品风险资本
            市场风险价值
            压力市场风险价值
            债券久期
            资产久期
            负债久期
            久期缺口
            利率风险限额使用率
            外汇风险限额使用率
        """),
    },
    {
        "scene": "RISK",
        "domain": "concentration_operational_compliance",
        "purpose": "监测授信和资金集中、操作损失、违规处罚及整改情况",
        "sourceIds": ["NFRA-CAPITAL-MEASURES-2023", "NFRA-MAJOR-INDICATORS-2025"],
        "locator": "大额风险暴露、操作风险和合规管理主题，具体条款待人工核验",
        "dimensions": ["organization", "date", "customer_group", "industry", "region", "event_type"],
        "names": _lines("""
            单一客户贷款集中度
            单一集团客户授信集中度
            最大十家客户贷款集中度
            最大十户存款集中度
            行业贷款集中度
            地区贷款集中度
            产品贷款集中度
            关联方授信余额
            关联方授信比例
            重大操作风险事件数
            操作风险损失金额
            员工违规事件数
            监管处罚次数
            监管处罚金额
            合规检查问题整改率
        """),
    },
    {
        "scene": "CUSTOMER_MARKETING",
        "domain": "customer_base_structure",
        "purpose": "刻画零售、对公和重点客群规模及客户资产基础",
        "sourceIds": ["CCB-ANNUAL-REPORT-2024", "ICBC-ANNUAL-REPORT-2024"],
        "locator": "客户基础、重点客群和客户资产披露主题，具体章节待人工核验",
        "dimensions": ["organization", "date", "customer_segment"],
        "names": _lines("""
            个人客户总数
            对公客户总数
            有效个人客户数
            有效对公客户数
            高净值客户数
            私人银行客户数
            普惠小微客户数
            涉农客户数
            代发工资客户数
            信用卡客户数
            借记卡客户数
            贷款客户数
            存款客户数
            理财客户数
            手机银行注册客户数
            网上银行注册客户数
            企业网银客户数
            老年客户数
            新市民客户数
            客户资产管理规模
        """),
    },
    {
        "scene": "CUSTOMER_MARKETING",
        "domain": "acquisition_activation_retention",
        "purpose": "衡量获客、触达、响应、激活、留存、流失和唤醒效果",
        "sourceIds": ["CCB-ANNUAL-REPORT-2024", "ICBC-ANNUAL-REPORT-2024", "SAMR-JRT-0297-2024"],
        "locator": "客户经营和产品运营披露主题，具体章节待人工核验",
        "dimensions": ["organization", "date", "customer_segment", "campaign", "channel"],
        "names": _lines("""
            新增个人客户数
            新增对公客户数
            新增高净值客户数
            新增普惠小微客户数
            获客成本
            营销触达客户数
            营销响应客户数
            营销响应率
            营销转化客户数
            营销转化率
            客户激活数
            客户激活率
            月活跃客户数
            客户活跃率
            客户留存数
            客户留存率
            客户流失数
            客户流失率
            沉睡客户唤醒数
            沉睡客户唤醒率
        """),
    },
    {
        "scene": "CUSTOMER_MARKETING",
        "domain": "penetration_cross_sell_aum",
        "purpose": "衡量产品持有深度、交叉销售、产品渗透和客户资产增长",
        "sourceIds": ["CCB-ANNUAL-REPORT-2024", "ICBC-ANNUAL-REPORT-2024", "SAMR-JRT-0297-2024"],
        "locator": "产品持有、财富管理和销售运营披露主题，具体章节待人工核验",
        "dimensions": ["organization", "date", "customer_segment", "product_type"],
        "names": _lines("""
            持有单一产品客户数
            持有两类产品客户数
            持有三类及以上产品客户数
            户均持有产品数
            交叉销售客户数
            交叉销售率
            存款产品渗透率
            贷款产品渗透率
            理财产品渗透率
            信用卡产品渗透率
            保险代销产品渗透率
            基金代销产品渗透率
            个人客户AUM
            高净值客户AUM
            私人银行客户AUM
            户均AUM
            AUM净增额
            AUM增长率
            理财销售客户数
            理财复购率
        """),
    },
    {
        "scene": "CUSTOMER_MARKETING",
        "domain": "digital_channel_service_usage",
        "purpose": "衡量手机银行、网银和数字渠道的用户、交易、销售与服务质量",
        "sourceIds": ["CCB-ANNUAL-REPORT-2024", "ICBC-ANNUAL-REPORT-2024", "PBC-JRT-0076-SERIES"],
        "locator": "线上渠道、移动金融与支付交易披露主题，具体章节待人工核验",
        "dimensions": ["organization", "date", "channel", "customer_segment", "product_type"],
        "names": _lines("""
            手机银行月活客户数
            手机银行日活客户数
            手机银行金融交易客户数
            网上银行活跃客户数
            企业手机银行用户数
            数字渠道登录次数
            数字渠道交易笔数
            数字渠道交易金额
            手机银行交易笔数
            手机银行交易金额
            线上理财销售额
            线上贷款申请数
            线上贷款放款额
            线上开户数
            线上客户服务量
            线上渠道交易占比
            手机银行月活率
            数字渠道服务可用率
            数字渠道平均响应时长
            数字渠道故障时长
        """),
    },
    {
        "scene": "CUSTOMER_MARKETING",
        "domain": "complaint_satisfaction_service_quality",
        "purpose": "衡量投诉受理和处理、满意度、客服接通及一次解决效果",
        "sourceIds": ["SAMR-JRT-0169-2018", "NFRA-COMPLAINT-MANAGEMENT-2020"],
        "locator": "投诉分类、受理、办结、分析和服务质量主题，具体条目待人工核验",
        "dimensions": ["organization", "date", "complaint_channel", "business_category", "complaint_reason"],
        "names": _lines("""
            消费投诉受理量
            消费投诉办结量
            投诉办结率
            投诉按时办结量
            投诉按时办结率
            重复投诉量
            重大投诉量
            客户满意度
            客服人工接通率
            一次性问题解决率
        """),
    },
)


DERIVED_FORMULAS: dict[str, dict[str, Any]] = {
    "存贷比": {"operation": "DIVIDE_PERCENT", "operands": ["各项贷款余额", "各项存款余额"]},
    "成本收入比": {"operation": "DIVIDE_PERCENT", "operands": ["业务及管理费", "营业收入"]},
    "人工成本利润率": {"operation": "DIVIDE_PERCENT", "operands": ["利润总额", "人员费用"]},
    "手续费及佣金净收入占比": {"operation": "DIVIDE_PERCENT", "operands": ["手续费及佣金净收入", "营业收入"]},
    "利息净收入占比": {"operation": "DIVIDE_PERCENT", "operands": ["利息净收入", "营业收入"]},
    "不良贷款率": {"operation": "DIVIDE_PERCENT", "operands": ["不良贷款余额", "各项贷款余额"]},
    "关注类贷款占比": {"operation": "DIVIDE_PERCENT", "operands": ["关注类贷款余额", "各项贷款余额"]},
    "逾期贷款率": {"operation": "DIVIDE_PERCENT", "operands": ["逾期贷款余额", "各项贷款余额"]},
    "逾期90天以上贷款占不良贷款比例": {"operation": "DIVIDE_PERCENT", "operands": ["逾期90天以上贷款余额", "不良贷款余额"]},
    "拨备覆盖率": {"operation": "DIVIDE_PERCENT", "operands": ["贷款损失准备余额", "不良贷款余额"]},
    "贷款拨备率": {"operation": "DIVIDE_PERCENT", "operands": ["贷款损失准备余额", "各项贷款余额"]},
    "重组贷款占比": {"operation": "DIVIDE_PERCENT", "operands": ["重组贷款余额", "各项贷款余额"]},
    "核心一级资本充足率": {"operation": "DIVIDE_PERCENT", "operands": ["核心一级资本净额", "风险加权资产"]},
    "一级资本充足率": {"operation": "DIVIDE_PERCENT", "operands": ["一级资本净额", "风险加权资产"]},
    "资本充足率": {"operation": "DIVIDE_PERCENT", "operands": ["总资本净额", "风险加权资产"]},
    "流动性比例": {"operation": "DIVIDE_PERCENT", "operands": ["流动性资产余额", "流动性负债余额"]},
    "流动性覆盖率": {"operation": "DIVIDE_PERCENT", "operands": ["优质流动性资产", "未来30日现金净流出量"]},
    "净稳定资金比例": {"operation": "DIVIDE_PERCENT", "operands": ["可用稳定资金", "所需稳定资金"]},
    "营销响应率": {"operation": "DIVIDE_PERCENT", "operands": ["营销响应客户数", "营销触达客户数"]},
    "营销转化率": {"operation": "DIVIDE_PERCENT", "operands": ["营销转化客户数", "营销响应客户数"]},
    "客户活跃率": {"operation": "DIVIDE_PERCENT", "operands": ["月活跃客户数", "个人客户总数"]},
    "手机银行月活率": {"operation": "DIVIDE_PERCENT", "operands": ["手机银行月活客户数", "手机银行注册客户数"]},
    "投诉办结率": {"operation": "DIVIDE_PERCENT", "operands": ["消费投诉办结量", "消费投诉受理量"]},
    "投诉按时办结率": {"operation": "DIVIDE_PERCENT", "operands": ["投诉按时办结量", "消费投诉受理量"]},
}


UNIT_OVERRIDES = {
    "每股收益": "元/股",
    "净息差": "%",
    "净利差": "%",
    "经济增加值": "万元",
    "支付业务平均处理时长": "秒",
    "产品审批平均时长": "天",
    "单笔业务平均处理时长": "分钟",
    "数字渠道平均响应时长": "毫秒",
    "数字渠道故障时长": "分钟",
    "杠杆率暴露总额": "万元",
    "资本缓冲要求": "%",
    "储备资本要求": "%",
    "逆周期资本要求": "%",
    "系统重要性银行附加资本要求": "%",
    "未来30日现金净流出量": "万元",
    "利率敏感性缺口": "万元",
    "累计利率敏感性缺口": "万元",
    "交易账簿利率风险资本": "万元",
    "汇率风险资本": "万元",
    "债券久期": "年",
    "资产久期": "年",
    "负债久期": "年",
    "久期缺口": "年",
    "人均管户数": "户/人",
    "人均存款增量": "万元/人",
    "人均贷款投放": "万元/人",
    "网点日均客流量": "人次",
    "获客成本": "元/户",
    "户均持有产品数": "个/户",
    "户均AUM": "万元/户",
    "单位运营成本": "元/笔",
    "线上贷款申请数": "笔",
    "线上贷款放款额": "万元",
    "线上开户数": "户",
    "线上客户服务量": "次",
    "新生成不良贷款额": "万元",
}

DIRECTION_OVERRIDES = {
    "人工成本利润率": "HIGHER_IS_BETTER",
    "产品销售完成率": "HIGHER_IS_BETTER",
    "业务线上化率": "HIGHER_IS_BETTER",
    "业务自动化处理率": "HIGHER_IS_BETTER",
    "集中运营覆盖率": "HIGHER_IS_BETTER",
    "拨备覆盖率": "HIGHER_IS_BETTER",
    "贷款拨备率": "HIGHER_IS_BETTER",
    "贷后检查完成率": "HIGHER_IS_BETTER",
    "风险预警处置率": "HIGHER_IS_BETTER",
    "核心一级资本充足率": "HIGHER_IS_BETTER",
    "一级资本充足率": "HIGHER_IS_BETTER",
    "资本充足率": "HIGHER_IS_BETTER",
    "资本充足率监管余量": "HIGHER_IS_BETTER",
    "核心一级资本充足率监管余量": "HIGHER_IS_BETTER",
    "一级资本充足率监管余量": "HIGHER_IS_BETTER",
    "经济资本回报率": "HIGHER_IS_BETTER",
    "流动性比例": "HIGHER_IS_BETTER",
    "流动性覆盖率": "HIGHER_IS_BETTER",
    "净稳定资金比例": "HIGHER_IS_BETTER",
    "流动性匹配率": "HIGHER_IS_BETTER",
    "高流动性资产充足率": "HIGHER_IS_BETTER",
    "存款稳定率": "HIGHER_IS_BETTER",
    "核心负债依存度": "HIGHER_IS_BETTER",
    "累计外汇敞口头寸比例": "LOWER_IS_BETTER",
    "利率风险限额使用率": "LOWER_IS_BETTER",
    "外汇风险限额使用率": "LOWER_IS_BETTER",
    "关联方授信比例": "LOWER_IS_BETTER",
    "合规检查问题整改率": "HIGHER_IS_BETTER",
    "客户激活率": "HIGHER_IS_BETTER",
    "交叉销售率": "HIGHER_IS_BETTER",
    "存款产品渗透率": "HIGHER_IS_BETTER",
    "贷款产品渗透率": "HIGHER_IS_BETTER",
    "理财产品渗透率": "HIGHER_IS_BETTER",
    "信用卡产品渗透率": "HIGHER_IS_BETTER",
    "保险代销产品渗透率": "HIGHER_IS_BETTER",
    "基金代销产品渗透率": "HIGHER_IS_BETTER",
    "理财复购率": "HIGHER_IS_BETTER",
    "线上渠道交易占比": "HIGHER_IS_BETTER",
    "投诉办结率": "HIGHER_IS_BETTER",
    "投诉按时办结率": "HIGHER_IS_BETTER",
    "客服人工接通率": "HIGHER_IS_BETTER",
    "一次性问题解决率": "HIGHER_IS_BETTER",
}


AGGREGATION_OVERRIDES = {
    "净息差": "RATIO",
    "净利差": "RATIO",
    "杠杆率暴露总额": "SNAPSHOT",
    "资本缓冲要求": "RATIO",
    "储备资本要求": "RATIO",
    "逆周期资本要求": "RATIO",
    "系统重要性银行附加资本要求": "RATIO",
    "未来30日现金净流出量": "SNAPSHOT",
    "利率敏感性缺口": "SNAPSHOT",
    "累计利率敏感性缺口": "SNAPSHOT",
    "交易账簿利率风险资本": "SNAPSHOT",
    "汇率风险资本": "SNAPSHOT",
    "数字渠道交易金额": "SUM",
    "客户满意度": "RATIO",
}


LEGACY_TARGET_NAMES = {
    "ZB001": "各项存款余额",
    "ZB002": "各项贷款余额",
    "ZB003": "对公存款余额",
    "ZB004": "个人存款余额",
    "ZB005": "对公贷款余额",
    "ZB006": "个人贷款余额",
    "ZB007": "中间业务收入",
    "ZB008": "利息净收入",
    "ZB009": "营业收入",
    "ZB010": "营业支出",
    "ZB011": "净利润",
    "ZB012": "成本收入比",
    "ZB013": "不良贷款率",
    "ZB014": "不良贷款余额",
    "ZB015": "拨备覆盖率",
    "ZB016": "资本充足率",
    "ZB017": "逾期贷款率",
    "ZB018": "员工人数",
    "ZB019": "网点数量",
    "ZB020": "个人客户总数",
    "ZB021": "对公客户总数",
}


COMMON_ALIAS_OVERRIDES: dict[str, tuple[str, ...]] = {
    "各项存款余额": ("存款余额", "存款规模", "存款总额"),
    "各项贷款余额": ("贷款余额", "贷款规模", "贷款总额"),
    "对公存款余额": ("对公存款", "公司存款"),
    "个人存款余额": ("个人存款", "零售存款"),
    "对公贷款余额": ("对公贷款", "公司贷款"),
    "个人贷款余额": ("个人贷款", "零售贷款"),
    "中间业务收入": ("中收", "非利息业务收入"),
    "利息净收入": ("净利息收入", "净利息收益"),
    "营业收入": ("营收", "营业总收入"),
    "营业支出": ("营业支出额", "营业总支出"),
    "净利润": ("净利", "税后利润"),
    "成本收入比": ("成本收入比例", "成本收益比"),
    "不良贷款率": ("不良率", "贷款不良率"),
    "不良贷款余额": ("不良余额", "不良贷款规模"),
    "拨备覆盖率": ("拨备覆盖比例", "不良贷款拨备覆盖率"),
    "资本充足率": ("资本充足比例", "总资本充足率"),
    "逾期贷款率": ("逾期率", "贷款逾期率"),
    "员工人数": ("员工数", "在岗员工数"),
    "网点数量": ("网点数", "营业网点数", "网点"),
    "个人客户总数": ("个人客户数", "零售客户数"),
    "对公客户总数": ("对公客户数", "公司客户数"),
}


DIMENSION_OVERRIDES = {
    "员工人数": ["organization", "date", "employee_type"],
    "网点数量": ["organization", "date", "channel"],
}


def _normalize_alias(value: str) -> str:
    return re.sub(r"[\s（）()_\-/]+", "", value).casefold()


def _alias_candidates(name: str) -> list[str]:
    candidates = list(COMMON_ALIAS_OVERRIDES.get(name, ()))
    if name.startswith("各项"):
        candidates.append(name[2:])
    replacements = (
        ("余额", ("", "规模")),
        ("数量", ("数",)),
        ("总数", ("数",)),
        ("笔数", ("业务量",)),
        ("平均时长", ("平均耗时",)),
        ("占比", ("比例",)),
        ("比率", ("比例",)),
        ("率", ("比例",)),
    )
    for suffix, alternatives in replacements:
        if name.endswith(suffix):
            stem = name[: -len(suffix)]
            candidates.extend(stem + alternative for alternative in alternatives)
            break
    if "日均" in name:
        candidates.append(name.replace("日均", "每日平均"))
    candidates.extend((f"{name}指标", f"{name}口径", f"{name}统计"))
    return candidates


def _build_aliases(names: list[str]) -> dict[str, list[str]]:
    canonical = {_normalize_alias(name) for name in names}
    claimed: set[str] = set()
    aliases_by_name: dict[str, list[str]] = {}
    for name in names:
        aliases: list[str] = []
        for alias in _alias_candidates(name):
            normalized = _normalize_alias(alias)
            if not normalized or normalized in canonical or normalized in claimed:
                continue
            aliases.append(alias)
            claimed.add(normalized)
        if len(aliases) < 2:
            raise AssertionError(f"expected at least two unique aliases for {name}")
        aliases_by_name[name] = aliases
    return aliases_by_name


def infer_unit(name: str) -> str:
    if name in UNIT_OVERRIDES:
        return UNIT_OVERRIDES[name]
    if name in DERIVED_FORMULAS:
        return "%"
    if any(token in name for token in ("率", "占比", "比例", "概率", "满意度", "敏感度", "集中度", "依存度")):
        return "%"
    if "AUM" in name or any(
        token in name
        for token in (
            "金额", "余额", "收入", "支出", "费用", "管理费", "利润", "收益", "损失", "成本", "规模", "额度", "缺口", "净额", "总额", "销售额", "赎回额", "兑付额", "处置额", "核销额", "清收额", "资产", "负债", "资本", "资金", "暴露", "头寸", "价值", "投放", "增额"
        )
    ):
        return "万元"
    if "人数" in name:
        return "人"
    if ("客户" in name and ("数" in name or "量" in name)) or any(
        token in name for token in ("账户数", "账户总数", "用户数", "商户数", "管户数")
    ):
        return "户"
    if any(token in name for token in ("笔数", "业务量", "交易量")):
        return "笔"
    if "投诉" in name and "率" not in name:
        return "件"
    if any(token in name for token in ("次数", "事件数")):
        return "次"
    if any(token in name for token in ("数量", "产品数", "网点数", "设备数", "终端数", "申请数")):
        return "个"
    return "个"


def infer_aggregation(name: str, metric_type: str) -> str:
    if name in AGGREGATION_OVERRIDES:
        return AGGREGATION_OVERRIDES[name]
    if metric_type == "DERIVED" or any(token in name for token in ("率", "占比", "比例", "敏感度", "集中度", "依存度")):
        return "RATIO"
    if any(token in name for token in ("余额", "净额", "总资产", "总负债", "规模", "头寸", "暴露")):
        return "SNAPSHOT"
    if any(token in name for token in ("平均", "日均", "户均", "人均", "每股", "久期")):
        return "AVG"
    if any(token in name for token in ("数", "量", "次数", "笔数")):
        return "COUNT"
    return "SUM"


def infer_direction(name: str) -> str:
    if name in DIRECTION_OVERRIDES:
        return DIRECTION_OVERRIDES[name]
    if any(
        token in name
        for token in (
            "不良", "逾期", "损失", "差错", "流失", "投诉", "故障", "违规", "处罚", "否决", "成本", "费用", "时长", "缺口", "风险暴露", "集中度"
        )
    ):
        return "LOWER_IS_BETTER"
    if any(
        token in name
        for token in (
            "利润", "收入", "收益", "成功", "可用", "覆盖", "完成", "满意", "接通", "解决", "办结", "活跃", "留存", "响应", "转化", "增长", "唤醒"
        )
    ):
        return "HIGHER_IS_BETTER"
    return "CONTEXT_DEPENDENT"


def definition_for(name: str, unit: str, purpose: str, formula: dict[str, Any] | None) -> str:
    if formula:
        operands = "与".join(formula["operands"])
        return f"以{operands}按百分比口径计算{name}，用于{purpose}；正式发布前须核验精确公式与适用范围。"
    if unit == "%":
        return f"统计{name}的报告比例，用于{purpose}；当前按来源主题原创转述，正式发布前须核验分子、分母与适用范围。"
    if unit in {"户", "笔", "件", "次", "个"}:
        return f"统计{name}对应对象在指定期间或时点的数量，用于{purpose}；正式发布前须核验去重和时间口径。"
    return f"统计{name}在指定期间或时点的报告值，用于{purpose}；正式发布前须核验范围、计量基础和时间口径。"


def build_metric_records() -> list[dict[str, Any]]:
    seeds: list[tuple[dict[str, Any], str]] = []
    for group in GROUPS:
        expected = DOMAIN_QUOTAS[group["domain"]]
        if len(group["names"]) != expected:
            raise AssertionError(f"{group['domain']} expected {expected}, got {len(group['names'])}")
        seeds.extend((group, name) for name in group["names"])
    if len(seeds) != 360:
        raise AssertionError(f"expected 360 metric seeds, got {len(seeds)}")
    names = [name for _, name in seeds]
    if len(names) != len(set(names)):
        raise AssertionError("metric seed names must be globally unique")

    code_by_name = {name: f"CNB{index:03d}" for index, name in enumerate(names, start=1)}
    aliases_by_name = _build_aliases(names)
    legacy_codes_by_name: dict[str, list[str]] = {}
    for legacy_code, target_name in LEGACY_TARGET_NAMES.items():
        if target_name not in code_by_name:
            raise AssertionError(f"missing legacy target metric: {legacy_code} -> {target_name}")
        legacy_codes_by_name.setdefault(target_name, []).append(legacy_code)
    records: list[dict[str, Any]] = []
    for index, (group, name) in enumerate(seeds, start=1):
        source_formula = DERIVED_FORMULAS.get(name)
        metric_type = "DERIVED" if source_formula else "BASE"
        unit = infer_unit(name)
        formula = None
        if source_formula:
            formula = {
                "operation": source_formula["operation"],
                "expression": " / ".join(source_formula["operands"]) + " * 100",
                "operands": [code_by_name[operand] for operand in source_formula["operands"]],
            }
        records.append(
            {
                "code": f"CNB{index:03d}",
                "name": name,
                "aliases": aliases_by_name[name],
                "legacyCodes": legacy_codes_by_name.get(name, []),
                "semanticKey": f"{group['domain']}/{name}",
                "scene": group["scene"],
                "domain": group["domain"],
                "metricType": metric_type,
                "valueType": "INTEGER" if unit in {"户", "笔", "件", "次", "个"} else "DECIMAL",
                "unit": unit,
                "aggregation": infer_aggregation(name, metric_type),
                "direction": infer_direction(name),
                "definition": definition_for(name, unit, group["purpose"], source_formula),
                "dimensions": list(DIMENSION_OVERRIDES.get(name, group["dimensions"])),
                "formula": formula,
                "sourceRefs": [
                    {"sourceId": source_id, "locator": group["locator"]}
                    for source_id in group["sourceIds"]
                ],
                "provenanceLevel": "NEEDS_HUMAN_VERIFICATION",
                "reviewStatus": "CANDIDATE",
                "valuePolicy": "SYNTHETIC_OR_DESENSITIZED_ONLY",
            }
        )
    return records
