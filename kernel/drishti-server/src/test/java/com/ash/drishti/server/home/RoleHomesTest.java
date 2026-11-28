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
package com.ash.drishti.server.home;

import com.ash.drishti.kernel.api.home.HomeData;
import com.ash.drishti.kernel.api.home.MeasureSource;
import com.ash.drishti.kernel.api.home.HomeDefinition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ash.drishti.kernel.api.security.Principal;
import com.ash.drishti.engine.time.BusinessDateProperties;
import com.ash.drishti.engine.time.BusinessDates;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * A Home test per role (docs/architecture/ROLE_HOME_SUTRAS.md 5): the real files of {@code config/homes} load without fault; each role is
 * offered its Home and none it should not have; a Home needs its modules and its mnemonics; the Sutra is built from library fragments only
 * and reads only tables the server serves; a locked Home is compiled with {@code user-can-arrange: false}; the tables answer under a fake
 * engine and give nothing, not an error, when an engine is off. No Spring context.
 */
class RoleHomesTest {

    static final Map<String, List<String>> ROLES = Map.ofEntries(Map.entry("risk-manager", List.of("risk-manager")), Map.entry("trader", List.of("trader")),
            Map.entry("risk-officer", List.of("risk-officer")), Map.entry("credit-officer", List.of("credit-officer")),
            Map.entry("operations", List.of("operations")), Map.entry("quant", List.of("quant")), Map.entry("economist", List.of("economist")), Map.entry("genomicist", List.of("genomicist")),
            Map.entry("market-data-reader", List.of("market-data")));

    /** The tables the modules serve to their own Homes (genomics: GenomicsHome.tables; market-data: MarketHome). */
    static final Set<String> MODULE_TABLES = Set.of("genomics_gwas_top", "genomics_manhattan", "genomics_clinvar", "genomics_loads", "genomics_needs",
            "market_tiles", "market_curve", "market_moves", "market_freshness");

    @TempDir Path data;

    static Path homes() {
        return Path.of(System.getProperty("lake.repo.root")).resolve("config/homes");   // the repository's root, absolute (TestPathsGuardTest)
    }

    HomeCatalogue catalogue() {
        return new HomeCatalogue(new HomeProperties(true, homes().toString(), data.toString(), ROLES, "17:00", 30));
    }

    @Test
    void everyHomeFileLoads() {
        HomeCatalogue c = catalogue();
        assertEquals(Map.of(), c.faults());
        assertEquals(Set.of("risk-manager", "trader", "risk-officer", "credit-officer", "operations", "quant", "economist", "genomicist", "market-data"),
                c.all().stream().map(HomeDefinition::name).collect(java.util.stream.Collectors.toSet()));
    }

    @ParameterizedTest
    @CsvSource({"risk-manager,risk-manager", "trader,trader", "risk-officer,risk-officer", "credit-officer,credit-officer", "operations,operations",
            "quant,quant", "economist,economist", "genomicist,genomicist", "market-data-reader,market-data"})
    void aRoleOpensItsHomeAndOnlyItsHome(String role, String home) {
        HomeCatalogue c = catalogue();
        List<HomeDefinition> offered = c.offered(new Principal("u", List.of(role)), false, m -> true, m -> true);
        assertEquals(List.of(home), offered.stream().map(HomeDefinition::name).toList());
        assertEquals(home, c.start(offered, "").orElseThrow().name());
        assertEquals(home, c.start(offered, "no-such-home").orElseThrow().name(), "a choice the person is not offered falls back to the role's Home");
    }

    @Test
    void anAdministratorOpensEveryHomeAndAnUnknownRoleNone() {
        HomeCatalogue c = catalogue();
        assertEquals(9, c.offered(new Principal("a", List.of("admin")), true, m -> true, m -> true).size());
        assertTrue(c.offered(new Principal("x", List.of("viewer")), false, m -> true, m -> true).isEmpty());
    }

    @Test
    void aHomeNeedsItsModules() {
        HomeCatalogue c = catalogue();
        assertTrue(c.offered(new Principal("r", List.of("risk-manager")), false, m -> !m.equals("banking"), m -> true).isEmpty());
        assertEquals(1, c.offered(new Principal("e", List.of("economist")), false, m -> m.equals("external-data"), m -> true).size());
    }

