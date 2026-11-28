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

import com.ash.lake.tck.TestRoots;
import static org.assertj.core.api.Assertions.assertThat;

import com.ash.lake.inbox.Routes;
import com.ash.drishti.api.resolution.ModuleHomes;
import com.ash.drishti.module.runtime.Dmod;
import com.ash.drishti.module.runtime.KernelFacts;
import com.ash.drishti.module.runtime.ModuleBoot;
import com.ash.drishti.testkit.ModuleFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A site's own source module as a real package (the shipped systems are packs of trading-systems; a site adds a system as a module, as
 * {@code pack new --source-for} writes it): here a module made of the files of the pack {@code swapone}, packed by the one packer
 * (ModulePack), dropped into a modules folder, booted by {@link ModuleBoot} (its inbox rules and its connector templates), then taken out
 * again and gone.
 */
class SourceModuleInstallTest {

    @TempDir
    Path tmp;

    private static Path repo() {
        Path p = Path.of(TestRoots.module());
        while (p != null && !Files.isDirectory(p.resolve("modules/trading-systems/packs/swapone"))) {
            p = p.getParent();
        }
        return p;
    }

    private static void delete(Path root) throws Exception {
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    private static void copy(Path from, Path to) throws Exception {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : s.toList()) {
                Path t = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(t);
                } else {
                    Files.copy(p, t);
                }
            }
        }
    }

    /** The module folders the boot gives for a modules folder (and a cache under {@code tmp}). */
    private List<Path> dirs(Path modules) {
        return ModuleBoot.boot(modules, tmp.resolve("cache"), true, KernelFacts.ofKernel(), List.of()).homes().homes().values().stream()
                .map(ModuleHomes.Home::dir).toList();
    }

    /** The module's rules as the server reads them: the dataload file of each module folder the boot gave. */
    private List<Routes.ModuleRules> rulesOf(Path modules) throws Exception {
        List<Routes.ModuleRules> out = new ArrayList<>();
        YAMLMapper yaml = new YAMLMapper();
        for (Path m : dirs(modules)) {
            JsonNode d = yaml.readTree(Files.readString(m.resolve("module.yaml"))).path("dataload");
            if (d.isTextual()) {
                out.add(new Routes.ModuleRules(ModuleHomes.development(m).id(), yaml.readTree(Files.readString(m.resolve(d.asText())))));
            }
        }
        return out;
    }

    @Test
    void aPackedSourceModuleIsDroppedInSeenAndTakenOutAgain() throws Exception {
        Path folder = tmp.resolve("source-swapone");
        Path pack = repo().resolve("modules/trading-systems/packs/swapone");
        copy(pack.resolve("connectors"), folder.resolve("connectors"));
        copy(pack.resolve("mappings"), folder.resolve("mappings"));
        Files.copy(pack.resolve("dataload.yaml"), folder.resolve("dataload.yaml"));
        Files.createDirectories(folder.resolve("data"));
        Files.createDirectories(folder.resolve("docs"));
        Files.writeString(folder.resolve("data/needs.yaml"), "needs:\n- kind: trade\n  contract: '>=1.0 <2'\n  required: false\n");
        Files.writeString(folder.resolve("docs/README.md"), "---\nmodule: source-swapone\ntitle: Source SwapOne\ndomain: trading\nsummary: SwapOne trades\nkinds: [trade]\n---\n# Source SwapOne\n");
        Files.writeString(folder.resolve("module.yaml"), String.join("\n", "format: 1", "id: source-swapone", "version: 1.0.0", "title: Source SwapOne",
                "about: 'SwapOne OTC swaps (a site module made of the swapone pack files)'", "domain: trading", "origin: first-party",
                "publisher:", "  id: drishti", "  name: Ashutosh Sinha", "licence:", "  feature: trading", "kernel: '>=0.2 <1.0'", "rachana: '>=1.0'",
                "contracts: '>=1.0'", "plugin-api: '>=2.0'", "elements: '>=3.3 <4'", "requires:", "  trading-systems: '>=1.0 <2'", "data:",
                "  needs: data/needs.yaml", "dataload: dataload.yaml", "connectors:", "  folder: connectors", "mappings:",
                "  trade: mappings/swapone-trade.yaml", "docs:", "  readme: docs/README.md", "resources:", "  memory-mb: 16", ""));
        Path out = tmp.resolve("dmod");
        Path dmod = ModuleFixtures.pack(folder, List.of(), out);
        assertThat(dmod.getFileName().toString()).isEqualTo(Dmod.fileName("source-swapone", "1.0.0"));
        assertThat(dmod).isRegularFile();

        Path modules = tmp.resolve("modules");
        assertThat(dirs(modules)).as("nothing installed yet").isEmpty();
        assertThat(rulesOf(modules)).isEmpty();

        Files.createDirectories(modules);
        Files.copy(dmod, modules.resolve(dmod.getFileName()));                    // dropped beside the server
        List<Path> booted = dirs(modules);
        assertThat(booted).hasSize(1);
        Path installed = booted.get(0);
        assertThat(ModuleHomes.development(installed).id()).isEqualTo("source-swapone");
        List<Routes.ModuleRules> rules = rulesOf(modules);
        assertThat(rules).hasSize(1);
        JsonNode rule = rules.get(0).document().path("dataload").path("rules").get(0);
        assertThat(rule.path("match").asText()).isEqualTo("swapone-*.jsonl.gz");
        assertThat(rule.path("date-pattern").asText()).isNotBlank();
        assertThat(rule.path("connector").asText()).isEqualTo("trade-swapone");
        // the templates by their connectors' names (KERNEL_AND_MODULES.md section 18.5); the other shapes are examples to copy
        try (Stream<Path> s = Files.list(installed.resolve("connectors"))) {
            assertThat(s.map(p -> p.getFileName().toString()).sorted().toList()).contains("trade-swapone.yaml", "trade-swapone-live.yaml", "examples");
        }
        assertThat(installed.resolve("connectors/examples/files.yaml")).isRegularFile();
        assertThat(installed.resolve("mappings/swapone-trade.yaml")).isRegularFile();

        Files.delete(modules.resolve(dmod.getFileName()));                         // taken out again
        assertThat(dirs(modules)).as("the module is gone").isEmpty();
        assertThat(rulesOf(modules)).as("its inbox rules are gone").isEmpty();
    }
}
