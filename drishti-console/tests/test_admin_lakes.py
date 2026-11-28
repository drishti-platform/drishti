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

"""Admin → Data loads → Lakes: the lakes and tables are the SERVER's answers (``/api/v1/admin/lakes/tables``, ``…/tables/detail``, ``…/runs``,
``…/files``): the console opens no lake folder and no longer builds a lake itself (a file goes through the data inbox, Admin → Data loads →
Upload). The console's own lake settings are lake maintenance's only.

    python -m pytest drishti-console/tests/test_admin_lakes.py -q
"""
from pathlib import Path

import pytest

from core.backend import BackendError
from core.lakes import Lakes, LakeSettings, figure_of

REPO = Path(__file__).resolve().parents[2]

TABLE = {"lake": "demo", "root": "/srv/lakes/demo", "name": "book.deal", "kind": "deal", "location": "/srv/lakes/demo/book/deal", "format": "delta",
         "how": "configured", "domain": "book", "folder": "deal", "rows": 1200, "files": 6, "bytes": 3 << 20, "partitionBy": ["PRODUCT_TYPE"],
         "partitions": 6, "dates": 2, "firstDate": "2026-10-01", "latestDate": "2026-10-02", "version": 3, "lastBuild": "2026-10-02 21:30:00Z"}
DETAIL = {**TABLE, "partitionColumns": ["business_date", "PRODUCT_TYPE"], "idColumn": "id",
          "byDate": [{"date": "2026-10-02", "rows": 600, "files": 3, "bytes": 1 << 20, "partitions": [["PRODUCT_TYPE=Swap", 300], ["PRODUCT_TYPE=Future", 300]]},
                     {"date": "2026-10-01", "rows": 600, "files": 3, "bytes": 1 << 20, "partitions": []}],
          "columns": [{"name": "id", "type": "string", "partition": False, "promoted": False}, {"name": "doc", "type": "string", "partition": False, "promoted": False},
                      {"name": "NOTIONAL", "type": "double", "partition": False, "promoted": True},
                      {"name": "business_date", "type": "date", "partition": True, "promoted": False}],
          "loads": [{"run": "r1", "startedAt": "2026-10-02T21:30:00Z", "status": "done", "mode": "overwrite-dates", "dates": "2026-10-02", "rows": 600,
                     "rejected": 0, "seconds": 2.5, "engine": "java", "error": None}]}


def fake(backend, monkeypatch, calls=None, tables=(TABLE,), detail=DETAIL, lake_files=None, connector=None):
    async def admin(method, path, ident, body=None, timeout=None, headers=None, **params):
        if calls is not None:
            calls.append((method, path))
        if path == "/status":
            return {}
        if path == "/lakes/tables":
            return {"tables": list(tables)}
        if path.startswith("/lakes/tables/detail"):
            if detail is None or "name=book.deal" not in path:
                raise BackendError(404, "DRS-1001", "no such table")
            return detail
        if path.startswith("/lakes/runs"):
            return {"runs": [{"lake": "demo", "line": "2026-10-02T21:30:00Z lake-build done book/deal rows=600"}]}
        if path == "/lakes/files":
            return lake_files if lake_files is not None else {"dir": "/srv/config/lakes", "lakes": [
                {"lake": "demo", "format": "delta", "root": "/srv/lakes/demo", "tables": [
                    {"name": "book.deal", "kind": "deal", "location": "/srv/lakes/demo/book/deal", "format": "delta", "contract": "deal@1", "how": "configured",
                     "partition-columns": ["PRODUCT_TYPE"], "promote": ["NOTIONAL"], "vectors": [], "id": "TRADE_ID", "load-mode": "replace-date"}]}]}
        if path == "/connectors" and method == "GET":
            return {"connectors": [{"name": "book-store", "plugin": "delta", "state": "RUNNING"}]}
        if path == "/connectors/book-store" and method == "GET":
            return connector or {"settings": {"lake": "demo", "lake-tables": "book.deal"}, "kinds": ["deal"]}
        return {}
    monkeypatch.setattr(backend, "admin", admin)


