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
package com.ash.drishti.inference;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.rachana.LiveCompiler;
import com.ash.drishti.rachana.RachanaProperties;
import com.ash.drishti.rachana.SutraMatcher;
import com.ash.drishti.rachana.SutraRegistry;
import com.ash.rachana.format.Formats;
import com.ash.rachana.model.Panel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A pack's page section (pack.yaml sections:, a *.section.yaml in its Sutra folder): shown for its rows only, gone with the pack. */
class PackSectionsTest {

    @TempDir
    Path dir;

    private static String section(String system) {
        return "rachana: 1.2\nsutra: " + system + "-native-fields\nversion: 1\nmatch: { kind: trade, where: \"$.SOURCE_SYSTEM == '" + system.toUpperCase() + "'\" }\n"
                + "title: { pill: " + system + ", id: $.TRADE_ID }\npanels:\n  - id: " + system + "-native\n    kind: kv\n    title: native\n    columns:\n"
                + "      - { label: \"DEAL_REF\", bind: \"$.TRADE_ID\" }\n";
    }

    @Test
    void aSectionAppearsForItsSystemsRowsOnlyAndFollowsThePackSwitch() throws Exception {
        Path endur = Files.createDirectories(dir.resolve("packs/endur/sutras"));
        Path murex = Files.createDirectories(dir.resolve("packs/murex/sutras"));
        Files.writeString(endur.resolve("endur-native-fields.v1.section.yaml"), section("endur"));
        Files.writeString(murex.resolve("murex-native-fields.v1.section.yaml"), section("murex"));
        RachanaProperties props = new RachanaProperties(List.of(), false, null, null, null, null, List.of(endur.toString(), murex.toString()), null, null, null);
        LiveCompiler el = LiveCompiler.current();
        Formats f = Formats.load(null, List.of());
        try (SutraRegistry r = new SutraRegistry(props, el)) {
            assertThat(r.problems()).isEmpty();
            LayoutMerger merger = new LayoutMerger(new InferenceEngine(Semantics.load(null, List.of()), Rules.builtIn()), el, f).sections(r::sectionsFor);
            SutraMatcher matcher = new SutraMatcher(r, el, f);
            JsonCodec json = new JsonCodec();
            DataNode endurRow = json.read("{\"SOURCE_SYSTEM\":\"ENDUR\",\"TRADE_ID\":\"1\",\"PRODUCT_TYPE\":\"Cash\"}");
            DataNode murexRow = json.read("{\"SOURCE_SYSTEM\":\"MUREX\",\"TRADE_ID\":\"2\",\"PRODUCT_TYPE\":\"Cash\"}");

            var layout = merger.merge(Optional.empty(), endurRow, "trade").sutra();
            assertThat(layout.panels()).extracting(Panel::id).contains("endur-native", "murex-native");   // the layout is one for every row of the shape
            assertThat(matcher.shownByWhen(layout, endurRow)).contains("endur-native").doesNotContain("murex-native");
            assertThat(matcher.shownByWhen(layout, murexRow)).contains("murex-native").doesNotContain("endur-native");
            assertThat(merger.sectionIds("trade")).hasSize(2);
            assertThat(merger.sectionIds("curve")).isEmpty();

            r.packSwitch(p -> !p.equals("endur"));                                   // the pack is switched off: its section is gone
            assertThat(merger.sectionIds("trade")).containsExactly("murex-native-fields@1");
            assertThat(merger.merge(Optional.empty(), endurRow, "trade").sutra().panels()).extracting(Panel::id).doesNotContain("endur-native");
            assertThat(r.forKind("trade")).as("a section is not a Sutra").isEmpty();
        }
    }
}
