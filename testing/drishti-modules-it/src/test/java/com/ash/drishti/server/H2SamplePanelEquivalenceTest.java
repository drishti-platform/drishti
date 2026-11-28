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

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** H2 on the packs drawn from their committed samples (the demo connector): {@link H2PanelEquivalence}. */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.packs.enabled=core,central-banks,gdelt-events,national-statistics,oecd,official-news,world-development,genomics-showcase,protein-engineering,market-data,trading,genomics,retail-banking,logistics",
        "drishti.packs.extra-dirs=${drishti.h2.examples}",
        "drishti.security.redact=NAME,TITLE,COUNTRY_NAME,VALUE",
        "drishti.security.roles.masked.kinds[0]=*", "drishti.security.roles.masked.raw=false",
        "drishti.security.roles.full.kinds[0]=*", "drishti.security.roles.full.raw=true"})
@AutoConfigureMockMvc
class H2SamplePanelEquivalenceTest {

    private static final Path SITE;

    static {
        try {
            SITE = H2PanelEquivalence.site("samples");
            System.setProperty("drishti.h2.examples", Path.of(com.ash.lake.tck.TestRoots.repo(), "examples", "packs").toString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void rachana(DynamicPropertyRegistry r) {
        r.add("drishti.rachana.dirs", SITE::toString);
    }

    @org.springframework.beans.factory.annotation.Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @org.springframework.beans.factory.annotation.Autowired com.ash.drishti.server.security.TokenVerifier tokens;
    @org.springframework.beans.factory.annotation.Autowired com.ash.drishti.rachana.SutraRegistry registry;

    @Test
    void theConvertedScreensDrawWhatTheInlinePanelsDrew() throws Exception {
        new H2PanelEquivalence() {
            { this.mvc = H2SamplePanelEquivalenceTest.this.mvc; this.tokens = H2SamplePanelEquivalenceTest.this.tokens; this.sutras = registry; }
            @Override List<String> packs() { return List.of("central-banks", "gdelt-events", "national-statistics", "oecd", "official-news", "world-development", "genomics-showcase", "protein-engineering", "market-data", "trading", "genomics", "retail-banking", "logistics"); }
            @Override Path siteDir() { return SITE; }
        }.proveSame();
    }
}