@pytest.fixture
def lakes(client, tmp_path):
    lk = Lakes(LakeSettings(roots=[], tools_dir=tmp_path / "no-tools"))          # a console without the tools: no maintenance, the pages still answer
    client.app.state.lakes = lk
    yield lk
    del client.app.state.lakes


def test_the_page_lists_the_servers_tables_with_their_figures_and_points_to_the_upload(client, backend, monkeypatch, lakes):
    calls = []
    fake(backend, monkeypatch, calls)
    page = client.get("/admin/lakes")
    assert page.status_code == 200
    t = page.text
    for text in ("book.deal", "1,200", "2026-10-02", "2026-10-02 21:30:00Z", "lake-build done book/deal", "/admin/loads/upload", "Recent builds"):
        assert text in t, text
    assert "/admin/lakes/table?lake=demo&amp;table=book.deal" in t                       # the table's own page, by its symbolic name
    for gone in ("data-file", "Id field", "drishti_admin-lakes.js", "Bind the lake to the pack", "data-maint"):
        assert gone not in t, gone                                                       # no build wizard, no maintenance without tools
    assert {c[1] for c in calls} >= {"/lakes/tables", "/lakes/files"}
    assert 'href="/admin/lakes"' in client.get("/admin/loads").text


def test_a_table_the_server_cannot_read_says_why_beside_its_name(client, backend, monkeypatch, lakes):
    fake(backend, monkeypatch, tables=({**TABLE, "rows": None, "error": "the table is not on this server's local disk"},))
    assert "not on this server" in client.get("/admin/lakes").text


def test_a_table_opens_its_own_page_with_dates_columns_loads_and_readers(client, backend, monkeypatch, lakes):
    fake(backend, monkeypatch)
    page = client.get("/admin/lakes/table", params={"lake": "demo", "table": "book.deal"})
    assert page.status_code == 200
    t = page.text
    dates = t[t.index("data-lt-dates"):]
    assert dates.index("2026-10-02") < dates.index("2026-10-01")                         # each business date, newest first
    assert ">NOTIONAL<" in t and "promoted field" in t and "the whole document" in t    # columns and their roles
    assert "data-lt-loads" in t and "java" in t                                         # lake build's record of the load
    assert 'href="/admin/connectors/book-store"' in t                                   # the connector that names this table
    assert "/srv/lakes/demo/book/deal" in t
    assert client.get("/admin/lakes/table", params={"lake": "demo", "table": "book.nothing"}).status_code == 404


def test_the_old_build_routes_are_gone(client, backend, monkeypatch, lakes):
    fake(backend, monkeypatch)
    for method, path in (("post", "/admin/api/lakes/upload"), ("get", "/admin/api/lakes/targets"), ("post", "/admin/api/lakes/jobs")):
        assert getattr(client, method)(path).status_code in (404, 405), path


def test_figure_of_names_the_table_by_its_folder_and_derives_the_root():
    f = figure_of(TABLE)
    assert (f["table"], f["root"], f["size"]) == ("book/deal", "/srv/lakes/demo", "3.0 MB")
    assert "table" not in figure_of({**TABLE, "location": "s3://bucket/deal", "folder": None, "domain": None})      # an object store: no folder table


def test_the_settings_are_lake_maintenances_only(tmp_path):
    s = LakeSettings.from_settings({"lakes.data_dir": "/srv/drishti-data"}, REPO / "drishti-console")
    assert s.roots == [] and s.lake_roots() == [] and s.data_dir == Path("/srv/drishti-data")       # nothing is found by walking a folder
    s = LakeSettings.from_settings({"lakes.roots": "/a,/b"}, REPO / "drishti-console")
    assert s.lake_roots() == [Path("/a"), Path("/b")]
    s.seen.append(Path("/c"))
    assert s.lake_roots() == [Path("/a"), Path("/b"), Path("/c")]
