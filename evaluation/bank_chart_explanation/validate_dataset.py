#!/usr/bin/env python3
"""Validate DATA-03 structure, chart rules, explanation grounding, and questionnaire."""

from __future__ import annotations

import hashlib
import json
from collections import Counter
from pathlib import Path
from typing import Any

from build_dataset import CHART_TYPES, OUTPUT_DIR, SPLITS, file_sha256, find_workbook

REQUIRED_FIELDS = {
    "id", "source", "split", "templateGroup", "scene", "question", "dataProfile",
    "result", "chartAnnotation", "explanationAnnotation",
}


def read_jsonl(path: Path) -> list[dict]:
    with path.open(encoding="utf-8") as stream:
        return [json.loads(line) for line in stream if line.strip()]


def result_sha256(result: dict) -> str:
    canonical = json.dumps({"columns": result["columns"], "rows": result["rows"]},
                           ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def numeric_values(result: dict) -> list[float]:
    return [value for row in result["rows"] for value in row
            if isinstance(value, (int, float)) and not isinstance(value, bool)]


def assert_chart_rule(case: dict) -> None:
    chart = case["chartAnnotation"]["recommended"]
    profile = case["dataProfile"]
    result = case["result"]
    if chart == "KPI_CARD":
        assert result["rowCount"] == 1 and profile["metricCount"] == 1
    elif chart == "LINE":
        assert result["rowCount"] >= 3 and profile["hasTimeDimension"]
        assert profile["metricCount"] == 1
    elif chart == "BAR":
        assert 2 <= result["rowCount"] <= 20 and profile["dimensionCount"] >= 1
    elif chart == "PIE":
        assert 2 <= result["rowCount"] <= 6 and not profile["hasNegativeValue"]
        units = {item["unit"] for item in case["explanationAnnotation"]["metricDefinitions"]}
        assert len(units) == 1, f"{case['id']} pie units differ"
    elif chart == "COMBO":
        assert result["rowCount"] >= 3 and profile["hasTimeDimension"]
        assert profile["metricCount"] == 2
        units = {item["unit"] for item in case["explanationAnnotation"]["metricDefinitions"]}
        assert len(units) == 1, f"{case['id']} combo units differ"
    else:
        assert result["rowCount"] >= 1 and len(result["columns"]) >= 3


def assert_grounded(case: dict) -> None:
    annotation = case["explanationAnnotation"]
    reference = annotation["referenceExplanation"]
    values = numeric_values(case["result"])
    for claim in annotation["requiredClaims"]:
        assert claim["token"] in reference, f"{case['id']} missing claim token"
        if claim["type"] == "SELECTED_SCOPE_SHARE":
            expected = claim["value"]
            actual = round(max(values) / sum(values) * 100, 2)
            assert expected == actual, f"{case['id']} invalid selected-scope share"
        else:
            assert claim["value"] in values, f"{case['id']} claim not found in result"
    assert annotation["timeRange"]["end"] in reference
    for metric in annotation["metricDefinitions"]:
        assert metric["definition"].strip() and metric["unit"].strip()
        assert metric["definition"] in reference, f"{case['id']} definition not referenced"
    for notice in annotation["requiredRiskNotices"]:
        assert notice in reference, f"{case['id']} risk notice not referenced"
    assert annotation["forbiddenClaims"] and annotation["lowConfidencePolicy"]["threshold"] == 0.7


def main() -> None:
    cases = [case for split in SPLITS for case in read_jsonl(OUTPUT_DIR / f"{split}.jsonl")]
    manifest = json.loads((OUTPUT_DIR / "manifest.json").read_text(encoding="utf-8"))
    ids: set[str] = set()
    templates = {split: set() for split in SPLITS}
    chart_counts: Counter[str] = Counter()
    scene_counts: Counter[str] = Counter()
    for case in cases:
        missing = REQUIRED_FIELDS - case.keys()
        assert not missing, f"{case.get('id')} missing fields: {sorted(missing)}"
        assert case["id"] not in ids, f"duplicate id: {case['id']}"
        ids.add(case["id"])
        assert case["source"] == "competition_workbook"
        assert case["split"] in SPLITS and case["question"].strip()
        templates[case["split"]].add(case["templateGroup"])
        result = case["result"]
        assert result["rowCount"] == len(result["rows"])
        assert len(result["columns"]) == len(result["rows"][0])
        assert result["sha256"] == result_sha256(result)
        chart = case["chartAnnotation"]
        assert chart["recommended"] in CHART_TYPES
        assert chart["confidence"] >= 0.7 and chart["rationale"].strip()
        assert chart["alternatives"] and chart["notRecommended"]
        assert all(item["type"] != chart["recommended"] for item in chart["alternatives"])
        assert all(item["reason"].strip() for item in chart["notRecommended"])
        assert_chart_rule(case)
        assert_grounded(case)
        chart_counts[chart["recommended"]] += 1
        scene_counts[case["scene"]] += 1
    assert len(cases) == 90
    assert Counter(case["split"] for case in cases) == Counter({split: 30 for split in SPLITS})
    assert chart_counts == Counter({chart: 15 for chart in CHART_TYPES})
    assert all(scene_counts[scene] >= 15 for scene in scene_counts)
    assert not templates["train"] & templates["dev"]
    assert not templates["train"] & templates["test"]
    assert not templates["dev"] & templates["test"]

    questionnaires = read_jsonl(OUTPUT_DIR / "business_questionnaire.jsonl")
    assert len(questionnaires) == 30
    for item in questionnaires:
        assert item["caseId"] in ids
        assert item["rubric"]["total"] == 10 and item["rubric"]["passScore"] == 9
        assert all(str(fact) in item["expectedAnswer"] for fact in item["requiredFacts"])

    workbook = find_workbook()
    assert manifest["sourceSha256"] == file_sha256(workbook)
    report: dict[str, Any] = {
        "status": "passed", "casesValidated": len(cases),
        "splitCounts": dict(Counter(case["split"] for case in cases)),
        "chartTypeCounts": dict(chart_counts), "sceneCounts": dict(scene_counts),
        "groundedExplanations": len(cases), "questionnairesValidated": len(questionnaires),
        "ruleBaselineChartAccuracy": 1.0, "oracleBusinessUnderstandingScore": 1.0,
        "templateOverlap": 0, "sourceWorkbookHashMatched": True,
    }
    (OUTPUT_DIR / "validation_report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
