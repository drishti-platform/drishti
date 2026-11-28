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
"""Admin → Data loads → Lakes: what the page shows of the server's lakes, and the settings of the console's lake maintenance.

The console reads no lake folder. Every table's figures, its detail (business dates, columns, loads) and the lakes' run logs are the
server's answers (``GET /api/v1/admin/lakes/tables``, ``…/tables/detail``, ``…/runs``; docs/guides/API_REFERENCE.md): a table is found by
its symbolic name in its lake file, wherever it sits. A file is loaded into a table through the server's one way, the data inbox
(Admin → Data loads → Upload: ``POST /api/v1/admin/dataload/upload``), never into a lake folder by the console.

What remains on the console's machine is lake maintenance (``core/lake_maint.py``), which runs the tools' ``lake maintain`` beside the
lakes until the server offers the same as an API: its settings are :class:`LakeSettings`.
"""
from __future__ import annotations

import sys
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import urlencode

from core.backend import BackendError


class LakeError(Exception):
    def __init__(self, status: int, detail: str, code: str = "DRS-5001"):
        super().__init__(detail)
        self.status, self.detail, self.code = status, detail, code


@dataclass
class LakeSettings:
    """The settings of the console's lake maintenance: the tools it runs, the folder of its own YAML and history, and the roots it may
    maintain (``lakes.roots``, plus the roots of the tables the server lists)."""

    roots: list[Path]
    tools_dir: Path
    data_dir: Path | None = None
    schedules: bool = True                       # run the lake-maintenance schedules in this console
    seen: list[Path] = field(default_factory=list)   # the roots of the local tables the server listed (set by the page)

    def lake_roots(self) -> list[Path]:
        out = list(self.roots)
        out += [r for r in self.seen if r not in out]
        return out

    @classmethod
    def from_settings(cls, s, console_dir: Path) -> "LakeSettings":
        def path(p):
            d = Path(str(p)).expanduser()
            return d if d.is_absolute() else (console_dir / d).resolve()
        given = s.get("lakes.roots", None)
        if isinstance(given, str):
            given = [r for r in given.split(",") if r.strip()]
        return cls(roots=[path(r) for r in given or []], tools_dir=path(s.get("builder.tools_dir", "../tools")),
                   data_dir=path(s.get("lakes.data_dir")) if s.get("lakes.data_dir", None) else None,
                   schedules=str(s.get("lakes.maintenance_schedules", True)).lower() not in ("false", "0", "off", "no"))


def _tool(tools_dir: Path, name: str):
    """tools/lake/<name>.py, imported by path (lake maintenance needs the repository's tools/ folder: builder.tools_dir)."""
    folder = tools_dir / "lake"
    if not (folder / f"{name}.py").is_file():
        raise LakeError(501, f"tools/lake/{name}.py is not here: the console needs the repository's tools/ folder (builder.tools_dir)")
    if str(folder) not in sys.path:
        sys.path.insert(0, str(folder))
    return __import__(name)


class Lakes:
    """The settings holder the maintenance works over (the tables themselves come from the server)."""

    def __init__(self, cfg: LakeSettings):
        self.cfg = cfg

    def here(self) -> bool:
        """Whether the tools' lake engine is beside this console (lake maintenance runs here); a console elsewhere offers none."""
        return (self.cfg.tools_dir / "lake" / "maintain.py").is_file()


def human_bytes(n: int | None) -> str:
    if not n:
        return "0 B"
    n = float(n)
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return f"{n:,.0f} {unit}" if unit == "B" else f"{n:,.1f} {unit}"
        n /= 1024
    return str(n)


def figure_of(t: dict) -> dict:
    """One table as the server answers it, in the shape the lake pages and maintenance read: ``table`` is ``<domain>/<folder>`` and
    ``root`` the folder above the domain, where the table sits on this server's disk (a table elsewhere has neither)."""
    out = dict(t)
    out["size"] = human_bytes(t.get("bytes"))
    loc = t.get("location") or ""
    if t.get("domain") and t.get("folder") and "://" not in loc:
        out["table"] = f"{t['domain']}/{t['folder']}"
        out["path"] = loc
        out["root"] = str(Path(loc).parent.parent)
    return out


async def tables(backend, me) -> list[dict]:
    """Every table of every lake file with its figures (the server's ``GET /api/v1/admin/lakes/tables``)."""
    return [figure_of(t) for t in ((await backend.admin("GET", "/lakes/tables", me)) or {}).get("tables") or []]


async def table_detail(backend, me, lake: str, name: str) -> dict | None:
    """One table in full, or None when the server has no such table."""
    try:
        return figure_of(await backend.admin("GET", "/lakes/tables/detail?" + urlencode({"lake": lake, "name": name}), me))
    except BackendError as e:
        if e.status == 404:
            return None
        raise


async def runs(backend, me, limit: int = 10) -> list[dict]:
    """The newest lines of the lakes' run logs."""
    return ((await backend.admin("GET", f"/lakes/runs?limit={int(limit)}", me)) or {}).get("runs") or []
