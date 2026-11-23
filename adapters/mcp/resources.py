"""MCP resources and prompts for the DAP adapter.

Pure helpers: URI parsing, rendering of the catalog and the recipe library, and
the prompt templates. The adapter owns the HTTP calls and the file system; this
module only turns data into JSON-RPC payloads and parses resource URIs.
"""
from __future__ import annotations

import json
import urllib.parse

CATALOG_URI = "dap://catalog"
RECIPES_URI = "dap://recipes"
RECIPE_PREFIX = "dap://recipes/"
DATASETS_URI = "dap://datasets"
DATASET_PREFIX = "dap://datasets/"
# Compact dataset views: the catalog record carries hashes and history, which the
# agent does not need when it only looks for a dataset to analyse.
DATASET_LIST_KEYS = ("name", "description", "rows", "columns", "version", "updatedAt")
DATASET_KEYS = ("name", "description", "owner", "tags", "source", "format", "path",
                "rows", "columns", "version", "contentHash", "createdAt", "updatedAt")


class ResourceError(ValueError):
    """An unknown or malformed resource URI; the adapter maps it to -32602."""

    def __init__(self, message: str, uri: str | None = None) -> None:
        super().__init__(message)
        self.data = {"uri": uri} if uri else None


def parse_uri(uri: str) -> tuple[str, str | None]:
    """Return ("catalog"|"recipes"|"recipe"|"datasets"|"dataset", name)."""
    if uri == CATALOG_URI:
        return ("catalog", None)
    if uri == RECIPES_URI:
        return ("recipes", None)
    if uri.startswith(RECIPE_PREFIX) and len(uri) > len(RECIPE_PREFIX):
        return ("recipe", urllib.parse.unquote(uri[len(RECIPE_PREFIX):]))
    if uri == DATASETS_URI:
        return ("datasets", None)
    if uri.startswith(DATASET_PREFIX) and len(uri) > len(DATASET_PREFIX):
        return ("dataset", urllib.parse.unquote(uri[len(DATASET_PREFIX):]))
    raise ResourceError("unknown resource URI: " + uri, uri)


def list_resources() -> list[dict]:
    """The library-level resources advertised by resources/list."""
    return [
        {
            "uri": CATALOG_URI,
            "name": "catalog",
            "title": "DAP node catalog",
            "description": (
                "All node ids, names, categories and types of the engine catalog. "
                "Read it before picking a factory for dap_add_node or a SQL recipe."
            ),
            "mimeType": "application/json",
        },
        {
            "uri": RECIPES_URI,
            "name": "recipes",
            "title": "Saved recipes",
            "description": (
                "Names and descriptions of the saved pipelines in $DAP_HOME/recipes; "
                "read dap://recipes/<name> for the workflow JSON with its recipe block."
            ),
            "mimeType": "application/json",
        },
        {
            "uri": DATASETS_URI,
            "name": "datasets",
            "title": "Dataset catalog",
            "description": (
                "Registered datasets with rows, columns and version; read "
                "dap://datasets/<name> for one entry with its cached profile. "
                "The name can be passed as 'dataset' to dap_analyze/dap_profile."
            ),
            "mimeType": "application/json",
        },
    ]


def render_catalog(payload: dict) -> str:
    """Compact catalog JSON: id, name, category, type and tier per node."""
    nodes = payload.get("nodes") if isinstance(payload, dict) else None
    view = []
    for node in nodes or []:
        if not isinstance(node, dict):
            continue
        view.append({
            key: node[key]
            for key in ("id", "name", "category", "type", "tier")
            if key in node
        })
    return json.dumps({"count": len(view), "nodes": view}, ensure_ascii=False)


def render_recipes(entries: list[dict]) -> str:
    """Recipe names and descriptions as JSON."""
    return json.dumps({"recipes": list(entries)}, ensure_ascii=False)


def render_recipe(document: dict, name: str | None = None) -> str:
    """A saved recipe as the workflow JSON it is stored in (with the recipe block)."""
    return json.dumps(document, ensure_ascii=False)


def _dataset_entries(payload) -> list[dict]:
    """Catalog entries from {"datasets": [...]} or a bare list."""
    if isinstance(payload, dict):
        entries = payload.get("datasets")
    else:
        entries = payload
    return [entry for entry in entries or [] if isinstance(entry, dict)]


