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
package com.ash.drishti.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * R2 speed check (docs/plans/R2_CUT_PLAN.md step 4a, owner: 100,000 positions are enough): one packaged server over a lake of
 * {@code tools/bench/vargen.py} P&amp;L vectors ({@code -Dspeed.positions}, one OTC trade a position, 509 scenarios, 2026-09-30) with
 * Drishti Risk's JARs in {@code lib/engines}; the VaR headline asked in-tree and through the {@code market-risk} engine: the first open of
 * the date on each path, then every node of the management hierarchy warm (two rounds) and the leaf nodes (books: their parts are
 * positions, the leaf headline's cost), p50 and p99 per path, the server's resident memory before and after the engine opens the date, and
 * the figures compared (VaR and ES of every node asked equal between the paths). Gated on {@code -Dspeed.positions}; the tier as the side
 * by side ({@code -Dsbs.tier=heap}; default mapped); the heap by {@code -Dsbs.xmx}. One heavy job: never with another speed run.
 *
 * <p>R2_ENGINE_SPEED_DESIGN step 0: both opens are cold (in-tree's start-up capture and preload off); the host's engine asks and cache
 * misses per headline of each phase (Admin, Caches: {@code engines.answers}); the engine's card (sessions, memory) after the open and at
 * the end; with sign-in on ({@code -Dspeed.second-viewer}, default on) a desk-limited second viewer's first ask, figures and alternation
 * with the administrator; {@code -Dspeed.gate=true} asserts the criteria of the design's section 6 the run measures (a criterion it does
 * not measure fails the gate, never passes it).
 */
class RiskSpeedCheckTest {

    static final String DATE = "2026-09-30";
    // the sample's scenario FX of the set HS-509 (vargen's set id): the engine reads a date's FX before it builds a book
    static final String ONLY = "market-risk/scenario-set,market-risk/fx-scenario,market-risk/var-limit,core/desk,core/book,core/legal-entity";
    static int positions;
    static Path folder;
    static PackagedServer server;
    static Path work0;
    /** {@code -Dspeed.zero-copy=on}: the host's float32 rows handed to the engine in place (R2_CUT_PLAN 3a); off: the engine builds its own. */
    static final String ZERO_COPY = System.getProperty("speed.zero-copy", "off");
    /** {@code -Dspeed.gate=true}: the R2 speed gate's criteria (R2_ENGINE_SPEED_DESIGN section 6) asserted, not only recorded. */
    static final boolean GATE = Boolean.getBoolean("speed.gate");
    /** {@code -Dspeed.second-viewer} (default on): sign-in on, and a desk-limited second viewer asks after the administrator. */
    static final boolean SECOND_VIEWER = Boolean.parseBoolean(System.getProperty("speed.second-viewer", "true"));
    static final String SECRET = "speed-check-secret-that-is-at-least-32-bytes";
    static final String DESK = "DESK-000";
    static final String DESK_RULE = "DESK = '" + DESK + "'";

    @BeforeAll
    static void start() throws Exception {
        positions = Integer.getInteger("speed.positions", 0);
        assumeTrue(positions > 0 && positions <= 100_000, "no speed check asked (-Dspeed.positions, at most 100,000)");
        String jars = System.getProperty("drishti.risk.jars", "");
        assumeTrue(!jars.isBlank() && Files.isDirectory(Path.of(jars)), "no folder of Drishti Risk JARs given (-Ddrishti.risk.jars)");
        folder = Path.of(System.getProperty("drishti.g14.dir")).toAbsolutePath().resolve("speed-" + positions);
        // -Dspeed.lake: a folder holding a built lake/ and vargen/ (one shared copy, read only: lake data is data); none: built here once
        Path shared = System.getProperty("speed.lake", "").isBlank() ? folder : Path.of(System.getProperty("speed.lake"));
        Path lake = shared.resolve("lake");
        PackagedServer.buildLake(lake, ONLY);
        Path vargen = shared.resolve("vargen");
        if (!Files.exists(vargen.resolve("LOADED"))) {
            run(folder.resolve("vargen.log"), System.getProperty("speed.python", "python3"), PackagedServer.REPO.resolve("tools/bench/vargen.py").toString(),
                    "--out", vargen.toString(), "--positions", Integer.toString(positions), "--portfolios", "300", "--dates", DATE, "--big-baskets", "0",
                    "--kinds", "pnl-vector,scenario-set,var-limit");
            Map<String, String> keys = Map.of("pnl-vector", "VECTOR_ID", "scenario-set", "SCENARIO_SET_ID", "var-limit", "LIMIT_ID");
            for (String kind : List.of("scenario-set", "var-limit", "pnl-vector")) {
                run(folder.resolve("load-" + kind + ".log"), PackagedServer.lakePython(), PackagedServer.REPO.resolve("tools/drishti.py").toString(), "lake",
                        "build", "--from", vargen.resolve(kind).toString(), "--pack", PackagedServer.REPO.resolve("modules/banking/packs/market-risk").toString(),
                        "--connector", "risk-store", "--kind", kind, "--key", keys.get(kind), "--mode", "overwrite-dates", "--lake",
                        lake.resolve("delta").toString(), "--engine", "java", "-q");
            }
            Files.writeString(vargen.resolve("LOADED"), "loaded\n");
        }
        String tier = System.getProperty("sbs.tier", "");
        Path work = folder.resolve("server-" + (tier.isBlank() ? "mapped" : tier) + "-zc-" + ZERO_COPY);
        RiskSideBySideTest.deleteTree(work);
        SideBySide.dropJars(Path.of(jars), work);
        Map<String, String> env = new LinkedHashMap<>();
        env.put("DRISHTI_VAR_SYSTEM_BASKETS_SEED", "");                 // the sample's baskets name sample positions: none here
        if (!tier.isBlank()) {
            env.put("DRISHTI_VAR_TIER", tier);
        }
        env.put("DRISHTI_VAR_MEMORY_MB", System.getProperty("speed.var-memory-mb", "3000"));   // the in-tree date's room (heap tier: float64 rows)
        env.put("DRISHTI_VAR_OPEN_HEAP_MB", System.getProperty("speed.open-heap-mb", "2500"));
        env.put("DRISHTI_VAR_ZERO_COPY", ZERO_COPY);
        env.put("DRISHTI_MARKET_RISK_MEMORY_MB", System.getProperty("speed.engine-memory-mb", "3000"));   // the engine's books of the date
        // the cold open of both paths (R2_ENGINE_SPEED_DESIGN step 0): in-tree's start-up capture and its preload of the date before off
        env.put("DRISHTI_VAR_PRELOAD_PREVIOUS_DATE", "false");
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("var", Map.of("official", Map.of("on-start", false)));
        if (SECOND_VIEWER) {                                            // sign-in on: an administrator and a desk-limited second viewer
            settings.put("security", Map.of("enabled", true, "secret", SECRET, "registered-users-only", false, "roles", Map.of(
                    "speed-admin", Map.of("kinds", List.of("*"), "raw", true, "admin", true),
                    "speed-desk", Map.of("kinds", List.of("*"), "raw", true, "rows", Map.of("pnl-vector", DESK_RULE, "scenario-pv", DESK_RULE)))));
        }
        env.put("SPRING_APPLICATION_JSON", PackagedServer.JSON.writeValueAsString(Map.of("drishti", settings)));
        work0 = work;
        server = PackagedServer.start(work, lake, DATE, env, SECOND_VIEWER ? bearer("speed-admin", "speed-admin") : null);
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.close();
        }
    }

    private static void run(Path log, String... cmd) throws IOException, InterruptedException {
        Files.createDirectories(log.getParent());
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(PackagedServer.REPO.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        pb.environment().put("DRISHTI_DATA_DIR", folder.resolve("data").toString());
        pb.environment().put("DRISHTI_TMP_DIR", folder.resolve("tmp").toString());
        Process p = pb.start();
        if (!p.waitFor(60, java.util.concurrent.TimeUnit.MINUTES) || p.exitValue() != 0) {
            p.destroyForcibly();
            throw new IllegalStateException(String.join(" ", cmd) + " failed: see " + log);
        }
    }

    @Test
    void theEnginePathAgainstInTreeAtSpeedPositions() throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("positions", positions);
        out.put("tier", System.getProperty("sbs.tier", "").isBlank() ? "mapped" : System.getProperty("sbs.tier"));
        out.put("zero_copy", ZERO_COPY);
        out.put("rss_mb_start", rssMb());
        out.put("engine_card_start", card());
        long t0 = System.nanoTime();                                       // the cold open of each path: nothing opened the date before
        server.get(headline("MGMT~BANK", "in-tree"));
        out.put("open_in_tree_ms", (System.nanoTime() - t0) / 1_000_000);
        out.put("rss_mb_after_in_tree_open", rssMb());
        long[] c0 = answers();
        t0 = System.nanoTime();
        server.get(headline("MGMT~BANK", "engine"));
        out.put("open_engine_ms", (System.nanoTime() - t0) / 1_000_000);
        out.put("open_engine_asks", answers()[0] + answers()[1] - c0[0] - c0[1]);
        out.put("rss_mb_after_engine_open", rssMb());
        out.put("engine_card_after_open", card());
        List<String> nodes = new ArrayList<>();
        List<String> leaves = new ArrayList<>();
        for (JsonNode n : server.get("/api/v1/risk/var/tree?hierarchy=MGMT&asOf=" + DATE + "&path=in-tree").path("nodes")) {
            (n.path("level").asText().equals("book") ? leaves : nodes).add(n.path("id").asText());   // books expand into positions: the leaves
        }
        int innerAsked = Integer.getInteger("speed.nodes", nodes.size());   // a profiling run may ask fewer inner nodes (the scripted run: all)
        if (innerAsked < nodes.size()) {
            nodes = new ArrayList<>(nodes.subList(0, innerAsked));
        }
        out.put("nodes", nodes.size());
        out.put("leaves", leaves.size());
        List<String> differ = new ArrayList<>();
        for (String path : List.of("in-tree", "engine")) {                 // a warm-up round of the inner nodes on each path
            for (String id : nodes) {
                server.get(headline(id, path));
            }
        }
        Map<String, List<Long>> inner = new LinkedHashMap<>();
        Map<String, List<Long>> leaf = new LinkedHashMap<>();
        Map<String, List<Long>> tree = new LinkedHashMap<>();
        long[] before = answers();                                          // the host's engine asks and misses per headline (warm inner)
        for (int round = 0; round < 2; round++) {
            for (String id : nodes) {
                compare(id, inner, differ);
            }
        }
        perAsk(out, "inner", before, 2 * nodes.size());
        List<String> sample = leaves.subList(0, Math.min(leaves.size(), Integer.getInteger("speed.leaves", 200)));
        before = answers();
        for (String id : sample) {
            compare(id, leaf, differ);
        }
        perAsk(out, "leaf", before, sample.size());
        for (String path : List.of("in-tree", "engine")) {
            out.put("inner_p50_ms_" + path, pct(inner.get(path), 0.50));
            out.put("inner_p99_ms_" + path, pct(inner.get(path), 0.99));
            out.put("leaf_p50_ms_" + path, pct(leaf.get(path), 0.50));
            out.put("leaf_p99_ms_" + path, pct(leaf.get(path), 0.99));
        }
        out.put("leaf_asked", sample.size());
        before = answers();
        for (int round = 0; round < 3; round++) {                           // the tree of the hierarchy (the explorer's left pane), warm
            JsonNode[] tr = new JsonNode[2];
            int i = 0;
            for (String path : List.of("in-tree", "engine")) {
                long t1 = System.nanoTime();
                tr[i++] = server.get("/api/v1/risk/var/tree?hierarchy=MGMT&asOf=" + DATE + "&path=" + path);
                tree.computeIfAbsent(path, k -> new ArrayList<>()).add((System.nanoTime() - t1) / 1_000_000);
            }
            if (round == 0) {
                Map<String, String> a = new LinkedHashMap<>();
                tr[0].path("nodes").forEach(n -> a.put(n.path("id").asText(), n.path("var").asText() + "/" + n.path("es").asText()));
                Map<String, String> b = new LinkedHashMap<>();
                tr[1].path("nodes").forEach(n -> b.put(n.path("id").asText(), n.path("var").asText() + "/" + n.path("es").asText()));
                if (!a.equals(b)) {
                    differ.add("tree: " + a.size() + " rows in-tree, " + b.size() + " engine, not equal");
                }
                out.put("tree_rows", a.size());
            }
        }
        perAsk(out, "tree", before, 3);
        for (String path : List.of("in-tree", "engine")) {
            out.put("tree_first_ms_" + path, tree.get(path).get(0));
            out.put("tree_p50_ms_" + path, pct(tree.get(path), 0.50));
        }
        if (SECOND_VIEWER) {
            secondViewer(out, nodes, differ);
        }
        out.put("figures_differ", differ.size());
        out.put("rss_mb_end", rssMb());
        out.put("engine_card_end", card());
        String log = Files.readString(work0.resolve("server.log"));
        out.put("zero_copy_served", log.contains("served to the engine in place"));
        out.put("zero_copy_lines", log.lines().filter(l -> l.contains("zero copy") || l.contains("not served")).map(String::strip).distinct().limit(6).toList());
        long[] all = answers();
        out.put("engine_answers_hits", all[0]);
        out.put("engine_answers_misses", all[1]);
        out.put("engine_answers_bytes", all[2]);
        List<String> gate = gate(out);
        out.put("gate_failed", gate);
        String line = PackagedServer.JSON.writeValueAsString(out);
        System.out.println("speed check: " + line + "; first differences " + differ.stream().limit(5).toList());
        Files.writeString(folder.resolve("speed-" + out.get("tier") + "-zc-" + ZERO_COPY + ".json"), line + "\n");
        assertThat(differ).as("VaR and ES that differ between the paths").isEmpty();
        if (GATE) {
            assertThat(gate).as("the R2 speed gate (R2_ENGINE_SPEED_DESIGN section 6)").isEmpty();
        }
    }

    /**
     * The second viewer (a desk-limited row rule) after the administrator: their first engine ask (a session of their own when the engine
     * runs per viewer), their figures against in-tree's for the same person, then the two viewers alternating on one node.
     */
    private static void secondViewer(Map<String, Object> out, List<String> nodes, List<String> differ) throws Exception {
        String desk = bearer("speed-desk-viewer", "speed-desk");
        long[] c0 = answers();
        long t0 = System.nanoTime();
        JsonNode e = server.get(headline("MGMT~BANK", "engine"), desk);
        out.put("viewer2_first_engine_ms", (System.nanoTime() - t0) / 1_000_000);
        out.put("viewer2_first_engine_asks", answers()[0] + answers()[1] - c0[0] - c0[1]);
        out.put("viewer2_sessions_after", card().get("sessions"));
        JsonNode t = server.get(headline("MGMT~BANK", "in-tree"), desk);
        for (String f : List.of("var", "es", "trades")) {
            if (!t.path(f).asText().equals(e.path(f).asText())) {
                differ.add("second viewer MGMT~BANK " + f + ": in-tree " + t.path(f) + ", engine " + e.path(f));
            }
        }
        out.put("viewer2_trades", e.path("trades").asLong());
        List<Long> alternate = new ArrayList<>();
        String node = nodes.isEmpty() ? "MGMT~BANK" : nodes.get(nodes.size() / 2);
        for (int i = 0; i < 4; i++) {
            long t1 = System.nanoTime();
            if (i % 2 == 0) {
                server.get(headline(node, "engine"), desk);
            } else {
                server.get(headline(node, "engine"));
            }
            alternate.add((System.nanoTime() - t1) / 1_000_000);
        }
        out.put("viewer2_alternate_ms", alternate);
    }

    /** The criteria of the gate this run can measure; each failure in words. A criterion not measured here is not a pass. */
    private static List<String> gate(Map<String, Object> o) {
        List<String> f = new ArrayList<>();
        if (((Number) o.get("figures_differ")).intValue() != 0) {
            f.add("figures differ");
        }
        bound(f, o, "inner_p50", 1.2, 5);
        bound(f, o, "inner_p99", 1.5, 15);
        bound(f, o, "leaf_p50", 1.2, 5);
        bound(f, o, "leaf_p99", 1.5, 15);
        bound(f, o, "tree_p50", 1.2, 0);
        long cold = num(o, "open_engine_ms");
        if (cold > 5_000 || cold > 2 * num(o, "open_in_tree_ms")) {
            f.add("cold open " + cold + " ms: at most 5,000 and 2 x in-tree's " + num(o, "open_in_tree_ms"));
        }
        if (o.containsKey("viewer2_first_engine_ms") && num(o, "viewer2_first_engine_ms") > 1_000) {
            f.add("second viewer's first ask " + o.get("viewer2_first_engine_ms") + " ms: at most 1,000");
        }
        if (!o.containsKey("viewer2_first_engine_ms")) {
            f.add("second viewer not measured (-Dspeed.second-viewer=false)");
        }
        if (((Number) o.get("inner_asks_per")).doubleValue() > 4 || ((Number) o.get("inner_misses_per")).doubleValue() > 0) {
            f.add("host asks per warm inner headline " + o.get("inner_asks_per") + ", misses " + o.get("inner_misses_per") + ": at most 4, 0");
        }
        long inTree = num(o, "rss_mb_after_in_tree_open");
        long engine = num(o, "rss_mb_after_engine_open") - inTree;
        if (engine > 0.15 * inTree) {
            f.add("resident memory: the engine path adds " + engine + " MB to in-tree's " + inTree + " MB: at most 15 %");
        }
        Object mem = o.get("engine_card_end") instanceof Map<?, ?> m ? m.get("memoryBytes") : null;
        if (!(mem instanceof Number n) || n.longValue() > 200L << 20) {
            f.add("engine heap held for the date " + mem + " bytes: at most 200 MB");
        }
        for (String k : List.of("first_ask_after_start_ms", "first_ask_after_load_ms", "ask_while_preparing_ms", "reopen_ms")) {
            if (!o.containsKey(k)) {
                f.add(k + " not measured by this run");
            }
        }
        return f;
    }

    /** {@code name}_ms_engine at most {@code factor} x in-tree, or in-tree + {@code plus} ms (when plus is not 0). */
    private static void bound(List<String> f, Map<String, Object> o, String name, double factor, long plus) {
        long in = num(o, name + "_ms_in-tree");
        long en = num(o, name + "_ms_engine");
        double limit = Math.max(factor * in, plus == 0 ? 0 : in + plus);
        if (en > limit) {
            f.add(name + " " + en + " ms against in-tree " + in + " ms: at most " + (long) limit);
        }
    }

    private static long num(Map<String, Object> o, String k) {
        return o.get(k) instanceof Number n ? n.longValue() : Long.MAX_VALUE;
    }

    /** The host's engine asks and cache misses per headline of a phase, from the answer cache's counters before and after. */
    private static void perAsk(Map<String, Object> out, String phase, long[] before, int headlines) throws Exception {
        long[] after = answers();
        long asks = after[0] + after[1] - before[0] - before[1];
        out.put(phase + "_asks_per", Math.round(100.0 * asks / Math.max(1, headlines)) / 100.0);
        out.put(phase + "_misses_per", Math.round(100.0 * (after[1] - before[1]) / Math.max(1, headlines)) / 100.0);
    }

    /** The answer cache's hits, misses and bytes (Admin, Caches: {@code engines.answers}). */
    private static long[] answers() throws Exception {
        for (JsonNode c : server.get("/api/v1/admin/caches").path("caches")) {
            if (c.path("name").asText().equals("engines.answers")) {
                return new long[] {c.path("hits").asLong(), c.path("misses").asLong(), c.path("bytes").asLong(-1)};
            }
        }
        return new long[] {0, 0, -1};
    }

    /** The engine's Admin card: state, isolation, sessions, memory, and each session's stage when the host reports one. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> card() throws Exception {
        for (JsonNode e : server.get("/api/v1/admin/engines/host").path("engines")) {
            if (e.path("id").asText().equals("market-risk")) {
                Map<String, Object> m = new LinkedHashMap<>();
                for (String k : List.of("state", "isolation", "sessions", "memoryBytes", "sessionStates")) {
                    if (e.has(k)) {
                        m.put(k, PackagedServer.JSON.convertValue(e.get(k), Object.class));
                    }
                }
                return m;
            }
        }
        return Map.of();
    }

    /** A signed token for {@code user} holding {@code role} (the server's own format: HS256 over the shared secret). */
    static String bearer(String user, String role) {
        try {
            java.util.Base64.Encoder b64 = java.util.Base64.getUrlEncoder().withoutPadding();
            String h = b64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            String c = b64.encodeToString(PackagedServer.JSON.writeValueAsBytes(Map.of("sub", user, "roles", List.of(role),
                    "exp", java.time.Instant.now().getEpochSecond() + 6 * 3600)));
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "Bearer " + h + "." + c + "." + b64.encodeToString(mac.doFinal((h + "." + c).getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void compare(String id, Map<String, List<Long>> times, List<String> differ) throws IOException, InterruptedException {
        JsonNode[] a = new JsonNode[2];
        int i = 0;
        for (String path : List.of("in-tree", "engine")) {
            long t0 = System.nanoTime();
            a[i++] = server.get(headline(id, path));
            times.computeIfAbsent(path, k -> new ArrayList<>()).add((System.nanoTime() - t0) / 1_000_000);
        }
        for (String f : List.of("var", "es", "trades")) {
            if (!a[0].path(f).asText().equals(a[1].path(f).asText())) {
                differ.add(id + " " + f + ": in-tree " + a[0].path(f) + ", engine " + a[1].path(f));
            }
        }
    }

    private static String headline(String id, String path) {
        return "/api/v1/risk/var/scope?scope=" + URLEncoder.encode("NODE:" + id, StandardCharsets.UTF_8) + "&asOf=" + DATE + "&path=" + path;
    }

    private static long pct(List<Long> xs, double q) {
        if (xs == null || xs.isEmpty()) {
            return -1;
        }
        List<Long> s = new ArrayList<>(xs);
        Collections.sort(s);
        return s.get(Math.min(s.size() - 1, (int) Math.ceil(q * s.size()) - 1));
    }

    private static long rssMb() throws IOException {
        for (String l : Files.readAllLines(Path.of("/proc/" + server.pid() + "/status"))) {
            if (l.startsWith("VmRSS:")) {
                return Long.parseLong(l.replaceAll("[^0-9]", "")) / 1024;
            }
        }
        return -1;
    }
}
