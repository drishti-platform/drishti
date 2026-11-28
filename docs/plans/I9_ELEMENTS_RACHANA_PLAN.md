<!-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti platform. Proprietary: see LICENSE. -->
# i9: one plan for Drishti Elements 3.4 and Drishti Rachana 1.2

**Design only, 2026-11-28. Nothing here is built; no product file was changed to write it.** One merged plan for two sources:

- **V** = Vyuha's request, `vyuha-labs/docs/design/drishti-enhancement-requests.md` v1.1 (ELM-001..026, RCH-001..016) and
  `vyuha-labs/docs/prototype/command/ELEMENTS-GAPS.md` (H1..H16, L1..L19). The owner put it into i9 on 2026-11-28.
- **D** = Drishti's own needs: the last 27 inline panels of H2, the one-way audit's P11, BI LiveTables on the live engine, the owner's
  standing rules for Elements, the GenUI and client-renderer principles, and the Home Sutra fragments of One Home.

Every "today" below was read on 2026-11-28 from: Elements tag `v3.3.2` (and `origin/develop`, which holds an unreleased 3.3.3),
Rachana tag `v1.1.1`, Drishti `origin/i8/develop` (6f903a4e3), `i8/h2` and the `i8/one-home` lane. Paths are relative to each
repository. Sizes are person-months in the request's own unit (rough, ±50%), kept so the two sources add up; they are not builder hours.

## 0. The answer on one page

1. **The request was written against Elements 3.1.0 and Rachana 0.11. A good part of it is already built.** Rachana has tags
   (`v1.0.0` to `v1.1.1`), the Data Protocol with its schemas, 7 conformance suites and a checker that takes `--base`, live tables with
   writes and four eyes, `state:`, `on:` and `actions:` in the language, and a working `<rachana-view>`. Elements has the `live` panel
   kind over the protocol, a lint hook on the code editor, and theme-following canvases. Section 2 marks each item
   **exists / partly / missing**. Net: of 42 Vyuha items, 2 are done but for a detail, 14 are partly there, 26 are missing.
2. **Wave 1 is Elements 3.4.0 and Rachana 1.2.0**, built in their own repositories beside i9, in **6 Elements lanes and 4 Rachana
   lanes with disjoint files** (section 4), after a short serial "words first" step that fixes the shared vocabulary.
3. **Three names in the request must change.** The ViewModel contract is already 1.2 (`state[]`, `actions`, `live`), so the request's
   "ViewModel 1.2" is **contract 1.3**. Rachana's language level is already 1.2, so "Rachana 1.2" below is the *product* version
   1.2.0. The designer already exports `DesignerShell`, so the application shell is **`AppShell`**, not `Shell`.
4. **Six overlaps are merged so there is one way per job** (section 1): masks, the Data Protocol client, the key registry and command
   line, as-of, guarded actions and approvals, and the span waterfall with the owner's Gantt and timeline.
5. **A finding that touches i8, not i9:** the Home Sutras of One Home use seven `core/` fragments that are **not in Rachana 1.1.1**
   (`kpi-strip`, `tile-grid`, `exceptions-list`, `change-feed`, `calendar-list`, `headline-rail`, `use-bar-table`; 52 uses in
   `i8/one-home` `config/homes/`). They sit unbuilt on Rachana `origin/i8/role-home-fragments` (commits abb47f13, 140a7544, both
   "wip: unbuilt"), and Drishti's pom still pins Rachana 1.0.1. `core/event-timeline` and `core/scatter-plot` do exist. See D-R5.
6. **Owner decisions** are in section 5: the MCP server (recommend the sajha plugin), the three Rust items (recommend none inside
   Rachana; cost given), the specification licence, and five smaller ones.

## 1. Overlaps merged (one way per job)

