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
    declared = recipe.get("params")
    if isinstance(declared, dict) and declared:
        entry["params"] = sorted(str(key) for key in declared)
    if recipe.get("dataset") is not None:
        entry["dataset"] = True
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

# ------------------------------------------------------------- parameter binding

def _set_setting(node: dict, setting: str, value) -> None:
    """Set node settings[setting], creating nested objects for dotted paths."""
    settings = node.get("settings")
    if not isinstance(settings, dict):
        settings = {}
        node["settings"] = settings
    parts = [part for part in str(setting).split(".") if part]
    if not parts:
        raise RecipeError("setting name must not be empty")
    target = settings
    for part in parts[:-1]:
        child = target.get(part)
        if not isinstance(child, dict):
            child = {}
            target[part] = child
        target = child
    target[parts[-1]] = value


def _binding(value, what: str, default_setting: str | None = None) -> dict:
    """Normalize a recipe binding to {"node", "setting"}."""
    if isinstance(value, str):
        value = {"node": value}
    if not isinstance(value, dict) or not value.get("node"):
        raise RecipeError("%s must be an object with 'node' and 'setting'" % what)
    setting = value.get("setting") or default_setting
    if not isinstance(setting, str) or not setting.strip():
        raise RecipeError("%s must name a 'setting' to override" % what)
    return {"node": str(value["node"]), "setting": setting.strip()}


def _default_dataset_binding(nodes: list) -> dict | None:
    """The first file-based reader node, so a plain recipe still takes a dataset."""
    for node in nodes:
        if not isinstance(node, dict):
            continue
        factory = str(node.get("factory") or "")
        settings = node.get("settings")
        if factory.endswith("_reader") and isinstance(settings, dict) and "file" in settings:
            return {"node": str(node.get("id")), "setting": "file"}
    return None


def _apply_binding(by_id: dict, binding: dict, value, what: str) -> None:
    node = by_id.get(binding["node"])
    if node is None:
        raise RecipeError(
            "%s references unknown node %r" % (what, binding["node"]),
            {"node": binding["node"]},
        )
    _set_setting(node, binding["setting"], value)


def bind_recipe(document: dict, dataset=None, params=None) -> tuple[dict, dict]:
    """Return a copy of the recipe with dataset/params applied, plus what changed.

    The stored document is never mutated: every run gets its own deep copy, so
    concurrent runs cannot see each other's bindings.
    """
    bound = copy.deepcopy(document)
    recipe = bound.get("recipe")
    if not isinstance(recipe, dict):
        recipe = {}
        bound["recipe"] = recipe
    nodes = bound.get("nodes") or []
    by_id = {
        str(node["id"]): node
        for node in nodes
        if isinstance(node, dict) and node.get("id")
    }
    applied = {}
    if dataset is not None:
        declared = recipe.get("dataset")
        if declared is None:
            binding = _default_dataset_binding(nodes)
            if binding is None:
                raise RecipeError(
                    "recipe has no dataset binding; pass params instead",
                    {"name": recipe.get("name")},
                )
        else:
            binding = _binding(declared, "recipe.dataset", default_setting="file")
        _apply_binding(by_id, binding, dataset, "recipe.dataset")
        applied["dataset"] = {
            "node": binding["node"],
            "setting": binding["setting"],
            "value": dataset,
        }
    if params is not None:
        if not isinstance(params, dict):
            raise RecipeError("params must be an object")
        declared_params = recipe.get("params")
        if not isinstance(declared_params, dict):
            declared_params = {}
        unknown = sorted(str(key) for key in params if key not in declared_params)
        if unknown:
            declared = ", ".join(sorted(str(key) for key in declared_params)) or "none"
            raise RecipeError(
                "unknown recipe parameter(s): %s; declared: %s"
                % (", ".join(unknown), declared),
                {"unknown": unknown, "declared": sorted(str(key) for key in declared_params)},
            )
        for key, value in params.items():
            binding = _binding(declared_params[key], "recipe.params.%s" % key)
            _apply_binding(by_id, binding, value, "recipe.params.%s" % key)
            applied[str(key)] = {
                "node": binding["node"],
                "setting": binding["setting"],
                "value": value,
            }
    return bound, applied


