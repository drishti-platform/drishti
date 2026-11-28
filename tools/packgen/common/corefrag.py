# Project Drishti · Any data. Any domain. One grammar.
#
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
# All rights reserved.
#
# PROPRIETARY AND CONFIDENTIAL.
#
# This file is the confidential and proprietary property of Ashutosh Sinha.
# Unauthorised copying, use, modification, distribution or disclosure of this
# file, via any medium, is strictly prohibited except with the express prior
# written permission of the copyright holder.
#
# See the LICENSE file in the root of this repository for the full terms.


"""Inline table, record and tabs panels written as uses of the Rachana Core fragments (H2).

One place, used by every generator (it post-processes the text of a generated Sutra) and run once over the hand-written Sutras:

  table -> core/data-table@1      kv -> core/record-card@1 (a plain record view: record "$" and a title)      tabs -> core/tabbed-views@1

A panel is converted only when every key it carries is one the fragment takes (or a use-level key: area, source, waits); a panel
with any other key (pivot, children, expand, highlight, moreLabel, emits, phone, controls' extras ...) stays inline and is
reported. The panel's id becomes the use's {@code as}, so ids, keys and references do not change. Idempotent: a use is left alone.
"""
from __future__ import annotations

import re

TABLE_KEYS = {"title", "rows", "columns", "search", "limit", "totalLabel", "key", "code", "empty", "when", "span"}
KV_KEYS = {"title", "rows", "columns", "key", "code", "when", "span"}
TABS_KEYS = {"title", "each", "tabTitle", "layout", "key", "code", "when", "span", "panels", "body"}
USE_LEVEL = {"area", "source", "waits"}
RENAME = {"totalLabel": "total-label", "tabTitle": "tab-title"}
KEY_LINE = re.compile(r"^(\s*)([A-Za-z][\w-]*):(\s.*|)$")


def _split_keys(block: list[str], item_indent: int):
    """The block's keys as [(name, [lines])]: the item line's own key first (its indent normalised to item_indent + 2)."""
    first = block[0]
    m = re.match(r"^(\s*)-\s+(.*)$", first)
    lines = [" " * (item_indent + 2) + m.group(2)] + block[1:]
    keys: list[tuple[str, list[str]]] = []
    pending: list[str] = []
    for ln in lines:
        km = KEY_LINE.match(ln)
        if km and len(km.group(1)) == item_indent + 2:
            keys.append((km.group(2), pending + [ln]))
            pending = []
        elif keys and (ln.strip() == "" or len(ln) - len(ln.lstrip()) > item_indent + 2):
            if ln.lstrip().startswith("#") and len(ln) - len(ln.lstrip()) == item_indent + 2:
                pending.append(ln)
            else:
                keys[-1][1].append(ln)
        elif ln.lstrip().startswith("#"):
            pending.append(ln)
        else:
            keys[-1][1].append(ln) if keys else None
    return keys, pending


def _first_value(lines: list[str]) -> str:
    return KEY_LINE.match(lines[-1] if False else next(l for l in lines if KEY_LINE.match(l) and not l.lstrip().startswith("#"))).group(3).strip()


def _rename_first(lines: list[str], new: str) -> list[str]:
    out = list(lines)
    for i, ln in enumerate(out):
        m = KEY_LINE.match(ln)
        if m and not ln.lstrip().startswith("#"):
            out[i] = f"{m.group(1)}{new}:{m.group(3)}"
            break
    return out


def _shift(lines: list[str], n: int) -> list[str]:
    return [(" " * n + l) if l.strip() else l for l in lines]


