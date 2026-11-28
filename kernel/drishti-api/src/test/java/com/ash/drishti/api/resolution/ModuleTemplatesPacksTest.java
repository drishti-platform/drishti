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
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A pack of a module brings its own connector templates ({@code <pack>/connectors/}) and inbox rules ({@code <pack>/dataload.yaml}): the source
 * systems of the module trading-systems (docs/plans/MODULE_RATIONALISATION.md, S3). A template's relative files are read from the pack's folder.
 */
class ModuleTemplatesPacksTest {

    @Test
    void aPacksTemplatesAndRulesAreTheModulesAndAModuleThatIsOffBringsNone(@TempDir Path tmp) throws Exception {
        Path home = Files.createDirectories(tmp.resolve("modules/trading-systems"));
        Path packs = Files.createDirectories(home.resolve("packs"));
        for (String s : List.of("murex", "endur")) {
            Path p = Files.createDirectories(packs.resolve(s + "/connectors"));
            Files.writeString(p.resolve("trade-" + s + ".yaml"), "name: trade-" + s + "\nplugin: delta\nkinds: [trade]\nsettings:\n  map: mappings/" + s + "-trade.yaml\n");
            Files.createDirectories(p.resolve("examples"));
            Files.writeString(p.resolve("examples/files.yaml"), "plugin: file\n");
            Files.writeString(packs.resolve(s + "/dataload.yaml"), "dataload:\n  rules:\n  - match: '" + s + "-*.jsonl.gz'\n    pack: " + s + "\n    kind: trade\n");
        }
        PackResolver.Module m = new PackResolver.Module("trading-systems", home, packs, List.of("trading", "murex", "endur"), "modules/trading-systems",
                null, null, List.of());
        List<String> problems = new ArrayList<>();
        Map<String, ModuleTemplates.Template> t = ModuleTemplates.of(List.of(m), id -> true, ResolutionSettings::yaml, problems);
        assertThat(problems).isEmpty();
        assertThat(t).containsOnlyKeys("trade-endur", "trade-murex");                  // the examples folder is never a template
        Map<String, Object> def = t.get("trade-murex").definition();
        assertThat(def).containsEntry(ModuleTemplates.FROM_MODULE, "trading-systems").containsEntry(ModuleTemplates.FROM_PACK, "murex");
        @SuppressWarnings("unchecked")
        Map<String, Object> settings = (Map<String, Object>) def.get("settings");
        assertThat(Path.of(String.valueOf(settings.get(ModuleTemplates.MODULE_DIR)))).isEqualTo(packs.resolve("murex").toAbsolutePath().normalize());

        List<ModuleTemplates.Rule> rules = ModuleTemplates.rules(List.of(m), id -> true, ResolutionSettings::yaml, problems);
        assertThat(rules).extracting(r -> r.rule().get("pack")).containsExactlyInAnyOrder("murex", "endur");
        assertThat(rules).extracting(ModuleTemplates.Rule::module).containsOnly("trading-systems");

        assertThat(ModuleTemplates.of(List.of(m), id -> false, ResolutionSettings::yaml, problems)).isEmpty();
        assertThat(ModuleTemplates.rules(List.of(m), id -> false, ResolutionSettings::yaml, problems)).isEmpty();
    }
}