def render_datasets(payload) -> str:
    """Compact catalog list: name, description, rows, columns and version."""
    view = [
        {key: entry[key] for key in DATASET_LIST_KEYS if key in entry}
        for entry in _dataset_entries(payload)
    ]
    return json.dumps({"count": len(view), "datasets": view}, ensure_ascii=False)


def render_dataset(document: dict, name: str | None = None) -> str:
    """One catalog entry with its cached profile."""
    if not isinstance(document, dict):
        return json.dumps(document, ensure_ascii=False)
    view = {key: document[key] for key in DATASET_KEYS if key in document}
    view["profile"] = document.get("profile")
    return json.dumps(view, ensure_ascii=False)


# ------------------------------------------------------------------- prompts

class PromptError(ValueError):
    """An unknown prompt or a missing required argument; the adapter maps it to -32602."""

    def __init__(self, message: str, data: dict | None = None) -> None:
        super().__init__(message)
        self.data = data


def _scenario_prompt(name: str, description: str) -> dict:
    """Definition of a saved-recipe scenario prompt."""
    return {
        "name": name,
        "description": description,
        "arguments": [
            {
                "name": "dataset",
                "description": "dataset name or path to a Parquet file",
                "required": True,
            },
            {
                "name": "report",
                "description": "Markdown report path (default: %s.md)" % name,
                "required": False,
            },
        ],
    }


PROMPTS = [
    {
        "name": "analyze_csv",
        "description": (
            "Analyse a CSV and answer one analytical question with a single dap_analyze "
            "(or dap_explore) call; the message gives the exact arguments."
        ),
        "arguments": [
            {"name": "path", "description": "CSV file to analyse", "required": True},
            {
                "name": "question",
                "description": "optional analytical question about the data",
                "required": False,
            },
        ],
    },
    {
        "name": "quality_check",
        "description": (
            "Run a one-call data quality pass over a CSV with dap_explore: nulls, duplicates, "
            "ranges and row counts, reported as PASS/FAIL per check."
        ),
        "arguments": [
            {"name": "path", "description": "CSV file to check", "required": True},
        ],
    },
    {
        "name": "profile_report",
        "description": (
            "Write a Markdown profile report for a CSV with a single dap_report call; "
            "the returned table and profile are the confirmation."
        ),
        "arguments": [
            {"name": "path", "description": "CSV file to profile", "required": True},
            {
                "name": "output",
                "description": "Markdown report path (default: profile.md)",
                "required": False,
            },
        ],
    },
] + [
    _scenario_prompt(
        "cohort_analysis",
        "Run the saved cohort_analysis recipe: cohort retention table and Markdown report.",
    ),
    _scenario_prompt(
        "ab_test",
        "Run the saved ab_test recipe: A/B comparison table and Markdown report.",
    ),
    _scenario_prompt(
        "time_series",
        "Run the saved time_series recipe: resampled forecast table and Markdown report.",
    ),
    _scenario_prompt(
        "segmentation",
        "Run the saved segmentation recipe: cluster segments table and Markdown report.",
    ),
    _scenario_prompt(
        "regression",
        "Run the saved regression recipe: linear regression table and Markdown report.",
    ),
    _scenario_prompt(
        "funnel",
        "Run the saved funnel recipe: funnel steps and conversion table plus Markdown report.",
    ),
    _scenario_prompt(
        "exec_report",
        "Run the saved exec_report recipe: grouped summary, chart and Markdown report.",
    ),
]


def list_prompts() -> list[dict]:
    """Prompt definitions advertised by prompts/list."""
    return PROMPTS


def prompt_definition(name: str) -> dict | None:
    for prompt in PROMPTS:
        if prompt["name"] == name:
            return prompt
    return None


def _analyze_csv(values: dict) -> str:
    question = values.get("question") or (
        "summarise the table: row count, columns, ranges and obvious data quality issues"
    )
    return (
        'Analyse the CSV file at %s.\n'
        '\n'
        'DAP pipeline (one tool call; do not build the graph by hand):\n'
        '1. dap_analyze {"file": "%s"} - one call ingests the CSV, runs the workflow and '
        'returns the table preview plus the profile. Put a read-only SELECT in "sql" when the '
        'question needs aggregation (the input table is "src"); for a heavy query call '
        'dap_estimate {"file": "%s", "sql": "<same SELECT>"} first.\n'
        '2. For a broader first pass, call dap_explore {"file": "%s"} instead - profile, '
        'quality, relationships and hypotheses. For the profile alone use '
        'dap_profile {"file": "%s"}. These one-call tools delete their temporary workflow, '
        'so do not follow them with dap_get_table or dap_sql on their workflow id.\n'
        '\n'
        'Question: %s\n'
        'Report the table preview, the profile highlights and the numbers that answer the question.\n'
        % (values["path"], values["path"], values["path"], values["path"],
           values["path"], question)
    )


