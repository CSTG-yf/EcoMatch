#!/usr/bin/env python3
"""Build the DATA-02 executable NL2SQL dataset from the competition workbook."""

from __future__ import annotations

import argparse
import hashlib
import json
import sqlite3
from collections import Counter
from datetime import date
from pathlib import Path
from typing import Any, Iterable

from openpyxl import load_workbook

ROOT = Path(__file__).resolve().parents[2]
OUTPUT_DIR = Path(__file__).resolve().parent
SPLITS = ("train", "dev", "test")
FEATURES = ("SINGLE_TABLE", "MULTI_TABLE", "NESTED_QUERY", "AGGREGATION", "YOY", "MOM", "TOP_N", "CROSS_ORGANIZATION")
ERROR_TYPES = ("MAPPING_ERROR", "DEFINITION_ERROR", "JOIN_ERROR", "FILTER_ERROR", "SYNTAX_ERROR", "EXECUTION_ERROR")


def find_workbook() -> Path:
    candidates = sorted((ROOT / "task").glob("*.xlsx"))
    if len(candidates) != 1:
        raise RuntimeError(f"expected exactly one competition workbook, found {len(candidates)}")
    return candidates[0]


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def workbook_rows(workbook: Path):
    book = load_workbook(workbook, read_only=True, data_only=True)
    organizations = [tuple(row) for row in list(book["机构信息表"].iter_rows(values_only=True))[1:]]
    metrics = [tuple(row) for row in list(book["指标清单表"].iter_rows(values_only=True))[1:]]
    facts = []
    rows = book["指标数据表"].iter_rows(values_only=True)
    next(rows)
    for data_date, metric_code, _metric_name, organization_code, metric_value in rows:
        facts.append((str(data_date), str(organization_code), str(metric_code), float(metric_value)))
    book.close()
    return organizations, metrics, facts


def create_database(organizations, metrics, facts, database: str = ":memory:") -> sqlite3.Connection:
    connection = sqlite3.connect(database)
    connection.executescript((OUTPUT_DIR / "schema.sql").read_text(encoding="utf-8"))
    connection.executemany("INSERT INTO bank_organization_dim VALUES (?, ?)", organizations)
    connection.executemany("INSERT INTO bank_metric_dim VALUES (?, ?, ?, ?)", metrics)
    connection.executemany("INSERT INTO bank_indicator_fact VALUES (?, ?, ?, ?)", facts)
    connection.commit()
    return connection


def metric_expr(code: str, date_filter: str | None = None) -> str:
    date_clause = f" AND data_date = '{date_filter}'" if date_filter else ""
    return f"MAX(CASE WHEN metric_code = '{code}'{date_clause} THEN metric_value END)"


def normalized_intent(intent: str, metric, organizations, dates, filters=None) -> dict[str, Any]:
    code, name, _description, _unit = metric
    risk_metrics = {"ZB013", "ZB014", "ZB015", "ZB016", "ZB017"}
    return {
        "scene": "RISK_CONTROL" if code in risk_metrics else "OPERATION_ANALYSIS",
        "intent": intent,
        "metrics": [{"code": code, "bizName": code.lower(), "name": name}],
        "dimensions": ["bank_data_date", "bank_organization"],
        "time": {"resolvedDates": dates},
        "organizations": [{"code": item[0], "name": item[1]} for item in organizations],
        "filters": filters or [],
        "clarificationExpected": False,
    }