    @ParameterizedTest
    @CsvSource({"risk-manager", "trader", "risk-officer", "credit-officer", "operations", "quant", "economist", "genomicist", "market-data"})
    void theSutraIsFragmentsOnlyAndReadsServedTables(String home) {
        HomeDefinition d = catalogue().find(home).orElseThrow();
        String text = d.sutraText();
        assertFalse(Pattern.compile("(?m)^\\s*-?\\s*kind:").matcher(text).find(), "no panel is written in a Home: " + home);
        Matcher use = Pattern.compile("use:\\s*([a-z-]+)/").matcher(text);
        int n = 0;
        while (use.find()) {
            n++;
            assertTrue(Set.of("core", "finance", "economy").contains(use.group(1)), "library " + use.group(1));
        }
        assertTrue(n >= 3, "a Home is built of fragments");
        Matcher table = Pattern.compile("table:\\s*(home_[a-z_]+)").matcher(text);   // the server's tables; a module's own (genomics_*, market_*) are checked below
        while (table.find()) {
            String name = table.group(1);
            assertTrue(HomeTables.BUILT_IN.contains(name), home + " reads " + name + ", which no server table serves");
        }
        Matcher own = Pattern.compile("table:\\s*((?:genomics|market)_[a-z_]+)").matcher(text);
        while (own.find()) {
            assertTrue(MODULE_TABLES.contains(own.group(1)), home + " reads " + own.group(1) + ", which no module serves");
        }
        assertTrue(text.contains("user-can-arrange: true"));
        assertEquals("home-" + home, d.sutraName());
    }

    @Test
    void aLockedHomeIsCompiledNotArrangeable() throws IOException {
        Path dir = Files.createDirectories(data.resolve("homes/x"));
        Files.writeString(dir.resolve("one.sutra.yaml"), "rachana: 1.2\nsutra: home-one\nlayout:\n  user-can-arrange: true\n");
        Files.writeString(dir.resolve("one.home.yaml"), "home: one\ntitle: One\nsutra: one.sutra.yaml\narrange: locked\n");
        Files.writeString(dir.resolve("bad.home.yaml"), "home: bad\ntitle: Bad\nsutra: gone.sutra.yaml\n");
        HomeCatalogue c = new HomeCatalogue(new HomeProperties(true, data.resolve("homes").toString(), data.toString(), Map.of("r", List.of("one")), null, null));
        HomeDefinition d = c.find("one").orElseThrow();
        assertFalse(c.arrangementApplies(d));
        assertTrue(c.sutraFor(d).contains("user-can-arrange: false"));
        assertEquals(1, c.faults().size(), "a Home with a fault is left out and said, never half-loaded");
    }

