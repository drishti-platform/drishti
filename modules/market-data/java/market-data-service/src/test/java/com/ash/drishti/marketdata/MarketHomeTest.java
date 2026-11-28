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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The market-data Home's tables are the Market landings' answers as rows, for that Home only (no kernel, no lake). */
class MarketHomeTest {

    @Test
    void theTablesAreServedToTheMarketDataHomeOnly() {
        MarketHome.Table t = new MarketHome.Table("market_tiles", c -> List.of());
        assertEquals(List.of("market-data"), t.homes());
        assertEquals("market_tiles", t.name());
    }

    @Test
    void tilesCarryTheirUnitTheirDayChangeAndTheCommandThatOpensThem() {
        Map<String, Object> desk = Map.of("tiles", List.of(
                Map.of("label", "USD OIS 10Y", "value", 4.12, "unit", "%", "cmd", "CRV CRV-USD-OIS", "sub", "CRV CRV-USD-OIS", "change", Map.of("delta", -2.5, "since", "2026-11-27")),
                Map.of("label", "CDS 5Y, median", "value", 88.0, "unit", "", "cmd", "CDS", "sub", "CDS")));
        List<Map<String, Object>> rows = MarketHome.tiles(desk);
        assertEquals("USD OIS 10Y (%)", rows.get(0).get("LABEL"));
        assertEquals(-2.5, rows.get(0).get("DELTA"));
        assertEquals("CRV CRV-USD-OIS", rows.get(0).get("COMMAND"));
        assertEquals("CDS 5Y, median", rows.get(1).get("LABEL"));
        assertNull(rows.get(1).get("DELTA"), "no day change measured: no figure, never an invented one");
    }

    @Test
    void theCurveAndTheMovesAreReadAsTheyAre() {
        Map<String, Object> desk = Map.of("curve", Map.of("ois", List.of(Map.of("tenor", "1Y", "years", 1.0, "rate", 0.041))));
        assertEquals(List.of(Map.of("TENOR", "1Y", "YEARS", 1.0, "RATE", 0.041)), MarketHome.curve(desk));
        assertEquals(List.of(), MarketHome.curve(Map.of()));
        Map<String, Object> moves = Map.of("items", List.of(Map.of("kind", "fx-spot", "id", "FX-EURUSD", "move", 0.8, "unit", "pct", "sigma", 2.1, "date", "2026-11-27", "cmd", "FX FX-EURUSD")));
        Map<String, Object> row = MarketHome.moves(moves).get(0);
        assertEquals("FX-EURUSD", row.get("SUBJECT"));
        assertEquals("FX FX-EURUSD", row.get("COMMAND"));
    }

    @Test
    void theFreshnessSaysHowWholeTheDataIsThenWhatIsWrongCriticalFirst() {
        Map<String, Object> q = Map.of("businessDate", "2026-11-27", "checked", 40, "clean", 38, "cleanPct", 95.0,
                "critical", List.of(Map.of("kind", "ir-curve", "id", "CRV-EUR", "check", "stale", "detail", "5 days old", "cmd", "CRV CRV-EUR")),
                "warnings", List.of(Map.of("kind", "fx-spot", "id", "FX-X", "check", "gap", "detail", "1M missing", "cmd", "FX FX-X")));
        List<Map<String, Object>> rows = MarketHome.freshness(q);
        assertEquals(3, rows.size());
        assertEquals("Checked 40, clean 38 (95.0%)", rows.get(0).get("SUBJECT"));
        assertEquals("high", rows.get(1).get("SEVERITY"));
        assertEquals("ir-curve CRV-EUR", rows.get(1).get("SUBJECT"));
        assertEquals("medium", rows.get(2).get("SEVERITY"));
    }
}
