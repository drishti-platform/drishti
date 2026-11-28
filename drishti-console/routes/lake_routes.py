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

"""Admin → Data loads → Lakes (/admin/lakes): the lakes and tables as the server answers them (its lake files, the figures of each table read
from its own log, its in-memory lake, the lakes' run logs) and a table's page in full. The console opens no lake folder: every figure is
the server's (``GET /api/v1/admin/lakes/…``, docs/guides/API_REFERENCE.md). A file is loaded into a table through the data inbox
(Admin → Data loads → Upload), the server's one way. Lake maintenance, which still runs the tools beside the lakes, is on this page only
on a console that has them (``Lakes.here``; core/lake_maint.py). Administrators only."""
from __future__ import annotations

import asyncio
from pathlib import Path

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from core import lakes as server_lakes
from core.backend import BackendError
from core.lakes import LakeError, Lakes, LakeSettings
from routes.common import ident, render

router = APIRouter(prefix="/admin", include_in_schema=False)


def lakes_of(app) -> Lakes:
    st = app.state
    if not hasattr(st, "lakes"):
        st.lakes = Lakes(LakeSettings.from_settings(st.settings, Path(__file__).resolve().parent.parent))
    return st.lakes


def _lakes(request: Request) -> Lakes:
    return lakes_of(request.app)


def _err(e: LakeError) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status)


def _admin(request: Request):
    me = ident(request)
    if not me.is_admin:
        raise LakeError(403, "administrators only", "DRS-5002")
    return me


@router.get("/lakes")
async def page(request: Request):
    me = ident(request)
    if not me.is_admin:
        return render(request, "admin/forbidden.html", status_code=403)
    lakes = _lakes(request)
    backend = request.app.state.backend
    try:
        status = await backend.admin("GET", "/status", me)
    except BackendError:
        status = {}
    try:
        figures = await server_lakes.tables(backend, me)
    except BackendError:                  # the screen opens without the table rather than not at all
        figures = []
    try:
        runs = await server_lakes.runs(backend, me, 10)
    except BackendError:
        runs = []
    maint = None
    if lakes.here():
        try:
            from core.lake_maint import maintenance
            maint = await asyncio.to_thread(maintenance(request.app).overview, [t for t in figures if t.get("root")])
        except Exception:                 # noqa: BLE001 - without the tools folder or with a broken file the tables are listed without Maintain
            maint = None
    memory, lake_files = await _servers_lakes(request, me)
    return render(request, "admin/lakes.html", status=status, runs=runs, tables=figures, figures={f"{t.get('lake')}/{t.get('name')}": t for t in figures}, maint=maint, memory=memory, lake_files=lake_files)


async def _servers_lakes(request: Request, me) -> tuple[dict | None, dict | None]:
    """The server's own answers: its in-memory lake (docs/guides/DATA_LAKE_GUIDE.md: ids and counts only, never a value of the data) and
    its lake files (every lake and table as the server reads them, read-only); None for one it does not give."""
    try:
        memory = await request.app.state.backend.admin("GET", "/lakes/memory", me)
    except BackendError:
        memory = None
    try:
        lake_files = await request.app.state.backend.admin("GET", "/lakes/files", me)
    except BackendError:
        lake_files = None
    return (memory if memory and memory.get("stores") else None), lake_files


@router.get("/lakes/table")
async def table_page(request: Request, lake: str = "", table: str = ""):
    """One table of a lake in full: its business dates, columns, loads, maintenance and the connectors that read it, as the server
    answers (``table`` is the table's symbolic name in the lake file ``lake``)."""
    me = ident(request)
    if not me.is_admin:
        return render(request, "admin/forbidden.html", status_code=403)
    backend = request.app.state.backend
    try:
        t = await server_lakes.table_detail(backend, me, lake, table)
    except BackendError as e:
        return render(request, "admin/lake_table.html", t=None, lake=lake, table=table, status_code=e.status if e.status in (403, 404) else 502)
    if t is None:
        return render(request, "admin/lake_table.html", t=None, lake=lake, table=table, status_code=404)
    maint = None
    if t.get("root") and not t.get("error") and _lakes(request).here():
        try:
            from core.lake_maint import maintenance
            over = await asyncio.to_thread(maintenance(request.app).overview, [t])
            maint = next((m for m in over["tables"] if m["table"] == t.get("table")), None)
        except Exception:                 # noqa: BLE001 - the page opens without maintenance rather than not at all
            pass
    readers = await _readers(request, me, t)
    return render(request, "admin/lake_table.html", t=t, lake=lake, table=table, loads=t.get("loads") or [], maint=maint, readers=readers)


async def _readers(request: Request, me, t: dict) -> list[dict]:
    """The connectors that read this table: those whose kinds include the table's kind (a connector naming the lake names its tables)."""
    out = []
    try:
        listed = (await request.app.state.backend.admin("GET", "/connectors", me)).get("connectors", [])
        for c in listed:
            if c.get("plugin") not in ("delta", "iceberg"):
                continue
            d = await request.app.state.backend.admin("GET", f"/connectors/{c['name']}", me)
            st = d.get("settings") or {}
            if st.get("lake"):                    # a connector naming a lake (DATA_LAKE_GUIDE.md): its tables are the lake file's entries
                if st["lake"] != t.get("lake"):
                    continue
                named = [x.strip() for x in str(st.get("lake-tables") or "").split(",") if x.strip()]
                if t.get("name") in named or not named and t.get("kind") in (d.get("kinds") or []):
                    out.append({"name": c["name"], "kind": t.get("kind"), "state": c.get("state"), "health": c.get("health")})
                continue
            if st.get("domain") != t.get("domain"):
                continue
            for k in d.get("kinds") or []:
                if st.get(f"table.{k}", k) == t.get("folder"):
                    out.append({"name": c["name"], "kind": k, "state": c.get("state"), "health": c.get("health")})
    except BackendError:
        pass
    return out
