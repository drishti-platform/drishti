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

"""Distributions and lenses in the console (docs/guides/ADMIN_GUIDE.md, the three layers of switches; docs/architecture/LENSES.md): what
exists is the distribution's (learned from the server's about answer); a lens is a workspace one person works in, kept in their settings:
it arranges (its Home, the menus it names first, the placeholder) and never hides anything; the distribution's default lens
is everyone's starting workspace. Without a distribution the console is exactly the full product."""
from __future__ import annotations

import server_answers
import html
import re

import pytest
from fastapi.testclient import TestClient

from conftest import CONSOLE, help_pages
from core.app import create_app
from core.auth import Identity
from core.config import load_settings

# today's top bar (the full product): every item the menus drew before editions, by link
TODAY = ["/t", "/s", "/forms", "/func", "/datamap", "/reports", "/w", "/m", "/alerts", "/build/new", "/build/pack/designer", "/build",
         "/approvals", "/what", "/help/tutorials", "/help/build-guide", "/admin/users", "/admin/roles",
         "/admin/audit", "/admin/packs", "/admin/connectors", "/admin/artifacts", "/admin/loads", "/admin/lakes", "/admin/collab",
         "/admin/embedding", "/admin/net", "/admin/health", "/admin/caches", "/help", "/help/operations#2-install-and-run-on-one-machine", "/about"]
RISK_ABOUT = {"product": "Drishti Risk", "tagline": "VaR and exposure from your own pricing data",
              "edition": {"name": "drishti.risk", "title": "Drishti Risk"}, "lenses": ["risk", "trading", "market"]}   # its default lens: risk
FULL_ABOUT = {"product": "Drishti", "edition": None, "lenses": ["risk", "world", "market", "trading"]}


def _workspace(app, lens: str, *people: str):
    """A full instance offering the four lenses, with these people (alice by default) working in ``lens`` (their settings)."""
    server_answers.follow(app, FULL_ABOUT)
    for who in people or ("alice",):
        app.state.store.setdefault(who, {})["lens"] = lens


def _app(backend, monkeypatch, users: dict | None = None):
    """A fresh console over the stand-in server, its people told apart by a cookie (alice by default), each with their own settings."""
    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = backend
    store: dict[str, dict] = {}

    async def settings(ident=None):
        return dict(store.get(ident.user if ident else "", {}))

    async def patch_settings(body, ident=None):
        mine = store.setdefault(ident.user, {})
        for k, v in body.items():
            if v is None:
                mine.pop(k, None)
            else:
                mine[k] = v
        return mine

    monkeypatch.setattr(backend, "settings", settings, raising=False)
    monkeypatch.setattr(backend, "patch_settings", patch_settings, raising=False)

    async def current(cookie, _backend):
        return Identity(user=cookie or "alice", display=(cookie or "alice").title(), desk="Risk", roles=("*",), admin=True)

    monkeypatch.setattr(app.state.auth, "current", current)
    app.state.store = store
    return app


def _client(app, user="alice"):
    c = TestClient(app)
    c.cookies.set(app.state.auth.cookie, user)
    return c


def _menu_links(html: str) -> list[str]:
    nav = html[html.index('class="tbar-menus"'):html.index('class="tbar-tools"')]
    return re.findall(r'class="mega-item[^"]*" href="([^"]+)"', nav)


