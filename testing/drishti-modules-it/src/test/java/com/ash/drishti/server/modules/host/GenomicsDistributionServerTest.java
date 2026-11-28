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
package com.ash.drishti.server.modules.host;

import com.ash.lake.tck.TestRoots;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.module.runtime.ModuleHealth;
import com.ash.drishti.module.conformance.ModuleConformance;
import com.ash.drishti.server.modules.ModuleNode;
import com.ash.drishti.testkit.ModuleFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Drishti Genomics (docs/architecture/KERNEL_AND_MODULES.md section 20.4) boots from its file alone: the thin kernel (drishti-server, no banking)
 * with the genomics module, built here from its sources (modules/genomics, the SDK's layout) and dropped into the modules folder as an administrator would. Its
 * brand, the Research lens, none of the kernel's packs (no counterparty, book or desk) and no risk word; the module loaded, its kinds read from the genomics lake
 * routed by the kernel like any connector, its kinds, functions, API and pages answering. The .dmod passes the fourteen checks.
 */
@SpringBootTest(properties = {"drishti.distribution=genomics", "drishti.distributions-dir=../../config/distributions",
        "drishti.packs.dir=../../config/packs",
        "drishti.packs.enabled=default", "drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.enabled=false",
        "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/genomics-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class GenomicsDistributionServerTest {

    private static Path work;
    private static Path dmod;

    @Autowired MockMvc mvc;
    @Autowired ModuleNode node;
    @Autowired SourceRouter router;
    final ObjectMapper json = new ObjectMapper();
    private static final java.util.Map<String, String> KEPT = new java.util.LinkedHashMap<>();

    /** The package dropped into the modules folder before the server starts (the launch boots it from the process's settings). */
    @org.junit.jupiter.api.BeforeAll
    static synchronized void drop() throws IOException {
        for (String k : List.of("drishti.modules.home", "drishti.modules.sources", "drishti.modules.dev", "lake.lakes.dir", "drishti.data.dir")) {
            KEPT.put(k, System.getProperty(k));
        }
        if (work == null) {
            work = Path.of(System.getProperty("drishti.test.dir", System.getProperty("java.io.tmpdir"))).resolve("genomics-" + System.nanoTime());
            dmod = genomics(work);
            Files.createDirectories(work.resolve("modules"));
            Files.copy(dmod, work.resolve("modules").resolve(dmod.getFileName()));     // dropped in, as an administrator would
            ModuleFixtures.write(work.resolve("placement.yaml"), "modules:\n  genomics:\n    parts:\n      api: { nodes: [local] }\n");
            loadDemoLake(work.resolve("data").resolve("delta").resolve("genomicslake"));
            Files.createDirectories(work.resolve("lakes"));       // the site's lake file of the module's lake (a site has it from set-up, config/lakes/genomicslake.yaml)
            Files.copy(Path.of(TestRoots.repo(), "config", "lakes", "genomicslake.yaml"), work.resolve("lakes").resolve("genomicslake.yaml"));
        }
        System.setProperty("drishti.modules.home", work.resolve("modules").toString());
        System.clearProperty("drishti.modules.sources");
        System.clearProperty("drishti.modules.dev");
        System.setProperty("drishti.data.dir", work.resolve("data").toString());     // the launch roots: the build's own data folder is a system property too
        System.setProperty("lake.lakes.dir", work.resolve("lakes").toString());     // a launch root: read before the test's dynamic properties exist
    }

    /** The module reads the genomics lake: its demo rows (data/testdata/genomics) loaded into Delta tables the way the data inbox loads them. */
    static void loadDemoLake(Path lake) throws IOException {
        java.util.Map<String, String> keys = java.util.Map.of("variant", "VARIANT_ID", "sample", "SAMPLE_ID", "gene", "GENE");
        for (var e : keys.entrySet()) {
            Path file;
            try (var files = Files.list(Path.of(TestRoots.repo(), "data", "testdata", "genomics", e.getKey()))) {
                file = files.filter(f -> f.getFileName().toString().endsWith(".jsonl.gz")).findFirst().orElseThrow();
            }
            Path cfg = work.resolve("load-" + e.getKey() + ".yaml");
            Files.writeString(cfg, "source:\n  files: '" + file.toAbsolutePath().normalize() + "'\n  as-of: 2026-11-16\n  id: " + e.getValue() + "\ntarget:\n  format: delta\n  path: '"
                    + lake.resolve("genomics").resolve(e.getKey()) + "'\n  domain: genomics\n  kind: " + e.getKey() + "\n  table: " + e.getKey() + "\n");
            java.io.ByteArrayOutputStream said = new java.io.ByteArrayOutputStream();
            assertThat(com.ash.lake.load.LakeLoad.run(com.ash.drishti.lakehost.DrishtiLake.runtime(), new String[] {"--config", cfg.toString(), "-q"},
                    new java.io.PrintStream(said, true, java.nio.charset.StandardCharsets.UTF_8))).as(e.getKey() + ": " + said.toString(java.nio.charset.StandardCharsets.UTF_8)).isZero();
        }
    }

    @org.junit.jupiter.api.AfterAll
    static synchronized void restore() {
        KEPT.forEach((k, v) -> {
            if (v == null) {
                System.clearProperty(k);
            } else {
                System.setProperty(k, v);
            }
        });
    }

    @DynamicPropertySource
    static synchronized void settings(DynamicPropertyRegistry r) {
        r.add("drishti.placement.file", () -> work.resolve("placement.yaml").toString());
        r.add("drishti.data.dir", () -> work.resolve("data").toString());
        r.add("lake.lakes.dir", () -> work.resolve("lakes").toString());
        r.add("drishti.bindings.file", () -> work.resolve("bindings.yaml").toString());
    }

    /** The module as its own build makes it: its sources (java/genomics) compiled against the kernel API, its folder packed. */
    static Path genomics(Path work) {
        Path project = Path.of(TestRoots.repo(), "modules", "genomics").toAbsolutePath().normalize();
        Path jar = ModuleFixtures.jarOf(project.resolve("java/genomics/src/main/java"), null, work.resolve("build"), work.resolve("build/genomics.jar"));
        return ModuleFixtures.pack(project, List.of(jar), work.resolve("dist"));
    }

    JsonNode getJson(String path) throws Exception {
        MvcResult r = mvc.perform(get(java.net.URI.create(path))).andReturn();
        assertThat(r.getResponse().getStatus()).as(path + ": " + r.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(r.getResponse().getContentAsString());
    }

    @Test
    void itBootsFromItsFileAloneWithTheResearchLensAndNoFinanceAnywhere() throws Exception {
        JsonNode about = getJson("/api/v1/about");
        assertThat(about.path("product").asText()).isEqualTo("Drishti Genomics");
        assertThat(about.path("edition").path("name").asText()).isEqualTo("drishti.genomics");
        assertThat(getJson("/public/about").path("lenses").toString()).contains("research");
        List<String> packs = new ArrayList<>();
        getJson("/api/v1/packs").forEach(p -> packs.add(p.path("name").asText()));
        assertThat(packs).as("none of the kernel's packs, no module's finance: the genomics module's own packs: genomics and its two showcase packs")
                .containsExactlyInAnyOrder("genomics", "genomics-showcase", "protein-engineering");
        List<String> suggested = new ArrayList<>();
        getJson("/api/v1/command/suggest?q=C").forEach(s -> suggested.add(s.path("mnemonic").asText()));
        assertThat(suggested).doesNotContain("CPTY", "CCP", "CRV", "CDS");
        JsonNode d = getJson("/api/v1/admin/health").path("distribution");
        assertThat(d.path("id").asText()).isEqualTo("drishti.genomics");
        assertThat(d.path("modules").path("genomics").asText()).isEqualTo("loaded");
        // the console's distributions read the server's module list, again whenever the about answer's fingerprint changes
        JsonNode manifests = getJson("/public/modules/manifests");
        assertThat(manifests.path("fingerprint").asText()).isEqualTo(getJson("/public/about").path("manifests").asText()).isNotBlank();
        JsonNode genomics = null;
        for (JsonNode m : manifests.path("modules")) {
            genomics = "genomics".equals(m.path("id").asText()) ? m : genomics;
        }
        assertThat(genomics).isNotNull();
        assertThat(genomics.path("words").toString()).contains("VARIANT", "GENE", "COHORT");
        assertThat(genomics.path("domain").asText()).isEqualTo("genomics");
    }

    @Test
    void theModuleLoadsReadsTheLakeServesTheKindsAndItsFunctionsAndPagesAnswer() throws Exception {
        assertThat(node.runtime().health()).extracting(ModuleHealth::id, ModuleHealth::state)
                .contains(org.assertj.core.groups.Tuple.tuple("genomics", ModuleHealth.State.LOADED));
        // the kinds are read in the delta connector's "effective" mode (an entity at a time: no whole-kind pass), so the variants are fetched by id
        assertThat(router.fetch(EntityRef.of("variant", "HX-A001"), AsOf.LATEST).get(10, TimeUnit.SECONDS).data().toString()).contains("HX-A001");
        assertThat(router.fetch(EntityRef.of("gene", "TP53"), AsOf.LATEST).get(10, TimeUnit.SECONDS).data().toString()).contains("PROTEIN_LENGTH");
        // the cohorts are computed by the module from the samples (a loaded cohort row only adds to them): no source holds the kind
        assertThat(router.fetch(EntityRef.of("sample", "HXS-013"), AsOf.LATEST).get(10, TimeUnit.SECONDS).data().toString()).contains("COH-B");
        JsonNode gene = getJson("/api/v1/genomics/genes/TP53");
        assertThat(gene.path("variants").size()).isPositive();
        assertThat(getJson("/api/v1/genomics/cohorts").path("count").asInt()).isEqualTo(2);
        for (String page : List.of("/m/genomics/variants", "/m/genomics/genes/TP53", "/m/genomics/cohorts/COH-A", "/m/genomics/variants/HX-A001")) {
            assertThat(node.runtime().routes().lookup(page).orElseThrow().status()).as(page).isEqualTo(200);
        }
    }

    /**
     * The .dmod's declared needs (data/needs.yaml) join the data gate when it loads: variant found by convention serves VARIANT; the site
     * binding variant where nothing serves it switches VARIANT off with the "needs data" page (409 DRS-1035), and only VARIANT.
     */
    @Test
    void unbindingVariantGatesVariantWithItsNeedsDataPage(@Autowired com.ash.drishti.server.bindings.BindingService bindings,
            @Autowired com.ash.drishti.registry.AccessRegistry registry) throws Exception {
        if (registry instanceof com.ash.drishti.registry.EmbeddedAccessRegistry r) {
            r.refresh();                                          // its watch's next look: the installed package is a module of the server
        }
        assertThat(gated()).doesNotContainKey("VARIANT");
        Path file = work.resolve("bindings.yaml");
        Files.writeString(file, "format: 1\nbindings:\n  variant: { demo: true }\n");      // the demo lake, which holds no variant
        try {
            bindings.refresh();
            assertThat(bindings.offReason("VARIANT")).as("the data gate").hasValueSatisfying(r -> assertThat(r).startsWith("VARIANT needs data: variant"));
            assertThat(gated().get("VARIANT")).startsWith("VARIANT needs data: variant");
            assertThat(gated()).doesNotContainKeys("GENE", "COHORT");
        } finally {
            Files.delete(file);
            bindings.refresh();
        }
        assertThat(gated()).doesNotContainKey("VARIANT");
    }

    /** The functions waiting for data, with their words (what the console's gated page shows). */
    private java.util.Map<String, String> gated() throws Exception {
        java.util.Map<String, String> out = new java.util.TreeMap<>();
        getJson("/api/v1/modules").path("functions").path("off").forEach(f -> {
            if ("data".equals(f.path("gate").asText())) {
                out.put(f.path("word").asText(), f.path("text").asText());
            }
        });
        return out;
    }

    @Test
    void itsPackagePassesTheFourteenChecks() {
        List<ModuleConformance.Check> checks = ModuleConformance.run(dmod, Path.of(TestRoots.repo(), "modules", "genomics").toAbsolutePath().normalize(),
                work.resolve("conformance"));
        assertThat(checks).hasSize(14);
        assertThat(checks).allSatisfy(c -> assertThat(c.status()).as(c.line()).isEqualTo(ModuleConformance.Status.PASS));
    }
}
