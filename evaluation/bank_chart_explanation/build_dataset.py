#!/usr/bin/env python3
"""Build DATA-03 chart recommendation and grounded explanation annotations."""

from __future__ import annotations

import hashlib
import importlib.util
import json
import sqlite3
from collections import Counter
from pathlib import Path
from typing import Any

OUTPUT_DIR = Path(__file__).resolve().parent
NL2SQL_DIR = OUTPUT_DIR.parent / "bank_nl2sql"
_SOURCE_SPEC = importlib.util.spec_from_file_location("bank_nl2sql_builder", NL2SQL_DIR / "build_dataset.py")
if _SOURCE_SPEC is None or _SOURCE_SPEC.loader is None:
    raise RuntimeError("Unable to load DATA-02 workbook utilities")
_SOURCE_MODULE = importlib.util.module_from_spec(_SOURCE_SPEC)
_SOURCE_SPEC.loader.exec_module(_SOURCE_MODULE)
create_database = _SOURCE_MODULE.create_database
find_workbook = _SOURCE_MODULE.find_workbook
workbook_rows = _SOURCE_MODULE.workbook_rows

SPLITS = ("train", "dev", "test")
CHART_TYPES = ("KPI_CARD", "LINE", "BAR", "PIE", "COMBO", "TABLE")
DATES = ("2025-11-30", "2025-12-31", "2026-01-31", "2026-02-28", "2026-03-31", "2026-04-30")
RISK_CODES = {"ZB013", "ZB014", "ZB015", "ZB016", "ZB017"}
CUSTOMER_CODES = {"ZB018", "ZB019", "ZB020", "ZB021"}
METRIC_POOLS = {
    "OPERATION_ANALYSIS": ("ZB001", "ZB002", "ZB009", "ZB011", "ZB012"),
    "RISK_CONTROL": ("ZB013", "ZB014", "ZB015", "ZB016", "ZB017"),
    "CUSTOMER_MARKETING": ("ZB018", "ZB019", "ZB020", "ZB021", "ZB020"),
}
PAIR_POOL = (
    ("ZB001", "ZB002"), ("ZB003", "ZB004"), ("ZB005", "ZB006"),
    ("ZB007", "ZB008"), ("ZB009", "ZB010"), ("ZB013", "ZB017"),
    ("ZB015", "ZB016"), ("ZB020", "ZB021"),
)
SCENES = tuple(METRIC_POOLS)


def dump_json(path: Path, value: Any) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def dump_jsonl(path: Path, rows: list[dict]) -> None:
    text = "\n".join(json.dumps(row, ensure_ascii=False, separators=(",", ":")) for row in rows)
    path.write_text(text + "\n", encoding="utf-8")


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def execute(connection: sqlite3.Connection, sql: str) -> dict:
    cursor = connection.execute(sql)
    columns = [item[0] for item in cursor.description or []]
    rows = [[round(value, 6) if isinstance(value, float) else value for value in row]
            for row in cursor.fetchall()]
    canonical = json.dumps({"columns": columns, "rows": rows}, ensure_ascii=False,
                           sort_keys=True, separators=(",", ":"))
    return {"columns": columns, "rowCount": len(rows), "rows": rows,
            "sha256": hashlib.sha256(canonical.encode("utf-8")).hexdigest()}


def scene_for(code: str) -> str:
    if code in RISK_CODES:
        return "RISK_CONTROL"
    if code in CUSTOMER_CODES:
        return "CUSTOMER_MARKETING"
    return "OPERATION_ANALYSIS"


def metric_annotation(metric: tuple[str, str, str, str]) -> dict:
    code, name, definition, unit = metric
    return {"code": code, "name": name, "definition": definition, "unit": unit,
            "definitionVersion": "competition-workbook-2026-07"}


def display(value: Any) -> str:
    if isinstance(value, float):
        return f"{value:.6f}".rstrip("0").rstrip(".")
    return str(value)


def profile(result: dict, chart_type: str, metric_count: int) -> dict:
    numeric_values = [value for row in result["rows"] for value in row
                      if isinstance(value, (int, float)) and not isinstance(value, bool)]
    return {
        "rowCount": result["rowCount"],
        "columns": result["columns"],
        "dimensionCount": 1 if chart_type == "PIE" else max(0, len(result["columns"]) - metric_count),
        "metricCount": metric_count,
        "hasTimeDimension": "data_date" in result["columns"],
        "categoryCardinality": result["rowCount"] if chart_type in {"BAR", "PIE", "TABLE"} else 0,
        "hasNegativeValue": any(value < 0 for value in numeric_values),
    }


