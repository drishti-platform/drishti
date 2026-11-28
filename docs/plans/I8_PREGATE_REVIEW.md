<!--
  Project Drishti · Any data. Any domain. One grammar.

  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
  All rights reserved.

  PROPRIETARY AND CONFIDENTIAL.

  This file is the confidential and proprietary property of Ashutosh Sinha.
  Unauthorised copying, use, modification, distribution or disclosure of this
  file, via any medium, is strictly prohibited except with the express prior
  written permission of the copyright holder.
-->

# i8 pre-gate review (Fable)

Written 2026-11-28 10:50 EST. Read-only review by `git show` / `git diff` on refs; nothing was built or run, so every finding is from reading code.
Purpose: find what must be fixed before the i8 drill now, so the final i8 gate is short.

**Verdict: FIX. 3 blockers, 22 should-fix, 12 nits.** The blockers are small (about half a day together); none needs a redesign.

Refs read: `i8/develop` 6f903a4e3 (changes since tag v2.9.11.i_7n, bd803e0fe), `i8/merge3` 38fceb15e, `i8/console-lake` 382a7fb62,
`i8/modules` 846349771, `i8/rachana-11` fc40fea0d, `i8/windows-docs` 0cbe54e1e, `i8/release-docs` 43e2f6007, `i8/docs-wave` 2a34904f9.
Not in scope: the R2 engine speed lanes (`r2/speed-*`) and the engine host internals.

Not covered (for the final gate): engine host and lifecycle internals line by line, console-lake templates and browser tests,
ExternalLakeTest, OPERATIONS_GUIDE 5.7 and ADMIN_GUIDE 2.7/15.1 line by line, the feeds README commands, GenomicsHome changes on merge3.

## 1. Blockers (fix before the drill)

### B1. Home KPIs show firm-wide figures to a person who may not see them (session isolation)

- Where (develop): `kernel/drishti-server/src/main/java/com/ash/drishti/server/home/MeasureSnapshotJob.java:111-116`,
  `.../home/HomeTables.java:127,135-136` (kpis) and `:143-156` (history), `.../home/HomeStore.java:122-129`.
- What: the cut-off snapshot asks every KPI once as the firm principal (`FIRM`, sees everything) and stores it by **label only**.
  `home_kpi_history` then returns those stored points to anyone whose Home has that label, and `home_kpis` computes
  DELTA = the person's own value minus the firm's stored value.
- Why it matters: the trader Home's KPIs are scoped `own-desk` / `own-books` (`config/homes/banking/trader.measures.yaml:22-26`),
  which `VarHomeMeasures.scope` (`:73-76`) answers as "BANK as the person sees it". A restricted trader therefore gets 30 days of the
  bank-wide VaR and P&L as the sparkline, and can derive yesterday's bank figure from VALUE - DELTA. The delta is also simply wrong.
  The default landing page shows it: the `/home` cards read `home_kpis` (`drishti-console/routes/home_cards_routes.py:40,75`,
  `modules/banking/console/plugin.yaml:266-306`).
- Second defect in the same lines: `seen.add("kpi|" + k.label())` keeps one point per label across all Homes, so two Homes with the same
  label but different measure or scope share one stored value.
- Fix: never serve a firm-principal KPI to a person. Ask the source as the person for the earlier dates
  (`s.kpi(c.person(), …, c.previous())` for the delta, per date for the history), or keep the snapshot only for KPIs whose scope is the
  firm and serve it only when the source confirms the person's view is unrestricted. Add a two-user test (admin and a restricted
  trader) on `home_kpis` and `home_kpi_history`.

### B2. A mistyped profile name starts an open server

- Where (develop): `kernel/drishti-server/.../profile/ProductionProfile.java:46-57`, `ProductionProfileProcessor.java:39-42`;
  the console's `drishti-console/core/production.py:30-39`; `tools/launch/upgradecheck.py:109`.
