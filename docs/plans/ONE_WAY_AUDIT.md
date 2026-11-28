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
# One functionality, one way: the platform audit

**Status: audit for the lead and the owner, written 2026-11-28 11:45. Read-only: nothing built, nothing run, no product code changed.**

**The question (owner, 2026-11-28).** Across the whole platform, is each job done by exactly one mechanism? The rules it is held to:
one way per job, with one exception (Delta create and fill: the Java concurrent loader by default plus one Python path); known
duplicates are fixed in the current release; shared code lives once in its product; all UI from Elements and Rachana; every operator
command through `tools/drishti.py`; one host-owned cache and one copy of bulk data; one MCP server.

**Read from git only.** Drishti `origin/i8/develop` `6f903a4e3` and the waiting branches `i8/merge3` `38fceb15e`, `i8/console-lake`
`382a7fb62`, `i8/modules` `846349771`, `i8/trading-systems` `24a15b16b`, `i8/pregate-fixes` `b8792c04e`, `i8/one-home` `7671cf7c2`
(plus its uncommitted worktree), `r2/speed-host` `2cd9e2c79`, `r2/speed-engine` `bc1501878`, `r2/speed-ccr` `57b7636da`;
drishti-tools `origin/release/1.1.1` `d1305f2`; drishti-rachana `v1.1.1` `9b6e0613`; drishti-lake `v0.9.2` `d53d864`;
drishti-elements `origin/develop` `58331f4`; drishti-engine `origin/develop` `2065af5` and its three `r2/speed-*` branches;
drishti-risk `origin/develop` `264b8ff` and its three `r2/speed-*` branches; drishti-engines `origin/develop` `0a031d2`.
Paths are relative to each repository; a path with no repository named is Drishti's. Line counts are `wc -l` at the ref.

**How it was done.** Three read-only sweeps (the Java server; the console and the Python tooling; products against Drishti) plus my
own reading of the engines, the R2 branches, the switches, the Home and the plans. Every row ranked FIX IN i8 was re-checked by me at
the ref. Rows marked *(sweep)* carry a sweep's figures that I did not re-measure; treat their counts as approximate.

## 0. The answer in one page

| Rank | Rows | Meaning |
|---|---|---|
| **FIX IN i8** | **12** | cheap and clean, or it blocks the gate |
| **PLAN** | **21** | a real second way; needs a design or a product release; a release is named for each |
| **TRANSITION** | **8** | two ways today, with a written plan that removes one; the plan was found and is named |
| **OK** | **17** | looks like a duplicate, is justified; the reason is given |

The three findings that matter most:

1. **The R2 flip would leave two risk engines in the tree unless the gate decides otherwise.** The gate brief's own table "What stays
   in-tree only" (the BI pivot and MR cube, Flight, the explain routes, VaR leftovers) contradicts the plan's rule MC-1 "no route
   stays in-tree past step 6". While those readers remain, `banking-compute` and the compute half of `banking-service` (about 59,000
   lines, T1) cannot be deleted (F2).
2. **The Python loader exists twice, and Delta is written by delta-rs in three places.** Drishti's `lake build` Python half
   (`tools/lake/build.py`, `stage.py`, `writers.py`: 1,812 lines) and Lake's `python -m drishti_lake load` are the same job;
   `tools/samplegen/layout.py` is a further delta-rs writer behind `data ingest` and the generators. The owner's exception allows
   one Python path (P1).
3. **"Switch something on or off" has four stores** (the ledger in the database for packs and commands, YAML files for modules,
   Spring properties for engines, `enabled:` in connector files), and the module switch is decided by two separate functions that
   parse the value differently (F3, P2).

What is in good order: one MCP home, one JDK HTTP client behind a guard, one hash, one workbook writer, one table catalogue, one
chart and one grid engine in the browser, Elements vendored byte for byte, the server side of the Home. Section 5 lists them.

## 1. FIX IN i8

