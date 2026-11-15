#!/usr/bin/env python3
"""Minimal block-style YAML editor for MCP client configs (stdlib only).

PyYAML is not installed and must not become a dependency, so this module edits
the client configs line by line: it finds a mapping path by indentation and
either replaces the block under its last key or inserts the missing keys. Text
outside that block (comments, sibling keys, ordering) is preserved.

Supported subset: block mappings and sequences with 2-space indentation, plain
or quoted scalars. Flow collections, anchors, aliases, tags and block scalars
are rejected with ConfigError, so an unusual config is reported (CLI exit 2)
instead of being silently rewritten.

Public API:
    upsert_mapping_block(text, path, block, indent=0) -> str
    upsert_list_entry(text, entry_id, path, block, indent=0) -> str
    write_hermes(config_path, entry, dry_run=False) -> str
    write_dsh(patch_path, entry, dry_run=False) -> str
"""
from __future__ import annotations

import json
import os
import pathlib
import re
import shutil
import tempfile
import time

INDENT = 2

_KEY_RE = re.compile(
    r'^(?P<indent> *)(?P<key>"[^"]+"|' + "'[^']+'" + r"|[A-Za-z0-9_][A-Za-z0-9_.-]*)"
    r"[ ]*:(?:[ ](?P<rest>.*))?$"
)
_PLAIN_RE = re.compile(r"^[A-Za-z0-9_./~+][A-Za-z0-9_./~+ @-]*$")
_NUMBER_RE = re.compile(r"^[-+]?(?:\d[\d_]*\.?\d*|\.\d+)(?:[eE][-+]?\d+)?$")
_ITEM_RE = re.compile(r"^(?P<indent> *)-(?:[ ]+(?P<rest>.*))?$")
_ITEM_RE = re.compile(r"^(?P<indent> *)-(?:[ ]+(?P<rest>.*))?$")
_YAML_WORDS = {"true", "false", "null", "~", "yes", "no", "on", "off"}


class ConfigError(Exception):
    """Raised when a config cannot be edited safely."""


# ------------------------------------------------------------------- parsing

def _indent_of(line: str) -> int:
    return len(line) - len(line.lstrip(" "))


def _is_blank(line: str) -> bool:
    return not line.strip()


def _is_comment(line: str) -> bool:
    return line.lstrip().startswith("#")


def _is_content(line: str) -> bool:
    return not _is_blank(line) and not _is_comment(line)


def _unquote(value: str) -> str:
    if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
        return value[1:-1]
    return value


def _match_key(line: str):
    match = _KEY_RE.match(line)
    if not match:
        return None
    return _unquote(match.group("key")), (match.group("rest") or "").strip()


def _document_lines(text: str) -> list[str]:
    lines = text.splitlines()
    for line in lines:
        leading = line[: len(line) - len(line.lstrip(" \t"))]
        if "\t" in leading:
            raise ConfigError("tab indentation is not supported")
    first = next((line for line in lines if _is_content(line)), None)
    if first is not None:
        if _indent_of(first) != 0:
            raise ConfigError("document must start at column 0")
        if first.lstrip().startswith(("{", "[")):
            raise ConfigError("flow-style YAML documents are not supported")
    return lines


def _find_key(lines, key, indent, start, end):
    for index in range(start, end):
        line = lines[index]
        if not _is_content(line) or _indent_of(line) != indent:
            continue
        parsed = _match_key(line)
        if parsed and parsed[0] == key:
            return index, parsed[1]
    return None, None


def _block_end(lines, index, indent, end):
    """First line after the block under *index* that closes it."""
    position = index + 1
    while position < end:
        line = lines[position]
        if _is_content(line) and _indent_of(line) <= indent:
            break
        if _is_comment(line) and _indent_of(line) <= indent:
            break
        position += 1
    return position


def _trim_end(lines, start, end):
    """Drop trailing blank lines from a range so separators stay in place."""
    while end > start and _is_blank(lines[end - 1]):
        end -= 1
    return end


def _join(lines) -> str:
    if not lines:
        return ""
    return "\n".join(lines) + "\n"


# ------------------------------------------------------------------- writing

def _plain_safe(text: str) -> bool:
    if not text or text != text.strip():
        return False
    if not _PLAIN_RE.match(text):
        return False
    if ": " in text or " #" in text:
        return False
    if text.lower() in _YAML_WORDS:
        return False
    if _NUMBER_RE.match(text):
        return False
    return True


def _scalar(value) -> str:
    if value is True:
        return "true"
    if value is False:
        return "false"
    if value is None:
        return "null"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, float):
        return repr(value)
    text = str(value)
    if _plain_safe(text):
        return text
    return json.dumps(text, ensure_ascii=False)


