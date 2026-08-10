#!/usr/bin/env python3
"""第二版银行 NL2SQL Ground Truth 审查 —— 规则库。

本模块只承载从冻结工作簿（source.xlsx）四张表可直接确定的规则与解析原语：

- 机构信息表：机构编号/名称；
- 指标清单表：指标编号/名称/含义/单位；
- 衍生维度说明：较年初/较上季/较上月/较同期/全省均值/排名方向/增量/增幅/表现较好(前三)/表现较差(后四)；
- 指标数据表：唯一数值证据。

规则版本号随规则变更递增；审查器把它写入 audit-summary.json。
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal, ROUND_HALF_UP
from typing import Any, Iterable

RULES_VERSION = "2.0.0"

SOURCE_SHA256_EXPECTED = "C3B810A4938FEFC77A5C834C4C6857BEC7F67162C4160ABD9F66D9DD6018703C"

SHEET_QUESTION = "问题答案清单"
SHEET_ORG = "机构信息表"
SHEET_METRIC = "指标清单表"
SHEET_DERIVED = "衍生维度说明"
SHEET_FACT = "指标数据表"

QUESTION_HEADERS = ("问题编号", "问题类型", "问题难度", "问题描述", "问题结果")
ORG_HEADERS = ("机构编号", "机构名称")
METRIC_HEADERS = ("指标编号", "指标名称", "指标含义", "指标单位")
DERIVED_HEADERS = ("衍生维度", "衍生口径说明")
FACT_HEADERS = ("数据日期", "指标编号", "指标名称", "机构编号", "指标值")

SPLIT_MAP = {"训练集": "train", "验证集": "dev", "测试集": "test"}

YEAR_START = "2024-12-31"  # 衍生维度说明：较年初 = 当日值 - 2024年12月31日值

# 衍生维度说明：比率类指标（不做增幅计算）
RATE_METRICS = frozenset({"ZB012", "ZB013", "ZB015", "ZB016", "ZB017"})
# 衍生维度说明：排名升序（越低越好）指标
ASCENDING_RANK_METRICS = frozenset({"ZB012", "ZB013", "ZB017"})
# 衍生维度说明：表现较好 = 前三；表现较差 = 后四
GOOD_RANK_CUTOFF = 3
BAD_TAIL_SIZE = 4

# 问题文本可直接命中的衍生比率名称 -> (分子指标, 分母指标)
DERIVED_RATIOS: dict[str, tuple[str, str]] = {
    "存贷比": ("ZB002", "ZB001"),
    "净利润率": ("ZB011", "ZB009"),
}

# 指标简称（长串优先匹配）-> 指标编号；None 表示衍生（由 DERIVED_RATIOS 判定）
METRIC_ALIASES: list[tuple[str, str | None]] = [
    ("各项存款余额", "ZB001"),
    ("各项贷款余额", "ZB002"),
    ("对公存款余额", "ZB003"),
    ("个人存款余额", "ZB004"),
    ("对公贷款余额", "ZB005"),
    ("个人贷款余额", "ZB006"),
    ("对公存款", "ZB003"),
    ("个人存款", "ZB004"),
    ("对公贷款", "ZB005"),
    ("个人贷款", "ZB006"),
    ("中间业务收入", "ZB007"),
    ("净利息收入", "ZB008"),
    ("营业收入", "ZB009"),
    ("营业支出", "ZB010"),
    ("净利润率", None),
    ("净利润", "ZB011"),
    ("成本收入比", "ZB012"),
    ("不良贷款率", "ZB013"),
    ("不良贷款余额", "ZB014"),
    ("拨备覆盖率", "ZB015"),
    ("资本充足率", "ZB016"),
    ("逾期贷款率", "ZB017"),
    ("员工人数", "ZB018"),
    ("网点数量", "ZB019"),
    ("个人客户数", "ZB020"),
    ("对公客户数", "ZB021"),
    ("不良率", "ZB013"),
    ("逾期率", "ZB017"),
    ("拨备", "ZB015"),
    ("存款总额", "ZB001"),
    ("存款规模", "ZB001"),
    ("贷款规模", "ZB002"),
    ("贷款余额", "ZB002"),
    ("存款余额", "ZB001"),
    ("存贷比", None),
    ("人均利润", None),
    ("网点平均存款规模", None),
    ("贷款总额", "ZB002"),
    ("存款", "ZB001"),
    ("贷款", "ZB002"),
    ("员工", "ZB018"),
    ("网点", "ZB019"),
]

# 与指标名同时出现的计量词，防止"员工人数"误命中"人数"
_METRIC_BLOCKERS = ("农商行", "机构", "全省", "家")

# 指标别名 -> 指标编号（含衍生占位解析）
METRIC_CODE_BY_ALIAS: dict[str, str] = {alias: code for alias, code in METRIC_ALIASES if code}
# 机构名称 -> 机构编号（运行时填充）
ORG_CODE_BY_NAME: dict[str, str] = {}

_MONTH_END = {1: 31, 2: 28, 3: 31, 4: 30, 5: 31, 6: 30, 7: 31, 8: 31, 9: 30, 10: 31, 11: 30, 12: 31}
_CN_NUM = {"一": 1, "二": 2, "两": 2, "三": 3, "四": 4, "五": 5, "六": 6, "七": 7, "八": 8, "九": 9}


def month_end(year: int, month: int) -> str:
    day = _MONTH_END[month]
    if month == 2 and (year % 400 == 0 or (year % 4 == 0 and year % 100 != 0)):
        day = 29
    return f"{year:04d}-{month:02d}-{day:02d}"


def quarter_end(year: int, quarter: int) -> str:
    return month_end(year, quarter * 3)


def shift_months(date_text: str, months: int) -> str:
    """把 YYYY-MM-DD 平移 months 个月（保持在月末/原日）。"""
    y, m, _d = (int(part) for part in date_text.split("-"))
    total = y * 12 + (m - 1) + months
    ny, nm = divmod(total, 12)
    nm += 1
    return month_end(ny, nm)


def previous_month_end(date_text: str) -> str:
    return shift_months(date_text, -1)


def previous_quarter_end(date_text: str) -> str:
    y, m, _d = (int(part) for part in date_text.split("-"))
    q = (m - 1) // 3
    yq = y * 4 + q - 1
    return quarter_end(yq // 4, yq % 4 + 1)


def same_month_last_year(date_text: str) -> str:
    y, m, _d = (int(part) for part in date_text.split("-"))
    return month_end(y - 1, m)


# ---------------------------------------------------------------- 数值与单位

_UNIT_SYNONYMS = {
    "亿元": "亿元",
    "亿": "亿元",
    "万元": "万元",
    "万": "万元",
    "%": "%",
    "个百分点": "个百分点",
    "百分点": "个百分点",
    "人": "人",
    "户": "户",
    "个": "个",
    "家": "家",
    "天": "天",
    "名": "名",
    "万元/人": "万元/人",
    "万元/网点": "万元/网点",
    "元": "元",
}

_UNIT_TOKEN_RE = re.compile(r"亿元|万元|万元/人|万元/网点|个百分点|百分点|%|人|户|个|家|天|名|亿|万|元")


def normalize_unit(unit: str | None) -> str | None:
    if unit is None:
        return None
    return _UNIT_SYNONYMS.get(unit.strip(), unit.strip())


def units_compatible(claim_unit: str | None, token_unit: str | None) -> bool:
    """claim 单位与旧答案 token 单位是否兼容。"""
    if claim_unit is None:
        return True
    if token_unit is None:
        return True
    cu, tu = normalize_unit(claim_unit), normalize_unit(token_unit)
    if cu == tu:
        return True
    if {cu, tu} == {"%", "个百分点"}:
        return True
    # "万元/网点" 与 "万元" 等前缀兼容
    return cu.startswith(tu) or tu.startswith(cu)


def round2(value: float | None) -> float | None:
    if value is None:
        return None
    return float(Decimal(str(value)).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP))


def round1(value: float | None) -> float | None:
    if value is None:
        return None
    return float(Decimal(str(value)).quantize(Decimal("0.1"), rounding=ROUND_HALF_UP))


def display_value(value: float | None, rounding: int = 2) -> str:
    if value is None:
        return "N/A"
    if rounding == 0:
        return f"{int(round(value))}"
    rounded = round1(value) if rounding == 1 else round2(value)
    text = f"{rounded:.2f}".rstrip("0").rstrip(".")
    if text == "-0" or text == "0":
        text = "0.0" if rounding >= 1 else "0"
    return text


def values_match(claim_value: float | None, token_value: float, rounding: int = 2) -> bool:
    """重算值与旧答案数值是否在舍入口径内容差匹配（方向由方向词表达，数值按绝对值比较）。"""
    if claim_value is None:
        return False
    if rounding == 0:
        return int(round(abs(claim_value))) == int(round(abs(token_value)))
    if rounding == 1:
        return round1(abs(claim_value)) == round1(abs(token_value))
    if abs(claim_value) < 0.005 and abs(token_value) < 0.005:
        return True
    return round2(abs(claim_value)) == round2(abs(token_value))


# ---------------------------------------------------------------- 日期解析

_ISO_DATE_RE = re.compile(r"(?<!\d)(20\d{2})-(\d{1,2})-(\d{1,2})(?!\d)")
_CN_DATE_RE = re.compile(r"(20\d{2})\s*年\s*(\d{1,2})\s*月\s*(\d{1,2})\s*日")
_CN_MONTH_END_RE = re.compile(r"(20\d{2})\s*年\s*(\d{1,2})\s*月(?:底|末)")
_CN_Q_RE = re.compile(r"(20\d{2})\s*年?\s*[Qq]([1-4])(?:季)?(?:度)?(?:末|底)?")
_CN_QUARTER_RE = re.compile(r"(20\d{2})\s*年\s*(第?[一二三四1-4])\s*季度(?:末|底)?")
_CN_YEAR_END_RE = re.compile(r"(20\d{2})\s*年\s*(?:底|末|底前)")
_CN_HALF_RE = re.compile(r"(20\d{2})\s*年\s*上半年\s*(?:末|底)")

_DATE_HINT_RE = re.compile(r"(20\d{2})-(\d{1,2})")


def _cn_to_int(text: str) -> int:
    if text.isdigit():
        return int(text)
    return _CN_NUM[text[-1]] if text[-1] in _CN_NUM else int(text)


def parse_absolute_date(text: str) -> str | None:
    """从文本中解析一个绝对日期（YYYY-MM-DD）。取第一个命中。"""
    match = _ISO_DATE_RE.search(text)
    if match:
        y, m, d = int(match.group(1)), int(match.group(2)), int(match.group(3))
        return f"{y:04d}-{m:02d}-{d:02d}"
    match = _CN_DATE_RE.search(text)
    if match:
        y, m, d = int(match.group(1)), int(match.group(2)), int(match.group(3))
        return f"{y:04d}-{m:02d}-{d:02d}"
    match = _CN_MONTH_END_RE.search(text)
    if match:
        y, m = int(match.group(1)), int(match.group(2))
        return month_end(y, m)
    match = _CN_Q_RE.search(text)
    if match:
        return quarter_end(int(match.group(1)), int(match.group(2)))
    match = _CN_QUARTER_RE.search(text)
    if match:
        return quarter_end(int(match.group(1)), _cn_to_int(match.group(2)))
    match = _CN_HALF_RE.search(text)
    if match:
        return f"{int(match.group(1)):04d}-06-30"
    match = _CN_YEAR_END_RE.search(text)
    if match:
        return f"{int(match.group(1)):04d}-12-31"
    if re.search(r"年\s*底", text):
        match = re.search(r"(20\d{2})\s*年", text)
        if match:
            return f"{int(match.group(1)):04d}-12-31"
    return None


def parse_date_range(text: str) -> tuple[str, str] | None:
    """解析明确的时间范围（2025年全年、2025年一季度）。"""
    match = re.search(r"(20\d{2})\s*年\s*全年", text)
    if match:
        year = int(match.group(1))
        return f"{year:04d}-01-01", f"{year:04d}-12-31"
    match = re.search(r"(20\d{2})\s*年\s*(?:第?[一二三四1-4])\s*季度", text)
    if match:
        year = int(match.group(1))
        qm = re.search(r"(?:第?([一二三四1-4]))\s*季度", text)
        q = _cn_to_int(qm.group(1))
        start = month_end(year, (q - 1) * 3 + 1)
        return start, quarter_end(year, q)
    return None


@dataclass
class TimeSpec:
    """题意时间：主日期、可选的基期与比较类型。"""

    main_date: str | None = None
    range_start: str | None = None
    range_end: str | None = None
    baseline: str | None = None
    comparison_type: str | None = None  # ytd / mom / qoq / yoy
    series_dates: list[str] = field(default_factory=list)  # 逐季序列


def resolve_relative_baseline(main_date: str, comparison_type: str) -> str | None:
    if comparison_type == "ytd":
        return YEAR_START
    if comparison_type == "mom":
        return previous_month_end(main_date)
    if comparison_type == "qoq":
        return previous_quarter_end(main_date)
    if comparison_type == "yoy":
        return same_month_last_year(main_date)
    return None


def quarter_series(start_date: str, end_date: str) -> list[str]:
    """从 start 到 end 的季度末序列（含首尾；首须为季度末）。"""
    result: list[str] = []
    cursor = start_date
    while cursor <= end_date:
        result.append(cursor)
        cursor = shift_months(cursor, 3)
    if not result or result[-1] != end_date:
        return []
    return result


# ---------------------------------------------------------------- 机构与指标

@dataclass
class Organization:
    code: str
    name: str


@dataclass
class Metric:
    code: str
    name: str
    meaning: str
    unit: str

    @property
    def is_rate(self) -> bool:
        return self.code in RATE_METRICS

    @property
    def rank_ascending(self) -> bool:
        return self.code in ASCENDING_RANK_METRICS


def extract_organizations(question: str, orgs: list[Organization]) -> list[Organization]:
    """匹配问题文本中的机构全名；'13家农商行'/'全省' 表示全部机构。"""
    found: list[Organization] = []
    for org in orgs:
        if org.name in question:
            found.append(org)
    if not found and re.search(r"13\s*家|全省", question):
        return list(orgs)
    return found


@dataclass
class MetricHit:
    code: str | None  # None = 衍生比率占位，见 derived
    matched_text: str
    derived: tuple[str, str] | None = None  # 衍生比率 (分子, 分母)


def extract_metrics(question: str) -> list[MetricHit]:
    """按长串优先匹配指标别名；命中后从文本移除，避免二次命中。"""
    remaining = question
    hits: list[MetricHit] = []
    for alias, code in sorted(METRIC_ALIASES, key=lambda item: len(item[0]), reverse=True):
        while alias in remaining:
            remaining = remaining.replace(alias, "　", 1)
            if code is not None:
                hits.append(MetricHit(code=code, matched_text=alias))
            else:
                ratio = DERIVED_RATIOS.get(alias)
                if ratio is not None:
                    hits.append(MetricHit(code=None, matched_text=alias, derived=ratio))
                elif alias == "人均利润":
                    hits.append(MetricHit(code=None, matched_text=alias, derived=("ZB011", "ZB018")))
                elif alias == "网点平均存款规模":
                    hits.append(MetricHit(code=None, matched_text=alias, derived=("ZB001", "ZB019")))
    return hits


_METRIC_ALIAS_RE = re.compile("|".join(sorted((alias for alias, _code in METRIC_ALIASES), key=len, reverse=True)))


def extract_metrics_ordered(question: str) -> list[tuple[int, MetricHit]]:
    """按文本中首次出现位置返回指标命中 [(位置, MetricHit)]；同一位置长串优先。

    与 extract_metrics 的别名表顺序不同，本函数按文本出现顺序输出，
    用于“明确命名的指标集合”的确定性提取（澄清文本与解析器共用）。
    """
    found: list[tuple[int, MetricHit]] = []
    for match in _METRIC_ALIAS_RE.finditer(question):
        alias = match.group(0)
        code = METRIC_CODE_BY_ALIAS.get(alias)
        if code is not None:
            hit = MetricHit(code=code, matched_text=alias)
        else:
            ratio = DERIVED_RATIOS.get(alias)
            if ratio is not None:
                hit = MetricHit(code=None, matched_text=alias, derived=ratio)
            elif alias == "人均利润":
                hit = MetricHit(code=None, matched_text=alias, derived=("ZB011", "ZB018"))
            elif alias == "网点平均存款规模":
                hit = MetricHit(code=None, matched_text=alias, derived=("ZB001", "ZB019"))
            else:
                continue
        found.append((match.start(), hit))
    return found


# ---------------------------------------------------------------- 旧答案 token

_DIRECTION_UP = ("增加", "增长", "上升", "提高", "涨", "增", "高于", "多", "高出", "超过", "达标", "满足", "上升了")
_DIRECTION_DOWN = ("下降", "减少", "降低", "跌", "低于", "少", "缩小", "低出", "下降")
_DIRECTION_FLAT = ("持平", "不变", "平稳", "基本持平")
_DIFF_WORDS = ("相差", "差", "差距")

_ROLE_RANK_RE = re.compile(r"第\s*(\d+)\s*名")
_ROLE_TOP_RE = re.compile(r"(前|后)\s*(\d+)\s*名?")
# occurrence-local total 检测：'共' 必须紧邻当前数字之前（before 以 '共' + 可选空白
# 结尾）、单位紧跟数字之后（after 以可选空白 + 家/天/个 开头）。不做全文等值搜索，
# 避免把所有相同数值的 token 都归因为 total。
_ROLE_TOTAL_PREFIX_RE = re.compile(r"共\s*$")
_ROLE_TOTAL_UNIT_RE = re.compile(r"\s*(家|天|个)")
_ROLE_EXTREME_RE = re.compile(r"(最高|最低|最大|最小)")
_ROLE_MEAN_RE = re.compile(r"(日均|均值|平均)")
_ROLE_CURRENT_RE = re.compile(r"当前值|当前")
_ROLE_SUM_RE = re.compile(r"(合计|总计|总和)")
_ROLE_DIFF_RE = re.compile(r"(差额|相差|差距|差值)")
_ROLE_RATIO_RE = re.compile(r"(占比|比例|比重)")
# 局部显式 total 标签：'总天数：N天' / '总天数N天' / '总天数为N天'。
# 只允许标签紧邻数字之前（before 是当前数字前的窗口文本），
# 不做全文等值搜索，避免把所有相同数值的 token 都归因为 total。
_ROLE_TOTAL_LABEL_RE = re.compile(r"总天数\s*(?:[:：]|为|是)?\s*$")

_NUM_RE = re.compile(r"-?\d+(?:\.\d+)?")
_ORG_NAME_IN_ANSWER_RE = re.compile(r"江苏省[A-Z]市农商行")
_METRIC_IN_ANSWER_RE = re.compile(
    "|".join(
        sorted((alias for alias in METRIC_CODE_BY_ALIAS if alias not in ("存款", "贷款", "员工", "网点")), key=len, reverse=True)
    )
)


@dataclass
class AnswerToken:
    raw: str
    value: float
    negative: bool
    unit: str | None
    direction: str | None  # up / down / flat / diff / none
    role: str | None
    org: str | None
    metric: str | None
    context: str
    date_hint: str | None
    pair_prev: str | None  # "从X" 前一值


def _nearest(text: str, position: int, pattern: re.Pattern[str]) -> str | None:
    hits = list(pattern.finditer(text))
    best: re.Match[str] | None = None
    for hit in hits:
        if hit.end() <= position:
            best = hit
        else:
            break
    return best.group(0) if best else None


def _first_after(text: str, position: int, pattern: re.Pattern[str]) -> str | None:
    for hit in pattern.finditer(text):
        if hit.start() > position:
            return hit.group(0)
    return None


def tokenize_answer(answer: str) -> list[AnswerToken]:
    date_spans: list[tuple[int, int]] = []
    for match in _DATE_HINT_RE.finditer(answer):
        # span 扩展到完整日期（YYYY-MM-DD），避免 "-DD" 成为无法归因的数值残片
        end = match.end()
        if end < len(answer) and answer[end] == "-":
            day_match = re.match(r"\d{1,2}", answer[end + 1 :])
            if day_match:
                end = end + 1 + day_match.end()
        date_spans.append((match.start(), end))
    tokens: list[AnswerToken] = []
    for match in _NUM_RE.finditer(answer):
        raw = match.group(0)
        if any(start <= match.start() < end for start, end in date_spans):
            continue
        value = float(raw)
        negative = raw.startswith("-")
        start, end = match.start(), match.end()
        before = answer[max(0, start - 12):start]
        after = answer[end:end + 14]
        context = (before + "|" + after).replace("\n", " ")
        unit_match = None
        for um in _UNIT_TOKEN_RE.finditer(answer):
            if um.start() == end:
                unit_match = um
                break
        unit = normalize_unit(unit_match.group(0)) if unit_match else None
        direction: str | None = None
        direction_candidates: list[tuple[int, str]] = []
        for word in _DIRECTION_UP:
            pos = before.rfind(word)
            if pos >= 0:
                direction_candidates.append((pos, "up"))
        for word in _DIRECTION_DOWN:
            pos = before.rfind(word)
            if pos >= 0:
                direction_candidates.append((pos, "down"))
        for word in _DIRECTION_FLAT:
            pos = before.rfind(word)
            if pos >= 0:
                direction_candidates.append((pos, "flat"))
        for word in _DIFF_WORDS:
            pos = before.rfind(word)
            if pos >= 0:
                direction_candidates.append((pos, "diff"))
        if direction_candidates:
            direction = max(direction_candidates, key=lambda item: item[0])[1]
        role: str | None = None
        if re.search(r"第\s*" + re.escape(raw) + r"\s*名", answer):
            role = "rank"
        else:
            top_role = re.search(r"(前|后)\s*" + re.escape(raw) + r"\s*名?", answer)
            if top_role and not (top_role.start() > 0 and answer[top_role.start() - 1] == "当"):
                role = "top" + top_role.group(1)
            else:
                # occurrence-local：'共N家/天/个' 的 '共' 必须紧邻当前数字之前（允许
                # 空白）、单位紧跟数字之后。不做全文等值搜索——不能因为答案某处有
                # '共N天' 就把其他相同数值的 token 也标成 total。
                if _ROLE_TOTAL_PREFIX_RE.search(before) and _ROLE_TOTAL_UNIT_RE.match(after):
                    role = "total"
                elif _ROLE_TOTAL_LABEL_RE.search(before) and (unit == "天" or unit is None):
                    # occurrence-local：'总天数：N天' 等显式标签必须紧邻当前数字之前；
                    # 只对当前出现位置归因，不做全局等值搜索（不能把其他相同数值
                    # 的 token 也标成 total）。放在 near 短语检查之前，避免相邻的
                    # “占比/比例”短语把 total token 误标为 ratio。
                    role = "total"
                else:
                    near_before = before[-20:]
                    near_after = after[:6]
                    for word in ("最高值", "最低值", "最高", "最低", "最大", "最小"):
                        if word in near_before or word in near_after:
                            role = "extreme"
                            break
                    if role is None and _ROLE_MEAN_RE.search(before[-10:] + after[:6]):
                        role = "mean"
                    if role is None and "当前" in before[-6:]:
                        role = "current"
                    if role is None and _ROLE_SUM_RE.search(before[-10:] + after[:6]):
                        role = "sum"
                    if role is None and _ROLE_DIFF_RE.search(before[-10:] + after[:6]):
                        role = "diff"
                    if role is None and _ROLE_RATIO_RE.search(before[-10:] + after[:6]):
                        role = "ratio"
        org = _nearest(answer, start, _ORG_NAME_IN_ANSWER_RE)
        metric = _nearest(answer, start, _METRIC_IN_ANSWER_RE)
        date_hint = None
        dm = _DATE_HINT_RE.search(before + after)
        if dm:
            date_hint = f"{int(dm.group(1))}-{int(dm.group(2)):02d}"
        pair_prev = None
        if re.search(r"从\s*$", before):
            pair_prev = raw
        tokens.append(
            AnswerToken(
                raw=raw,
                value=value,
                negative=negative,
                unit=unit,
                direction=direction,
                role=role,
                org=org,
                metric=metric,
                context=context,
                date_hint=date_hint,
                pair_prev=pair_prev,
            )
        )
    return tokens


def question_numbers(question: str) -> list[float]:
    """问题文本中出现的非日期数值（阈值常数等）。"""
    remaining = question
    for pattern in (
        _ISO_DATE_RE, _CN_DATE_RE, _CN_MONTH_END_RE, _CN_Q_RE, _CN_QUARTER_RE, _CN_HALF_RE, _CN_YEAR_END_RE,
        re.compile(r"20\d{2}\s*年(?:底|末)"),
    ):
        remaining = pattern.sub(" ", remaining)
    values: list[float] = []
    for match in _NUM_RE.finditer(remaining):
        raw = match.group(0)
        if re.fullmatch(r"20\d{2}", raw):
            continue
        values.append(float(raw))
    return values


# ---------------------------------------------------------------- 判定与展示

def delta_direction(delta: float | None) -> str | None:
    if delta is None:
        return None
    if abs(delta) < 0.005:
        return "flat"
    return "up" if delta > 0 else "down"


def rank_metric_direction(metric_code: str) -> int:
    """1=升序（越低越好），-1=降序（越高越好）。"""
    return 1 if metric_code in ASCENDING_RANK_METRICS else -1


def sort_key_for_ranking(metric_code: str, value: float, org_code: str) -> tuple[Any, ...]:
    """排名排序键：方向 + 机构代码稳定次序。"""
    if rank_metric_direction(metric_code) == 1:
        return (value, org_code)
    return (-value, org_code)


def rank_positions(values: list[tuple[str, str, float]], metric_code: str) -> list[tuple[str, str, float, int]]:
    """RANK 算法：升/降序排名，并列同名次；返回 (org_code, org_name, value, rank)。"""
    ordered = sorted(values, key=lambda item: sort_key_for_ranking(metric_code, item[2], item[0]))
    result: list[tuple[str, str, float, int]] = []
    prev_value: Any = None
    prev_rank = 0
    for index, (org_code, org_name, value) in enumerate(ordered, start=1):
        if prev_value is not None and value == prev_value:
            rank = prev_rank
        else:
            rank = index
        result.append((org_code, org_name, value, rank))
        prev_value, prev_rank = value, rank
    return result


def tie_free(positions: list[tuple[str, str, float, int]]) -> bool:
    return len({item[3] for item in positions}) == len(positions)