| # | What is done twice | Evidence | Resolution (what stays, what goes) |
|---|---|---|---|
| F1 | **Root litter and dead files that would ride into the release** | `NEXT.md` at the repository root on `i8/merge3`, `i8/pregate-fixes`, `i8/one-home` (a lane's hand-over note). `drishti-console/web/static/js/drishti_pivot-grid.js` says "Deleted with the renderer 1.2.0"; only `static/render/3.3.2` exists and `tests/test_pivot.py:64` asserts no page loads it. Console keys `packs.dir`, `installed_dir`, `extra_dirs` (`drishti-console/config/application.yaml:257-258`): the boundary guard itself calls them dead. `static/vendor/VENDOR.md:53-55` still describes Perspective's chart plugin as vendored, under a section that says Perspective was removed | Delete all four. `NEXT.md` never reaches `i8/develop` (its content belongs in the resume memory) |
| F2 | **R2: the flip must not leave in-tree readers, aliases or a second cache** | `docs/plans/R2_CUT_PLAN.md` on `r2/speed-ccr`: gate brief "What stays in-tree only" lists `VarPivotProvider`, `VarPivotLens`, `MrCubeExtras`, `VarFlightForm`, `VarNumberExplainer`, `VarWhyPanels`, `VarExplain` and the VaR leftovers; section 5 says "No route stays in-tree past step 6 (MC-1)". Removal list item 6 keeps `config/engines/var.yaml` and `exposure.yaml` keys "as aliases". `VarEnginePath.java:1634` on `r2/speed-engine` still holds the private `indexes` map that `r2/speed-host` and `r2/speed-ccr` deleted | The Fable R2 gate decides one of two: every listed reader gets its engine path before the flip, or the flip waits. No family flips while a reader of it is in-tree. Retired keys stop the start through `RetiredNames`, as every other retired key does; no alias. The speed merge takes `speed-host`'s `VarEnginePath` (no `indexes`). Section 3, T1 lists what goes at removal |
| F3 | **The module switch is decided by two functions** | Server: `kernel/drishti-registry/.../AccessRegistries.java:41-47` with `EmbeddedAccessRegistry` (file value read by `ModuleManifest.bool`, `YamlAccessStore.java:158`). Offline (tools, the loader process): `kernel/drishti-api/.../resolution/ModuleTemplates.java:203-225` (file value read by `Boolean.valueOf(String.valueOf(e))`). The setting accepts `true/on/yes` in both; the file value is parsed differently, and "required module is off" is computed twice | One function in `drishti-api` (`ModuleSwitches.decide(modules, switchesDir, property)`) that both call; one test over both entry points |
| F4 | **Pack state: three stores for one switch** | `kernel/drishti-identity/.../PackStateStore.java:28-52` (the ledger since 2.8.0, plus the content-store document `admin/pack-state.json`, plus a JPA table), chosen by `drishti.packs.state-store` (`IdentityConfiguration.java:298`). Carried as F28 l in `I8_CARRY_IN.md` ("remove once no install holds old state") | The ledger only. Delete the document and table modes, the setting and the one-time import. Before deleting, confirm the owner's stack (v2.9.11.i_7n) has its pack states in the ledger; it has run on the ledger since 2.8.0, so the import has already happened |
| F5 | **Lake code copied into the Delta and Iceberg plugins** | `plugins/drishti-plugin-delta/.../VectorBatches.java` (104) is Lake's `lake-delta/.../delta/table/VectorBatches.java` with only the header and package changed (4 differing lines); the plugin already depends on `lake-delta`. `MappedIdMap.java` in the Delta plugin (84) and the Iceberg plugin (85) differ from each other by 5 lines, and Lake ships `lake-api/.../read/MappedIdMap.java` and `IdMaps.java` | Delete the plugin's `VectorBatches`, use Lake's. One `MappedIdMap` (Lake's if its surface is enough, else one copy in `drishti-api` beside `MappedIdFile`) |
| F6 | **Leftovers of the i7b duplicates register that were never removed** | `I7B_DUPLICATES.md` R5 and R10 named them for G4. Still present: `kernel/drishti-engine/.../search/form/RelativeDates.java` (99; 3 body lines apart from rachana-core's *(sweep)*), `engine/source/SourceFailures.java` (9 lines apart *(sweep)*), `new DecimalFormat` in `HistoryRows:402`, `Explanation:261`, `DocFmt:49` *(sweep)* | Use rachana-core's `RelativeDates` and `SourceFailures`; the three number formats go through Rachana's `Formats`; add the guard R10 asked for |
| F7 | **The Java and JAR finder written four times in the tools** | `tools/javacmd.py:185` (its own text calls it the one resolver), `tools/drishti.py:107,115`, `tools/lake/writers.py:53,66`, `tools/sutragen.py:379,388` | `javacmd.py` only; the other six functions deleted, callers import it |
| F8 | **An HTTP client outside the one TLS and proxy wrapper** | `plugins/drishti-plugin-kafka/.../SchemaRegistryClient.java:133` builds `HttpURLConnection` with its own proxy and TLS code (177 lines). The guard `ArchitectureRulesTest.java:299-307` watches only `HttpClient.newBuilder` | Build it from `DrishtiTools.httpBuilder`; widen the guard to `HttpURLConnection` and `openConnection` |
| F9 | **Operator commands outside `tools/drishti.py`** | `tools/rolling.py` (367), `tools/build_demo_lakes.py` (558) and `tools/sizing_planner.py` (968) are named as operator commands in the guides and in `deploy/rolling.example.yaml:16-17`, and are not in `tools/drishti.py`, `drishti_parser.py` or `commands.builtin.yaml`. `tools/packmake.py:176-179` writes `run-server.sh` and `run-server.ps1` into every `pack make` folder | Register the three as `drishti.py` commands (`cluster roll`, `lake demo --all` or its existing name, `admin capacity workbook`) and change the guides in the same commit; `pack make` writes the one `drishti.py start server` line into its README instead of two scripts |
| F10 | **Deprecated CSV input still shipped** | `plugins/drishti-connectors/.../file/CsvRows.java` (72) and `FileSourcePlugin.java:219-225` ("CSV input is deprecated and will be removed in a later release"). JSONL is the one data standard; no customer holds CSV | Delete now (no shim without a customer, the i7b rule) |
| F11 | **One Home: the list the lane must finish** (the lane exists, T3; these are its completion checks) | Card machinery: `drishti-console/core/home_cards.py` (428), `routes/home_cards_routes.py` (263), `config/home-cards.yaml` (47), `web/templates/home_cards.html`, `static/js/drishti_home-cards.js` (103) and its CSS. Three Jinja lens landings: `plugins/banking/templates/home/{risk,trading,market}_landing.html` (398). `i8/one-home` has no commit of its own yet | All of the above deleted in the lane's change, not left behind a switch. `drishti_role-home.js:21` ("the one thing not wired yet") resolved. Server check of "view as" (pre-gate S10) |
| F12 | **Home facts: a second expression language in the console** | `drishti-console/core/facts_expr.py` (409: its own parser and functions `ratio`, `top`, `min`, `max`, `round`) and `core/facts.py` (518), for the fact of the day on the public landing page | Decide inside the One Home lane: the facts become Rachana-EL measures served by the Home's server side, and the two files go. If the owner keeps the public landing's fact tile for i8, this row moves to PLAN i9; it must not survive unnoticed |

## 2. PLAN (a release is named for each)

| # | What is done twice | Evidence | Resolution | Release |
|---|---|---|---|---|
| P1 | **Two Python loaders, three delta-rs writers** | Drishti: `tools/lake/build.py` (1,018), `stage.py` (524), `writers.py` (270). Lake: `python/drishti_lake/load.py` (439), `inputs.py` (250), `rowrule.py` (359), `delta.py` (248). `stage.py:79-189` redefines seven functions of `rowrule.py` (`get` identical, the others 2 to 12 lines apart *(sweep)*). Third writer: `tools/samplegen/layout.py:255-279`, used by `tools/ingest_jsonl.py` and the generators. Drishti imports only `drishti_lake.filedate` and `archives` | The one Python path is Lake's. `lake build --engine deltalake` and `data ingest` call `drishti_lake.load`; `stage.py`, `writers.py` and the writer in `layout.py` go. `jsonstream.py` (JSON arrays) moves into Lake's inputs. Proof: the loader contract fixture (75 cases) and byte-equal demo lakes | i9, with Lake 0.9.3 |
| P2 | **Four switch stores** | Packs and commands: `kernel/drishti-identity/.../SwitchLedger.java` (database, gossiped by `SwitchSync`). Modules: `config/modules/<id>.yaml` and `drishti.modules.<id>.enabled` (`YamlAccessStore`). Engines: `drishti.engines.<x>.enabled` from Spring sources (`server/engines/EngineSwitches.java`). Connectors: `enabled:` in the connector file | One ledger for every administrator switch (scope module, pack, command, engine); files and settings give the shipped default and the operator's override, never the state. Design first; the cluster behaviour of a module switch is the open point | i9 (design by Fable) |
| P3 | **Two query stacks and their caches** | Pivot: `compute/drishti-bicompute/.../sql/DuckDbExecutor.java` (242) with `cache/StateCache.java`. BI: `kernel/drishti-server/.../bi/engine/DuckDbEngine.java` (212) with `bi/plan/ResultCache.java`; `bi/sql/SqlService.java` opens a third DuckDB per composite statement. The replacement is written: drishti-engines `java/engine-query` ("Drishti's two query stacks... one planner") | One query engine behind the engine API; its answers in the host's one answer cache (`engines.answers`); `StateCache` and BI `ResultCache` go with their stacks | i10 (Drishti Analytics, owner 2026-11-27) |
| P4 | **Two live-table engines and five live wires** | `kernel/drishti-server/.../bi/engine/LiveTable.java` (636), `LiveTables.java` (330) against `server/live/DrishtiLiveTables.java` (712, Rachana's `rachana-live`, "the one engine of live tables"). Fan-out hubs *(sweep)*: `TopicHub`, `GenerationBus`, `ReactiveHub`, `InboxHub`, 14 SSE controllers | BI live tables move onto the one live-results engine; one stream wire (the Data Protocol's change stream) | i10 (owner decision 2026-11-28 04:02) |
| P5 | **Two Python worker pools** | `compute/drishti-compute/.../worker/WorkerProcess.java` (373) with `Pool` (516 *(sweep)*); `kernel/drishti-server/.../pivot/functions/ProcessPythonClient.java` (348). Different wires (JSON lines against Arrow IPC), no shared launcher | The `python` engine (ENGINE_INTEGRATION_DESIGN section 2, M4): one pool, one wire | i9 |
| P6 | **The capacity planner evaluated in three places** | `drishti-console/web/static/js/drishti_capacity.js` (321, in the browser), `tools/sizing_planner.py` (968, its own evaluator), the workbook template; `server/capacity/CapacityPlanner.java` says "The server never evaluates them". Replacement written: drishti-engines `java/engine-capacity` | The `capacity` engine: one evaluator on the server, the page displays, the workbook is written by it | i9 (platform engines, M2) |
| P7 | **A second Rachana-EL parser inside an engine** | drishti-engines `java/engine-query/.../expr/Parser.java` (395, "Reads the part of Rachana-EL a query uses") with `Eval.java`; `engine-capacity/.../Expr.java` (333) is the planner's Excel subset (a different language: OK) | Before `engine-query` is adopted: it compiles with rachana-core (dependency-free), or the host evaluates through the existing `Expressions` port of `engine-inputs-files`. No third copy of the grammar | i9 (before M2 adoption) |
| P8 | **Six Lake classes copied into `drishti-api`; two of them a third time in Rachana** | `kernel/drishti-api/.../api/{ListValues 218, VectorReads 375, ChannelIo 281, ColumnSet 175, IdText 50, UnreadableData 44}.java`: each 4 lines apart from Lake's `lake-io` class (header and package only). `IdText` and `ListValues` also live in rachana-core *(sweep)*. `drishti-api` has no Lake dependency | One home. Either `drishti-api` depends on `lake-io`, or the six move to drishti-tools and Lake, Rachana and Drishti use them. Needs a Tools and a Lake patch release, so not i8 | i9 (Tools 1.2, Lake 0.9.3) |
| P9 | **About ten Python clients of the server API** | `tools/drishti.py:256` (`Api`), `tools/datacli.py:44`, `tools/launch/nodecmd.py:73`, `clusterctl.py:540`, `procs.py:200`, `tools/rolling.py:237`, `tools/doctor.py:131`, `clients/python/drishti_client.py`, console `core/backend.py:115` plus seven more console modules that build their own `httpx` client. The entity path rule is written three times (`tools/entitypath.py:28`, `backend.py:110`, `drishti_client.py:54`) *(sweep)* | Tools: `clients/python/drishti_client.py` is the one client (token, TLS, proxy, path encoding). Console: `BackendClient` only | i9 |
| P10 | **Shell operator scripts** | `deploy/aws`: 26 files, 2,002 lines; `delta-lake/` and `iceberg/` hold the same eight scripts twice (27 to 115 lines apart); `.sh` and `.ps1` twins in `delta-s3/` and `s3-tables/`. Also `tools/bench/*.sh` (338), `docs/guides/examples/embed/fetch-vendor.sh` | Phase 2 of the Python-only rule (owner 2026-11-16): `drishti.py aws ...` over `deploy/aws/lib/drishti_aws.py`, one set with `--format`. Container entry points stay (allowed) | i9 (with the production standard) |
| P11 | **Entity pages drawn two ways** | Jinja: `web/templates/_macros/panels.html` (410), `view.html` (74); 62 of 165 templates hold 136 `<table` tags *(sweep)*. The same views are served as a ViewModel and drawn by Elements' painter for embeds (`static/render/3.3.2`). I7B_DUPLICATES called this Tier 2; no release holds it today | The painter draws the console's pages; the Jinja macros go | i9 or i10: **no plan names a release; the owner should place it** |
| P12 | **Three notification channel stacks** | `server/collab/Notifier.java` (in-app, email, bridge), `server/notify/NotifyChannels.java` (247: webhook, text), `server/alerts/AlertChannels.java` (76) | One channel service (`Notifier` beans); alerts and shares hand it a message | i9 |
| P13 | **Two doors for a file into a pack's data** | `server/dataload/ReadyController` (the inbox, loaded by Lake) and `server/loads/PackDataLoader.java` (430: writes `<root>/<domain>/<date>/<kind>.jsonl` straight into the file connector) | The inbox is the door; "Load data" on a pack posts to it with the pack's rule | i9 (production rollout standard: loaders without implicit targets) |
| P14 | **Row visibility written three ways** | `server/security/Entitlements` (the row predicate); BI dataset rules compiled to SQL (`SqlCompiler.java:266`, `GroupingSetsCompiler:159,310`); `VarAccess` (155) and `ExposureAccess` (187) *(sweep)* | Host RowSet (EN-67: scope to RowSet by the host) for every engine; the BI path with the query engine | R2 for VaR and exposure (i8), i10 for BI |
| P15 | **YAML through two libraries; no shared JSON mapper** | SnakeYAML directly in 14 main files, Jackson YAML in 66; `new ObjectMapper(` about 254 times *(sweep)*. I7B_DUPLICATES R11 planned the first for G4; not done | Jackson YAML only, with a guard; one `Json` holder in tools-codec for the mappers | i9 |
| P16 | **Two Arrow IPC writers by hand, a third in Rachana** | `compute/drishti-compute/.../arrow/ArrowIpc.java` (372), `server/bi/engine/ArrowIpcWriter.java` (216), rachana-data `ArrowRows.java` (409). I7B_DUPLICATES R12 | `rachana-data`'s codec, or Arrow Flight's when it lands | i10 (Arrow Flight roadmap) |
| P17 | **Guards kept per repository** | Rachana's `rachana-guards/LicenceHeaderAndLineCapTest.java` hard-codes `LINE_CAP = 2500` and does not use `tools-guards`; Lake and Drishti do. `tools/launchers/check_launchers.py` is in four repositories (three near-identical, Drishti's 74 lines apart); `tools/license_headers.py` in two; Drishti's `tools/code_cap.py` holds 2,500 again *(sweep)* | `tools-guards` everywhere for Java; the Python checks ship once (a small Tools Python package, or the index repository) | i9 (Rachana 1.1.2, Elements 3.3.3) |
| P18 | **Three archives for a pack** | `.dmod` (Java `module pack`), `tools/packbundle.py` (343: tar.gz with manifest), `tools/packreg/packreg.py` (187: signed zip) *(sweep; whether bundle and registry zip are one artefact in use was not verified)* | With "pack = switch" (MODULE_RATIONALISATION): one archive and one packager | i9 |
| P19 | **Lake maintenance run from two hosts** | `drishti-console/core/lake_maint.py` (411: a console thread) and `drishti.py lake maintain --daemon`; one engine and one scheduler underneath (`tools/lake/maintschedule.py`), one YAML, one lock | A server maintenance API; the console only displays. The boundary guard already records this as i9 | i9 |
| P20 | **Python helpers defined many times** | `business_days` 9 times, `data_dir` 9, `sha256` 10 or more, `slug` 18, `free_port` 4 *(sweep)* | One `tools/common` module; generators adopt it when they are regenerated from seeds | i9 (test data from seeds) |
| P21 | **Risk and pricing maths in the browser** | `drishti-console/web/static/calc/drishti_quant.py` (782, Pyodide) *(sweep)*. Rules: the server computes risk; Drishti does not price | Owner decision: remove, or replace with the QLRisk engine when its API is stable | owner to place (QLRisk integration) |

