/*
 * Project Drishti · Any data. Any domain. One grammar.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
package com.ash.drishti.enginehost;

import com.ash.engine.Answer;
import com.ash.engine.Descriptor;
import com.ash.engine.Engine;
import com.ash.engine.EngineException;
import com.ash.engine.EngineException.Kind;
import com.ash.engine.EngineStats;
import com.ash.engine.Filter;
import com.ash.engine.Health;
import com.ash.engine.LifecycleState;
import com.ash.engine.Request;
import com.ash.engine.RequestClass;
import com.ash.engine.Snapshot;
import com.ash.engine.Visibility;
import com.ash.engine.host.Admission;
import com.ash.engine.host.AnswerCache;
import com.ash.engine.host.KeptImages;
import com.ash.engine.host.Budget;
import com.ash.engine.host.EngineFile;
import com.ash.engine.host.EngineInstance;
import com.ash.engine.host.EngineJar;
import com.ash.engine.host.EngineLib;
import com.ash.engine.host.HostContext;
import com.ash.engine.host.HostControl;
import com.ash.engine.host.Params;
import com.ash.engine.host.Problem;
import com.ash.engine.host.Sessions;
import com.ash.engine.host.StartChecks;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One engine id on this node: its JAR's registration, its engine file, the start checks' verdict, the binding of its inputs, and (when it
 * may run) its instance with its lifecycle (init, start, suspend, resume, shutdown; engine specification 7.1), its sessions per snapshot and
 * per viewer (7.2, 8.6 rule 7), its admission, budget and work folder. {@link #ask} is the one way a request reaches the engine: access,
 * visibility, parameters, snapshot, session, control and the output gate are the host's, every time.
 */
public final class HostedEngine implements AutoCloseable {

    /** Guards the blocking work below (a ReentrantLock: synchronized would pin a virtual thread). */
    private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();

    /** A request as the host takes it: outputs, parameters, snapshot dimensions, the class, and whether its tables go to live and stored outputs. */
    public record Ask(List<String> outputs, Map<String, Object> params, Map<String, String> snapshot, RequestClass requestClass, boolean publish) {

        public Ask {
            outputs = List.copyOf(outputs == null ? List.of() : outputs);
            params = params == null ? Map.of() : params;
            snapshot = snapshot == null ? Map.of() : snapshot;
            requestClass = requestClass == null ? RequestClass.INTERACTIVE : requestClass;
        }
    }

    /** What the host writes to the audit log: one line per ask, transition and refusal. */
    @FunctionalInterface
    public interface Auditor {
        void record(String actor, String action, String target, String details);
    }

    private final String id;
    private final EngineLib.Entry entry;
    private final EngineFile file;
    private final String fileProblem;
    private final EngineHostSettings settings;
    private final KindData data;
    private final WhereCompiler wheres;
    private final Outputs outputs;
    private final Auditor audit;
    private final InputBinder.Binding binding;
    private final List<Problem> problems = new ArrayList<>();
    private final Map<String, RowRule> compiledWheres = new LinkedHashMap<>();
    private final AtomicLong hostRefused = new AtomicLong();
    private final AtomicLong rowsIn = new AtomicLong();
    private final Map<String, ViewerAccess> viewers = new ConcurrentHashMap<>();
    private final Map<Sessions.Key, LakeInputs> inputsByKey = new ConcurrentHashMap<>();
    private volatile String isolation = "per-viewer";
    private volatile EngineInstance instance;
    private volatile HostContext context;
    private volatile Sessions sessions;
    /** The engine's kept images (ENGINE_SPEC 18 rule 8): {@code <data>/engines/<id>/kept}, mapped through the kernel's FileMaps. */
    private volatile KeptImages kept;
    /**
     * The host's answer cache (engine specification 13 rule 6), one for every engine of the host (null: off): a final answer kept by its
     * stamp (engine version, descriptor, options, the input stamps of the session that answered, the request with its visibility
     * signature), so a kept answer is served only to the same question, from the same viewer's rows, over the same data. This engine's
     * entries leave when an instance starts or shuts down (an options change is a new instance), when their session closes or goes stale.
     */
    private final AnswerCache answers;
    private final AtomicLong answerHits = new AtomicLong();
    private final AtomicLong answerMisses = new AtomicLong();
    /** The session key of the request on this thread: the input stamps a stamp names are that session's own (never another viewer's). */
    private static final ThreadLocal<Sessions.Key> ASKING = new ThreadLocal<>();
    /** A snapshot being prepared (a {@code prepare} ask running): since when, its control (the engine's stage words) and its end. */
    private record Preparing(Instant since, java.util.concurrent.atomic.AtomicReference<HostControl> control,
            java.util.concurrent.CompletableFuture<Void> done) {}

    /** The snapshots being prepared now: an interactive ask of one waits at most the open wait, then is told "preparing" (7.2 rule 5). */
    private final Map<Snapshot, Preparing> preparing = new ConcurrentHashMap<>();
    /** The preparation this thread runs (its own ask never waits for itself). */
    private static final ThreadLocal<Preparing> PREPARER = new ThreadLocal<>();

    /** The session being asked on this thread: its own inputs stamp the answer (a stale session and its successor share a key). */
    private static final ThreadLocal<com.ash.engine.Session> ASKING_SESSION = new ThreadLocal<>();
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(HostedEngine.class);
    /** How many of the instance's log lines are already in the server's log. */
    private final java.util.concurrent.atomic.AtomicInteger logged = new java.util.concurrent.atomic.AtomicInteger();
    private volatile Admission admission;
    private volatile ExecutorService executor;
    private volatile String by = "";
    private volatile Instant since = Instant.now();

    HostedEngine(String id, EngineLib.Entry entry, EngineFile file, String fileProblem, EngineHostSettings settings, KindData data,
            KindContracts contracts, WhereCompiler wheres, Outputs outputs, Auditor audit, Set<String> visibilityRules, AnswerCache answers) {
        this.id = id;
        this.answers = answers;
        this.entry = entry;
        this.file = file;
        this.fileProblem = fileProblem;
        this.settings = settings;
        this.data = data;
        this.wheres = wheres;
        this.outputs = outputs;
        this.audit = audit;
        Descriptor d = descriptor();
        if (fileProblem != null) {
            problems.add(new Problem("ENG-1010", fileProblem));
        }
        if (d != null && file != null && entry.jar() != null && entry.state() != EngineLib.State.BLOCKED) {
            StartChecks.Verdict v = StartChecks.check(entry.jar(), entry.engine(), file, settings.trust(), settings.kitMajor(), FileLicence.read(settings.licenceFile()));
            problems.addAll(v.problems());
            isolation = v.isolation() == null ? "per-viewer" : v.isolation();
            if (!visibilityRules.contains(file.access().visibility())) {
                problems.add(new Problem("ENG-1010", id + ": access.visibility names " + file.access().visibility() + ", which is not a rule of this host; "
                        + "its rules are " + new java.util.TreeSet<>(visibilityRules)));
            }
            this.binding = InputBinder.bind(d, file, contracts, data::serves);
            problems.addAll(binding.problems());
            file.inputs().forEach((name, b) -> {
                if (b.where() != null && !b.where().isBlank()) {
                    try {
                        compiledWheres.put(name, wheres.compile(b.where()));
                    } catch (IllegalArgumentException e) {
                        problems.add(new Problem("ENG-1010", id + ": inputs." + name + ".where: " + e.getMessage()));
                    }
                }
            });
        } else {
            this.binding = new InputBinder.Binding(Map.of(), List.of());
        }
    }

