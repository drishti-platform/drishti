"""No risk maths in the browser (owner rule: the server computes every risk number; the console only displays).

Calc's folder static/calc/ holds the Python runtime's modules only: drishti.py and drishti_bi.py (they ask the server) and
drishti_quant.py (the snippets' pricing maths until the QLRisk engine replaces it; test_quant.py allows pricing names only).
Console JS must not carry a risk formula, and the risk statistics Calc uses are the server's (drishti.stats)."""
import re
from pathlib import Path

WEB = Path(__file__).resolve().parents[1] / "web" / "static"
ALLOWED = {"drishti.py", "drishti_bi.py", "drishti_quant.py"}
RISK_NAMES = re.compile(r"\b(?:def\s+|function\s+)(historical_var|expected_shortfall|parametric_var|kupiec_pof|max_drawdown|"
                        r"frtb_bucket_charge|frtb_across_buckets|hhi|sharpe|histVar|expectedShortfall|kupiec)\b", re.I)


def test_the_calc_folder_holds_only_the_runtime_modules():
    assert {p.name for p in (WEB / "calc").iterdir() if p.is_file()} == ALLOWED


def test_no_console_javascript_or_python_module_computes_risk():
    for f in [*(WEB / "js").glob("*.js"), WEB / "calc" / "drishti.py", WEB / "calc" / "drishti_bi.py"]:
        for n, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
            assert not RISK_NAMES.search(line), f"{f.name}:{n}: {line.strip()}"


def test_calc_asks_the_server_for_its_statistics():
    py = (WEB / "calc" / "drishti.py").read_text(encoding="utf-8")
    js = (WEB / "js" / "drishti_calc.js").read_text(encoding="utf-8")
    assert "def stats(" in py and "async def stats_async(" in py
    assert "/api/calc/stats/" in js