## 3. TRANSITION (two ways today, a written plan removes one)

| # | What | Plan found | What must go at removal |
|---|---|---|---|
| T1 | **In-tree risk engines beside Drishti Risk** (every route switch defaults to `in-tree`) | `docs/plans/R2_CUT_PLAN.md` step 6 and the gate brief's removal list (on the `r2/*` branches); `ENGINE_INTEGRATION_DESIGN.md` EN-49 | `modules/banking/java/banking-compute` whole (55 files, 15,423 lines); the compute half of `banking-service/var` (107 files, 34,729) and `exposure` (29 files, 9,227); `compute/drishti-compute` `vector` and `linalg` (4,024); `server/playground` (605); the five route switches and `path=`; `tools/pvgen` (seven files, 2 to 4 lines apart from drishti-risk `tools/samples/pvgen`); the side-by-side tests. F2 adds what the list misses |
| T2 | **In-tree caches beside the host's answer cache** | `R2_ENGINE_SPEED_DESIGN.md` 2.1, 2.3 and 4 ("One cache, owned by the host") | Groups `var.answers`, `var.dates`, `var.hot-files`, `var.shard-books`, `var.standalones`, `var.refusals` (`VarCaches.java:32-39`) and `exposure.answers`, `exposure.trade-paths`, `exposure.dates`, `exposure.hot-files` (`ExposureCaches.java:32-35`); `VarTier` and `ExposureTier` with their hot files (one mapped store, host-owned: `engines.kept`). Kept: `engines.answers`, and the reference groups `var.views`, `var.names`, `exposure.views`, `exposure.reference`. The switch `var.zero-copy` goes: zero copy is the only mode |
| T3 | **Two Homes** (`/home` cards and `/home/role`) | Owner decision 2026-11-28 11:33; lane `i8/one-home`; pre-gate S8 | F11 and F12 |
| T4 | **The console reading lake folders itself** | Lane `i8/console-lake` (done on the branch: `core/lakes.py` 465 to 131 lines, the upload that ran `lake build` on the console machine removed, server `memlake/LakeTablesController`) | Merge it; P19 is the remainder |
| T5 | **Genomics: its own VCF connector beside the kernel bindings** | `i8/merge3` (deleted `VcfConnector`, `GenomicsKinds`, `Vcf`) | Merge it |
| T6 | **Old and new module ids** | `MODULE_RATIONALISATION.md`; `i8/modules`, `i8/trading-systems`. `RetiredModules.java` refuses the old ids at start; no alias is read | Nothing further: a refusal is the right form |
| T7 | **213 inline panels beside Rachana fragments** | Owner decision 2026-11-28 04:02 (H2: extend the core fragments, convert generator by generator) | The inline panel generators, as each is converted |
| T8 | **Lake wheel pinned at 0.9.1 while 0.9.2 is tagged** | The pins lane (`i8-pins`) | `lib/lake-wheel.pin` on every waiting branch moves to 0.9.2 before the gate |