def _convert_item(block: list[str], ind: int, report: list, where: str):
    """The converted lines of one panel item, or None (and a report row) when it stays inline."""
    m = re.match(r"^\s*-\s+(\w[\w-]*):", block[0])
    if not m:
        fm = re.match(r"^\s*-\s+\{.*\bkind:\s*(table|kv|tabs)\b", block[0])
        if fm and "use:" not in block[0]:
            report.append((where, "", fm.group(1), "written in flow form (not converted)"))
        return None
    keys, _ = _split_keys(block, ind)
    names = [k for k, _ in keys]
    if "use" in names or "slot" in names:
        return None
    d = {k: ls for k, ls in keys}
    kind = _first_value(d["kind"]) if "kind" in d else ""
    pid = _first_value(d["id"]) if "id" in d else ""
    if kind not in ("table", "kv", "tabs"):
        return None
    allowed = {"table": TABLE_KEYS, "kv": KV_KEYS, "tabs": TABS_KEYS}[kind]
    extra = [k for k in names if k not in allowed | USE_LEVEL | {"id", "kind"}]
    if extra:
        report.append((where, pid, kind, "keys with no fragment parameter: " + ", ".join(extra)))
        return None
    if kind == "tabs":
        return _tabs(block, ind, keys, d, pid, report, where)
    frag, default_id = ("core/data-table@1", "table") if kind == "table" else ("core/record-card@1", "card")
    if kind == "table" and "rows" not in d:
        report.append((where, pid, kind, "no rows: key"))
        return None
    if "columns" not in d:
        report.append((where, pid, kind, "no columns: key"))
        return None
    out = [" " * ind + f"- use: {frag}"]
    if pid and pid != default_id:
        out.append(" " * (ind + 2) + f"as: {pid}")
    elif not pid:
        pass
    for k in ("area", "source", "waits"):
        if k in d:
            out += _shift(d[k], 0)
    out.append(" " * (ind + 2) + "with:")
    w: list[str] = []
    if kind == "kv":
        if "rows" in d:
            w += _shift(_rename_first(d["rows"], "record"), 2)
        else:
            w.append(" " * (ind + 4) + 'record: "$"')
    for k, ls in keys:
        if k in ("id", "kind", "area", "source", "waits"):
            continue
        if kind == "kv" and k == "rows":
            continue
        name = "facts" if (kind == "kv" and k == "columns") else RENAME.get(k, k)
        w += _shift(_rename_first(ls, name), 2)
    out += w
    return out


def _tabs(block, ind, keys, d, pid, report, where):
    report.append((where, pid, "tabs", "converted by hand (slot design)"))
    return None


def convert(text: str, report: list | None = None, where: str = "", libs: tuple[str, ...] = ("core@1",)) -> str:
    """The Sutra text with its convertible inline panels as fragment uses (and libraries: declared); report collects the ones left."""
    report = report if report is not None else []
    lines = text.split("\n")
    try:
        start = next(i for i, l in enumerate(lines) if l == "panels:")
    except StopIteration:
        return text
    end = next((i for i in range(start + 1, len(lines)) if lines[i] and not lines[i].startswith((" ", "#", "-"))), len(lines))
    out = lines[:start + 1]
    i = start + 1
    changed = False
    ind = 2
    for ln in lines[start + 1:end]:
        if ln.lstrip().startswith("- "):
            ind = len(ln) - len(ln.lstrip())
            break
    while i < end:
        ln = lines[i]
        if re.match(r"^ {%d}- " % ind, ln):
            j = i + 1
            while j < end and not re.match(r"^ {%d}- " % ind, lines[j]) and not (lines[j].strip() and len(lines[j]) - len(lines[j].lstrip()) < ind and not lines[j].lstrip().startswith("#")):
                j += 1
            k = j                                               # comments and blanks at the panel level belong to the next item
            while k > i + 1 and (lines[k - 1].strip() == "" or (lines[k - 1].lstrip().startswith("#") and len(lines[k - 1]) - len(lines[k - 1].lstrip()) <= ind)):
                k -= 1
            conv = _convert_item(lines[i:k], ind, report, where)
            if conv is None:
                out += lines[i:k]
            else:
                out += conv
                changed = True
            out += lines[k:j]
            i = j
        else:
            out.append(ln)
            i += 1
    out += lines[end:]
    if not changed:
        return text
    return _declare(out, libs)


def _declare(lines: list[str], libs) -> str:
    for i, l in enumerate(lines):
        if l.startswith("libraries:"):
            have = re.findall(r"[\w-]+@\d+", l)
            new = have + [x for x in libs if x not in have]
            lines[i] = "libraries: [" + ", ".join(new) + "]"
            return "\n".join(lines)
    at = next(i for i, l in enumerate(lines) if l.startswith("version:")) + 1
    lines.insert(at, "libraries: [" + ", ".join(libs) + "]")
    return "\n".join(lines)
