#!/usr/bin/env python3
"""Формирует catalog/curated.json: явный список core-узлов DAP.

Правило: имя узла из CORE_NAMES, не deprecated, схема доступна.
Если имя встречается несколько раз — берём первый по категории/имени.
"""
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
CATALOG = ROOT / "catalog" / "catalog.json"
REPORT = ROOT / "catalog" / "schema-report.json"
OUT = ROOT / "catalog" / "curated.json"

CORE_NAMES = [
    # IO
    "Example Data Reader", "CSV Reader", "Excel Reader", "Table Reader", "ARFF Reader",
    "Line Reader", "File Reader (Complex Format)", "Model Reader", "Read Images",
    "Table Writer", "CSV Writer", "Excel Writer", "ARFF Writer", "Model Writer", "PMML Writer",
    # columns
    "Column Filter", "Column Resorter", "Column Renamer", "Column Splitter", "Column Combiner",
    "Column Merger", "Column Aggregator", "Column Appender", "Column Auto Type Cast",
    "Column Name Replacer",
    "Cell Splitter", "Cell Splitter By Position", "One to Many", "Many to One",
    "Category to Number", "Number to Category (Apply)", "Number to String", "String to Number",
    "Constant Value Column Appender", "Constant Value Column Filter", "Domain Calculator",
    "Normalizer", "Numeric Binner", "Binner", "Target Shuffling", "Column Comparator",
    # rows
    "Row Filter", "Row Splitter", "Reference Row Filter", "Reference Row Splitter",
    "Nominal Value Row Filter", "Nominal Value Row Splitter", "Rule-based Row Filter",
    "Rule-based Row Splitter", "Row Sampler", "Equal Size Sampling", "Bootstrap Sampling",
    "Shuffle", "Sorter", "Rank", "Top k Row Filter", "Duplicate Row Filter", "Value Counter",
    "GroupBy", "Pivot", "Unpivot", "Concatenate", "Cross Joiner", "Joiner",
    "Missing Value", "Missing Value Column Filter", "Row Aggregator", "RowID", "Ungroup",
    "Table Cropper", "Table Splitter", "Table Manipulator", "Table Partitioner",
    "String Replacer", "String Cleaner", "String Splitter (Regex)",
    "Rule Engine (Dictionary)", "Value Lookup", "Lag Column",
    # analytics
    "Decision Tree Learner", "Decision Tree Predictor", "Random Forest Learner",
    "Random Forest Predictor", "Gradient Boosted Trees Learner",
    "Gradient Boosted Trees Predictor", "Naive Bayes Learner", "Naive Bayes Predictor",
    "Logistic Regression Learner", "Logistic Regression Predictor",
    "Linear Regression Learner", "Regression Predictor", "Polynomial Regression Learner",
    "k-Means", "k-Medoids", "PCA", "PCA Apply", "PCA Compute", "Scorer", "Numeric Scorer",
    "Statistics", "Linear Correlation", "Crosstab", "One-way ANOVA", "Independent groups t-test",
    "Paired t-test", "Single sample t-test", "K Nearest Neighbor", "SVM Learner", "SVM Predictor",
    "Hierarchical Clustering", "DBSCAN", "Silhouette Coefficient", "Association Rule Learner",
    # flow
    "Chunk Loop Start", "Column List Loop Start", "Group Loop Start", "Counting Loop Start",
    "Interval Loop Start", "Table Row to Variable Loop Start", "Loop End",
    "Loop End (Column Append)", "Variable Loop End", "Variable Condition Loop End",
    "IF Switch", "End IF", "CASE Switch Start", "CASE Switch End",
    "Catch Errors (Data Ports)", "Table Row to Variable", "Variable to Table Column",
    "Table Column to Variable", "Variable to Table Row", "Wait...",
    # structure
    "JSON to Table", "Table to JSON", "JSON Path", "XML Reader", "XPath", "String to XML",
    "Column to XML", "XML Column Combiner",
]


def main() -> int:
    nodes = json.loads(CATALOG.read_text())["nodes"]
    report = {r["id"]: r for r in json.loads(REPORT.read_text())["details"]}
    by_name = {}
    for n in nodes:
        if n.get("deprecated"):
            continue
        if not report.get(n["id"], {}).get("available"):
            continue
        by_name.setdefault(n["name"], []).append(n)
    core, missing = [], []
    for name in CORE_NAMES:
        candidates = by_name.get(name)
        if not candidates:
            missing.append(name)
            continue
        candidates.sort(key=lambda n: (n.get("category", ""), n["id"]))
        core.append(candidates[0]["id"])
    # Уникализируем, сохраняя порядок.
    core = list(dict.fromkeys(core))
    out = {
        "version": 1,
        "description": "DAP core nodes: schema guaranteed, covered by golden tests. Everything else is experimental.",
        "core": core,
    }
    OUT.write_text(json.dumps(out, indent=1, ensure_ascii=False) + "\n")
    print(f"core={len(core)} missing={len(missing)}")
    if missing:
        print("missing names:", ", ".join(missing))
    return 0 if not missing else 1


if __name__ == "__main__":
    raise SystemExit(main())