| # | Vyuha asks | Drishti has or needs | The one design | Drishti code it replaces |
|---|---|---|---|---|
| M1 | ELM-003, RCH-004: a masked value with a reason | Row rules and field masks on the server (`kernel/drishti-engine-host/.../RowRule.java`, `FieldMaskingTest`); the console writes "Hidden for you" or `•••` by hand in 23 places (`drishti-console/web/static/js/*.js`, `core/*.py`) | **One mask value, one chip.** `{"$masked": true, "reason": "<word>"}`; the host registers its reason words; every component draws the same chip. Drishti registers its own words (for example `entitlement`, `row-rule`); Vyuha registers its four. No reason word is named in a library | The 23 literals; `MASK` strings in chart report kinds (`drishti_chart-report-kinds.js:482,556`) |
| M2 | ELM-011: a Data Protocol client in Elements | Rachana already ships one: `js/packages/rachana/src/data_client.js` (`DataClient`, `liveTables`; tests for windows and writes). Elements' `live` kind takes it through `options.tables(ref)` (`drishti_views-datakinds.js:239`). Elements also has a second live path: `LiveSetGrid` over its own `httpLiveApi` + channel (`drishti_live-set.js`) | **One client, in drishti-data** (`rdpSource`). Rachana's `<rachana-view>` uses it from the vendored Elements and `data_client.js` is deleted. `LiveSetGrid` reads the same source (the Elements side of "BI LiveTables onto the live engine", i10) | Rachana `data_client.js`; later the console's `drishti_live.js` (155 lines) and `drishti_bi-live.js` (180) |
| M3 | ELM-001, ELM-002: shell, command line, key registry | The console has all three, outside Elements: `drishti_keys.js` (201 lines; scopes dialog, panel, screen, global; conflicts; the `?` sheet; 30 files register), `drishti_command.js` (121) on `drishti_insight-search.js` (201), `base.html` (94), `_footer.html`, `drishti_nav.css` (173). Elements' palette already expects "the console's keymap registry" (`drishti_command-palette.js:77`) | **Move, do not rewrite.** The console's registry becomes drishti-core `Keys` (plus key sequences); its command line becomes `CommandLine`; the bar, sub-bar, tabs and footer become `AppShell`. The console is the first host | `drishti_keys.js`, `drishti_command.js`, the search-box half of drishti-insight, `drishti_nav.css`, the bar in `base.html` |
| M4 | ELM-007, RCH-003: as-of an instant or a host position | Drishti has a business date **and** an instant: `drishti-console/core/asof.py` (`X-Drishti-As-Of`, `X-Drishti-Known-At`, an ISO instant pattern), `routes/asof_routes.py`; Elements' `ApiClient` sends only the date (`drishti_api.js:127`) | **One `AsOf` value**: `live` or `{date?, instant?, position?}` (position opaque). One control, one bus event, headers named by the host. Drishti maps date and known-at onto it | The console's as-of picker and its glue |
| M5 | ELM-010, ELM-020: guarded action, approver panel | Drishti's command switches page already does "review N changes, say what each blocks, ask for a reason, apply" by hand (`drishti_functions.js`, 210 lines); approvals are Jinja (`approvals.html` 96 lines, `approvals_sutra.html`) over `ApprovalQueue`; the Data Protocol already has held changes and approver decisions (PROTOCOL 16) | **`guardedAction`** (preview, expiry, reason, typed word) and **`ApprovalPanel`** (the approver's side). The hash is an opaque string the host gives | The review panel of `drishti_functions.js`; `approvals.html` |
| M6 | ELM-006 span waterfall | Owner's "more widgets" list still lacks a Gantt and a real timeline (views `timeline` is a list of dated events, `drishti_views-words.js:183`; `gantt` appears only in the AG map) | **One time-axis component, `spans`**: rows with start, end and parent on a shared axis. A trace is a tree of spans; a Gantt is spans with dependencies; a timeline is spans without a tree. Never three components | Nothing today; first users are load and job runs in Admin |

## 2. Every item

Columns: **Src** V or D · **Today** = exists / partly / missing with the file that shows it · **Where** = product and version · **Size**.
"3.4" is Elements 3.4.0, "1.2" is Rachana 1.2.0, "3.5 / 1.3" is Wave 2, "later" is Wave 3.

### 2.1 Elements, Wave 1 (3.4.0)

