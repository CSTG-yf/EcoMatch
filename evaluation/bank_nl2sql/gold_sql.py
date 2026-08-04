#!/usr/bin/env python3
"""Deterministic, database-portable gold SQL for the bank NL2SQL benchmark.

This module intentionally has no model dependency.  It converts the frozen
intent annotations into auditable SQL templates over the three benchmark
tables.  Unsupported questions raise ``GoldSqlError`` so that callers create
an adjudication entry rather than silently assigning an invented gold query.
"""

from __future__ import annotations

import calendar
import re
from dataclasses import dataclass
from datetime import date
from typing import Any


LOWER_IS_BETTER = {"ZB012", "ZB013", "ZB017"}
METRIC_CODE_PATTERN = re.compile(r"^ZB\d{3}$")
CHINESE_RANK_NUMBERS = {
    "一": 1,
    "二": 2,
    "三": 3,
    "四": 4,
    "五": 5,
    "六": 6,
    "七": 7,
    "八": 8,
    "九": 9,
    "十": 10,
}
METRIC_ALIASES = {
    "各项存款余额": "ZB001",
    "存款余额": "ZB001",
    "存款规模": "ZB001",
    "各项贷款余额": "ZB002",
    "贷款余额": "ZB002",
    "贷款规模": "ZB002",
    "对公存款": "ZB003",
    "个人存款": "ZB004",
    "对公贷款": "ZB005",
    "个人贷款": "ZB006",
    "中间业务收入": "ZB007",
    "净利息收入": "ZB008",
    "营业收入": "ZB009",
    "营业支出": "ZB010",
    "净利润": "ZB011",
    "成本收入比": "ZB012",
    "不良贷款率": "ZB013",
    "不良率": "ZB013",
    "不良贷款余额": "ZB014",
    "拨备覆盖率": "ZB015",
    "资本充足率": "ZB016",
    "逾期贷款率": "ZB017",
    "逾期率": "ZB017",
    "员工人数": "ZB018",
    "员工": "ZB018",
    "网点": "ZB019",
    "个人客户数": "ZB020",
    "对公客户数": "ZB021",
}


class GoldSqlError(ValueError):
    """A question cannot be transformed into a verified gold SQL template."""


@dataclass(frozen=True)
class GoldSqlSpec:
    sql: str
    s2sql: str
    features: list[str]


