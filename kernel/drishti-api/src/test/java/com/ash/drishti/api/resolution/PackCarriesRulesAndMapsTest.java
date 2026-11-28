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
package com.ash.drishti.api.resolution;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** G5 of the pack switch: a pack carries its own inbox rules ({@code dataload:}) and maps ({@code mappings:}), anchored to its own folder. */
class PackCarriesRulesAndMapsTest {

    @TempDir
    Path tmp;

    private PackResolver.Located pack(String name, String yaml, String rules, String map) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve(name));
        Files.writeString(dir.resolve("pack.yaml"), yaml);
        if (rules != null) {
            Files.writeString(dir.resolve("dataload.yaml"), rules);
        }
        if (map != null) {
            Files.createDirectories(dir.resolve("mappings"));
            Files.writeString(dir.resolve("mappings/murex.yaml"), map);
        }
        return new PackResolver.Located(name, dir, PackResolver.Layer.SITE, null);
    }

    @Test
    void aPacksRulesBelongToThePackAndAreReadFromItsFolder() throws Exception {
        PackResolver.Located murex = pack("murex", "pack: murex\ndataload: dataload.yaml\nmappings: { trade: mappings/murex.yaml }\n",
                "dataload:\n  rules:\n  - { match: 'murex-*.jsonl.gz', kind: trade, connector: trade-murex }\n  - { match: 'x-*.gz', pack: other, kind: x }\n",
                "format: 1\nfields: {}\n");
        PackResolver.Located plain = pack("plain", "pack: plain\n", null, null);
        List<String> problems = new ArrayList<>();
        List<ModuleTemplates.Rule> rules = ModuleTemplates.packRules(List.of(murex, plain), ResolutionSettings::yaml, problems);
        assertThat(problems).isEmpty();
        assertThat(rules).hasSize(2);
        assertThat(rules.get(0).rule()).containsEntry("pack", "murex").containsEntry("connector", "trade-murex");      // the carrying pack is the default
        assertThat(rules.get(1).rule()).containsEntry("pack", "other");                                                // a rule that names its pack keeps it
        assertThat(rules.get(0).module()).isEqualTo("pack:murex");
    }

    @Test
    void aFileOutsideThePacksFolderOrAMissingMapIsAProblemInWords() throws Exception {
        PackResolver.Located bad = pack("bad", "pack: bad\ndataload: ../elsewhere.yaml\nmappings: { trade: mappings/none.yaml }\n", null, null);
        List<String> problems = new ArrayList<>();
        assertThat(ModuleTemplates.packRules(List.of(bad), ResolutionSettings::yaml, problems)).isEmpty();
        assertThat(problems).anyMatch(p -> p.contains("outside the pack's folder")).anyMatch(p -> p.contains("its map for kind trade"));
    }
}
