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
package com.ash.drishti.marketdata;

import com.ash.drishti.kernel.api.home.HomeTable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * What the market-data module gives the market-data Home (docs/architecture/ROLE_HOME_SUTRAS.md): four home tables served to that Home only,
 * each the person's own answer of {@link MarketLanding} (the curves, FX and prices of the Market desk, the biggest moves, and the data
 * checks that say how fresh and whole the data is). Nothing is computed here beyond turning those answers into rows; a figure the data does not hold
 * is a row with no value, never an invented one.
 */
@Configuration
public class MarketHome {

    /** The Home these tables are for. */
    public static final String HOME = "market-data";

    /** One home table of the module. */
    public record Table(String name, Function<HomeTable.Context, List<Map<String, Object>>> read) implements HomeTable {
        @Override
        public List<String> homes() {
            return List.of(HOME);
        }

        @Override
        public List<Map<String, Object>> rows(Context c) {
            return read.apply(c);
        }
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object o) {
        return o instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    /** The desk's tiles: the USD OIS 10Y, the 2s10s slope, the EUR/USD 1M ATM vol and the CDS median, with their day change. */
    static List<Map<String, Object>> tiles(Map<String, Object> desk) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> t : list(desk.get("tiles"))) {
            Object change = t.get("change");
            Object delta = change instanceof Map<?, ?> c ? c.get("delta") : null;
            String unit = String.valueOf(t.getOrDefault("unit", ""));
            out.add(row("LABEL", t.get("label") + (unit.isEmpty() ? "" : " (" + unit + ")"), "VALUE", t.get("value"), "DELTA", delta,
                    "CAPTION", t.get("sub"), "COMMAND", t.get("cmd")));
        }
        return out;
    }

    /** The USD OIS curve of the business date: tenor and zero rate. */
    static List<Map<String, Object>> curve(Map<String, Object> desk) {
        List<Map<String, Object>> out = new ArrayList<>();
        Object curve = desk.get("curve");
        for (Map<String, Object> p : curve instanceof Map<?, ?> c ? list(c.get("ois")) : List.<Map<String, Object>>of()) {
            out.add(row("TENOR", p.get("tenor"), "YEARS", p.get("years"), "RATE", p.get("rate")));
        }
        return out;
    }

    /** The biggest moves of the last weeks (rates, spreads, FX, commodities, indices), each with the command that opens it. */
    static List<Map<String, Object>> moves(Map<String, Object> moves) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> x : list(moves.get("items"))) {
            out.add(row("SUBJECT", x.get("id"), "KIND", x.get("kind"), "MOVE", x.get("move"), "UNIT", x.get("unit"), "SIGMA", x.get("sigma"),
                    "DATE", x.get("date"), "COMMAND", x.get("cmd")));
        }
        return out;
    }

    /** The data checks: what is stale, has gaps or is broken, critical first; a row of the summary comes first. */
    static List<Map<String, Object>> freshness(Map<String, Object> q) {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(row("SEVERITY", "info", "SUBJECT", "Checked " + q.get("checked") + ", clean " + q.get("clean")
                + (q.get("cleanPct") == null ? "" : " (" + q.get("cleanPct") + "%)"), "MESSAGE", "as of " + q.get("businessDate"), "COMMAND", null));
        for (String sev : List.of("critical", "warnings")) {
            for (Map<String, Object> e : list(q.get(sev))) {
                out.add(row("SEVERITY", "critical".equals(sev) ? "high" : "medium", "SUBJECT", e.get("kind") + " " + e.get("id"),
                        "MESSAGE", e.get("check") + ": " + e.get("detail"), "COMMAND", e.get("cmd")));
            }
        }
        return out;
    }

    @Bean
    public HomeTable marketTiles(MarketLanding landing) {
        return new Table("market_tiles", c -> tiles(landing.desk(landing.caller())));
    }

    @Bean
    public HomeTable marketCurve(MarketLanding landing) {
        return new Table("market_curve", c -> curve(landing.desk(landing.caller())));
    }

    @Bean
    public HomeTable marketMoves(MarketLanding landing) {
        return new Table("market_moves", c -> moves(landing.moves(landing.caller())));
    }

    @Bean
    public HomeTable marketFreshness(MarketLanding landing) {
        return new Table("market_freshness", c -> {
            var asker = landing.caller();
            return freshness(landing.quality(asker, landing.staleDays(asker)));
        });
    }
}
