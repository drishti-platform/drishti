<!--
  Project Drishti · Any data. Any domain. One grammar.

  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
  All rights reserved.

  PROPRIETARY AND CONFIDENTIAL.

  This file is the confidential and proprietary property of Ashutosh Sinha.
  Unauthorised copying, use, modification, distribution or disclosure of this
  file, via any medium, is strictly prohibited except with the express prior
  written permission of the copyright holder.

  See the LICENSE file in the root of this repository for the full terms.
-->

# i8 gate, part 1 (Fable): everything except R2

Written 2026-11-28 14:46 EST. Part 2 (the R2 engine and speed work) is reviewed after it merges.

**Verdict: FIX. Not GO for the drill on this part. 5 blockers, 10 should-fix, 14 nits.**
No security hole and no session leak was found in the new code, and no merge silently undid another branch's change.
Every blocker is a red test or a check that the drill's gate runs in its first minutes. They are about one builder-day together.

## 0. What was reviewed and how

- **Target:** `i8/merge3b` at `b2e3b0b6e` (123 commits and 1,561 files on `origin/i8/develop` `6f903a4e3`).
  **The branch is local only: `origin/i8/merge3b` does not exist.** It was read from the local ref.
- **Merged:** all twelve branches are ancestors of the target (rachana-11, merge3, pregate-fixes, console-lake, modules,
  trading-systems, one-home, h2, one-way, windows-docs, release-docs, docs-wave). `i8/no-browser-maths` is not merged (moved to i9).
- **Reading:** `git show` / `diff` / `grep` on refs, by me and by three parallel read-only passes (server, console, one-way and docs).
  I re-read every blocker and the security items myself (Home tables, View as, the genomics Home, the pack switch).
- **Run, read-only:** a scratch worktree (`~/.cache/drishti-lanes/fable-i8g1`, removed afterwards) with no build and no server jar.
  In it I ran the gate's quick file checks: the console's static tests, the tools' static tests, the docs and Help tests, and
  `tools/license_headers.py`. Ten tests need the server jar and were not judged (section 6).
- **Batch run** `~/drishti-bench-scratch/m3c` at this commit, read up to 14:45: products, install and build passed;
  `docs-generate`, `java-unit` and `modules-it` failed (blockers 1 to 3). Console, browser and tools lanes had not run yet.
- **Not covered:** R2; the console and browser lanes' results; Windows; line-by-line reading of the 116 converted Sutras
  (a script compared all 89 files: no panel id and no bind lost).

## 1. Blockers

### K1. The kernel unit run is red: 8 failures in `drishti-server`

Evidence: `m3c/java-unit.log` (14:38, at this commit).

- `kernel/drishti-server/src/test/java/com/ash/drishti/server/admin/ArtifactApiTest.java`: 7 of 14 fail, the first at `:229`
  (`DRS-5040 no bi named 'art.trades'`), then `:263`, `:288`, `:314`, `:341`, `:415`, `:438`.
  The run also leaves `kernel/drishti-server/config/tables/art/new_widgets.table.yaml` in the source tree: an upload in this test is
  written under the working folder, not under the test's own folder.
- `CoreSutraNextEquivalenceTest`: the context does not start, `no pack 'core'`. The pack `core` is now in the `reference-data`
  module (`modules/reference-data/packs/core`) and this kernel test switches no module on.

Fix: give `CoreSutraNextEquivalenceTest` the reference-data module (or move it to `testing/drishti-modules-it`). For
`ArtifactApiTest`, find the first failing order step; the cause was not established here. The test must also name the
tables write folder so nothing lands in `kernel/drishti-server/config/`.

### K2. The module integration run is red: 5 failures

Evidence: `m3c/modules-it.log` (14:44, with Rachana 1.1.2 installed; the earlier `DRS-2114` noise is gone).

- `H2SamplePanelEquivalenceTest:63`: 404, `DRS-1001 no source holds central-bank/CBANK-BOC (asked file; centralbank-store, which kind
  'central-bank' is routed to, is failed)`. With samples on, the kind is routed to its lake connector, which has no lake.
- `H2LakePanelEquivalenceTest:50`: 403, `DRS-5002 u-masked may not open lei-entity entities`. The test's masked reader has no access to
  the gleif kinds after the move to `reference-data`.
- `ConsumersSideBySideTest:51`, `HomesSideBySideTest:46`, `PackagedVarGoldenTest:85`: `the lake build failed (1)`. The output ends
  normally and the test prints only its tail, so the reason is not in the log. This is the packaged `lake demo`, which the drill and
  the owner's demo both use.

