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
 * R2 speed check of the exposure family (R2_ENGINE_SPEED_DESIGN, lane S3: the {@code ccr} engine as {@link RiskSpeedCheckTest} is for
 * {@code market-risk}): one packaged server with Drishti Risk's JARs in {@code lib/engines}, every figure asked in-tree and through the
 * {@code ccr} engine in the same run: the cold open of the date on each path (the bank's scope, nothing opened before: the in-tree official
 * capture at start off), the exposure tree (three rounds, warm), every netting set with paths (two rounds, warm), the SIMM total of every
 * netting set (the first ask of each path apart, then two rounds); p50 and p99 per path, the host's engine asks and cache misses per ask
 * of each phase (Admin, Caches: {@code engines.answers}), the engine's card, the server's resident memory, and the figures compared
 * (whole units as the routes give them; the side-by-side tests compare every double to the bit).
 *
 * <p>The lake: the exposure inputs are not in the shared 100,000-position VaR lake, and a local run holds at most 10,000 trades; so the
 * check reads the counterparty-risk sample day the SIMM side by side builds (one folder, built once, read only: the same lake both tests
 * read), or {@code -Dspeed.ccr-lake=<folder>}: a built lake (its {@code BUILT} stamp, {@code delta/}, {@code connectors/}) holding the
 * exposure inputs. Gated on {@code -Dspeed.exposure=true}; the tier as the side by side ({@code -Dsbs.tier=heap}; default mapped).
 */
class ExposureSpeedCheckTest {

    static final String DATE = SimmSideBySideTest.DATE;
    static PackagedServer server;
    static Path work0;
    static Path folder;