    // ------------------------------------------------------------------------------------------------------------- what it is

    public String id() {
        return id;
    }

    /** The descriptor, or null when no JAR registers the id (not installed) or it could not be read. */
    public Descriptor descriptor() {
        return entry.engine() == null ? null : entry.engine().descriptor();
    }

    public EngineLib.Entry entry() {
        return entry;
    }

    /** The engine file, or null when there is none (not configured) or it is not valid ({@link #problems}). */
    public EngineFile file() {
        return file;
    }

    public InputBinder.Binding binding() {
        return binding;
    }

    /** What blocks the engine besides the lib folder's own state (the file, the start checks, the binding); empty: it may start. */
    public List<Problem> problems() {
        return List.copyOf(problems);
    }

    /** True when the engine may run here: installed, configured, every check passed. */
    public boolean startable() {
        return (entry.state() == EngineLib.State.RUNNING || entry.state() == EngineLib.State.OFF) && file != null && problems.isEmpty();
    }

    /** The engine file's switch. */
    public boolean enabled() {
        return file != null && file.enabled();
    }

    /** The state in Admin's words: running, suspended, off, failed, shut down, not configured, not installed, blocked. */
    public String state() {
        if (entry.state() == EngineLib.State.NOT_INSTALLED || entry.state() == EngineLib.State.NOT_CONFIGURED) {
            return entry.state().words();
        }
        if (entry.state() == EngineLib.State.BLOCKED || !problems.isEmpty()) {
            return "blocked";
        }
        EngineInstance i = instance;
        if (i == null) {
            return "off";
        }
        return switch (i.state()) {
            case RUNNING -> "running";
            case SUSPENDED -> "suspended";
            case FAILED -> "failed";
            case SHUTDOWN -> "off";
            case NEW, INITIALISED -> "starting";
        };
    }

    /** Why it is in its state, in words (empty for running). */
    public String words() {
        if (entry.state() != EngineLib.State.RUNNING && entry.state() != EngineLib.State.OFF) {
            return entry.words();
        }
        if (!problems.isEmpty()) {
            return problems.get(0).words();
        }
        EngineInstance i = instance;
        if (i == null) {
            return id + " is switched off";
        }
        if (i.state() == LifecycleState.SUSPENDED) {
            return i.suspendedBecause();
        }
        return i.state() == LifecycleState.FAILED ? i.failure() : "";
    }

    /** The code of the state's words ({@code ENG-1014}, {@code ENG-5034}), or null. */
    public String code() {
        if (entry.state() != EngineLib.State.RUNNING && entry.state() != EngineLib.State.OFF) {
            return entry.code();
        }
        return problems.isEmpty() ? null : problems.get(0).code();
    }

    public LifecycleState lifecycle() {
        EngineInstance i = instance;
        return i == null ? LifecycleState.SHUTDOWN : i.state();
    }

    /** The instance's health (the host's correction of the engine's own verdict), or DOWN with the state's words when there is none. */
    public Health health() {
        EngineInstance i = instance;
        if (i == null) {
            String w = words();
            return Health.down(Instant.now(), w == null || w.isBlank() ? state() : w);
        }
        return i.health();
    }

    /** The engine's counters, or empty when there is no instance. */
    public EngineStats stats() {
        EngineInstance i = instance;
        return i == null ? EngineStats.empty(Instant.now()) : i.stats();
    }

    /** Requests the host refused before the engine (suspended, not permitted, over the admission limit): the host's own counter (7.5 rule 2). */
    public long refusedByHost() {
        return hostRefused.get();
    }

    public long rowsDelivered() {
        return rowsIn.get();
    }

    public long memoryBytes() {
        Sessions s = sessions;
        return s == null ? 0 : s.memoryBytes();
    }

    /** The engine's own log lines (its context's log) into the server's log, each once, as they are written. */
    private void forwardLog() {
        HostContext c = context;
        if (c == null) {
            return;
        }
        int from = logged.get();
        List<String> lines = c.logLines(from);                         // only what is new since the last look
        for (int i = 0; i < lines.size(); i++) {
            if (logged.compareAndSet(from + i, from + i + 1)) {
                LOG.info("engine {}: {}", id, lines.get(i));
            }
        }
    }

    /** This engine's part of the answer cache for Admin, Caches: entries, bytes, hits and misses since the start (zeros when off). */
    public long[] answerCache() {
        long[] mine = answers == null ? new long[2] : answers.of(id);
        return new long[] {mine[0], mine[1], answerHits.get(), answerMisses.get()};
    }

    /** Drops this engine's kept answers ({@code date} null: all; else those of snapshots of that business date); the next asks run again. */
    public void purgeAnswers(LocalDate date) {
        if (answers != null) {
            String d = date == null ? null : ",date=" + date + ",";
            answers.removeIf(k -> k.engine().equals(id) && (d == null || ("," + k.snapshot() + ",").contains(d)));
        }
    }

    /** Drops this engine's kept answers of one session's snapshot (closed, gone stale, retired). */
    private void dropAnswers(Snapshot snapshot) {
        if (answers != null) {
            String text = snapshot.text();
            answers.removeIf(k -> k.engine().equals(id) && k.snapshot().equals(text));
        }
    }

    /**
     * Data of {@code kind} was written by this server (a capture's publish: the lake's commit): when this engine binds the kind, the
     * data-version look runs now instead of at its next poll (ENGINE_SPEC 9.3 rule 5: the one way the host learns that inputs changed). A
     * session whose inputs' versions moved is stale and answers, noted, until its successor is ready; the kept answers need no purge (a new
     * data version is a new stamp). True when it binds the kind.
     */
    public boolean inputsChanged(String kind) {
        if (binding.inputs().values().stream().noneMatch(b -> b.kinds().contains(kind))) {
            return false;
        }
        lookAtDataVersions();
        return true;
    }

    public int sessionsOpen() {
        Sessions s = sessions;
        return s == null ? 0 : s.keys().size();
    }

    public String isolation() {
        return isolation;
    }

    EngineInstance instance() {
        return instance;
    }

    /** The class loader of the engine's JAR (its own, under the JDK; the platform packages from the server), or null when not installed. */
    public ClassLoader engineClassLoader() {
        return entry.engine() == null || entry.engine().type() == null ? null : entry.engine().type().getClassLoader();
    }