def chart_annotation(chart_type: str, row_count: int) -> dict:
    alternatives = {
        "KPI_CARD": ["TABLE"], "LINE": ["TABLE", "BAR"], "BAR": ["TABLE"],
        "PIE": ["BAR", "TABLE"], "COMBO": ["LINE", "TABLE"], "TABLE": ["BAR"],
    }[chart_type]
    rejected = {
        "KPI_CARD": ("PIE", "单个数值不存在构成占比，饼图会误导。"),
        "LINE": ("PIE", "时间序列应表达变化，不应编码为构成占比。"),
        "BAR": ("LINE", "机构类别无连续时间顺序，折线会暗示不存在的趋势。"),
        "PIE": ("LINE", "当前问题比较同一时点的构成，折线不适用。"),
        "COMBO": ("PIE", "多指标时间序列不是同一整体的构成。"),
        "TABLE": ("PIE", "明细字段和多行记录无法形成可解释的整体占比。"),
    }[chart_type]
    rationale = {
        "KPI_CARD": "单机构、单时点、单指标结果适合突出核心数值。",
        "LINE": "连续六个期末值适合展示变化方向与波动。",
        "BAR": "同一时点的机构排名适合按长度比较。",
        "PIE": "同单位、正值且类别不超过六项，适合展示所选范围内构成。",
        "COMBO": "两个同单位指标跨时间并列展示，便于比较走势与差距。",
        "TABLE": "结果包含业务元数据和精确值，表格最利于核对。",
    }[chart_type]
    return {
        "recommended": chart_type,
        "alternatives": [{"type": item, "suitability": "ACCEPTABLE"} for item in alternatives],
        "notRecommended": [{"type": rejected[0], "reason": rejected[1]}],
        "rationale": rationale,
        "confidence": 0.98 if row_count else 0.0,
    }


def risk_notices(scene: str, row_count: int) -> list[str]:
    notices = ["结论仅适用于当前查询的数据范围，不得外推到未展示机构或时间。"]
    if scene == "RISK_CONTROL":
        notices.append("风险指标解释仅用于经营分析，不替代监管报送、授信审批或风险处置。")
    if row_count < 3:
        notices.append("样本少于3个观测值，不得输出趋势、异常点或因果结论。")
    return notices


def explanation(metrics: list[tuple[str, str, str, str]], result: dict, time_range: dict,
                scope: str, scene: str, chart_type: str) -> dict:
    rows = result["rows"]
    required_claims: list[dict] = []
    if chart_type == "KPI_CARD":
        value = rows[0][-1]
        required_claims.append({"type": "VALUE", "label": metrics[0][1], "value": value,
                                "unit": metrics[0][3], "token": display(value)})
        reference = f"{time_range['end']}，{scope}{metrics[0][1]}为{display(value)}{metrics[0][3]}。"
    elif chart_type == "LINE":
        first, last = rows[0][-1], rows[-1][-1]
        required_claims.extend([
            {"type": "START_VALUE", "label": metrics[0][1], "value": first,
             "unit": metrics[0][3], "token": display(first)},
            {"type": "END_VALUE", "label": metrics[0][1], "value": last,
             "unit": metrics[0][3], "token": display(last)},
        ])
        reference = (f"{scope}{metrics[0][1]}从{time_range['start']}的{display(first)}{metrics[0][3]}"
                     f"变为{time_range['end']}的{display(last)}{metrics[0][3]}；该序列仅描述数值变化。")
    elif chart_type in {"BAR", "TABLE"}:
        top = max(rows, key=lambda row: row[-1])
        required_claims.append({"type": "MAX_VALUE", "label": str(top[0]), "value": top[-1],
                                "unit": metrics[0][3], "token": display(top[-1])})
        reference = (f"{time_range['end']}查询范围内，{top[0]}的{metrics[0][1]}最高，"
                     f"为{display(top[-1])}{metrics[0][3]}。")
    elif chart_type == "PIE":
        total = sum(row[-1] for row in rows)
        top = max(rows, key=lambda row: row[-1])
        share = round(top[-1] / total * 100, 2)
        required_claims.extend([
            {"type": "COMPONENT_VALUE", "label": str(top[0]), "value": top[-1],
             "unit": metrics[0][3], "token": display(top[-1])},
            {"type": "SELECTED_SCOPE_SHARE", "label": str(top[0]), "value": share,
             "unit": "%", "token": display(share)},
        ])
        reference = (f"{time_range['end']}在{scope}所选指标范围内，{top[0]}为"
                     f"{display(top[-1])}{metrics[0][3]}，占所选项合计的{display(share)}%；"
                     "该占比不代表全部业务口径。")
    else:
        latest = rows[-1]
        for index, metric in enumerate(metrics, start=1):
            required_claims.append({"type": "END_VALUE", "label": metric[1],
                                    "value": latest[index], "unit": metric[3],
                                    "token": display(latest[index])})
        reference = (f"{time_range['end']}，{scope}{metrics[0][1]}为{display(latest[1])}{metrics[0][3]}，"
                     f"{metrics[1][1]}为{display(latest[2])}{metrics[1][3]}；两者仅按同单位并列比较。")
    notices = risk_notices(scene, result["rowCount"])
    definitions = "；".join(f"{metric[1]}：{metric[2]}" for metric in metrics)
    reference = f"{reference} 指标口径：{definitions}。风险提示：{' '.join(notices)}"
    return {
        "requiredClaims": required_claims,
        "metricDefinitions": [metric_annotation(metric) for metric in metrics],
        "timeRange": time_range,
        "scope": scope,
        "requiredRiskNotices": notices,
        "forbiddenClaims": ["不得编造查询结果中不存在的数值。", "不得把相关变化表述为因果关系。",
                            "不得省略指标单位或混用不同指标口径。"],
        "lowConfidencePolicy": {"threshold": 0.7, "emptyData": "仅说明无数据，不输出结论。",
                                "smallSample": "少于3个观测值时仅陈述事实。"},
        "referenceExplanation": reference,
    }


