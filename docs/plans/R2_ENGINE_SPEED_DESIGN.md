<!-- Copyright (c) 2026 Ashutosh Sinha. Proprietary: see LICENSE. -->
# R2 engine speed design: the Drishti to engine path as fast as the in-tree path

Fable design review, 2026-11-28, 09:25 to 09:45 (owner request). Design only: no product code was changed, nothing was built or run by
this review; it read code, the lane's results and the lane's JFR recording. It is what the next Opus lane builds from.

| Read | Where | Commit |
|---|---|---|
| `drishti` | worktree `drishti-lanes/r2b`, branch `r2/consumers-speed` | `22e9aea05` |
| `drishti-risk` | worktree `drishti-lanes/risk-r2b`, branch `r2/consumers-speed` | `b6795e3` |
| `drishti-engine` | `origin/develop` (`spec/1/SPEC.md`, `engine-api`, `engine-host`) | `2065af5` |
| Results | `speed-mapped-zc-off.json`, `speed-mapped-zc-on.json`, `speed-heap-zc-off.json` (100,000 positions) | 09:17, 09:24, 09:30 |
| Profile | `~/drishti-bench-scratch/r2b/jfr3/rec-1353867.jfr` (229 s, 30 inner nodes, 30 leaves, 3 trees) | 09:36 |

The lane's own reading of the same recording is `~/drishti-bench-scratch/r2b/PROFILE_SUMMARY.md`; this note agrees with it and adds four
causes it does not name (sections 1.2 to 1.5, marked **new**).

**The owner's rules this design is held to.** Bulk data exists once, in memory the host owns, read in place (zero copy). One cache,
owned by the host; no engine-private or module-private second cache. One way per job. Nothing per viewer in a shared cache unless its
key makes it safe. The kernel never names an engine; modules mediate; an engine sees only the engine API. Every mechanism below is a
general engine-API or kernel capability that CCR, liquidity and analytics use the same way, with its spec rule (section 3). A first open
of 46 s is not acceptable to a user: first open is a hard gate criterion (section 6). Bit-equality with in-tree on both tiers is never
traded for speed.

## 1. Where the time goes

Measured (100,000 positions, 509 scenarios, window 250, figures equal to the bit in every run; p50 in ms):

| Ask | In-tree | Engine, mapped, zero copy off | Engine, mapped, zero copy on | Engine, heap |
|---|---:|---:|---:|---:|
| Inner node headline, warm | 22 | 141 | 140 | 150 |
| Leaf headline, first ask | 18 | 405 | 394 | 528 |
| Tree, 1,106 rows | 550 (heap 144) | 4,677 | 3,218 | 1,479 |
| First ask of the date | 0.28 s | 46 s | 46 s | 49 s |
| Resident memory, both paths open | 3.7 GB (in-tree alone) | 5.7 GB | 5.9 GB | 6.5 GB |

The headline finding: **on a warm ask the engine does almost nothing; the time is spent around it.** The host answer cache served
2,295 of 2,605 asks. The stamp (canonical JSON and SHA-384 of the request, row lists included) and the parameter handling are under 1 %
each of the samples: **request hashing is not the cause** and is not where the work goes first.

### 1.1 The tree (4.7 s against 0.55 s)

96.6 % of the tree's samples are in `VarEnginePath.tree` itself, and 767 of its 853 samples are one line: `limits.getIfAvailable()`
at line 554, **inside the loop over the rows**. It is a Spring `ObjectProvider` lookup (`DefaultListableBeanFactory.doResolveDependency`,
`findAutowireCandidates`), about 4 ms each, 1,106 times a tree. The remainder: `svc.hierarchies()` per row (40 samples), shaping
`node-var` and `node-pvar` (below). The engine is not asked at all (both answers are cache hits).

### 1.2 The warm inner headline (141 ms against 22 ms)

One headline makes six host asks (`node-var`; `scope-words` three times, once per `VarEnginePath.index()` call, only to read the input
stamp; `scope-var` with `scope-contributions`; `node-pvar`). All are cache hits. Each hit then pays:

- `KernelEngines.ask` → `AnswerShapes.plain` → `Tables.rows`: the **whole cached table is turned into boxed rows on every ask**
  (17 % of warm samples; `TableSchema.column` is a linear search per cell). `node-var` and `node-pvar` each hold all 3,424 nodes of every
  hierarchy. By caller: `parametric` 230 samples, `index` 156, `nodeFigures` 124.
- `VarEnginePath.rows()` then builds a `LinkedHashMap` per row, and `row()` scans them, **to read one node** (3.2 %);
  `parametricRows` builds a `Parametric` for all 3,424 nodes to return one (4.3 %).
- **new**: `ObjectProvider.getIfAvailable()` (`engines`, `service`) on every ask and every helper call, the same bean lookup as 1.1.
- **new**: `VarLoader.names(asOf, hierarchies)` returns a fresh, empty name table on every call (`tree`, `nodeParts`, `nodeName`,
  `pMatrix`); in-tree builds it once per load. It re-reads the reference kinds' `NAME` column per request, and where a kind keeps no
  column it fetches entity by entity (up to `NAME_READS` = 200 a kind). Small in this data set (16 samples), unbounded on a site whose
  desk or book kinds are documents.