def execute(connection: sqlite3.Connection, sql: str) -> dict[str, Any]:
    cursor = connection.execute(sql)
    columns = [item[0] for item in cursor.description or []]
    rows = [[round(value, 6) if isinstance(value, float) else value for value in row] for row in cursor.fetchall()]
    canonical = json.dumps({"columns": columns, "rows": rows}, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return {
        "columns": columns,
        "rowCount": len(rows),
        "rows": rows[:20],
        "sha256": hashlib.sha256(canonical.encode("utf-8")).hexdigest(),
    }


def build_case(connection, sample_id, split, template_group, question, difficulty, intent, s2sql, physical_sql, complexity, unit):
    result = execute(connection, physical_sql)
    if not result["rowCount"]:
        raise RuntimeError(f"{sample_id} returned no rows")
    preview = json.dumps(result["rows"][:3], ensure_ascii=False, separators=(",", ":"))
    summary = f"返回{result['rowCount']}行；字段：{', '.join(result['columns'])}；前3行：{preview}；指标单位：{unit}"
    return {
        "id": sample_id, "source": "competition_workbook", "split": split,
        "templateGroup": template_group, "difficulty": difficulty, "question": question,
        "normalizedIntent": intent, "s2sql": s2sql, "physicalSql": physical_sql,
        "sqlDialect": "sqlite", "complexity": complexity,
        "expectedResultSummary": summary, "expectedResult": result,
    }


QUESTION_TEMPLATES = {
    "train": {
        "point": "{date}{org}的{metric}是多少？", "join": "请列出{date}{org}的{metric}、指标编码和单位。",
        "nested": "{date}{metric}高于全省机构均值的农商行有哪些？", "aggregate": "{org}在{start}至{date}期间的{metric}日均值是多少？",
        "yoy": "{org}{date}的{metric}同比增幅是多少？", "mom": "计算{org}{date}{metric}的环比变化率。",
        "topn": "{date}{metric}最高的前3家机构是哪些？", "cross": "对比{date}{org1}与{org2}的{metric}。",
    },
    "dev": {
        "point": "查询{org}在{date}的{metric}报表值。", "join": "{org}{date}{metric}的元数据及数值明细。",
        "nested": "找出{date}{metric}超过当日机构平均水平的机构。", "aggregate": "统计{start}到{date}{org}{metric}的平均水平。",
        "yoy": "比较{org}{metric}在{date}和上年同期的变化百分比。", "mom": "{date}{org}{metric}较上月末变动百分之多少？",
        "topn": "按{metric}从高到低给出{date}前三家农商行。", "cross": "{date}{org1}和{org2}谁的{metric}更高？",
    },
    "test": {
        "point": "调取{date}{org}{metric}数据。", "join": "返回{date}{org}{metric}及其口径单位。",
        "nested": "哪些机构在{date}的{metric}位于全省均值之上？", "aggregate": "{org}{start}至{date}{metric}均值查询。",
        "yoy": "{date}{org}{metric}比去年同期增长或下降多少？", "mom": "分析{org}{metric}截至{date}的月度环比。",
        "topn": "查看{date}全省{metric}Top3机构。", "cross": "给出{org1}、{org2}在{date}的{metric}对照表。",
    },
}


def family_sql(family, metric, org, org2, data_date, previous_month, previous_year):
    code, metric_name, _description, _unit = metric
    biz = code.lower()
    org_name = org[1].replace("'", "''")
    org2_name = org2[1].replace("'", "''")
    start = data_date[:8] + "01"
    if family == "point":
        s2sql = f"SELECT bank_data_date, bank_organization, {biz} FROM bank_indicator_dataset WHERE bank_data_date = '{data_date}' AND bank_organization = '{org_name}'"
        sql = f"SELECT data_date, organization_code, ROUND({metric_expr(code)}, 6) AS metric_value FROM bank_indicator_fact WHERE data_date = '{data_date}' AND organization_code = '{org[0]}' GROUP BY data_date, organization_code"
        return s2sql, sql, "POINT_QUERY", "简单", ["SINGLE_TABLE"], [org], [data_date], []
    if family == "join":
        s2sql = f"SELECT bank_data_date, bank_organization, {biz} FROM bank_indicator_dataset WHERE bank_data_date = '{data_date}' AND bank_organization = '{org_name}'"
        sql = f"SELECT f.data_date, o.organization_name, m.metric_code, m.metric_name, m.metric_unit, ROUND(f.metric_value, 6) AS metric_value FROM bank_indicator_fact f JOIN bank_organization_dim o ON o.organization_code = f.organization_code JOIN bank_metric_dim m ON m.metric_code = f.metric_code WHERE f.data_date = '{data_date}' AND f.organization_code = '{org[0]}' AND f.metric_code = '{code}'"
        return s2sql, sql, "POINT_QUERY", "简单", ["MULTI_TABLE"], [org], [data_date], []
    if family == "nested":
        s2sql = f"SELECT bank_organization, {biz} FROM bank_indicator_dataset WHERE bank_data_date = '{data_date}' AND {biz} > (SELECT AVG({biz}) FROM bank_indicator_dataset WHERE bank_data_date = '{data_date}') ORDER BY {biz} DESC"
        sql = f"SELECT organization_code, ROUND(metric_value, 6) AS metric_value FROM bank_indicator_fact WHERE data_date = '{data_date}' AND metric_code = '{code}' AND metric_value > (SELECT AVG(metric_value) FROM bank_indicator_fact WHERE data_date = '{data_date}' AND metric_code = '{code}') ORDER BY metric_value DESC, organization_code"
        filters = [{"operator": "COMPARE", "value": "PROVINCE_AVERAGE"}]
        return s2sql, sql, "THRESHOLD", "普通", ["SINGLE_TABLE", "NESTED_QUERY", "AGGREGATION", "CROSS_ORGANIZATION"], [], [data_date], filters
    if family == "aggregate":
        s2sql = f"SELECT AVG({biz}) AS avg_value FROM bank_indicator_dataset WHERE bank_data_date >= '{start}' AND bank_data_date <= '{data_date}' AND bank_organization = '{org_name}'"
        sql = f"SELECT ROUND(AVG(metric_value), 6) AS avg_value FROM bank_indicator_fact WHERE data_date >= '{start}' AND data_date <= '{data_date}' AND organization_code = '{org[0]}' AND metric_code = '{code}'"
        return s2sql, sql, "AGGREGATION", "普通", ["SINGLE_TABLE", "AGGREGATION"], [org], [start, data_date], []
    if family in {"yoy", "mom"}:
        base_date = previous_year if family == "yoy" else previous_month
        alias, feature = ("yoy_rate", "YOY") if family == "yoy" else ("mom_rate", "MOM")
        s2sql = f"WITH period_values AS (SELECT bank_data_date, {biz} FROM bank_indicator_dataset WHERE bank_organization = '{org_name}' AND bank_data_date IN ('{base_date}', '{data_date}')) SELECT ROUND((MAX(CASE WHEN bank_data_date = '{data_date}' THEN {biz} END) / NULLIF(MAX(CASE WHEN bank_data_date = '{base_date}' THEN {biz} END), 0) - 1) * 100, 6) AS {alias} FROM period_values"
        current_expr, base_expr = metric_expr(code, data_date), metric_expr(code, base_date)
        sql = f"SELECT ROUND(({current_expr} / NULLIF({base_expr}, 0) - 1) * 100, 6) AS {alias} FROM bank_indicator_fact WHERE organization_code = '{org[0]}' AND metric_code = '{code}' AND data_date IN ('{base_date}', '{data_date}')"
        return s2sql, sql, "CHANGE", "复杂", ["SINGLE_TABLE", "NESTED_QUERY", "AGGREGATION", feature], [org], [base_date, data_date], []
    if family == "topn":
        s2sql = f"SELECT bank_organization, {biz} FROM bank_indicator_dataset WHERE bank_data_date = '{data_date}' ORDER BY {biz} DESC LIMIT 3"
        sql = f"SELECT o.organization_name, ROUND(f.metric_value, 6) AS metric_value FROM bank_indicator_fact f JOIN bank_organization_dim o ON o.organization_code = f.organization_code WHERE f.data_date = '{data_date}' AND f.metric_code = '{code}' ORDER BY f.metric_value DESC, f.organization_code LIMIT 3"
        filters = [{"operator": "TOP_N", "value": "3"}]
        return s2sql, sql, "RANKING", "普通", ["MULTI_TABLE", "TOP_N", "CROSS_ORGANIZATION"], [], [data_date], filters
    s2sql = f"SELECT bank_organization, {biz} FROM bank_indicator_dataset WHERE bank_data_date = '{data_date}' AND bank_organization IN ('{org_name}', '{org2_name}') ORDER BY {biz} DESC"
    sql = f"SELECT organization_code, ROUND(metric_value, 6) AS metric_value FROM bank_indicator_fact WHERE data_date = '{data_date}' AND metric_code = '{code}' AND organization_code IN ('{org[0]}', '{org2[0]}') ORDER BY metric_value DESC, organization_code"
    return s2sql, sql, "COMPARISON", "简单", ["SINGLE_TABLE", "CROSS_ORGANIZATION"], [org, org2], [data_date], []


def generate_cases(connection, organizations, metrics):
    target_dates = ("2026-01-31", "2026-02-28", "2026-03-31", "2026-04-30")
    previous_months = ("2025-12-31", "2026-01-31", "2026-02-28", "2026-03-31")
    previous_years = ("2025-01-31", "2025-02-28", "2025-03-31", "2025-04-30")
    selected_codes = {"ZB001", "ZB002", "ZB011", "ZB012", "ZB013", "ZB015", "ZB018", "ZB021"}
    metric_pool = [metric for metric in metrics if metric[0] in selected_codes]
    families = ("point", "join", "nested", "aggregate", "yoy", "mom", "topn", "cross")
    cases, sequence = [], 0
    for split_index, split in enumerate(SPLITS):
        for family_index, family in enumerate(families):
            for variant in range(4):
                sequence += 1
                metric = metric_pool[(family_index * 3 + split_index + variant) % len(metric_pool)]
                org = organizations[(family_index + split_index * 4 + variant) % len(organizations)]
                org2 = organizations[(family_index + split_index * 4 + variant + 5) % len(organizations)]
                data_date = target_dates[variant]
                start = data_date[:8] + "01"
                context = {"date": data_date, "org": org[1], "org1": org[1], "org2": org2[1], "metric": metric[1], "start": start}
                values = family_sql(family, metric, org, org2, data_date, previous_months[variant], previous_years[variant])
                s2sql, sql, intent_name, difficulty, features, selected_orgs, dates, filters = values
                intent = normalized_intent(intent_name, metric, selected_orgs, dates, filters)
                cases.append(build_case(connection, f"NL2SQL-{split.upper()}-{sequence:03d}", split, f"{split}-{family}-v1", QUESTION_TEMPLATES[split][family].format(**context), difficulty, intent, s2sql, sql, features, metric[3]))
    return cases


def faulty_sql(error_type, sample, metric_codes):
    sql = sample["physicalSql"]
    source_code = sample["normalizedIntent"]["metrics"][0]["code"]
    replacement = metric_codes[(metric_codes.index(source_code) + 1) % len(metric_codes)]
    if error_type == "MAPPING_ERROR":
        return f"SELECT metric_code, ROUND(AVG(metric_value), 6) AS metric_value FROM bank_indicator_fact WHERE metric_code = '{replacement}' GROUP BY metric_code", "指标被映射为另一个合法指标编码，SQL可执行但结果语义错误。"
    if error_type == "DEFINITION_ERROR":
        return f"SELECT SUM(metric_value) AS metric_value FROM bank_indicator_fact WHERE metric_code = '{source_code}'", "聚合范围和口径被扩大到全量数据。"
    if error_type == "JOIN_ERROR":
        return "SELECT f.metric_value FROM bank_indicator_fact f JOIN bank_organization_dim o ON f.organization_code = o.organization_name LIMIT 20", "事实表机构编码错误关联到机构名称。"
    if error_type == "FILTER_ERROR":
        return sql.replace("2026-", "1900-"), "日期过滤条件被替换为数据范围外日期。"
    if error_type == "SYNTAX_ERROR":
        return "SELEC metric_value FORM bank_indicator_fact", "SQL关键字拼写和顺序错误。"
    return "SELECT missing_metric_value FROM bank_indicator_fact", "引用不存在的物理字段。"


def generate_errors(cases, metric_codes):
    errors = []
    for index, sample in enumerate(cases):
        error_type = ERROR_TYPES[index % len(ERROR_TYPES)]
        sql, diagnosis = faulty_sql(error_type, sample, metric_codes)
        errors.append({"id": f"ERR-{index + 1:03d}", "sourceId": sample["id"], "split": sample["split"], "errorType": error_type, "faultySql": sql, "expectedDiagnosis": diagnosis})
    return errors


def write_jsonl(path: Path, rows: Iterable[dict[str, Any]]) -> None:
    with path.open("w", encoding="utf-8", newline="\n") as stream:
        for row in rows:
            stream.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--workbook", type=Path)
    parser.add_argument("--database", type=Path, help="also materialize the SQLite sample database")
    args = parser.parse_args()
    workbook = args.workbook or find_workbook()
    organizations, metrics, facts = workbook_rows(workbook)
    connection = create_database(organizations, metrics, facts)
    cases = generate_cases(connection, organizations, metrics)
    errors = generate_errors(cases, [metric[0] for metric in metrics])
    for split in SPLITS:
        write_jsonl(OUTPUT_DIR / f"{split}.jsonl", (case for case in cases if case["split"] == split))
    write_jsonl(OUTPUT_DIR / "error_cases.jsonl", errors)
    feature_counts = Counter(feature for case in cases for feature in case["complexity"])
    manifest = {
        "version": "1.0.0", "generatedAt": date.today().isoformat(), "sourceWorkbook": workbook.name,
        "sourceSha256": sha256_file(workbook), "counts": {split: sum(case["split"] == split for case in cases) for split in SPLITS},
        "positiveCount": len(cases), "errorCount": len(errors), "featureCounts": {feature: feature_counts[feature] for feature in FEATURES},
        "errorTypeCounts": dict(Counter(error["errorType"] for error in errors)),
        "templateOverlap": {"trainDev": 0, "trainTest": 0, "devTest": 0},
        "physicalSqlExecution": {"engine": "SQLite", "passed": len(cases), "failed": 0},
    }
    (OUTPUT_DIR / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if args.database:
        args.database.unlink(missing_ok=True)
        disk_connection = create_database(organizations, metrics, facts, str(args.database))
        disk_connection.close()
    connection.close()
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