def _quality_check(values: dict) -> str:
    return (
        'Check the data quality of the CSV file at %s.\n'
        '\n'
        'DAP pipeline (one tool call; do not build the graph by hand):\n'
        '1. dap_explore {"file": "%s"} - one call returns row counts, the column profile, a '
        'quality score, issues, relationships and hypotheses.\n'
        '2. Optional: dap_profile {"file": "%s"} for a compact null/distinct/range summary. '
        'Both tools delete their temporary workflow, so do not call dap_get_table or dap_sql '
        'on their workflow id afterwards.\n'
        '\n'
        'Report every check as PASS/FAIL with the row counts that justify it.\n'
        % (values["path"], values["path"], values["path"])
    )


def _profile_report(values: dict) -> str:
    output = values.get("output") or "profile.md"
    return (
        'Build a profile report for the CSV file at %s.\n'
        '\n'
        'DAP pipeline (one tool call):\n'
        '1. dap_report {"file": "%s", "report": "%s", "format": "md"} - one call wires '
        'dap.parquet_reader into dap.markdown_report (table + profile), writes the file and '
        'returns the same table and profile; use html, pdf or pptx as "format" when another '
        'file type is needed. For a catalog dataset pass its name as "dataset" instead of '
        '"file". Do not call dap_get_table afterwards: dap_report deletes its temporary '
        'workflow, so the returned table is the complete pass-through result.\n'
        '\n'
        'Output file: %s\n'
        % (values["path"], values["path"], output, output)
    )


def _scenario_renderer(recipe: str):
    """Build the prompt renderer for one saved-recipe scenario."""
    def render(values: dict) -> str:
        dataset = values["dataset"]
        report = values.get("report") or (recipe + ".md")
        return (
            'Run the %s scenario on %s with the saved DAP recipe.\n'
            '\n'
            'DAP pipeline:\n'
            '1. Read the resource dap://recipes/%s - it returns the workflow JSON and the '
            'recipe block with the expected dataset and params.\n'
            '2. Call dap_recipe_run {"name": "%s", "dataset": "%s", "params": {"report": "%s"}} '
            '- it applies the dataset and report override, runs the saved pipeline and returns '
            'the result table.\n'
            '3. If the input columns do not match the recipe, first call dap_profile '
            '{"dataset": "%s"} or dap_explore {"dataset": "%s"} to see the real columns, then '
            'rebuild the equivalent pipeline with dap_add_node and dap_sql instead of the '
            'mismatching nodes.\n'
            '4. Return the resulting table and the report path %s.\n'
            % (recipe, dataset, recipe, recipe, dataset, report, dataset, dataset, report)
        )
    return render


_TEMPLATES = {
    "analyze_csv": _analyze_csv,
    "quality_check": _quality_check,
    "profile_report": _profile_report,
    "cohort_analysis": _scenario_renderer("cohort_analysis"),
    "ab_test": _scenario_renderer("ab_test"),
    "time_series": _scenario_renderer("time_series"),
    "segmentation": _scenario_renderer("segmentation"),
    "regression": _scenario_renderer("regression"),
    "funnel": _scenario_renderer("funnel"),
    "exec_report": _scenario_renderer("exec_report"),
}


def render_prompt(name: str, arguments) -> dict:
    """Return {"description", "text"} for prompts/get; raise PromptError when invalid."""
    definition = prompt_definition(name)
    if definition is None:
        raise PromptError("unknown prompt: %s" % name, {"prompt": name})
    if arguments is None:
        arguments = {}
    if not isinstance(arguments, dict):
        raise PromptError("prompt arguments must be an object", {"prompt": name})
    values = {}
    missing = []
    for argument in definition["arguments"]:
        raw = arguments.get(argument["name"])
        if raw is None or (isinstance(raw, str) and not raw.strip()):
            if argument["required"]:
                missing.append(argument["name"])
            continue
        values[argument["name"]] = raw.strip() if isinstance(raw, str) else str(raw)
    if missing:
        raise PromptError(
            "prompt %s is missing required argument(s): %s" % (name, ", ".join(missing)),
            {"prompt": name, "missing": missing},
        )
    return {"description": definition["description"], "text": _TEMPLATES[name](values)}
