<!-- Copyright (c) 2026 Ashutosh Sinha. Proprietary: see LICENSE. -->
# i9 microkernel hardening: fault containment for engines and modules

Fable design, 2026-11-28, 13:30 to 13:39 (i9 front-load; owner item "improve our microkernel architecture without sacrificing
performance"). Design only: no product code was changed, nothing was built or run. It read code and the specification at the commits
below. Every latency and memory figure in this note is an **estimate** until section 6 measures it; none was measured here.

| Read | Where | Commit |
|---|---|---|
| `drishti` | `origin/i8/develop`, `origin/r2/speed-host` (`kernel/drishti-engine-host`, `kernel/drishti-module-runtime`, `compute/drishti-compute`, `tools/launch`) | `6f903a4e3`, `6b36ea3c1` |
| `drishti-engine` | `origin/r2/speed-host` (`spec/1/SPEC.md` draft 4, `engine-api`, `engine-host`, `engine-tck`) | `d4945b6` |
| `drishti-engines` | `main` (`python/`: the unbuilt `python` engine, EN-54) | `c0872f7` |
| Designs | `ENGINE_INTEGRATION_DESIGN.md` (section 8, RK-10, EN-54 to EN-74), `R2_ENGINE_SPEED_DESIGN.md`, `ONE_WAY_AUDIT.md` (P5) | this branch |

**The owner's rules this design is held to.** Bulk data exists once, in memory the host owns, read in place; one result cache, the
host's. One way per job. Clean over fast: a mechanism is a general capability of the kernel or of the engine API, never a branch on an
engine's name. Externals are optional: where the operating system offers no sandbox, that feature is off in words and the rest works.
No executables on the Windows machine: a worker is started as `java` or `python` only.

## Contents

1. [The position in one page](#1-the-position-in-one-page) · 2. [Failure modes: today and target](#2-failure-modes-today-and-target) ·
3. [The isolated host mode](#3-the-isolated-host-mode-the-process-binding-built) · 4. [In-process hardening](#4-in-process-hardening-for-certified-engines) ·
5. [Modules](#5-modules) · 6. [Specification changes and kit laws](#6-specification-changes-and-kit-laws) ·
7. [The measured-cost plan](#7-the-measured-cost-plan) · 8. [Rejected as hacks](#8-rejected-as-hacks) · 9. [Build plan](#9-build-plan) ·
10. [Open points for the owner](#10-open-points-for-the-owner)

## 1. The position in one page

**What a JVM can and cannot contain.** Inside one JVM there is no wall for heap, no way to stop a thread that does not want to stop
(`Thread.stop` is gone), and a native fault ends the process. Class loaders separate names, not faults (the specification says so:
17.3, "not a security boundary"). So in process, containment is a **contract that is proven** (the kit) and **watched** (the host); it
bounds the damage of honest mistakes. Only a process boundary contains code that breaks the contract. The design therefore has two
tiers and one rule that picks between them:

| Tier | Runs | Contains | For |
|---|---|---|---|
| **In process** (`engine.run: in-process`) | in the kernel's JVM, own class loader, own threads, own budget | exceptions, stack overflow, a request that overruns (the caller is released, the engine is quarantined), declared memory, thread and file budgets | engines whose certificate proves the containment laws; first-party module code |
| **Isolated** (`engine.run: process`) | in a worker process the kernel supervises, with the same engine API | everything above, and: heap exhaustion, a loop that never stops (the worker is killed), native crashes, thread and handle leaks; with `isolation: strict` also the network and the file system | uncertified and third-party engines, any engine that loads native code, an engine too large for the kernel's heap |

**The rule that picks (by evidence, never by name).** The host scans every engine JAR when it finds it (the kit's scan, moved into
`engine-host` so kit and host use one class) and reads the certificate beside it. A JAR with a certificate that lists the containment
laws may run in process. A JAR that loads native code runs isolated. A JAR without a certificate runs as the site's one setting says
(`drishti.engine-lib.uncertified`: `process`, `in-process` or `refuse`; the default is chosen after section 7's measurements, `process`
proposed). The engine's author writes one JAR and does not know which tier runs it; the kit's `binding` law proves both give the same bits.

**Why performance is kept.** The hot path does not cross the boundary: the answer cache, access, stamps and the output gate stay in the
kernel, so a kept answer costs the same in both tiers. Bulk data does not cross it either: both processes map the same host-owned files
(kept images, exported inputs), so there is one physical copy in the page cache. What crosses is a small control frame per computed
answer. Certified engines pay nothing but one thread hand-over per computed answer (section 4.1), which also removes a hazard that
exists today (engine work on the server's request threads).

**What is not built.** No second kernel API over a wire for modules, no shared-memory protocol of our own (RK-10 stands), no native
launcher, no in-JVM "sandbox", no automatic move of an engine from one tier to the other at run time.

## 2. Failure modes: today and target

"Today" is what the code at the commits above does. Evidence is named so each line can be checked.

| # | Failure | Today | Evidence | Target, in process (certified) | Target, isolated |
|---|---|---|---|---|---|
| F1 | The engine throws | contained: `EngineException` passes, anything else is `ENG-9000`; the instance keeps running | `EngineInstance.within` catches `Exception` and `LinkageError` | unchanged | the same; the exception crosses as canonical JSON (spec 14.2) |
| F2 | `StackOverflowError` and other `VirtualMachineError` | **not caught**: it leaves `within` as a raw `Error` on the caller's thread | the same `catch` clause | caught at the boundary: stack overflow fails that request (`ENG-9000`); out of memory is F3 | in the worker; the kernel sees `ENG-9000` or a worker exit |
| F3 | Heap exhaustion | **not contained**. The budget refuses what an engine reserves (`ENG-5002`) and is blind to what it does not reserve. Each engine's default limit is a quarter of the heap whatever the number of engines, so the limits can add up to more than the heap | `Budget.take`; `HostedEngine.init` (`maxMemory() / 4`) | made unlikely, not impossible: budgets are slices of the kernel's memory budget (no over-commit), an allocation ceiling per request, a governor that sheds before the heap is full, out of memory caught at the engine boundary and answered by quarantine (4.3) | **contained**: the worker's own `-Xmx` and direct-memory cap; an exhausted worker exits and is restarted; the kernel's heap is untouched |
| F4 | A request that ignores cancellation | **not contained**. The deadline is cooperative (`Control.check()`); nothing watches it. The request runs on the caller's thread, so it holds that thread, its admission place and its session lease without end; on a virtual thread it also holds a carrier thread of the server | `HostControl.check`; `HostedEngine.doAsk` (`i.request(() -> lease.session().ask(..))`) | the caller is always released at the deadline; a watchdog interrupts, then counts the request as stuck with its stack, then quarantines the engine (4.2). The stuck thread itself lives until the node restarts, and Health says so | **contained**: the supervisor kills the worker; its sessions reopen from kept images |
| F5 | Deadlock inside the engine | as F4 | | as F4 | as F4 |
| F6 | Thread creation and thread leaks | by scan only, and only when the kit ran: threads, executors, the common pool, parallel streams, timers are refused by the kit's static scan. The host gives each engine its own executor (a fixed pool of daemon threads) and stops it at shutdown. Under trust `folder` (the default) a JAR needs no certificate, so an unscanned JAR runs in process | `Delivery.FORBIDDEN`; `HostedEngine.init`; `StartChecks` (a certificate only under `pinned`) | the host scans at discovery; all engine work on the engine's own two pools (4.1); a thread count per engine in stats; the kit's `leak` law | **contained**: threads die with the worker; `-XX:ActiveProcessorCount` bounds its pools |
| F7 | File handles, files, disk | scan only (no file outside `workDir()`); the kept images' folder has a byte limit (`limits.disk-gb`); `ENG-5003` exists; nothing counts handles | spec 8.4, 8.5 rule 5, 18 rule 8 | the work folder's size checked on the poll; the kit's `leak` law counts handles over start and shutdown cycles | **contained**: handles close with the worker; with `strict`, the file system is held to the engine's folders by the operating system |
| F8 | Native crash (a fault in OpenBLAS or any JNI code) | **not contained**: the JVM dies with every session of the node. The kernel binds OpenBLAS in process (with a breaker for errors it can catch); an engine may carry native code in its JAR behind a Java fallback | `NativeLibraries`, `compute/linalg/NativeBlas`, `BlasResilienceTest`; spec 8.4 and 17.2 rule 4 | no engine-carried native code in process (a start check); the kernel's own maths library is proven in a child process before it is bound (4.5) | **contained**: the worker dies; `ENG-7001`; restart |
| F9 | Direct or mapped memory taken by an engine | by scan only (spec 18 rule 1: no mapping, no direct memory, no `Unsafe`); mapped memory is the host's (`KeptImages`, `FileMaps`), with reader counts and a refusal after release (`ENG-5024`) | spec 18 rules 1, 2, 8 | unchanged, with the scan run by the host | the worker's `-XX:MaxDirectMemorySize`; its mappings are read-only views of host files |
| F10 | `System.exit`, `Runtime.halt`, starting a process, opening a socket | by scan only, kit only (as F6) | `Delivery.FORBIDDEN` | the host's scan refuses in-process for a JAR with such a finding | exit ends the worker only; with `strict` there is no network and no other process to reach |
| F11 | Third-party code run by the kernel before any check | **open**: discovery instantiates every registered engine class in the kernel's JVM (static initialisers, the constructor, `descriptor()`), to compare its descriptor with the file | `EngineJar` (`ServiceLoader` provider, `descriptor()`, `ENG-1003`) | discovery reads the registration and the descriptor as files; a class is loaded only where the engine runs (3.7) | no class of an isolated JAR is ever loaded in the kernel |
| F12 | A slow or hanging `health()` or `stats()` | the poll calls every engine in turn on one scheduler thread; one that hangs stops the board for all | `HealthBoard.pollAll` | each engine polled on its own pool with a 50 ms bound (the specification's own figure, 7.4 rule 2); late is `DEGRADED` | answered from the supervisor's last heartbeat; never a call across the boundary on a page view |
| F13 | A half-written result after a crash | contained already: a kept image is written to a part file and moved into place whole; an answer enters the cache only when complete | spec 18 rule 8; `HostedEngine.doAsk` | unchanged | the same rule across the boundary: the host moves the file, the worker never does (3.4) |
| F14 | Python code | contained already: worker processes with memory and CPU limits, a wall-time kill, an audit-hook sandbox, and `isolation: strict` (a network namespace with no interface and Landlock; Linux) | `compute/Pool`, `worker/WorkerProcess`, `PoolSettings.isolation`, `drishti_compute/guard.py` | n/a | the same supervisor and the same sandbox serve Java and Python workers (one way; 3.2, 3.6) |
| F15 | Module Java code | names are separated (`ModuleClassLoader`: kernel first, the kernel API only, no other module); a refused module is absent; an unloaded loader is checked for leaks. At run time a module's code is kernel code: no budget, no watchdog, no run-time `failed` state; module sources create threads and executors in 13 files | `ModuleClassLoader`, `ModuleHealth` (loaded, off, refused, leaving, not placed), `LeakCheck`; a search of `modules/*/java/*/src/main` | section 5: module Java is glue, compute is an engine; kernel-given executors; one guard at every kernel-to-module call; Java in the kernel only for trusted modules | third-party module compute runs as engines and Python parts, both isolated |

Three findings above are holes rather than limits, and the build plan closes them first because they are cheap and independent of any
new binding: F11 (discovery runs third-party code), F4's use of the server's request and carrier threads, and F3's over-committed
default budgets.

## 3. The isolated host mode (the process binding, built)

The specification already defines it (section 14: `engine.run: process`, the `process` capability, the `binding` law, the wire forms,
`ENG-7001`, `ENG-7002`). The engine file's key is parsed and start-checked today; nothing runs it. This section is the design of that
binding as the containment tier. Nothing in it is new to an engine's author.

### 3.1 Process model

1. **One worker process per engine JAR.** Engines of one JAR share a class loader in process (17.3 rule 5) and share a worker here.
   Two JARs never share a worker: a worker is one publisher's code under one sandbox and one set of limits.
2. **The worker is the host library, not a second host.** Its main class is in `engine-host` (`com.ash.engine.host.process.WorkerMain`);
   it uses the same `EngineJarLoader`, `EngineInstance`, `Budget` and executor rules as the kernel and the kit. Its class path is the
   platform packages (17.1 rule 2) and nothing of Drishti.
3. **The kernel side is a proxy behind the same seam.** `ProcessEngine implements Engine` and `ProcessSession implements Session` forward
   calls. `HostedEngine` is unchanged above them: access, visibility, row sets, parameters, snapshots, the session table (keys, leases,
   stale and successor, the hot count), the answer cache, single flight, preparation, stamps, the output gate, admission and audit stay
   in the kernel, once, for both tiers.
4. **Started as `java`** from the server's own Java home, with an explicit command line, an empty environment and the engine's work
   folder as its directory (as `WorkerProcess` starts Python today). A Python engine's worker is started as `python` by the same
   supervisor (3.6).
5. **A pool of workers for one engine** (`engine.workers: n`) is for engines with the `partition` capability only (a shard per worker) and
   is not in the first build. The default and the only first-build value is one.

### 3.2 One supervisor (no fourth worker pool)

There are three worker-process implementations today: `compute/Pool` with `WorkerProcess` (Python items, tables, transforms), the pivot
functions' `ProcessPythonClient`, and the `python` engine's own copy in `drishti-engines` (`python/pool`: `WorkerProcess`, `FairQueue`,
`Scratch`, `PoolSettings`). ONE_WAY_AUDIT P5 already folds the first two into the `python` engine. This design must not add a fourth.

**Decision FC-1.** The language-neutral part (start, the framed control channel, the ready handshake, exit watch, kill, restart, the
limits and the sandbox entry) lives once, in `engine-host` (`com.ash.engine.host.process`), because the kit must run the `binding` law
with no host present. The `python` engine's pool keeps only what is Python's (the interpreter, approved code, frames) and uses that
supervisor; Drishti's two pools retire with EN-54 as P5 says.

### 3.3 The control channel

The worker's standard streams, as the specification says (14.2). Frames are length-prefixed; each carries a call id, so one worker
serves concurrent calls (a Java worker is multi-threaded; a Python worker takes one call at a time, as today).

| Direction | Calls |
|---|---|
| kernel to worker | `init`, `start`, `suspend`, `resume`, `shutdown`; `session` (snapshot, the inputs' manifest and stamps); `ask`, `explain`, `workbook`, `partial`, `apply`, `close`; a declared function's call; `cancel(call)`; `release(lease)`; `ping` (returns health and stats) |
| worker to kernel | `ready` (wire version, engine ids, descriptor hashes, declared functions); `inputs.read` (a scan); `supplier` calls; `progress`, `stage`, `interim`; `kept.find`, `kept.begin`, `kept.seal`; `table.begin`, `table.seal` (a large answer); log lines |

Values use the wire forms of 14.2: requests, snapshots, visibility, stamps, missing lines, explanations and exceptions as canonical
JSON; small tables as an Arrow IPC stream inside the frame. The deadline travels as time remaining, and `cancel` sets the worker-side
`Control`. The memory budget is local to the worker (its limit is the worker's heap): a reservation never crosses the boundary. The
worker's standard output is the protocol's; the engine's own `System.out` is pointed at standard error before the JAR is loaded.

### 3.4 Bulk data: the same files, mapped by both sides

Zero copy across processes means **one file, mapped read-only by each process, one set of pages in the operating system's page cache**.
The files are Arrow-framed, written by the host with the official library (18 rule 4). No memory is shared any other way.

| Data | Where it lives | How the worker reads it | Who writes |
|---|---|---|---|
| Kept images (18 rule 8) | `<data>/engines/<id>/kept/<snapshot>/<name>-<digest>.kept`, already files | `kept.find` returns the file and its parts; the worker maps them read-only | the engine writes through `ImageWriter` into `<data>/engines/<id>/incoming/`; on `kept.seal` the **host** checks the header, the size and the byte limit and moves the file into place. The worker never moves or names a kept file |
| Inputs already in a mapped file (vectors, a lake's column files) | where the host maps them | `inputs.read` returns **region descriptors** (file, offset, length, buffer kind, rows); the worker maps the regions | the host |
| Inputs the host holds only on its heap (the kernel's column cache today) | exported once per input stamp to `<data>/engines/shared/<stamp>.arrow` | region descriptors, as above | the host, once; every isolated engine that binds the table reads the same file |
| Row sets (the caller's rows) | small: in the frame, as runs or a bitmap; large: a region of a host file | as given | the host (19 rule 1: scope is the host's) |
| Small answers | the frame | Arrow IPC stream | the worker |
| Large answers (`TableWriter`, 18 rule 5) | `<data>/engines/<id>/out/` | the worker writes one file; on `table.seal` the host seals, maps and serves it | the worker writes, the host seals |

1. **Leases cross the boundary.** A view in the worker is valid under a lease the kernel granted. When the lease ends the kernel sends
   `release`; the worker poisons its views (a later read is `ENG-5024`, never a crash) and acknowledges; the kernel unmaps and may delete
   only after the acknowledgement or the worker's exit. This is the reader count `FileMaps` callers keep today, with the worker as one
   more reader. It also settles Windows, where a mapped file cannot be deleted.
2. **The export is honest about its cost.** A table the host holds only on its heap exists a second time as a file while an isolated
   engine reads it. It is still one host-owned copy per medium, shared by every worker, bounded by `drishti.engine-lib.shared-gb`, and
   kept or dropped by the kept images' own rules (it is implemented as kept images owned by the host: one mechanism). The clean end
   state, where the kernel's column cache is itself mapped Arrow memory and the heap copy is gone, is the direction of
   R2_ENGINE_SPEED_DESIGN 2.2 and the in-memory lake; this design depends on it for full zero copy of lake inputs and does not solve it.
3. **Nothing bulk on the control channel.** The host counts the bytes of each kind that cross the channel; the kit's zero-copy group
   asserts that no vector or column buffer does.

### 3.5 Lifecycle and supervision

| Event | What the kernel does |
|---|---|
| The engine starts | starts the worker, waits for `ready` (bounded by `start-timeout`), checks the wire version (`ENG-7002`), then `init` and `start`. The `on: start` preparation (9.3) opens the newest snapshot, as in process |
| Running | `ping` on the host's poll; the answer is the engine's health and stats, kept by the `HealthBoard`; a page view never crosses the boundary |
| A request overruns | deadline: `cancel`. Deadline plus the declared cancel latency: the request is overdue. Deadline plus three times it (at least 5 s): the worker is killed and restarted. The caller was answered `ENG-5011` at the deadline |
| The worker exits or stops answering pings | requests in flight are `ENG-7001` (UNAVAILABLE, with a retry time); its sessions are marked closed in the kernel's table; the exit code or signal, the last lines of standard error and the path of the JVM's crash file (`-XX:ErrorFile` under the work folder) go to the log, the audit and Admin → Engines |
| Restart | after a back-off of 1, 2, 4 ... 60 seconds with jitter. The engine's switch stays on (14.1 rule 3). Hot sessions reopen through the same preparation as a start, so a user sees "preparing" (`ENG-5025`), never a wait. Kept images are files: reopening maps them and decodes the skeleton; nothing is built again |
| Repeated crashes | a circuit breaker: three exits in ten minutes (both settable) put the engine in `failed` with the evidence in words; an administrator starts it again, or it is tried once after a cool-down. Packs that bind it report `DEGRADED` with the engine named (7.6 rule 3), as for any engine that is down |
| A request that kills the worker twice | its request hash is refused (`ENG-7003`) for `limits.refusal-ttl-seconds` or until the engine's version or the inputs' stamps change, so one question cannot keep an engine down |
| The engine is suspended, shut down, or its file changes | the same calls as in process; shutdown ends the worker after `shutdown` returns, or kills it after the cancel latency |

Hard limits, set by the supervisor from the engine file: the heap (`-Xmx` from `limits.memory-mb`), direct memory
(`-XX:MaxDirectMemorySize` from `limits.direct-mb`), processors (`-XX:ActiveProcessorCount` from `limits.threads`), exit on exhaustion
(`-XX:+ExitOnOutOfMemoryError`: a worker is crash-only), the work folder's bytes. Where the node runs under a control group the site
delegates, the worker is also placed in a group with a memory ceiling; where it does not, the heap and direct limits stand alone and
Admin says "heap limit only". An address-space limit is not used for a JVM: mapped files make it meaningless.

### 3.6 The sandbox (`engine.isolation`)

The same two words the Python pools use today (`PoolSettings.isolation`), with the same meaning and the same implementation.

| Value | Wall | Where |
|---|---|---|
| `standard` | the process: memory, crashes, threads, handles, a kill | everywhere `java` runs, Windows included |
| `strict` | also: a user and network namespace with no interface, and Landlock rules held by the operating system. Read: the Java home, the platform jars, the engine's JAR, its kept folder, the shared inputs folder. Write: its work, incoming and out folders. Nothing else: no configuration, no secrets, no other engine's folder, no connection to anything | Linux 5.13 or later with Landlock; elsewhere the engine does not start under `strict` and says why (`ENG-7004`), as the Python worker does today |

**Decision FC-2: one sandbox entry for both languages.** A Java process cannot apply Landlock to itself on Java 21 without native code.
The rules are applied by the existing guard (`drishti_compute/guard.py`, which already makes the namespace and the Landlock rule set
through the system-call interface of the standard library) and then that process **becomes** the `java` worker (`exec`); namespaces and
Landlock rules are inherited across `exec` by design. So `strict` for a Java engine needs the site's Python, which `strict` Python pools
already need; without it, `standard` is what the site has. No native launcher, no second sandbox.

Uncertified code under `standard` on Windows or macOS is contained for faults, not for intent: it can read what the server's account can
read. The site's setting has to say that this is accepted (`drishti.engine-lib.uncertified: process` with `isolation: standard` written
in the engine file); Admin shows the tier and the wall of every engine.

### 3.7 Discovery without loading classes

For every JAR, whatever its tier, discovery reads `META-INF/services/com.ash.engine.Engine` and the descriptor files as resources,
reads the function index (`functions.idx`), runs the static scan over the class files, and reads the certificate. No class is loaded.
The check that a registered class's `descriptor()` equals its file (`ENG-1003`) moves to where the engine is first created: `init` in
process, `ready` in a worker. Declared functions of an isolated engine are those the worker reports in `ready`, verified against the
index. This closes F11 for both tiers and costs the in-process tier nothing.

### 3.8 Sessions, caches, kept images and prepared dates across the boundary

| Thing | Owner | Across the boundary |
|---|---|---|
| The answer cache (13 rule 6) | kernel | not at all: a kept answer is served before any call to the engine, in either tier. Shapes the modules derive from an answer stay with its entry, as now |
| Session table, leases, stale and successor, hot count | kernel | the kernel opens and closes; the worker holds the `Session` objects by id. After a worker restart the table is empty and preparation refills it |
| Session state the engine derives (books, skeletons) | worker heap | never crosses; counted by `memoryBytes()`, reported through `ping` |
| Kept images | kernel (files) | mapped by both; found after a restart, never rebuilt |
| Input stamps | kernel | fixed when the session is ready (5.2 rule 8) and sent with `session`; the worker never computes a stamp of data it cannot see |
| Prepared dates (9.3 rule 4, `ENG-5025`) | kernel | a `prepare` ask is an ask; its stage words come back as `stage` frames; those who wait are told the same words |
| Visibility, row rules, masks, the output gate | kernel | the worker receives a `RowSet` and columns already reduced (18 rule 6); the gate runs on every answer in the kernel |
| Audit, licence, admission | kernel | never cross |

### 3.9 Latency cost (estimates; section 7 measures each)

| Path | In process today | Isolated, estimated | Why |
|---|---|---|---|
| A kept answer (cache hit) | as measured by R2 | **the same** | never reaches the engine |
| A computed answer, small request and answer | the engine's own time | plus about 0.1 to 0.3 ms | two pipe frames, canonical JSON of the request, a small Arrow table |
| First touch of a mapped input or image in a new worker | none (already mapped in the kernel) | about 50 to 100 ms per GB, once per worker | page-table faults on pages already in the page cache |
| Export of a heap-only input | none | one sequential write per input stamp (about 1 GB/s or better), once | 3.4 rule 2 |
| The arithmetic over views | read-only buffers | **the same** code over the same kind of buffer | views are `java.nio` buffers in both tiers |
| Session open | the engine's own time | the same, plus worker start when cold: about 0.3 to 0.8 s, and slower first asks until the worker's JIT is warm | a second JVM; the worker is started with the engine, not with the first request |
| Recovery from a crash | the node restarts | back-off, worker start, map the images, decode the skeleton | kept images |
| Memory | none extra | about 150 to 300 MB per worker beside its heap | a second JVM's own footprint |

## 4. In-process hardening for certified engines

### 4.1 Threads: engine work runs on the engine's own threads, always

**Decision FC-3.** A host MUST run every call into an engine (`session`, `ask`, `explain`, `workbook`, `partial`, `apply`, a declared
function, the lifecycle methods) on a thread of that engine's **ask pool**, and give the engine one **work pool** as
`EngineContext.executor()`. Both are platform threads the host names and counts; their sizes come from the engine file
(`limits.concurrent` for the ask pool, with a host default for a class the file leaves open, so no class is unlimited; `limits.threads`
for the work pool). The caller waits for the result until the deadline and is then released whatever the engine does.

1. Today the call runs on the caller's thread. Where a web request runs on a virtual thread (`HostedEngine`'s lock is written for
   that case), engine arithmetic occupies one of the server's carrier threads, and as many slow asks as the node has processors hold
   every carrier. With FC-3 an engine can use at most its own
   threads, which is the thread budget the specification already promises (8.5 rule 3) made true for the calling side as well.
2. The request's thread-locals in `HostedEngine` (`ASKING`, `ASKING_SESSION`, `LakeInputs.CURRENT`, `PREPARER`) become one explicit ask
   context handed to the task. No scoped values (a preview on Java 21).
3. There is no queue behind the ask pool: admission already answers `ENG-5001` when a class is full.
4. The cost is one hand-over per computed answer (estimated 5 to 20 µs); kept answers do not hand over. Section 7 measures it.
5. The kit's scan already refuses threads, executors, the common pool, parallel streams and timers; the host now runs that scan itself.

### 4.2 Cancellation: a contract, a watchdog, and what happens when the contract is broken

The contract exists (5.3: `check()` at least once per batch and once per declared cancel latency [K]; 8.5 rule 4). Added:

1. **An interrupt is a cancellation.** An engine MUST treat its thread's interrupt as `Control.cancelled()`: blocking calls end, the
   request ends as `CANCELLED`. An engine MUST NOT swallow an interrupt.
2. **One watchdog per host** (not per engine) walks the requests in flight:

   | When | State | What the host does |
   |---|---|---|
   | deadline | timed out | the caller is answered `ENG-5011`; the control says cancelled |
   | deadline + cancel latency | **overdue** | interrupts the thread; counts it |
   | deadline + 3 × cancel latency (at least 5 s) | **stuck** | records the thread's stack in the log and in Admin; the engine is `DEGRADED` with the words "a request did not stop"; its admission place stays taken (it is still running) |
   | `limits.stuck-requests` stuck at once (default 2), or one stuck for 60 s | **quarantined** | the instance is `FAILED`: new requests are `ENG-5026`; its sessions are abandoned (dropped, not closed, since a call still runs on them: 7.2 rule 1) and their budget is shown as held; packs that bind it are `DEGRADED` |

3. **Leaving quarantine** is an administrator's start (a new instance with new pools) or a node restart. The stuck thread cannot be
   ended inside a JVM; Health lists it until the node restarts, with the advice in words: run this engine with `engine.run: process`.
   The host never moves an engine between tiers by itself.
4. The kit's `cancel` group proves the contract before a certificate is written (section 6.2), which is what makes quarantine rare.

### 4.3 Memory: no over-commit, an allocation ceiling, a governor, and the boundary

A JVM gives no heap limit per class loader, so in process the design combines four general mechanisms and says plainly what is left.

1. **Budgets are slices of the kernel's memory budget.** The engines' session budgets are a pool of the kernel's one memory budget
   (the one that already has the `engine.answers` pool). An engine's `limits.memory-mb` is its slice; an engine without one gets an equal
   share of what is left. A set of limits that exceeds the pool refuses the start of the engine that does not fit, in words
   (`ENG-1016`), instead of today's quarter of the heap for each.
2. **A request allocation ceiling.** Because all engine work runs on the engine's threads (FC-3), the host reads the bytes those threads
   allocated (the JDK's per-thread counter) at the start and end of each ask and on the watchdog's walk. A request over
   `limits.request-alloc-mb` is cancelled as `ENG-5002` with the figure. This catches a loop that allocates without end before the heap
   does. It is also the "bytes allocated per ask" the kit's benchmark already reports, now visible per engine in production.
3. **One governor for the whole kernel.** When the heap's live size after a collection passes a threshold, the kernel sheds in a fixed
   order: its answer cache; idle sessions no request leases, oldest first across engines (a host may close a session between calls, 7.2
   rule 2; kept images make reopening cheap); then it refuses new session opens (`ENG-5002`); then it cancels the request that has
   allocated most in the engine furthest over its slice. Each step is logged and shown. The governor is the kernel's, serves modules
   and engines alike, and names no engine.
4. **The boundary.** `EngineInstance.within` catches `VirtualMachineError` on engine threads: a stack overflow fails the request; out of
   memory quarantines the instance (`ENG-5027`), abandons its sessions and runs the governor. An out-of-memory error seen **outside** an
   engine boundary means kernel state can no longer be trusted: the node reports itself unhealthy, drains and is restarted by its
   launcher (open point O2).
5. **What is left.** An engine that retains memory it did not reserve is seen only by the kit (8.5 rule 2, measured on the heap) and by
   the governor's effects. That is a limit of the tier, stated in the engine guide; an engine whose memory cannot be vouched for runs
   isolated.
6. **Off-heap and mapped memory** stay the host's alone (18 rule 1). Mapped bytes leased to each engine (kept images, exported inputs)
   are counted per engine and shown beside its heap slice; they are page cache, not heap, and are bounded by the folders' byte limits.

### 4.4 Handles, files, restarts

1. The work folder's size is read on the host's poll; over `limits.disk-gb` the next write through the context is `ENG-5003`.
2. On shutdown the host checks what the specification already requires (7.1 rule 5): no thread of the engine's pools alive, no file
   under the work folder. Added: the engine's class loader must be collectable after shutdown, checked the way `LeakCheck` checks a
   module's loader (the same class, moved to where both can use it). A leak does not block a restart; it is shown, and the kit's
   `leak` law fails certification for it.
3. File handles cannot be attributed to an engine inside a JVM. The kit's `leak` law counts the process's open handles over repeated
   start, ask and shutdown cycles in a child JVM; at run time only the process's total is on Health.

### 4.5 Native code

**Decision FC-4.** In process there is exactly one place native code may live: the kernel's own maths library (`drishti-native`, the
abstraction with Java fall-backs that is already decided), offered to engines as a platform service. An engine JAR that carries or loads
native code runs isolated; in process it is refused by a start check (`ENG-1015`). This replaces 8.4's allowance ("native code from its
own jar, behind a guard that falls back to Java") for the in-process tier and keeps it for the isolated one.

1. **Probe before bind.** A catchable failure (the library does not load, a wrong answer on the self-test) is handled today by falling
   back to Java. A fault that kills the process is not. So at start, and whenever the binding or its version changes, the kernel runs
   the library's self-test in a short-lived child `java` process, with the same thread settings the server will use. If the child dies,
   the server binds Java and says so through the channels `NativeLibraries` already has (the log, Health `DEGRADED`, the meter, the
   alert). The server loads the library into its own process only after a child survived it.
2. **Threads.** The kernel sets the library's own thread count, so native threads are inside the node's budget and never multiply with
   an engine's work pool.
3. **A native helper process for BLAS is rejected.** A call per small product across a process boundary costs more than the product;
   a call per large product is an engine in the isolated tier, which already exists. One mechanism serves both: an engine that needs
   native speed and cannot use the kernel's library runs as a process.
4. ENGINE_INTEGRATION_DESIGN 9.1 lists an OpenBLAS binding among Drishti Risk's merged private libraries; none is in the build files of
   `drishti-risk` `main` today. If one is added, FC-4 decides its tier (open point O3).

## 5. Modules

A module's Java part is a Spring context under the kernel's, with routes, service registrations and beans the kernel calls directly.
Moving it to a process would need every kernel service and every registration point remoted: a second kernel API over a wire, kept in
step with the first for ever. That is dual machinery and is rejected (section 8). The same containment is reached by where code is
allowed to run:

1. **FC-5: compute is an engine.** A module's Java is glue: routes, bindings, shaping an answer for a page. Anything that computes over
   bulk data, holds large state, runs long or carries native code is an engine (Java or Python) the module binds, and so gets the tiers
   of this design without any module-specific mechanism. The modules already mediate between kernel and engines; this makes it a rule
   with a conformance check (a module route that reads lake rows in bulk through anything but an engine or the query layer is a finding).
2. **FC-6: Java in the kernel only from a trusted module.** `drishti.modules.trust` mirrors the engines' setting. A first-party or
   signed module may contribute a `java:` part. An untrusted module is **declarative plus engines**: packs, Sutras, pages, routes mapped
   by configuration to engine asks and declared functions (EN-66 gives exactly this mapping), its Python part in a `strict` pool, its
   Java compute as engine JARs in the isolated tier. Untrusted code never runs in the kernel's process, by construction.
3. **The same in-process laws for trusted module Java**, checked by `ModuleConformance` (which already reads class files) with the
   engines' scan list: no thread, executor, common pool, timer, `System.exit`, process, native library. Threads come from a kernel API
   service that gives each module named, bounded executors, stopped at unload. Thirteen files under `modules/*/java/*/src/main` create
   threads or executors today; each moves to that service or is justified in the audit that opens the step.
4. **One guard at every kernel-to-module call.** Where the kernel calls module code (a route, a registered provider, a listener, a
   scheduled job) it goes through one guard: the module's loader as context loader, the time, an exception turned into that module's
   problem with its code, a count per module. A module whose calls fail or overrun beyond a threshold gets a run-time `failed` state in
   `ModuleHealth` (today: loaded, off, refused, leaving, not placed), its routes answer 503 with the module named, and the kernel and
   every other module go on. An administrator's reload is the way back.
5. **Memory.** Module code allocates on the kernel's heap under the governor (4.3 rule 3); a module's caches must be registered pools
   of the kernel's memory budget, never private maps (the one-cache rule).
6. **Python parts** are isolated already (pool `m-<id>`, limits, `isolation`); they move onto the one supervisor with EN-54.

## 6. Specification changes and kit laws

### 6.1 Specification (`drishti-engine` `spec/1/SPEC.md`), all additive within `engine: 1` except where marked

| Spec | Change | Decision |
|---|---|---|
| 5.3 `Control` | new sentence: an engine MUST treat its thread's interrupt as a cancellation and MUST NOT swallow it [K] | 4.2 |
| 7.1 rule 5 | after `shutdown` the engine's class loader MUST be collectable [K] | 4.4 |
| 7.2 new rule 7 | a host MAY abandon the sessions of an instance it has failed for a request that did not stop; it MUST NOT call `close` on them | 4.2 |
| 7.4 rule 2, 7.6 rule 1 | a host MUST poll each engine apart from the others and bound each call; late is `DEGRADED` | F12 |
| 8.4 | **amended**: native code may be loaded from the engine's own JAR only when the engine runs in the process binding; in process an engine has none. A host MUST enforce the scan itself at discovery | FC-4, F6 |
| 8.5 rule 1 | a host MUST give budgets that do not add up to more than it has; a request over its allocation ceiling is `ENG-5002` | 4.3 |
| 8.5 rule 3 | **strengthened**: a host MUST call an engine only on threads of the engine's own ask pool; all other work on `EngineContext.executor()` | FC-3 |
| 8.5 new rule 6 | what a host does at the deadline, when overdue, when stuck, and quarantine (`ENG-5026`) | 4.2 |
| 9.1 | `engine.isolation` (`standard`, `strict`); `engine.workers`; `limits.direct-mb`, `limits.request-alloc-mb`, `limits.stuck-requests`; `supervision` (`start-timeout`, `backoff`, `breaker: { exits, within }`, `retry-after`) | 3.5, 3.6, 4.2, 4.3 |
| 9.4 | start checks: a JAR with native code and `in-process` (`ENG-1015`); budgets over the pool (`ENG-1016`); `strict` where the operating system offers none (`ENG-7004`); an uncertified JAR against the site's setting | section 1 |
| 11.2 | `ENG-1015`, `ENG-1016` REFUSED; `ENG-5026` UNAVAILABLE (quarantined: a request did not stop); `ENG-5027` UNAVAILABLE (quarantined: memory); `ENG-7003` REFUSED (this request ended the engine's process twice); `ENG-7004` UNAVAILABLE (the sandbox asked for is not available here) | |
| 12.3, 12.4, 12.6 | the containment group and laws of 6.2; the certificate lists the scan's verdict and the laws passed | 6.2 |
| 14.1 | rule 2: the one supervisor and worker main class; rule 3: back-off, the circuit breaker, the refused request, hot sessions reopened by preparation; rule 4: "a site does not trust" defined by the scan, the certificate and the site's setting; new rule 5: limits and the two isolation words; new rule 6: no class of an isolated JAR is loaded by the host | section 3 |
| 14.2 | region descriptors for views; kept images and large answers by file with the host's move; leases, `release` and its acknowledgement; no bulk buffer on the control channel [K] | 3.4 |
| 17.4 rule 2 | **amended**: discovery reads registrations and descriptors as files and loads no class; `ENG-1003` is raised at first creation | 3.7 |
| 17.6 | the site setting `uncertified` (`process`, `in-process`, `refuse`) beside `trust`; what each tier walls | section 1 |
| 18 rule 2 | leases hold across a process boundary: poison, acknowledge, then unmap | 3.4 |
| new section 20 "Fault containment" | the two tiers, the rule that picks, the table of section 2's targets as obligations of a host | all |

Drishti's own documents: `ENGINES_IN_DRISHTI.md` (tiers, the engine file's new keys, Admin → Engines), `KERNEL_AND_MODULES.md`
(FC-5, FC-6, the call guard, module executors, the run-time `failed` state), `CONFIGURATION.md` (generated), `OPERATIONS_GUIDE.md` (crash
evidence, quarantine, the governor). `ENGINE_INTEGRATION_DESIGN` records FC-1 to FC-6 under the next free EN numbers when accepted.

### 6.2 Kit laws (each a law of 12.4 unless marked host test)

| Law | Derived run | Passes when |
|---|---|---|
| `cancel` | each reference ask cancelled at seeded points; then by interrupt alone | the request ends within the declared cancel latency; the next ask equals the case |
| `overrun` | a deadline shorter than the ask | `TIMED_OUT` within the cancel latency; no thread of the engine still runs it |
| `leak` | 20 cycles of start, session, ask, shutdown in a child JVM | threads, open handles and the heap after collection return to the first cycle's level; the class loader is collected |
| `alloc` | each reference ask | bytes allocated per ask within the case's declared bound; none over the ceiling |
| `budget` (exists, extended) | a 1 MB budget; then a budget equal to `memoryBytes()` | `ENG-5002` in words and the next ask works; no allocation beyond the reservation by more than 8.5 rule 2 allows |
| `binding` (exists) | the other binding, now also with mapped views and kept images | the same bits |
| zero-copy group (exists, extended) | process binding | backing identity is the same file region; poison after `release`; no bulk bytes on the control channel |
| `image` (exists) | process binding; the worker killed while writing an image | no part file is ever found as an image; the second instance finds or builds, answers equal |
| scan (exists) | run by the host class | the verdict (`clean`, `native`, `open`) is in the certificate |
| host tests (`engine-host`, not an engine's law) | fault engines: spin, uninterruptible wait, deadlock, deep recursion, allocate without end, exit, halt, hang in `health()` | each ends in the state section 2 names, and a second engine on the same host answers throughout |

A certificate that lists `cancel`, `overrun`, `leak`, `alloc`, `budget` and a `clean` scan is what "certified for in process" means.

## 7. The measured-cost plan

Defaults are chosen from numbers, by the owner. All runs on this machine follow the standing rules: at most 10,000 positions locally,
one heavy job at a time through `heavy.sh`, the kit's benchmark (12.7) and the R2 speed harness as the instruments, results into the
one `docs/admin/BENCHMARK.md`.

| # | Measure | Compared | Decides |
|---|---|---|---|
| B1 | reference asks, cold and warm, p50 and p99, alone and with 16 callers | today's in-process path (the baseline, taken first) | the reference for every line below |
| B2 | the same after FC-3 (asks on the engine's ask pool) | against B1 | whether the hand-over is free enough to be unconditional (expected: yes) |
| B3 | kept answers (cache hits) | in process against isolated | confirms the hot path is unchanged |
| B4 | computed small answers | in process against isolated `standard` against `strict` | the control channel's cost per answer |
| B5 | vector arithmetic over mapped views (a tree, a path) | in process against isolated | confirms the arithmetic is the same speed; first-touch cost per GB |
| B6 | export of a heap-only input, and session open | in process against isolated, cold worker and warm worker | whether lake inputs need the mapped column cache before isolated can be a default |
| B7 | memory: the kernel's heap, the worker's resident size, page cache shared | one engine in each tier | the per-worker footprint; how many isolated engines a node of a given size carries |
| B8 | recovery: kill the worker under load | time until the first computed answer; other engines' p99 meanwhile | the back-off's first step; proof that a neighbour is unaffected |
| B9 | start-up: the scan of every JAR at discovery (cached by hash on later starts); the native probe | against today's start | whether either needs to be lazy |
| B10 | the watchdog and the allocation counter | asks per second with and without | their overhead (expected below noise) |
| B11 | the worker's JIT warm-up | first 100 asks against the next 1,000 | whether a class-data archive for workers is worth building |

Gates the owner sets after B1: the largest loss allowed for a certified engine in process (proposed 2% at p99 on B2 and B10) and for
an isolated engine on computed answers (proposed: record only, no gate, since isolation is chosen for safety). A shortfall is recorded,
never hidden by a tolerance or a special path.

## 8. Rejected as hacks

1. **`Thread.stop`, bytecode rewriting or an agent to make a thread stoppable or to count an engine's heap.** The first is gone from the
   JDK; the others are a second runtime under the engine that the kit cannot prove.
2. **A security manager or any in-JVM sandbox.** Removed from the platform; class loaders are not a boundary.
3. **A list of engine ids or publishers that decides the tier.** The scan, the certificate and one site setting decide.
4. **Moving an engine between tiers automatically after a failure,** or answering from the in-process path while the worker restarts.
   One engine, one tier, set in its file; a failure is a state in words.
5. **A shared-memory protocol of our own** (ring buffers, semaphores, a segment format). Files, Arrow framing, the standard streams.
6. **A native launcher or helper executable** for the sandbox or for limits. `java` and `python` only.
7. **A fourth worker-process implementation,** or a Java-only supervisor beside the Python one.
8. **A module host process with a remoted kernel API.** Module compute is an engine instead.
9. **A native helper process per BLAS call.**
10. **Raising a timeout, the heap or a limit to make a fault case pass,** or a tolerance in the `binding` law.
11. **Catching every `Throwable` everywhere and carrying on.** Errors are caught at the one engine boundary and at the one module
    guard, and each catch changes a state someone can see.
12. **Copying bulk data into the worker "for now".** If a view cannot be mapped, the export file is the mechanism; a frame never carries it.
13. **Skipping the output gate, the audit or the stamp for the isolated tier "because it is sandboxed".**
14. **An engine-private cache or private mapped file in the worker.** The worker has kept images and its session state, nothing else.

## 9. Build plan

Opus lane; each step at most three hours and ends with its proof. Lane work in `~/IdeaProjects/drishti-lanes/<name>`, builds with the
lane's own Maven repository, heavy jobs through `heavy.sh`, targeted tests only (the drill is the one full run). Steps 1 to 6 need no
new binding and each stands alone. A Fable gate closes each phase.

| # | Step | Repository | Proof |
|---|---|---|---|
| 0 | Baselines B1 and B3 on the current in-process path, recorded | bench | the figures in `BENCHMARK.md`; nothing else may start a default discussion |
| | **Phase 1: in process** | | |
| 1 | Discovery without class loading (3.7); `ENG-1003` at first creation | `drishti-engine` | a test JAR whose static initialiser writes a marker: it is listed with its descriptor and the marker is absent; the existing host tests pass |
| 2 | The scan moves to `engine-host`; the kit uses it; the host runs it at discovery, cached by the JAR's hash; the verdict is shown, nothing is enforced yet | `drishti-engine`, `drishti` | sample JARs give `clean`, `native`, `open`; the kit's results file is byte-identical to before (the stability gate) |
| 3 | FC-3: the ask pool; the explicit ask context replaces the thread-locals | `drishti-engine`, `drishti` | a spinning fault engine: the caller gets `ENG-5011` at the deadline within 100 ms and no server request thread is held; `EngineHostTest` passes; B2 recorded |
| 4 | The watchdog: overdue, stuck, quarantine, `ENG-5026`; interrupt as cancellation | `drishti-engine` | fault engines (spin, uninterruptible wait, deadlock) reach each state with the stack in the log; a second engine answers throughout |
| 5 | The boundary for errors (`ENG-5027`); the health poll per engine with a bound | `drishti-engine` | deep recursion fails one request; allocate-without-end in a child JVM with a small heap quarantines the engine and the host answers on; a hanging `health()` leaves the other engines' polls on time |
| 6 | Memory: budgets as slices (`ENG-1016`), the allocation ceiling, the governor and its order | `drishti`, `drishti-engine` | in a small-heap child JVM the governor sheds the answer cache, then idle sessions, and the fault case ends in `ENG-5002`, not in an out-of-memory error |
| | **Phase 2: the process binding** | | |
| 7 | FC-1: the supervisor in `engine-host` (framed channel with call ids, start, ready, exit watch, kill), from the `python` engine's copy, with a `java` launcher | `drishti-engine`, `drishti-engines` | an echo worker: 1,000 concurrent calls; killed under load, calls in flight are `ENG-7001` and it restarts |
| 8 | `WorkerMain`, `ProcessEngine`, `ProcessSession` for values: lifecycle, session, ask, explain, health and stats | `drishti-engine` | the kit's `binding` law on both sample engines: the same bits |
| 9 | Views across the boundary: region descriptors, the export file, leases with `release` and acknowledgement | `drishti-engine`, `drishti` | the zero-copy group under the process binding; the channel's bulk-byte counter is zero; a read after release is `ENG-5024` |
| 10 | Kept images and large answers across the boundary (incoming folder, the host's move) | `drishti-engine` | the `image` law under the process binding; the worker killed while writing leaves no image; after a restart the build counter stays at zero |
| 11 | Supervision: back-off, the breaker, `ENG-7003`, hot sessions reopened by preparation, the crash evidence | `drishti-engine`, `drishti` | a fault engine that halts its JVM: the host and a second engine are unaffected, it is back within the back-off, three exits make it `failed` in words, the killing request is refused |
| 12 | Limits and the sandbox: heap, direct memory, processors, kill on overrun; `strict` through the guard's entry, then `exec`; `ENG-7004` | `drishti-engine`, `drishti` | a test JAR tries a socket, a write outside its folders and a read of the configuration folder: each refused by the operating system; a memory hog ends its worker and the kernel's heap does not move; on a platform without Landlock, the words |
| 13 | Drishti wiring: `engine.run`, `engine.isolation`, `drishti.engine-lib.uncertified`, the start checks, Admin → Engines (tier, wall, scan verdict, worker, restarts, stuck), generated configuration | `drishti` | the host tests under both bindings; the engine catalogue test; the configuration document's check |
| | **Phase 3: proof, natives, modules, defaults** | | |
| 14 | The kit's containment laws and the certificate's new fields | `drishti-engine` | the sample engines pass; each fault engine fails with its own finding |
| 15 | FC-4: probe before bind for the kernel's maths library; `ENG-1015` | `drishti` | a probe child made to die: the server starts on Java with the words; `BlasResilienceTest` passes |
| 16 | Modules: the audit of the 13 files; module executors in the kernel API; the scan rules in `ModuleConformance`; the call guard and the run-time `failed` state; `drishti.modules.trust` | `drishti` | a fault module: a raw thread is refused at verification; a throwing route answers with the module's code, the module goes `failed`, the kernel and a second module answer on |
| 17 | B2 to B11 run and recorded; the owner chooses the defaults and the gates | bench | `BENCHMARK.md`; the choices written into section 10 |
| 18 | The specification's text (section 20 and the amendments), the Drishti documents of 6.1, a tutorial "a third-party engine, isolated" | docs | the documents' checks; the Fable gate |
| 19 | `python` engine (EN-54) on the supervisor; Drishti's two Python pools retired (ONE_WAY P5) | `drishti-engines`, `drishti` | the Python kit cases recorded from the three pools pass on the one supervisor; the retired classes are gone |

Order and independence: 1, 2, 3 touch disjoint classes and can run as parallel lanes; 4 and 5 follow 3; 6 follows 5. Phase 2 is one lane
in order. 15 and 16 are independent of phase 2. Step 19 is EN-54's own work, listed because it shares step 7.

## 10. Open points for the owner

| # | Question | Recommendation |
|---|---|---|
| O1 | The default of `drishti.engine-lib.uncertified` | `process`, with the developer profile set to `in-process` so that "copy a JAR and it runs" still holds on a laptop; decide after B4 to B7 |
| O2 | An out-of-memory error outside any engine boundary | the node declares itself unhealthy, drains and restarts through its launcher (crash-only for the kernel), instead of carrying on with unknown state |
| O3 | Native code in a first-party engine JAR (if Drishti Risk adds a binding of its own) | use the kernel's maths library in process; a private binding means the isolated tier |
| O4 | Uncertified engines on Windows, where only `standard` exists | allowed only when the engine file names `isolation: standard` explicitly; Admin shows "faults contained, access not" |
| O5 | The proposed gates (2% at p99 for certified engines in process) | set after B1 |