    // ------------------------------------------------------------------------------------------------------------- the lifecycle

    /** A new instance, initialised (NEW to INITIALISED); refused when the engine may not run or an instance is live. */
    public void init(String actor) throws EngineException {
        lock.lock();
        try {
            if (!startable()) {
                throw new EngineException(code() == null ? "ENG-5033" : code(), Kind.REFUSED, id + " cannot start: " + words());
            }
            EngineInstance now = instance;
            if (now != null && now.state() != LifecycleState.SHUTDOWN) {
                throw new EngineException("ENG-5020", Kind.REFUSED, id + " is " + now.state() + "; init needs no live instance (shut it down first)");
            }
            Descriptor d = descriptor();
            EngineJar jar = entry.jar();
            Engine engine;
            try {
                engine = jar.create(entry.engine());
            } catch (ReflectiveOperationException | LinkageError e) {
                throw new EngineException("ENG-1009", Kind.FAILED, id + " could not be made from " + jar.path().getFileName() + ": " + e, Map.of(), e);
            }
            SortedMap<String, Object> options = Params.options(d, file.options());
            Path work = settings.workRoot().resolve(id);
            try {
                Files.createDirectories(work);
            } catch (java.io.IOException e) {
                throw new EngineException("ENG-1009", Kind.FAILED, id + ": its work folder " + work + " cannot be made: " + e.getMessage(), Map.of(), e);
            }
            int threads = file.limits().threads() > 0 ? file.limits().threads() : Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
            ExecutorService ex = Executors.newFixedThreadPool(threads, Thread.ofPlatform().daemon(true).name("drishti-engine-" + id + "-", 0).factory());
            long budget = file.limits().memoryMb() > 0 ? file.limits().memoryMb() << 20 : Runtime.getRuntime().maxMemory() / 4;
            HostContext ctx = new HostContext(d, options, work, Clock.systemUTC(), ex, new Budget(budget), Map.of(), null, this::inputStamps);
            EngineInstance inst = new EngineInstance(id, engine, jar.loader());
            executor = ex;
            context = ctx;
            admission = new Admission(file.limits().concurrent());
            instance = inst;
            sessions = new Sessions(inst, file.snapshot().hot(), this::inputsFor, this::sessionClosed);
            Sessions pool = sessions;
            kept = new KeptImages(settings.workRoot().getParent().resolve(id).resolve("kept"), d.id(), d.version(), ctx.descriptorHash(),
                    com.ash.engine.host.Stamping.options(options), session -> keyed(pool, session), HostedEngine::map,
                    (long) file.limits().diskGb() << 30);
            KeptImages images = kept;
            pool.onClose(images::release);                              // a closed session's leases end with it
            ctx.kept(images);
            logged.set(0);
            purgeAnswers(null);                                          // a new instance (a start, an options change) answers afresh
            transition(actor, "init", () -> inst.init(ctx));

        } finally {
            lock.unlock();
        }
    }

    /** INITIALISED to RUNNING; from nothing (or after a shutdown) a new instance is made first. */
    public void start(String actor) throws EngineException {
        lock.lock();
        try {
            EngineInstance now = instance;
            if (now == null || now.state() == LifecycleState.SHUTDOWN) {
                init(actor);
                now = instance;
            }
            EngineInstance i = now;
            transition(actor, "start", i::start);
            prepareOn("start", null);                                   // 9.3: on start, the newest snapshot prepared before any user asks

        } finally {
            lock.unlock();
        }
    }

    /** RUNNING to SUSPENDED: new requests refused at once (ENG-5023), those in flight drain within the cancel latency; state and sessions kept. */
    public void suspend(String actor, String why) throws EngineException {
        EngineInstance i = require("suspend");
        Duration grace = descriptor().limits() == null || descriptor().limits().cancelLatency() == null ? Duration.ofSeconds(1) : descriptor().limits().cancelLatency();
        String reason = (why == null || why.isBlank() ? "suspended" : why) + (actor == null || actor.isBlank() ? "" : " (by " + actor + " at " + Instant.now() + ")");
        transition(actor, "suspend", () -> i.suspend(reason, grace, null));
    }

    /** SUSPENDED to RUNNING: nothing reloaded, the same answers as before. */
    public void resume(String actor) throws EngineException {
        EngineInstance i = require("resume");
        transition(actor, "resume", i::resume);
    }

    /** Sessions closed, then the instance shut down and its threads released; harmless twice. The live outputs are forgotten. */
    public void shutdown(String actor) {
        lock.lock();
        try {
            EngineInstance i = instance;
            if (i == null || i.state() == LifecycleState.SHUTDOWN) {
                return;
            }
            Sessions s = sessions;
            if (s != null) {
                s.close();
            }
            KeptImages k = kept;
            if (k != null) {
                k.close();                                              // unmapped; the files stay for the next start to find
            }
            inputsByKey.clear();
            viewers.clear();
            purgeAnswers(null);
            try {
                transition(actor, "shutdown", () -> i.shutdown());
            } catch (EngineException e) {
                // shutdown never throws; recorded by transition
            }
            ExecutorService ex = executor;
            if (ex != null) {
                ex.shutdownNow();
            }
            outputs.forget(id);

        } finally {
            lock.unlock();
        }
    }

    private EngineInstance require(String what) throws EngineException {
        EngineInstance i = instance;
        if (i == null) {
            throw new EngineException("ENG-5020", Kind.REFUSED, id + " has no instance; " + what + " needs one (start it first)");
        }
        return i;
    }

    @FunctionalInterface
    private interface Step {
        void run() throws EngineException;
    }

    private void transition(String actor, String what, Step step) throws EngineException {
        LifecycleState from = instance == null ? LifecycleState.NEW : instance.state();
        try {
            step.run();
            by = actor == null ? "" : actor;
            since = Instant.now();
            if (actor != null && !actor.isBlank() && audit != null) {
                audit.record(actor, "engine-" + what, "engine/" + id, from + " -> " + lifecycle());
            }
        } catch (EngineException e) {
            if (audit != null) {
                audit.record(actor == null ? "" : actor, "engine-" + what + ("ENG-5020".equals(e.code()) ? "-refused" : "-failed"), "engine/" + id,
                        from + ": " + e.code() + " " + e.getMessage());
            }
            throw e;
        }
    }

    /** Who made the last transition and when. */
    public Map<String, Object> since() {
        return Map.of("by", by, "at", since.toString());
    }

    // ------------------------------------------------------------------------------------------------------------- asking

    /**
     * One request as {@code viewer}: the engine must be running here; the caller may open it and each output asked; the visibility is the
     * host's; the parameters are normalised; the session is the snapshot's (and the caller's, per viewer); the answer passes the output gate.
     */
    public Answer ask(Ask ask, ViewerAccess viewer) throws EngineException {
        return answer(ask, viewer).answer();
    }

