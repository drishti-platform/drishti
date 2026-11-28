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

"""The catalogue of a checkout's shipped content that must not change when modules are moved (docs/plans/MODULE_RATIONALISATION.md, 4.2):
every pack's name, kind, word (mnemonic), connector (the pack's inline ones and each module's or pack's template files) and lake table.
A module move changes none of them: ``tools/test_module_rationalisation.py`` compares this catalogue with the one recorded before the
move (``tools/testdata/module_catalogue.before.yaml``). Reads files only (no jar).

    python tools/module_catalogue.py [ROOT]      print the catalogue of the checkout at ROOT (default: this one) as YAML
"""
from __future__ import annotations

import sys
from pathlib import Path

import yaml


def _load(p: Path) -> dict:
    try:
        return yaml.safe_load(p.read_text(encoding="utf-8")) or {}
    except (OSError, yaml.YAMLError):
        return {}


def _pack_files(root: Path) -> list[Path]:
    return sorted([*root.glob("modules/*/packs/*/pack.yaml"), *root.glob("config/packs/*/*/pack.yaml"), *root.glob("config/packs/*/pack.yaml")])


def _connector_templates(root: Path) -> set[str]:
    """The connector names the module and pack template files bring (the file's ``name:``, else its stem; ``examples/`` are not templates)."""
    out: set[str] = set()
    for f in [*root.glob("modules/*/connectors/*.yaml"), *root.glob("modules/*/packs/*/connectors/*.yaml")]:
        out.add(str(_load(f).get("name") or f.stem))
    return out


def catalogue(root: Path) -> dict:
    root = Path(root)
    kinds: set[str] = set()
    words: set[str] = set()
    packs: set[str] = set()
    connectors = _connector_templates(root)
    tables: set[str] = set()
    for f in _pack_files(root):
        m = _load(f)
        packs.add(str(m.get("pack") or f.parent.name))
        kinds.update(str(k) for k in (m.get("kinds") or []) if not isinstance(k, dict))
        words.update(str(w) for w in (m.get("mnemonics") or {}))
        if isinstance(m.get("connectors"), dict):
            connectors.update(str(c) for c in m["connectors"])
        tables.update(str(t) for t in ((m.get("lake") or {}).get("tables") or {}))
    for f in sorted(root.glob("config/lakes/*.yaml")) + sorted(root.glob("config/lakes/*/*.yaml")):
        tables.update(str(t) for t in (_load(f).get("tables") or {}))
    return {"packs": sorted(packs), "kinds": sorted(kinds), "words": sorted(words), "connectors": sorted(connectors), "lake-tables": sorted(tables)}


if __name__ == "__main__":
    print(yaml.safe_dump(catalogue(Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[1]), sort_keys=False, width=140))