def build_case(connection: sqlite3.Connection, split: str, family: str, variant: int,
               split_index: int, organizations: list[tuple], metric_map: dict[str, tuple]) -> dict:
    org = organizations[(split_index * 3 + variant) % len(organizations)]
    scene = SCENES[(variant + split_index) % len(SCENES)]
    code = METRIC_POOLS[scene][variant]
    metric = metric_map[code]
    date = DATES[(variant + split_index) % len(DATES)]
    chart_type = family
    if family == "KPI_CARD":
        sql = ("SELECT data_date, organization_code, ROUND(metric_value, 6) AS metric_value "
               f"FROM bank_indicator_fact WHERE data_date = '{date}' AND organization_code = '{org[0]}' "
               f"AND metric_code = '{code}'")
        question = f"{date}{org[1]}的{metric[1]}是多少？"
        scope, metrics, time_range = org[1], [metric], {"start": date, "end": date}
    elif family == "LINE":
        sql = ("SELECT data_date, ROUND(metric_value, 6) AS metric_value FROM bank_indicator_fact "
               f"WHERE organization_code = '{org[0]}' AND metric_code = '{code}' "
               f"AND data_date IN ({','.join(repr(item) for item in DATES)}) ORDER BY data_date")
        question = f"查看{org[1]}近六个月{metric[1]}走势。"
        scope, metrics, time_range = org[1], [metric], {"start": DATES[0], "end": DATES[-1]}
    elif family == "BAR":
        sql = ("SELECT o.organization_name, ROUND(f.metric_value, 6) AS metric_value "
               "FROM bank_indicator_fact f JOIN bank_organization_dim o ON o.organization_code=f.organization_code "
               f"WHERE f.data_date='{date}' AND f.metric_code='{code}' "
               "ORDER BY f.metric_value DESC, f.organization_code LIMIT 5")
        question = f"比较{date}全省机构{metric[1]}前五名。"
        scope, metrics, time_range = "全省前五家机构", [metric], {"start": date, "end": date}
    elif family == "PIE":
        pair = PAIR_POOL[(split_index + variant) % 3]
        metrics = [metric_map[item] for item in pair]
        code_list = ",".join(repr(item) for item in pair)
        sql = ("SELECT m.metric_name, ROUND(f.metric_value, 6) AS metric_value "
               "FROM bank_indicator_fact f JOIN bank_metric_dim m ON m.metric_code=f.metric_code "
               f"WHERE f.data_date='{date}' AND f.organization_code='{org[0]}' "
               f"AND f.metric_code IN ({code_list}) ORDER BY m.metric_code")
        question = f"查看{date}{org[1]}所选{metrics[0][1]}和{metrics[1][1]}的构成。"
        scope, scene = f"{org[1]}的", scene_for(pair[0])
        time_range = {"start": date, "end": date}
    elif family == "COMBO":
        pair = PAIR_POOL[(split_index * 2 + variant) % len(PAIR_POOL)]
        metrics = [metric_map[item] for item in pair]
        sql = ("SELECT data_date, "
               f"ROUND(MAX(CASE WHEN metric_code='{pair[0]}' THEN metric_value END), 6) AS {pair[0].lower()}, "
               f"ROUND(MAX(CASE WHEN metric_code='{pair[1]}' THEN metric_value END), 6) AS {pair[1].lower()} "
               "FROM bank_indicator_fact "
               f"WHERE organization_code='{org[0]}' AND metric_code IN ('{pair[0]}','{pair[1]}') "
               f"AND data_date IN ({','.join(repr(item) for item in DATES)}) GROUP BY data_date ORDER BY data_date")
        question = f"对比{org[1]}近六个月{metrics[0][1]}和{metrics[1][1]}。"
        scope, scene = org[1], scene_for(pair[0])
        time_range = {"start": DATES[0], "end": DATES[-1]}
    else:
        sql = ("SELECT f.data_date, o.organization_name, m.metric_code, m.metric_name, m.metric_unit, "
               "ROUND(f.metric_value, 6) AS metric_value FROM bank_indicator_fact f "
               "JOIN bank_organization_dim o ON o.organization_code=f.organization_code "
               "JOIN bank_metric_dim m ON m.metric_code=f.metric_code "
               f"WHERE f.data_date='{date}' AND f.metric_code='{code}' "
               "ORDER BY f.metric_value DESC, f.organization_code LIMIT 5")
        question = f"列出{date}{metric[1]}前五家机构及指标元数据。"
        scope, metrics, time_range = "全省前五家机构", [metric], {"start": date, "end": date}
    result = execute(connection, sql)
    if not result["rowCount"]:
        raise RuntimeError(f"{split}-{family}-{variant} returned no rows")
    annotation = explanation(metrics, result, time_range, scope, scene, chart_type)
    case_id = f"CHART-{split.upper()}-{CHART_TYPES.index(family) * 5 + variant + 1:03d}"
    return {
        "id": case_id, "source": "competition_workbook", "split": split,
        "templateGroup": f"{split}-{family.lower()}-v1", "scene": scene,
        "question": question, "dataProfile": profile(result, chart_type, len(metrics)),
        "result": result, "chartAnnotation": chart_annotation(chart_type, result["rowCount"]),
        "explanationAnnotation": annotation,
    }