    /**
     * An answer as the caller may see it, with its entry in the host's answer cache ({@code kept}, null when the answer is not kept: the
     * cache off, a published ask, an interim answer): what a reader derives from the answer is kept there ({@link Answered#keep}), so it
     * is reachable only through the same stamp (the same question, rows and data) and leaves with the answer.
     */
    public record Answered(Answer answer, AnswerCache.Kept kept, AnswerCache cache, String gate) {

        /**
         * Keeps a value derived from the answer under {@code name} and the output gate's masks ({@code gate}: a shape made through one
         * viewer's masks is never another's), counted as {@code bytes}; returns the value kept first for that name.
         */
        public Object keep(String name, Object value, long bytes) {
            if (kept == null) {
                return value;
            }
            Object had = kept.shaped().putIfAbsent(gate + "|" + name, value);
            if (had != null) {
                return had;
            }
            cache.grow(kept, bytes);
            return value;
        }

        /** A value kept under {@code name}, or null. */
        public Object kept(String name) {
            return kept == null ? null : kept.shaped().get(gate + "|" + name);
        }
    }

    /** As {@link #ask}, with the answer's entry in the host's answer cache. */
    public Answered answer(Ask ask, ViewerAccess viewer) throws EngineException {
        try {
            return doAsk(ask, viewer);
        } catch (EngineException e) {
            if (Set.of("ENG-5023", "ENG-5020", "ENG-5030", "ENG-5031", "ENG-5032", "ENG-5033", "ENG-5034", "ENG-4001", "ENG-4003", "ENG-5001")
                    .contains(e.code())) {
                hostRefused.incrementAndGet();
            }
            if (audit != null && viewer != null) {
                audit.record(viewer.actor(), "engine-ask-refused", "engine/" + id, ask.outputs() + ": " + e.code() + " " + e.getMessage());
            }
            throw e;
        }
    }

    private Answered doAsk(Ask ask, ViewerAccess viewer) throws EngineException {
        EngineInstance i = instance;
        if (i == null) {
            throw unavailable();
        }
        if (viewer == null) {
            throw new EngineException("ENG-4001", Kind.NOT_PERMITTED, "a request to " + id + " with no caller is refused");
        }
        Descriptor d = descriptor();
        if (!opens(viewer, file.access().opens())) {
            throw new EngineException("ENG-4003", Kind.NOT_PERMITTED, viewer.actor() + " may not open " + id);
        }
        for (String o : ask.outputs()) {
            EngineFile.OutputUse use = file.outputs().get(o);
            if (use != null && !use.enabled()) {
                throw new EngineException("ENG-5031", Kind.UNAVAILABLE, id + "." + o + " is switched off in config/engines/" + id + ".yaml");
            }
            Object opens = use == null ? null : use.access().get("opens");
            if (opens instanceof List<?> l && !opens(viewer, l.stream().map(String::valueOf).toList())) {
                throw new EngineException("ENG-4003", Kind.NOT_PERMITTED, viewer.actor() + " may not ask " + id + "." + o);
            }
        }
        i.admit();
        SortedMap<String, Object> params = Params.request(d, ask.outputs(), ask.params());
        Snapshot snapshot = snapshot(d, ask.snapshot());
        boolean perViewer = !"shared".equals(isolation);
        String signature = perViewer ? signature(viewer) : null;
        Visibility visibility = visibility(d, viewer, perViewer, signature);
        Request unhashed = new Request(UUID.randomUUID().toString(), ask.outputs(), params, visibility, viewer.actor(), 0L, ask.requestClass());
        // hashed once: the cache's look-up and the engine's own stamp (Stamps.of) both read the hash the request carries
        Request request = unhashed.withHash(snapshot, com.ash.engine.host.Stamping.request(snapshot, unhashed));
        Sessions.Key key = new Sessions.Key(snapshot, signature);
        if (perViewer) {
            viewers.put(signature, viewer);
        }
        Duration timeout = Duration.ofSeconds(Math.max(1, file.limits().requestSeconds()));
        HostControl control = new HostControl(Instant.now().plus(timeout), Clock.systemUTC(), false);
        Preparing mine = PREPARER.get();
        if (mine != null) {
            mine.control().set(control);                                // the stage the engine names while it prepares, for those who wait
        } else if (!sessions.servesStale(key)) {
            awaitPrepared(snapshot, ask.requestClass());                // no stale version answers meanwhile: wait (briefly) for the preparation
        }
        Answer answer;
        AnswerCache.Kept entry = null;
        try (Admission.Permit permit = admission.admit(ask.requestClass(), id); Sessions.Lease lease = sessions.lease(key, control, openWait(ask.requestClass()), mine != null)) {
            LakeInputs.CURRENT.set(control);
            ASKING.set(key);
            ASKING_SESSION.set(lease.session());
            try {
                AnswerCache cache = ask.publish() ? null : answers;     // a published ask always runs (its tables are written)
                // the stamp after the lease: the session is open, so the stamp names the input versions it holds
                String stamp = cache == null ? null : context.stamp(snapshot, request).text();
                entry = stamp == null ? null : cache.get(stamp);
                Flight shared = entry != null || stamp == null ? null : joinFlight(stamp, control);
                if (entry != null) {
                    answerHits.incrementAndGet();
                    answer = entry.answer();
                } else if (shared != null) {                            // another caller computed this very stamp meanwhile
                    answerHits.incrementAndGet();
                    entry = shared.kept();
                    answer = shared.answer();
                } else {
                    // single flight: one caller of a stamp computes it, the others asking it meanwhile wait for its answer (joinFlight)
                    java.util.concurrent.CompletableFuture<Flight> flight = stamp == null ? null : new java.util.concurrent.CompletableFuture<>();
                    boolean owner = flight != null && inFlight.putIfAbsent(stamp, flight) == null;
                    Flight done = null;
                    try {
                        answer = i.request(() -> lease.session().ask(request, control));
                        forwardLog();
                        if (cache != null) {
                            answerMisses.incrementAndGet();
                            entry = cache.put(answer, id, snapshot.text());   // under its own stamp; an interim answer is never kept
                            done = answer.status() == com.ash.engine.Status.INTERIM ? null : new Flight(answer, entry);
                        }
                    } finally {
                        if (owner) {
                            inFlight.remove(stamp, flight);
                            flight.complete(done);                        // null (failed, interim): a waiter asks itself
                        }
                    }
                }
                if (lease.note() != null) {                             // a stale session answering while its successor opens (7.2 rule 4)
                    List<String> notes = new ArrayList<>(answer.notes());
                    notes.add(lease.note());
                    answer = new Answer(answer.status(), answer.tables(), answer.missing(), answer.stamp(), notes);
                }
            } finally {
                ASKING.remove();
                ASKING_SESSION.remove();
                LakeInputs.CURRENT.remove();
            }
        }
        Answer gated = OutputGate.apply(d, answer, viewer, binding);   // on every answer, kept or not
        if (ask.publish()) {
            List<String> notes = outputs.publish(id, file, snapshot, gated);
            if (!notes.isEmpty()) {
                List<String> all = new ArrayList<>(gated.notes());
                all.addAll(notes);
                gated = new Answer(gated.status(), gated.tables(), gated.missing(), gated.stamp(), all);
            }
        }
        if (audit != null) {
            audit.record(viewer.actor(), "engine-ask", "engine/" + id, ask.outputs() + " " + auditParams(params) + " " + snapshot.text() + " -> "
                    + gated.status() + (gated.stamp() == null ? "" : " stamp " + gated.stamp().request()));
        }
        return new Answered(gated, entry, answers, OutputGate.key(gated));
    }