    @BeforeAll
    static void start() throws Exception {
        assumeTrue(Boolean.getBoolean("speed.exposure"), "no exposure speed check asked (-Dspeed.exposure=true)");
        String jars = System.getProperty("drishti.risk.jars", "");
        assumeTrue(!jars.isBlank() && Files.isDirectory(Path.of(jars)), "no folder of Drishti Risk JARs given (-Ddrishti.risk.jars)");
        folder = Path.of(System.getProperty("drishti.g14.dir")).toAbsolutePath().resolve("speed-exposure");
        String given = System.getProperty("speed.ccr-lake", "");
        Path lake;
        if (given.isBlank()) {
            lake = SimmSideBySideTest.FOLDER.resolve("lake");
            PackagedServer.buildLake(lake, SimmSideBySideTest.ONLY);
        } else {
            lake = Path.of(given);
            assumeTrue(Files.exists(lake.resolve("BUILT")), "the lake " + lake + " is not built (no BUILT stamp)");
        }
        String tier = System.getProperty("sbs.tier", "");
        Path work = folder.resolve("server-" + (tier.isBlank() ? "mapped" : tier));
        RiskSideBySideTest.deleteTree(work);
        SideBySide.dropJars(Path.of(jars), work);
        Path params = Files.createDirectories(folder.resolve("simm-parameters"));   // the in-tree path's SIMM file: the lake's TOY file
        Files.copy(PackagedServer.REPO.resolve("modules/banking/java/banking-compute/src/test/resources/simm/simm-toy.yaml"), params.resolve("simm-toy.yaml"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Map<String, String> env = new LinkedHashMap<>();
        env.put("DRISHTI_SIMM_PARAMETERS", params.toString());
        if (!tier.isBlank()) {
            env.put("DRISHTI_EXPOSURE_TIER", tier);
        }
        // both opens cold: the in-tree official captures at start off (exposure's and, on a lake with VaR inputs, VaR's)
        env.put("SPRING_APPLICATION_JSON", PackagedServer.JSON.writeValueAsString(Map.of("drishti", Map.of(
                "ccr", Map.of("exposure", Map.of("official", Map.of("on-start", false))),
                "var", Map.of("official", Map.of("on-start", false))))));
        work0 = work;
        server = PackagedServer.start(work, lake, DATE, env);
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void theEnginePathAgainstInTreeForTheExposureFamily() throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tier", System.getProperty("sbs.tier", "").isBlank() ? "mapped" : System.getProperty("sbs.tier"));
        out.put("rss_mb_start", rssMb());
        out.put("engine_card_start", card());
        List<String> differ = new ArrayList<>();
        // the cold open of each path: the bank's scope, nothing opened before
        JsonNode[] bank = new JsonNode[2];
        int k = 0;
        for (String path : List.of("in-tree", "engine")) {
            long[] c0 = answers();
            long t0 = System.nanoTime();
            bank[k++] = server.get(scope("BANK", path));
            out.put("open_" + key(path) + "_ms", (System.nanoTime() - t0) / 1_000_000);
            if (path.equals("engine")) {
                out.put("open_engine_asks", answers()[0] + answers()[1] - c0[0] - c0[1]);
            }
            out.put("rss_mb_after_" + key(path) + "_open", rssMb());
        }
        same("BANK", bank[0], bank[1], differ);
        out.put("engine_card_after_open", card());
        // the tree (the Exposure Home's left pane), warm: the first ask apart
        Map<String, List<Long>> tree = new LinkedHashMap<>();
        long[] before = answers();
        List<String> sets = new ArrayList<>();
        for (int round = 0; round < 3; round++) {
            JsonNode[] tr = new JsonNode[2];
            int i = 0;
            for (String path : List.of("in-tree", "engine")) {
                long t1 = System.nanoTime();
                tr[i++] = server.get("/api/v1/risk/exposure/tree?asOf=" + DATE + "&path=" + path);
                tree.computeIfAbsent(path, x -> new ArrayList<>()).add((System.nanoTime() - t1) / 1_000_000);
            }
            if (round == 0) {
                Map<String, String> a = new LinkedHashMap<>();
                nodes(tr[0], a, sets);
                Map<String, String> b = new LinkedHashMap<>();
                nodes(tr[1], b, new ArrayList<>());
                if (!a.equals(b)) {
                    differ.add("tree: " + a.size() + " nodes in-tree, " + b.size() + " engine, not equal; first " + firstDifference(a, b));
                }
                out.put("tree_nodes", a.size());
            }
        }
        perAsk(out, "tree", before, 3);
        for (String path : List.of("in-tree", "engine")) {
            out.put("tree_first_ms_" + path, tree.get(path).get(0));
            out.put("tree_p50_ms_" + path, pct(tree.get(path).subList(1, tree.get(path).size()), 0.50));
        }
        // every netting set with paths, warm (the tree's first round opened nothing new: the date is open on both paths)
        out.put("netting_sets", sets.size());
        Map<String, List<Long>> nset = new LinkedHashMap<>();
        before = answers();
        for (int round = 0; round < 2; round++) {
            for (String s : sets) {
                JsonNode[] a = new JsonNode[2];
                int i = 0;
                for (String path : List.of("in-tree", "engine")) {
                    long t1 = System.nanoTime();
                    a[i++] = server.get(scope("NSET:" + s, path));
                    nset.computeIfAbsent(path, x -> new ArrayList<>()).add((System.nanoTime() - t1) / 1_000_000);
                }
                if (round == 0) {
                    same("NSET:" + s, a[0], a[1], differ);
                }
            }
        }
        perAsk(out, "nset", before, 2 * Math.max(1, sets.size()));
        for (String path : List.of("in-tree", "engine")) {
            out.put("nset_p50_ms_" + path, pct(nset.get(path), 0.50));
            out.put("nset_p99_ms_" + path, pct(nset.get(path), 0.99));
        }
        // the SIMM total of every netting set: the first ask of each path apart (SIMM's inputs read), then two rounds
        Map<String, List<Long>> simm = new LinkedHashMap<>();
        int simmAnswered = 0;
        before = answers();
        for (int round = 0; round < 2; round++) {
            for (String s : sets) {
                String[] text = new String[2];
                int i = 0;
                for (String path : List.of("in-tree", "engine")) {
                    long t1 = System.nanoTime();
                    PackagedServer.Answer r = server.send("GET", "/api/v1/risk/simm?asOf=" + DATE + "&nettingSet=" + enc(s) + "&path=" + path);
                    long ms = (System.nanoTime() - t1) / 1_000_000;
                    if (!out.containsKey("simm_first_ms_" + path)) {
                        out.put("simm_first_ms_" + path, ms);
                    } else {
                        simm.computeIfAbsent(path, x -> new ArrayList<>()).add(ms);
                    }
                    text[i++] = r.status() == 200 ? r.json().path("IM_COLLECT").asText() + "/" + r.json().path("IM_POST").asText() : "status " + r.status();
                }
                if (round == 0) {
                    if (!text[0].equals(text[1])) {
                        differ.add("SIMM " + s + ": in-tree " + text[0] + ", engine " + text[1]);
                    } else if (!text[0].startsWith("status")) {
                        simmAnswered++;
                    }
                }
            }
        }
        perAsk(out, "simm", before, 2 * Math.max(1, sets.size()));
        out.put("simm_answered", simmAnswered);
        for (String path : List.of("in-tree", "engine")) {
            out.put("simm_p50_ms_" + path, pct(simm.get(path), 0.50));
            out.put("simm_p99_ms_" + path, pct(simm.get(path), 0.99));
        }
        out.put("figures_differ", differ.size());
        out.put("rss_mb_end", rssMb());
        out.put("engine_card_end", card());
        long[] all = answers();
        out.put("engine_answers_hits", all[0]);
        out.put("engine_answers_misses", all[1]);
        out.put("engine_answers_bytes", all[2]);
        String line = PackagedServer.JSON.writeValueAsString(out);
        System.out.println("exposure speed check: " + line + "; first differences " + differ.stream().limit(5).toList());
        Files.writeString(folder.resolve("speed-exposure-" + out.get("tier") + ".json"), line + "\n");
        assertThat(sets).as("netting sets with paths").isNotEmpty();
        assertThat(differ).as("figures that differ between the paths").isEmpty();
    }

    /** The scope route's figures in whole units and its peak date. */
    private static void same(String what, JsonNode in, JsonNode en, List<String> differ) {
        for (String f : ExposureSideBySideTest.FIGURES) {
            if (!in.path(f).asText().equals(en.path(f).asText())) {
                differ.add(what + " " + f + ": in-tree " + in.path(f) + ", engine " + en.path(f));
            }
        }
    }

    /** Every node of a tree answer by scope (EPE, peak PFE, peak EE, its tenor, the EE line); the netting sets with paths in order. */
    private static void nodes(JsonNode n, Map<String, String> out, List<String> sets) {
        if (n.has("scope")) {
            String s = n.path("scope").asText();
            out.put(s, n.path("epe").asText() + "/" + n.path("peakPfe").asText() + "/" + n.path("peakEe").asText() + "/" + n.path("peakPfeTenor").asText()
                    + "/" + n.path("ee"));
            if (s.startsWith("NSET:") && !n.path("noPaths").asBoolean() && !sets.contains(s.substring(5))) {
                sets.add(s.substring(5));
            }
        }
        for (String child : List.of("groups", "children")) {
            for (JsonNode c : n.path(child)) {
                nodes(c, out, sets);
            }
        }
    }

    private static String firstDifference(Map<String, String> a, Map<String, String> b) {
        for (Map.Entry<String, String> e : a.entrySet()) {
            if (!e.getValue().equals(b.get(e.getKey()))) {
                return e.getKey() + ": in-tree " + e.getValue() + ", engine " + b.get(e.getKey());
            }
        }
        return "engine has more: " + b.keySet().stream().filter(x -> !a.containsKey(x)).limit(3).toList();
    }

    /** The host's engine asks and cache misses per ask of a phase, from the answer cache's counters before and after. */
    private static void perAsk(Map<String, Object> out, String phase, long[] before, int asks) throws Exception {
        long[] after = answers();
        long n = after[0] + after[1] - before[0] - before[1];
        out.put(phase + "_engine_asks_per", Math.round(100.0 * n / Math.max(1, asks)) / 100.0);
        out.put(phase + "_misses_per", Math.round(100.0 * (after[1] - before[1]) / Math.max(1, asks)) / 100.0);
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

    /** The ccr engine's Admin card: state, isolation, sessions, memory. */
    private static Map<String, Object> card() throws Exception {
        for (JsonNode e : server.get("/api/v1/admin/engines/host").path("engines")) {
            if (e.path("id").asText().equals("ccr")) {
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

    private static String scope(String s, String path) {
        return "/api/v1/risk/exposure/scope?scope=" + enc(s) + "&asOf=" + DATE + "&path=" + path;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String key(String path) {
        return path.equals("in-tree") ? "in_tree" : "engine";
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