Fix: run `python tools/drishti.py lake demo` by hand on this commit and read the first failing table (the 13:39 round said
`KeyError: 'trade-endur'`; `d2af4eada` changed that path). Make `PackagedServer.runLake` print the whole output on failure.

### K3. `docs generate` fails and generated files are stale

Evidence: `m3c/docs-generate.log` (14:36); the merge worktree after it.

- **Environment index.** The six source packs read `DRISHTI_STREAM_<SYSTEM>` and `DRISHTI_<SYSTEM>_TOPIC`
  (`modules/trading-systems/packs/{calypso,endur,ici,murex,swapone,wss}/pack.yaml:56-57,63-64,75-76`). They moved from connector
  files, which the check does not scan, into `pack.yaml`, which it does. `tools/settings_schemas.yaml:504-505` lists only the
  `TRADING` pair. Fix: add the twelve rows, regenerate `docs/admin/CONFIGURATION.md`.
- **Docs corpus.** No rule matches `modules/trading-systems/packs/trading/guides/trading.md` and `trading-products.md`
  (`tools/docs/corpus.py:73-75` is where the pack guide rules are). Fix: add the rule, `corpus.py --write`.
- **Table catalogue.** After generation `docs/admin/TABLE_CATALOGUE.md` differs from the commit (the six `trading.trade_<system>`
  rows). Fix: commit the regenerated file.
- **Pack README standard (by code, the run was in write mode).** The six source pack READMEs have `kinds: []` and an empty
  `## Data sources and licences` (`modules/trading-systems/packs/wss/README.md:24,73`). `tools/packgen/common/readme.py:114,120` reports
  both under `--check`, as the 13:55 run did. Fix: a pack that only feeds another pack's kind lists the kinds it feeds, and its
  `about.yaml` gains the sources section (mocked data, the generator). Change the standard once, not six allowances.

### K4. The Rachana 1.1.2 pin is half done

`2ea441958` moved the pom, the workflows, the copies (`docs/products/rachana/v1.1.2/`), `help.yaml` and the corpus. It did not move:

- 74 links to `drishti-rachana/blob/v1.1.1/...`: `docs/guides/RACHANA_REFERENCE.md` (65), `docs/guides/BUILD_GUIDE.md` (6),
  `docs/guides/INTEGRATION_GUIDE.md:857,860`, `README.md:27`.
- `drishti-console/tests/test_help_products.py:43,45,51,58,60`.

Effect: `Library.product_link` (`drishti-console/core/guides.py:329-336`) maps only the pinned tag, so every one of those links
leaves offline Help for GitHub, on a demo machine that is offline. Three tests fail (run here):
`test_a_github_link_to_a_bundled_document_opens_the_local_page`, `test_a_relative_link_that_leaves_a_copy_goes_to_github_at_the_pinned_tag`,
`test_every_drishti_link_to_a_bundled_product_document_resolves_locally`.
Fix: replace `v1.1.1` by `v1.1.2` in those five files. The six product documents are identical in both tags.

### K5. The gate's quick checks fail (run here, none needs the jar)