    /** An answer one caller computed for a stamp, handed to the callers that asked the same stamp meanwhile (with its cache entry, if kept). */
    private record Flight(Answer answer, AnswerCache.Kept kept) {}

    /** The stamps being computed now (single flight: the 100,000-position index is computed once, not once per caller asking at once). */
    private final java.util.concurrent.ConcurrentMap<String, java.util.concurrent.CompletableFuture<Flight>> inFlight =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The answer of a stamp another caller is computing now, waited for within this ask's own deadline; null when none is in flight or it
     * ended without a final answer (failed, interim: this caller then asks itself). A waiter whose deadline passes is refused as any ask
     * past its deadline ({@code ENG-5011}), never starting the computation again late; an interrupted one {@code ENG-5010}.
     */
    private Flight joinFlight(String stamp, HostControl control) throws EngineException {
        java.util.concurrent.CompletableFuture<Flight> f = inFlight.get(stamp);
        if (f == null) {
            return null;
        }
        try {
            if (control.deadline().equals(Instant.MAX)) {
                return f.get();
            }
            long left = Math.max(1, Duration.between(Instant.now(), control.deadline()).toMillis());
            return f.get(left, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EngineException("ENG-5010", Kind.CANCELLED, "interrupted while waiting for the same question asked by another caller");
        } catch (java.util.concurrent.ExecutionException e) {
            return null;
        } catch (java.util.concurrent.TimeoutException e) {
            control.check();                                            // past the deadline: ENG-5011, as any ask
            throw new EngineException("ENG-5011", Kind.TIMED_OUT, "the deadline passed while the same question was computed for another caller");
        }
    }

    /** The longest list an audit line writes out item by item (specification 3.2); a longer one is named by its count. */
    static final int AUDIT_LIST_ITEMS = 32;

    /**
     * The parameters as the audit line names them: a list longer than {@link #AUDIT_LIST_ITEMS} items and a table by their size, never
     * their values (the line's request hash, its {@code stamp}, is their digest): an audit line of a scope of 200,000 rows stays short.
     */
    static String auditParams(SortedMap<String, Object> params) {
        StringBuilder b = new StringBuilder("{");
        params.forEach((k, v) -> {
            b.append(b.length() == 1 ? "" : ", ").append(k).append('=');
            if (v instanceof java.util.Collection<?> c && c.size() > AUDIT_LIST_ITEMS) {
                b.append("list:").append(c.size()).append(" items");
            } else if (v instanceof com.ash.engine.Table t) {
                b.append("table:").append(t.rows()).append(" rows");
            } else {
                b.append(v);
            }
        });
        return b.append('}').toString();
    }

    /** A session closed (evicted, retired, the instance shut down): its inputs and its kept answers go with it. */
    private void sessionClosed(Sessions.Key key) {
        inputsByKey.remove(key);
        if (sessions == null || sessions.keys().stream().noneMatch(k -> k.snapshot().equals(key.snapshot()))) {
            dropAnswers(key.snapshot());
        }
    }

    private EngineException unavailable() {
        String c = code();
        String st = state();
        String codeNow = c != null ? c : switch (st) {
            case "not installed" -> "ENG-5030";
            case "off" -> "ENG-5031";
            case "not configured" -> "ENG-5034";
            default -> "ENG-5033";
        };
        return new EngineException(codeNow, Kind.UNAVAILABLE, id + " is " + st + (words().isBlank() ? "" : ": " + words()));
    }

    /** True when the list is empty (everyone who may reach the server), or the caller is an administrator, holds a named role or opens a named pack. */
    private static boolean opens(ViewerAccess viewer, List<String> opens) {
        if (opens == null || opens.isEmpty() || viewer.admin()) {
            return true;
        }
        for (String o : opens) {
            if (o.startsWith("role:") && viewer.roles().contains(o.substring(5))) {
                return true;
            }
            if (o.startsWith("pack:") && viewer.mayOpenPack(o.substring(5))) {
                return true;
            }
        }
        return false;
    }

    /** The snapshot asked, with a missing {@code date} dimension filled with the newest date of the first bound input. */
    private Snapshot snapshot(Descriptor d, Map<String, String> asked) throws EngineException {
        Map<String, String> dims = new TreeMap<>();
        for (Descriptor.Dimension dim : d.snapshot()) {
            String v = asked.get(dim.name());
            if (v == null && "date".equals(dim.type())) {
                v = binding.inputs().values().stream().flatMap(b -> b.kinds().stream()).map(data::latest).flatMap(Optional::stream).findFirst()
                        .map(LocalDate::toString).orElse(LocalDate.now(java.time.ZoneOffset.UTC).toString());
            }
            if (v == null) {
                throw new EngineException("ENG-3002", Kind.REFUSED, id + " needs the snapshot dimension " + dim.name());
            }
            if ("date".equals(dim.type())) {
                try {
                    LocalDate.parse(v);
                } catch (java.time.format.DateTimeParseException e) {
                    throw new EngineException("ENG-3002", Kind.REFUSED, "the snapshot date " + v + " is not a date (yyyy-MM-dd)");
                }
            }
            dims.put(dim.name(), v);
        }
        for (String k : asked.keySet()) {
            if (d.snapshot().stream().noneMatch(x -> x.name().equals(k))) {
                throw new EngineException("ENG-3002", Kind.REFUSED, k + " is not a snapshot dimension of " + id + "; it has "
                        + d.snapshot().stream().map(Descriptor.Dimension::name).toList());
            }
        }
        return Snapshot.of(dims);
    }

    /** Equal for two callers exactly when they read the same rows and see the same fields masked (a session per signature, 7.2 rule 3). */
    private String signature(ViewerAccess viewer) {
        StringBuilder b = new StringBuilder();
        Descriptor d = descriptor();
        binding.inputs().forEach((name, bound) -> {
            b.append(name).append('{');
            for (String kind : bound.kinds()) {
                b.append(kind).append(viewer.mayOpen(kind) ? ":open:" : ":closed:").append(viewer.rows(kind).text()).append(';');
            }
            Descriptor.Input in = d.inputs().get(name);
            for (String c : in.columns()) {
                if (viewer.masked(bound.column(c)) || viewer.masked(c)) {
                    b.append("mask:").append(c).append(';');
                }
            }
            b.append('}');
        });
        return com.ash.tools.codec.Hashes.hex(com.ash.tools.codec.Hashes.of(com.ash.tools.codec.Hashes.SHA_256, b.toString())).substring(0, 32);
    }

    /**
     * The request's visibility (8.6): per viewer, every input with scope columns is {@code Filter.ALL} (the rows are already the caller's);
     * shared, each such input's filter is the caller's row rule made a filter, and a rule that cannot be one refuses the request.
     */
    private Visibility visibility(Descriptor d, ViewerAccess viewer, boolean perViewer, String signature) throws EngineException {
        Map<String, Filter> rows = new TreeMap<>();
        Set<String> masked = new java.util.TreeSet<>();
        for (Map.Entry<String, Descriptor.Input> e : d.inputs().entrySet()) {
            InputBinder.Bound b = binding.inputs().get(e.getKey());
            if (e.getValue().scopeColumns().isEmpty()) {
                continue;
            }
            if (perViewer || b == null || b.kinds().isEmpty() || e.getValue().open()) {
                rows.put(e.getKey(), Filter.ALL);
                continue;
            }
            List<Filter> any = new ArrayList<>();
            for (String kind : b.kinds()) {
                Optional<Filter> f = viewer.rows(kind).asFilter();
                if (f.isEmpty()) {
                    throw new EngineException("ENG-4001", Kind.NOT_PERMITTED, id + " runs shared, and " + viewer.actor() + "'s row rule on " + kind
                            + " cannot be given to an engine as a filter; run it per-viewer (access.isolation) to serve this caller");
                }
                any.add(f.get());
            }
            rows.put(e.getKey(), any.size() == 1 ? any.get(0) : new Filter.Or(any));
        }
        for (Descriptor.Output o : d.outputs().values()) {
            for (Descriptor.Column c : o.columns()) {
                if (c.passes() != null && c.passes().indexOf('.') > 0) {
                    String in = c.passes().substring(0, c.passes().indexOf('.'));
                    String col = c.passes().substring(c.passes().indexOf('.') + 1);
                    InputBinder.Bound b = binding.inputs().get(in);
                    if (viewer.masked(b == null ? col : b.column(col)) || viewer.masked(col)) {
                        masked.add(c.name());
                    }
                }
            }
        }
        return new Visibility(perViewer ? signature : "shared:" + signature(viewer), rows, masked);
    }

    /** The key parts of a session this host opened: its snapshot, its inputs' stamps and its visibility ({@code all}: shared). */
    private KeptImages.Keyed keyed(Sessions pool, com.ash.engine.Session session) {
        Sessions.Key key = pool.keyOf(session);
        LakeInputs in = pool.inputsOf(session) instanceof LakeInputs l ? l : null;
        return in == null ? null : new KeptImages.Keyed(key.snapshot().text(), in.stamps(), key.signature() == null ? "all" : key.signature());
    }

    /** A region of a kept image mapped by the kernel's FileMaps (released explicitly when no session leases the image). */
    private static KeptImages.Region map(java.nio.channels.FileChannel ch, long offset, long length) throws java.io.IOException {
        com.ash.drishti.api.FileMaps.Mapping m = com.ash.drishti.api.FileMaps.map(ch, offset, length);
        return new KeptImages.Region(m.buffer(), m::release);
    }

    /** The kept images for Admin, Caches ({@code engines.kept}); empty when the engine has no instance. */
    public java.util.List<KeptImages.Listed> keptImages() {
        KeptImages k = kept;
        return k == null ? java.util.List.of() : k.list();
    }

    /** Releases and deletes the kept images no session leases (Admin, Caches: purge); how many. */
    public int purgeKept() {
        KeptImages k = kept;
        return k == null ? 0 : k.purge();
    }

    /** The inputs of one session: its snapshot's date, its viewer (null: shared). */
    private LakeInputs inputsFor(Sessions.Key key) {
        String date = key.snapshot().dimensions().get("date");
        ViewerAccess v = key.signature() == null ? null : viewers.get(key.signature());
        LakeInputs in = new LakeInputs(descriptor(), binding.inputs(), data, v, date == null ? null : LocalDate.parse(date), compiledWheres, rowsIn);
        in.stamps();                                                    // the versions it reads, fixed before it opens (5.2 rule 8): a later commit makes it stale
        in.onMoved(() -> {                                              // a read found a newer version than the stamp: stale now, not at the next look
            Sessions s = sessions;
            if (s != null) {
                boolean due = preparationDue(key.snapshot());
                s.stale(key.snapshot(), due);
                if (due) {
                    prepareOn("data-version", null);
                }
            }
        });
        inputsByKey.put(key, in);
        return in;
    }

    /** The input stamps an answer's stamp names: those of the session being asked (the inputs it read). */
    private SortedMap<String, String> inputStamps(Snapshot snapshot) {
        com.ash.engine.Session session = ASKING_SESSION.get();
        Sessions pool = sessions;
        if (session != null && pool != null && pool.inputsOf(session) instanceof LakeInputs own && session.snapshot().equals(snapshot)) {
            return own.stamps();
        }
        Sessions.Key asking = ASKING.get();
        if (asking != null && asking.snapshot().equals(snapshot)) {
            LakeInputs own = inputsByKey.get(asking);
            if (own != null) {
                return own.stamps();
            }
        }
        for (Map.Entry<Sessions.Key, LakeInputs> e : inputsByKey.entrySet()) {
            if (e.getKey().snapshot().equals(snapshot)) {
                return e.getValue().stamps();
            }
        }
        return new TreeMap<>();
    }

    // ------------------------------------------------------------------------------------------------------------- triggers

    /** Runs a named ask of the engine file as its identity ({@code as}, default {@code system}) and publishes its tables. */
    public Answer runAsk(String name, RequestClass c) throws EngineException {
        return runAsks(List.of(name), c).get(0);
    }

    /**
     * Runs named asks in order (a trigger's {@code ask: [a, b]}, 9.3). Consecutive prepare asks of one snapshot are one preparation: the
     * snapshot shows "preparing" from the first until the last has filled the answer cache, so nobody sees it ready half prepared.
     */
    public List<Answer> runAsks(List<String> names, RequestClass c) throws EngineException {
        List<Answer> out = new ArrayList<>();
        int i = 0;
        while (i < names.size()) {
            Named first = named(names.get(i), c);
            if (!first.ask().prepare()) {
                out.add(ask(first.request(), first.as()));
                i++;
                continue;
            }
            List<Named> group = new ArrayList<>(List.of(first));       // the prepare asks of the same snapshot that follow it
            while (i + group.size() < names.size()) {
                Named next = named(names.get(i + group.size()), c);
                if (!next.ask().prepare() || !next.snapshot().equals(first.snapshot())) {
                    break;
                }
                group.add(next);
            }
            out.addAll(prepare(first.snapshot(), group));
            i += group.size();
        }
        return out;
    }

    /** A named ask resolved: the file's ask, the request it makes, its snapshot, its identity. */
    private record Named(EngineFile.NamedAsk ask, Ask request, Snapshot snapshot, ViewerAccess as) {}

    private Named named(String name, RequestClass c) throws EngineException {
        EngineFile.NamedAsk a = file.asks().get(name);
        if (a == null) {
            throw new EngineException("ENG-3003", Kind.REFUSED, id + " has no named ask " + name + "; its asks are " + file.asks().keySet());
        }
        Map<String, String> snap = new TreeMap<>();
        a.snapshot().forEach((k, v) -> snap.put(k, String.valueOf(v)));
        if ("latest".equals(snap.get("date"))) {
            snap.put("date", latestDate().map(LocalDate::toString).orElse(null));
            if (snap.get("date") == null) {
                throw new EngineException("ENG-2001", Kind.UNAVAILABLE, id + ": no input of its data-version trigger holds a date yet");
            }
        }
        // a prepare ask opens the session and fills the answer cache, published nowhere (9.3 rule 4); any other publishes its tables
        Ask ask = new Ask(a.outputs(), a.params(), snap, c, !a.prepare());
        return new Named(a, ask, snapshot(descriptor(), snap), ViewerAccess.system(a.as() == null ? "system" : a.as()));
    }

    /** One preparation of {@code snapshot}: its prepare asks in order, under one "preparing" mark (one preparation of a snapshot at a time). */
    private List<Answer> prepare(Snapshot snapshot, List<Named> group) throws EngineException {
        List<Answer> out = new ArrayList<>();
        Preparing p = new Preparing(Instant.now(), new java.util.concurrent.atomic.AtomicReference<>(),
                new java.util.concurrent.CompletableFuture<>());
        Preparing was = preparing.putIfAbsent(snapshot, p);
        if (was != null) {
            awaitPrepared(snapshot, RequestClass.EVENT);               // another preparation of it runs: these after it (from its cache)
            for (Named n : group) {
                out.add(ask(n.request(), n.as()));
            }
            return out;
        }
        PREPARER.set(p);
        try {
            EngineException failed = null;
            for (Named n : group) {                                     // each ask tried: one that fails leaves the others' answers cached
                try {
                    out.add(ask(n.request(), n.as()));
                } catch (EngineException e) {
                    LOG.warn("engine {}: preparing {}: {} failed: {}", id, snapshot.text(), n.request().outputs(), e.getMessage());
                    failed = failed == null ? e : failed;
                }
            }
            if (failed != null) {
                throw failed;
            }
            return out;
        } finally {
            PREPARER.remove();
            Sessions s = sessions;
            if (s != null) {
                s.prepared(snapshot);                                   // a held new version swapped in, its answers cached
            }
            preparing.remove(snapshot, p);
            p.done().complete(null);
        }
    }

    /**
     * Waits for a preparation of {@code snapshot} running now: an interactive ask at most the open wait, then {@code ENG-5025} with the
     * stage the engine names; any other until it ends. Nothing to wait for: returns at once.
     */
    private void awaitPrepared(Snapshot snapshot, RequestClass c) throws EngineException {
        Preparing p = preparing.get(snapshot);
        if (p == null) {
            return;
        }
        Duration wait = openWait(c);
        try {
            if (wait == null) {
                p.done().get();
            } else {
                p.done().get(Math.max(0, wait.toMillis()), java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        } catch (java.util.concurrent.TimeoutException e) {
            HostControl ctl = p.control().get();
            String stage = ctl == null || ctl.words() == null ? "opening the date" : ctl.words();
            throw new EngineException("ENG-5025", Kind.UNAVAILABLE, "preparing " + snapshot.text() + " since " + p.since() + ": " + stage
                    + "; ask again in a few seconds", Map.of("snapshot", snapshot.text(), "since", p.since().toString(), "stage", stage,
                    "retry-after-seconds", "2"), null);
        } catch (java.util.concurrent.ExecutionException e) {
            // the preparation failed (audited by its ask): this ask opens the date itself
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EngineException("ENG-5010", Kind.CANCELLED, "interrupted while " + snapshot.text() + " was prepared");
        }
    }

    /** How long a request of {@code c} waits for an opening session: interactive ones {@code limits.open-wait-seconds}, others until it opens. */
    private Duration openWait(RequestClass c) {
        return c == RequestClass.INTERACTIVE ? Duration.ofSeconds(file.limits().openWaitSeconds()) : null;
    }

    /**
     * The newest date of the snapshot {@code latest} (9.3 rule 4): the newest date the first input named by the data-version trigger holds
     * (its primary input; an input without a date is skipped), else the newest date of any bound kind.
     */
    private Optional<LocalDate> latestDate() {
        for (EngineFile.Trigger t : file.triggers()) {
            if ("data-version".equals(t.on())) {
                for (String input : t.inputs()) {
                    InputBinder.Bound b = binding.inputs().get(input);
                    Optional<LocalDate> d = b == null ? Optional.empty() : b.kinds().stream().map(data::latest).flatMap(Optional::stream)
                            .max(java.util.Comparator.naturalOrder());
                    if (d.isPresent()) {
                        return d;
                    }
                }
            }
        }
        return binding.inputs().values().stream().flatMap(b -> b.kinds().stream()).map(data::latest).flatMap(Optional::stream)
                .max(java.util.Comparator.naturalOrder());
    }

    /**
     * Runs, on a host thread of its own, the named ask of each trigger {@code on} (a {@code prepare} ask opens the newest snapshot and fills
     * the answer cache before any user asks); {@code only}: the triggers of these names only (null: all). Failures are audited by ask.
     */
    private void prepareOn(String on, java.util.function.Predicate<EngineFile.Trigger> only) {
        List<String> names = file.triggers().stream().filter(t -> on.equals(t.on()) && (only == null || only.test(t)))
                .flatMap(t -> t.asks().stream()).distinct().toList();
        if (names.isEmpty()) {
            return;
        }
        Thread.ofVirtual().name("drishti-engine-" + id + "-" + on).start(() -> {
            try {
                runAsks(names, RequestClass.EVENT);
            } catch (EngineException | RuntimeException e) {
                LOG.warn("engine {}: the {} trigger's asks {} failed: {}", id, on, names, e.getMessage());
            }
        });
    }

    /** True when a data-version trigger will prepare {@code snapshot}: its prepare ask's snapshot is the newest date, and this is it. */
    private boolean preparationDue(Snapshot snapshot) {
        boolean prepares = file.triggers().stream().filter(t -> "data-version".equals(t.on())).flatMap(t -> t.asks().stream())
                .map(n -> file.asks().get(n)).anyMatch(a -> a != null && a.prepare() && "latest".equals(String.valueOf(a.snapshot().get("date"))));
        String date = snapshot.dimensions().get("date");
        return prepares && date != null && latestDate().map(d -> d.toString().equals(date)).orElse(false);
    }

    /** Per data-version trigger: the newest snapshot and its inputs' versions last seen, and a change waiting for its quiet period. */
    private final Map<Integer, String> seenLatest = new ConcurrentHashMap<>();
    private final Map<Integer, long[]> pendingLatest = new ConcurrentHashMap<>();

    /**
     * The data-version look (9.3), the one way this host learns that inputs changed (a module that commits data tells the host through
     * {@code inputsChanged}, which looks at once), run for every running engine whether or not its file has a {@code data-version}
     * trigger: when a bound kind's stamp for an open snapshot moved, the snapshot's sessions are stale (each answers, noted, until its
     * successor is ready), so a new data version is always a new key; with the trigger, the named asks are also run again; when the newest snapshot of
     * the trigger's inputs is new (a newer date, or a new version of it: the lake's commit), after the trigger's quiet period (at most its
     * max-wait) its ask runs (a {@code prepare} ask: the new snapshot opened and cached before users ask). Returns the snapshots found stale.
     */
    public synchronized List<Snapshot> lookAtDataVersions() {
        Sessions s = sessions;
        EngineInstance i = instance;
        if (s == null || i == null || i.state() != LifecycleState.RUNNING) {
            return List.of();                                           // every running engine's sessions are watched; a trigger only adds its asks
        }
        List<Snapshot> stale = new ArrayList<>();
        boolean prepareNow = false;
        for (Sessions.Key key : s.keys()) {                            // an open session whose inputs' versions moved since it read them
            if (s.isStale(key) || stale.contains(key.snapshot()) || !(s.inputsOf(key) instanceof LakeInputs in)) {
                continue;
            }
            if (!in.stamps().equals(in.currentStamps())) {
                boolean due = preparationDue(key.snapshot());
                s.stale(key.snapshot(), due);
                prepareNow |= due;
                stale.add(key.snapshot());
            }
        }
        if (prepareNow) {
            prepareOn("data-version", null);                            // the stale newest date prepared at once: its successor is held for it
        }
        if (!stale.isEmpty() && file.triggers().stream().anyMatch(t -> "data-version".equals(t.on()))) {   // re-asks: the trigger's
            for (Map.Entry<String, EngineFile.NamedAsk> a : file.asks().entrySet()) {
                if (a.getValue().prepare()) {
                    continue;                                           // preparations run by their triggers below
                }
                try {
                    runAsk(a.getKey(), RequestClass.EVENT);
                } catch (EngineException e) {
                    // recorded in the audit log by ask
                }
            }
        }
        long nowMs = System.currentTimeMillis();
        for (int t = 0; t < file.triggers().size(); t++) {
            EngineFile.Trigger trig = file.triggers().get(t);
            if (!"data-version".equals(trig.on()) || trig.asks().isEmpty()) {
                continue;
            }
            String newest = newestSignature(trig);
            String was = seenLatest.put(t, newest);
            if (was != null && !was.equals(newest)) {
                boolean first = !pendingLatest.containsKey(t);
                long[] p = pendingLatest.computeIfAbsent(t, k -> new long[] {nowMs, nowMs});
                p[1] = nowMs;
                if (first) {                                            // looked at again when the quiet period ends, not at the next poll
                    long wait = millis(trig.quiet(), 0) + 50;
                    Thread.ofVirtual().name("drishti-engine-" + id + "-quiet").start(() -> {
                        try {
                            Thread.sleep(wait);
                            lookAtDataVersions();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                }
            }
            long[] p = pendingLatest.get(t);
            if (p != null && (nowMs - p[1] >= millis(trig.quiet(), 0) || nowMs - p[0] >= millis(trig.maxWait(), 60_000))) {
                pendingLatest.remove(t);
                int at = t;
                prepareOn("data-version", x -> file.triggers().indexOf(x) == at);
            }
        }
        return stale;
    }

    /** The newest date of the trigger's inputs and the versions of its inputs on it: a change is a new snapshot to prepare. */
    private String newestSignature(EngineFile.Trigger t) {
        Optional<LocalDate> newest = latestDate();
        StringBuilder sig = new StringBuilder(newest.map(LocalDate::toString).orElse("none")).append('|');
        List<String> names = t.inputs().isEmpty() ? List.copyOf(binding.inputs().keySet()) : t.inputs();
        for (String input : names) {
            InputBinder.Bound b = binding.inputs().get(input);
            if (b != null) {
                b.kinds().forEach(k -> sig.append(k).append('=').append(newest.flatMap(d -> data.stamp(k, d)).orElse("?")).append(';'));
            }
        }
        return sig.toString();
    }

    /** A duration of the engine file ({@code 5s}, {@code 500ms}, {@code 2m}) in milliseconds; {@code dflt} when absent. */
    static long millis(String d, long dflt) {
        if (d == null || d.isBlank()) {
            return dflt;
        }
        String t = d.strip();
        if (t.endsWith("ms")) {
            return Long.parseLong(t.substring(0, t.length() - 2));
        }
        long n = Long.parseLong(t.substring(0, t.length() - 1));
        return switch (t.charAt(t.length() - 1)) {
            case 's' -> n * 1_000;
            case 'm' -> n * 60_000;
            case 'h' -> n * 3_600_000;
            default -> throw new IllegalArgumentException("a duration is a number with ms, s, m or h, not " + d);
        };
    }

    /** The snapshots being prepared now, each with since when and the stage the engine names (the engine card). */
    public List<Map<String, String>> preparingNow() {
        List<Map<String, String>> out = new ArrayList<>();
        preparing.forEach((snap, p) -> {
            HostControl c = p.control().get();
            out.add(Map.of("snapshot", snap.text(), "since", p.since().toString(), "stage", c == null || c.words() == null ? "opening" : c.words()));
        });
        return out;
    }

    /** Each session as the engine card shows it: opening (the stage the engine named), ready, or stale (its successor opening). */
    public List<Sessions.State> sessionStates() {
        Sessions s = sessions;
        return s == null ? List.of() : s.states();
    }

    /** The triggers the host does not run yet, in words for the Admin card (9.3 rule 2: not an error). */
    public List<String> waiting() {
        List<String> out = new ArrayList<>();
        for (EngineFile.Trigger t : file == null ? List.<EngineFile.Trigger>of() : file.triggers()) {
            if ("changes".equals(t.on()) || "after".equals(t.on())) {
                out.add(t.on() + (t.input() != null ? " on " + t.input() : "") + ": waiting: not configured on this server yet");
            }
        }
        return out;
    }

    @Override
    public void close() {
        shutdown("");
    }
}
