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
package com.ash.drishti.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.rachana.SutraRegistry;
import com.ash.drishti.server.security.TokenVerifier;
import com.ash.lake.tck.TestRoots;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * H2: a screen whose inline table, record and tabs panels became uses of the Rachana Core fragments draws exactly what the inline
 * panels drew. The Sutras as they were are kept under {@code src/test/resources/sutra-next-h2/<pack>/<name>.sutra.yaml} (version 99,
 * so they replace the converted ones when set beside them); the view of each kind's first committed sample is read with the converted
 * Sutra, then with the old one, for a reader who sees everything and for one whose role masks fields, and the two compared with the
 * moment and the layout's name left out. A panel key that was absent or null before and is empty now is reported, never silently let by.
 */
abstract class H2PanelEquivalence {

    static final Set<String> VOLATILE = Set.of("fingerprint", "layout", "fetchedAt", "generation", "builtAt", "tookMs", "elapsedMs", "servedBy", "timings", "phone");
    static final ObjectMapper JSON = new ObjectMapper();

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired SutraRegistry sutras;

    /** The packs whose old Sutras (folders of sutra-next-h2) this test proves. */
    abstract List<String> packs();

    static Path site(String name) throws Exception {
        Path site = TestRun.root().resolve(Path.of("h2-" + name, "sutras"));
        Files.createDirectories(site);
        try (var s = Files.list(site)) {
            for (Path f : s.toList()) {
                Files.delete(f);
            }
        }
        return site;
    }

    abstract Path siteDir();

    private static final Pattern KIND = Pattern.compile("(?m)^match:\\s*\\{\\s*kind:\\s*([\\w-]+)");

    /** The first committed sample id of each kind of a pack. */
    static Map<String, String> samples(String pack) throws Exception {
        Map<String, String> first = new LinkedHashMap<>();
        try (var w = Files.walk(Path.of(TestRoots.repo()), 8)) {
            for (Path c : w.filter(p -> p.endsWith(Path.of(pack, "samples", "catalog.jsonl")) && !p.toString().contains("/target/")).toList()) {
                for (String line : Files.readAllLines(c)) {
                    if (!line.isBlank()) {
                        JsonNode e = JSON.readTree(line);
                        first.putIfAbsent(e.path("kind").asText(), e.path("id").asText());
                    }
                }
            }
        }
        return first;
    }

    /** The old Sutras: file name -> {kind, text}. */
    List<String[]> olds() throws Exception {
        List<String[]> out = new ArrayList<>();
        for (String pack : packs()) {
            Path dir = Path.of(TestRoots.repo(), "testing", "drishti-modules-it", "src", "test", "resources", "sutra-next-h2", pack)   /* this module's own folder: lake.module.root names the server's */;
            if (!Files.isDirectory(dir)) {
                continue;
            }
            Map<String, String> sample = samples(pack);
            try (var s = Files.list(dir)) {
                for (Path f : s.sorted().toList()) {
                    String text = Files.readString(f);
                    Matcher m = KIND.matcher(text);
                    String kind = m.find() ? m.group(1) : f.getFileName().toString().replace(".sutra.yaml", "");
                    out.add(new String[] {pack, f.getFileName().toString(), kind, sample.getOrDefault(kind, ""), f.toString()});
                }
            }
        }
        return out;
    }

    JsonNode read(String kind, String id, String role) throws Exception {
        return JSON.readTree(mvc.perform(get("/api/v1/views/" + kind + "/" + id).header("Authorization", "Bearer " + tokens.mint("u-" + role, List.of(role), 300)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    static JsonNode normal(JsonNode n, boolean dropNulls) {
        if (n instanceof ObjectNode o) {
            for (Iterator<String> it = o.fieldNames(); it.hasNext(); ) {
                String f = it.next();
                if (VOLATILE.contains(f) || dropNulls && o.get(f).isNull()) {
                    it.remove();
                }
            }
            o.forEach(c -> normal(c, dropNulls));
        } else if (n != null && n.isArray()) {
            n.forEach(c -> normal(c, dropNulls));
        }
        return n;
    }

    static String same(JsonNode view, boolean dropNulls) {
        return normal(view.deepCopy(), dropNulls).toString().replaceAll("Sutra ([\\w-]+) v\\d+", "Sutra $1 vN");
    }

    void proveSame() throws Exception {
        List<String[]> olds = olds();
        assertThat(olds).as("old Sutras of " + packs()).isNotEmpty();
        Map<String, JsonNode[]> after = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (String[] o : olds) {
            if (o[3].isEmpty()) {
                missing.add(o[0] + "/" + o[1] + " (no committed sample of " + o[2] + ")");
                continue;
            }
            after.put(o[1], new JsonNode[] {read(o[2], o[3], "full"), read(o[2], o[3], "masked")});
        }
        List<Path> copied = new ArrayList<>();
        List<String> fails = new ArrayList<>();
        List<String> nulls = new ArrayList<>();
        try {
            for (String[] o : olds) {
                if (after.containsKey(o[1])) {
                    Path p = siteDir().resolve(o[0] + "-" + o[1]);
                    Files.copy(Path.of(o[4]), p);
                    copied.add(p);
                }
            }
            sutras.reload();
            for (String[] o : olds) {
                if (!after.containsKey(o[1])) {
                    continue;
                }
                for (int i = 0; i < 2; i++) {
                    String role = i == 0 ? "full" : "masked";
                    JsonNode before = read(o[2], o[3], role);
                    JsonNode now = after.get(o[1])[i];
                    assertThat(before.path("provenance").path("layout").asText()).as(o[1]).contains(" v99");
                    if (before.path("panels").size() < 1) {
                        fails.add(o[1] + " " + role + ": the old view has no panels");
                    }
                    if (!same(before, false).equals(same(now, false))) {
                        if (same(before, true).equals(same(now, true))) {
                            nulls.add(o[1] + " " + role);
                        } else {
                            Path out = siteDir().getParent();
                            Files.writeString(out.resolve(o[1] + "-" + role + "-before.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(normal(before.deepCopy(), true)));
                            Files.writeString(out.resolve(o[1] + "-" + role + "-after.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(normal(now.deepCopy(), true)));
                            fails.add(o[1] + " " + role + " differs (" + out + ")");
                        }
                    }
                }
            }
        } finally {
            for (Path p : copied) {
                Files.deleteIfExists(p);
            }
            sutras.reload();
        }
        System.out.println("H2 compared " + after.size() + " screens; null-only differences: " + nulls + "; no sample: " + missing);
        assertThat(fails).as("screens that differ").isEmpty();
        assertThat(missing).as("old Sutras with no sample to draw").isEmpty();
    }
}