| Check | What fails | Fix |
|---|---|---|
| `test_docs_rot.py::test_every_relative_link_and_anchor_resolves`, `test_help_links.py` | 15 links to files One Home deleted: `docs/architecture/LENSES.md:95-96`, `docs/plans/LENSES_PROPOSALS.md:175-177,557-561,895-897,1124-1126` | rewrite the passages to the one Home (see S5); unlink |
| `test_docs_rot.py::test_every_repository_path_named_exists` | `ARCHITECTURE.md:227,448`, `BUILD_GUIDE.md:3376`, `RACHANA_REFERENCE.md:3244-3245` (`.../packs/trading/sutras/murex/`, `endur/`), `DATA_GUIDE.md:1538,1830` (`packs/endur/connectors/trade-endur.yaml`), `DEVELOPER_GUIDE.md:436` (`kernel/phone.html`), `RACHANA_REFERENCE.md:7870,8217,8222` (`examples/packs/genomics/`, now `genomics-fixture`) | name the paths as they are now |
| `test_docs_rot.py::test_every_setting_and_environment_variable_named_exists` | `docs/admin/CONFIGURATION.md:822` `lakes.max_upload_mb` (console-lake removed the upload) | regenerate (with K3) |
| `test_docs_api_endpoints.py` | `GET /homes/view` and `POST /homes/state` are not in `API_REFERENCE.md` | add both |
| `test_tools_guide.py` (2 tests) | `lake entries`, and `tools/build_demo_lakes.py`, `tools/lake/catalogue_entries.py`, `tools/module_catalogue.py`, `tools/rolling.py`, `tools/sizing_planner.py` are not in `CLI_REFERENCE.md` or `OPERATIONS_GUIDE.md` | one-way F9 registered the commands; finish the guides |
| `test_docs_gates.py::test_docs_architecture_holds_only_what_is_built` | `docs/plans/H2_CONVERSION.md` records finished work | fold what a reader needs into the Rachana reference, delete the file |
| `test_docs_pdfs.py` | `docs/DEPLOYMENT_AND_QA_GUIDE.pdf` is older than its source | `tools/docs/md_to_pdf.py` |
| `test_browser_rules.py::test_the_baseline_only_goes_down` | `drishti-console/tests/test_browser_rules.py:54` still lists the deleted `test_edition_trading_browser.py` | remove the entry |
| `test_source_systems_contract.py` | `:43` globs `packs/*/connectors/*.yaml`; the source packs now carry their connector in `pack.yaml`, so it finds none (`0 >= 6`) | read the connectors from `pack.yaml` |
| `tools/test_module_layout.py:90` | `len(MODULES) >= 10`; there are 7 | assert the seven by name |
| `test_python_main_paths_guard.py` | `tools/settingsfile.py:42` `Path("config") / "profiles" / ...` (from the pre-gate B2 fix); `tools/launch/upgradecheck.py:38` (already on `i8/develop`) | anchor on the install root |
| `test_no_relative_paths.py` | `kernel/drishti-server/.../profile/ProductionProfile.java:146` (the default `"folder"`; already on `i8/develop`) | a named constant, or an entry in the guard with the reason |
| `tools/license_headers.py` | `docs/plans/I8_CARRY_IN.md` has no header | it is a lane's hand-over note (a workflow run id, "state for i9"): move it to the plans branch and delete it here |

## 2. Should fix

- **S1. A source-system pack switched on one node stays on in reads on the peers.**
  `kernel/drishti-server/.../security/PackAccess.java:264-282` (`reconcile`, from pregate-fixes) never runs `switchListeners`
  (`:290,299`), and `SourceSystemsWiring.java:65` (trading-systems) refreshes only through them. Two branches, each right alone.
  Fix: `reconcile` fires the listeners when anything changed; one two-node test.
- **S2. The genomicist Home ignores the person.** `modules/genomics/.../GenomicsHome.java:96-112` and its tables (`:134-148`) answer
  counts, cohort ids and gene rows from the module snapshot; `Principal p` is unused. The routes are gated (`GenomicsAccess.java:38-42`),
  the Home is not, so a person with the role but without the packs sees them. Fix: `access.require(KINDS)` as the person, no rows on refusal.
- **S3. Pre-gate S15 and S16 are open.** `drishti-console/config/application.yaml:229` (`data_dir: ${DRISHTI_DATA_DIR:../data}`, no
  production refusal); `drishti-console/core/lakes.py:51-54` (every local root the server lists becomes maintainable).
- **S4. Pre-gate S12, S20, S21, S22 are open.** `deploy/systemd/drishti-server.service:31-36` (data inside the install);
  `ModuleSettingsEnvironmentPostProcessor.java:61` (falls back to the release folder); `GenomicsData.java:71-83,165-198` (no refresh,
  whole kinds on the heap); `memlake/LakeTablesController.java:47-57` (no cache).
- **S5. Documents describe what One Home and the module moves removed.**
  `LENSES.md:95-101,135,194,223,581-582,612` (lens landings, `TradingLanding`, `/api/v1/trading/landing/*`: no code);
  `KERNEL_AND_MODULES.md:1599,3371-3377,3463,3562`, and `:2647,2858-2859` (`config/packs/core`, `DRS-1040`: no code);
  `THIN_CONSOLE_DESIGN.md:403,926`; `ROLE_HOME_SUTRAS.md:160-170` ("Written, unbuilt and unrun", "not wired": it is built);
  `ADMIN_GUIDE.md:778-779,809-810,989,1007,1014,1020,1561` and `DEPLOYMENT_AND_QA_GUIDE.md:610,1197-1199` (retired module ids);
  `QUICKSTART_DEVELOPERS.md:277,332`.
