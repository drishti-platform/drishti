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

"""Your Home, the console's side (routes/role_home_routes.py): ``/home`` is the one Home page of everyone signed in. The server composes the Home
(the Sutra bound over the home tables as the person, the person's own state applied); the console passes the document to the page and the person's
actions back, and holds nothing per person. A fake server stands in: what is checked here is the page's shape, who is refused what, and that two people
never see each other's Home. The server's own rules are HomeStateTest and HomeComposerTest (Java)."""
import json
import sys
from pathlib import Path

from fastapi.testclient import TestClient

CONSOLE = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(CONSOLE))

from core.app import create_app  # noqa: E402
from core.auth import COOKIE  # noqa: E402
from core.backend import BackendError  # noqa: E402
from core.config import Settings, load_settings  # noqa: E402
from tests.conftest import FakeBackend  # noqa: E402

FORM = {"Content-Type": "application/x-www-form-urlencoded"}


def _users():
    base = {"enabled": True, "mustChangePassword": False, "locked": False, "email": "", "lastLoginAt": None}
    return {"qa-risk": {**base, "username": "qa-risk", "displayName": "Rina", "desk": "Risk", "roles": ["risk-manager"]},
            "qa-admin": {**base, "username": "qa-admin", "displayName": "Ada", "desk": "Ops", "roles": ["admin"]},
            "qa-none": {**base, "username": "qa-none", "displayName": "Nia", "desk": "Ops", "roles": ["viewer"]}}


class HomeBackend(FakeBackend):
    """The server's Homes API as the console sees it: per person, with the rules the server keeps (View as is an administrator's)."""

    def __init__(self):
        super().__init__()
        self.state = {}                      # user -> the person's own state, kept "on the server"
        self.calls = []

    def _page(self, user):
        homes = {"qa-risk": ["risk-manager"], "qa-admin": ["risk-manager", "trader", "genomicist"]}.get(user)
        if not homes:
            raise BackendError(404, "DRS-1001", "no Home is offered to you")
        st = self.state.setdefault(user, {"hidden": [], "viewAs": ""})
        admin = user == "qa-admin"
        shown = st["viewAs"] if admin and st["viewAs"] in homes else homes[0]
        cols = 12 if shown == "risk-manager" else 6
        panels = [{"id": p, "title": p.title(), "area": p, "actions": []} for p in ("kpis", "exc", "loads") if p not in st["hidden"]]
        return {"home": shown, "title": shown.title(), "start": homes[0], "picker": len(homes) > 1, "arrange": True, "sutraName": "home-" + shown,
                "homes": [{"home": h, "title": h.title()} for h in homes], "viewAs": {"allowed": admin, "value": st["viewAs"] if admin else ""},
                "hidden": [{"id": h, "title": h.title()} for h in st["hidden"]], "panels": [p["id"] for p in panels], "lastVisit": "",
                "view": {"panels": panels, "layout": {"columns": [1] * cols, "areas": [" ".join(p["id"] for p in panels)]}}}

    async def _send(self, method, path, ident=None, **kw):
        user = getattr(ident, "user", "")
        self.calls.append((method, path, user))
        if path == "/homes/view":
            return self._page(user)
        if path == "/homes/state":
            body, st = kw["json"], self.state.setdefault(user, {"hidden": [], "viewAs": ""})
            if body["action"] == "viewas" and user != "qa-admin":
                raise BackendError(403, "DRS-1003", "only an administrator may view as another Home; choose your start Home instead")
            if body["action"] == "hide":
                st["hidden"] = [*st["hidden"], body["panel"]]
            elif body["action"] == "show":
                st["hidden"] = [h for h in st["hidden"] if h != body["panel"]]
            elif body["action"] == "viewas":
                st["viewAs"] = body["panel"]
            return self._page(user)
        if path == "/homes/me":
            return {"start": kw["json"]["home"]}
        return await super()._send(method, path, ident, **kw)


def _app():
    data = load_settings(CONSOLE / "config").as_dict()
    data["auth"] = {"enabled": True, "session_secret": "s" * 40, "token_secret": "t" * 40, "token_ttl_seconds": 60, "session_hours": 1,
                    "secure_cookie": False, "recheck_seconds": 0}
    app = create_app(Settings(data))
    fake = HomeBackend()
    fake.users = _users()
    fake.passwords = {u: u + "-pass-1" for u in fake.users}
    app.state.backend = fake
    return app, fake


def _in(app, user):
    c = TestClient(app)
    r = c.post("/login", content=f"user={user}&password={user}-pass-1&next=/t", headers=FORM, follow_redirects=False)
    assert r.status_code == 303 and c.cookies.get(COOKIE)
    return c


def test_home_is_one_page_and_carries_the_document_the_server_composed():
    app, _ = _app()
    page = _in(app, "qa-risk").get("/home")
    assert page.status_code == 200 and "data-role-home=\"risk-manager\"" in page.text and "drishti_role-home.js" in page.text
    assert 'data-layout="cockpit"' in page.text, "twelve columns: the Cockpit"
    assert "drishti_home-cards" not in page.text, "the cards page is gone"
    assert _in(app, "qa-risk").get("/api/home/view").json()["view"]["panels"][0]["id"] == "kpis"


def test_the_old_pages_are_gone_and_a_person_with_no_home_goes_to_the_landing():
    app, _ = _app()
    c = _in(app, "qa-risk")
    assert c.get("/api/home/cards").status_code in (404, 405) and c.get("/home/role", follow_redirects=False).status_code in (404, 405)
    r = _in(app, "qa-none").get("/home", follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/"


def test_state_actions_reach_the_server_as_the_person_and_come_back_as_the_new_home():
    app, fake = _app()
    c = _in(app, "qa-risk")
    got = c.post("/api/home/state", json={"action": "hide", "panel": "exc"}).json()
    assert got["panels"] == ["kpis", "loads"] and got["hidden"] == [{"id": "exc", "title": "Exc"}]
    assert ("POST", "/homes/state", "qa-risk") in fake.calls
    assert c.post("/api/home/state", json={"action": "show", "panel": "exc"}).json()["panels"] == ["kpis", "exc", "loads"]
    assert c.post("/api/home/state", content="not json", headers={"Content-Type": "application/json"}).status_code == 400


def test_view_as_is_an_administrators_and_the_server_refuses_everyone_else():
    app, _ = _app()
    risk = _in(app, "qa-risk")
    refused = risk.post("/api/home/state", json={"action": "viewas", "panel": "trader"})
    assert refused.status_code == 403 and "administrator" in refused.json()["detail"]
    assert risk.get("/api/home/view").json()["viewAs"] == {"allowed": False, "value": ""}
    admin = _in(app, "qa-admin")
    got = admin.post("/api/home/state", json={"action": "viewas", "panel": "trader"}).json()
    assert got["home"] == "trader" and got["viewAs"] == {"allowed": True, "value": "trader"}
    assert risk.get("/api/home/view").json()["home"] == "risk-manager", "one person's choice is not another's"
    page = admin.get("/home").text
    assert 'data-role-home="trader"' in page and 'data-layout="calm"' in page


def test_the_start_home_is_chosen_on_the_server():
    app, fake = _app()
    c = _in(app, "qa-admin")
    assert c.put("/api/home/me", json={"home": "trader"}).json() == {"start": "trader"}
    assert ("PUT", "/homes/me", "qa-admin") in fake.calls
