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

"""The lens (docs/guides/ADMIN_GUIDE.md › The design; docs/guides/ADMIN_GUIDE.md).

A lens names the Home it opens (``home.home`` of its file: a role Home of the server, docs/architecture/ROLE_HOME_SUTRAS.md); the page is the one
Home page (``/home``, routes/role_home_routes.py), the same mechanism as every role's Home.

The lens: ``/lens/<name>`` opens an edition the server offers as a lens (``drishti.lenses``, one or more) for this person; opening another
while in one switches; ``/lens/leave`` closes it. Kept in the person's settings (``lens``), so every server of a cluster and every console
worker sees it. A lens hides; it does not secure.
"""
from __future__ import annotations

from urllib.parse import quote

from fastapi import APIRouter, Request
from fastapi.responses import RedirectResponse

from core.backend import BackendError
from core.edition import Edition
from routes.common import ident, render

router = APIRouter(include_in_schema=False)


def edition_of(request) -> Edition:
    return getattr(request.state, "edition", None) or request.app.state.editions.instance


def _confirm(request: Request, action: str, title: str, text: str):
    """A change a link asks for answers a GET with its form: only a POST changes anything (drishti_theme-init.js posts the links directly)."""
    return render(request, "confirm_post.html", action=action, title=title, text=text)


@router.get("/lens/leave")
async def leave_lens_form(request: Request):
    return _confirm(request, "/lens/leave", "Leave the lens", "Back to the full product.")


@router.get("/lens/{name}")
async def open_lens_form(request: Request, name: str):
    if getattr(request.state, "identity", None) is None:
        return RedirectResponse(f"/login?next={quote('/lens/' + name)}", status_code=303)
    return _confirm(request, f"/lens/{quote(name)}", "Open the lens", f"See the product through the {name} lens.")


@router.post("/lens/leave")
async def leave_lens(request: Request):
    """Out of the person's lens (their settings forget it): back to the distribution's starting lens, or the full product."""
    if getattr(request.state, "identity", None) is not None:
        await _patch(request, {"lens": None})
    return RedirectResponse("/", status_code=303)


@router.post("/lens/{name}")
async def open_lens(request: Request, name: str):
    """Opens a lens (a workspace) for this person: only one the server offers (drishti.lenses)."""
    editions = request.app.state.editions
    if getattr(request.state, "identity", None) is None:
        return RedirectResponse(f"/login?next={quote('/lens/' + name)}", status_code=303)
    if name not in editions.lenses:
        return RedirectResponse(f"/t?error={quote(f'DRS-4023 no lens {name} on this server (drishti.lenses)')}", status_code=303)
    await _patch(request, {"lens": name})
    return RedirectResponse("/", status_code=303)


async def _patch(request, values: dict) -> None:
    me = ident(request)
    try:
        await request.app.state.backend.patch_settings(values, me)
    except BackendError:
        pass
    request.app.state.user_settings.forget(me.user if me else "", request)
