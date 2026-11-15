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


class ResourceError(ValueError):
    """An unknown or malformed resource URI; the adapter maps it to -32602."""

    def __init__(self, message: str, uri: str | None = None) -> None:
        super().__init__(message)
        self.data = {"uri": uri} if uri else None


def parse_uri(uri: str) -> tuple[str, str | None]:
    """Return ("catalog"|"recipes"|"recipe", name) or raise ResourceError."""
    if uri == CATALOG_URI:
        return ("catalog", None)
    if uri == RECIPES_URI:
        return ("recipes", None)
    if uri.startswith(RECIPE_PREFIX) and len(uri) > len(RECIPE_PREFIX):
        return ("recipe", urllib.parse.unquote(uri[len(RECIPE_PREFIX):]))
    raise ResourceError("unknown resource URI: " + uri, uri)


def list_resources() -> list[dict]:
    """The two library-level resources advertised by resources/list."""
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
