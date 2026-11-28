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
package com.ash.drishti.server.profile;

import com.ash.lake.config.Lakes;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Production rule T1 at start (docs/admin/PRODUCTION_STANDARD.md, "Tables"): under the production profile the server refuses to start while a
 * lake table is resolved only by the lake's layout. It runs when the beans are built, where the lake files are readable with the settings
 * resolved (the profile's own check, {@link ProductionProfile#refusals}, runs before any bean exists and cannot read them); the refusal has
 * the same form, one line per lake ({@link TablesRule}).
 */
@Component
class ProductionTablesCheck {

    ProductionTablesCheck(Environment env) {
        if (!ProductionProfile.production(env)) {
            return;
        }
        List<String> refusals = TablesRule.refusals(Lakes.current(com.ash.drishti.lakehost.DrishtiLake.runtime()));
        if (!refusals.isEmpty()) {
            throw new IllegalStateException(ProductionProfile.message(refusals));
        }
    }
}
