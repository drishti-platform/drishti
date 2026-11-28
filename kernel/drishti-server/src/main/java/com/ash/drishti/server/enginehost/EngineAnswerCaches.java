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
 * The host's one answer cache in Admin, Caches ({@code engines.answers}): every engine's kept answers and what the modules shaped from them,
 * in bytes, per engine; purged whole or per business date. It joins the memory budget's {@code engine.answers} pool like every other
 * server cache of answers (its own limit {@code drishti.engine-lib.answers-mb}; the pool's share when smaller). Entries are keyed by the
 * answer's stamp, which names the inputs' lake data versions: a new commit is a new key, and the data-version look drops the old ones.
 */
@Component
public class EngineAnswerCaches implements CacheReport.Owner, com.ash.drishti.server.caches.CacheGroup {

    static final String NAME = "engines.answers";

    private final ObjectProvider<EngineHost> host;

    public EngineAnswerCaches(ObjectProvider<EngineHost> host) {
        this.host = host;
    }

    @Override
    public void join(com.ash.drishti.server.caches.MemoryBudget budget) {
        EngineHost h = host.getIfAvailable();
        com.ash.engine.host.AnswerCache c = h == null ? null : h.answers();
        if (c == null) {
            return;
        }
        long own = h.settings().answersBytes();
        budget.join("engine.answers", new com.ash.drishti.server.caches.MemoryBudget.Member() {
            @Override
            public String name() {
                return NAME;
            }

            @Override
            public long weightedSize() {
                return c.bytes();
            }

            @Override
            public long ownLimit() {
                return own;
            }

            @Override
            public void maximum(long bytes) {
                c.maximum(bytes);
            }
        });
    }

    @Override
    public List<CacheReport> reports() {
        EngineHost h = host.getIfAvailable();
        if (h == null) {
            return List.of();
        }
        com.ash.engine.host.AnswerCache c = h.answers();
        long hits = 0;
        long misses = 0;
        Map<String, Object> each = new LinkedHashMap<>();
        for (HostedEngine e : h.engines()) {
            long[] a = e.answerCache();
            hits += a[2];
            misses += a[3];
            each.put(e.id(), Map.of("entries", a[0], "bytes", a[1], "hits", a[2], "misses", a[3]));
        }
        if (c != null) {
            each.put("evictions", c.evictions());
        }
        return List.of(new CacheReport(NAME, "engine", "Engines' answers by stamp and what modules shaped from them (specification 13 rule 6)",
                "heap", null, c == null ? 0L : c.bytes(), c == null ? 0L : c.maximum(), c == null ? 0L : (long) c.size(), null, null, null,
                hits, misses, "data-version", true, each));
    }

    @Override
    public List<String> purge(String name, LocalDate date) {
        EngineHost h = host.getIfAvailable();
        if (h == null || !(name.equals(NAME) || name.equals("all"))) {
            return List.of();
        }
        h.engines().forEach(e -> e.purgeAnswers(date));
        return List.of(NAME);
    }
}