- **new**: the audit line of every ask is built as `ask.outputs() + " " + params + ...` (`HostedEngine.doAsk`): for the bank that is the
  text of about 200,000 row numbers per ask, written to the audit log (2.1 % of warm samples; megabytes of audit per headline).
- Per ask, small: a new `PrincipalAccess`, `HostedEngine.signature` (SHA-256 over the caller's rules on every bound kind),
  `visibility`, `Stamping.options` (the options hashed again each ask), `LakeInputs.stamps`, `OutputGate`.

### 1.3 The leaf headline, first ask (405 ms against 18 ms)

A cache miss by nature (each leaf is asked once). 53 % of all warm samples are `MarketScopes.scopeContributions` →
`MarketScopes.summed` → `VarBook.plannedVector` lines 902 to 903: **each part is summed by the scope planner**, which builds a
`HashMap` of the covered count of every book (1,000 books) per call. A leaf with 100 positions pays that 100 times, plus once for the
whole. In-tree reads each position's row directly and keeps standalone sums (`VarPartTotals`, cache `var.standalones`). On top:
`hostPositions` goes through the in-tree `VarService.scoped` and the in-tree book for labels and sizes (so the in-tree date must be
loaded for the engine path to answer), and the row lists are built as boxed `List<Integer>`.

**new**: `MarketRiskEngine.descriptor()` is `Descriptors.of(MarketRiskEngine.class, ID)`: it **reads and parses the 700-line
descriptor YAML on every call**, and `MarketSession.table` calls it for every table of every request (6 % of warm samples:
`Descriptors$R.keys`, `StrictYaml`).

### 1.4 The first ask of a date (46 s against 0.28 s)

The two figures are not the same thing. In-tree's 0.28 s is a date **already open**: `VarConfiguration.captureNewest` opens the newest
date in the background at start (threads `drishti-var-load`, `drishti-var-official`). In-tree's cold open is 4.5 s at 200,000
positions (BENCHMARK.md section 5), so about 2.5 s at 100,000. The engine has no such start-up open, and its 46 s is four things, from
the recording's timeline (61 s under the profiler; shares of the 2,368 samples in the window):

| Step | Share | What it does |
|---|---:|---|
| `MarketDay.read` | 30 % | every input read whole before anything is asked: each row into a `HashMap`, then a `Map.copyOf`; each vector `Arrays.copyOf`; rows sorted by key; all nine inputs, the histories included (`MarketExtras.read`) |
| `MarketBooks.build` | 21 % | each row's texts fetched by name from its map, `cols.indexOf` six times a row, the vector cloned again (`tail`), the builder's sort, then `withFloat32Rows` |
| `scope-position-values` table | 29 % | **new**: not the open at all. The first headline asks the index (`VarEnginePath.index`: six outputs). `Tables$ColumnTable$Maker.sortedAndChecked` sorts and checks 100,000 rows with a comparator that looks each column up by name per comparison |
| `EngineScopeBook.<init>` and `AnswerShapes.plain` | 13 % | the 100,000-row index answer boxed into rows, then into the module's index |
| the rest | 7 % | `node-var`, `node-pvar` (0.5 s and 1.6 s, once), the bank's contributions |

So about half of "first open" is the session's open and a third is one index table built and shipped as rows. Zero copy (now engaged
end to end: the earlier "never reached" was stale test builds) changes none of it, because `MarketBooks.build` builds every row first
and only then swaps the host's float32 rows in (`HostRows.swapIn` after `b.build(sums)`): it saves 0.2 GB of heap, no time.

Two structural facts make the open worse in production than in this single-user test:

- `config/engines/market-risk.yaml` has `snapshot: { hot: 1 }` and the session key is the snapshot **and the viewer's signature** when
  isolation is per viewer (spec 7.2 rule 3; `HostedEngine.doAsk`: `new Sessions.Key(snapshot, signature)`). Per viewer is the default
  unless a certificate with the `filter-equivalence` and `interleaving` laws lies beside the JAR (spec 9.1). With one hot session, two
  users of different roles, or one user comparing two dates, **close and reopen the session on every alternation**: 46 s each time.
  Confirm on the lane's server which isolation ran (Admin, the engine's card); the design below does not depend on the answer.
- `HostedEngine.inputStamps` → `LakeInputs.stamps()` → `Table.stamp()` → `load()`: asking for a stamp **reads the input**, so the first
  stamp of a session reads every bound input whether or not any output needs it.

### 1.5 Memory (+2.0 to 2.8 GB)

- `MarketDay` keeps every delivered row for the life of the session: 100,000 `double[509]` (407 MB) and a map of texts per row. The
  book is built from them and they are never read again by the VaR outputs.
