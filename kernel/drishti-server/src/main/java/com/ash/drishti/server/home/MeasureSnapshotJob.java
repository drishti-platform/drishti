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
package com.ash.drishti.server.home;

import com.ash.drishti.kernel.api.home.HomeData;
import com.ash.drishti.kernel.api.home.MeasureSource;
import com.ash.drishti.kernel.api.home.HomeDefinition;

import com.ash.drishti.kernel.api.security.Principal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The 17:00 snapshot (ROLE_HOME_SUTRAS.md 4.2): at each Home's cut-off it asks the followed engines for every followed measure, once for
 * the firm (the firm principal sees everything), and stores them as the reference of the drivers run ({@link AttributionRunner}) only. A
 * stored firm figure never reaches a person: the home tables ask every earlier figure (KPI delta and history, limit change, changes, digest)
 * of the engine as the person, so their row rules and masks apply (session isolation). A scheduled service with a side effect, not an engine. It fires at most once per cut-off and day, survives an engine that is
 * off or has no data (that measure is left out and said), and can be run at once ({@link #run}) after a late load.
 */
public final class MeasureSnapshotJob implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(MeasureSnapshotJob.class);
    /** The firm principal: every row, never reaches a person. */
    static final Principal FIRM = new Principal("homes", List.of("admin"));

    private final HomeCatalogue catalogue;
    private final HomeStore store;
    private final HomeClock clock;
    private final Map<String, MeasureSource> sources;
    private final java.util.concurrent.ScheduledExecutorService timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "home-snapshot");
        t.setDaemon(true);
        return t;
    });
    private volatile LocalDate lastDay;
    private volatile String lastCutoff = "";

    public MeasureSnapshotJob(HomeCatalogue catalogue, HomeStore store, HomeClock clock, List<MeasureSource> sources) {
        this.catalogue = catalogue;
        this.store = store;
        this.clock = clock;
        Map<String, MeasureSource> m = new java.util.LinkedHashMap<>();
        sources.forEach(s -> m.put(s.engine(), s));
        this.sources = Map.copyOf(m);
    }

    /** Checks every minute whether a Home's cut-off has passed today without a snapshot of today's business date. */
    public MeasureSnapshotJob start() {
        timer.scheduleWithFixedDelay(this::tick, 30, 60, java.util.concurrent.TimeUnit.SECONDS);
        return this;
    }

    void tick() {
        try {
            LocalDate day = clock.today();
            for (String cut : cutoffs()) {
                if (!clock.now().isBefore(clock.cutoff(day, LocalTime.parse(cut))) && !(day.equals(lastDay) && cut.equals(lastCutoff))
                        && !store.hasSnapshot(day)) {
                    run(day, LocalTime.parse(cut));
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("home snapshot: {}", e.toString());
        }
    }

    private Set<String> cutoffs() {
        Set<String> out = new LinkedHashSet<>();
        for (HomeDefinition d : catalogue.all()) {
            out.add(d.measures().cutoff() == null ? "17:00" : d.measures().cutoff());
        }
        return out;
    }

    /** Takes the snapshot of {@code day} now, taken to be the cut-off {@code at}: every followed measure of every Home, each measure once. */
    public synchronized int run(LocalDate day, LocalTime at) {
        List<HomeData.MeasureValue> values = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (HomeDefinition d : catalogue.all()) {
            for (HomeDefinition.Follow f : d.measures().follow()) {
                MeasureSource s = HomeSources.find(sources, f.engine());
                if (s == null || !s.on()) {
                    LOG.info("home snapshot: {} left out, engine {} is {}", f.measure(), f.engine(), s == null ? "not present" : "off");
                    continue;
                }
                if (seen.add(f.engine() + "|" + f.measure() + "|" + f.subjects())) {
                    values.addAll(s.values(FIRM, f.measure(), f.subjects(), day));
                }
            }
        }
        store.putSnapshot(day, clock.cutoff(day, at), values);
        lastDay = day;
        lastCutoff = at.toString();
        return values.size();
    }

    @Override
    public void close() {
        timer.shutdownNow();
    }
}
