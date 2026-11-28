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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * R2 side by side for SIMM (docs/plans/R2_CUT_PLAN.md, Appendix A): one packaged server over the sample day with Drishti Risk's JARs in
 * {@code lib/engines}; {@code GET /api/v1/risk/simm} asked in-tree and through the {@code ccr} engine for every netting set the person sees,
 * with the default switches and with concentration and the historical volatility ratios off. The engine reads every SIMM input as a lake
 * table bound in {@code config/engines/ccr.yaml} (the position sensitivities' elements, the factor dictionary, the licensed parameter
 * files as the {@code simm-parameters} kind: the test data holds the TOY file of the SIMM tests, never ISDA's); the in-tree path reads the
 * same TOY file from its parameter folder ({@code DRISHTI_SIMM_PARAMETERS}). Every figure of the answer (both directions, by product class
 * and by risk class, the version's SHA-256, the regimes, trades) must be equal, numbers as JSON (every double to the bit). Both tiers
 * ({@code -Dsbs.tier=heap}: the exposure tier the server keeps; SIMM reads no vector). Run as {@link ExposureSideBySideTest}.
 */
class SimmSideBySideTest {

    static final Path FOLDER = Path.of(System.getProperty("drishti.g14.dir")).toAbsolutePath().resolve("side-by-side-simm");
    static final String DATE = "2026-09-30";
    static final String ONLY = ExposureSideBySideTest.ONLY + ",market-risk/position-sensitivity,market-risk/risk-factor,counterparty-risk/simm-parameters"
            // the VaR book: the in-tree run takes the person's rows from the VaR scope members (the engine path does too)
            + ",market-risk/scenario-pv,market-risk/fx-scenario,market-risk/scenario-set,market-risk/var-limit,trades/";
    /** Every key of the in-tree headline but its provenance (the host's words about what it read). */
    static final String[] FIELDS = {"SCOPE", "KIND", "ANALYTIC", "NETTING_SET", "COUNTERPARTY", "CSA", "BUSINESS_DATE", "SIMM_VERSION", "PARAMETER_SHA256",
        "CALCULATION_CURRENCY", "IM_COLLECT", "IM_POST", "COLLECT_REGIME", "POST_REGIME", "SIMM_COLLECT", "ADDON_COLLECT", "SIMM_POST", "ADDON_POST",
        "BY_PRODUCT_CLASS", "BY_RISK_CLASS", "TRADES", "MISSING_TRADES", "SWITCHES", "OVERRIDES", "REGULATORY", "notRegulatory", "label", "refused"};
    static PackagedServer server;

    @BeforeAll
    static void start() throws Exception {
        String jars = System.getProperty("drishti.risk.jars", "");
        assumeTrue(!jars.isBlank() && Files.isDirectory(Path.of(jars)), "no folder of Drishti Risk JARs given (-Ddrishti.risk.jars)");
        Path lake = FOLDER.resolve("lake");
        PackagedServer.buildLake(lake, ONLY);
        Path work = FOLDER.resolve("server");
        RiskSideBySideTest.deleteTree(work);
        SideBySide.dropJars(Path.of(jars), work);
        // the in-tree path's parameter folder: the same TOY file the lake's simm-parameters row holds (data/testdata/counterparty-risk/simm-parameters)
        Path params = Files.createDirectories(FOLDER.resolve("simm-parameters"));
        Files.copy(PackagedServer.REPO.resolve("modules/banking/java/banking-compute/src/test/resources/simm/simm-toy.yaml"), params.resolve("simm-toy.yaml"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Map<String, String> env = new LinkedHashMap<>();
        env.put("DRISHTI_SIMM_PARAMETERS", params.toString());
        String tier = System.getProperty("sbs.tier", "");
        if (!tier.isBlank()) {
            env.put("DRISHTI_EXPOSURE_TIER", tier);
        }
        server = PackagedServer.start(work, lake, DATE, env);
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void theSimmOfEveryNettingSetIsTheSameOnBothPaths() throws Exception {
        SideBySide sbs = new SideBySide();
        List<String> sets = new ArrayList<>();
        // every netting set of the date (the exposure tree lists them all, with or without paths); the SIMM list itself shows only the
        // sets whose trade ids are among the VaR members, which the sample's position-grain members are not (both paths ask the same)
        JsonNode t = server.get("/api/v1/risk/exposure/tree?asOf=" + DATE);
        for (JsonNode g : t.path("groups")) {
            for (JsonNode cp : g.path("children")) {
                for (JsonNode ns : cp.path("children")) {
                    String sc = ns.path("scope").asText("");
                    if (sc.startsWith("NSET:") && !sets.contains(sc.substring(5))) {
                        sets.add(sc.substring(5));
                    }
                }
            }
        }
        int withFigures = 0;
        int refusedBoth = 0;
        List<String> engineOnlyRefused = new ArrayList<>();
        for (String set : sets) {
            for (String extra : List.of("", "&concentration=off", "&hvr=off&concentration=off")) {
                String q = "/api/v1/risk/simm?asOf=" + DATE + "&nettingSet=" + set + extra;
                Outcome o = ask(sbs, set + extra, q);
                if (o == Outcome.FIGURES) {
                    withFigures++;
                } else if (o == Outcome.BOTH_REFUSED) {
                    refusedBoth++;
                } else {
                    engineOnlyRefused.add(set + extra);
                }
            }
        }
        // two analytic scopes (the host resolves the records, calculation currency USD, no regime)
        for (String scope : List.of("BANK", "DESK:DESK-RATES")) {
            Outcome o = ask(sbs, scope, "/api/v1/risk/simm?asOf=" + DATE + "&scope=" + scope);
            if (o == Outcome.FIGURES) {
                withFigures++;
            } else if (o == Outcome.BOTH_REFUSED) {
                refusedBoth++;
            } else {
                engineOnlyRefused.add(scope);
            }
        }
        System.out.println("side by side, SIMM: " + sets.size() + " netting sets, " + sbs.compared + " answers with figures on both paths ("
                + withFigures + "), refused on both " + refusedBoth + ", refused on the engine path only " + engineOnlyRefused.size() + " "
                + engineOnlyRefused.stream().limit(10).toList() + "; first differences: " + sbs.differences.stream().limit(Integer.getInteger("sbs.show", 10)).toList());
        assertThat(withFigures).as("answers with figures on both paths").isGreaterThan(30);
        assertThat(engineOnlyRefused).as("answered in-tree, refused on the engine path").isEmpty();
        assertThat(sbs.differences).as("SIMM answers that differ between in-tree and engine").isEmpty();
    }

    /** The netting sets of the date (the exposure tree lists them all), in the tree's order. */
    private static List<String> nettingSets() throws IOException, InterruptedException {
        List<String> sets = new ArrayList<>();
        JsonNode t = server.get("/api/v1/risk/exposure/tree?asOf=" + DATE);
        for (JsonNode g : t.path("groups")) {
            for (JsonNode cp : g.path("children")) {
                for (JsonNode ns : cp.path("children")) {
                    String sc = ns.path("scope").asText("");
                    if (sc.startsWith("NSET:") && !sets.contains(sc.substring(5))) {
                        sets.add(sc.substring(5));
                    }
                }
            }
        }
        return sets;
    }

    /** Overrides (parameter what-ifs) and the hand-built examples: every netting set with two override sets, the examples three ways. */
    @Test
    void theOverridesAndTheExamplesAreTheSameOnBothPaths() throws Exception {
        SideBySide sbs = new SideBySide();
        String one = "&override=" + enc("InterestRate.concentration-thresholds.delta.1=3000");
        String two = one + "&override=" + enc("FX.vega-risk-weight=0.5") + "&concentration=off";
        int withFigures = 0;
        List<String> engineOnlyRefused = new ArrayList<>();
        List<String> asks = new ArrayList<>();
        for (String set : nettingSets()) {
            asks.add("nettingSet=" + set + one);
            asks.add("nettingSet=" + set + two);
        }
        asks.add("nettingSet=EXAMPLE");
        asks.add("nettingSet=EXAMPLE&concentration=off&hvr=off");
        asks.add("nettingSet=EXAMPLE" + two);
        asks.add("scope=DESK:DESK-RATES" + one);
        for (String a : asks) {
            Outcome o = ask(sbs, a, "/api/v1/risk/simm?asOf=" + DATE + "&" + a);
            if (o == Outcome.FIGURES) {
                withFigures++;
            } else if (o == Outcome.ENGINE_REFUSED) {
                engineOnlyRefused.add(a);
            }
        }
        // a refused override and a refused add-on of nothing: refused on both paths, in the same words
        int refusedSame = 0;
        for (String a : List.of("nettingSet=EXAMPLE&override=" + enc("InterestRate.no-such=1"), "nettingSet=" + nettingSets().get(0) + "&override="
                + enc("InterestRate.concentration-thresholds.delta.1=abc"))) {
            refusedSame += sameRefusal(sbs, a, "/api/v1/risk/simm?asOf=" + DATE + "&" + a) ? 1 : 0;
        }
        System.out.println("side by side, SIMM overrides and examples: " + asks.size() + " asked, " + withFigures + " with figures on both paths, "
                + refusedSame + " refused on both in the same words, refused on the engine path only " + engineOnlyRefused.size() + " "
                + engineOnlyRefused.stream().limit(10).toList() + "; first differences: " + sbs.differences.stream().limit(10).toList());
        assertThat(withFigures).isGreaterThan(100);
        assertThat(refusedSame).as("refusals in the same words").isEqualTo(2);
        assertThat(engineOnlyRefused).as("answered in-tree, refused on the engine path").isEmpty();
        assertThat(sbs.differences).as("SIMM answers that differ between in-tree and engine").isEmpty();
    }

    /** The netting-set list, the comparison with the counterparty's call, the explanation's steps and the installed versions. */
    @Test
    void theListTheCallsTheStepsAndTheVersionsAreTheSameOnBothPaths() throws Exception {
        SideBySide sbs = new SideBySide();
        List<String> sets = nettingSets();
        int lists = 0;
        for (String q : List.of("", "&concentration=off", "&counterparty=CP-ALDERSHOT")) {
            whole(sbs, "netting-sets" + q, "/api/v1/risk/simm/netting-sets?asOf=" + DATE + q);
            lists++;
        }
        int against = 0;
        int explain = 0;
        for (String set : sets) {
            if (wholeOrSameRefusal(sbs, "against " + set, "/api/v1/risk/simm/against?asOf=" + DATE + "&nettingSet=" + set)) {
                against++;
            }
            for (String q : List.of("", "&direction=post", "&class=InterestRate&concentration=off")) {
                if (wholeOrSameRefusal(sbs, "explain " + set + q, "/api/v1/risk/simm/explain?asOf=" + DATE + "&nettingSet=" + set + q)) {
                    explain++;
                }
            }
        }
        for (String q : List.of("nettingSet=EXAMPLE", "nettingSet=EXAMPLE&direction=post", "scope=DESK:DESK-RATES",
                "nettingSet=EXAMPLE&override=" + enc("InterestRate.concentration-thresholds.delta.1=3000"))) {
            if (wholeOrSameRefusal(sbs, "explain " + q, "/api/v1/risk/simm/explain?asOf=" + DATE + "&" + q)) {
                explain++;
            }
        }
        wholeOrSameRefusal(sbs, "against DESK:DESK-RATES", "/api/v1/risk/simm/against?asOf=" + DATE + "&nettingSet=DESK:DESK-RATES");
        // the versions: the lake's simm-parameters (engine) against the parameter folder (in-tree); where each file lives differs by design
        JsonNode[] v = sbs.both(server, "versions", "/api/v1/risk/simm/versions?asOf=" + DATE, "default");
        java.util.function.Function<JsonNode, String> rows = n -> {
            StringBuilder b = new StringBuilder();
            n.path("versions").forEach(x -> b.append(x.path("version")).append('|').append(x.path("installed")).append('|').append(x.path("effectiveFrom"))
                    .append('|').append(x.path("sha256")).append('|').append(x.path("problem")).append('\n'));
            return b.toString();
        };
        if (!rows.apply(v[0]).equals(rows.apply(v[1]))) {
            sbs.differences.add("versions: in-tree " + rows.apply(v[0]) + ", engine " + rows.apply(v[1]));
        }
        System.out.println("side by side, SIMM routes: " + lists + " netting-set lists, " + against + " comparisons with the call, " + explain
                + " explanations with steps (" + sets.size() + " netting sets), versions " + v[1].path("versions").size() + "; differences "
                + sbs.differences.size() + "; first: " + sbs.differences.stream().limit(10).toList());
        assertThat(against).isGreaterThan(30);
        assertThat(explain).isGreaterThan(100);
        assertThat(v[1].path("versions").size()).isPositive();
        assertThat(sbs.differences).as("SIMM routes that differ between in-tree and engine").isEmpty();
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** The whole answer on both paths (JSON equality of every field but {@code path}); false when refused on both in the same words. */
    private static boolean wholeOrSameRefusal(SideBySide sbs, String what, String q) throws IOException, InterruptedException {
        JsonNode a;
        try {
            a = server.get(q + "&path=in-tree");
        } catch (IllegalStateException e) {
            sameRefusal(sbs, what, q);
            return false;
        }
        JsonNode b;
        try {
            b = server.get(q + "&path=engine");
        } catch (IllegalStateException x) {
            sbs.differences.add(what + ": answered in-tree, refused on the engine path: " + x.getMessage());
            return false;
        }
        compareWhole(sbs, what, a, b);
        return true;
    }

    private static void whole(SideBySide sbs, String what, String q) throws IOException, InterruptedException {
        compareWhole(sbs, what, server.get(q + "&path=in-tree"), server.get(q + "&path=engine"));
    }

    private static void compareWhole(SideBySide sbs, String what, JsonNode a, JsonNode b) {
        sbs.compared++;
        com.fasterxml.jackson.databind.node.ObjectNode x = ((com.fasterxml.jackson.databind.node.ObjectNode) a).deepCopy();
        com.fasterxml.jackson.databind.node.ObjectNode y = ((com.fasterxml.jackson.databind.node.ObjectNode) b).deepCopy();
        x.remove("path");
        y.remove("path");
        if (!x.equals(y)) {                                             // JSON equality: every key (any order), every number as written
            sbs.differences.add(what + ": " + SideBySide.first(x, y));
        }
    }

    /** True when both paths refuse with the same status and the same words; a difference is kept otherwise. */
    private static boolean sameRefusal(SideBySide sbs, String what, String q) throws IOException, InterruptedException {
        String a = refusal(q + "&path=in-tree");
        String b = refusal(q + "&path=engine");
        if (a == null || b == null || !a.equals(b)) {
            sbs.differences.add(what + ": refusals differ: in-tree " + a + ", engine " + b);
            return false;
        }
        return true;
    }

    /** The status and the words of a refusal (the problem's body), null when answered. */
    private static String refusal(String q) throws IOException, InterruptedException {
        try {
            server.get(q);
            return null;
        } catch (IllegalStateException e) {
            String m = String.valueOf(e.getMessage());
            int colon = m.indexOf(": ");
            return m.substring(0, m.indexOf(' ')) + (colon < 0 ? "" : m.substring(colon + 2));
        }
    }

    static final String[] FIELDS_IN_ORDER = java.util.Arrays.stream(FIELDS).filter(f -> !f.equals("BY_RISK_CLASS")).toArray(String[]::new);

    /** The rows by product class and risk class, each row's JSON (numbers as written: every double to the bit). */
    private static java.util.TreeMap<String, String> sorted(JsonNode rows) {
        java.util.TreeMap<String, String> out = new java.util.TreeMap<>();
        rows.forEach(r -> out.put(r.path("PRODUCT_CLASS").asText() + "|" + r.path("RISK_CLASS").asText(), r.toString()));
        return out;
    }

    enum Outcome { FIGURES, BOTH_REFUSED, ENGINE_REFUSED }

    static final List<String> LAST = new ArrayList<>();

    private static Outcome ask(SideBySide sbs, String what, String q) throws IOException, InterruptedException {
        JsonNode inTree;
        try {
            inTree = server.get(q + "&path=in-tree");
        } catch (IllegalStateException e) {
            try {
                server.get(q + "&path=engine");
                sbs.differences.add(what + ": refused in-tree (" + e.getMessage() + "), answered on the engine path");
            } catch (IllegalStateException x) {
                if (!status(e).equals(status(x))) {
                    sbs.differences.add(what + ": refused with " + status(e) + " in-tree and " + status(x) + " on the engine path: " + x.getMessage());
                }
            }
            return Outcome.BOTH_REFUSED;
        }
        try {
            server.get(q + "&path=engine");
        } catch (IllegalStateException x) {
            LAST.add(what + ": " + x.getMessage());
            System.out.println("engine path refused " + what + ": " + x.getMessage());
            return Outcome.ENGINE_REFUSED;
        }
        assertThat(inTree.path("SCOPE").isMissingNode()).isFalse();
        JsonNode[] both = sbs.both(server, what, q, FIELDS_IN_ORDER);
        // BY_RISK_CLASS: every row equal; the in-tree run lists the classes as its elements first name them, the engine path in SIMM's order
        if (!sorted(both[0].path("BY_RISK_CLASS")).equals(sorted(both[1].path("BY_RISK_CLASS")))) {
            sbs.differences.add(what + " BY_RISK_CLASS: " + SideBySide.first(both[0].path("BY_RISK_CLASS"), both[1].path("BY_RISK_CLASS")));
        }
        return Outcome.FIGURES;
    }

    private static String status(IllegalStateException e) {
        String m = String.valueOf(e.getMessage());
        int sp = m.indexOf(' ');
        return sp > 0 ? m.substring(0, sp) : m;
    }
}