- What: any value other than `production` / `prod` is treated as "not production": `drishti.defaults.open=true`, so sign-in is off,
  the development administrator is seeded, the demo source and API description are on, and nothing is refused.
  `drishti.profile=prodution` or `live` gives an unauthenticated server on what the operator believes is production.
- Also: `upgradecheck.py` accepts only the word `production`, while the server and console also accept `prod` and the Spring profile,
  so the upgrade check skips its production checks for `prod`.
- Fix: one list of known names (blank/development, demo, production, prod) in the server, the console and the upgrade check; any other
  value refuses the start with the list. Tests for an unknown name on all three.

### B3. The shipped production console unit starts the wrong Python

- Where (develop): `deploy/systemd/prod/drishti-console.service:17,30` uses `/opt/python3.13/bin/python3.13` (the bare base Python).
  OPERATIONS_GUIDE (`:3670,3726,3802,4034`) installs the libraries only into the per-release Python and prints
  `/opt/drishti/current/python/bin/python3.13` in the unit it tells the operator to copy.
- What: a copied unit starts the console without the pinned libraries, and the console does not change Python on upgrade or rollback,
  which is the point of the per-release link.
- Fix: correct ExecStart and the comment; add a test that the unit blocks printed in the guide equal the shipped files (server and console).

## 2. Should fix

### Security and access

- **S1. Genomics routes ignore the viewer (merge3; check first, a blocker if it reads).**
  `modules/genomics/.../GenomicsController.java:42-58`, `GenomicsData.java:101-126`, `GenomicsModule.java:49`: the screens read the
  kinds variant/sample/gene/cohort as the module into one process-wide snapshot, and the routes take no principal. I found no kernel
  filter that applies pack access to a module's routes. If none exists, `drishti-dev-market` (market data only) can read genomics rows,
  including sample ids, which defeats the scoped demo users. The data is now governed lake data, and the route inventory still says
  "none: showcase module" (`row-rule-route-inventory.tsv:154`). Fix: the controller takes the principal and requires access to pack
  `genomics`; update the inventory; a two-user test settles it.
- **S2. Published accounts survive a move to production.** `profile/DemoSeeder.java:54-56,60-66`, `UserService.java:132-140`: under
  production the demo defaults are not registered, so a store first started under demo or development keeps `drishti-dev-admin` and
  the three `drishti-dev-*` users with their published passwords, with no warning. Fix: under production, check the store at start
  for those names with their published passwords and refuse (or disable them and say so).
- **S3. Production does not check the data folder.** `ProductionProfile.java:111-120` checks the site folder only. With
  `drishti.data.dir` unset the data folder is `<home>/data` (`Roots.java:152-154`), inside the release; `Roots.guardWrite` (`:122`)
  covers only `config/` and files directly under home. Fix: refuse an unset or inside-release data folder under production.
- **S4. Home load states go to everyone.** `HomeTables.java:228-239,251-258`: `home_loads` and the load-failed / feed-late rules list
  every pack's kinds and states regardless of the person's pack access. Fix: filter by pack access.
- **S5. Console site folder check is too weak.** `drishti-console/core/production.py:71-73` only checks that `DRISHTI_SITE_DIR` is set;
  a relative path or one inside the console's own release passes, and `core/config.py:157-164` then writes there. Fix: absolute and
  outside the install.
- **S6. Clustered node with no advertise-url passes the TLS check.** `ProductionProfile.java:147-164` returns "fine" when
  `advertise-url` is absent or unreadable. Suspect: verify the default advertised address; if it is http, refuse.
- **S7. Boundary guard has blind spots.** `drishti-console/tests/test_console_boundary_guard.py:33-49,142` (develop) keys ALLOWED by
  (file, rule), and on console-lake `:67-69` exempts the whole of `core/lake_maint.py`. New reaches in those files pass silently.
  Fix: key by (file, rule, text) or pin a count.

### One way per job, no bespoke

