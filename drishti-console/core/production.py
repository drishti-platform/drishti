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

"""The console under the production profile (docs/admin/PRODUCTION_STANDARD.md, section 1): the same profile name as the server
(``drishti.profile=production`` in a settings file, ``DRISHTI_PROFILE``, or ``profile`` in the console's configuration). Under it the
console refuses to start while anything unsafe is set: one line per setting, naming the setting and the fix. Demo and development
(none) are never refused; a name outside the one list (``config/profiles/profile-names.json``, shared with the server and the upgrade check)
always is. Standard library only; pure over :class:`core.config.Settings` (``refusals``), with one place that
stops the process (``enforce``)."""
from __future__ import annotations

import functools
import ipaddress
import json
import os
from pathlib import Path

PRODUCTION = "production"
#: addresses that reach this machine only
_LOOPBACK_NAMES = {"localhost"}


#: the one list of profile names, shared with the server (built into its jar) and the upgrade check
NAMES_FILE = Path(__file__).resolve().parents[2] / "config" / "profiles" / "profile-names.json"


@functools.lru_cache(maxsize=1)
def spellings() -> dict[str, str]:
    """Every accepted spelling (lower case) to its profile name, from :data:`NAMES_FILE`."""
    names = json.loads(NAMES_FILE.read_text(encoding="utf-8"))["names"]
    out: dict[str, str] = {}
    for name, other in names.items():
        out[name.lower()] = name.lower()
        out.update({str(x).strip().lower(): name.lower() for x in other})
    return out


def known() -> str:
    """The known names in words: ``development (or blank), demo, production (or prod)``."""
    by: dict[str, list[str]] = {}
    for spelt, name in spellings().items():
        by.setdefault(name, []).append(spelt)
    words = []
    for name, spelt in by.items():
        other = [x or "blank" for x in spelt if x != name]
        words.append(f"{name} (or {', '.join(other)})" if other else name)
    return ", ".join(words)


def profile_name(settings) -> str:
    """``production``, ``demo`` or ``development`` (none set); a name outside the one list is returned as typed and refused by :func:`refusals`."""
    raw = str(settings.get("profile", "") or "").strip().lower()
    return spellings().get(raw, raw)


def is_production(settings) -> bool:
    return profile_name(settings) == PRODUCTION


def _loopback(host: str) -> bool:
    h = (host or "").strip().strip("[]").lower()
    if h in _LOOPBACK_NAMES:
        return True
    try:
        return ipaddress.ip_address(h).is_loopback
    except ValueError:
        return False


def refusals(settings) -> list[str]:
    """Every unsafe setting of this console, one line each (empty: safe to start; outside production only an unknown profile name)."""
    name = profile_name(settings)
    if name not in spellings().values():
        return [f"profile (drishti.profile, DRISHTI_PROFILE) is {name!r}, which is not a profile name, so the console cannot tell whether this is "
                f"production. Known: {known()}. Fix: set one of these (production for a production console), or remove it for development"]
    if not is_production(settings):
        return []
    out: list[str] = []
    if not bool(settings.get("auth.enabled", False)):
        out.append("auth.enabled is off: anyone who reaches the console is an administrator. Fix: set DRISHTI_AUTH_ENABLED=true (console auth.enabled)")
    if len(str(settings.get("auth.session_secret") or "")) < 32:
        out.append("auth.session_secret (DRISHTI_SESSION_SECRET) is missing or shorter than 32 characters. Fix: set a random secret of at least 32 characters")
    if len(str(settings.get("auth.token_secret") or "")) < 32:
        out.append("auth.token_secret (DRISHTI_TOKEN_SECRET) is missing or shorter than 32 characters. Fix: set the server's drishti.security.secret here too")
    if not bool(settings.get("auth.secure_cookie", True)):
        out.append("auth.secure_cookie is off: the session cookie would travel over plain HTTP. Fix: remove DRISHTI_SECURE_COOKIE=false (cookies are also HttpOnly and SameSite=Lax)")
    host = str(settings.get("server.host", "") or "").strip()
    tls = str(settings.get("production.tls", "") or "").strip().lower()
    if not _loopback(host) and tls != "proxy":
        shown = host or "(every interface)"
        out.append(f"server.host is {shown}: the console would be reached over plain HTTP from the network. Fix: bind 127.0.0.1 "
                   "(DRISHTI_CONSOLE_HOST) behind a proxy that ends TLS, or state that proxy with production.tls=proxy (DRISHTI_PRODUCTION_TLS=proxy)")
    site = (os.environ.get("DRISHTI_SITE_DIR") or "").strip()
    install = Path(__file__).resolve().parents[2]
    if not site:
        out.append("drishti.site.dir is not set: the console's approved settings would be written into the release folder. Fix: name the site folder outside the release "
                   "(DRISHTI_SITE_DIR, /etc/drishti), in the settings file the server reads too (the server refuses a site folder inside the release)")
    elif not Path(site).is_absolute():
        out.append(f"drishti.site.dir ({site}) is a relative path. Fix: name the site folder by its absolute path outside the release (/etc/drishti)")
    elif Path(site).resolve() == install or install in Path(site).resolve().parents:
        out.append(f"drishti.site.dir ({site}) is inside the console's release folder ({install}), which is never written. Fix: name a site folder outside the release (/etc/drishti)")
    return out


def message(lines: list[str]) -> str:
    head = f"The console will not start under the production profile (docs/admin/PRODUCTION_STANDARD.md): {len(lines)} setting{'s are' if len(lines) != 1 else ' is'} unsafe"
    return "\n".join([head] + [f"  - {line}" for line in lines])


def enforce(settings) -> None:
    """Stop the process with every refusal (a real start only; tests call :func:`refusals`)."""
    lines = refusals(settings)
    if lines:
        raise SystemExit(message(lines))
