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

"""The before = after tests of the module rationalisation (docs/plans/MODULE_RATIONALISATION.md, 4.2): the move of the trade kind and the six
source systems into the module trading-systems, and the title "Risk", change no pack name but the six new source packs, no kind, no word,
no connector and no lake table (no lake folder or table is renamed or moved). Files only: no jar, no server."""
from __future__ import annotations

import sys
from pathlib import Path

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import module_catalogue as MC  # noqa: E402

BEFORE = yaml.safe_load((ROOT / "tools" / "testdata" / "module_catalogue.before.yaml").read_text(encoding="utf-8"))
SOURCES = ["endur", "calypso", "murex", "wss", "swapone", "ici"]
TRADE_WORDS = ["TRD", "CTRD", "PTRD", "LTRD", "ITRD"]


def manifest(module: str) -> dict:
    return yaml.safe_load((ROOT / "modules" / module / "module.yaml").read_text(encoding="utf-8"))


@pytest.fixture(scope="module")
def after() -> dict:
    return MC.catalogue(ROOT)


def test_no_kind_word_connector_or_lake_table_changes(after):
    for what in ("kinds", "words", "connectors", "lake-tables"):
        assert after[what] == BEFORE[what], f"{what}: before and after differ: {sorted(set(after[what]) ^ set(BEFORE[what]))}"


def test_the_only_new_packs_are_the_six_source_systems(after):
    assert sorted(set(after["packs"]) - set(BEFORE["packs"])) == sorted(SOURCES)
    assert not set(BEFORE["packs"]) - set(after["packs"])


def test_the_trade_kind_words_and_menu_moved_to_trading_systems():
    ts, bk = manifest("trading-systems"), manifest("banking")
    assert ts["id"] == "trading-systems" and ts["packs"]["list"] == ["trading", *SOURCES]
    assert set(ts["packs"]["off-by-default"]) == set(SOURCES)
    assert set(ts["provides"]["kinds"]["trading"]) == {"trade", "counterparty-trades", "portfolio-trades", "legal-entity-trades", "index-trades"}
    assert set(TRADE_WORDS) <= set(ts["functions"])
    assert not set(TRADE_WORDS) & {w if isinstance(w, str) else w.get("word") for w in bk["functions"]}
    assert "trading" not in bk["packs"]["list"] and "trading" not in bk["provides"]["kinds"]
    assert "trading-systems" in bk["requires"]
    assert "blotter" in [m["id"] for m in ts["console"]["menus"]] and "blotter" not in [m["id"] for m in bk["console"]["menus"]]


def test_the_risk_module_is_titled_risk_and_keeps_its_id():
    assert manifest("banking")["id"] == "banking" and manifest("banking")["title"] == "Risk"


def test_the_retired_module_folders_and_switch_files_are_gone():
    for old in ["trading", *(f"source-{s}" for s in SOURCES)]:
        assert not (ROOT / "modules" / old).exists(), old
    for old in [f"source-{s}" for s in SOURCES]:
        assert not (ROOT / "config" / "modules" / f"{old}.yaml").exists(), old


@pytest.mark.parametrize("system", SOURCES)
def test_a_source_pack_brings_its_connectors_map_rule_and_example_role(system):
    pack = ROOT / "modules" / "trading-systems" / "packs" / system
    m = yaml.safe_load((pack / "pack.yaml").read_text(encoding="utf-8"))
    assert m["pack"] == system and m["extends"] == ["trading"] and not m["kinds"]
    assert (pack / "mappings" / f"{system}-trade.yaml").is_file()
    assert m["dataload"] == "dataload.yaml" and m["mappings"] == {"trade": f"mappings/{system}-trade.yaml"}
    assert sorted(m["connectors"]) == [f"trade-{system}", f"trade-{system}-live"]
    rule = yaml.safe_load((pack / "dataload.yaml").read_text(encoding="utf-8"))["dataload"]["rules"][0]
    assert rule["pack"] == system and rule["connector"] == f"trade-{system}" and rule["kind"] == "trade"
    role = m["roles"][f"trader-{system}"]
    assert role["rows"]["trade"] == f"SOURCE_SYSTEM = '{system.upper()}'" and role["edit-systems"] == [system.upper()]
    conn = m["connectors"][f"trade-{system}"]
    assert conn["settings"]["set"]["SOURCE_SYSTEM"] == system.upper() and conn["settings"]["map"] == "${pack.dir}/mappings/" + f"{system}-trade.yaml"


@pytest.mark.parametrize("system", SOURCES)
def test_a_source_pack_adds_its_native_fields_section_to_the_trade_page(system):
    pack = ROOT / "modules" / "trading-systems" / "packs" / system
    m = yaml.safe_load((pack / "pack.yaml").read_text(encoding="utf-8"))
    (sec,) = m["sections"]
    assert sec["kind"] == "trade" and sec["when"] == f"$.SOURCE_SYSTEM == '{system.upper()}'"
    doc = yaml.safe_load((pack / sec["file"]).read_text(encoding="utf-8"))
    assert sec["file"].endswith(".section.yaml") and doc["match"] == {"kind": "trade", "where": sec["when"]}
    assert doc["panels"][0]["kind"] == "kv" and doc["panels"][0]["columns"]


def test_the_distributions_name_the_new_modules_only():
    risk = yaml.safe_load((ROOT / "config" / "distributions" / "drishti.risk.yaml").read_text(encoding="utf-8"))
    ids = [m["id"] for m in risk["modules"]]
    assert ids == ["banking", "market-data", "reference-data", "trading-systems"]
    for name in ("finance", "public-sector", "genomics"):
        d = yaml.safe_load((ROOT / "config" / "distributions" / f"drishti.{name}.yaml").read_text(encoding="utf-8"))
        assert not {"trading", "trading-systems", *(f"source-{s}" for s in SOURCES)} & {m["id"] for m in d.get("modules", []) if isinstance(m, dict)}
