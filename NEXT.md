# i8/merge3 -- what remains (paused 2026-11-28 by the lead)

Done: merged origin/i8/develop (6f903a4e3); CONFIGURATION.md regenerated; full reactor install OK; PackAccessTest (503 DRS-1037) and
GenomicsDistributionServerTest green; genomics pack fixed (feed tables on their own connector genomics-feeds, no mode.effective, README regenerated).

Remaining (heavy, wait for the lead):
1. Rebuild dmods is current (packaging/dist installed after the last pack edit); if sources changed run `drishti.py build modules genomics`.
2. tools suites: docs+sdk+packform, loaders+lake, testdata (script ~/drishti-bench-scratch/i8-merge3b-tools.sh; needs current dmod).
3. console non-browser suite (~/drishti-bench-scratch/i8-merge3b-con.sh): was at ~37% with one F (not identified; log pt-merge3b/console.log, killed).
4. RiskSideBySideTest: not a deadlock, very slow (thePnlPathOfEveryGoldenNodeIsTheSameOnBothPaths, 2 requests per golden node, >15 min); thread dumps in ~/drishti-bench-scratch/m3b-*-dump*.txt. Killed at 10:23.
5. ConsumersSideBySideTest annual-report-var / var-backtesting-chart: R2 lane V fixing on r2/vres (not the merge; uri/contract lines ruled out).
6. If green: push i8/merge3, fast-forward i8/develop. Remove Maven copy ~/drishti-bench-scratch/i8-m2-merge3b at the end.
