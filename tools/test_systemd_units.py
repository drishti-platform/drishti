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

"""The production units the operator copies are the units the guide prints (pre-gate B3): every ``deploy/systemd/prod/*.service`` is shown
in docs/guides/OPERATIONS_GUIDE.md as the ``ini`` block right after the text naming it, and the two agree line for line (comments and blank
lines aside). The console unit starts the running release's own Python, the one the guide installs the libraries into."""
from __future__ import annotations

import re
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
GUIDE = ROOT / "docs" / "guides" / "OPERATIONS_GUIDE.md"
UNITS = sorted((ROOT / "deploy" / "systemd" / "prod").glob("*.service"))


def body(text: str) -> list[str]:
    return [line.rstrip() for line in text.splitlines() if line.strip() and not line.lstrip().startswith("#")]


def printed(name: str) -> list[str]:
    """The ini block that follows the guide's mention of the shipped unit ``deploy/systemd/prod/<name>``."""
    text = GUIDE.read_text(encoding="utf-8")
    m = re.search(r"deploy/systemd/prod/" + re.escape(name) + r"`[^\n]*\n+```ini\n(.*?)```", text, re.S)
    assert m, f"OPERATIONS_GUIDE.md prints no ini block right after naming deploy/systemd/prod/{name}"
    return body(m.group(1))


def test_there_are_production_units():
    assert {u.name for u in UNITS} >= {"drishti-server.service", "drishti-console.service"}


@pytest.mark.parametrize("unit", UNITS, ids=lambda u: u.name)
def test_the_guide_prints_the_shipped_unit(unit):
    assert printed(unit.name) == body(unit.read_text(encoding="utf-8")), f"{unit.name}: the guide's block and the shipped file differ"


def test_the_console_starts_the_running_releases_python():
    lines = body((ROOT / "deploy" / "systemd" / "prod" / "drishti-console.service").read_text(encoding="utf-8"))
    start = next(x for x in lines if x.startswith("ExecStart="))
    assert "/opt/drishti/current/python/bin/python3.13 " in start, start