def _emit(mapping, indent: int) -> list[str]:
    lines: list[str] = []
    for key, value in mapping.items():
        prefix = " " * indent
        if isinstance(value, dict):
            if not value:
                lines.append(f"{prefix}{key}: {{}}")
            else:
                lines.append(f"{prefix}{key}:")
                lines.extend(_emit(value, indent + INDENT))
        elif isinstance(value, (list, tuple)):
            if not value:
                lines.append(f"{prefix}{key}: []")
            else:
                lines.append(f"{prefix}{key}:")
                for item in value:
                    if isinstance(item, dict):
                        lines.extend(_emit_list_item(item, indent + INDENT))
                    else:
                        lines.append(" " * (indent + INDENT) + "- " + _scalar(item))
        else:
            lines.append(f"{prefix}{key}: {_scalar(value)}")
    return lines


def _emit_list_item(mapping, indent: int) -> list[str]:
    lines = _emit(mapping, indent + INDENT)
    lines[0] = " " * indent + "- " + lines[0].lstrip()
    return lines


# -------------------------------------------------------------------- upsert

def _inline_error(key: str, rest: str) -> str:
    first = rest[0]
    if first in "{[":
        kind = "flow-style YAML"
    elif first in "&*":
        kind = "YAML anchors/aliases"
    elif first == "!":
        kind = "YAML tags"
    elif first in "|>":
        kind = "block scalars"
    else:
        kind = "inline scalar"
    return f"{kind} is not supported for {key!r}: {rest[:60]!r}"


def _upsert_lines(lines, path, block, indent, start, end):
    key = path[0]
    index, rest = _find_key(lines, key, indent, start, end)
    if index is None:
        nested = block
        for parent in reversed(path[1:]):
            nested = {parent: nested}
        at = _trim_end(lines, start, end)
        return lines[:at] + _emit({key: nested}, indent) + lines[at:]
    if rest and not rest.startswith("#"):
        raise ConfigError(_inline_error(key, rest))
    block_end = _block_end(lines, index, indent, end)
    if len(path) == 1:
        at = _trim_end(lines, index + 1, block_end)
        return lines[: index + 1] + _emit(block, indent + INDENT) + lines[at:]
    return _upsert_lines(lines, path[1:], block, indent + INDENT, index + 1, block_end)


def upsert_mapping_block(text: str, path: list[str], block: dict, indent: int = 0) -> str:
    """Insert or replace the mapping at *path* in a block-style YAML document.

    *path* is a list of mapping keys; *indent* is the column of path[0].
    Missing keys are created with the mapping under them; an existing leaf is
    replaced, leaving comments and siblings outside the leaf untouched.
    """
    if not path:
        raise ValueError("path must not be empty")
    if not isinstance(block, dict):
        raise ConfigError("block must be a mapping")
    lines = _document_lines(text)
    return _join(_upsert_lines(lines, list(path), block, indent, 0, len(lines)))


# -------------------------------------------------------------- list entries

def _plain_value(rest: str):
    text = rest.strip()
    if not text:
        return None
    if text[0] in "\"'":
        end = text.find(text[0], 1)
        return text[1:end] if end > 0 else text[1:]
    return text.split(" #", 1)[0].strip()


def _item_end(lines, index, indent, end):
    """First line after the list item that starts at *index*."""
    position = index + 1
    while position < end:
        line = lines[position]
        if _is_content(line) and _indent_of(line) <= indent:
            break
        position += 1
    return position


def _list_items(lines, indent, start, end):
    first = next((line for line in lines[start:end] if _is_content(line)), None)
    if first is not None and _indent_of(first) != indent:
        raise ConfigError("expected a YAML list at the top level")
    items = []
    position = start
    while position < end:
        line = lines[position]
        if _is_content(line) and _indent_of(line) == indent:
            if not _ITEM_RE.match(line):
                raise ConfigError("expected a YAML list of patch entries")
            item_end = _item_end(lines, position, indent, end)
            items.append((position, item_end))
            position = item_end
            continue
        position += 1
    return items


def _item_id(lines, start, end, indent):
    inline = (_ITEM_RE.match(lines[start]).group("rest") or "").strip()
    if inline:
        parsed = _match_key(" " * (indent + INDENT) + inline)
        if parsed and parsed[0] == "id":
            return _plain_value(parsed[1])
    for index in range(start + 1, end):
        line = lines[index]
        if not _is_content(line) or _indent_of(line) != indent + INDENT:
            continue
        parsed = _match_key(line)
        if parsed and parsed[0] == "id":
            return _plain_value(parsed[1])
    return None


def upsert_list_entry(text: str, entry_id: str, path: list[str], block: dict, indent: int = 0) -> str:
    """Insert or update the mapping at *path* inside the list entry *entry_id*.

    The entry is a block-style mapping in a top-level list. An existing entry is
    merged (only *path* is replaced); otherwise a new "- id: <entry_id>" item is
    appended, preserving comments and the rest of the document.
    """
    if not path:
        raise ValueError("path must not be empty")
    if not isinstance(block, dict):
        raise ConfigError("block must be a mapping")
    lines = _document_lines(text)
    for start, end in _list_items(lines, indent, 0, len(lines)):
        if _item_id(lines, start, end, indent) != entry_id:
            continue
        updated = upsert_mapping_block(_join(lines[start:end]), list(path), block, indent + INDENT)
        return _join(lines[:start] + updated.splitlines() + lines[end:])
    entry = {"id": entry_id}
    node = entry
    for part in path[:-1]:
        node[part] = {}
        node = node[part]
    node[path[-1]] = block
    new_lines = _emit_list_item(entry, indent)
    at = _trim_end(lines, 0, len(lines))
    if at:
        return _join(lines[:at] + [""] + new_lines + lines[at:])
    return _join(new_lines + lines)