- **S6. Guides tell readers to run scripts `pack make` no longer writes.** `BUILD_GUIDE.md:2306,2317,2751-2752`,
  `CLI_REFERENCE.md:173-174,800,834`, `OPERATIONS_GUIDE.md:1375`, `tutorials/19-build-a-pack-and-a-screen.md:209`.
- **S7. One-way rows not finished.** F5: `plugins/drishti-plugin-delta/.../VectorBatches.java:32` is still the copy. F7: the six
  finders remain as delegates (`tools/drishti.py:107,115`, `tools/lake/writers.py:53,66`, `tools/sutragen.py:379,388`). F9: see K5.
  The duplicate baseline `tools/cpd/baseline.txt` was not lowered.
- **S8. KPI history costs one engine call per KPI per day on every Home open.** `home/HomeTables.java:219-233`, sequential; it also
  steps calendar days, so weekends give no point. Fix: one ranged call per KPI as the person.
- **S9. Branch and lane names in customer text.** `ENGINES_IN_DRISHTI.md:22,166` (pre-gate N5); `ROLE_HOME_SUTRAS.md:20-21`;
  `CHANGELOG.md:19-20` and `RELEASE_NOTES.md:18-19` say the lead trims the branch comments before the tag: do it before the drill.
- **S10. Push the branch.** `i8/merge3b` exists only in a local worktree; a day of merge work has no copy on origin.

## 3. Nits

- N1. `packs/PackLifecycles.java:112-122`: a reloaded pack starts RUNNING whatever its switch says.
- N2. `home/HomeController.java:151-164`: read, change, write of the Home state without a lock; two tabs can lose an update.
- N3. `profile/DemoSeeder.java:35,60-66`: the comment says "demo profile (and nowhere else)"; the code also seeds under development
  when `seed-demo-users=true`. Production is refused either way. Correct the comment.
- N4. `drishti-console/core/production.py:35` reads the profile names from the release's `config/`; a console on its own host has none.
- N5. `home/AttributionRunner.java:73,86` computes drivers as the firm; `HomeTables.java:381` filters by subject. Unverified whether
  a person can see part of a subject.
- N6. `kernel/drishti-api/.../ModuleSwitches.java:88`: an unreadable site switch file discards a readable release default.
- N7. `drishti.kernel.packs-dir` still defaults to `config/packs/core` (`ResolutionSettings.java:68`, `LaunchRoots.java:68`); the
  folder is gone and the kernel tier holds no pack. Keep the tier and say so in one place, or remove it.
- N8. "The economy module's" in shipped text: `config/feeds/*.yaml:23-25`, `config/lakes/newslake.yaml:19`, `worldlake.yaml:19`,
  `tools/lake/demo.py:32`, `tools/testdata/publicsnap.py:76`.
- N9. `modules/trading-systems/packs/ici/config/about.yaml` (and its README): "the owner did not say what it trades", and a broken
  line "module that / extends `trading`".
- N10. Console fixtures keep retired module ids (`tests/answers/admin-modules.json`, `conftest.py:909`,
  `test_module_menus.py:41-62`, `test_distributions.py:114`); `AdminModulesRecordTest.java:69`. They pass on a fake backend and mislead.
- N11. Stale tooling: `tools/docs/intended.yaml:22,138-141`; `tools/packs_to_modules.py:33-34` (a one-time move tool).
- N12. `drishti-console/routes/lake_routes.py` `_readers`: one connector call per connector on every table page.
- N13. `OPERATIONS_GUIDE.md:3581,3730,4346` still uses `-m venv` (pre-gate N11: ask the owner).
- N14. `TOOLS_TAG: v1.1.0-ide2` in `.github/workflows/fast.yml:24` and `windows.yml:30`; the index row says `-ide3` (pre-gate N12).

## 4. Earlier reviews: what is closed

### I8_PREGATE_REVIEW.md