- `LakeInputs.Table.loaded` keeps the host's own column arrays of every input for the life of the session: a second copy of the columns.
- The book's rows: float32 on the heap (204 MB) with zero copy off; with it on they are the in-tree hot file's pages.
- **new**: `AnswerCache` is bounded by count (256), not bytes, and is not joined to the server's cache budget
  (`server.caches.MemoryBudget`, group `engine.answers`, which in-tree's `var.answers` joins). An entry can be a 100,000-row index answer
  or a `scope-var` with its vector.
- `VarEnginePath.indexes` is a private map of up to four `EngineScopeBook`s: a second cache, outside Admin, Caches.
- Garbage: boxed rows of whole tables on every ask (100 collections, 5.9 s of pauses in 229 s at `-Xmx6g`).

## 2. The target design

Seven general capabilities. Each names what it replaces, so nothing is left as a second way.

### 2.1 Answers are read as columns, and the host keeps what is shaped from them (the one cache)

**Kernel API.** `Engines.ask` returns an `EngineAnswer`, not a `Map`:

```java
public interface EngineAnswer {                       // com.ash.drishti.kernel.api.services
    String status();  List<String> missing();  Map<String, String> inputStamps();  String key();   // key: the answer's identity (2.4)
    EngineTable table(String output);                 // null when the output was not answered
    <T> T shaped(String name, Function<EngineAnswer, T> shape);   // built once per answer, kept with it by the host
}
public interface EngineTable {
    int rows();
    String text(String column, int row);  double number(String column, int row);  long integer(String column, int row);
    boolean isNull(String column, int row);  double[] list(String column, int row);
    int find(String keyColumn, String key);           // an index by one column, built once per answer and kept with it
}
```

- The engine's `Table` is already columnar; `EngineTable` reads its arrays in place. Nothing is boxed, and one node is one `find`.
- `AnswerShapes.plain` stays for exactly two callers: the REST answer and the Data Protocol table, at the HTTP edge. The `Map`
  overloads of `Engines.ask` are deleted once `VarEnginePath`, `VarEngineParts`, `VarResultEngine` and the exposure and SIMM paths
  have moved (one way).
- `shaped` is how a module keeps what it derives from an answer **in the host's cache**: the scope index (`EngineScopeBook`), the
  node rows by id, the tree's rows, the parametric figures by node. It is stored inside the answer's cache entry, so it is evicted,
  purged, counted and listed with it. `VarEnginePath.indexes` is deleted.
- **The cache** (`engine-host` `AnswerCache`, wired by `HostedEngine`, reported by `EngineAnswerCaches`): bounded by **bytes**
  (tables' array sizes plus the shaped values' own estimate), joined to `MemoryBudget` group `engine.answers` like every other server
  cache, listed in Admin, Caches as `engines.answers` with bytes, purged whole or per business date.
- **Keyed** by the answer's identity (2.4): engine, version, descriptor, options, the session's input stamps, the request (outputs,
  parameters, visibility signature). **Invalidated** by construction (a new data version is a new key), and eagerly: entries of a
  session are dropped when the host closes or retires it, when the instance restarts or its options change, and by Admin.
- **Per-viewer safety.** The key always carries the visibility signature (per viewer: the session's; shared: the hash of the caller's
  row rules and masks, as `HostedEngine.visibility` builds it today). A shaped value hangs off its answer, so it is reachable only
  through the same key. Rule for modules, with a guard test: a shape function reads the answer and reference data that is the same for
  every viewer; anything per caller (masking a label, an access check, `seesPositions`) is applied after, per ask. The output gate
  (`OutputGate.apply`) still runs on every hit.

Not in the cache: reference data a module reads from the lake itself. The node names (`VarLoader.names`) are kept once per date and
reference-data version in the module's existing cache group `var.views`, as in-tree keeps them with the loaded date.

### 2.2 Inputs are read in one pass and nothing delivered is kept twice (zero copy, part 1)

Why zero copy "is not engaged" in effect: it is engaged, but **after** the copy it was meant to avoid. The exact fix has two parts;
this is the first, and it needs no new API.

- **The engine reads an input when an output needs it.** `MarketSession` opens with the scenario set, FX, limits and baskets only (small).
  The vector inputs are read when the first book is built; `MarketExtras` (histories, desk P&L, sensitivities, factors) when the first
  output that needs them is asked (`MarketSession.NEEDS` already lists which).
- **A stamp is not a read.** `LakeInputs.Table.stamp()` composes `data.stamp(kind, date)` without `load()`; the session's input stamps
  are fixed when the session becomes READY (spec 7.2: a moved stamp makes the session STALE; it never changes under a running one).
- **One pass, straight into the book.** `MarketBooks.build` takes the columns by batch (`Batch.texts(column)` arrays, resolved to the
  builder's column positions once, not per row) and each vector from the `VectorSink` straight into `VarBook.Builder.trade`, as
  in-tree's `VarIntake` does. No `MarketDay.Row`, no map per row, no `Arrays.copyOf`, no `clone()`. The canonical order is the
  builder's own sort (it already sorts); `MarketDay`'s sort by key exists only because rows were collected first, and the kit's
  `order` law proves the answer does not depend on delivery order.
- **The host keeps no second copy.** `LakeInputs.Table.loaded` is released when the scan that needed it ends; a later scan reads the
  source again (the kernel's column cache is the one copy).
- **Visibility is a selection, not a session** (spec 18 rule 6; R2_CUT_PLAN 3a.3; the engine already has it: EN-63, `MarketSession.ALL`,
  `MarketScopes.visible`). `market-risk` runs `isolation: shared`: one session per date for every viewer. That needs the kit's
  certificate (laws `filter-equivalence`, `interleaving`) installed beside the JAR, which is the spec's own condition, and
  `snapshot.hot: 2` (the date and the date it is compared with). `MarketScopes.visible` is computed once per book and signature
  (session state, spec 8.6 rules 3 and 5), not per `summed` call from two maps of every row.

### 2.3 Kept images: what an engine builds from its inputs lives once, in host-owned mapped memory (zero copy, part 2)

The float32 position rows and float64 node aggregates of a date are neither an input (the lake holds float64 trade vectors) nor an
answer. Today they exist twice in design: in-tree's hot file (`VarHotFile`, `var-hot/<date>/inputs-<digest>.vhot`) and the engine's own
book. `VectorViews` (a module hands its hot file's matrices to the engine) joins the two only while the in-tree service has the date
open, and it disappears with the in-tree service at R2 step 6. An engine must not map a file itself (spec 18 rule 1). So the general
capability is:

```java
public interface EngineContext { ...  Kept kept(); }          // engine-api, additive
public interface Kept {
    /** The image kept under this name for the session's snapshot and input stamps, or empty. Read in place, under the session's lease. */
    Optional<Image> find(Session session, String name);
    /** Builds it once (one builder per key on the server, others wait), seals it, returns it. */
    Image build(Session session, String name, ImageBuilder builder) throws EngineException;
}
public interface Image { Lease lease();  Columns columns(String part);  FloatVectors floats(String part);
                         DoubleVectors doubles(String part);  ByteBuffer bytes(String part); }
public interface ImageWriter { TableWriter table(String part, TableSchema schema);  void floats(String part, int rows, int width, FloatRows rows);
                               void doubles(String part, int rows, int width, DoubleRows rows);  void bytes(String part, ByteBuffer small); }
```

- **The host owns it.** A file under the data folder (`<data>/engines/<id>/kept/<snapshot>/<name>-<digest>`), written once, mapped with
  `FileMaps`, framed as Arrow lays buffers out (spec 18 rule 4), closed under a reader count (`MappedMatrix`'s guard: a view read after
  release answers `ENG-5024`, never a crash). Bounded by `limits.disk-gb`, listed in Admin, Caches as `engines.kept`, removed when no
  session leases it and its key is no longer current.
- **Keyed by the host**: engine id, version, descriptor hash, options hash, the stamps of the inputs the image was built from, the name,
  and the visibility signature (or `all` for an image of every row, the only kind a shared engine builds). The engine supplies only the
  name (`book|HS-509|250|USD`); it cannot reach another key.
- **`market-risk`** writes its book through `ImageWriter` with the sections `VarHotFile` already defines (columns and ids, position
  runs, books and nodes, node aggregates float64, the float32 position matrix) and reads them back as views: `VarBook` over `Image`
  parts instead of a `FileChannel`. The builder's spill goes to `workDir()` by plain I/O (spec 8.5 rule 5), not to the heap.
- **What it gives.** The first open after data lands builds the image once. Every later open (a restart, an eviction, another node on
  shared storage, a second session) maps it: the in-tree "history date opened by mapping its file". The engine's heap holds the
  skeleton only.
- **What it replaces.** The engine option `zero-copy`, the `float32-rows` view (`HostRows`, a `Map<String, Object>` of arrays that
  copies 100,000 ids per call), `VarVectorViews` and the kernel SPI `VectorViews`: all transitional, removed in the step that proves
  the image (section 5, step 8). While the in-tree service still exists there are two images of a date on disk (in-tree's hot file and
  the engine's); they are mapped pages, not heap, and the in-tree one goes at step 6 of the cut plan.

### 2.4 Request identity: a scope is a row set with its own identity

Hashing is under 1 % today, so this is not a speed step at 100,000 positions; it is the proper form (spec 19 rule 1: "every scope kind
reaches the engine as a `RowSet`") and it removes the boxed lists, the megabyte audit lines and the cost at 1,000,000 positions.

- **New parameter types** `rows` and `row-groups` (spec 3.2): the caller gives a `RowSet` (or a list of them) over a named input's
  ordinals; the engine receives the `RowSet`. The kernel API builds one from runs or an `int[]`, never from boxed integers.
- **Identity**: `RowSet.digest()`, a hash of its runs computed once when it is built (in-tree's `OrdinalSet.digest()` is the same
  idea, and its answers cache is keyed by it). The stamp and the audit line carry `rows:<digest>:<count>`, exactly as a `table`
  parameter is already carried by its hash and row count (spec 3.2).
- **The stamp is built from parts that do not change per ask**: the options hash once per instance, the input stamps once per session,
  the request hash once per request (`Request` carries it; `Stamps.of` in the engine reuses it instead of hashing again). The cache is
  looked up by a key object of those parts; the stamp text is the same as today (spec 13 rule 6 is unchanged).
- **Groups the engine already knows are named, not listed** (spec 19 rule 2, `aggregate(view, rows, groups)`): `scope-contributions`
  takes `group-by: children` (of `node`) or `positions`, and the engine partitions the row set itself. Row lists cross the boundary
  only for parts the host alone can define (the terms of an expression). The labels and sizes of positions come from the engine's own
  `scope-position-values` (already in the index), not from the in-tree book: `hostPositions` no longer needs `VarService`.
- The module keeps a scope's resolution (`ScopeResolver.Resolution`, the `RowSet`) as a shaped value of the index answer, keyed by the
  canonical scope text; saved baskets and captures are per person, so their resolutions are kept under the person's own key part.

### 2.5 The engine reuses what a session has built, as session state

No engine cache. The API already defines the two places: the host's answer cache for answers, and the session for state derived from its
inputs (spec 8.6 rule 5; counted in `Session.memoryBytes`, released at `close`, no eviction of its own).

- `MarketRiskEngine.descriptor()` returns a constant (`private static final Descriptor`), and the spec says a descriptor is constant.
- `MarketBooks.Built` holds, built at first use: every node's `VarCalc.Measures` (the order and tail that `node-var`, `node-pvar`,
  `node-components`, `node-var-horizon` and the contributions all start from), and each position's standalone measures. About 4 MB
  for 3,424 nodes. `node-var` and `node-pvar` themselves stay host-cached answers.
- A part that is a position is read from its row (`VarBook.positionVector`), a part that is a node from its aggregate, several row sets
  through `VarBook.vectors(List<OrdinalSet>, sums)` (the batched read that exists). `plannedVector` runs once, for the whole.
- `engine-api` `Tables.Builder`: a table whose rows arrive in key order is checked in one pass, not sorted; the comparator reads
  column arrays resolved once (`TableSchema.column` by name is not called per cell). This is the library, so every engine gains.

### 2.6 A date is prepared when its data lands, and a user sees a state, never a wait

Spec 9.3 already has the trigger (`data-version`: "marks sessions stale; reopens hot ones; re-asks stored and subscribed requests in
class EVENT") and the engine file already has named asks. Three additions make it complete; none names an engine.

1. **`on: start`**: when the engine reaches RUNNING, the host runs the trigger's named asks for the snapshot `latest`.
2. **`data-version` sees a new snapshot**, not only a moved stamp of an open one: `HostedEngine.lookAtDataVersions` also watches
   `KindData.latest(kind)` of the trigger's inputs; a new business date is prepared like a new version of an open one.
3. **A named ask may be `prepare: true`**: it opens the session, builds or maps its kept images and fills the answer cache, as `system`,
   in class EVENT, without publishing. In `config/engines/market-risk.yaml`:

```yaml
triggers:
  - { on: request }
  - { on: start, run: [ prepare ] }
  - { on: data-version, inputs: [ pnl-vector, scenario-pv, scenario-set, fx-scenario, var-limit ], run: [ prepare ], quiet: 5s, max-wait: 60s }
asks:
  prepare: { prepare: true, as: system, snapshot: { date: latest },
             outputs: [ scope-index, scope-positions, scope-position-values, scope-nodes, scope-words, scope-limits, node-var, node-pvar ],
             params: { window: "${drishti.module.banking.var.window:250}", currency: USD } }
```

With shared isolation the prepared session, image and index are every viewer's. This is what `VarConfiguration.captureNewest` does for
in-tree today, as a host capability instead of a module's start-up listener.

**What a user sees who asks during preparation. Recommended: a clear "preparing" state, with the previous version kept answering.**

- **A new version of a date already open** (a reload): the old session keeps answering, its answers stamped with its own data version
  and a note ("a newer load of 2026-09-30 is being prepared"), until the new session is READY; then the host swaps. Spec 7.2 allows a
  STALE session to finish requests in flight; the amendment lets the host keep it for new requests until its successor is READY.
- **A date not open yet**: the ask joins the opening (`Sessions.lease` already opens once for all callers) and waits at most
  `limits.open-wait-seconds` (default 2). After that the host answers `ENG-5025` (UNAVAILABLE, "preparing"): the snapshot, since when,
  the stage in words from the engine (`Control.stage("reading 62,000 of 100,000 rows")`), and a retry time; HTTP 503 with `Retry-After`.
  The screens show it with one Drishti Elements component ("Preparing 2026-09-30: reading rows") that asks again by itself. The engine's
  Admin card lists each session as opening or ready, with the stage.
- **Not recommended: answering from in-tree during the warm-up.** It is a second way to compute the same number, it would have to be
  kept after the cut for this alone, and a figure's provenance would change silently between two asks. While the R2 route switches
  exist an operator can still route a consumer in-tree; that is a switch, never an automatic fallback.

### 2.7 The host's fixed cost per ask

- The audit line names each list or row-set parameter by digest and count, never by its values (spec 3.2 already says so for `table`).
- `HostedEngine.signature` and `visibility` are kept per caller for the life of the caller's entitlements (the kernel's
  `Entitlements` version), not recomputed per ask; `PrincipalAccess` likewise.
- `HostContext.stamp` reuses the options hash; `forwardLog` reads from its index instead of copying the log; the
  `inputsByKey.retainAll(new HashSet<>(sessions.keys()))` sweep runs when a session closes, not on every ask.
- Modules hold their collaborators (`ObjectProvider.getIfAvailable()` resolved once, at first use), never per ask or per row.

### 2.8 Memory after the design

| Held today | After |
|---|---|
| `MarketDay` rows: 407 MB of vectors and a text map per row, for the session's life | not held (2.2) |
| `LakeInputs` column arrays per input, for the session's life | released after the scan (2.2) |
| Book rows float32 on the heap, 204 MB (zero copy off) | pages of the kept image, host-owned (2.3) |
| Answer cache: 256 entries of any size, outside the budget | bytes, inside `engine.answers` (2.1) |
| `VarEnginePath.indexes` | a shaped value of the index answer (2.1) |
| Boxed rows of whole tables per ask | none (2.1) |

Expected: the engine path adds under 0.3 GB of resident memory to a server at 100,000 positions (the book's skeleton, the index, the
cache), against 2.0 to 2.8 GB today.

## 3. Engine API specification changes

All additive within `engine: 1` (spec 13 rule 1). Each also goes into `docs/architecture/ENGINES_IN_DRISHTI.md` (sections 2 "At start",
3 "The engine file", 4 "Inputs", 7 "Lifecycle") and `ENGINE_INTEGRATION_DESIGN` as EN-75 onwards.

| Spec | Change | Why |
|---|---|---|
| 3.2 | parameter types `rows` and `row-groups`: a `RowSet` over a named input; in the stamp and the audit by digest and count | 19 rule 1 says a scope reaches an engine as a `RowSet`; today it is a `list` of `int` |
| 3.2 | a `list` longer than 32 items is audited by digest and count | megabyte audit lines |
| 4.1 | `descriptor()` MUST return the same value at constant cost | parsed per call today |
| 5.1 | `EngineContext.kept()` (`Kept`, `Image`, `ImageWriter`); `Control.stage(words)` | 2.3, 2.6 |
| 5.2 new rule 8 | `InputTable.stamp()` MUST NOT read rows; a session's input stamps are fixed when it is READY | a stamp read every input |
| 5.2 new rule 9 | a host MUST NOT keep delivered rows beyond the call unless they are its one copy | the second copy in `LakeInputs` |
| 7.2 rule 4 (new) | a STALE session MAY answer new requests until its successor is READY; such an answer carries a note | no wait on a reload |
| 7.2 rule 5 (new) | a request for a session that is OPENING waits at most `limits.open-wait-seconds`, then `ENG-5025` | never an unbounded wait |
| 8.6 rule 5 | state a session derives from its inputs is allowed, counted in `memoryBytes`, has no eviction of its own; anything meant to outlive the session is an answer (host cache) or a kept image | where reuse lives |
| 9.1 | `asks.<name>.prepare`, `limits.open-wait-seconds` | 2.6 |
| 9.3 | trigger `on: start`; `data-version` also fires for a new latest snapshot; `run: [ask names]` | 2.6 |
| 11.2 | `ENG-5025` UNAVAILABLE "the snapshot is being prepared" (stage, since, retry after) | 2.6 |
| 13 rule 6 | unchanged; a host MAY look an answer up by the stamp's parts without building the text | 2.4 |
| 18 rule 8 (new) | kept images: host-owned, mapped, Arrow-framed, keyed by the host, leased; an engine names an image, never a path | 2.3 |
| 18 rule 6 | a certified engine runs `shared` with the caller's rows as a `RowSet` | one session per snapshot |
| 12.4 | kit laws: `image` (answers equal whether the image was found or built), `rows` (a `RowSet` however it was built), `prepare` (an answer after preparation equals one without) | proof for every engine |

Kernel API (`drishti-kernel-api`, surface file regenerated): `Engines.ask` returns `EngineAnswer`; `RowSet` factory; `VectorViews`
removed at step 8.

## 4. Rejected as hacks

1. Any VaR or `market-risk` name in the kernel: a kernel preload of "the VaR date", a kernel branch on an engine id or an output name.
2. A module's start-up listener that opens the engine's session (a second `captureNewest`). Preparation is the host's trigger.
3. An engine-private LRU of node tables or answers, or a static map in the engine. Reuse is session state or the host's cache.
4. A second cache in the module (`VarEnginePath.indexes` today), or caching the HTTP-shaped answer per user in the controller.
5. Answering from in-tree automatically while the engine prepares.
6. The engine mapping the in-tree hot file by its path, or a view handed over as `Object` whose shape only two classes know
   (`float32-rows` today): acceptable as the lane's proof, not as the product.
7. Raising `drishti.engine-lib.answers` or `snapshot.hot` to hide rebuilds and reopen cost.
8. Skipping the stamp, the visibility, the output gate or the audit on a cache hit ("fast path").
9. Using the scope's text as its identity without the data version and the visibility; truncating a row list before hashing it.
10. Any tolerance in the side-by-side tests, or a change of value width, to make a faster path pass.

## 5. Build plan (Opus lane; each step at most 3 hours)

Every step ends with the same proof, called **P** below: `RiskSpeedCheckTest` at 100,000 positions on the mapped and the heap tier with
`figures_differ` 0 and the scripted p50/p99; `ConsumersSideBySideTest` (59 answers, 0 differences, both tiers); `RiskSideBySideTest`;
drishti-risk `VarHotFileTest` and `MarketRecordedIT`; the kit for `market-risk`. Heavy runs through `heavy.sh`, one at a time.
Expected figures are p50 at 100,000 positions, mapped tier; they are estimates from the profile shares and are confirmed step by step.

| # | Step (repository) | Hours | Expected after the step | Extra proof |
|---|---|---:|---|---|
| 0 | **The harness** (drishti): `RiskSpeedCheckTest` measures the cold open of both paths (in-tree's start-up capture off: `drishti.var.official.on-start=false`, preload off), the open's stages from the engine card, asks and cache misses per ask type, a second viewer with another row rule, and `-Dspeed.gate=true` asserts section 6 | 2 | today's figures, reproducible in one command | the JSON has every gate field |
| 1 | **Module waste, no API change** (drishti, `VarEnginePath`, `VarEngineParts`): `limits`, `service`, `engines` resolved once; one `index()` per request and no `scope-words` ask for the stamp; `VarLoader.names` once per date and reference version in `var.views`; `svc.hierarchies()` outside the loops | 2 | tree 4,677 → about 600; inner 141 → about 110 | profile: no `DefaultListableBeanFactory` under `VarEnginePath` |
| 2 | **Engine waste** (drishti-risk, drishti-engine): constant `descriptor()`; `Tables.Builder` single-pass check with resolved columns; parts read by position row or node aggregate, the rest batched; node and position measures on `MarketBooks.Built`; `visible` once per book and signature | 3 | leaf first ask 405 → about 60; first ask of a date 46 s → about 28 s | kit `repeat`, `threads`, `order` laws; bits of every part |
| 3 | **`EngineAnswer` and the one cache** (drishti kernel API, engine-host, banking): columnar handle, `find`, `shaped`; the cache in bytes inside `engine.answers`; `VarEnginePath`, `VarEngineParts`, `VarResultEngine`, exposure and SIMM paths moved; `indexes` and the `Map` overloads deleted | 3 | inner 141 → 25 to 30; tree → 550 to 650; leaf → about 35; first ask → about 22 s | interleaving test: two viewers, different rules, alternate asks; Admin, Caches shows bytes |
| 4 | **Host fixed cost** (drishti engine-host, drishti-engine): audit by digest; signature and visibility per entitlements version; options hash once; stamps without a read; the request's hash carried by `Request` | 2 | inner → 22 to 27; audit bytes per bank headline from megabytes to under 1 KB | `EngineHostTest`; an audit line asserted |
| 5 | **Open in one pass** (drishti-risk `MarketSession`, `MarketDay`, `MarketBooks`; drishti `LakeInputs`): inputs read when needed; batches and vectors straight into the builder; nothing delivered is kept; spill to `workDir()` | 3 | cold open about 24 s → 4 to 5 s (in-tree cold about 2.5 s); first ask of a date → about 5 s; resident memory −1.5 GB | heap histogram: no `double[]` of the date held by the engine; kit `absence`, `order` |
| 6 | **Shared isolation** (drishti-risk kit certificate; drishti `market-risk.yaml`): the certificate beside the JAR, `isolation: shared`, `hot: 2`; visibility as a selection | 2 | a second viewer's first ask: no reopen, under 0.5 s | kit `filter-equivalence`, `interleaving`; side by side as a desk-limited user |
| 7 | **Kept images, the capability** (drishti-engine API, host library and kit host; drishti engine-host on `FileMaps`): `Kept`, `Image`, `ImageWriter`, Admin `engines.kept`, the `image` law | 3 | none yet | host test: build once under 16 callers, map, poison after release, restart finds it |
| 8 | **`market-risk` on its image** (drishti-risk `VarBook`, `VarHotFile` sections; drishti): the book written to and read from the image; `zero-copy`, `HostRows`, `VarVectorViews`, `VectorViews` removed | 3 | reopen (restart, eviction) under 0.5 s; engine heap for the date under 150 MB | `VarHotFileTest` on the image, every position, node and 30 random sets to the bit, both tiers |
| 9 | **Prepared dates** (drishti-engine spec and host library; drishti engine-host, Elements component, the VaR screens): `on: start`, new-snapshot `data-version`, `prepare` asks, the open wait and `ENG-5025`, the stale session answering until the swap, the card's session states | 3 | first user ask after a start or a load under 0.5 s; an ask during preparation answered or told "preparing" within 2.5 s | scripted: load, wait READY, first ask; ask during the open; reload while asking |
| 10 | **Row sets** (drishti-engine `rows`, `row-groups`, `RowSet.digest`; drishti-risk scope outputs; drishti `VarEnginePath`): `group-by` for children and positions; `hostPositions` from the index, not `VarService` | 3 | leaf → 20 to 25; the bank's headline carries no row list | kit `rows` law; the engine path answers with the in-tree date not loaded |
| 11 | **The gate run** (drishti): the scripted run of section 6 on both tiers, BENCHMARK.md section 23 rewritten, R2_CUT_PLAN status and gate brief, ENGINES_IN_DRISHTI.md and the spec text of section 3 | 2 | the table of section 6 | every side-by-side suite of the gate brief |

About 31 hours. Steps 1 to 4 are independent of 5 to 9 and can run as a second lane on disjoint files (`VarEnginePath` and the kernel
API against `MarketSession`, `MarketBooks` and `LakeInputs`); step 3 and step 10 both touch `VarEnginePath`, so they stay in one lane.
If the gate must come sooner, steps 0 to 6 and 9 meet every criterion of section 6 except "reopen" and "memory"; steps 7, 8 and 10 are
then the first items after the gate, before any removal of in-tree code.

## 6. Target numbers and the R2 speed gate

At 100,000 positions (the owner: enough), `-Xmx6g`, one scripted `RiskSpeedCheckTest -Dspeed.gate=true` per tier on a quiet machine,
three runs, the median run taken; in-tree and engine measured in the same run.

| Criterion | Today (mapped) | Gate | Expected |
|---|---:|---:|---:|
| Figures different between the paths, either tier | 0 | **0** | 0 |
| Inner node headline, warm, p50 | 141 against 22 ms | **at most 1.2 × in-tree, or in-tree + 5 ms** | 22 to 27 ms |
| Inner node headline, warm, p99 | 284 against 65 ms | **at most 1.5 × in-tree, or in-tree + 15 ms** | 60 to 80 ms |
| Leaf headline, first ask, p50 | 405 against 18 ms | **at most 1.2 × in-tree, or in-tree + 5 ms** | 20 to 25 ms |
| Leaf headline, first ask, p99 | 670 against 42 ms | **at most 1.5 × in-tree, or in-tree + 15 ms** | 45 to 60 ms |
| Tree, 1,106 rows, p50 | 4,677 against 550 ms | **at most 1.2 × in-tree** | 550 to 650 ms |
| **First user ask of the newest date after a server start** (no earlier ask) | 46 s | **at most 1.0 s** (prepared; engine card READY first) | under 0.5 s |
| **First user ask after a load commits a new date or version** | 46 s | **at most 1.0 s** once READY; READY within 15 s of the commit's quiet period | under 0.5 s; READY in 6 to 10 s |
| **An ask while a date is being prepared** | waits 46 s | **an answer (the previous version, noted) or `ENG-5025` with a stage within 2.5 s; never longer** | 2 s |
| **Cold open itself** (forced: not prepared, no image) | about 24 s of the 46 s | **at most 5 s, and at most 2 × in-tree's cold open in the same run** | 4 to 5 s |
| **Reopen** (restart or eviction, image present) | 46 s | **at most 1.0 s** | under 0.5 s |
| A second viewer with another row rule, first ask | a reopen (46 s) per alternation when per viewer | **at most 1.0 s; no session opened** | under 0.5 s |
| Resident memory, engine path alone against in-tree alone | +2.0 GB with both open | **at most 1.15 × in-tree alone** | +0.3 GB |
| Engine heap held for the date (`Session.memoryBytes`, checked by histogram) | about 1 GB | **at most 200 MB** | about 100 MB |
| Host asks per warm inner headline; cache misses among them | 6; 0 | **at most 4; 0** | 3; 0 |

**The gate criterion for speed, in one sentence:** every row above holds on the mapped and on the heap tier in the same scripted run
that shows 0 differences, and no default of a route switch flips before it does.

Two notes for the owner's decision:

- R2_CUT_PLAN 3a.4 and step 4a ask for p50 within 5 % and p99 within 10 % at 200,000 and 1,000,000 positions. This note proposes 1.2 ×
  (or +5 ms) at 100,000 as the **gate for flipping defaults**, and keeps the plan's 5 % and 10 % as the bar **before in-tree code is
  removed** (step 6), measured once at 1,000,000 as the plan says. At 20 ms an ask, 5 % is 1 ms, inside the noise of this workstation.
- First open is proved by the scripted sequence of step 0 and step 9, not by one timing: start the server and ask nothing until the
  engine card shows the newest date READY; time the first ask. Load a new date; time from the commit to READY and then the first ask.
  Ask during the open and assert the answer or `ENG-5025` arrives within 2.5 s. Restart with the image present and time the first ask.
  Each is a field of the result JSON and an assertion under `-Dspeed.gate=true`.

## 7. Open points

1. Which isolation the lane's server ran (the engine's card). If shared already, step 6 is only `hot: 2` and the second-viewer proof.
2. `risk-store` and `exposure-store` each logged "pnl-vector 2026-09-30 loaded (read, mapped to disk), 100,000 ids" at start (1.8 s
   each): two connectors mapping the same table. Outside this design; worth one look for a duplicate.
3. The in-tree tree is 550 ms on the mapped tier and 144 ms on the heap tier; the engine tree's gate is relative, so it follows, but
   the difference is in-tree's own and unexplained here.
4. At 1,000,000 positions the cold open of step 5 is about 25 s on in-tree's own figures (BENCHMARK.md section 5): prepared dates
   (step 9) and kept images (steps 7 and 8) are what keep that away from users, so they are not optional at that size.
