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

import com.ash.drishti.identity.IdentityProperties;
import com.ash.drishti.identity.UserService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Production rule P17 at start (docs/admin/PRODUCTION_STANDARD.md): a user store first started under the demo or development profile keeps the
 * development administrator and the demo users with their published passwords; under production the server refuses to start while any of
 * them can still sign in with it, one line per account, naming the fix. It runs when the beans are built (the identity store is readable
 * then; the profile's own check runs before any bean exists).
 */
@Component
class ProductionAccountsCheck {

    ProductionAccountsCheck(Environment env, ObjectProvider<UserService> users) {
        UserService u = ProductionProfile.production(env) ? users.getIfAvailable() : null;
        if (u == null) {
            return;
        }
        List<String> refusals = refusals(u);
        if (!refusals.isEmpty()) {
            throw new IllegalStateException(ProductionProfile.message(refusals));
        }
    }

    /** One line per account of the store that still signs in with its published password. */
    static List<String> refusals(UserService users) {
        List<String> out = new ArrayList<>();
        List<String[]> published = new ArrayList<>();
        published.add(new String[] {ProductionProfile.BUILT_IN_ADMIN, IdentityProperties.DEFAULT_SEED_PASSWORD});
        DemoAccounts.ALL.forEach(a -> published.add(new String[] {a.user(), a.password()}));
        for (String[] a : published) {
            if (users.signsInWith(a[0], a[1])) {
                out.add("the account '" + a[0] + "' still signs in with its published password (a store first started under the demo or development profile). "
                        + "Fix: start once under the profile it was made with and change its password or disable it (Admin -> Users), then start under production");
            }
        }
        return out;
    }
}