def _sql_literal(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def _month_end(year: int, month: int) -> date:
    return date(year, month, calendar.monthrange(year, month)[1])


def resolve_date(expression: str, question: str = "") -> str:
    """Resolve absolute Chinese/ISO dates used by the frozen question set."""

    text = expression or question
    iso = re.search(r"(20\d{2})-(\d{1,2})-(\d{1,2})", text)
    if iso:
        return date(int(iso.group(1)), int(iso.group(2)), int(iso.group(3))).isoformat()
    chinese_day = re.search(r"(20\d{2})年(\d{1,2})月(\d{1,2})日", text)
    if chinese_day:
        return date(int(chinese_day.group(1)), int(chinese_day.group(2)), int(chinese_day.group(3))).isoformat()
    chinese_month = re.search(r"(20\d{2})年(\d{1,2})月(?:末|底)", text)
    if chinese_month:
        return _month_end(int(chinese_month.group(1)), int(chinese_month.group(2))).isoformat()
    half_year = re.search(r"(20\d{2})年(?:上半年末|年中)", text)
    if half_year:
        return date(int(half_year.group(1)), 6, 30).isoformat()
    quarter = re.search(r"(20\d{2})\s*(?:年)?\s*[Qq]([1-4])(?:末)?", text)
    if not quarter:
        quarter = re.search(r"(20\d{2})年([一二三四])季度(?:末)?", text)
        quarter_index = {"一": 1, "二": 2, "三": 3, "四": 4}
        if quarter:
            return _month_end(int(quarter.group(1)), quarter_index[quarter.group(2)] * 3).isoformat()
    elif quarter:
        return _month_end(int(quarter.group(1)), int(quarter.group(2)) * 3).isoformat()
    year_end = re.search(r"(20\d{2})年(?:底|末|全年)", text)
    if year_end:
        return date(int(year_end.group(1)), 12, 31).isoformat()
    raise GoldSqlError(f"Cannot resolve an absolute date from: {expression or question}")


def _date_from_record(record: dict[str, Any]) -> str:
    expressions = record.get("normalizedIntent", {}).get("time", {}).get("expressions", [])
    question = str(record.get("question", ""))
    for expression in expressions:
        try:
            return resolve_date(str(expression), question)
        except GoldSqlError:
            continue
    return resolve_date("", question)


def _latest_date_from_record(record: dict[str, Any]) -> str:
    expressions = record.get("normalizedIntent", {}).get("time", {}).get("expressions", [])
    question = str(record.get("question", ""))
    resolved: list[str] = []
    for expression in expressions:
        try:
            resolved.append(resolve_date(str(expression), question))
        except GoldSqlError:
            continue
    return max(resolved) if resolved else _date_from_record(record)


def _metric_codes(record: dict[str, Any]) -> list[str]:
    """Return the record's declared base metric codes, or infer them from text.

    Directly supplied ``normalizedIntent.metrics`` fail closed: every entry
    must be an object carrying a valid ``ZB###`` code, duplicates are
    rejected, and any violation raises ``GoldSqlError`` before SQL can be
    generated.  An empty metrics list keeps the historical question-text
    inference behavior (``METRIC_ALIASES``).
    """
    metrics = record.get("normalizedIntent", {}).get("metrics", [])
    if not isinstance(metrics, list):
        raise GoldSqlError(f"Invalid metrics for {record.get('id')}")
    if not metrics:
        question = str(record.get("question", ""))
        aliases = sorted(METRIC_ALIASES, key=len, reverse=True)
        found: list[str] = []
        for alias in aliases:
            if alias in question and METRIC_ALIASES[alias] not in found:
                found.append(METRIC_ALIASES[alias])
        if not found:
            raise GoldSqlError(f"No metric annotation or recognized metric text for {record.get('id')}")
        return found
    codes: list[str] = []
    for metric in metrics:
        if not isinstance(metric, dict):
            raise GoldSqlError(f"Invalid metric entry for {record.get('id')}: {metric!r}")
        code = metric.get("code")
        if not isinstance(code, str) or not code or METRIC_CODE_PATTERN.fullmatch(code) is None:
            raise GoldSqlError(f"Invalid metric code {code!r} for {record.get('id')}")
        codes.append(code)
    if len(set(codes)) != len(codes):
        raise GoldSqlError(f"Duplicate metric codes for {record.get('id')}: {sorted(set(codes))}")
    return codes


def _derived_metrics(record: dict[str, Any]) -> list[dict[str, Any]]:
    """Validate and return the record's declared derived ratio specs.

    Derived specs come exclusively from the official change ledger projection
    (``normalizedIntent.derivedMetrics``); nothing is guessed from the
    question text.  Directly supplied specs fail closed: each must be an
    object declaring numerator/denominator as distinct valid ``ZB###`` base
    metric codes, and any violation raises ``GoldSqlError`` before SQL can be
    generated.  Each spec receives the stable metric code
    ``DERIVED_<numerator>_DIV_<denominator>``.
    """
    specs = record.get("normalizedIntent", {}).get("derivedMetrics", [])
    if not isinstance(specs, list):
        raise GoldSqlError(f"Invalid derivedMetrics for {record.get('id')}")
    validated: list[dict[str, Any]] = []
    for spec in specs:
        if not isinstance(spec, dict):
            raise GoldSqlError(f"Invalid derived metric spec for {record.get('id')}: {spec!r}")
        numerator = spec.get("numerator")
        denominator = spec.get("denominator")
        if not isinstance(numerator, str) or METRIC_CODE_PATTERN.fullmatch(numerator) is None:
            raise GoldSqlError(f"Invalid derived numerator {numerator!r} for {record.get('id')}")
        if not isinstance(denominator, str) or METRIC_CODE_PATTERN.fullmatch(denominator) is None:
            raise GoldSqlError(f"Invalid derived denominator {denominator!r} for {record.get('id')}")
        if numerator == denominator:
            raise GoldSqlError(
                f"Derived numerator and denominator must differ for {record.get('id')}: {numerator!r}"
            )
        validated.append(
            {
                "metricCode": f"DERIVED_{numerator}_DIV_{denominator}",
                "numerator": numerator,
                "denominator": denominator,
            }
        )
    return validated


def _organization_codes(record: dict[str, Any]) -> list[str]:
    return [
        str(organization["code"])
        for organization in record.get("normalizedIntent", {}).get("organizations", [])
        if organization.get("code")
    ]


def _where(date_value: str, metric_code: str, organizations: list[str]) -> str:
    terms = [f"d.data_date = {_sql_literal(date_value)}", f"d.metric_code = {_sql_literal(metric_code)}"]
    if organizations:
        if len(organizations) == 1:
            terms.append(f"d.org_code = {_sql_literal(organizations[0])}")
        else:
            codes = ", ".join(_sql_literal(code) for code in organizations)
            terms.append(f"d.org_code IN ({codes})")
    return " AND ".join(terms)


def _metric_filter(metric_codes: list[str]) -> str:
    if len(metric_codes) == 1:
        return f"d.metric_code = {_sql_literal(metric_codes[0])}"
    return "d.metric_code IN (" + ", ".join(_sql_literal(code) for code in metric_codes) + ")"


def _status_metrics() -> list[str]:
    return ["ZB001", "ZB002", "ZB011", "ZB012", "ZB013", "ZB015", "ZB016", "ZB017"]


def _point_query(record: dict[str, Any]) -> GoldSqlSpec:
    metric_codes = _metric_codes(record)
    where_terms = [f"d.data_date = {_sql_literal(_date_from_record(record))}", _metric_filter(metric_codes)]
    organizations = _organization_codes(record)
    if organizations:
        where_terms.append(_where(_date_from_record(record), metric_codes[0], organizations).split(" AND ")[-1])
    sql = f"""SELECT o.org_code, o.org_name, d.metric_code, d.metric_value
FROM bank_metric_daily d
JOIN bank_organization o ON o.org_code = d.org_code
WHERE {' AND '.join(where_terms)}
ORDER BY d.metric_code, o.org_code"""
    features = ["POINT_QUERY"]
    if len(metric_codes) > 1:
        features.append("MULTI_METRIC")
    return GoldSqlSpec(sql, sql, features)


def _rank_direction(record: dict[str, Any], metric_code: str) -> str:
    question = str(record.get("question", ""))
    if _top_and_bottom_limits(question):
        return "ASC" if metric_code in LOWER_IS_BETTER else "DESC"
    if any(token in question for token in ("最高", "最多", "最大", "第一")):
        return "DESC"
    if any(token in question for token in ("最低", "最少", "最小")):
        return "ASC"
    if "最好" in question:
        return "ASC" if metric_code in LOWER_IS_BETTER else "DESC"
    return "ASC" if metric_code in LOWER_IS_BETTER else "DESC"


def _rank_number(value: str) -> int:
    return int(value) if value.isascii() else CHINESE_RANK_NUMBERS[value]


def _top_and_bottom_limits(question: str) -> tuple[int, int] | None:
    number = r"([1-9]\d*|[一二三四五六七八九十])"
    match = re.search(rf"前{number}和后{number}", question)
    if not match:
        return None
    return _rank_number(match.group(1)), _rank_number(match.group(2))


def _rank_limit(question: str) -> int:
    match = re.search(r"(?:前|后|最后的?)([1-9]\d*|[一二三四五六七八九十])", question)
    if not match:
        return 1
    return _rank_number(match.group(1))


def _bottom_rank_requested(question: str) -> bool:
    if _top_and_bottom_limits(question):
        return False
    return "最后" in question or re.search(
        r"(?:排名)?后(?:[1-9]\d*|[一二三四五六七八九十])", question
    ) is not None


def _annual_average_top_bottom_rank_query(
    record: dict[str, Any], metric_code: str, top_limit: int, bottom_limit: int,
    direction: str,
) -> GoldSqlSpec:
    start_date, end_date = _date_range(record)
    sql = f"""WITH averaged AS (
  SELECT o.org_code, o.org_name, d.metric_code, AVG(d.metric_value) AS metric_value
  FROM bank_metric_daily d
  JOIN bank_organization o ON o.org_code = d.org_code
  WHERE d.data_date BETWEEN {_sql_literal(start_date)} AND {_sql_literal(end_date)}
    AND d.metric_code = {_sql_literal(metric_code)}
  GROUP BY o.org_code, o.org_name, d.metric_code
), ranked AS (
  SELECT org_code, org_name, metric_code, metric_value,
         ROW_NUMBER() OVER (ORDER BY metric_value {direction}, org_code) AS rank_position,
         COUNT(*) OVER () AS total_count
  FROM averaged
)
SELECT org_code, org_name, metric_code, metric_value, rank_position
FROM ranked
WHERE rank_position <= {top_limit} OR rank_position > total_count - {bottom_limit}
ORDER BY CASE WHEN rank_position <= {top_limit} THEN 0 ELSE 1 END, rank_position"""
    return GoldSqlSpec(sql, sql, ["RANKING", "WINDOW_RANK", "DATE_RANGE", "AVERAGE", "TOP_BOTTOM"])


def _annual_daily_extrema_query(record: dict[str, Any], metric_code: str) -> GoldSqlSpec:
    start_date, end_date = _date_range(record)
    sql = f"""WITH ranked AS (
  SELECT o.org_code, o.org_name, d.metric_code, d.metric_value,
         ROW_NUMBER() OVER (
           ORDER BY d.metric_value DESC, d.data_date, o.org_code
         ) AS maximum_rank,
         ROW_NUMBER() OVER (
           ORDER BY d.metric_value ASC, d.data_date, o.org_code
         ) AS minimum_rank
  FROM bank_metric_daily d
  JOIN bank_organization o ON o.org_code = d.org_code
  WHERE d.data_date BETWEEN {_sql_literal(start_date)} AND {_sql_literal(end_date)}
    AND d.metric_code = {_sql_literal(metric_code)}
), extrema AS (
  SELECT org_code, org_name, metric_code, metric_value, 1 AS rank_position,
         0 AS result_order
  FROM ranked
  WHERE maximum_rank = 1
  UNION ALL
  SELECT org_code, org_name, metric_code, metric_value, 1 AS rank_position,
         1 AS result_order
  FROM ranked
  WHERE minimum_rank = 1
)
SELECT org_code, org_name, metric_code, metric_value, rank_position
FROM extrema
ORDER BY result_order"""
    return GoldSqlSpec(
        sql, sql, ["RANKING", "WINDOW_RANK", "DATE_RANGE", "EXTREMA", "TOP_BOTTOM"]
    )


def _annual_daily_statistics_query(record: dict[str, Any], metric_code: str) -> GoldSqlSpec:
    start_date, end_date = _date_range(record)
    organizations = _organization_codes(record)
    if len(organizations) != 1:
        raise GoldSqlError(
            f"Annual daily statistics require one organization for {record.get('id')}"
        )
    sql = f"""SELECT o.org_code, o.org_name, d.metric_code,
       AVG(d.metric_value) AS aggregate_value,
       MIN(d.metric_value) AS min_value,
       MAX(d.metric_value) AS max_value,
       COUNT(*) AS observation_count
FROM bank_metric_daily d
JOIN bank_organization o ON o.org_code = d.org_code
WHERE d.data_date BETWEEN {_sql_literal(start_date)} AND {_sql_literal(end_date)}
  AND d.metric_code = {_sql_literal(metric_code)}
  AND d.org_code = {_sql_literal(organizations[0])}
GROUP BY o.org_code, o.org_name, d.metric_code"""
    return GoldSqlSpec(
        sql, sql, ["AGGREGATION", "DATE_RANGE", "AVERAGE", "EXTREMA"]
    )


def _is_subset_winner(question: str, organizations: list[str]) -> bool:
    return len(organizations) > 1 and "谁" in question and any(
        token in question for token in ("最高", "最低", "最多", "最少", "最大", "最小")
    )


def _ranking_query(record: dict[str, Any]) -> GoldSqlSpec:
    derived_metrics = _derived_metrics(record)
    metrics = record.get("normalizedIntent", {}).get("metrics", [])
    try:
        metric_codes = _metric_codes(record)
    except GoldSqlError:
        # 仅当 metrics 确为 list 且为空（题干文本推断也失败）时保留历史
        # 状态指标回退；任何非 list 值（""、None、{} 等）或非空但非法的
        # list 都必须 fail closed re-raise，绝不生成回退 ranking SQL。
        if isinstance(metrics, list) and not metrics:
            metric_codes = _status_metrics()
        else:
            raise
    if len(metric_codes) != 1 or derived_metrics:
        return _multi_metric_rank_query(record, metric_codes or _status_metrics(), derived_metrics)
    metric_code = metric_codes[0]
    direction = _rank_direction(record, metric_code)
    question = str(record.get("question", ""))
    organizations = _organization_codes(record)
    if (
        "全年" in question
        and "日均" in question
        and "最高日" in question
        and "最低日" in question
    ):
        return _annual_daily_statistics_query(record, metric_code)
    if (
        "全年" in question
        and "单日最高" in question
        and "单日最低" in question
    ):
        return _annual_daily_extrema_query(record, metric_code)
    top_and_bottom = _top_and_bottom_limits(question)
    if top_and_bottom and "全年" in question and any(token in question for token in ("均值", "日均", "平均")):
        return _annual_average_top_bottom_rank_query(
            record, metric_code, *top_and_bottom, direction
        )
    limit = _rank_limit(question)
    subset_winner = _is_subset_winner(question, organizations)
    if subset_winner:
        result_filter = "rank_position = 1"
    elif organizations:
        result_filter = "org_code IN (" + ", ".join(_sql_literal(code) for code in organizations) + ")"
    elif _bottom_rank_requested(question):
        result_filter = f"rank_position > total_count - {limit}"
    else:
        result_filter = f"rank_position <= {limit}"
    ranking_organizations = organizations if subset_winner else []
    sql = f"""WITH ranked AS (
  SELECT o.org_code, o.org_name, d.metric_code, d.metric_value,
         ROW_NUMBER() OVER (ORDER BY metric_value {direction}, o.org_code) AS rank_position,
         COUNT(*) OVER () AS total_count
  FROM bank_metric_daily d
  JOIN bank_organization o ON o.org_code = d.org_code
  WHERE {_where(_date_from_record(record), metric_code, ranking_organizations)}
)
SELECT org_code, org_name, metric_code, metric_value, rank_position
FROM ranked
WHERE {result_filter}
ORDER BY rank_position{" DESC" if _bottom_rank_requested(question) else ""}"""
    if not organizations and limit == 1 and not _bottom_rank_requested(question):
        sql = sql.replace("rank_position <= 1", "rank_position = 1")
    return GoldSqlSpec(sql, sql, ["RANKING", "WINDOW_RANK"])


def _derived_ratio_rank_query(
    record: dict[str, Any], derived: dict[str, Any], date_value: str, organizations: list[str]
) -> str:
    """Rank one declared derived ratio (numerator/denominator*100).

    The stable metric code is ``DERIVED_<numerator>_DIV_<denominator>``, the
    ratio ranks by ``metric_value DESC, org_code`` (tie-break), and a zero or
    missing denominator yields NULL via ``NULLIF``.  Rows without a valid
    ratio are filtered out before ranking, so NULLs can never rank ahead of
    valid values; when no requested organization has a valid ratio the
    derived query returns no rows.  Output columns match the existing
    multi-metric rank contract: ``metric_code, org_code, org_name,
    metric_value, rank_position``.
    """
    numerator = derived["numerator"]
    denominator = derived["denominator"]
    metric_code = derived["metricCode"]
    selected = ""
    if organizations:
        selected = " WHERE org_code IN (" + ", ".join(_sql_literal(code) for code in organizations) + ")"
    return f"""SELECT {_sql_literal(metric_code)} AS metric_code, org_code, org_name, metric_value, rank_position
FROM (
  SELECT org_code, org_name, metric_value,
         ROW_NUMBER() OVER (ORDER BY metric_value DESC, org_code) AS rank_position
  FROM (
    SELECT o.org_code, o.org_name,
           MAX(CASE WHEN d.metric_code = {_sql_literal(numerator)} THEN d.metric_value END) * 100.0 /
               NULLIF(MAX(CASE WHEN d.metric_code = {_sql_literal(denominator)} THEN d.metric_value END), 0) AS metric_value
    FROM bank_metric_daily d
    JOIN bank_organization o ON o.org_code = d.org_code
    WHERE d.data_date = {_sql_literal(date_value)}
      AND d.metric_code IN ({_sql_literal(numerator)}, {_sql_literal(denominator)})
    GROUP BY o.org_code, o.org_name
  ) ratios
  WHERE ratios.metric_value IS NOT NULL
) ranked{selected}"""


def _multi_metric_rank_query(
    record: dict[str, Any], metric_codes: list[str], derived_metrics: list[dict[str, Any]] | None = None
) -> GoldSqlSpec:
    date_value = _date_from_record(record)
    organizations = _organization_codes(record)
    queries: list[str] = []
    for metric_code in metric_codes:
        direction = "ASC" if metric_code in LOWER_IS_BETTER else "DESC"
        selected = ""
        if organizations:
            selected = " WHERE org_code IN (" + ", ".join(_sql_literal(code) for code in organizations) + ")"
        queries.append(
            f"""SELECT {_sql_literal(metric_code)} AS metric_code, org_code, org_name, metric_value, rank_position
FROM (
  SELECT o.org_code, o.org_name, d.metric_value,
         ROW_NUMBER() OVER (ORDER BY d.metric_value {direction}) AS rank_position
  FROM bank_metric_daily d
  JOIN bank_organization o ON o.org_code = d.org_code
  WHERE d.data_date = {_sql_literal(date_value)} AND d.metric_code = {_sql_literal(metric_code)}
) ranked{selected}"""
        )
    for derived in derived_metrics or []:
        queries.append(_derived_ratio_rank_query(record, derived, date_value, organizations))
    sql = "\nUNION ALL\n".join(queries) + "\nORDER BY metric_code, org_code"
    features = ["RANKING", "WINDOW_RANK", "MULTI_METRIC"]
    if derived_metrics:
        features.append("DERIVED_METRIC")
    return GoldSqlSpec(sql, sql, features)


def _previous_month(value: date) -> date:
    if value.month == 1:
        return _month_end(value.year - 1, 12)
    return _month_end(value.year, value.month - 1)


def _previous_quarter(value: date) -> date:
    current_quarter = (value.month - 1) // 3 + 1
    if current_quarter == 1:
        return date(value.year - 1, 12, 31)
    return _month_end(value.year, (current_quarter - 1) * 3)


def _baseline_date(record: dict[str, Any], current_date: str) -> str:
    question = str(record.get("question", ""))
    expressions = [str(value) for value in record.get("normalizedIntent", {}).get("time", {}).get("expressions", [])]
    current = date.fromisoformat(current_date)
    for expression in expressions:
        if "2024年末" in expression or "年初" in expression:
            return date(current.year - 1, 12, 31).isoformat()
        if "去年同期" in expression or "同比" in expression:
            return current.replace(year=current.year - 1).isoformat()
        try:
            candidate = resolve_date(expression, question)
        except GoldSqlError:
            continue
        if candidate != current_date:
            return candidate
    if any(token in question for token in ("上个月", "上月")):
        return _previous_month(current).isoformat()
    if any(token in question for token in ("上季度", "上季")):
        return _previous_quarter(current).isoformat()
    if any(token in question for token in ("同比", "去年同期")):
        # The supplied benchmark starts on 2024-12-31.  For 2025 questions,
        # "去年同期" is therefore defined by the source workbook as its
        # available opening baseline rather than a non-existent 2024 daily row.
        if current.year == 2025:
            return "2024-12-31"
        return current.replace(year=current.year - 1).isoformat()
    if any(token in question for token in ("年初", "年末")):
        return date(current.year - 1, 12, 31).isoformat()
    raise GoldSqlError(f"Cannot resolve baseline date for {record.get('id')}")


def _mom_yoy_change_query(record: dict[str, Any], current_date: str) -> GoldSqlSpec:
    metric_code = _metric_codes(record)[0]
    org_code = _organization_codes(record)[0]
    current = date.fromisoformat(current_date)
    month_baseline = _previous_month(current).isoformat()
    year_baseline = current.replace(year=current.year - 1).isoformat()
    sql = f"""WITH values_at_dates AS (
  SELECT data_date, metric_value
  FROM bank_metric_daily
  WHERE org_code = {_sql_literal(org_code)}
    AND metric_code = {_sql_literal(metric_code)}
    AND data_date IN (
      {_sql_literal(current_date)}, {_sql_literal(month_baseline)}, {_sql_literal(year_baseline)}
    )
), current_row AS (
  SELECT metric_value AS current_value
  FROM values_at_dates
  WHERE data_date = {_sql_literal(current_date)}
), comparisons AS (
  SELECT 1 AS comparison_order, current_value,
         (SELECT metric_value FROM values_at_dates WHERE data_date = {_sql_literal(month_baseline)}) AS baseline_value
  FROM current_row
  UNION ALL
  SELECT 2 AS comparison_order, current_value,
         (SELECT metric_value FROM values_at_dates WHERE data_date = {_sql_literal(year_baseline)}) AS baseline_value
  FROM current_row
)
SELECT current_value, baseline_value,
       current_value - baseline_value AS absolute_change,
       CASE WHEN baseline_value = 0 THEN NULL
            ELSE (current_value - baseline_value) * 100.0 / baseline_value END AS percent_change
FROM comparisons
ORDER BY comparison_order"""
    return GoldSqlSpec(sql, sql, ["CHANGE", "BASELINE_COMPARISON", "MOM_YOY"])


def _change_query(record: dict[str, Any]) -> GoldSqlSpec:
    question = str(record.get("question", ""))
    if "逐季" in question or re.search(r"20\d{2}\s*(?:年)?\s*Q1", question, flags=re.IGNORECASE):
        return _trend_query(record)
    metric_codes = _metric_codes(record)
    current_date = _latest_date_from_record(record)
    if "到年末" in question or "到年底" in question:
        year_match = re.search(r"(20\d{2})年", question)
        if year_match:
            current_date = f"{year_match.group(1)}-12-31"
    organizations = _organization_codes(record)
    if (
        "环比" in question
        and "同比" in question
        and len(metric_codes) == 1
        and len(organizations) == 1
    ):
        return _mom_yoy_change_query(record, current_date)
    baseline_date = _baseline_date(record, current_date)
    if len(metric_codes) != 1 or len(organizations) != 1:
        return _multi_change_query(record, metric_codes, current_date, baseline_date)
    metric_code, org_code = metric_codes[0], organizations[0]
    sql = f"""WITH values_at_dates AS (
  SELECT data_date, metric_value
  FROM bank_metric_daily
  WHERE org_code = {_sql_literal(org_code)}
    AND metric_code = {_sql_literal(metric_code)}
    AND data_date IN ({_sql_literal(current_date)}, {_sql_literal(baseline_date)})
), current_row AS (
  SELECT metric_value AS current_value FROM values_at_dates WHERE data_date = {_sql_literal(current_date)}
), baseline_row AS (
  SELECT metric_value AS baseline_value FROM values_at_dates WHERE data_date = {_sql_literal(baseline_date)}
)
SELECT current_value, baseline_value,
       current_value - baseline_value AS absolute_change,
       CASE WHEN baseline_value = 0 THEN NULL
            ELSE (current_value - baseline_value) * 100.0 / baseline_value END AS percent_change
FROM current_row CROSS JOIN baseline_row"""
    return GoldSqlSpec(sql, sql, ["CHANGE", "BASELINE_COMPARISON"])


def _multi_change_query(
    record: dict[str, Any], metric_codes: list[str], current_date: str, baseline_date: str
) -> GoldSqlSpec:
    if not metric_codes:
        metric_codes = _status_metrics()
    metric_filter = ", ".join(_sql_literal(code) for code in metric_codes)
    organizations = _organization_codes(record)
    org_filter = ""
    if organizations:
        org_filter = " AND org_code IN (" + ", ".join(_sql_literal(code) for code in organizations) + ")"
    sql = f"""WITH values_at_dates AS (
  SELECT data_date, org_code, metric_code, metric_value
  FROM bank_metric_daily
  WHERE data_date IN ({_sql_literal(current_date)}, {_sql_literal(baseline_date)})
    AND metric_code IN ({metric_filter}){org_filter}
), pivoted AS (
  SELECT org_code, metric_code,
         MAX(CASE WHEN data_date = {_sql_literal(current_date)} THEN metric_value END) AS current_value,
         MAX(CASE WHEN data_date = {_sql_literal(baseline_date)} THEN metric_value END) AS baseline_value
  FROM values_at_dates
  GROUP BY org_code, metric_code
)
SELECT o.org_code, o.org_name, p.metric_code, p.current_value, p.baseline_value,
       p.current_value - p.baseline_value AS absolute_change,
       CASE WHEN p.baseline_value = 0 THEN NULL
            ELSE (p.current_value - p.baseline_value) * 100.0 / p.baseline_value END AS percent_change
FROM pivoted p
JOIN bank_organization o ON o.org_code = p.org_code
ORDER BY p.metric_code, p.org_code"""
    return GoldSqlSpec(sql, sql, ["CHANGE", "BASELINE_COMPARISON", "MULTI_METRIC"])


def _ratio_operands(record: dict[str, Any]) -> tuple[str, str] | None:
    question = str(record.get("question", ""))
    patterns = (
        ("存贷比", ("ZB002", "ZB001")),
        ("净利润率", ("ZB011", "ZB009")),
        ("对公贷款", ("ZB005", "ZB002")),
        ("个人贷款", ("ZB006", "ZB002")),
        ("对公存款", ("ZB003", "ZB001")),
        ("个人存款", ("ZB004", "ZB001")),
        ("不良贷款余额", ("ZB014", "ZB002")),
        ("净利息收入", ("ZB008", "ZB009")),
        ("中间业务收入", ("ZB007", "ZB009")),
    )
    for token, operands in patterns:
        if token in question:
            return operands
    return None


def _ratio_query(record: dict[str, Any]) -> GoldSqlSpec:
    operands = _ratio_operands(record)
    metric_codes = _metric_codes(record)
    if operands is None:
        if len(metric_codes) == 1:
            return _point_query(record)
        if len(metric_codes) == 2:
            operands = (metric_codes[0], metric_codes[1])
        else:
            raise GoldSqlError(f"Cannot determine ratio operands for {record.get('id')}")
    numerator, denominator = operands
    date_value = _date_from_record(record)
    organizations = _organization_codes(record)
    org_terms = ""
    if organizations:
        org_terms = " AND d.org_code IN (" + ", ".join(_sql_literal(code) for code in organizations) + ")"
    sql = f"""SELECT o.org_code, o.org_name,
       MAX(CASE WHEN d.metric_code = {_sql_literal(numerator)} THEN d.metric_value END) AS numerator_value,
       MAX(CASE WHEN d.metric_code = {_sql_literal(denominator)} THEN d.metric_value END) AS denominator_value,
       CASE WHEN MAX(CASE WHEN d.metric_code = {_sql_literal(denominator)} THEN d.metric_value END) = 0 THEN NULL
            ELSE MAX(CASE WHEN d.metric_code = {_sql_literal(numerator)} THEN d.metric_value END) * 100.0 /
                 MAX(CASE WHEN d.metric_code = {_sql_literal(denominator)} THEN d.metric_value END) END AS ratio_percent
FROM bank_metric_daily d
JOIN bank_organization o ON o.org_code = d.org_code
WHERE d.data_date = {_sql_literal(date_value)}
  AND d.metric_code IN ({_sql_literal(numerator)}, {_sql_literal(denominator)}){org_terms}
GROUP BY o.org_code, o.org_name
ORDER BY o.org_code"""
    return GoldSqlSpec(sql, sql, ["RATIO", "DERIVED_METRIC"])


def _comparison_query(record: dict[str, Any]) -> GoldSqlSpec:
    metric_codes = _metric_codes(record)
    date_value = _date_from_record(record)
    organizations = _organization_codes(record)
    if len(metric_codes) != 1 or len(organizations) < 2:
        return _point_query(record)
    metric_code = metric_codes[0]
    org_list = ", ".join(_sql_literal(code) for code in organizations)
    sql = f"""WITH selected AS (
  SELECT o.org_code, o.org_name, d.metric_value
  FROM bank_metric_daily d
  JOIN bank_organization o ON o.org_code = d.org_code
  WHERE d.data_date = {_sql_literal(date_value)}
    AND d.metric_code = {_sql_literal(metric_code)}
    AND d.org_code IN ({org_list})
)
SELECT org_code, org_name, metric_value,
       MAX(metric_value) OVER () - MIN(metric_value) OVER () AS value_difference
FROM selected
ORDER BY metric_value DESC, org_code"""
    return GoldSqlSpec(sql, sql, ["COMPARISON", "WINDOW_AGGREGATE"])


def _date_range(record: dict[str, Any]) -> tuple[str, str]:
    question = str(record.get("question", ""))
    expressions = " ".join(str(value) for value in record.get("normalizedIntent", {}).get("time", {}).get("expressions", []))
    text = expressions + " " + question
    year = re.search(r"(20\d{2})年全年", text)
    if year:
        return f"{year.group(1)}-01-01", f"{year.group(1)}-12-31"
    quarter = re.search(r"(20\d{2})年([一二三四])季度", text)
    if quarter:
        month = {"一": 3, "二": 6, "三": 9, "四": 12}[quarter.group(2)]
        start = date(int(quarter.group(1)), month - 2, 1)
        return start.isoformat(), _month_end(int(quarter.group(1)), month).isoformat()
    value = _date_from_record(record)
    return value, value


def _aggregation_query(record: dict[str, Any]) -> GoldSqlSpec:
    metric_codes = _metric_codes(record)
    question = str(record.get("question", ""))
    start_date, end_date = _date_range(record)
    organizations = _organization_codes(record)
    metric_filter = ", ".join(_sql_literal(code) for code in metric_codes)
    org_filter = ""
    if organizations:
        org_filter = " AND d.org_code IN (" + ", ".join(_sql_literal(code) for code in organizations) + ")"
    if "网点平均存款" in question:
        metric_codes = ["ZB001", "ZB019"]
        metric_filter = ", ".join(_sql_literal(code) for code in metric_codes)
        sql = f"""SELECT o.org_code, o.org_name,
       MAX(CASE WHEN d.metric_code = 'ZB001' THEN d.metric_value END) AS deposit_value,
       MAX(CASE WHEN d.metric_code = 'ZB019' THEN d.metric_value END) AS outlet_count,
       MAX(CASE WHEN d.metric_code = 'ZB001' THEN d.metric_value END) * 10000.0 /
       NULLIF(MAX(CASE WHEN d.metric_code = 'ZB019' THEN d.metric_value END), 0) AS deposit_per_outlet_wanyuan
FROM bank_metric_daily d
JOIN bank_organization o ON o.org_code = d.org_code
WHERE d.data_date = {_sql_literal(end_date)} AND d.metric_code IN ({metric_filter}){org_filter}
GROUP BY o.org_code, o.org_name"""
        return GoldSqlSpec(sql, sql, ["AGGREGATION", "DERIVED_METRIC"])
    aggregate = "AVG" if any(token in question for token in ("均值", "日均", "平均")) else "SUM"
    sql = f"""SELECT o.org_code, o.org_name, d.metric_code,
       {aggregate}(d.metric_value) AS aggregate_value,
       MIN(d.metric_value) AS min_value,
       MAX(d.metric_value) AS max_value,
       COUNT(*) AS observation_count
FROM bank_metric_daily d
JOIN bank_organization o ON o.org_code = d.org_code
WHERE d.data_date BETWEEN {_sql_literal(start_date)} AND {_sql_literal(end_date)}
  AND d.metric_code IN ({metric_filter}){org_filter}
GROUP BY o.org_code, o.org_name, d.metric_code
ORDER BY d.metric_code, aggregate_value DESC, o.org_code"""
    return GoldSqlSpec(sql, sql, ["AGGREGATION", "DATE_RANGE"])


def _threshold_query(record: dict[str, Any]) -> GoldSqlSpec:
    metric_codes = _metric_codes(record)
    if len(metric_codes) > 1 and "同时" in str(record.get("question", "")):
        return _multi_metric_threshold_query(record, metric_codes)
    metric_code = metric_codes[0]
    date_value = _date_from_record(record)
    question = str(record.get("question", ""))
    organizations = _organization_codes(record)
    if "全省" in question or "均值" in question:
        compare = "<" if any(token in question for token in ("低于", "小于")) else ">"
        sql = f"""WITH values_at_date AS (
  SELECT o.org_code, o.org_name, d.metric_value
  FROM bank_metric_daily d
  JOIN bank_organization o ON o.org_code = d.org_code
  WHERE d.data_date = {_sql_literal(date_value)} AND d.metric_code = {_sql_literal(metric_code)}
), provincial AS (SELECT AVG(metric_value) AS provincial_average FROM values_at_date)
SELECT v.org_code, v.org_name, v.metric_value, p.provincial_average,
       CASE WHEN v.metric_value {compare} p.provincial_average THEN 1 ELSE 0 END AS meets_condition
FROM values_at_date v CROSS JOIN provincial p
ORDER BY v.org_code"""
        return GoldSqlSpec(sql, sql, ["THRESHOLD", "PROVINCIAL_AVERAGE"])
    threshold_match = re.search(r"(?:超过|高于|达到|满足|不低于)\s*(\d+(?:\.\d+)?)(?:%|人|家|个)?", question)
    if not threshold_match:
        raise GoldSqlError(f"Cannot find a numeric threshold for {record.get('id')}")
    threshold = threshold_match.group(1)
    sql = f"""SELECT o.org_code, o.org_name, d.metric_code, d.metric_value,
       CASE WHEN d.metric_value >= {threshold} THEN 1 ELSE 0 END AS meets_condition
FROM bank_metric_daily d
JOIN bank_organization o ON o.org_code = d.org_code
WHERE {_where(date_value, metric_code, organizations)}"""
    return GoldSqlSpec(sql, sql, ["THRESHOLD"])


def _multi_metric_threshold_query(record: dict[str, Any], metric_codes: list[str]) -> GoldSqlSpec:
    if len(metric_codes) != 2:
        raise GoldSqlError(f"Multi-metric threshold needs exactly two metrics for {record.get('id')}")
    date_value = _date_from_record(record)
    first, second = metric_codes
    first_operator = "<" if first in LOWER_IS_BETTER else ">"
    second_operator = ">" if second in LOWER_IS_BETTER else ">"
    sql = f"""WITH values_at_date AS (
  SELECT org_code,
         MAX(CASE WHEN metric_code = {_sql_literal(first)} THEN metric_value END) AS first_value,
         MAX(CASE WHEN metric_code = {_sql_literal(second)} THEN metric_value END) AS second_value
  FROM bank_metric_daily
  WHERE data_date = {_sql_literal(date_value)}
    AND metric_code IN ({_sql_literal(first)}, {_sql_literal(second)})
  GROUP BY org_code
), provincial AS (
  SELECT AVG(first_value) AS first_average, AVG(second_value) AS second_average FROM values_at_date
)
SELECT o.org_code, o.org_name, v.first_value, p.first_average, v.second_value, p.second_average,
       CASE WHEN v.first_value {first_operator} p.first_average
                  AND v.second_value {second_operator} p.second_average THEN 1 ELSE 0 END AS meets_condition
FROM values_at_date v
JOIN bank_organization o ON o.org_code = v.org_code
CROSS JOIN provincial p
ORDER BY o.org_code"""
    return GoldSqlSpec(sql, sql, ["THRESHOLD", "PROVINCIAL_AVERAGE", "MULTI_METRIC"])


def _trend_query(record: dict[str, Any]) -> GoldSqlSpec:
    metric_codes = _metric_codes(record)
    if len(metric_codes) != 1:
        raise GoldSqlError(f"TREND requires one metric for {record.get('id')}")
    organizations = _organization_codes(record)
    if len(organizations) != 1:
        raise GoldSqlError(f"TREND requires one organization for {record.get('id')}")
    question = str(record.get("question", ""))
    years = [int(value) for value in re.findall(r"(20\d{2})\s*(?:年)?\s*[Qq一二三四]", question)]
    if not years:
        years = [2025, 2026]
    quarter_dates = [f"{years[0]}-03-31", f"{years[0]}-06-30", f"{years[0]}-09-30", f"{years[0]}-12-31", f"{years[-1]}-03-31"]
    sql = f"""SELECT data_date, metric_value,
       metric_value - LAG(metric_value) OVER (ORDER BY data_date) AS quarter_change
FROM bank_metric_daily
WHERE org_code = {_sql_literal(organizations[0])}
  AND metric_code = {_sql_literal(metric_codes[0])}
  AND data_date IN ({', '.join(_sql_literal(value) for value in quarter_dates)})
ORDER BY data_date"""
    return GoldSqlSpec(sql, sql, ["TREND", "WINDOW_LAG"])


def build_gold_sql(record: dict[str, Any]) -> GoldSqlSpec:
    """Return a gold query for a supported frozen benchmark sample."""

    intent = record.get("normalizedIntent", {}).get("intent")
    if intent == "POINT_QUERY":
        return _point_query(record)
    if intent == "RANKING":
        return _ranking_query(record)
    if intent == "CHANGE":
        return _change_query(record)
    if intent == "RATIO":
        return _ratio_query(record)
    if intent == "COMPARISON":
        return _comparison_query(record)
    if intent == "AGGREGATION":
        return _aggregation_query(record)
    if intent == "THRESHOLD":
        return _threshold_query(record)
    if intent == "TREND":
        return _trend_query(record)
    raise GoldSqlError(f"Unsupported intent for {record.get('id')}: {intent}")
