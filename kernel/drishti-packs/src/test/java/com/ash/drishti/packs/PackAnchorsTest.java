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
package com.ash.drishti.packs;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** G5 of the pack switch: a connector a pack declares reads its map from the pack's own folder ({@code ${pack.dir}} or {@code mappings:}). */
@SuppressWarnings("unchecked")
class PackAnchorsTest {

    @TempDir
    Path dir;

    private Pack pack(Map<String, Object> manifest) {
        return new Pack("murex", "1", "Murex", "", dir, manifest);
    }

    @Test
    void thePackDirTokenBecomesThePacksAbsoluteFolder() {
        Pack p = pack(Map.of("connectors", Map.of("trade-murex", Map.of("plugin", "delta", "kinds", List.of("trade"),
                "settings", Map.of("map", "${pack.dir}/mappings/murex.yaml", "root", "x")))));
        Map<String, Object> s = (Map<String, Object>) p.connectorTemplates().get("trade-murex").get("settings");
        assertThat(s.get("map")).isEqualTo(dir.toAbsolutePath().normalize() + "/mappings/murex.yaml");
        assertThat(s.get("root")).isEqualTo("x");
    }

    @Test
    void aMappingsEntryIsTheMapOfAConnectorOfThatKindWithNoMapOfItsOwn() {
        Pack p = pack(Map.of("mappings", Map.of("trade", "mappings/murex.yaml"),
                "connectors", Map.of("trade-murex", Map.of("plugin", "delta", "kinds", List.of("trade"), "settings", Map.of("root", "x")),
                        "own", Map.of("plugin", "delta", "kinds", List.of("trade"), "settings", Map.of("map", "mine.yaml")),
                        "two", Map.of("plugin", "delta", "kinds", List.of("trade", "curve"), "settings", Map.of()))));
        Map<String, Map<String, Object>> t = p.connectorTemplates();
        assertThat(((Map<String, Object>) t.get("trade-murex").get("settings")).get("map")).isEqualTo(dir.toAbsolutePath().normalize() + "/mappings/murex.yaml");
        assertThat(((Map<String, Object>) t.get("own").get("settings")).get("map")).isEqualTo("mine.yaml");          // a connector's own map wins
        assertThat(((Map<String, Object>) t.get("two").get("settings")).get("map")).isNull();                       // two kinds: no guess
    }
}