def test_without_an_edition_the_console_is_the_full_product(backend, monkeypatch):
    app = _app(backend, monkeypatch)
    server_answers.follow(app, {"product": "Drishti", "tagline": "Any data. Any domain. One grammar.", "edition": None, "lenses": []})
    c = _client(app)
    t = c.get("/t").text
    links = _menu_links(t)
    for href in TODAY:
        assert href in links, href
    assert "/lens/risk" not in links
    assert "/var" in links and "/exposure" in links and "data-engines-menu" not in t   # VaR and Exposure under Risk (the Engines menu removed)
    views = t[t.index('<span class="tbar-menu-l">Views</span>'):t.index('<span class="tbar-menu-l">Domains</span>')]
    assert 'href="/var"' not in views and 'href="/exposure"' not in views             # not in Views: one place each
    assert 'placeholder="TRD MX-20000001, CPTY north, TRD PRODUCT_TYPE=FX_QUANTO …  then &lt;GO&gt;"' in t
    assert c.get("/admin/compute").status_code == 200
    home = c.get("/").text
    assert 'id="grammar"' in home and 'id="pipeline"' in home
    assert app.state.settings.get("ui.product") == "Drishti"
    footer = home[home.index('class="site-footer"'):]
    assert "/help/release-notes" in footer and "competitive" not in footer   # the competitive analysis is in the Help menu, administrators only


