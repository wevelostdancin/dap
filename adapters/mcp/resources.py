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


PROMPTS = [
    {
        "name": "analyze_csv",
        "description": (
            "Ingest a CSV, profile it with DAP tools and answer one analytical question; "
            "the message spells out the exact pipeline and the arguments to pass."
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
            "Run a data quality pass over a CSV: nulls, duplicates, ranges and row counts, "
            "reported as PASS/FAIL per check."
        ),
        "arguments": [
            {"name": "path", "description": "CSV file to check", "required": True},
        ],
    },
    {
        "name": "profile_report",
        "description": (
            "Ingest a CSV and write a Markdown profile report with dap.markdown_report, "
            "then confirm the pass-through table."
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
        'DAP pipeline (one tool call per step; do not build the graph by hand):\n'
        '1. dap_ingest {"file": "%s"} - convert the CSV once into the Parquet cache; keep the returned path.\n'
        '2. dap_explore {"dataset": "<cached path>"} - profile, quality, relationships and '
        'hypotheses in one call; use it as the first pass.\n'
        '3. dap_recipe_run {"name": "analyze_csv", "dataset": "<cached path>"} when that recipe is saved, '
        'otherwise dap_workflow_create plus dap_add_node {"factory": "dap.parquet_reader", '
        '"settings": {"file": "<cached path>"}} and dap_run.\n'
        '4. dap_profile_table {"workflow": "<id>", "node": "<node>"} for the summary, then '
        'dap_sql {"workflow": "<id>", "node": "<node>", "sql": "<one SELECT>"} if the question '
        'needs a custom query; call dap_estimate first when the query may be heavy.\n'
        '\n'
        'Question: %s\n'
        'Report the table preview, the profile highlights and the numbers that answer the question.\n'
        % (values["path"], values["path"], question)
    )


def _quality_check(values: dict) -> str:
    return (
        'Check the data quality of the CSV file at %s.\n'
        '\n'
        'DAP pipeline (one tool call per step):\n'
        '1. dap_ingest {"file": "%s"} - convert the CSV once into the Parquet cache.\n'
        '2. dap_explore {"dataset": "<cached path>"} - quality score and issues in one call.\n'
        '3. dap_workflow_create, dap_add_node {"factory": "dap.parquet_reader", '
        '"settings": {"file": "<cached path>"}}, then dap_run.\n'
        '4. dap_profile_table {"workflow": "<id>", "node": "<node>"} for nulls, uniqueness and '
        'ranges; use dap_sql {"workflow": "<id>", "node": "<node>", "sql": "<one SELECT>"} for the '
        'counts that need a query (dap_sql takes one read-only SELECT/WITH statement and rejects DDL).\n'
        '\n'
        'Report every check as PASS/FAIL with the row counts that justify it.\n'
        % (values["path"], values["path"])
    )


def _profile_report(values: dict) -> str:
    output = values.get("output") or "profile.md"
    return (
        'Build a profile report for the CSV file at %s.\n'
        '\n'
        'DAP pipeline (one tool call per step):\n'
        '1. dap_ingest {"file": "%s"} - convert the CSV once into the Parquet cache.\n'
        '2. dap_report {"dataset": "<cached path>", "report": "%s", "format": "md"} - one call: '
        'it wires dap.parquet_reader into dap.markdown_report (table + profile) and returns the '
        'pass-through table; use format html, pdf or pptx when the user needs that file.\n'
        '3. dap_get_table on the report node (it passes its input through) and read the report; '
        'for a dataset that is already in the catalog pass its name as "dataset" instead of the path.\n'
        '\n'
        'Output file: %s\n'
        % (values["path"], values["path"], output, output)
    )


_TEMPLATES = {
    "analyze_csv": _analyze_csv,
    "quality_check": _quality_check,
    "profile_report": _profile_report,
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