- **S8. Two Home pages with separate machinery.** `/home` (`routes/home_cards_routes.py`, `core/home_cards.py`, cards in console
  `plugin.yaml` / `config/home-cards.yaml`, preferences in `homeCards`) and `/home/role` (`routes/role_home_routes.py`, the server's
  Home Sutra, start Home by `PUT /api/home/me`, arrangement by `/me/arrange`). `web/static/js/drishti_role-home.js:21` says the Sutra's
  view model "is the one thing not wired yet", while the docs wave tells people `/home/role` opens it. Fix: decide one. Either the cards
  become the role Home Sutra's fragments, or `/home/role` is hidden until wired, with one start / "view as" setting.
- **S9. Console computes risk figures.** `drishti-console/core/home_cards.py:348` (limit use = v / limit), `:307-308` (0.95 / 0.8 tone),
  `:335`. Rule: the server computes, the console displays. Fix: the home table serves USE and TONE.
- **S10. "View as" is checked only in the console.** `SettingsController.java:328-333` keeps any well-formed name. Harmless today (data
  is still read as the person), but the check belongs on the server (the offered Homes are known there).

### Production standard, paths, implicit targets

- **S11. Example settings carry the old layout.** `config/settings.examples/prod.ini` (`drishti.home=/opt/drishti`,
  `drishti.data.dir=/var/lib/drishti/data`, `drishti.modules.home=/opt/drishti/modules`) against OPERATIONS_GUIDE 4.1 and
  PRODUCTION_STANDARD:26-27 (`/opt/drishti/current/drishti`, `/srv/drishti/data`). The settings file beats the environment for these
  keys, so a copied example overrides `release.env`. Fix: align the `[roots]` block.
- **S12. Two production layouts documented.** OPERATIONS_GUIDE 5.1-5.3 and `deploy/systemd/drishti-server.service:31-36` keep data
  inside the install; PRODUCTION_STANDARD says one layout, release never written. Fix: retire the old unit or label section 5 non-production.
- **S13. Implicit server target remains.** `tools/lake/demo.py:541` (with `:90,519-525`) and `tools/drishti.py:276`
  (`tools/drishti_parser.py:24`) fall back to `http://localhost:18480` and send `DRISHTI_TOKEN` to whatever listens there. Fix: signal
  only with `--server`, `DRISHTI_SERVER` or the settings file; otherwise write the stamp and say so.
- **S14. Tools fall back to the checkout's data folder.** `tools/lake/lakeconfig.py:130-133,140-145`: no data folder named means
  "this checkout's data/", so staging and lake writes land in the release. Fix: refuse under production or a read-only install.
- **S15. Console lake maintenance can write into the release (console-lake).** `drishti-console/core/lake_maint.py:81`,
  `config/application.yaml:223-224`: `lakes.data_dir` defaults to `../data`. Fix: the console production check refuses it; delete the code fallback.
- **S16. Maintenance roots widen by themselves (console-lake).** `lake_maint.py:112-123`, `core/lakes.py:51-54`: every local root the
  server lists becomes maintainable, including an external lake Drishti does not own. Fix: only roots named in `lakes.roots`.

### Correctness

- **S17. Pack switch is not kept across a restart or a cluster.** `security/PackAccess.java:245,251,257`,
  `kernel/drishti-packs/.../PackLifecycles.java:274`: suspend/resume runs only on the node that took the Admin call, at that moment.
  After a restart an off pack starts RUNNING with its connectors pulling; the other two nodes never stop. Fix: at start
  (`LifecycleConfiguration.java:119-121`) suspend every pack whose stored switch is off, and peers apply the switch when it reaches them.
  Until then the Admin guide's effects table must say node-local.
- **S18. Economist Home dies with the modules merge.** On `i8/modules` the `economy` module is gone, but
  `config/homes/economy/economist.home.yaml:19` still says `modules: [ economy ]`, so the Home is never offered and nothing says why.
  Fix: name the new module; add a guard that every Home's `modules:` exists.
- **S19. Demo genomics and market users get no Home.** `application.yaml` `drishti.homes.roles` maps `risk-trading` but not
  `genomics-reader` or `market-data-reader` (none of the branches adds them). Fix: `genomics-reader: [genomicist]`, and decide the
  market user's Home.
