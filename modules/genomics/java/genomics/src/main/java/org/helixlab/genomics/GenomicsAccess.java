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

import com.ash.drishti.kernel.api.services.Kernel;
import com.ash.drishti.kernel.api.services.Person;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;

/**
 * The kernel's door on every genomics route (pre-gate S1): the module holds the rows of its kinds in one snapshot read as the module, so
 * each route asks the kernel, as the person of the request, whether they may open every kind its answer is made of (their roles and the
 * kinds' packs, as the REST paths of the kernel ask) before a row leaves. A person without the genomics packs (the demo market-data user)
 * is refused with the kernel's own status and words; nothing of the snapshot is shared with them.
 */
public final class GenomicsAccess {

    private final Kernel kernel;

    public GenomicsAccess(Kernel kernel) {
        this.kernel = kernel;
    }

    /** The request's person, refused unless they may open each of {@code kinds}. */
    public Person require(List<String> kinds) {
        Person p = kernel.person();
        kinds.forEach(k -> kernel.access().requireOpen(p, k));
        return p;
    }

    /** A row of {@code kind} with the person's masks applied. */
    public Map<String, Object> redact(Person p, String kind, Map<String, Object> row) {
        return kernel.access().redact(p, kind, row);
    }

    /** The answer of a refusal: the kernel's status, code and words. */
    public static ResponseEntity<Map<String, Object>> refusal(Kernel.Refused e) {
        return ResponseEntity.status(e.status()).body(Map.of("code", e.code(), "detail", String.valueOf(e.getMessage())));
    }
}
