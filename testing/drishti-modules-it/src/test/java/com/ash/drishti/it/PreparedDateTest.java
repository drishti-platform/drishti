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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Prepared dates (R2 engine speed design step 9; ENGINE_SPEC 7.2 rules 4 and 5, 9.3 rules 4 and 5), scripted over the speed check's lake
 * ({@code -Dspeed.positions}, the same folder as {@link RiskSpeedCheckTest}): a fresh server (no kept image) asked at once for the engine
 * headline answers or says "preparing" (503, DRS-1024, busy) within 2.5 s; once the engine card shows the newest date ready the first ask is
 * timed; a restart with the kept images present is timed to ready and to its first ask; a new version of an input committed while asking
 * (var-limit loaded again) makes the date stale, each ask answered (noted) or told "preparing" within 2.5 s, until the successor is ready.
 * The figures go to {@code prepared-<tier>.json}; under {@code -Dspeed.gate=true} the gate's bounds are asserted.
 */
class PreparedDateTest {

    static final String DATE = RiskSpeedCheckTest.DATE;
    static final String CARD = "/api/v1/admin/engines/market-risk/lifecycle";

    @Test
    void aDateIsPreparedAtStartAndAfterALoadAndNoAskWaitsLonger() throws Exception {
        int positions = Integer.getInteger("speed.positions", 0);
        assumeTrue(positions > 0, "no speed check asked (-Dspeed.positions)");
        String jars = System.getProperty("drishti.risk.jars", "");
        assumeTrue(!jars.isBlank() && Files.isDirectory(Path.of(jars)), "no folder of Drishti Risk JARs given (-Ddrishti.risk.jars)");
        Path folder = Path.of(System.getProperty("drishti.g14.dir")).toAbsolutePath().resolve("speed-" + positions);
        Path lake = folder.resolve("lake");
        assumeTrue(Files.isDirectory(lake.resolve("delta")), "the speed check's lake is built by RiskSpeedCheckTest first: " + lake);
        boolean gate = Boolean.getBoolean("speed.gate");
        String tier = System.getProperty("sbs.tier", "").isBlank() ? "mapped" : System.getProperty("sbs.tier");
        Path work = folder.resolve("server-prepared-" + tier);
        RiskSideBySideTest.deleteTree(work);
        SideBySide.dropJars(Path.of(jars), work);
        Map<String, String> env = new LinkedHashMap<>();
        env.put("DRISHTI_VAR_SYSTEM_BASKETS_SEED", "");
        if (!tier.equals("mapped")) {
            env.put("DRISHTI_VAR_TIER", tier);
        }
        env.put("DRISHTI_MARKET_RISK_MEMORY_MB", System.getProperty("speed.engine-memory-mb", "3000"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("positions", positions);
        out.put("tier", tier);
        try (PackagedServer server = PackagedServer.start(work, lake, DATE, env)) {
            // 1. at once after the start: an answer or "preparing", never a longer wait
            Timed first = ask(server);
            out.put("ask_at_start_ms", first.ms());
            out.put("ask_at_start_status", first.status());
            // 2. the newest date ready on the engine card, then the first ask
            out.put("ready_after_start_ms", awaitReady(server, 600_000));
            Timed ready = ask(server);
            out.put("first_ask_after_ready_ms", ready.ms());
            out.put("first_ask_after_ready_status", ready.status());
            memory(server, out, "after_ready");
            // 3. a new version of an input committed while asking: stale, its successor prepared; each ask answered or "preparing"
            long t0 = System.nanoTime();
            Thread load = Thread.ofPlatform().start(() -> reload(folder, lake));
            List<Long> during = new ArrayList<>();
            List<Integer> statuses = new ArrayList<>();
            List<String> slow = new ArrayList<>();                     // each ask over 2.5 s: when it began (ms after the load began), how long
            while (load.isAlive()) {
                long at = (System.nanoTime() - t0) / 1_000_000;
                Timed t = ask(server);
                during.add(t.ms());
                statuses.add(t.status());
                if (t.ms() > 2_500) {
                    slow.add(at + "+" + t.ms());
                }
            }
            load.join();
            long committed = (System.nanoTime() - t0) / 1_000_000;
            out.put("reload_commit_ms", committed);
            long staleSeen = awaitState(server, "stale", 120_000);
            out.put("stale_seen_ms_after_commit", staleSeen);
            while (!readyNow(server)) {
                long at = (System.nanoTime() - t0) / 1_000_000;
                Timed t = ask(server);
                during.add(t.ms());
                statuses.add(t.status());
                if (t.ms() > 2_500) {
                    slow.add(at + "+" + t.ms());
                }
            }
            out.put("ready_after_commit_ms", (System.nanoTime() - t0) / 1_000_000 - committed);
            Timed after = ask(server);
            out.put("first_ask_after_reload_ms", after.ms());
            out.put("asks_during_reload", during.size());
            out.put("ask_during_reload_max_ms", during.stream().mapToLong(Long::longValue).max().orElse(0));
            out.put("ask_during_reload_statuses", statuses.stream().distinct().toList());
            out.put("asks_over_2500_ms", slow);
        }
        Files.copy(work.resolve("server.log"), work.resolve("server-first.log"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        // 4. a restart with the kept images present (the data folder kept; the config links made again): ready and the first ask
        try (var s = Files.list(work.resolve("config"))) {
            for (Path c : s.toList()) {
                if (Files.isSymbolicLink(c)) {
                    Files.delete(c);                                   // the link only, never what it points to
                } else {
                    RiskSideBySideTest.deleteTree(c);
                }
            }
        }
        try (PackagedServer again = PackagedServer.start(work, lake, DATE, env)) {
            out.put("ready_after_restart_ms", awaitReady(again, 600_000));
            Timed t = ask(again);
            out.put("first_ask_after_restart_ms", t.ms());
            memory(again, out, "after_restart");
            for (JsonNode c : again.get("/api/v1/admin/caches").path("caches")) {
                if (c.path("name").asText().equals("engines.kept")) {
                    out.put("kept_images", c.path("entries").asLong());
                    out.put("kept_bytes", c.path("sizeBytes").asLong());
                }
            }
        }
        String line = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(out);
        System.out.println("prepared dates: " + line);
        Files.writeString(folder.resolve("prepared-" + tier + ".json"), line + "\n");
        assertThat((long) out.get("ask_at_start_ms")).as("an ask at start: answered or told preparing within 2.5 s").isLessThanOrEqualTo(2_500);
        assertThat((long) out.get("ask_during_reload_max_ms")).as("an ask while a new version is prepared: within 2.5 s").isLessThanOrEqualTo(2_500);
        if (gate) {
            assertThat((long) out.get("first_ask_after_ready_ms")).as("first ask after a start").isLessThanOrEqualTo(1_000);
            assertThat((long) out.get("first_ask_after_reload_ms")).as("first ask after a load").isLessThanOrEqualTo(1_000);
            assertThat((long) out.get("ready_after_commit_ms")).as("ready after the commit's quiet period").isLessThanOrEqualTo(15_000 + 5_000);
            assertThat((long) out.get("first_ask_after_restart_ms")).as("reopen with the image present").isLessThanOrEqualTo(1_000);
        }
    }

    record Timed(long ms, int status) {}

    /** The engine's own count of what its sessions hold (the card), the server's resident memory, and the host's answer cache hits. */
    static void memory(PackagedServer s, Map<String, Object> out, String when) throws Exception {
        out.put("engine_memory_mb_" + when, s.get(CARD).path("memoryBytes").asLong() / (1024 * 1024));
        for (String l : Files.readAllLines(Path.of("/proc/" + s.pid() + "/status"))) {
            if (l.startsWith("VmRSS:")) {
                out.put("rss_mb_" + when, Long.parseLong(l.replaceAll("[^0-9]", "")) / 1024);
            }
        }
        for (JsonNode c : s.get("/api/v1/admin/caches").path("caches")) {
            if (c.path("name").asText().equals("engines.answers")) {
                out.put("answer_cache_" + when, c.toString());
            }
        }
    }

    /** The engine headline of the bank: 200, or 503 DRS-1024 (preparing, busy) is an outcome; anything else fails the run. */
    static Timed ask(PackagedServer s) throws Exception {
        long t0 = System.nanoTime();
        PackagedServer.Answer a = s.send("GET", "/api/v1/risk/var/scope?scope=" + URLEncoder.encode("NODE:MGMT~BANK", StandardCharsets.UTF_8)
                + "&asOf=" + DATE + "&path=engine");
        long ms = (System.nanoTime() - t0) / 1_000_000;
        if (a.status() != 200) {
            assertThat(a.status()).as(a.text()).isEqualTo(503);
            assertThat(a.text()).as("a 503 is the engine preparing").contains("DRS-1024");
        }
        return new Timed(ms, a.status());
    }

    /** True when the card shows the date's session ready and no preparation running. */
    static boolean readyNow(PackagedServer s) throws Exception {
        JsonNode card = s.get(CARD);
        if (card.path("preparing").size() > 0) {
            return false;
        }
        for (JsonNode st : card.path("sessionStates")) {
            if (st.path("snapshot").asText().contains(DATE) && st.path("state").asText().equals("ready")) {
                return true;
            }
        }
        return false;
    }

    static long awaitReady(PackagedServer s, long limitMs) throws Exception {
        long t0 = System.nanoTime();
        while (!readyNow(s)) {
            assertThat((System.nanoTime() - t0) / 1_000_000).as("the date ready on the engine card").isLessThan(limitMs);
            Thread.sleep(100);
        }
        return (System.nanoTime() - t0) / 1_000_000;
    }

    /** Milliseconds until a session of the date shows {@code state} (or a preparation runs), at most {@code limitMs}. */
    static long awaitState(PackagedServer s, String state, long limitMs) throws Exception {
        long t0 = System.nanoTime();
        while (true) {
            JsonNode card = s.get(CARD);
            if (card.path("preparing").size() > 0) {
                break;
            }
            boolean seen = false;
            for (JsonNode st : card.path("sessionStates")) {
                seen |= st.path("state").asText().equals(state) || st.path("state").asText().equals("opening");
            }
            if (seen) {
                break;
            }
            assertThat((System.nanoTime() - t0) / 1_000_000).as("the date stale on the engine card after the commit").isLessThan(limitMs);
            Thread.sleep(100);
        }
        return (System.nanoTime() - t0) / 1_000_000;
    }

    /** The date's var-limit loaded again (a new version: the lake's commit), as the speed check's lake was built. */
    static void reload(Path folder, Path lake) {
        try {
            ProcessBuilder pb = new ProcessBuilder(PackagedServer.lakePython(), PackagedServer.REPO.resolve("tools/drishti.py").toString(), "lake", "build",
                    "--from", folder.resolve("vargen").resolve("var-limit").toString(), "--pack",
                    PackagedServer.REPO.resolve("modules/banking/packs/market-risk").toString(), "--connector", "risk-store", "--kind", "var-limit",
                    "--key", "LIMIT_ID", "--mode", "overwrite-dates", "--lake", lake.resolve("delta").toString(), "--engine", "java", "-q")
                    .directory(PackagedServer.REPO.toFile()).redirectErrorStream(true).redirectOutput(folder.resolve("reload-var-limit.log").toFile());
            pb.environment().put("DRISHTI_DATA_DIR", folder.resolve("data").toString());
            pb.environment().put("DRISHTI_TMP_DIR", folder.resolve("tmp").toString());
            Process p = pb.start();
            if (!p.waitFor(30, java.util.concurrent.TimeUnit.MINUTES) || p.exitValue() != 0) {
                p.destroyForcibly();
                throw new IllegalStateException("the reload failed: see " + folder.resolve("reload-var-limit.log"));
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