| ID | Src | Today | General design (no host vocabulary) | Where | Drishti benefit | Size | Needs |
|---|---|---|---|---|---|---|---|
| ELM-002 | V+D | **partly**: functions only (`drishti-core/src/drishti_keys.js`: `keymap`, `keyName`, `normalizeKey`); the registry is the console's | `Keys`: `register({id, keys, scope, area, group, description, when, run, typing})` returns unregister; chords and sequences with a time-out; the most specific scope wins; a second claim of a chord in one scope and area **throws, naming both ids**; `list()` feeds `KeysSheet` and the palette | core 3.4 | M3: the console's 201 lines go; one registry for console, embeds and other hosts | 0.3 | none |
| ELM-001 | V+D | **missing** in Elements (H1, H2, H3); built in the console (M3) | Three parts: `CommandLine` (mnemonic registry `{word, arg, title, go}`, host `suggest`, history, `<GO>`, ARIA combobox), `AppShell` (brand, bar, icon row with counts, sub-bar with facts and actions, link tabs, footer; all from options), `routeState` (serialize, restore, one query parameter) | core 3.4 | M3; also the prerequisite of P11 (D-E1) | 1.5 | ELM-002; slot for ELM-007 |
| ELM-003 | V+D | **partly**: `isMasked` (`drishti_aggregate.js:101`), "a masked member masks a sum" (`:117`), grid never edits or copies a masked cell (`drishti_live-edit-model.js:161`); no reason, no chip, charts print `•••` | M1. `registerMaskReasons({word: words})`; `maskChip(doc, value)` is the only drawing; an unknown reason draws the plain mask; `aria-label` "Restricted: <words>"; copy and export write "Restricted (<word>)"; sort, filter, group on the column refused in words; state kit gains `restricted` (H16) | core 3.4, then one call in grid, tree, graph, chart, inputs, views | M1 | 1.0 | RCH-004 words |
| ELM-004 | V | **missing** as a slot; view-level `status.notes` exist; the live panel has its own lane (`LiveNotices`, `drishti_live-edit.js:450`) | `notices: [{id, text, tone?}]` on a panel and on grid, chart, graph, tree card chrome; one status line, announced once; `LiveNotices` moves onto it | core + views 3.4 | One notice lane instead of two; "partial data" on BI panels | 0.3 | RCH-004 |
| ELM-005 | V+D | **partly**: `markup: basic` builds nodes, prints HTML as text; but `inlineNodes` makes live `<a href>` (`drishti_views-words.js:113`); no bidi or invisible-character marks | `inert` mode on the same functions and `<drishti-inert>`: links as text plus target, images as a placeholder, bidi and zero-width characters as visible tokens, a framed block with a label slot, a length cap | views 3.4 | AI answers, comments, news bodies and GenUI output drawn by one safe renderer | 0.8 | none |
| ELM-006 | V+D | **missing** (`waterfall` is the finance kind, `trace3d` is 3D points; no Gantt) | M6. `Spans` over rows `{id, parent, label, kind, start, end, status}`; virtual tree left, shared axis right; open spans grow; markers; measures (non-additive marked); zoom, pan, cut line; `applyTransaction`; table alternative | tree 3.4 (new files), registered as a views kind | Load, job, drill and engine-run timing; the owner's Gantt and timeline | 2.0 | none |
| ELM-007 | V+D | **partly**: business date only (`drishti_api.js:77`, date pickers); chart `frames` player; views `frozen` state (`drishti_views-patch.js:182`) | M4. `AsOf` control (field, slider with markers, step keys, Play, Live toggle), `asof.changed`, `actions.disabled`; views state `historical` beside `frozen` | core 3.4 (+ views, inputs one hook each) | M4 | 0.8 | RCH-003; ELM-002 |
| ELM-008 | V | **missing**: no `hlc` or resume token in `drishti_protocol.js`; `meta` passes through (`:119`) | Additive fields: `msg.hlc`, `subd.streams[s].token`, `resync {reason, at?}`, badge words "Gap healed at …", `latencyFrom(msg)` | events 3.4 | Exact "caught up at" words on live tables | 0.5 | none |
| ELM-009 | V | **missing**: `conflate` and code `slow` exist (`drishti_protocol.js:37`); no state | Frame `{t: 'fidelity', id, mode, since}`; subscription state `coalesced`; badge and a notice chip | events 3.4 | Honest "reduced detail" on a slow client | 0.3 | ELM-004 slot |
| ELM-010 | V+D | **partly**: `confirm` on view actions (`drishti_views-forms.js:150`); `Form` with `fourEyes` (`drishti_form.js:46`); one submit button only (H12) | M5. `guardedAction({title, preview, form, submit, fourEyes?, typedConfirm?})`; `Form` takes named submit actions | inputs 3.4 | M5 | 0.7 | none |
| ELM-011 | V+D | **partly**: the client exists in Rachana; Elements has the `live` kind and the `rdp.write.*` words (`drishti_live-edit-model.js:165`) but no client and no filter converter | M2. `rdpSource({base, table, query?, asOf?, live?})`, `toRdpFilter(filterModel)`; grid `ServerRowModel` and tree `loadChildren` adapters; dataset form `{rdp: …}` | data 3.4 (+ grid adapter) | M2 | 1.0 | RCH-001..004 |
| ELM-012 | V | **missing**: `errorWords` reads two fixed shapes (`drishti_api.js:64`) | `ClientOptions.errorFrom(body, status)` | core 3.4 | Engines and modules may answer in their own error shape | 0.1 | none |
| ELM-013 | V | **partly**: `highlight(ids)`, `findPath`, `setGroupBy` (`drishti_graph-view.js:131,143,205`); no counts on groups, no host path, edge look is width only (`drishti_graph-canvas.js:113`) | `groupCounts` (host-given when members are not loaded), `showPath({nodes, edges, label})`, `highlight(ids, {label, dim})`, a `ring` mark, `toolbar: top | overlay | none` (L7, L8, L9); edge upsert in `applyTransaction` (L10: `drishti_graph-model.js:118`) | graph 3.4 | Lineage and counterparty graphs: a given path, counts on folded groups | 0.8 | none |
| D-E3 | D | Pivot grid **exists** (`drishti_pivot.js`, `drishti_pivot-answer.js`, `drishti_server-rows.js`, the AG adapter `drishti_grid-ag-*.js`). Still **missing** in the native API: `flex` columns (none in `drishti_columns.js`), `autoHeight` (only the adapter, `drishti_grid-ag-events.js:297`), wrapped or variable row height (fixed per density, `drishti_grid-model-base.js:269`), chip and link cell renderers (L1..L5) | Four small native options; no new machinery | grid 3.4 | Short tables on entity pages (P11) without hand-set heights | 0.5 | none |
| D-E5a | D | KPI tile **missing** as a component (H15); `sparkline`, `bullet` exist | `Figure` tile: label, value, delta with tone, optional sparkline or bullet; the painter's `metric` draws with it | core 3.4 | What `core/kpi-strip` and `core/tile-grid` paint (D-R5) | 0.3 | none |
| L12, L13, L15, L16 | V | Toast style sheet missing (`css/` has none); palette group order; `when` shape check in `Form`; status chip on tree nodes | Small fixes, each in its library's lane | 3.4 | Same gaps in the console | 0.3 | none |

