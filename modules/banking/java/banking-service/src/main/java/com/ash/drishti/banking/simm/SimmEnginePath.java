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
package com.ash.drishti.banking.simm;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.error.DrishtiException;
import com.ash.drishti.api.error.ErrorCode;
import com.ash.drishti.banking.compute.simm.SimmChoices;
import com.ash.drishti.banking.frtb.FrtbData;
import com.ash.drishti.kernel.api.security.Principal;
import com.ash.drishti.kernel.api.services.Engines;
import com.ash.drishti.kernel.api.services.Kernel;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * R2 (docs/plans/R2_CUT_PLAN.md, "Switching a route"): {@code GET /api/v1/risk/simm} answered by Drishti Risk's counterparty engine
 * ({@code ccr}: outputs {@code simm}, {@code simm-total}, {@code simm-product}, {@code simm-version}) through the kernel's {@link Engines}
 * service, never by a Risk type. The engine reads the SIMM inputs as lake tables bound in {@code config/engines/ccr.yaml} (the position
 * sensitivities' elements, the factor dictionary, the licensed parameter files as the {@code simm-parameters} kind, the FX mids); the host
 * resolves the scope (a netting set's records as its trade master places them, an analytic scope's records, the viewer's rows: the
 * engine's {@code simm-members}) and keeps the CSA's terms (version, regimes, calculation currency). One switch,
 * {@code drishti.module.banking.simm.route}: {@code in-tree} (default) or {@code engine}; a request may name its {@code path}. What the
 * engine answers the hand-built examples (its own copy, no data read), parameter overrides ({@code simm-overrides}) and the CSA's add-ons
 * ({@code simm-addon-*}, the host's terms and lines) as in-tree.
 */
@Component
public class SimmEnginePath {

    /** The engine's id (config/engines/ccr.yaml). */
    public static final String ENGINE = "ccr";
    static final String SETTING = "drishti.module.banking.simm.route";
    static final List<String> PRODUCT_CLASSES = List.of("RatesFX", "Credit", "Equity", "Commodity");
    static final List<String> RISK_CLASSES = List.of("InterestRate", "CreditQ", "CreditNonQ", "Equity", "Commodity", "FX");
    static final List<String> OUTPUTS = List.of("simm", "simm-total", "simm-product", "simm-version");

    private final ObjectProvider<Engines> engines;
    private final Environment env;

    SimmEnginePath(ObjectProvider<Engines> engines, Environment env) {
        this.engines = engines;
        this.env = env;
    }

    /** True when this request is answered by the engine: its own {@code path}, else the family's switch. */
    public boolean engine(String requested) {
        String p = requested != null && !requested.isBlank() ? requested : env.getProperty(SETTING, "in-tree");
        return switch (p.trim().toLowerCase(Locale.ROOT)) {
            case "engine" -> true;
            case "in-tree" -> false;
            default -> throw new DrishtiException(ErrorCode.BAD_REQUEST, "path is engine or in-tree, not " + p);
        };
    }

    /** The headline of {@code GET /simm} (SimmAnswer.result's keys) with the engine's figures; {@code path: engine}. */
    Map<String, Object> result(SimmService svc, Principal p, String scope, LocalDate date, String asked, SimmChoices ch) {
        return answer(svc, p, scope, date, asked, ch, List.of()).headline();
    }

    /**
     * One engine run of a scope, both directions: the headline and each direction's {@code simm} rows in the method's order (the
     * comparison with the counterparty's call sums them by risk class in that order, as in-tree).
     */
    record Answer(Map<String, Object> headline, List<Map<String, Object>> collectClasses, List<Map<String, Object>> postClasses,
            Map<String, Object> collect, Map<String, Object> post) {}

    /** The steps' outputs (the explanation: charges, buckets, weighted sensitivities, concentration groups, add-on lines). */
    static final List<String> STEPS = List.of("simm-charge", "simm-bucket", "simm-factor", "simm-group", "simm-addon");

    Answer answer(SimmService svc, Principal p, String scope, LocalDate date, String asked, SimmChoices ch, List<String> extra) {
        svc.enabled();
        if (scope == null || scope.isBlank()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "give a netting set (nettingSet=NS-...), a scope (scope=DESK:DESK-RATES, an analytic) or EXAMPLE");
        }
        String stripped = scope.strip();
        String named = stripped.toUpperCase(Locale.ROOT).startsWith("NS:") ? stripped.substring(3).strip() : stripped;
        boolean example = named.equalsIgnoreCase(SimmService.EXAMPLE);  // the hand-built examples: the engine's own inputs, no data read
        final String s = example ? SimmService.EXAMPLE : named;
        SimmData.Day day = svc.data().day(new AsOf(date, null));
        if (!example && !day.sens().sensitivitiesRead()) {
            throw new DrishtiException(ErrorCode.NO_SOURCE_FOR_KIND, "no connector serves position-sensitivity: load the position sensitivities first");
        }
        SimmData.NettingSet ns = day.nettingSets().get(s);
        List<FrtbData.Record> recs = new ArrayList<>();
        String kind;
        String calc;
        String version;
        List<String> collect = List.of("NONE");
        List<String> post = List.of("NONE");
        SimmData.Csa csa = null;
        int missing = 0;
        Map<String, Object> prm = new LinkedHashMap<>();
        if (example) {
            kind = SimmService.EXAMPLE;
            calc = "USD";
            version = asked;
        } else if (ns != null) {
            Set<String> keep = svc.visible(p, day.date());
            List<FrtbData.Record> all = day.recordsBySet().getOrDefault(ns.id(), List.of());
            if (keep != null && ns.trades().stream().noneMatch(keep::contains) && all.stream().noneMatch(r -> SimmService.seen(keep, r))) {
                throw new DrishtiException(ErrorCode.FORBIDDEN, "your role does not see the trades of " + ns.id());
            }
            csa = ns.csa() == null ? null : day.csas().get(ns.csa());
            if (csa != null && csa.model().equals("SCHEDULE")) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, ns.id() + " is margined by schedule IM under " + csa.id() + ": not a SIMM netting set");
            }
            addOns(prm, day, ns, csa);
            for (FrtbData.Record r : all) {
                if (SimmService.seen(keep, r)) {
                    recs.add(r);
                }
            }
            kind = SimmService.NETTING_SET;
            calc = csa == null ? "USD" : csa.calculationCurrency();
            version = asked != null && !asked.isBlank() ? asked : csa == null ? null : csa.version();
            if (csa != null && !csa.collect().isEmpty()) {
                collect = csa.collect();
            }
            if (csa != null && !csa.post().isEmpty()) {
                post = csa.post();
            }
            Set<String> covered = day.tradesWithRecords().getOrDefault(ns.id(), Set.of());
            missing = (int) ns.trades().stream().filter(t -> !covered.contains(t)).count();
        } else {
            recs.addAll(svc.analyticRecords(p, day, s).records());
            kind = SimmService.ANALYTIC;
            calc = "USD";
            version = asked;
        }
        if (version == null || version.isBlank()) {
            version = defaultVersion(svc, p, day.date());
        }
        if (example) {
            prm.put("simm-scope", SimmService.EXAMPLE);
        } else {
            List<Map<String, Object>> members = new ArrayList<>();
            for (FrtbData.Record r : recs) {
                members.add(new LinkedHashMap<>(Map.of("SCOPE", s, "SENSITIVITY_ID", r.id())));
            }
            if (members.isEmpty()) {
                members.add(new LinkedHashMap<>(Map.of("SCOPE", s)));   // the scope with no record: a run of no element, as in-tree
            }
            prm.put("simm-members", members);
        }
        if (!ch.overrides().isEmpty()) {                                // what-if parameters, as the in-tree run applies them
            List<String> o = new ArrayList<>();
            ch.overrides().forEach((k, v) -> o.add(k + "=" + v));
            prm.put("simm-overrides", o);
        }
        prm.put("calculation-currency", calc);
        prm.put("simm-version", version == null ? "" : version);
        prm.put("concentration", ch.concentration());
        prm.put("hvr", ch.hvr());
        List<String> outputs = new ArrayList<>(OUTPUTS);
        outputs.addAll(extra);
        prm.put("direction", "collect");
        Map<String, Object> c = call(p, prm, day.date(), outputs);
        prm.put("direction", "post");
        Map<String, Object> po = call(p, prm, day.date(), outputs);
        Map<String, Object> tc = one(c, "simm-total", s);
        Map<String, Object> tp = one(po, "simm-total", s);
        Map<String, Object> ver = one(c, "simm-version", s);
        List<String> regimes = ver.get("REGIMES") == null || ver.get("REGIMES").toString().isBlank() ? List.of()
                : Arrays.asList(ver.get("REGIMES").toString().split(","));
        String cr = collect.stream().filter(r -> regimes.isEmpty() || regimes.contains(r)).findFirst().orElse("NONE");
        String pr = post.stream().filter(r -> regimes.isEmpty() || regimes.contains(r)).findFirst().orElse("NONE");

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("SCOPE", s);
        m.put("KIND", kind);
        m.put("ANALYTIC", !kind.equals(SimmService.NETTING_SET));
        m.put("NETTING_SET", ns == null ? null : ns.id());
        m.put("COUNTERPARTY", ns == null ? null : ns.counterparty());
        m.put("CSA", ns == null ? null : ns.csa());
        m.put("BUSINESS_DATE", example || day.date() == null ? null : day.date().toString());
        m.put("SIMM_VERSION", ver.get("VERSION"));
        m.put("PARAMETER_SHA256", ver.get("PARAMETER_SHA256"));
        m.put("CALCULATION_CURRENCY", calc);
        m.put("IM_COLLECT", num(tc, "TOTAL"));
        m.put("IM_POST", num(tp, "TOTAL"));
        m.put("COLLECT_REGIME", cr);
        m.put("POST_REGIME", pr);
        m.put("SIMM_COLLECT", num(tc, "SIMM"));
        m.put("ADDON_COLLECT", num(tc, "ADD_ON"));
        m.put("SIMM_POST", num(tp, "SIMM"));
        m.put("ADDON_POST", num(tp, "ADD_ON"));
        Map<String, Double> pcC = byProduct(c, s);
        Map<String, Double> pcP = byProduct(po, s);
        List<Map<String, Object>> pcs = new ArrayList<>();
        for (String pc : PRODUCT_CLASSES) {
            if (!pcC.containsKey(pc) && !pcP.containsKey(pc)) {
                continue;
            }
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("PRODUCT_CLASS", pc);
            x.put("COLLECT", pcC.containsKey(pc) ? pcC.get(pc) : (Object) 0);
            x.put("POST", pcP.containsKey(pc) ? pcP.get(pc) : (Object) 0);
            pcs.add(x);
        }
        m.put("BY_PRODUCT_CLASS", pcs);
        List<Map<String, Object>> postRows = rows(po, "simm");
        List<Map<String, Object>> rcs = new ArrayList<>();
        for (Map<String, Object> r : rows(c, "simm")) {
            if (!s.equals(r.get("SCOPE"))) {
                continue;
            }
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("PRODUCT_CLASS", r.get("PRODUCT_CLASS"));
            x.put("RISK_CLASS", r.get("RISK_CLASS"));
            x.put("DELTA", num(r, "DELTA"));
            x.put("VEGA", num(r, "VEGA"));
            x.put("CURVATURE", num(r, "CURVATURE"));
            x.put("BASE_CORR", num(r, "BASE_CORR"));
            x.put("TOTAL", num(r, "IM"));
            x.put("POST_TOTAL", postRows.stream().filter(y -> s.equals(y.get("SCOPE")) && r.get("PRODUCT_CLASS").equals(y.get("PRODUCT_CLASS"))
                    && r.get("RISK_CLASS").equals(y.get("RISK_CLASS"))).map(y -> num(y, "IM")).findFirst().orElse(null));
            rcs.add(x);
        }
        // SIMM's order (product classes, then risk classes within each); the in-tree run lists them as its elements first name them
        rcs.sort(java.util.Comparator.comparingInt((Map<String, Object> x) -> PRODUCT_CLASSES.indexOf(String.valueOf(x.get("PRODUCT_CLASS"))))
                .thenComparingInt(x -> RISK_CLASSES.indexOf(String.valueOf(x.get("RISK_CLASS")))));
        m.put("BY_RISK_CLASS", rcs);
        m.put("TRADES", example ? 0 : ((Number) tc.get("TRADES")).intValue());   // the examples: no record read, as in-tree
        m.put("MISSING_TRADES", missing);
        m.put("SWITCHES", ch.asMap());
        Map<String, String> overrides = java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(ch.overrides()));   // as the file applies them
        m.put("OVERRIDES", overrides);
        boolean regulatory = Boolean.TRUE.equals(tc.get("REGULATORY"));
        m.put("REGULATORY", regulatory && kind.equals(SimmService.NETTING_SET));
        if (!regulatory) {
            m.put("notRegulatory", "not the regulatory figure: " + ch.changed() + (overrides.isEmpty() ? "" : " overrides " + overrides));
        }
        if (!kind.equals(SimmService.NETTING_SET)) {
            m.put("label", example ? "the hand-built examples with SIMM " + ver.get("VERSION") : SimmService.ANALYTIC);
        }
        m.put("refused", ((Number) tc.get("REFUSED")).intValue());
        Map<String, Object> pv = new LinkedHashMap<>();
        pv.put("Business date", String.valueOf(day.date()));
        pv.put("Records in scope", recs.size());
        pv.put("SIMM version", ver.get("DESCRIPTION"));
        pv.put("Parameter file", "simm-parameters " + ver.get("VERSION") + " · SHA-256 " + ver.get("PARAMETER_SHA256"));
        pv.put("Choices", ch.asMap());
        if (!overrides.isEmpty()) {
            pv.put("Overrides (not the regulatory figure)", overrides);
        }
        if (example) {
            pv.put("Inputs", "the hand-built examples (SIMM.md section 15, the swaption of the explanation, case 11), no data read");
        }
        pv.put("Engine", ENGINE + " (Drishti Risk), through the host");
        m.put("PROVENANCE", pv);
        m.put("path", "engine");
        List<Map<String, Object>> cc = rows(c, "simm").stream().filter(r -> s.equals(r.get("SCOPE"))).toList();
        List<Map<String, Object>> pc = postRows.stream().filter(r -> s.equals(r.get("SCOPE"))).toList();
        return new Answer(m, cc, pc, c, po);
    }

    /** {@code GET /simm/netting-sets} with each computed set's figures from the engine (the list, stored figures and calls the host's). */
    Map<String, Object> nettingSets(SimmService svc, Principal p, LocalDate date, String counterparty, String asked, SimmChoices ch) {
        return svc.nettingSets(p, date, counterparty, asked, ch, (day, id) -> {
            Map<String, Object> h = result(svc, p, id, day, asked, ch);
            return new SimmService.SetFigures(String.valueOf(h.get("SIMM_VERSION")), (Double) h.get("IM_COLLECT"), (Double) h.get("IM_POST"),
                    (String) h.get("COLLECT_REGIME"), (String) h.get("POST_REGIME"), (Integer) h.get("MISSING_TRADES"), (String) h.get("CALCULATION_CURRENCY"));
        });
    }

    /** {@code GET /simm/against}: the engine's margins against the counterparty's call (the calls and the CSA's tolerance the host's). */
    Map<String, Object> against(SimmService svc, Principal p, String nettingSet, LocalDate date) {
        Answer a = answer(svc, p, nettingSet, date, null, SimmChoices.defaults(), List.of());
        Map<String, Object> h = a.headline();
        if (h.get("NETTING_SET") == null) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the comparison with the counterparty's call is per netting set, not for " + h.get("SCOPE"));
        }
        SimmData.Day day = svc.data().day(new AsOf(date, null));
        SimmData.NettingSet ns = day.nettingSets().get(String.valueOf(h.get("NETTING_SET")));
        SimmData.Csa csa = ns.csa() == null ? null : day.csas().get(ns.csa());
        return SimmAnswer.against(String.valueOf(h.get("SCOPE")), String.valueOf(h.get("SIMM_VERSION")), side((Double) h.get("IM_COLLECT"), a.collectClasses()),
                side((Double) h.get("IM_POST"), a.postClasses()), day.calls().getOrDefault(ns.id(), List.of()), csa == null ? Double.NaN : csa.tolerance(),
                day.callsRead());
    }

    /**
     * The version a scope without one is run on: {@code drishti.simm.default-version}, else the installed version of simm-parameters
     * with the latest effective date (the engine's {@code simm-versions}), as the in-tree service picks it.
     */
    String defaultVersion(SimmService svc, Principal p, LocalDate date) {
        String dv = svc.props().defaultVersion();
        if (!dv.isBlank()) {
            return dv;
        }
        Map<String, Object> best = null;
        for (Map<String, Object> v : versionRows(p, date)) {
            if (Boolean.TRUE.equals(v.get("INSTALLED")) && (best == null
                    || String.valueOf(v.get("EFFECTIVE_FROM")).compareTo(String.valueOf(best.get("EFFECTIVE_FROM"))) > 0)) {
                best = v;
            }
        }
        return best == null ? "" : String.valueOf(best.get("VERSION"));
    }

    private List<Map<String, Object>> versionRows(Principal p, LocalDate date) {
        return rows(call(p, Map.of(), date, List.of("simm-versions")), "simm-versions");
    }

    /** {@code GET /simm/versions}: the versions of the lake's simm-parameters as the engine reads and checks them. */
    Map<String, Object> versions(SimmService svc, Principal p, LocalDate date) {
        svc.enabled();
        LocalDate d = svc.data().day(new AsOf(date, null)).date();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> v : versionRows(p, d)) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("version", v.get("VERSION"));
            x.put("file", "simm-parameters " + v.get("VERSION"));
            x.put("installed", v.get("INSTALLED"));
            x.put("effectiveFrom", v.get("EFFECTIVE_FROM"));
            x.put("sha256", v.get("PARAMETER_SHA256"));
            x.put("problem", v.get("PROBLEM"));
            rows.add(x);
        }
        String dv = defaultVersion(svc, p, d);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("parametersDir", "the lake table of kind simm-parameters (config/engines/ccr.yaml), " + d);
        m.put("default", dv.isBlank() ? null : dv);
        m.put("versions", rows);
        m.put("licence", "ISDA's SIMM parameters are licensed: " + com.ash.drishti.common.Branding.name("the product") + " ships none; the bank installs one file a version (docs/guides/SIMM_GUIDE.md section 4)");
        m.put("path", "engine");
        return m;
    }

    /** {@code GET /simm/explain} from the engine's steps, shaped as the in-tree explanation (SimmAnswer.explain). */
    Map<String, Object> explain(SimmService svc, Principal p, String scope, LocalDate date, String asked, SimmChoices ch, boolean post, String riskClass, int max) {
        Answer a = answer(svc, p, scope, date, asked, ch, STEPS);
        String s = String.valueOf(a.headline().get("SCOPE"));
        Map<String, Object> r = post ? a.post() : a.collect();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scope", s);
        m.put("direction", post ? "post" : "collect");
        m.put("version", a.headline().get("SIMM_VERSION"));
        List<Map<String, Object>> factors = rows(r, "simm-factor").stream().filter(x -> s.equals(x.get("SCOPE"))).toList();
        Map<Long, List<Map<String, Object>>> bucketsOf = new java.util.HashMap<>();
        for (Map<String, Object> b : rows(r, "simm-bucket")) {
            if (s.equals(b.get("SCOPE"))) {
                bucketsOf.computeIfAbsent(((Number) b.get("CHARGE")).longValue(), k -> new ArrayList<>()).add(b);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        int shown = 0;
        for (Map<String, Object> c : rows(r, "simm-charge")) {
            if (!s.equals(c.get("SCOPE"))) {
                continue;
            }
            String rc = String.valueOf(c.get("RISK_CLASS"));
            if (riskClass != null && !riskClass.isBlank() && !rc.equalsIgnoreCase(riskClass)) {
                continue;
            }
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("productClass", c.get("PRODUCT_CLASS"));
            x.put("riskClass", rc);
            x.put("measure", c.get("MEASURE"));
            x.put("margin", c.get("MARGIN"));
            x.put("root", c.get("ROOT"));
            x.put("residual", c.get("RESIDUAL"));
            if ("CURVATURE".equals(c.get("MEASURE"))) {             // Simm.CURVATURE: theta, lambda, the summed CVR, the scale
                x.put("theta", c.get("THETA"));
                x.put("lambda", c.get("LAMBDA"));
                x.put("sumCvr", c.get("SUM_CVR"));
                x.put("scale", c.get("SCALE"));
            }
            List<Map<String, Object>> bs = new ArrayList<>();
            List<Map<String, Object>> mine = new ArrayList<>(bucketsOf.getOrDefault(((Number) c.get("CHARGE")).longValue(), List.of()));
            mine.sort(java.util.Comparator.comparingLong(b -> ((Number) b.get("BUCKET_INDEX")).longValue()));
            for (Map<String, Object> b : mine) {
                Map<String, Object> y = new LinkedHashMap<>();
                y.put("bucket", b.get("BUCKET"));
                y.put("residual", b.get("RESIDUAL"));
                y.put("K", b.get("K"));
                y.put("net", b.get("NET"));
                y.put("S", b.get("S"));
                y.put("cr", b.get("CR"));
                List<Map<String, Object>> fs = new ArrayList<>();
                for (int k : ints(b.get("MEMBERS"))) {
                    if (shown++ >= max) {
                        break;
                    }
                    Map<String, Object> k2 = factors.get(k);
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("factor", k2.get("FACTOR"));
                    f.put("s", k2.get("S"));
                    f.put("rw", k2.get("RW"));
                    f.put("rwKey", k2.get("RW_KEY"));
                    f.put("hvr", k2.get("HVR"));
                    f.put("cr", k2.get("CR"));
                    f.put("ws", k2.get("WS"));
                    f.put("trades", ((Number) k2.get("TRADES")).intValue());
                    fs.add(f);
                }
                y.put("factors", fs);
                bs.add(y);
            }
            x.put("buckets", bs);
            out.add(x);
        }
        m.put("charges", out);
        m.put("truncated", shown > max);
        List<Map<String, Object>> groups = new ArrayList<>();
        for (Map<String, Object> g : rows(r, "simm-group")) {
            if (s.equals(g.get("SCOPE")) && ((Number) g.get("CR")).doubleValue() > 1) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("productClass", g.get("PRODUCT_CLASS"));
                x.put("riskClass", g.get("RISK_CLASS"));
                x.put("measure", g.get("MEASURE"));
                x.put("group", g.get("GROUP"));
                x.put("sum", g.get("SUM"));
                x.put("threshold", g.get("THRESHOLD"));
                x.put("cr", g.get("CR"));
                groups.add(x);
            }
        }
        m.put("concentration", groups);
        Map<String, Object> addOns = new LinkedHashMap<>();
        Map<String, Object> lines = new LinkedHashMap<>();
        for (Map<String, Object> l : rows(r, "simm-addon")) {
            if (!s.equals(l.get("SCOPE"))) {
                continue;
            }
            if (String.valueOf(l.get("LINE")).isEmpty()) {
                addOns.put("fixed", l.get("FIXED"));
                addOns.put("notional", l.get("NOTIONAL"));
                addOns.put("multipliers", l.get("MULTIPLIERS"));
            } else {
                lines.put(String.valueOf(l.get("LINE")), l.get("AMOUNT"));
            }
        }
        addOns.put("lines", lines);
        m.put("addOns", addOns);
        m.put("path", "engine");
        return m;
    }

    private static int[] ints(Object list) {
        if (list instanceof int[] a) {
            return a;
        }
        if (list instanceof double[] a) {
            return java.util.Arrays.stream(a).mapToInt(x -> (int) x).toArray();
        }
        if (list instanceof long[] a) {
            return java.util.Arrays.stream(a).mapToInt(x -> (int) x).toArray();
        }
        List<?> l = (List<?>) list;
        int[] out = new int[l.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = ((Number) l.get(i)).intValue();
        }
        return out;
    }

    private static SimmAnswer.Side side(Double total, List<Map<String, Object>> classes) {
        List<String> rc = new ArrayList<>();
        double[] im = new double[classes.size()];
        for (int i = 0; i < classes.size(); i++) {
            rc.add(String.valueOf(classes.get(i).get("RISK_CLASS")));
            im[i] = ((Number) classes.get(i).get("IM")).doubleValue();
        }
        return new SimmAnswer.Side(total == null ? Double.NaN : total, rc, im);
    }

    /**
     * The CSA's add-on terms and the netting set's notional add-on lines as the engine's params (the host keeps the CSA's terms, as it
     * keeps the version, the regimes and the calculation currency); no CSA record: no add-on, as in-tree.
     */
    private static void addOns(Map<String, Object> prm, SimmData.Day day, SimmData.NettingSet ns, SimmData.Csa csa) {
        if (csa == null) {
            return;
        }
        if (csa.fixed() != 0) {
            prm.put("simm-addon-fixed", csa.fixed());
        }
        if (!csa.multipliers().isEmpty()) {
            prm.put("simm-addon-multipliers", csa.multipliers().entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList());
        }
        if (!csa.factors().isEmpty()) {
            prm.put("simm-addon-factors", csa.factors().entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList());
        }
        List<String> lines = new ArrayList<>();
        for (com.ash.drishti.api.DataNode n : day.addOnLines().getOrDefault(ns.id(), List.of())) {
            String t = SimmData.text(n, "TRADE_ID");
            String pr = SimmData.text(n, "PRODUCT");
            String ccy = SimmData.text(n, "CURRENCY");
            lines.add((t == null ? "" : t) + "|" + (pr == null ? "" : pr) + "|" + SimmData.num(n, "NOTIONAL", 0) + "|" + (ccy == null ? "" : ccy));
        }
        if (!lines.isEmpty()) {
            prm.put("simm-addon-lines", lines);
        }
    }

    private static Double num(Map<String, Object> r, String c) {
        Object v = r.get(c);
        return v instanceof Number n && Double.isFinite(n.doubleValue()) ? n.doubleValue() : null;
    }

    private static Map<String, Double> byProduct(Map<String, Object> answer, String scope) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map<String, Object> r : rows(answer, "simm-product")) {
            if (scope.equals(r.get("SCOPE"))) {
                out.put(String.valueOf(r.get("PRODUCT_CLASS")), ((Number) r.get("SIMM")).doubleValue());
            }
        }
        return out;
    }

    private static Map<String, Object> one(Map<String, Object> answer, String table, String scope) {
        for (Map<String, Object> r : rows(answer, table)) {
            if (scope.equals(r.get("SCOPE"))) {
                return r;
            }
        }
        @SuppressWarnings("unchecked")
        List<Object> miss = (List<Object>) answer.getOrDefault("missing", List.of());
        throw new DrishtiException(ErrorCode.SIMM_NOT_INSTALLED, "the engine " + ENGINE + " answered no " + table + " of " + scope
                + (miss.isEmpty() ? "" : " (not found: " + miss + ")"));
    }

    private static List<Map<String, Object>> rows(Map<String, Object> answer, String name) {
        List<Map<String, Object>> r = com.ash.drishti.banking.exposure.ExposureEnginePath.rows(answer, name);
        return r == null ? List.of() : r;
    }

    private Map<String, Object> call(Principal p, Map<String, Object> params, LocalDate date, List<String> outputs) {
        Engines e = engines.getIfAvailable();
        if (e == null || !e.running(ENGINE)) {
            throw new DrishtiException(ErrorCode.ENGINE_BLOCKED, "the engine " + ENGINE + " is not running here (drishti-risk-ccr in lib/engines and "
                    + "config/engines/ccr.yaml); SIMM answers nothing on the engine path without it");
        }
        try {
            return e.ask(p, ENGINE, outputs, params, Map.of("date", date.toString()));
        } catch (Kernel.Refused r) {
            if (r.code().equals("RSK-3164") || r.code().equals("RSK-3165")) {   // an override or an add-on term the method refuses: the request's
                throw new DrishtiException(ErrorCode.BAD_REQUEST, r.getMessage());
            }
            throw new DrishtiException(r.status() == 403 ? ErrorCode.FORBIDDEN : r.status() == 422 ? ErrorCode.SIMM_NOT_INSTALLED : ErrorCode.ENGINE_BLOCKED,
                    r.getMessage());
        }
    }
}
