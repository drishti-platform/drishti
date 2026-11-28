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
package org.helixlab.genomics;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.kernel.api.services.Access;
import com.ash.drishti.kernel.api.services.Kernel;
import com.ash.drishti.kernel.api.services.Person;
import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The kernel's doors as the tests need them: the person of the request is set by the test, and a role opens the kinds the demo accounts'
 * roles open (genomics-reader: the three genomics packs' kinds; market-data-reader: none of them), as the kernel's entitlements answer.
 */
final class Doors {

    static final Set<String> GENOMICS_READER = Set.of("variant", "sample", "gene", "cohort", "study-gene", "study-sample", "study-variant", "variant-call",
            "association", "cnv-segment", "pathway-gene", "protein", "residue", "residue-contact", "dms-score", "design", "design-mutation", "design-round");

    String user = "drishti-dev-genomics";
    List<String> roles = List.of("genomics-reader");
    final java.util.List<String> asked = new java.util.ArrayList<>();

    GenomicsAccess access() {
        Access a = new Access() {
            public void requireOpen(Person p, String kind) {
                asked.add(p.user() + ":" + kind);
                if (!p.roles().contains("genomics-reader") || !GENOMICS_READER.contains(kind)) {
                    throw new Kernel.Refused(403, "DRS-1003", p.user() + " may not open " + kind + " entities");
                }
            }

            public Map<String, Object> redact(Person p, String kind, Map<String, Object> data) {
                return data;
            }
        };
        Kernel k = (Kernel) Proxy.newProxyInstance(Kernel.class.getClassLoader(), new Class<?>[] {Kernel.class}, (proxy, m, args) -> switch (m.getName()) {
            case "person" -> person(user, roles);
            case "access" -> a;
            default -> throw new UnsupportedOperationException(m.getName());
        });
        return new GenomicsAccess(k);
    }

    static Person person(String user, List<String> roles) {
        return new Person() {
            public String user() {
                return user;
            }

            public String display() {
                return "";
            }

            public boolean admin() {
                return false;
            }

            public List<String> roles() {
                return roles;
            }

            public String lens() {
                return "";
            }

            public AsOf asOf() {
                return AsOf.LATEST;
            }

            public String key() {
                return user + "|" + roles;
            }

            public Person onDate(LocalDate day) {
                return this;
            }
        };
    }
}
