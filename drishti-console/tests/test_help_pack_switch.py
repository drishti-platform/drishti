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


"""G2 of the pack switch (docs/plans/MODULE_RATIONALISATION.md): a Help page that names its pack (help.yaml ``pack:``, a module's help file
too) follows that pack's switch on the server: shown while the pack is on, gone when an administrator switches it off. A page with no
``pack:`` is not touched.

    python -m pytest drishti-console/tests/test_help_pack_switch.py -q
"""
from pathlib import Path
from types import SimpleNamespace

import pytest

from core.guides import Library
from core.module_parts import _help
from routes.help_routes import _packs_on


def request(person):
    return SimpleNamespace(state=SimpleNamespace(person=person))


def test_a_page_with_a_pack_follows_the_servers_switch(tmp_path):
    docs = tmp_path / "docs"
    docs.mkdir()
    (docs / "murex.md").write_text("# Murex\n", encoding="utf-8")
    (docs / "plain.md").write_text("# Plain\n", encoding="utf-8")
    cat = tmp_path / "help.yaml"
    cat.write_text("sections:\n- id: s\n  groups:\n  - title: G\n    guides:\n"
                   "    - {slug: murex, title: Murex, doc: murex.md, pack: murex}\n"
                   "    - {slug: plain, title: Plain, doc: plain.md}\n", encoding="utf-8")
    lib = Library(tmp_path, cat, docs, [], sections=[{"id": "s", "name": "S"}])
    murex, plain = lib.guides["murex"], lib.guides["plain"]
    assert murex.pack == "murex" and plain.pack == ""
    assert _packs_on(request({"enabled-packs": ["murex", "trading"]}), murex)
    assert not _packs_on(request({"enabled-packs": ["trading"]}), murex)                    # switched off in Admin: the page goes
    assert _packs_on(request({"enabled-packs": []}), plain)                                  # no pack named: never touched
    assert _packs_on(request({}), murex) and _packs_on(request(None), murex)                  # the server has not answered: nothing is hidden


def test_a_modules_help_page_can_name_its_pack(tmp_path):
    folder = tmp_path / "mod"
    (folder / "docs").mkdir(parents=True)
    (folder / "docs" / "a.md").write_text("# A\n", encoding="utf-8")
    (folder / "docs" / "help.yaml").write_text("sections:\n- id: src\n  title: Sources\n  pages:\n"
                                                "  - {title: Murex, file: a.md, pack: murex}\n  - {title: Other, file: a.md}\n", encoding="utf-8")
    got = _help("src", "Source", folder, {"docs": {"help": "docs/help.yaml"}})
    guides = got["sections"][0]["groups"][0]["guides"]
    assert [g["pack"] for g in guides] == ["murex", ""]


def test_the_persons_answer_keeps_the_packs_the_server_has_on(client, backend):
    import asyncio
    from types import SimpleNamespace as NS
    me = NS(user="ash", roles=("admin",), is_admin=True)
    packs = client.app.state.packs
    packs.forget_all()
    person = asyncio.run(packs.decisions(backend, me))
    assert "finance" in person["enabled-packs"]
