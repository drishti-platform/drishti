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
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The engines' answer caches in Admin, Caches (one row, {@code engines.answers}, with the other caches of the server): each running engine's
 * host cache of final answers by stamp ({@link HostedEngine#answerCache()}), entries against the limit, hits and misses, per engine in the
 * statistics. Keyed by the data versions its sessions read, so never stale, only cold; a purge empties every engine's.
 */
@Component
public class EngineAnswerCaches implements CacheReport.Owner {

    static final String NAME = "engines.answers";

    private final ObjectProvider<EngineHost> host;

    public EngineAnswerCaches(ObjectProvider<EngineHost> host) {
        this.host = host;
    }

    @Override
    public List<CacheReport> reports() {
        EngineHost h = host.getIfAvailable();
        if (h == null) {
            return List.of();
        }
        long entries = 0;
        long limit = 0;
        long hits = 0;
        long misses = 0;
        Map<String, Object> each = new LinkedHashMap<>();
        for (HostedEngine e : h.engines()) {
            long[] a = e.answerCache();
            entries += a[0];
            limit += a[1];
            hits += a[2];
            misses += a[3];
            each.put(e.id(), Map.of("entries", a[0], "hits", a[2], "misses", a[3]));
        }
        return List.of(new CacheReport(NAME, "engine", "Engines' answers, by stamp (specification 13 rule 6)", "heap", null, null, null, entries,
                limit, null, null, hits, misses, "data-version", false, each));
    }

    @Override
    public List<String> purge(String name, LocalDate date) {
        EngineHost h = host.getIfAvailable();
        if (h == null || !(name.equals(NAME) || name.equals("all"))) {
            return List.of();
        }
        h.engines().forEach(HostedEngine::purgeAnswers);
        return List.of(NAME);
    }
}