def build_questionnaire(cases: list[dict]) -> list[dict]:
    selected = [case for index, case in enumerate(cases) if index % 3 == 0]
    rows = []
    for index, case in enumerate(selected, start=1):
        annotation = case["explanationAnnotation"]
        claim = annotation["requiredClaims"][0]
        definition = annotation["metricDefinitions"][0]
        rows.append({
            "id": f"BQ-{index:03d}", "caseId": case["id"], "scene": case["scene"],
            "question": f"基于图表结果，说明{claim['label']}的关键数值、{definition['name']}口径和使用限制。",
            "expectedAnswer": annotation["referenceExplanation"],
            "requiredFacts": [claim["token"], definition["definition"],
                              annotation["timeRange"]["end"], annotation["requiredRiskNotices"][0]],
            "rubric": {"value": 3, "metricDefinition": 3, "timeRange": 2, "riskNotice": 2,
                       "total": 10, "passScore": 9},
        })
    return rows


def main() -> None:
    workbook = find_workbook()
    organizations, metrics, facts = workbook_rows(workbook)
    connection = create_database(organizations, metrics, facts)
    metric_map = {metric[0]: metric for metric in metrics}
    all_cases: list[dict] = []
    for split_index, split in enumerate(SPLITS):
        split_cases = [build_case(connection, split, family, variant, split_index,
                                  organizations, metric_map)
                       for family in CHART_TYPES for variant in range(5)]
        dump_jsonl(OUTPUT_DIR / f"{split}.jsonl", split_cases)
        all_cases.extend(split_cases)
    questionnaire = build_questionnaire(all_cases)
    dump_jsonl(OUTPUT_DIR / "business_questionnaire.jsonl", questionnaire)
    chart_counts = Counter(case["chartAnnotation"]["recommended"] for case in all_cases)
    scene_counts = Counter(case["scene"] for case in all_cases)
    manifest = {
        "version": "1.0.0", "generatedAt": "2026-07-23", "sourceWorkbook": workbook.name,
        "sourceSha256": file_sha256(workbook),
        "caseCount": len(all_cases), "splitCounts": dict(Counter(case["split"] for case in all_cases)),
        "chartTypeCounts": dict(chart_counts), "sceneCounts": dict(scene_counts),
        "questionnaireCount": len(questionnaire), "targetChartAccuracy": 0.9,
        "targetBusinessUnderstanding": 0.9,
    }
    dump_json(OUTPUT_DIR / "manifest.json", manifest)
    connection.close()
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
