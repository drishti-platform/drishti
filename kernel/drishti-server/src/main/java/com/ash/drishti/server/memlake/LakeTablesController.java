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

import com.ash.drishti.api.error.DrishtiException;
import com.ash.drishti.api.error.ErrorCode;
import com.ash.drishti.kernel.api.security.Principal;
import com.ash.drishti.server.security.Entitlements;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin, Lakes: the figures of the tables the lake files name, for the console (which reads no lake folder itself): {@code GET
 * /api/v1/admin/lakes/tables} every table with its rows, files, size, partitions, business dates and last build;
 * {@code GET …/tables/detail?lake=&name=} one table in full (each business date, the columns, the loads of the lake build);
 * {@code GET …/runs} the lakes' run logs. Administrators only. A table is found by its symbolic name in its lake file, never by a folder.
 */
@RestController
public class LakeTablesController {

    private final Entitlements entitlements;

    LakeTablesController(Entitlements entitlements) {
        this.entitlements = entitlements;
    }

    @GetMapping("/api/v1/admin/lakes/tables")
    public Map<String, Object> tables(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> lake : lakes()) {
            for (Map<String, Object> t : tablesOf(lake)) {
                out.add(row(lake, t, false));
            }
        }
        return Map.of("tables", out);
    }

    @GetMapping("/api/v1/admin/lakes/tables/detail")
    public Map<String, Object> detail(@RequestParam("lake") String lakeName, @RequestParam("name") String name,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        for (Map<String, Object> lake : lakes()) {
            if (!lakeName.equals(lake.get("lake"))) {
                continue;
            }
            for (Map<String, Object> t : tablesOf(lake)) {
                if (name.equals(t.get("name"))) {
                    Map<String, Object> m = row(lake, t, true);
                    String root = String.valueOf(lake.get("root"));
                    if (!root.contains("://") && m.get("folder") != null) {
                        m.put("loads", LakeTableFigures.loads(Path.of(root), String.valueOf(m.get("domain")), String.valueOf(m.get("folder")), 20));
                    } else {
                        m.put("loads", List.of());
                    }
                    return m;
                }
            }
        }
        throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no table '" + name + "' in lake '" + lakeName + "' (config/lakes/)");
    }

    @GetMapping("/api/v1/admin/lakes/runs")
    public Map<String, Object> runs(@RequestParam(name = "limit", required = false, defaultValue = "20") int limit,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        int n = Math.max(1, Math.min(limit, 500));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> lake : lakes()) {
            String root = String.valueOf(lake.get("root"));
            if (root.contains("://") || Boolean.TRUE.equals(lake.get("in-memory"))) {
                continue;
            }
            for (String line : LakeTableFigures.runLog(Path.of(root), n)) {
                out.add(Map.of("lake", String.valueOf(lake.get("lake")), "line", line));
            }
        }
        return Map.of("runs", out.subList(Math.max(0, out.size() - n), out.size()));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> lakes() {
        try {
            return com.ash.lake.config.Lakes.current(com.ash.drishti.lakehost.DrishtiLake.runtime()).describe();
        } catch (IllegalArgumentException | java.io.UncheckedIOException e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> tablesOf(Map<String, Object> lake) {
        Object t = lake.get("tables");
        return t instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    private static Map<String, Object> row(Map<String, Object> lake, Map<String, Object> t, boolean detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lake", lake.get("lake"));
        m.put("root", lake.get("root"));
        m.put("name", t.get("name"));
        m.put("kind", t.get("kind"));
        m.put("location", t.get("location"));
        m.put("format", t.get("format"));
        m.put("how", t.get("how"));
        String location = t.get("location") == null ? null : String.valueOf(t.get("location"));
        if (location != null && !location.contains("://")) {
            Path loc = Path.of(location);
            m.put("folder", loc.getFileName() == null ? null : loc.getFileName().toString());
            m.put("domain", loc.getParent() == null || loc.getParent().getFileName() == null ? null : loc.getParent().getFileName().toString());
        }
        if (Boolean.TRUE.equals(lake.get("in-memory"))) {
            m.put("error", "held in memory: see the memory stores");
        } else {
            m.putAll(LakeTableFigures.read(t.get("format") == null ? String.valueOf(lake.get("format")) : String.valueOf(t.get("format")), location, detail));
        }
        return m;
    }
}