Already done since the request, so **not in the plan**: L17 (chart and graph redraw on a theme change: `drishti_chart-view.js:93`,
`drishti_graph-base.js:129`), L19 (drishti-events ships), L11 (annotations: `drishti_chart-kinds.js:76`), the lint hook of ELM-021
(`drishti_code-editor.js:85`). L18 (brand fonts in Elements) is declined: a product's fonts are the host's, by the branding rule.

### 2.2 Rachana, Wave 1 (1.2.0)

| ID | Src | Today | General design | Where | Drishti benefit | Size | Needs |
|---|---|---|---|---|---|---|---|
| RCH-001 | V | **mostly exists**: `spec/1/PROTOCOL.md` (592 lines), six JSON Schemas, `conformance/1/protocol/` (approve, forms, host, live, narrow, window, write + examples), `tools/conformance/protocol_check.py --base <url>`, the reference bridge | Fold RCH-002..004 in; the owner's word that v1 is approved; `rachana conformance protocol` as the one command over the script | spec + conformance 1.2 | Drishti's own bridge is proven by the same checker | 0.3 | 002..004 |
| RCH-002 | V+D | **missing**: PROTOCOL 8.2 still says "one numbering per table" (line 245), while rows are filtered per caller (section 9) | A stream per subscription; `seq` dense after the authorisation filter; totals, cursors and counts over the caller's rows; a "noninterference" section with two-reader cases | spec + Java live 1.2 | A reader behind a Drishti row rule sees the same `seq` gaps today; check `DrishtiAuthoriser` + `KindStream` at lane start, expected to be a real fix | 0.3 | none |
| RCH-003 | V+D | **partly**: `asOf` is a query field with no meaning (line 137); `snapshot` in `can` (line 40); `<rachana-view today>` | `asOf` = ISO time or opaque position; `version.at`; `historical: true`; `can: ["asof"]`; a live subscription with `asOf` refused by name | spec + Java + JS 1.2 | M4: Drishti's dated reads through one field | 0.5 | none |
| RCH-004 | V+D | **partly**: `{"$masked": true}`, Arrow `<name>$masked`, column `masked` (3.2, 11); no reason, no notices | M1. `reason` on the mask; `maskReasons` in the base document; Arrow `<name>$reason` dictionary column; `notices` on table and query answers (from scope, never from rows); **ViewModel contract 1.3** carries both; the compiler refuses a 1.3 notice for a 1.2 painter | spec + compiler 1.2 | M1; `provenance.maskedPanels` keeps working | 0.7 | none |
| RCH-006 | V+D | **partly**: `<rachana-view>` is built (`js/packages/rachana/src/rachana_view.js`: compile, bind, live, forms, writes, `linkResolver`); it runs on the main thread and vendors Elements 3.3.1 | Bind in a Worker; data through drishti-data's `rdpSource` (M2); `asOf` and `historical`; `registerKind` pass-through | JS package 1.2 | The embed and GenUI kit paint without blocking a host page | 1.0 | Elements 3.4 (ELM-011, 007) |
| RCH-007 | V | **mostly done**: tags exist; Elements 3.3.1 vendored with a SHA-384 manifest. Missing: the pair table; `LICENSE-SPEC.md` says "Draft … Not yet in force" | The pair table lives in the index repository's `COMPATIBILITY.md` (its one home); licence: owner decision 5.3 | index + release 1.2 | none new | 0.1 + legal | owner |
| RCH-009 (part) | V+D | **partly**: built at level 1.2: `state:` (SPEC 2.8), `on:` effects, `actions:` with `confirm` and `requires` (2.8 item 6), windowed live tables (`window`, `conformance/1/protocol/window.yaml`). Missing: state in the address (`url: true` is `RCH-2023` today), `requires` on a panel, `select: multi` with bulk actions, `load: visible` | As designed in the roadmap (sections 2 "State", "Data", "permissions"); the four missing pieces only | compiler 1.2 (address state, panel `requires`); 1.3 (bulk, lazy) | Deep links to a view state; panels hidden by right without a profile key | 0.8 | contract 1.3 |
| D-R1 | D | `core/data-table` has `rows, columns, title, search, limit, total-label, key, code, empty, when, span, where, id`. **Missing**: `moreLabel`, `emits`, `form`, `phone`, and `pivot` (a host profile key). Blocks the inline tables among H2's 27 left panels (`i8/h2 docs/plans/H2_CONVERSION.md`) | Core keys become optional parameters (`more-label`, `emits`, `form`, `phone`); a **profile key travels by a declared pass-through**: a fragment marks its first panel `profile-keys: pass`, the use may carry the host profile's panel keys, the profile validates them. `pivot` then needs no Core parameter | library `core` 1.2 + compiler | H2 reaches 143 of 143; the inline table shape is deleted from the generators | 0.3 | none |
| D-R2 | D | `core/tree-table` has `rows, title, open` only. **Missing**: `key`, `code`, `source`, `children`, `expand`, `highlight` (the GLEIF tree panels) | The same panel keys as `data-table`, plus `children`, `expand`, `highlight` | library `core` 1.2 | GLEIF group and parent trees on the fragment | 0.2 | none |
| D-R3 | D | `core/record-card`: `facts` is `required: true` (line 29). A kv with `rows:` and no columns stays inline (path-book and correlation-matrix `stamp`, fund and finance kv panels) | `facts` optional: without it the card lists the record's own fields in their order; `phone` as in D-R1 | library `core` 1.2 | The kv panels left inline | 0.1 | none |
| D-R4 | D | `core/tabbed-views`: slot `body` is `max: 6` (line 37); the fund `views` tabs hold 12 panels and a per-tab `when` | `max: 16` and an optional `tab-when` function | library `core` 1.2 | The fund page; no bespoke workaround | 0.1 | none |
| D-R5 | D | `core/event-timeline` (`rows`, `title`) and `core/scatter-plot` (`rows`, `title`, `x-label`, `y-label`, `x-fmt`, `fmt`) **exist**. **`core/tile-grid` does not**, nor `kpi-strip`, `exceptions-list`, `change-feed`, `calendar-list`, `headline-rail`, `use-bar-table`: 34 fragments in `libraries/core` at v1.1.1, none of these; all eight (with `stress-tiles`) are "wip: unbuilt" on `origin/i8/role-home-fragments` | Finish that branch through the library gates (tests, examples, doc blocks, LG3); `kpi-strip` and `tile-grid` paint with the `Figure` tile (D-E5a) | library `core`, **as early as it can ship** (decision 5.5) | One Home paints; today its Sutras name fragments no release holds | 0.4 | D-E5a for the look |

