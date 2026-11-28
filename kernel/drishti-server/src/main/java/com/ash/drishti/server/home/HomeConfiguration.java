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

import com.ash.drishti.kernel.api.home.MeasureSource;
import com.ash.drishti.kernel.api.home.HomeTable;

import com.ash.drishti.engine.time.BusinessDates;
import com.ash.drishti.server.loads.ExpectationService;
import com.ash.drishti.server.loads.LoadRecord;
import com.ash.drishti.server.loads.LoadService;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Role Homes: the catalogue, the store, the snapshot job, the event writer, the attribution runner and the tables. Modules contribute
 * {@link MeasureSource}s and {@link HomeTable}s as beans of their own context; the host is {@link ChangeEngineHost#NOT_HOSTED} until R2.
 * Switched off ({@code drishti.homes.enabled=false}) no job runs and the catalogue is empty.
 */
@Configuration
@EnableConfigurationProperties(HomeProperties.class)
public class HomeConfiguration {

    @Bean
    public HomeCatalogue homeCatalogue(HomeProperties props) {
        return new HomeCatalogue(props);
    }

    @Bean
    public HomeClock homeClock(BusinessDates dates) {
        return new HomeClock(dates, Clock.systemUTC());
    }

    @Bean
    public HomeStore homeStore(HomeProperties props) {
        if (props.dataDir() == null || props.dataDir().isBlank()) {
            throw new IllegalStateException("drishti.homes.data-dir is not set: it is an absolute folder the change detection keeps its snapshots in");
        }
        return new HomeStore(Path.of(props.dataDir()), props.keepDays());
    }

    @Bean
    public ChangeEngineHost changeEngineHost() {
        return ChangeEngineHost.NOT_HOSTED;                       // TODO(R2): the hosted `changes` engine
    }

    @Bean(destroyMethod = "close")
    public MeasureSnapshotJob measureSnapshotJob(HomeProperties props, HomeCatalogue catalogue, HomeStore store, HomeClock clock,
            ObjectProvider<MeasureSource> sources) {
        MeasureSnapshotJob job = new MeasureSnapshotJob(catalogue, store, clock, sources.orderedStream().toList());
        return props.enabled() ? job.start() : job;
    }

    @Bean
    public ChangeEventWriter changeEventWriter(HomeStore store, HomeClock clock, LoadService loads) {
        ChangeEventWriter w = new ChangeEventWriter(store, clock);
        loads.onReady(w::load);
        return w;
    }

    @Bean
    public AttributionRunner attributionRunner(HomeCatalogue catalogue, HomeStore store, HomeClock clock, ObjectProvider<MeasureSource> sources,
            LoadService loads) {
        AttributionRunner r = new AttributionRunner(catalogue, store, clock, sources.orderedStream().toList());
        loads.onReady((LoadRecord l) -> r.run());                  // after each load completes
        return r;
    }

    @Bean
    public HomeTables homeTables(HomeProperties props, HomeStore store, HomeClock clock, ObjectProvider<MeasureSource> sources,
            ObjectProvider<HomeTable> tables, ChangeEngineHost host, ExpectationService expectations, com.ash.drishti.server.security.Entitlements entitlements,
            ObjectProvider<HomePersonal> personal) {
        return new HomeTables(props, store, clock, sources.orderedStream().toList(), tables.orderedStream().toList(), host,
                () -> List.<ExpectationService.State>copyOf(expectations.attention(Clock.systemUTC().instant())), entitlements::mayOpen,
                personal.getIfAvailable(() -> HomePersonal.NONE));
    }

    /** The person's own alerts, approvals and pins, read as the person (an absent service gives none). */
    @Bean
    public HomePersonal homePersonal(ObjectProvider<com.ash.drishti.server.alerts.AlertService> alerts, ObjectProvider<com.ash.drishti.server.approvals.ApprovalQueue> queue,
            ObjectProvider<com.ash.drishti.server.api.MySettings> settings, com.ash.drishti.server.security.Entitlements entitlements) {
        return new HomePersonal() {
            @Override
            public List<java.util.Map<String, Object>> alerts(com.ash.drishti.kernel.api.security.Principal p) {
                var a = alerts.getIfAvailable();
                return a == null ? List.of() : a.raised(p.user(), entitlements.isAdmin(p), true);
            }

            @Override
            public List<com.ash.drishti.server.approvals.ApprovalQueue.Entry> inbox(com.ash.drishti.kernel.api.security.Principal p) {
                var q = queue.getIfAvailable();
                return q == null ? List.of() : q.list(p, "inbox", null, null, 50).items();
            }

            @Override
            public List<java.util.Map<String, Object>> pinned(com.ash.drishti.kernel.api.security.Principal p) {
                var s = settings.getIfAvailable();
                List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
                if (s != null) {
                    s.of(p.user()).path("pinned").forEach(e -> out.add(java.util.Map.of("kind", e.path("kind").asText(), "id", e.path("id").asText())));
                }
                return out;
            }
        };
    }

    @Bean
    public HomeComposer homeComposer(HomeTables tables, HomeCatalogue catalogue, com.ash.drishti.rachana.SutraRegistry sutras,
            com.ash.drishti.engine.ViewPipeline pipeline, com.ash.drishti.server.security.Entitlements entitlements, com.ash.drishti.common.JsonCodec codec,
            com.fasterxml.jackson.databind.ObjectMapper json) {
        return new HomeComposer(tables, catalogue, sutras, pipeline, entitlements, codec, json);
    }
}
