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
package com.ash.drishti.server.calc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ash.drishti.api.error.DrishtiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The risk statistics return the numbers the browser's old Python (drishti_quant.py) returned. */
class RiskStatsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static Map<String, Object> run(String fn, String body) throws Exception {
        return RiskStats.run(fn, JSON.readTree(body));
    }

    private static double d(Map<String, Object> m, String k) {
        return ((Number) m.get(k)).doubleValue();
    }

    @Test
    void kupiecMatchesTheOldPythonAndChiSquared() throws Exception {
        Map<String, Object> k = run("kupiec-pof", "{\"exceptions\":0,\"n\":250,\"confidence\":0.99}");
        assertEquals(5.025167926750226, d(k, "lr"), 1e-12);
        assertEquals(0.024981503053456915, d(k, "pvalue"), 1e-12);
        assertEquals("green", k.get("light"));
        k = run("kupiec-pof", "{\"exceptions\":8,\"n\":250}");
        assertEquals(7.7335507244945205, d(k, "lr"), 1e-12);
        assertEquals(0.0054204051941277925, d(k, "pvalue"), 1e-12);
        assertEquals("amber", k.get("light"));
        k = run("kupiec-pof", "{\"exceptions\":12,\"n\":250}");
        assertEquals(1.2961433017631004e-05, d(k, "pvalue"), 1e-15);
        assertEquals("red", k.get("light"));
        k = run("kupiec-pof", "{\"exceptions\":3,\"n\":100,\"confidence\":0.95}");
        assertEquals(0.322975471817906, d(k, "pvalue"), 1e-12);
    }

    @Test
    void drawdownSharpeAndHhi() throws Exception {
        Map<String, Object> dd = run("max-drawdown", "{\"values\":[100,120,90,95,130,70,80]}");
        assertEquals(-60.0, d(dd, "drawdown"));
        assertEquals(4, dd.get("peak"));
        assertEquals(5, dd.get("trough"));
        assertEquals(List.of(0.0, 0.0, -30.0, -25.0, 0.0, -60.0, -50.0), dd.get("series"));
        assertEquals(0.29814239699997197, d(run("sharpe", "{\"pnl\":[1,-1,1,-1,2],\"periods\":1}"), "value"), 1e-12);
        assertEquals(4.991518229686048, d(run("sharpe", "{\"pnl\":[1,2,-1,3,0.5,-2]}"), "value"), 1e-12);
        assertEquals(0.5, d(run("hhi", "{\"amounts\":[5,-5]}"), "value"), 1e-15);
        assertEquals(0.3, d(run("hhi", "{\"amounts\":[3,1,2,4]}"), "value"), 1e-12);
        assertTrue(Double.isNaN(d(run("hhi", "{\"amounts\":[0,0]}"), "value")));
    }

    @Test
    void frtbAggregation() throws Exception {
        assertEquals(6.082762530298219, d(run("frtb-bucket-charge", "{\"ws\":[3,4],\"rho\":0.5}"), "value"), 1e-12);
        assertEquals(1.0, d(run("frtb-bucket-charge", "{\"ws\":[3,-4],\"rho\":1.0}"), "value"), 1e-12);
        assertEquals(5.813776741499453, d(run("frtb-bucket-charge",
                "{\"ws\":[3,4,-2],\"rho\":[[1,0.5,0.2],[0.5,1,0.3],[0.2,0.3,1]]}"), "value"), 1e-12);
        assertEquals(5.0, d(run("frtb-across-buckets", "{\"kb\":[5,5],\"sb\":[5,-5],\"gamma\":0.5}"), "value"), 1e-12);
        assertEquals(0.0, d(run("frtb-across-buckets", "{\"kb\":[1,1],\"sb\":[1,-1],\"gamma\":1.0}"), "value"), 1e-12);
        assertEquals(4.8373546489791295, d(run("frtb-across-buckets",
                "{\"kb\":[4,3,2],\"sb\":[1,-2,3],\"gamma\":[[0,0.5,0.2],[0.5,0,0.4],[0.2,0.4,0]]}"), "value"), 1e-12);
    }

    @Test
    void badInputIsRefused() {
        assertThrows(DrishtiException.class, () -> run("nope", "{}"));
        assertThrows(DrishtiException.class, () -> run("hhi", "{\"amounts\":3}"));
        assertThrows(DrishtiException.class, () -> run("frtb-bucket-charge", "{\"ws\":[1,2],\"rho\":[[1]]}"));
    }
}
