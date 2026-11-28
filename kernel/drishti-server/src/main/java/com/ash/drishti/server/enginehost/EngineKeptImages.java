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
package com.ash.drishti.server.enginehost;

import com.ash.drishti.enginehost.EngineHost;
import com.ash.drishti.enginehost.HostedEngine;
import com.ash.drishti.server.caches.CacheReport;
import com.ash.engine.host.KeptImages;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Admin, Caches: {@code engines.kept}, the engines' kept images (ENGINE_SPEC 18 rule 8): files under {@code <data>/engines/<id>/kept},
 * mapped read-only, keyed by the data version of their inputs (never stale, only superseded). Purge releases and deletes the images no
 * session leases; a leased one stays until its session closes.
 */
@Component
public class EngineKeptImages implements CacheReport.Owner {

    static final String NAME = "engines.kept";

    private final ObjectProvider<EngineHost> host;

    public EngineKeptImages(ObjectProvider<EngineHost> host) {
        this.host = host;
    }

    @Override
    public List<CacheReport> reports() {
        EngineHost h = host.getIfAvailable();
        if (h == null) {
            return List.of();
        }
        long bytes = 0;
        long entries = 0;
        Map<String, Object> each = new LinkedHashMap<>();
        for (HostedEngine e : h.engines()) {
            List<KeptImages.Listed> images = e.keptImages();
            if (images.isEmpty()) {
                continue;
            }
            List<Map<String, Object>> rows = new java.util.ArrayList<>();
            for (KeptImages.Listed l : images) {
                bytes += l.bytes();
                entries++;
                rows.add(Map.of("snapshot", l.snapshot(), "name", l.name(), "bytes", l.bytes(), "leases", l.leases(), "current", l.current()));
            }
            each.put(e.id(), rows);
        }
        return List.of(new CacheReport(NAME, "engine", "Engines' kept images: what an engine builds from a date's inputs (specification 18 rule 8)",
                "mapped", null, bytes, null, entries, null, null, null, null, null, "data-version", false, each));
    }

    @Override
    public List<String> purge(String name, LocalDate date) {
        EngineHost h = host.getIfAvailable();
        if (h == null || !(name.equals(NAME) || name.equals("all"))) {
            return List.of();
        }
        h.engines().forEach(HostedEngine::purgeKept);
        return List.of(NAME);
    }
}