def output_node(document: dict) -> str | None:
    """The recipe's output node: recipe.output when set, otherwise the last node."""
    recipe = document.get("recipe")
    output = recipe.get("output") if isinstance(recipe, dict) else None
    if isinstance(output, str) and output.strip():
        return output.strip()
    nodes = document.get("nodes") or []
    if nodes and isinstance(nodes[-1], dict):
        return nodes[-1].get("id")
    return None


def node_position(document: dict, node_id: str) -> int | None:
    """Index of a node in document order; /v1/workflows/load keeps that order."""
    for index, node in enumerate(document.get("nodes") or []):
        if isinstance(node, dict) and node.get("id") == node_id:
            return index
    return None


# --------------------------------------------------------------- SQL safety

SQL_DENIED = frozenset({
    "INSERT", "UPDATE", "DELETE", "CREATE", "DROP", "ALTER", "ATTACH", "DETACH",
    "COPY", "INSTALL", "LOAD", "PRAGMA", "SET", "EXPORT", "IMPORT", "CALL",
})


def _strip_semicolon(sql: str) -> str:
    value = sql.strip()
    while value.endswith(";"):
        value = value[:-1].strip()
    return value


def _skip_quoted(sql: str, start: int, quote: str) -> int:
    index = start + 1
    while index < len(sql):
        if sql[index] == quote:
            if index + 1 < len(sql) and sql[index + 1] == quote:
                index += 2
                continue
            return index + 1
        index += 1
    return index


def validate_sql(raw) -> str:
    """Reject anything but one read-only SELECT/WITH before it reaches the engine.

    This mirrors dap.sql_query's own check: quoted strings, quoted identifiers
    and comments are skipped, a trailing semicolon is allowed, and any other
    statement separator or DDL/DML keyword is an error.
    """
    if not isinstance(raw, str):
        raise RecipeError("sql must be a string", {"code": "sql_not_allowed"})
    sql = _strip_semicolon(raw)
    if not sql:
        raise RecipeError(
            "sql is empty; provide one SELECT or WITH statement",
            {"code": "sql_not_allowed"},
        )
    words = []
    index = 0
    while index < len(sql):
        current = sql[index]
        if current == "'":
            index = _skip_quoted(sql, index, "'")
            continue
        if current == '"':
            index = _skip_quoted(sql, index, '"')
            continue
        if current == "-" and index + 1 < len(sql) and sql[index + 1] == "-":
            while index < len(sql) and sql[index] != "\n":
                index += 1
            continue
        if current == "/" and index + 1 < len(sql) and sql[index + 1] == "*":
            index += 2
            while index + 1 < len(sql) and not (sql[index] == "*" and sql[index + 1] == "/"):
                index += 1
            index = min(len(sql), index + 2)
            continue
        if current == ";":
            raise RecipeError(
                "only one statement is allowed; remove the statement after ';' "
                "and call dap_sql once per query",
                {"code": "sql_not_allowed"},
            )
        if current.isalpha() or current == "_":
            start = index
            while index < len(sql) and (sql[index].isalnum() or sql[index] == "_"):
                index += 1
            words.append(sql[start:index].upper())
            continue
        index += 1
    if not words:
        raise RecipeError(
            "no statement found; provide one SELECT or WITH statement",
            {"code": "sql_not_allowed"},
        )
    if words[0] not in ("SELECT", "WITH"):
        raise RecipeError(
            "only SELECT and WITH statements are allowed, got %s; rewrite the query "
            "as a single SELECT or WITH statement" % words[0],
            {"code": "sql_not_allowed"},
        )
    for keyword in words:
        if keyword in SQL_DENIED:
            raise RecipeError(
                "%s is not allowed in a read-only query; dap_sql accepts SELECT/WITH only"
                % keyword,
                {"code": "sql_not_allowed"},
            )
    return sql