## 4. Merge and gate notes

- F2 is the only row that can stop the i8 gate. The rest of section 1 is about 3 to 4 builder-days of Sonnet work on disjoint files
  (F1, F7, F9, F10 tools and console; F3, F4, F5, F6, F8 Java), F11 and F12 inside the One Home lane.
- F4 touches the identity store: one targeted test (a pack switched off survives a restart and reaches a peer) is enough; it is the
  test pre-gate S17 already added.
- The duplicate ratchet (`tools/duplicates.py`, baseline `java 3560`, `javascript 560`, `python 284` duplicated lines) should drop
  after section 1; lower the baseline in the same commits so the gain is kept.
- Section 2 needs the owner's word on two placements: P11 (no release holds it) and P21 (remove or wait for QLRisk).

## 5. OK (looks like a duplicate, is justified)

| What | Why it is fine |
|---|---|
| Java loader and a Python loader for Delta | the owner's one exception; bound by the loader contract fixture. P1 is about there being two Python ones |
| Native and Hadoop Delta readers | kept on purpose (owner, 2026-11-02) |
| MCP | no standalone server anywhere; Rachana ships a sajha plugin (`tools/sajha`), `rachana-mcp` is gone (Rachana `CHANGELOG.md:11`, D31 withdrawn); Drishti holds only an MCP client |
| JDK HTTP client | one builder (`DrishtiTools.httpBuilder`), guarded; the Bedrock and S3 clients belong to the AWS SDK. F8 is the one holdout |
| Hashes, workbook writer, CSV writer, temp files, business calendar, canonical JSON | one each in drishti-tools or rachana-core, each with a guard test |
| Table name to location | `TableCatalogue` only, guarded by `TableCataloguePathsGuardTest` |
| Server side of the Home | one mechanism with an interface for modules (`HomeDefinition`, `HomeTable`, `MeasureSource`) |
| Elements in the console | 576 vendored files byte-identical to tag v3.3.2; one chart engine, one grid; the guard's 29 exceptions are listed in the test |
| `engine-host` (drishti-engine) and `kernel/drishti-engine-host` (Drishti) | the product's host library and Drishti's adapter onto it; no shared class |
| Rachana's Data Protocol host in Java, Python and JavaScript | one specification, a conformance kit binds the three; each serves a different host language |
| Rachana's `rachana` command in Node with the library tool in Java | the Node command delegates `lib` to the Java tool ("one implementation"). `lint` exists in the Node and the Python command: small, noted for Rachana 1.2 |
| `tools/settingsfile.py` beside Java `SettingsFile` | the tools need the format without a JVM; one Python implementation (it loads the console's) |
| `tools/packgen/ccr/ccrmath.py` beside `ExposureMath.java` | an independent oracle for generated test data, with a parity test; it is not served |
| `RetiredModules`, `RetiredNames` | refusals, not aliases |
| Zip writing in three classes | a shrinking allow-list in `ArchitectureRulesTest.java:327-336` |
| Docs generators | one registry (`tools/docs/generate.py`, 21 entries) and one check; the scripts are separate because the documents are |
| Two classes named `PoolSettings`, `ResultCache`, `Cells` | name collisions, different jobs (`ResultCache` in BI goes with P3) |