| Item | State | Where on `i8/merge3b` |
|---|---|---|
| B1 Home figures as the person | **closed** (re-read) | `home/HomeTables.java:205-206,229,252`; two-user test in `RoleHomesTest` |
| B2 unknown profile name | **closed** | `profile/ProductionProfile.java:54-67`, `config/profiles/profile-names.json`, `core/production.py:35-90`, `upgradecheck.py:106-116` |
| B3 console unit's Python | **closed** | `deploy/systemd/prod/drishti-console.service:31`; `tools/test_systemd_units.py` passes |
| S1 genomics routes | **closed**, test thin | `GenomicsController.java:111-113`, `GenomicsAccess.java:38-47`; no server test that the market user gets 403 |
| S2, S3, S6 production refusals | closed | `ProductionAccountsCheck.java:35-58`; `ProductionProfile.java:165-173,200-218` |
| S4 load states by pack | closed | `HomeTables.java:342-350` |
| S5, S7 console | closed | `core/production.py:112-121`; `tests/test_console_boundary_guard.py:46-66` |
| S8, S9 one Home, no console arithmetic | closed | card machinery and lens landings deleted; `/home` draws the Home Sutra (`routes/role_home_routes.py:50-62`) |
| S10 View as on the server | **closed** (re-read) | `home/HomeState.java:135-136,197-203`, `HomeController.java:116,128`: administrators only, offered Homes only, data read as the caller |
| S11, S13, S14 | closed by commit, not re-read | `517ee1298`, `56a55f517`, `b8792c04e` |
| S17 pack switch | **partly** | restart and gossip: `LifecycleConfiguration.java:123-130`, `PackAccess.java:264-282`; the gap is S1 above |
| S18, S19 Homes after the module moves | closed | `config/homes/external-data/economist.home.yaml`; guard `RoleHomesTest.java:481`; server `application.yaml:1009-1014` |
| S12, S15, S16, S20, S21, S22 | **open** | S3 and S4 above |
| N5, N11, N12 | open | S9, N13, N14 above |

### ONE_WAY_AUDIT.md, section 1

| Row | State |
|---|---|
| F1 litter | done: no `NEXT.md`, no `drishti_pivot-grid.js`, no dead pack keys, `VENDOR.md` corrected |
| F2 | R2, part 2 |
| F3 one module switch | done: `ModuleSwitches.java:33`, called from `AccessRegistries.java:40` and `ModuleTemplates.java:214` |
| F4 pack state, ledger only | done: `PackStateStore.java:34`; no reader of `drishti.packs.state-store` |
| F5 | partly (S7) |
| F6 | done; guard at `ArchitectureRulesTest.java:325` |
| F7 | partly (S7) |
| F8 | done: no `HttpURLConnection` in main code |
| F9 | partly: commands registered, guides not (K5, S6) |
| F10 CSV input | done |
| F11 one Home | done in code, not in the documents (K5, S5) |
| F12 | moved to i9 |

## 5. Looked at and found clean

- **Home:** KPIs, deltas, history, limits, "Needs you", "Pinned", arrangement and start Home are all read as the person and kept per
  user; nothing is cached or stored by label. View as is an administrator's, checked on write and on read.
- **Console session isolation:** no new per-user global, `app.state` entry or cache; the allow-list of `test_no_shared_user_state.py` shrank.
- **Production profile:** unknown names refused in the server, the console and the upgrade check from one list; demo users never under production.
- **Merge damage:** 13 files conflicted across the ten merges (seven of them generated documents). For the 19 files named as touched
  by more than one branch, every hunk of each source branch is in the merged file. The one behavioural gap between branches is S1.
- **H2:** 89 converted Sutra files identical to `i8/h2` at their moved paths; ids kept; only YAML changed. No bespoke HTML, JS or CSS.
- **Bespoke UI, CDN, prefix:** one new static file (`drishti_role-home.css`); guard allow-lists only shrank.
- **Pins:** Rachana 1.1.2, Lake 0.9.2, Elements 3.3.2, Tools 1.1.0 in the pom, both workflows, the wheel pin and the console
  requirement (K4 is the links). README counts equal the tree: 7 modules, 25 packs.
- **Commits:** 123, all `ajsinha`, no attribution lines. No changed code file over 2,500 lines. No conflict markers. No root litter.

## 6. Not judged here

Ten quick checks need the server jar and were not run to a verdict: `test_settings_catalogue` (2), `test_guide_data` (3),
`test_command_catalog` (2), `test_code_volume`, `test_elements_version`, `test_under_construction`; also
`tools/test_docs_consolidation.py`, `test_pack_guides.py`, `test_pack_layout.py`, `test_readmes.py` and one case of `test_launchers.py`.
The batch run's console, browser and tools lanes had not started. Read them before the drill.

## 7. To reach GO

1. Fix K1 to K5 and S1, S2 (both are small and touch access).
2. One pass of the generators, then `docs generate --check` and the gate's quick checks green, with a clean `git status` after.
3. The batch run green on the fixed commit: `java-unit`, `modules-it`, console, browser, tools.
4. Push the branch. S3 to S9 may go to `I8_CARRY_IN` on the owner's word; S9's changelog comments must be trimmed before the tag.