### 2.3 Wave 2 (Elements 3.5, Rachana 1.3)

| ID | Src | Today | Design in one line | Size | Needs |
|---|---|---|---|---|---|
| ELM-014 sequence | V | missing | `sequence` kind: participants, events on a vertical time axis, statuses by line style and glyph; virtualised | 2.0 | ELM-006 axis code |
| ELM-015 threads | V+D | missing | `Threads`: grouped messages, body through `inert`, a streaming message API; also the surface for GenUI streaming (D-G1) | 1.5 | ELM-005 |
| ELM-016 board | V | missing | `Board`: columns from a field, lanes, host counts, optional `move` event | 1.0 | none |
| ELM-017 edge types | V+D | missing (L6) | `edgeTypes {tone, line, end, label}`, edge `status`, hidden-edge caption, "Expand around" | 0.7 | ELM-013 |
| ELM-018 graph diff | V | missing | `graph.diff({added, removed, changed})` computed by the host | 0.7 | ELM-017 |
| ELM-019 layout worker | V+D | missing (drishti-data and drishti-events already have workers) | A `layout` part above a threshold; same seed, same picture | 1.0 | none |
| ELM-020 approval panel | V+D | missing (M5) | `ApprovalPanel`: payload tree with marked paths, the shown hash is the sent hash, void approvals struck, edit then approve | 1.2 | ELM-010 |
| ELM-021 editor modes | V | **partly**: lint hook exists | `cedarMode()` is declined as named (a host's language): a **`registerMode`** hook so a host adds its own; JSON mode with completion from a JSON Schema | 0.3 | none |
| ELM-022 interval marks | V+D | missing | `interval: {low?, high?, label}` on a series; one-sided caps; used by VaR confidence bounds | 0.4 | none |
| RCH-008 kinds | V | missing | Core kinds `spans`, `sequence`, `thread`, `board` (contract 1.4), refused for a painter without them | 1.0 | ELM-006, 014..016 |
| RCH-011 budgets | V+D | missing: only `SafeRegex` and `PanelLimits`; roadmap "counted budgets with a cost model" | Steps, cells, text and depth counted the same in every runtime; a host hook to lower them and read the count | 1.5 | none |
| RCH-012 libraries | V+D | **partly**: `.rlib`, ten `rachana lib` commands, `rachana.lock` designed (DESIGN "Sutra libraries"); the trust list and offline resolve of a third publisher to verify at lane start | As designed: resolver, lock file, `trusted-publishers`, `require-signed` | planned | none |
| RCH-013 `ops`, `ai` | V+D | missing (ROADMAP 6) | The two libraries; `ops/approval-queue` with a bound decision; nothing agent-specific in names | 1.5 | RCH-008 |
| RCH-014 graph tables | V+D | missing; `distinct` exists only as a group aggregate (PROTOCOL 6.3) | `<name>.nodes` / `<name>.edges`, `sample`, `around`, `hidden` counts; `distinct` query | 1.0 | RCH-001 |
| RCH-009 rest | V | see 2.2 | `select: multi` + bulk, `load: visible` | in 2.2 | |
| RCH-015 | V+D | **partly, as a plugin**: `tools/sajha/` (`search_fragments`, `get_fragment`, `check_sutra`, `publish_ui`); the standalone server was retired in 1.1.1 | Decision 5.1. Add `describe_table`, `update_dashboard`, and a check against host table schemas without data | planned | owner |
| D-G1 GenUI streaming | D | **partly**: boards pushed over SSE by the kit; view patches in Elements (`drishti_views-patch.js`); `linkResolver` and the `elements-view:link` event in `<rachana-view>`. Missing: the prefix rule and `panel-add` (ROADMAP 4), in-place navigation with a back stack (`navigation: in-place | host | new-window`) | One `render()` for static, live and streamed; links, drill-downs, provenance and masks intact; never a new window unless the host asks | 1.5 | RCH-006, ELM-015 |
| D-E1 P11 | D | Entity pages drawn twice: Jinja `_macros/panels.html` (410 lines), `view.html`, 165 templates; the same views already a ViewModel for embeds | The painter draws the console's pages inside `AppShell`; the macros go. Elements needs nothing beyond Wave 1 (ELM-001, 002, 003, 004, 007, D-E3) | 1.5 (Drishti lane) | Elements 3.4 pinned |
| D-E2 BI live | D | Two live tables on the server (`bi/engine/LiveTables.java` and `server/live/DrishtiLiveTables.java`) and two in the browser (M2) | i10 on the server; the Elements side is M2 in 3.4, then `httpLiveApi` is removed in 3.5 | 0.5 | ELM-011 |
| D-E6 designers | D | The kit **exists** (`LayoutCanvas`, `Palette`, `Inspector`, `Outline`, `YamlPane`, `DesignerShell`). The console still carries `drishti_designer-grid.js` (387), `drishti_bi-design-canvas.js` (354), `build/drishti_canvas.js` (284), `build/drishti_inspector.js` (242) and the connector and pack designers | A Drishti audit first (some may already be thin callers of the kit), then conversion; Elements work only where the audit finds a gap | audit 0.1 | none |
| D-E8 Vega-Lite | D | Vega is not drawn (decided); the translator `drishti_chart-vega.js` remains | Convert the stored BI visuals to drishti-chart specs once, then delete the translator | 0.3 | none |

Checked against v3.3.2 and **complete, nothing to build**: maps (world, 252 countries' first-level regions in
`vendor/echarts/maps/regions/`, `data/world-regions.yaml`, so US states and Canadian provinces and territories are in), network graph,
tree and org chart, lineage DAG (`drishti_graph-lineage.js`), sunburst and icicle (drishti-tree), calendar heatmap (`calendar` kind),
sparklines and bullets, the Playground with the code that drives each visual (`gallery/drishti_gallery-code.js`,
`drishti_playground-frame.html`). Every component of this plan ships with its Playground page.

### 2.4 Wave 3 (later)

| ID | Src | Today | Note | Size |
|---|---|---|---|---|
| ELM-023 graph canvas | V+D | missing | On the designer kit's `CommandStack`, `Selection`, `Palette`, `Inspector`; also what a Drishti pipeline or lineage editor would use | 3.0 |
| ELM-024 edit operations | V+D | missing in JS; Rachana has them in Java | Id- and path-addressed operations from every designer command; the same closed list as Rachana's patch documents (ROADMAP 2), never a second list | 1.2 |
| ELM-025 run compare | V | missing | Two `Spans` aligned by host pairs | 1.0 |
| ELM-026 AG-UI codec | V | missing | A codec **registered by the host** through a codec hook in drishti-events; the AG-UI mapping itself is offered as an optional part, so the core library names no outside protocol | 1.0 |
| RCH-005, 010, 016 | V | missing | Rust: decision 5.2 | 2.0 / 5.0 / 1.0 |
| D-E5b more kinds | D | parallel coordinates, chord, funnel, word cloud, hexbin, stream and horizon are not among the 27 registered chart kinds | The owner's lower-priority list; one batch, each with its page | 1.5 |
| H8, H9, H10 | V | missing | `Composition` rows, multi-line result list (served by D-E3's wrapped rows), item list: decide after Wave 2, each may be a grid or views mode and not a component | 0.8 |

## 3. Totals

| | Wave 1 | Wave 2 | Wave 3 |
|---|---|---|---|
| Elements | 11.2 (V items 10.4 after what exists; D items 0.8) | 9.6 | 8.5 |
| Rachana | 4.8 (V 3.7, D 1.1) | 6.5 + planned items | Rust 8.0 if taken |
| Drishti adoption (its own repository) | 2.0 | 2.4 (P11, BI live, designers, Vega) | |

The request's own Wave 1 figure was 11.3 + 4.7 with the Rust kit (2.0) inside it. Without Rust and with what already exists, Vyuha's
Wave 1 is 14.1; Drishti's own items add 1.9.

## 4. Wave 1 build order: parallel lanes, disjoint files

**Step 0, serial, one builder each, before any lane (about three days).** This is what lets the lanes run apart.

| Step | Repository | What is fixed | Files |
|---|---|---|---|
| 0a | Rachana | The words of RCH-002, 003, 004 in the protocol text and schemas; ViewModel contract 1.3 schema (mask reason, notices, `historical`) | `spec/1/PROTOCOL.md`, `spec/1/data-protocol/*.json`, `spec/1/viewmodel.schema.json` |
| 0b | Elements | `maskChip`, `registerMaskReasons`, the notice slot helper and the `restricted` state in drishti-core, exported; the generated contract schema taken in; the bundle budget raised once; 3.3.3 from `develop` taken in | `drishti-core/src/drishti_format.js`, `drishti_states.js`, `drishti_index.js`, `css/drishti_states.css`, `drishti-views/src/drishti_views-schema.js` |

**Then the lanes.** One library folder per Elements lane; a lane owns that folder's `src/`, its `drishti_index.js`, its
`gallery/drishti_gallery-demos-<library>.js` and its tests. Nobody edits `docs/API.md` (generated at the merge), and each lane writes
its CHANGELOG lines in its own fragment file, joined by the lead.

| Lane | Repository · folder | Items, in this order | Model | Size | Waits for |
|---|---|---|---|---|---|
| **E1 core** | Elements · `drishti-core` | ELM-002 → ELM-012 → ELM-001 (`Keys`, `CommandLine`, `AppShell`, `routeState`) → ELM-007 control → `Figure` tile → L12, L13 | Opus | 3.2 | 0b |
| **E2 events** | Elements · `drishti-events` | ELM-008 → ELM-009 (+ vectors) | Sonnet | 0.8 | none |
| **E3 graph** | Elements · `drishti-graph` | ELM-013, edge upsert, mask chip in labels and tooltips | Sonnet | 0.9 | 0b |
| **E4 spans** | Elements · `drishti-tree` (new files) + `drishti-chart` (mask chip only) | ELM-006; L16; chart tooltips and report kinds on `maskChip` | Opus | 2.2 | 0b |
| **E5 views + inputs** | Elements · `drishti-views`, `drishti-inputs` | ELM-005 → ELM-004 (and `LiveNotices` onto it) → ELM-010 + named submit actions → `historical` state → L15 | Opus | 2.2 | 0b; E1's `AsOf` event name (fixed in 0b's notes) |
| **E6 data + grid** | Elements · `drishti-data`, `drishti-grid` | ELM-011 (`rdpSource`, `toRdpFilter`, adapters) → grid mask chip, copy and export words → D-E3 (flex, autoHeight, wrap, chip and link cells) → `LiveSetGrid` on `rdpSource` | Opus | 2.3 | 0a, 0b |
| **R1 protocol** | Rachana · `spec/`, `conformance/1/protocol`, `conformance/1/live`, `tools/conformance`, `tools/reference-bridge`, Java live-table classes | RCH-002 → 003 → 004 (protocol half) → 001 (cases, checker command) | Opus | 1.8 | 0a |
| **R2 compiler** | Rachana · `java/rachana-core` compile and bind, `spec/1/SPEC.md`, `conformance/1/sutra`, `conformance/1/plan` | RCH-004 (contract 1.3 half) → panel `requires` → state in the address → `profile-keys: pass` (D-R1) | Opus | 1.3 | 0a |
| **R3 libraries** | Rachana · `libraries/core` only | D-R5 (finish the role-home branch) → D-R1 parameters → D-R2 → D-R3 → D-R4 | Sonnet | 1.1 | R2 only for `pivot` pass-through; the rest at once |
| **R4 view** | Rachana · `js/packages/rachana`, `js/vendor` | Vendor Elements 3.4 → `<rachana-view>` on `rdpSource`, delete `data_client.js` → Worker bind → `asOf`, `registerKind` | Opus | 1.0 | Elements 3.4 release candidate (E6, E1) |

Ten lanes, never more than six builders at once (the standing limit): **first set** 0a, 0b, then E1, E2, E3, E4, R1, R3; **second
set** as slots free: E5, E6, R2; **last** R4, then the two releases, each behind its own gate review. Heavy jobs are small here (Node
tests and the gallery sweep in Elements, `./mvnw verify` in Rachana) and go through `heavy.sh` as always. A rendered sweep of the
gallery in both themes is part of the Elements gate (the lesson of 2026-11-16).

**Drishti's own lanes, after the pins move to Elements 3.4 and Rachana 1.2** (Drishti i9, its repository): A1 console onto `Keys`,
`CommandLine`, `AppShell` (deletes M3's files); A2 mask chip and reason words (M1's 23 literals); A3 as-of (M4); A4 the command
switches review on `guardedAction`; A5 H2's last 27 panels (re-run `corefrag.py`, also teach it the YAML flow form, 3 panels); then,
where the owner places them, P11 and the designer audit.

## 5. Owner decisions needed

### 5.1 RCH-015: a Rachana MCP server, against the one-MCP-server rule

The request asks for "a Rachana MCP server". The owner's rule of 2026-11-27 is one MCP host, sajhamcpserver, and Rachana 1.1.1
already retired the standalone server (CHANGELOG v1.1.1; ROADMAP 3; decision D31 withdrawn). **Recommendation: deliver RCH-015 as
the sajha plugin that exists (`tools/sajha/`), with the three added tools.** The tools' logic is a plain Python package
(`tools/sajha/rachana_genui`), so a host that has its own MCP surface can call the same functions in process; that gives Vyuha what it
needs without a second server from us. Needed: the owner's yes, and a line to Vyuha that the item is met this way.

### 5.2 The Rust items: RCH-005, RCH-010, RCH-016

Rust would be a fourth language in a product whose promise is "the same bytes in Java, JavaScript and Python" (2,607 conformance
cases per runtime today). Costs beyond the estimates:

- a new tool chain in the gates and in CI, with crates vendored (the no-public-registries rule), about 0.5 to set up;
- every later protocol or language minor is built and proven a fourth time, for good;
- no use inside Drishti: the server is Java, the console Python and JavaScript; the Windows QA machine takes no native binaries.

| Item | Estimate | Recommendation |
|---|---|---|
| **RCH-005** Rust server kit for the Data Protocol | 2.0 (+0.5 set-up) | **Not inside Rachana.** Rachana delivers what makes any kit provable: the schemas, the cases and the checker that takes any base address (RCH-001, Wave 1). The crate is built **in its own repository** (`drishti-rachana-rust`, its own version and gate), in Wave 2, after RCH-001..004 are fixed; or by Vyuha in its tree (its own contingency: "about the same effort, Vyuha-owned"). The owner chooses who; either way Rust never enters Rachana's build |
| **RCH-010** Rachana-EL in Rust (predicate profile) | 5.0; 10 to 14 for all of Core | **Not in i9.** It is gated by Vyuha's own choice (CEL stays in its v1). Build RCH-011 (counted budgets) first, in the three runtimes: the Rust port must match those counts and cannot be specified before them. Decide again when Vyuha's gate is reached |
| **RCH-016** Rust host for the JavaScript bundle | 1.0 | **Decline for now.** It is "general: partly" by the request's own mark, and its contingency (lint in CI and in the browser, or the existing `rachana` command) costs nothing |

This differs from decision 1 of the request ("the Drishti project builds every item"); the owner settles it.

### 5.3 RCH-007: the specification licence

`LICENSE-SPEC.md` reads "Draft for the owner's review. Not yet in force", and `spec/1/PROTOCOL.md` cites it as a draft. The tags the
request asked for exist. **Recommendation:** tie it to 5.2. While only the Drishti project implements the specification, the draft can
stay a draft. The day a separate company implements the protocol or the expression language from `spec/` and `conformance/` (Vyuha
building its own kit), the licence must be in force first: the owner reads the draft, a lawyer looks once, and the header lines in
`spec/` drop "draft". Also needed with it: the owner's word that Data Protocol v1 is approved once RCH-002..004 are folded in.

### 5.4 Smaller decisions

| # | Question | Recommendation |
|---|---|---|
| a | Where does P11 (entity pages drawn by the painter) go? The audit says no release holds it | Its Elements prerequisites are Wave 1 anyway; do the Drishti conversion in **i9 after the pins move**, so the Jinja macros and the shell go in one release |
| b | The home of the Data Protocol client (M2) | **drishti-data**; Rachana deletes its copy in 1.2 |
| c | Key conflicts: the console records them and a test fails the build; Vyuha asks for a throw | **Throw**, naming both ids; the console's guard test already keeps the set clean |
| d | `cedarMode` in Elements (ELM-021) and the AG-UI codec in drishti-events (ELM-026) name outside products | A `registerMode` hook and a codec hook in the libraries; the two mappings ship as optional parts |
| e | Wave 0 of the request (Rachana-EL in Rust early) | Not taken (follows 5.2) |

### 5.5 A finding for i8, outside this plan's scope

One Home's Sutras name seven `core/` fragments that Rachana 1.1.1 does not hold (D-R5), and Drishti pins Rachana 1.0.1. Either the
role-home branch is finished and released ahead of 1.2 as a library-only patch (Rachana 1.1.2, library `core` 1.2.0, language
unchanged, as 1.1.1 was), or One Home cannot paint from a released Rachana. **Recommendation: the 1.1.2 patch, as lane R3's first
step, whenever the owner opens it.**

## 6. What this plan does not do

- It does not start anything during i8.
- It adds no Vyuha word to a library: reason words, mnemonics, routes, hashes, policies and agent terms are host data throughout.
- It builds nothing twice: where the console already has the thing (keys, command line, as-of, the review panel), the library gets
  that code and the console becomes its first host.
- Items marked "to verify at lane start" (RCH-002 in Drishti, RCH-012's trust list, the designer audit) are read first and may shrink.