def _mapping_path_exists(text: str, path, base_indent: int = 0) -> bool:
    lines = text.splitlines()
    start, end, indent = 0, len(lines), base_indent
    for key in path:
        index, rest = _find_key(lines, key, indent, start, end)
        if index is None:
            return False
        if rest and not rest.startswith("#"):
            return False
        start, end = index + 1, _block_end(lines, index, indent, end)
        indent += INDENT
    return True


# --------------------------------------------------------------------- files

def _read_text(path: pathlib.Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except FileNotFoundError:
        return ""
    except OSError as exc:
        raise ConfigError(f"cannot read {path}: {exc}") from exc


def _backup(path: pathlib.Path):
    if not path.is_file():
        return None
    stamp = time.strftime("%Y%m%d-%H%M%S")
    candidate = path.with_name(f"{path.name}.bak-{stamp}")
    counter = 1
    while candidate.exists():
        candidate = path.with_name(f"{path.name}.bak-{stamp}-{counter}")
        counter += 1
    try:
        shutil.copy2(path, candidate)
    except OSError as exc:
        raise ConfigError(f"cannot create backup {candidate}: {exc}") from exc
    return candidate


def _commit(path: pathlib.Path, old_text: str, new_text: str, dry_run: bool) -> str:
    if new_text == old_text:
        return new_text
    if dry_run:
        return new_text
    if path.is_file() and not os.access(path, os.W_OK):
        raise ConfigError(f"config is not writable: {path}")
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        _backup(path)
        descriptor, temp_name = tempfile.mkstemp(
            prefix=f".{path.name}.", suffix=".tmp", dir=str(path.parent)
        )
    except OSError as exc:
        raise ConfigError(f"cannot write {path}: {exc}") from exc
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
            stream.write(new_text)
        if path.is_file():
            shutil.copymode(str(path), temp_name)
        os.replace(temp_name, str(path))
    except OSError as exc:
        try:
            os.unlink(temp_name)
        except OSError:
            pass
        raise ConfigError(f"cannot write {path}: {exc}") from exc
    return new_text


def _restore(path: pathlib.Path, old_text: str) -> None:
    try:
        if old_text:
            path.write_text(old_text, encoding="utf-8")
        elif path.exists():
            path.unlink()
    except OSError:
        pass


def _verify_written(path: pathlib.Path, old_text: str, verify, what: str) -> None:
    if verify(_read_text(path)):
        return
    _restore(path, old_text)
    raise ConfigError(f"verification failed: {what} missing from {path} after write")


def write_hermes(config_path: pathlib.Path, entry: dict, dry_run: bool = False) -> str:
    """Upsert mcp_servers.dap in the Hermes config; return the new text."""
    path = pathlib.Path(config_path)
    old_text = _read_text(path)

    def verify(text: str) -> bool:
        return _mapping_path_exists(text, ["mcp_servers", "dap"])

    new_text = upsert_mapping_block(old_text, ["mcp_servers", "dap"], entry)
    if not verify(new_text):
        raise ConfigError(f"cannot place mcp_servers.dap in {path}")
    _commit(path, old_text, new_text, dry_run)
    if not dry_run and new_text != old_text:
        _verify_written(path, old_text, verify, "mcp_servers.dap")
    return new_text

def _dsh_has_entry(text: str, entry_id: str = "mcp-manager") -> bool:
    lines = text.splitlines()
    try:
        items = _list_items(lines, 0, 0, len(lines))
    except ConfigError:
        return False
    for start, end in items:
        if _item_id(lines, start, end, 0) != entry_id:
            continue
        return _mapping_path_exists(_join(lines[start:end]), ["config", "servers", "dap"], INDENT)
    return False


def write_dsh(patch_path: pathlib.Path, entry: dict, dry_run: bool = False) -> str:
    """Upsert mcp-manager.config.servers.dap in the DSH profile patch."""
    path = pathlib.Path(patch_path)
    old_text = _read_text(path)
    new_text = upsert_list_entry(old_text, "mcp-manager", ["config", "servers", "dap"], entry)
    if not _dsh_has_entry(new_text):
        raise ConfigError(f"cannot place mcp-manager.config.servers.dap in {path}")
    _commit(path, old_text, new_text, dry_run)
    if not dry_run and new_text != old_text:
        _verify_written(path, old_text, _dsh_has_entry, "mcp-manager.config.servers.dap")
    return new_text