- **S20. Retired module ids may not be seen with a site folder (modules; suspect).**
  `ModuleSettingsEnvironmentPostProcessor.java:60-61` falls back to `Roots.config("modules")` (release) where every reader uses
  `Roots.site("modules")`, and `RetiredModules.java:56` tells the operator to write `config/modules/<id>.yaml`. Whether the property
  already resolves to the site folder at that point needs one test with `/etc/drishti`. Fix: `Roots.site`, and name the resolved path.
- **S21. Genomics screens never see new loads; whole kinds on the heap (merge3).** `GenomicsData.java:71-83` re-reads only while a kind
  is empty; `:101-126,165-198` holds whole kinds and does cohorts × variants × carriers with `List.contains`. Fix: refresh on the
  data-version signal (the same mechanism as R2); cap with a clear message and state the limit.
- **S22. Lake list walks every file on every request (console-lake).** `LakeTablesController.java:47-57`,
  `LakeTableFigures.java:63-104`: about 183 tables, every date and file, no cache, called by the page and the 60 s loop. Fix: list from
  the snapshot summary or cache per table version. Also `SutraRegistry.java:93,125-137`: a Sutra's pack is taken from the folder name,
  and `get(name, version)` still serves a Sutra of an off pack.

## 3. Nits

- N1. `GET /api/v1/version` (open) returns product and version (`RootController.java:41-43`); return only `api` and `minSupported`.
- N2. `SettingsController.java:78,136` read-modify-write without a lock: two tabs (snooze and lastVisit) can lose an update;
  `homeCards` is replaced whole, not merged.
- N3. `ProductionTablesCheck` has no test of its own (only `TablesRuleTest`); one start test under production with a fallback table.
- N4. `routes/home_cards_routes.py:256`: a malformed stored `lastVisit` gives 500; use `hc._when()`.
- N5. Customer docs name branches and lanes: `docs/architecture/ENGINES_IN_DRISHTI.md:22,166`, `LIFECYCLE.md:188`,
  `ROLE_HOME_SUTRAS.md:20,21,28`. `PRODUCTION_STANDARD.md:130` names a test without its path; `:234` reads like a bug note.
- N6. `HomeTables.java:96-105`: several home tables answer empty with `TODO(R2)`; make sure no shipped Home shows a card that can only be empty.
- N7. The Home snapshot is taken per node into `<data>/homes`; with node-local data folders the nodes differ. Say it or share it.
- N8. console-lake: `lake_routes.py:121` compares `None == None` for tables without a folder; `LakeTableFigures.java:194` falls back to a
  hidden `.drishti-lake` folder and `:198` reads the whole run log; `LakeTablesController.java:98` runs not time-ordered, `:102-107`
  a lake file error shows an empty list with no reason; `LakeTableFigures.java:139-153` matches loads by folder, not symbolic name.
- N9. merge3: `modules/genomics/packs/genomics/pack.yaml:43-68` repeats the lake file's table entries; `config/lakes/genomicslake.yaml`
  gives `genomics.clinvar-variant` the kind `variant`, bridged by a second connector. One home per fact.
- N10. 16 packs still carry `samples/` data (about 110 files). Not an i8 regression (nothing added since the base); schedule the removal.
- N11. OPERATIONS_GUIDE production section 3 has the operator run `-m venv` per release: confirm with the owner against the no-venv rule.
- N12. rachana-11: `TOOLS_TAG` stays v1.1.0-ide2 in both workflows while the index row says v1.1.0-ide3; align at the cut.

## 4. Merge hazards (round 3b)

Simulated in a scratch object store (`git merge-tree`, nothing written to any repository) in the planned order on top of develop 6f903a4e3.
Facts that change the order: **windows-docs already contains console-lake**, and **docs-wave already contains release-docs**.
develop's `pom.xml` already asks for Lake 0.9.2 while its console requirements, `lib/lake-wheel.pin` and both workflows are still 0.9.1,
so CI on develop builds a mismatched pair until rachana-11 is in.

