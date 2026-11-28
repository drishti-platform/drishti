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
package com.ash.drishti.server.sources;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** A source system is its pack (docs/plans/MODULE_RATIONALISATION.md, S3): the pack's name is the system's lower-case name. */
class SourcePackSwitchTest {

    @Test
    void aSystemsPackIsItsLowerCaseName() {
        Set<String> systems = Set.of("ENDUR", "CALYPSO", "MUREX", "WSS", "SWAPONE", "ICI");
        assertThat(systems.stream().map(SourceSystemsWiring::packName).sorted().toList())
                .containsExactly("calypso", "endur", "ici", "murex", "swapone", "wss");
        assertThat(SourceSystemsWiring.packName("Murex Desk 2")).isEqualTo("murex-desk-2");
    }
}