def test_drishti_risk_starts_everyone_in_the_risk_workspace_which_hides_nothing_it_has(backend, monkeypatch):
    app = _app(backend, monkeypatch)
    assert server_answers.follow(app, RISK_ABOUT)
    c = _client(app)
    t = c.get("/t").text
    links = _menu_links(t)
    assert "/var" in links and "/exposure" in links and "/func" in links and "/datamap" in links and "/forms" in links
    assert "Drishti Risk" in t and "VAR BANK, VAR DESK RATES" in t                     # the brand; the lens's placeholder
    r = c.get("/", follow_redirects=False)                                              # the default lens opens its Home (a Home Sutra)
    assert r.status_code == 303 and r.headers["location"] == "/home"
    for path in ("/admin/compute", "/admin/users", "/admin/packs", "/smat"):
        assert c.get(path).status_code == 200, path                                     # the lens hides nothing
    r = c.get("/go", params={"q": "SMAT DESK RATES 30 <GO>"}, follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/smat?id=DESK%3ARATES&days=30"
    r = c.get("/go", params={"q": "ECON CAN <GO>"}, follow_redirects=False)            # what Drishti Risk leaves out does not exist
    assert r.status_code == 303 and "DRS-4023" in r.headers["location"]
    centre = help_pages(c)
    assert 'href="/help/knowledge-risk"' in centre and 'href="/help/bi-guide"' in centre and 'href="/help/knowledge-world"' not in centre
    assert "competitive" not in t[t.index('class="site-footer"'):]


def test_a_lens_arranges_one_persons_workspace_and_hides_nothing(backend, monkeypatch):
    app = _app(backend, monkeypatch)
    server_answers.follow(app, {"product": "Drishti", "edition": None, "lenses": ["risk"]})
    alice, bob = _client(app, "alice"), _client(app, "bob")
    before = alice.get("/t").text
    assert "/lens/risk" in _menu_links(before)                                         # the link, in Views
    assert alice.post("/lens/risk", follow_redirects=False).status_code == 303
    assert app.state.store["alice"]["lens"] == "risk"
    page = alice.get("/t").text                                                         # every page keeps it (a refresh too)
    assert "/lens/leave" in _menu_links(page)
    assert set(_menu_links(before)) - {"/lens/risk"} <= set(_menu_links(page))         # nothing left out
    assert alice.get("/admin/compute").status_code == 200
    assert alice.get("/", follow_redirects=False).headers["location"] == "/home" and bob.get("/", follow_redirects=False).status_code == 200
    said = [alice.get("/api/search", params={"in": "command", "q": "FO"}).json()["items"] for alice in (alice, bob)]
    assert said[0] == said[1]                                                            # the same commands offered
    alice.post("/lens/leave")                                                           # leaving it: the full product's Home again
    assert "lens" not in app.state.store["alice"]
    assert alice.get("/", follow_redirects=False).status_code == 200


def test_a_distribution_offers_its_lenses_and_leaving_one_returns_to_its_default(backend, monkeypatch):
    app = _app(backend, monkeypatch)
    server_answers.follow(app, RISK_ABOUT)
    c = _client(app)
    links = _menu_links(c.get("/t").text)
    assert "/lens/trading" in links and "/lens/market" in links and "/lens/leave" not in links   # the default lens: nothing to leave
    assert c.post("/lens/trading", follow_redirects=False).status_code == 303 and app.state.store["alice"]["lens"] == "trading"
    assert "/lens/leave" in _menu_links(c.get("/t").text)
    assert c.get("/", follow_redirects=False).headers["location"] == "/home"
    c.post("/lens/leave")
    assert c.get("/", follow_redirects=False).headers["location"] == "/home"           # back in the distribution's starting workspace
    r = c.post("/lens/nope", follow_redirects=False)
    assert "DRS-4023" in r.headers["location"]


@pytest.mark.parametrize("key", ["ui.logo", "ui.favicon"])
def test_logo_and_icons_come_from_configuration(backend, monkeypatch, key):
    app = create_app(load_settings(CONSOLE / "config", [f"--{key}=/static/img/acme.svg"]))
    app.state.backend = backend
    app.state.editions.follow(server_answers.branded(product="Acme Risk"))      # the server's answer for a site's own distribution file
    c = TestClient(app)
    page = c.get("/").text
    if key == "ui.logo":
        assert 'class="brand-mark brand-logo" src="/static/img/acme.svg"' in page
    else:
        assert '<link rel="icon" href="/static/img/acme.svg">' in page
    m = c.get("/manifest.webmanifest").json()
    assert m["name"] == "Acme Risk" and m["icons"][0]["src"] in ("/static/img/acme.svg", "/static/img/icons/drishti_icon-192.png")


# ---- Drishti World (2.9.7): the second edition, and two lenses side by side --------------------------------------------


@pytest.mark.parametrize("lens", ["risk", "world", "market", "trading"])
def test_each_lens_arranges_and_hides_nothing(backend, monkeypatch, lens):
    """The same pages, menu items, help and words inside a lens as outside it; only the order, Home and the placeholder change."""
    app = _app(backend, monkeypatch)
    _workspace(app, lens, "alice")
    alice, bob = _client(app, "alice"), _client(app, "bob")
    mine, theirs = alice.get("/t").text, bob.get("/t").text
    assert {h for h in _menu_links(theirs) if not h.startswith("/lens/")} <= set(_menu_links(mine)), lens
    for path in ("/var", "/exposure", "/datamap", "/build/new", "/admin/compute", "/admin/users", "/smat"):
        assert alice.get(path).status_code == bob.get(path).status_code, (lens, path)
    for typed in ("VAR BANK <GO>", "RUPAKA <GO>", "FORM trading <GO>", "ECON CAN <GO>", "DATAMAP <GO>"):
        a = alice.get("/go", params={"q": typed}, follow_redirects=False).headers.get("location", "")
        assert "DRS-4023" not in a, (lens, typed)
    assert set(re.findall(r'href="(/help/[^"#]+)', help_pages(alice))) == set(re.findall(r'href="(/help/[^"#]+)', help_pages(bob))), lens
    edition = app.state.editions.for_request(lens)
    said = f'placeholder="{html.escape(edition.placeholder)}"'                          # the lens's own placeholder (arranging)
    assert edition.lens and said in mine and said not in theirs


def _world_searches(backend, monkeypatch, sent: list):
    async def search(q, ident=None):
        from core import edition
        sent.append((q, edition.headers()))
        return {"kind": "economy", "matched": 213, "rows": [{"ref": {"kind": "economy", "id": "ECON-CAN"}, "values": {"$.NAME": "Canada"}}]}

    monkeypatch.setattr(backend, "search", search, raising=False)


def test_two_lenses_each_person_opens_either_and_switches_between_them(backend, monkeypatch):
    app = _app(backend, monkeypatch)
    server_answers.follow(app, {"product": "Drishti", "edition": None, "lenses": ["risk", "world"]})
    alice, bob = _client(app, "alice"), _client(app, "bob")
    page = alice.get("/t").text
    links = _menu_links(page)
    assert "/lens/risk" in links and "/lens/world" in links and "/lens/leave" not in links
    assert "Open Drishti Risk" in page and "Open Drishti World" in page
    home = alice.get("/").text                                                          # on the product's Home too
    assert 'data-lens-link="risk"' in home and 'data-lens-link="world"' in home
    alice.post("/lens/risk")
    assert app.state.store["alice"]["lens"] == "risk"
    page = alice.get("/t").text
    assert 'data-product="Drishti"' in page and "/var" in _menu_links(page)                 # a lens never renames the product
    assert "Leave Drishti Risk" in page and "Switch to Drishti World" in page and "Open Drishti Risk" not in page
    assert _menu_links(page).index("/lens/leave") < _menu_links(page).index("/lens/world")
    assert alice.get("/", follow_redirects=False).headers["location"] == "/home"         # the lens opens its Home
    alice.post("/lens/world")                                                            # ... switching: no need to leave first
    assert app.state.store["alice"]["lens"] == "world"
    page = alice.get("/t").text
    assert "/var" in _menu_links(page) and "/forms" in _menu_links(page)                  # World hides nothing
    assert "Leave Drishti World" in page and "Switch to Drishti Risk" in page
    assert alice.get("/var").status_code == 200
    alice.post("/lens/risk")
    # bob, at the same time, sees the full product with both links
    other = bob.get("/t").text
    assert 'data-product="Drishti"' in other and "/lens/risk" in _menu_links(other) and "/lens/world" in _menu_links(other)
    alice.post("/lens/leave")
    assert "lens" not in app.state.store["alice"]
    back = alice.get("/t").text
    assert "/lens/risk" in _menu_links(back) and "/lens/world" in _menu_links(back) and 'data-product="Drishti"' in back


# ---- The design links follow Build, the distribution's switch: a lens never turns it off, so inside one the Sutra keeps its link ------


def _provenance_html(app, lens):
    from core import edition as ed

    ed.set_lens(lens)
    try:
        p = {"id": "built", "kind": "provenance", "title": "How this view was built",
             "data": {"fields": [{"label": "Layout", "text": "Sutra irs-vanilla v3"}, {"label": "Source", "text": "trade-calypso, gen 7"}]}}
        tpl = app.state.templates.env.from_string('{% from "_macros/panels.html" import panel %}{{ panel(p, ref=ref) }}')
        return tpl.render(p=p, ref={"kind": "trade", "id": "IRS-48213"})
    finally:
        ed.set_lens(None)


def test_inside_a_lens_the_sutra_keeps_its_design_link(backend, monkeypatch):
    app = _app(backend, monkeypatch)
    server_answers.follow(app, FULL_ABOUT)
    full = _provenance_html(app, None)
    assert "data-prov-sutra" in full and "/studio?sutra=irs-vanilla%403" in full                  # the full product: the designer link
    for lens in ("risk", "world", "market", "trading"):
        inside = _provenance_html(app, lens)
        assert "data-prov-sutra" in inside and "/studio?sutra=irs-vanilla%403" in inside, lens        # a lens hides nothing
        assert "data-prov-source" in inside, lens


@pytest.mark.parametrize("lens,home", [("risk", "risk-manager"), ("trading", "trader"), ("market", "market-data"), ("world", "economist")])
def test_a_lens_names_the_role_home_it_opens_and_the_server_gets_it_as_the_default_start(backend, monkeypatch, lens, home):
    from core.edition import Edition
    app = _app(backend, monkeypatch)
    _workspace(app, lens)
    c = _client(app)
    assert app.state.editions.lenses[lens].home_name == home                  # config/lenses/*: home.home, a Home of the server (config/homes/)
    r = c.get("/", follow_redirects=False)                                    # the one Home page, not a landing of its own
    assert r.status_code == 303 and r.headers["location"] == "/home"
    assert c.get("/", params={"home": "none"}, follow_redirects=False).status_code == 200      # a person offered no Home: the public landing, no loop
    assert Edition("x", {"home": {"home": "../etc"}}).home_name is None and Edition("x", {"home": {}}).home_name is None
