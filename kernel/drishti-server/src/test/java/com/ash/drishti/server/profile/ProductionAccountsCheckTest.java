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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ash.drishti.identity.FileAuditLog;
import com.ash.drishti.identity.FileUserStore;
import com.ash.drishti.identity.IdentityProperties;
import com.ash.drishti.identity.PasswordHasher;
import com.ash.drishti.identity.UserService;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Pre-gate S2 (rule P17): a store made under demo or development keeps published passwords; production refuses while any still signs in. */
class ProductionAccountsCheckTest {

    @TempDir Path dir;

    @Test
    void publishedPasswordsAreRefusedUntilChangedOrDisabled() {
        IdentityProperties p = new IdentityProperties(dir.resolve("users.json").toString(), dir.resolve("audit.jsonl").toString(),
                1000, 10, 3, Duration.ofMinutes(15), true, null, null, null, false, false, dir.resolve("prefs").toString(), null, null, null, null, null);
        UserService users = new UserService(new FileUserStore(Path.of(p.usersFile())), new PasswordHasher(1000), new FileAuditLog(Path.of(p.auditFile())), p,
                Set.of("admin", "trader"));
        assertEquals(List.of(), ProductionAccountsCheck.refusals(users), "an empty store");
        assertTrue(users.seedIfEmpty());                                     // what a demo or development start left behind
        DemoAccounts.Account market = DemoAccounts.ALL.stream().filter(a -> a.user().equals("drishti-dev-market")).findFirst().orElseThrow();
        users.create("system", market.user(), new UserService.Profile("Demo Market", "", "Demo", Set.of("trader"), true), market.password(), false);

        List<String> lines = ProductionAccountsCheck.refusals(users);
        assertEquals(2, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("'drishti-dev-admin'") && lines.get(0).contains("Fix:"), lines.get(0));
        assertTrue(lines.get(1).contains("'drishti-dev-market'"), lines.get(1));

        users.resetPassword("system", "drishti-dev-admin", "a-new-long-password-of-the-bank-1");
        users.setEnabled("system", market.user(), false);
        assertEquals(List.of(), ProductionAccountsCheck.refusals(users), "changed or disabled: nothing published can sign in");
    }
}
