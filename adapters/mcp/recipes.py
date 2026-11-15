"""Recipe library for the DAP MCP adapter.

A recipe is a saved workflow: the plain dap-native-workflow/1 document written by
POST /v1/workflows/{id}/save plus a "recipe" block with metadata and optional
parameter bindings. Recipes live in $DAP_HOME/recipes/<name>.json.
"""
from __future__ import annotations

import copy
import json
import os
import tempfile

WORKFLOW_FORMAT = "dap-native-workflow/1"


class RecipeError(ValueError):
    """A bad recipe name, file or workflow; the adapter maps it to -32602."""

    def __init__(self, message: str, data: dict | None = None) -> None:
        super().__init__(message)
        self.data = data


def recipes_dir(home: str) -> str:
    return os.path.join(os.path.expanduser(home), "recipes")


def _check_name(name) -> str:
    """Recipe names are file names: reject empty names and path separators."""
    if not isinstance(name, str) or not name.strip():
        raise RecipeError("recipe name must be a non-empty string")
    name = name.strip()
    if name in (".", "..") or "/" in name or "\\" in name or "\x00" in name:
        raise RecipeError("recipe name must not contain path separators: %r" % name)
    if name.startswith("."):
        raise RecipeError("recipe name must not start with '.': %r" % name)
    return name


def recipe_path(home: str, name: str) -> str:
    return os.path.join(recipes_dir(home), _check_name(name) + ".json")


def _read(path: str) -> dict:
    try:
        with open(path, "r", encoding="utf-8") as stream:
            document = json.load(stream)
    except json.JSONDecodeError as failure:
        raise RecipeError("recipe is not valid JSON: %s: %s" % (path, failure))
    except OSError as failure:
        raise RecipeError("cannot read recipe: %s: %s" % (path, failure))
    if not isinstance(document, dict):
        raise RecipeError("recipe must be a JSON object: %s" % path)
    if document.get("format") != WORKFLOW_FORMAT:
        raise RecipeError(
            "recipe has unsupported format %r; expected %r"
            % (document.get("format"), WORKFLOW_FORMAT)
        )
    return document


def _entry(name: str, document: dict) -> dict:
    recipe = document.get("recipe")
    if not isinstance(recipe, dict):
        recipe = {}
    entry = {"name": name}
    if recipe.get("description"):
        entry["description"] = str(recipe["description"])
    if recipe.get("tags"):
        entry["tags"] = [str(tag) for tag in recipe["tags"]]
    return entry


def list_recipes(home: str) -> list[dict]:
    """Names, descriptions and tags of the saved recipes, sorted by name.

    A file that cannot be parsed is skipped: one broken recipe must not hide the
    rest of the library. Reading it directly still reports the error.
    """
    directory = recipes_dir(home)
    try:
        names = sorted(os.listdir(directory))
    except OSError:
        return []
    entries = []
    for filename in names:
        if not filename.endswith(".json") or filename.startswith("."):
            continue
        name = filename[:-5]
        try:
            document = _read(os.path.join(directory, filename))
        except RecipeError:
            continue
        entries.append(_entry(name, document))
    return entries


def load_recipe(home: str, name: str) -> dict:
    """Load one recipe document (workflow JSON with a recipe block)."""
    path = recipe_path(home, name)
    if not os.path.isfile(path):
        raise RecipeError("unknown recipe: %s" % name, {"name": name})
    document = _read(path)
    recipe = document.get("recipe")
    if not isinstance(recipe, dict):
        recipe = {}
    recipe.setdefault("name", name)
    document["recipe"] = recipe
    return document


def _validate_workflow(workflow) -> dict:
    if not isinstance(workflow, dict):
        raise RecipeError("workflow must be a JSON object")
    document = copy.deepcopy(workflow)
    if document.get("format") != WORKFLOW_FORMAT:
        raise RecipeError(
            "workflow format must be %r, got %r"
            % (WORKFLOW_FORMAT, document.get("format"))
        )
    nodes = document.get("nodes")
    if not isinstance(nodes, list) or not nodes:
        raise RecipeError("workflow needs a non-empty nodes[]")
    connections = document.get("connections")
    if connections is None:
        document["connections"] = []
    elif not isinstance(connections, list):
        raise RecipeError("workflow connections[] must be an array")
    return document


def save_recipe(home: str, name: str, workflow: dict, description=None, tags=None) -> dict:
    """Write <name>.json into the library and return its document.

    The stored workflow is a copy: the caller's object is never mutated, and no
    timestamps are added, so saving the same workflow twice gives the same file.
    """
    name = _check_name(name)
    document = _validate_workflow(workflow)
    recipe = document.get("recipe")
    if not isinstance(recipe, dict):
        recipe = {}
    recipe["name"] = name
    if description is not None:
        recipe["description"] = str(description)
    if tags is not None:
        if not isinstance(tags, list):
            raise RecipeError("tags must be an array of strings")
        recipe["tags"] = [str(tag) for tag in tags]
    document["recipe"] = recipe
    directory = recipes_dir(home)
    try:
        os.makedirs(directory, exist_ok=True)
    except OSError as failure:
        raise RecipeError("cannot create recipe directory: %s: %s" % (directory, failure))
    path = recipe_path(home, name)
    descriptor, temporary = tempfile.mkstemp(dir=directory, prefix=".recipe-", suffix=".json")
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(document, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
        os.replace(temporary, path)
    except OSError as failure:
        try:
            os.unlink(temporary)
        except OSError:
            pass
        raise RecipeError("cannot write recipe: %s: %s" % (path, failure))
    return document
