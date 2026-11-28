# I8 carry-in

## Windows workflow (windows.yml, branch i8/windows-docs): state for i9

- Last run: 46166152450 (still in progress when stopped; check `gh run view 46166152450 -R drishti-platform/drishti`). Lake is built from tag v0.9.2 (carries the `\` split fix in ConnectorLake).
- Fixed so far: RunServiceTest twin test (latch instead of timing); route-inventory guard (`/` keys, TestRoots.repo()); TableCataloguePaths allow-list for LakeTableFigures; cluster tests' budgets scale with MachineSpeed (env DRISHTI_TEST_SLOW_FACTOR=3 on the server-test step: NetGossipBusy, NetSecurity, NetHttp2); PackReloader.checkNow() (Admin actions wait for a running reload; ArtifactApiTest failed 4x on Windows because check() returned on a busy lock).
- Previous run 46162949006: only ArtifactApiTest failed (cause above, fix in the last run). Server suite takes about 15-20 min on the runner.
- Never yet reached on Windows: Python small lake (make_data.py, load_rows.py), build jar, setup/--check, start/stop server steps. First thing in i9: read the last run's result, then those steps.
- BiFoundationParityTest stays skipped on Windows (written reason).
