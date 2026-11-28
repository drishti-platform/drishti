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

"""Your Home, the console's side (docs/architecture/ROLE_HOME_SUTRAS.md): ``/home`` is the one Home page of everyone signed in. The server decides which
Homes the person is offered (their role, the modules on, the active mnemonics), binds the Home's Sutra over the home tables *as the person* (row rules
and masks applied there) and keeps the person's own state of it (pins, hidden and moved panels, snoozes, the visit, the start Home and, for an
administrator, "View as"); this passes the document it composed to the page and the person's actions back. No figure is computed here and no panel is
drawn here: the page hands the ViewModel to the Drishti Elements painter (drishti-views). Nothing per person is kept in this process."""
from __future__ import annotations

import json

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, RedirectResponse

from core.backend import BackendError
from core.csrf import BodyError, json_body
from routes.common import ident, render

router = APIRouter(include_in_schema=False)


async def _send(request: Request, method: str, path: str, **kw):
    return await request.app.state.backend._send(method, path, ident(request), **kw)


def _lens_home(request: Request) -> dict:
    """The Home the person's lens opens (``home.home`` of the lens's file), passed on as the default start: the server uses it only for a person
    who has not chosen a start Home of their own, and only when it is one offered to them."""
    from routes.edition_routes import edition_of
    name = edition_of(request).home_name
    return {"params": {"home": name}} if name else {}


def _failed(e: BackendError) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status if 400 <= (e.status or 0) < 600 else 502)


@router.get("/home")
async def home(request: Request):
    """The person's Home: the page is a shell; the first paint's ViewModel rides in it (one read, no flash of an empty page). A person who is
    offered no Home goes to the public landing."""
    try:
        got = await _send(request, "GET", "/homes/view", **_lens_home(request))
    except BackendError as e:
        if e.status in (403, 404):
            return RedirectResponse("/?home=none" if _lens_home(request) else "/", status_code=303)   # the public landing ("?home=none": "/" does not send a lens back here)
        raise
    cols = ((got.get("view") or {}).get("layout") or {}).get("columns") or []
    layout = "cockpit" if len(cols) >= 12 else "calm"
    return render(request, "role_home.html", home=got, layout=layout, data_json=json.dumps(got))


@router.get("/api/home/view")
async def view(request: Request):
    try:
        return JSONResponse(await _send(request, "GET", "/homes/view", **_lens_home(request)), headers={"Cache-Control": "no-store"})
    except BackendError as e:
        return _failed(e)


@router.post("/api/home/state")
async def state(request: Request):
    """One change to the person's own Home (pin, unpin, hide, show, up, down, snooze, reset, visited, viewas); the server checks it (View as is an
    administrator's) and answers the Home as it now is."""
    try:
        body = await json_body(request, limit=4096)
    except BodyError as e:
        return JSONResponse({"code": "DRS-5001", "detail": str(e)}, status_code=400)
    try:
        got = await _send(request, "POST", "/homes/state", json={"action": str(body.get("action") or ""), "panel": str(body.get("panel") or ""),
                                                                  "keys": [str(k) for k in body.get("keys") or [] if isinstance(k, str)][:20]},
                          **_lens_home(request))
    except BackendError as e:
        return _failed(e)
    return JSONResponse(got, headers={"Cache-Control": "no-store"})


@router.put("/api/home/me")
async def choose(request: Request):
    """The person's own start Home (their setting ``home``); the server refuses a Home they are not offered."""
    try:
        body = await json_body(request, limit=1024)
    except BodyError as e:
        return JSONResponse({"code": "DRS-5001", "detail": str(e)}, status_code=400)
    try:
        got = await _send(request, "PUT", "/homes/me", json={"home": str(body.get("home") or "")})
    except BackendError as e:
        return _failed(e)
    return JSONResponse(got or {})