Recommended order: **rachana-11 → merge3 → console-lake → modules → windows-docs → docs-wave** (release-docs comes with it).
rachana-11 first because it only moves pins and removes the Lake mismatch for every later heavy run.

| Step | Textual conflicts | How to resolve |
|---|---|---|
| rachana-11 | `pom.xml` (comment line of the Lake pin), `docs/README.md` | take rachana-11's pins; README by hand |
| merge3 | `docs/admin/CONFIGURATION.md`, `tools/test_docs_consolidation.py` | regenerate the document; recount, never hand-merge |
| console-lake | `config/docs-corpus.yaml`, `docs/admin/CONFIGURATION.md`, `docs/admin/TABLE_CATALOGUE.md`, `tools/test_docs_consolidation.py` | corpus: union of rows; the rest regenerate |
| modules | `docs/admin/TABLE_CATALOGUE.md`, `tools/testdata/publicsnap.py` | regenerate; publicsnap: keep merge3's `genomics` entry and modules' `news-and-events` paths (both) |
| windows-docs | `.github/workflows/windows.yml`, `modules/genomics/.../GenomicsHomeTest.java` | workflow: rachana-11's tags plus windows-docs' steps; test: merge3's lake-backed data with windows-docs' own-location paths |
| docs-wave | none | replace the marked `i8/modules` blocks as its notes say |

Silent hazards (no textual conflict, wrong result):

- modules retires `economy`; develop's economist Home still requires it (S18).
- merge3 adds a controller-less change to genomics while the route inventory row stays "none" (S1); console-lake adds
  `LakeTablesController` with its inventory row, so the inventory auto-merges but must be re-checked by the guard after all merges.
- merge3 renames the fixture pack to `genomics-fixture` and adds the real pack `genomics`; `DemoAccounts` (develop) names `genomics`,
  so the demo genomics user changes meaning with this merge: run the demo-seed test after it.
- `drishti-console/config/application.yaml`, `ConfigResolution.java`, `ModuleTemplates.java`, `PackLifecycles.java`, `PackAccess.java`,
  `TokenFilter.java`, `RootController.java` auto-merge between merge3 and console-lake (both carry earlier copies of work now on develop):
  read the merged result of these seven files once before the heavy run.
- After the last merge, one pass of the generators (`docs generate`, table catalogue, settings document, consolidation count, the
  QUICKSTART PDF) instead of one per merge.

## 5. Looked at and found clean

- `/api/v1/admin/lakes/tables | tables/detail | runs`: all require an administrator; `lake` and `name` are matched against the lake
  files, never used as a path; uris go to administrators only.
- `/api/v1/version`: exact-path open route, no secrets (see N1 for the version string).
- Demo users: never seeded under production (also refused when `seed-demo-users` is set there); the demo roles carry no admin, author
  or approve power.
- Lifecycle, Home admin and snapshot routes require an administrator; Home files are confined to the Homes folder.
- Console Home: every read is made as the person; no per-user state in module or application globals; cached data is static
  configuration only; drawn by Elements; static files `drishti_*`; no CDN; CSRF on POSTs.
- API compatibility handshake and `/readyz`; the console production check (auth, secrets, cookie, bind); the upgrade check is
  read-only and installs nothing; loader staging under `<data>/tmp/loaders` with clean-up; API keys masked in logs.
- Site folder: Admin writers go through `Roots.guardWrite`; writes into the release are refused (DRS-1038).
- merge3 genomics pack: no data inside the pack; demo data under `data/testdata/genomics`; tables by symbolic name; no lake path
  derived from a pack or module id; the VCF connector route is removed (no dual route).
- modules: the 287 renames move packs, docs and config between module folders; lake files and uris untouched.
- About 30 spot-checked settings, variables, error codes and test names in PRODUCTION_STANDARD, release-docs and docs-wave have code
  behind them (docs-wave's `drishti.public-sector` text needs modules merged first).