    @Test
    void relativeFoldersAreRefused() {
        try {
            new HomeProperties(true, "config/homes", null, null, null, null);
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("not an absolute path"));
            return;
        }
        throw new AssertionError("a relative folder must be refused");
    }

    /** A fake engine: desk VaR with a limit, and a KPI. */
    static class FakeVar implements MeasureSource {
        boolean on = true;
        Double yesterday;                                   // the KPI's figure of 2026-11-19, none when null

        public String engine() {
            return "var";
        }

        public List<LimitTable> limitTables() {
            return List.of(new LimitTable("home_desk_limits", "VAR", "desk", "DESK"));
        }

        public boolean on() {
            return on;
        }

        public OptionalDouble kpi(Principal p, String m, Map<String, Object> a, LocalDate d) {
            if (!m.equals("VAR")) {
                return OptionalDouble.empty();
            }
            return d.equals(LocalDate.parse("2026-11-20")) ? OptionalDouble.of(5_000_000) : yesterday == null ? OptionalDouble.empty() : OptionalDouble.of(yesterday);
        }

        public List<HomeData.MeasureValue> values(Principal p, String m, String kind, LocalDate d) {
            return m.equals("LIM_USE") ? List.of(new HomeData.MeasureValue("desk", "DESK-A", "LIM_USE", 0.97), new HomeData.MeasureValue("desk", "DESK-B", "LIM_USE", 0.83),
                    new HomeData.MeasureValue("desk", "DESK-C", "LIM_USE", 0.2)) : List.of();
        }

        public List<HomeData.LimitRow> limits(Principal p, String m, String kind, LocalDate d) {
            return List.of(new HomeData.LimitRow("DESK-A", 970, 1000.0), new HomeData.LimitRow("DESK-B", 100, null));
        }
    }

    HomeTables tables(FakeVar var, HomeStore store) {
        BusinessDates dates = new BusinessDates(new BusinessDateProperties(null, "UTC", null, "2026-11-20"), Clock.fixed(Instant.parse("2026-11-20T18:00:00Z"), ZoneOffset.UTC));
        return new HomeTables(new HomeProperties(true, null, data.toString(), null, null, null), store, new HomeClock(dates, Clock.fixed(Instant.parse("2026-11-20T18:00:00Z"), ZoneOffset.UTC)),
                List.of(var), List.of(), ChangeEngineHost.NOT_HOSTED, List::of, (who, kind) -> true);
    }

    @Test
    void theRiskManagersTablesAnswerFromTheEngineAndStaySilentWhenItIsOff() {
        HomeStore store = new HomeStore(data, 30);
        FakeVar var = new FakeVar();
        HomeTables t = tables(var, store);
        HomeDefinition d = catalogue().find("risk-manager").orElseThrow();
        Principal p = new Principal("u", List.of("risk-manager"));

        List<Map<String, Object>> exc = t.rows("home_exceptions", p, d).orElseThrow();
        assertEquals(2, exc.size(), "95% is high, 80% medium, 20% nothing; one row a subject");
        assertEquals("high", exc.get(0).get("SEVERITY"));
        assertEquals("VAR DESK-A", exc.get(0).get("COMMAND"));
        assertTrue(String.valueOf(exc.get(0).get("MESSAGE")).contains("97%"));

        List<Map<String, Object>> kpis = t.rows("home_kpis", p, d).orElseThrow();
        assertEquals(1, kpis.size(), "only the figures the engine answers");
        assertEquals("Firm VaR", kpis.get(0).get("LABEL"));
        assertEquals("no earlier figure", kpis.get(0).get("CAPTION"));

        var.yesterday = 4_000_000.0;
        assertEquals(1_000_000.0, (Double) t.rows("home_kpis", p, d).orElseThrow().get(0).get("DELTA"));

        List<Map<String, Object>> limits = t.rows("home_desk_limits", p, d).orElseThrow();
        assertEquals("DESK-A", limits.get(0).get("DESK"), "the nearest to its limit first");
        assertEquals(0.97, (Double) limits.get(0).get("USE"), 1e-9, "the server computes the use (the console only draws it)");
        assertEquals("bad", limits.get(0).get("TONE"));
        assertEquals(null, limits.get(1).get("USE"), "no limit: no use, no tone");
        assertEquals(null, limits.get(1).get("TONE"));
        assertTrue(t.rows("home_changes", p, d).orElseThrow().isEmpty(), "no changes engine is hosted yet: no rows, said by /status");
        assertEquals("yesterday 17:00", t.rows("home_meta", p, d).orElseThrow().get(0).get("SINCE"));
        assertTrue(t.rows("home_nonsense", p, d).isEmpty());

        var.on = false;
        assertTrue(t.rows("home_kpis", p, d).orElseThrow().isEmpty());
        assertTrue(t.rows("home_exceptions", p, d).orElseThrow().isEmpty());
        assertTrue(t.rows("home_desk_limits", p, d).orElseThrow().isEmpty());
    }

    /** A loaded module's MeasureSource and HomeTable reach the Homes through the bridge's live collection (a product bug before: the panels were empty). */
    @Test
    void aLoadedModulesSourceAndTableAreReadLive() {
        HomeStore store = new HomeStore(data, 30);
        HomeTables t = tables(new FakeVar() {
            public String engine() {
                return "other";
            }
        }, store);
        HomeDefinition d = catalogue().find("risk-manager").orElseThrow();
        Principal p = new Principal("u", List.of("risk-manager"));
        assertTrue(t.rows("home_kpis", p, d).orElseThrow().isEmpty(), "no source of the engine var yet");
        Runnable w1 = com.ash.drishti.common.Extensions.add(MeasureSource.class, "test-module", new FakeVar());
        Runnable w2 = com.ash.drishti.common.Extensions.add(com.ash.drishti.kernel.api.home.HomeTable.class, "test-module", new com.ash.drishti.kernel.api.home.HomeTable() {
            public String name() {
                return "home_test_live";
            }

            public List<Map<String, Object>> rows(Context c) {
                return List.of(Map.of("X", 1));
            }
        });
        try {
            assertEquals(1, t.rows("home_kpis", p, d).orElseThrow().size(), "the module's source answers");
            assertEquals(1, t.rows("home_test_live", p, d).orElseThrow().size(), "the module's table answers");
            assertTrue(t.names().contains("home_test_live"));
        } finally {
            w1.run();
            w2.run();
        }
        assertTrue(t.rows("home_kpis", p, d).orElseThrow().isEmpty(), "withdrawn with the module");
    }

    /** The homes folder may be a symlink (a packaged server links each committed config part): it is walked, no setting needed. */
    @Test
    void aSymlinkedHomesFolderIsWalked() throws IOException {
        Path link = data.resolve("homes-link");
        Files.createSymbolicLink(link, homes());
        HomeCatalogue c = new HomeCatalogue(new HomeProperties(true, link.toString(), data.toString(), ROLES, "17:00", 30));
        assertEquals(Map.of(), c.faults());
        assertEquals(9, c.all().size());
    }

    @Test
    void theSnapshotIsTakenOnceAndTheEventsAndDriversAreKept() {
        HomeStore store = new HomeStore(data, 30);
        LocalDate day = LocalDate.parse("2026-11-19");
        store.putSnapshot(day, Instant.parse("2026-11-19T22:00:00Z"), List.of(new HomeData.MeasureValue("desk", "DESK-A", "VAR", 1.5)));
        assertTrue(store.hasSnapshot(day));
        assertEquals(1.5, store.snapshot(day).get(0).value());
        store.addEvent(day, new HomeData.Event("feed", "p/k", "load", Instant.parse("2026-11-19T23:00:00Z"), "loaded"));
        assertEquals(1, store.events(day, Instant.parse("2026-11-19T22:00:00Z")).size());
        assertEquals(0, store.events(day, Instant.parse("2026-11-20T00:00:00Z")).size());
        store.putDrivers(day, List.of(new HomeData.Driver("desk", "DESK-A", "VAR", "market-data", 0.5)));
        assertEquals("market-data", store.drivers(day).get(0).driver());
        store.putSnapshot(day.plusDays(40), Instant.parse("2026-12-29T22:00:00Z"), List.of());
        assertFalse(store.hasSnapshot(day), "days beyond keep-days are removed");
    }

    /**
     * A fake engine that answers as the person: the firm (every row) and a restricted trader (their own desk) get different figures on every
     * day, as an engine applying row rules does. Firm: VaR 5M today, 4M before; desk VaR by desk A 3M (2M before) and B 2M. Restricted: only
     * desk A's own books, 1M today, 0.9M before.
     */
    static final class ScopedVar implements MeasureSource {
        static final LocalDate TODAY = LocalDate.parse("2026-11-20");

        public String engine() {
            return "var";
        }

        public boolean on() {
            return true;
        }

        public List<LimitTable> limitTables() {
            return List.of(new LimitTable("home_desk_limits", "VAR", "desk", "DESK"));
        }

        static boolean full(Principal p) {
            return p.roles().contains("admin");
        }

        public OptionalDouble kpi(Principal p, String m, Map<String, Object> a, LocalDate d) {
            if (!m.equals("VAR") && !m.equals("PNL_DAY")) {
                return OptionalDouble.empty();
            }
            return OptionalDouble.of(full(p) ? (d.equals(TODAY) ? 5_000_000 : 4_000_000) : (d.equals(TODAY) ? 1_000_000 : 900_000));
        }

        public List<HomeData.MeasureValue> values(Principal p, String m, String kind, LocalDate d) {
            if (!full(p)) {
                return List.of(new HomeData.MeasureValue("desk", "DESK-A", m, d.equals(TODAY) ? 1_000_000 : 900_000));
            }
            return List.of(new HomeData.MeasureValue("desk", "DESK-A", m, d.equals(TODAY) ? 3_000_000 : 2_000_000), new HomeData.MeasureValue("desk", "DESK-B", m, 2_000_000));
        }

        public List<HomeData.LimitRow> limits(Principal p, String m, String kind, LocalDate d) {
            return values(p, m, kind, d).stream().map(v -> new HomeData.LimitRow(v.subject(), v.value(), 10_000_000.0)).toList();
        }
    }

    /**
     * Session isolation (pre-gate B1): a restricted trader and a full-access person open Homes with the same KPIs; the firm snapshot has been
     * taken. Each gets their own figures, deltas and history, and the restricted person never a firm figure (no 4M or 5M, no desk B).
     */
    @Test
    void eachPersonGetsTheirOwnEarlierFiguresNeverTheFirms() {
        HomeStore store = new HomeStore(data, 30);
        ScopedVar var = new ScopedVar();
        BusinessDates dates = new BusinessDates(new BusinessDateProperties(null, "UTC", null, "2026-11-20"), Clock.fixed(Instant.parse("2026-11-20T18:00:00Z"), ZoneOffset.UTC));
        HomeClock clock = new HomeClock(dates, Clock.fixed(Instant.parse("2026-11-20T18:00:00Z"), ZoneOffset.UTC));
        HomeCatalogue cat = catalogue();
        new MeasureSnapshotJob(cat, store, clock, List.of(var)).run(LocalDate.parse("2026-11-19"), java.time.LocalTime.parse("17:00"));
        assertTrue(store.hasSnapshot(LocalDate.parse("2026-11-19")), "the firm snapshot exists, so serving it would show here");
        HomeTables t = new HomeTables(new HomeProperties(true, null, data.toString(), null, null, null), store, clock, List.of(var), List.of(),
                ChangeEngineHost.NOT_HOSTED, List::of, (who, kind) -> true);
        HomeDefinition trader = cat.find("trader").orElseThrow(), manager = cat.find("risk-manager").orElseThrow();
        Principal restricted = new Principal("trader-a", List.of("trader")), full = new Principal("chief", List.of("admin"));

        for (HomeDefinition h : List.of(trader, manager)) {
            for (Map<String, Object> k : t.rows("home_kpis", restricted, h).orElseThrow()) {
                assertEquals(1_000_000.0, k.get("VALUE"), h.name() + " " + k);
                assertEquals(100_000.0, (Double) k.get("DELTA"), 1e-6, "the restricted person's own move, not value minus the firm's: " + k);
            }
            for (Map<String, Object> k : t.rows("home_kpis", full, h).orElseThrow()) {
                assertEquals(1_000_000.0, (Double) k.get("DELTA"), 1e-6, "the full person's own move: " + k);
            }
            List<Map<String, Object>> hist = t.rows("home_kpi_history", restricted, h).orElseThrow();
            assertFalse(hist.isEmpty(), h.name());
            assertTrue(hist.stream().allMatch(r -> r.get("VALUE").equals(900_000.0)), "every point as the restricted person sees it: " + hist);
            assertTrue(t.rows("home_kpi_history", full, h).orElseThrow().stream().allMatch(r -> r.get("VALUE").equals(4_000_000.0)));
        }

        List<Map<String, Object>> mine = t.rows("home_desk_limits", restricted, manager).orElseThrow();
        assertEquals(1, mine.size(), "only the restricted person's desk");
        assertEquals(100_000.0, (Double) mine.get(0).get("CHANGE"), 1e-6, "their own change, not against the firm's desk figure");
        assertEquals(1_000_000.0, (Double) t.rows("home_desk_limits", full, manager).orElseThrow().get(0).get("CHANGE"), 1e-6);
    }

    /** Pre-gate S4: the load states and feed events a person sees are those of the kinds they may open, never another pack's. */
    @Test
    void loadStatesAndFeedEventsAreThePersonsPacksOnly() {
        HomeStore store = new HomeStore(data, 30);
        store.addEvent(LocalDate.parse("2026-11-20"), new HomeData.Event("feed", "market-data/curve", "load", Instant.parse("2026-11-20T09:00:00Z"), "curve loaded"));
        store.addEvent(LocalDate.parse("2026-11-20"), new HomeData.Event("feed", "genomics/variant", "load", Instant.parse("2026-11-20T09:01:00Z"), "variant loaded"));
        BusinessDates dates = new BusinessDates(new BusinessDateProperties(null, "UTC", null, "2026-11-20"), Clock.fixed(Instant.parse("2026-11-20T18:00:00Z"), ZoneOffset.UTC));
        List<com.ash.drishti.server.loads.ExpectationService.State> states = List.of(
                new com.ash.drishti.server.loads.ExpectationService.State("market-data", "curve", LocalDate.parse("2026-11-20"), "08:00", "UTC", null, "LATE", null, null),
                new com.ash.drishti.server.loads.ExpectationService.State("genomics", "variant", LocalDate.parse("2026-11-20"), "08:00", "UTC", null, "FAILED", null, null));
        HomeTables t = new HomeTables(new HomeProperties(true, null, data.toString(), null, null, null), store,
                new HomeClock(dates, Clock.fixed(Instant.parse("2026-11-20T18:00:00Z"), ZoneOffset.UTC)), List.of(new FakeVar()), List.of(), ChangeEngineHost.NOT_HOSTED,
                () -> states, (who, kind) -> who.roles().contains("admin") || kind.equals("curve"));
        HomeDefinition d = catalogue().find("risk-manager").orElseThrow();
        Principal market = new Principal("m", List.of("market-data-reader")), admin = new Principal("a", List.of("admin"));
        assertEquals(List.of("curve"), t.rows("home_loads", market, d).orElseThrow().stream().map(r -> r.get("KIND")).toList());
        assertEquals(2, t.rows("home_loads", admin, d).orElseThrow().size());
        List<String> digest = t.rows("home_digest", market, d, Instant.parse("2026-11-20T00:00:00Z")).orElseThrow().stream().map(r -> String.valueOf(r.get("TEXT"))).toList();
        assertTrue(digest.contains("curve loaded") && !digest.contains("variant loaded"), digest.toString());
    }

    /** A fake engine whose desk VaR moved: DESK-A now 150 (was 100), DESK-B unchanged. */
    static final class MovingVar implements MeasureSource {
        public String engine() {
            return "var";
        }

        public boolean on() {
            return true;
        }

        public OptionalDouble kpi(Principal p, String m, Map<String, Object> a, LocalDate d) {
            return OptionalDouble.empty();
        }

        public List<HomeData.MeasureValue> values(Principal p, String m, String kind, LocalDate d) {
            return List.of(new HomeData.MeasureValue("desk", "DESK-A", "VAR", d.equals(LocalDate.parse("2026-11-20")) ? 150 : 100),
                    new HomeData.MeasureValue("desk", "DESK-B", "VAR", 80));
        }

        public List<HomeData.LimitRow> limits(Principal p, String m, String kind, LocalDate d) {
            return List.of();
        }
    }

    /** A module's own table, served to its own Home only. */
    record OwnTable(String name, List<String> homes) implements com.ash.drishti.kernel.api.home.HomeTable {
        public List<Map<String, Object>> rows(Context c) {
            return List.of(Map.of("SINCE", String.valueOf(c.since())));
        }
    }

    @Test
    void theDigestReadsFromTheGivenTimeAndAModulesTableIsItsHomesOnly() throws IOException {
        Path dir = Files.createDirectories(data.resolve("homes/x"));
        Files.writeString(dir.resolve("one.sutra.yaml"), "rachana: 1.2\nsutra: home-one\n");
        Files.writeString(dir.resolve("one.home.yaml"), "home: one\ntitle: One\nsutra: one.sutra.yaml\nmeasures: one.measures.yaml\n");
        Files.writeString(dir.resolve("one.measures.yaml"), "home: one\nfollow:\n  - { measure: VAR, engine: var, subjects: desk, min-abs: 10, min-rel: 0 }\nevents: [ load ]\n");
        Files.writeString(dir.resolve("two.sutra.yaml"), "rachana: 1.2\nsutra: home-two\n");
        Files.writeString(dir.resolve("two.home.yaml"), "home: two\ntitle: Two\nsutra: two.sutra.yaml\n");
        HomeCatalogue c = new HomeCatalogue(new HomeProperties(true, data.resolve("homes").toString(), data.toString(), Map.of("r", List.of("one", "two")), null, null));
        HomeStore store = new HomeStore(data, 30);
        store.addEvent(LocalDate.parse("2026-11-19"), new HomeData.Event("feed", "p/old", "load", Instant.parse("2026-11-19T20:00:00Z"), "p/old loaded"));
        store.addEvent(LocalDate.parse("2026-11-19"), new HomeData.Event("feed", "p/new", "load", Instant.parse("2026-11-19T23:00:00Z"), "p/new loaded"));
        store.addEvent(LocalDate.parse("2026-11-20"), new HomeData.Event("feed", "p/today", "load", Instant.parse("2026-11-20T09:00:00Z"), "p/today loaded"));
        store.addEvent(LocalDate.parse("2026-11-20"), new HomeData.Event("desk", "DESK-Z", "limit-state", Instant.parse("2026-11-20T10:00:00Z"), "not a kind of this Home"));
        BusinessDates dates = new BusinessDates(new BusinessDateProperties(null, "UTC", null, "2026-11-20"), Clock.fixed(Instant.parse("2026-11-20T18:00:00Z"), ZoneOffset.UTC));
        HomeTables t = new HomeTables(new HomeProperties(true, null, data.toString(), null, null, null), store,
                new HomeClock(dates, Clock.fixed(Instant.parse("2026-11-20T18:00:00Z"), ZoneOffset.UTC)), List.of(new MovingVar()),
                List.of(new OwnTable("module_table", List.of("one"))), ChangeEngineHost.NOT_HOSTED, List::of, (who, kind) -> true);
        Principal p = new Principal("u", List.of("r"));
        HomeDefinition one = c.find("one").orElseThrow(), two = c.find("two").orElseThrow();

        List<String> texts = t.rows("home_digest", p, one, Instant.parse("2026-11-20T00:00:00Z")).orElseThrow().stream().map(r -> String.valueOf(r.get("TEXT"))).toList();
        assertTrue(texts.contains("p/today loaded") && !texts.contains("p/new loaded") && !texts.contains("p/old loaded"), "only what happened since the visit: " + texts);
        assertTrue(texts.stream().anyMatch(x -> x.equals("DESK-A VAR 100 to 150 (+50)")) && texts.stream().noneMatch(x -> x.contains("DESK-B") || x.contains("not a kind")),
                "a followed measure that moved, not one that did not, and no event of another kind: " + texts);
        List<String> since = t.rows("home_digest", p, one, Instant.parse("2026-11-19T21:00:00Z")).orElseThrow().stream().map(r -> String.valueOf(r.get("TEXT"))).toList();
        assertTrue(since.contains("p/new loaded") && since.contains("p/today loaded") && !since.contains("p/old loaded"));
        assertEquals(3, t.rows("home_digest", p, one).orElseThrow().stream().filter(r -> String.valueOf(r.get("TEXT")).endsWith("loaded")).count(),
                "with no time the Home's own cut-off (yesterday 17:00) is the start");

        assertTrue(t.rows("module_table", p, one, null).isPresent());
        assertTrue(t.rows("module_table", p, two, null).isEmpty(), "a module's table is served to its own Homes only");
        assertTrue(t.names().contains("module_table") && t.names().contains("home_digest"));
    }

    /** The module a Home names exists: a typo or a module that was renamed (economy became external-data) fails here, not in a person's Home. */
    @Test
    void everyModuleAHomeNamesExists() throws IOException {
        Path modules = homes().getParent().getParent().resolve("modules");
        Set<String> ids = new java.util.TreeSet<>();
        try (var s = Files.list(modules)) {
            for (Path m : s.filter(Files::isDirectory).toList()) {
                Path manifest = m.resolve("module.yaml");
                if (Files.isRegularFile(manifest)) {
                    Matcher id = Pattern.compile("(?m)^id:\\s*(\\S+)").matcher(Files.readString(manifest));
                    if (id.find()) {
                        ids.add(id.group(1));
                    }
                }
            }
        }
        assertFalse(ids.isEmpty(), "no module manifest was found under " + modules);
        for (HomeDefinition d : catalogue().all()) {
            for (String m : d.modules()) {
                assertTrue(ids.contains(m), "the Home " + d.name() + " names the module " + m + ", which does not exist (modules: " + ids + ")");
            }
        }
    }

    /** One way: every Home shows "Since you were here" and its data loads as panels of its own Sutra, and no Home is drawn any other way. */
    @ParameterizedTest
    @CsvSource({"risk-manager", "trader", "risk-officer", "credit-officer", "operations", "quant", "economist", "genomicist", "market-data"})
    void everyHomeCarriesTheVisitAndLoadsPanels(String home) {
        String text = catalogue().find(home).orElseThrow().sutraText();
        assertTrue(text.contains("table: home_digest") && text.contains("as: visit"), home + " has no \"Since you were here\" panel");
        assertTrue(text.contains("table: home_loads"), home + " has no data-loads panel");
        Map<String, String> aliases = HomeComposer.aliases(text);
        assertTrue(aliases.containsKey("digest") && aliases.containsKey("loads"), aliases.toString());
        assertFalse(HomeComposer.stripData(text).contains("table:"), "the data: block is read by the server and left out of the Sutra it binds");
    }

    /** A fake of what is the person's own: alerts and pins by user, one approval each way. */
    static HomePersonal personal() {
        return new HomePersonal() {
            public List<Map<String, Object>> alerts(Principal p) {
                return switch (p.user()) {
                    case "ann" -> List.of(Map.of("id", "a1", "rule", "r1", "title", "VaR breach", "severity", "critical", "stage", "raised", "message", "99% of limit"),
                            Map.of("id", "a2", "rule", "r2", "title", "Old", "severity", "low", "stage", "resolved"),
                            Map.of("id", "a3", "rule", "r3", "title", "Asleep", "severity", "high", "stage", "raised", "snoozedUntil", "2026-11-21T00:00:00Z"));
                    case "bob" -> List.of(Map.of("id", "b1", "rule", "r9", "title", "Bob only", "severity", "medium", "stage", "raised"));
                    default -> List.of();
                };
            }

            public List<com.ash.drishti.server.approvals.ApprovalQueue.Entry> inbox(Principal p) {
                var mine = new com.ash.drishti.kernel.api.approvals.ApprovalSource.Item("sutra", "s1", "Change X", "bob", Instant.parse("2026-11-20T10:00:00Z"), "pending", "", null,
                        true, false, false, "/approvals", null, null, null);
                var own = new com.ash.drishti.kernel.api.approvals.ApprovalSource.Item("sutra", "s2", "My own", "ann", Instant.parse("2026-11-20T10:00:00Z"), "pending", "", null,
                        false, true, true, "/approvals", null, null, null);
                return p.user().equals("ann") ? List.of(new com.ash.drishti.server.approvals.ApprovalQueue.Entry(mine, "Sutra", "screens", false, 0),
                        new com.ash.drishti.server.approvals.ApprovalQueue.Entry(own, "Sutra", "screens", false, 0)) : List.of();
            }

            public List<Map<String, Object>> pinned(Principal p) {
                return p.user().equals("ann") ? List.of(Map.of("kind", "trade", "id", "T-1"), Map.of("kind", "desk", "id", "DESK-A")) : List.of();
            }
        };
    }

    @Test
    void needsYouAndPinnedAreTheCallersOwnAndNeverAnotherPersons() {
        BusinessDates dates = new BusinessDates(new BusinessDateProperties(null, "UTC", null, "2026-11-20"), Clock.fixed(Instant.parse("2026-11-20T18:00:00Z"), ZoneOffset.UTC));
        HomeTables t = new HomeTables(new HomeProperties(true, null, data.toString(), null, null, null), new HomeStore(data, 30),
                new HomeClock(dates, Clock.fixed(Instant.parse("2026-11-20T18:00:00Z"), ZoneOffset.UTC)), List.of(), List.of(), ChangeEngineHost.NOT_HOSTED, List::of,
                (who, kind) -> true, personal());
        HomeDefinition d = catalogue().find("risk-manager").orElseThrow();
        Principal ann = new Principal("ann", List.of("risk-manager"));
        Principal bob = new Principal("bob", List.of("risk-manager"));
        Principal eve = new Principal("eve", List.of("risk-manager"));

        List<Map<String, Object>> a = t.rows("home_needs", ann, d).orElseThrow();
        assertEquals(2, a.size(), "the raised alert and the approval to decide; not the resolved, the snoozed or her own item: " + a);
        assertEquals("high", a.get(0).get("SEVERITY"));
        assertTrue(String.valueOf(a.get(0).get("MESSAGE")).startsWith("Alert: VaR breach"));
        assertEquals("Approve: Change X (from bob)", a.get(1).get("MESSAGE"));
        List<Map<String, Object>> b = t.rows("home_needs", bob, d).orElseThrow();
        assertEquals(1, b.size());
        assertTrue(String.valueOf(b.get(0).get("MESSAGE")).contains("Bob only"));
        assertEquals(List.of(), t.rows("home_needs", eve, d).orElseThrow());

        assertEquals(List.of(Map.of("KIND", "trade", "ID", "T-1"), Map.of("KIND", "desk", "ID", "DESK-A")), t.rows("home_pinned", ann, d).orElseThrow());
        assertEquals(List.of(), t.rows("home_pinned", bob, d).orElseThrow());
    }

    @ParameterizedTest
    @CsvSource({"risk-manager", "risk-officer", "trader", "operations", "quant", "credit-officer", "economist", "market-data", "genomicist"})
    void everyHomeBindsPinnedAndTheNonModuleOnesBindNeedsYou(String home) {
        String text = catalogue().find(home).orElseThrow().sutraText();
        Map<String, String> aliases = HomeComposer.aliases(text);
        assertEquals("home_pinned", aliases.get("pinned"), home);
        if (!home.equals("genomicist")) {
            assertEquals("home_needs", aliases.get("needs"), home);
        }
    }
}
