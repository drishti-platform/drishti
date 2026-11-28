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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** H2 on the lake-backed public packs (gleif, us-equities, funds, world-economy): {@link H2PanelEquivalence}. */
class H2LakePanelEquivalenceTest extends PublicPacksOnLakes {

    private static final Path SITE;

    static {
        try {
            SITE = H2PanelEquivalence.site("lake");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void rachana(DynamicPropertyRegistry r) {
        r.add("drishti.rachana.dirs", SITE::toString);
    }

    @org.springframework.beans.factory.annotation.Autowired com.ash.drishti.rachana.SutraRegistry registry;

    @Test
    void theConvertedScreensDrawWhatTheInlinePanelsDrew() throws Exception {
        new H2PanelEquivalence() {
            { this.mvc = H2LakePanelEquivalenceTest.this.mvc; this.tokens = H2LakePanelEquivalenceTest.this.tokens; this.sutras = registry; }
            @Override List<String> packs() { return List.of("gleif", "us-equities", "funds", "world-economy"); }
            @Override Path siteDir() { return SITE; }
        }.proveSame();
    }
}
