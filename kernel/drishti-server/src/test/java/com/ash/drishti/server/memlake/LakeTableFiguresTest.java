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
package com.ash.drishti.server.memlake;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.lakehost.LakeRowsLoad;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The figures the console's Admin -> Lakes shows, read from a real Delta table at a location the lake file names (no layout assumed). */
class LakeTableFiguresTest {

    @TempDir
    Path tmp;

    @Test
    void figuresAndDetailOfATableAtAnyFolder() throws Exception {
        Path table = tmp.resolve("mart").resolve("risk").resolve("scenario_pv");          // a folder the lake file named, not Drishti's layout
        for (int d = 1; d <= 2; d++) {
            List<LakeRowsLoad.Row> rows = List.of(new LakeRowsLoad.Row("T-1", "{\"id\":\"T-1\"}", Map.of("DESK", "A")),
                    new LakeRowsLoad.Row("T-2", "{\"id\":\"T-2\"}", Map.of("DESK", "B")));
            LakeRowsLoad.load("delta", table.toString(), "risk", "scenario_pv", LocalDate.of(2026, 11, d), "replace-date", rows, List.of("DESK"),
                    List.of(), Map.of(), Map.of());
        }
        Map<String, Object> f = LakeTableFigures.read("delta", table.toString(), true);
        assertThat(f).doesNotContainKey("error");
        assertThat(f.get("rows")).isEqualTo(4L);
        assertThat(f.get("dates")).isEqualTo(2);
        assertThat(f.get("latestDate")).isEqualTo("2026-11-02");
        assertThat(f.get("firstDate")).isEqualTo("2026-11-01");
        assertThat((List<?>) f.get("byDate")).hasSize(2);
        assertThat(((Map<?, ?>) ((List<?>) f.get("byDate")).get(0)).get("date")).isEqualTo("2026-11-02");     // newest first
        assertThat(String.valueOf(f.get("columns"))).contains("DESK");
        assertThat(f.get("lastBuild")).isNotNull();
    }

    @Test
    void anAbsentOrRemoteTableSaysSoInsteadOfFailing() {
        assertThat(LakeTableFigures.read("delta", tmp.resolve("x/y/none").toString(), false)).containsKey("error");
        assertThat(LakeTableFigures.read("delta", "s3://bucket/t", false).get("error").toString()).contains("not on this server");
    }

    @Test
    void loadsAndRunLogComeFromTheLakeRootsRecords() throws Exception {
        Path runs = Files.createDirectories(tmp.resolve("_drishti-lake").resolve("runs"));
        Files.writeString(runs.resolve("20261101T000000Z.json"),
                "{\"run\":\"r1\",\"table\":\"risk/trade\",\"status\":\"done\",\"mode\":\"overwrite-dates\",\"rows\":{\"2026-11-01\":5,\"2026-11-02\":7},\"rejected\":1}");
        Files.writeString(runs.resolve("20261102T000000Z.json"), "{\"run\":\"r2\",\"table\":\"other/x\",\"status\":\"done\"}");
        Files.writeString(tmp.resolve("_drishti-lake").resolve("runs.log"), "a\nb\nc\n");
        var loads = LakeTableFigures.loads(tmp, "risk", "trade", 20);
        assertThat(loads).hasSize(1);
        assertThat(loads.get(0)).containsEntry("rows", 12L).containsEntry("dates", "2026-11-01, 2026-11-02").containsEntry("rejected", 1L);
        assertThat(LakeTableFigures.runLog(tmp, 2)).containsExactly("b", "c");
    }
}
