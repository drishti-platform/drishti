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

import com.ash.drishti.kernel.api.security.Principal;
import com.ash.drishti.server.approvals.ApprovalQueue;
import java.util.List;
import java.util.Map;

/**
 * What is the person's own and lives outside the Home: the alerts raised for them, the approvals waiting for them (four eyes: never their own
 * item) and the entities they pinned. {@link HomeTables} turns these into the {@code home_needs} and {@code home_pinned} tables; every read is
 * as the person, so one person never sees another's.
 */
public interface HomePersonal {

    /** Nothing of the person's own (a server without alerts, approvals or settings). */
    HomePersonal NONE = new HomePersonal() {
        @Override
        public List<Map<String, Object>> alerts(Principal p) {
            return List.of();
        }

        @Override
        public List<ApprovalQueue.Entry> inbox(Principal p) {
            return List.of();
        }

        @Override
        public List<Map<String, Object>> pinned(Principal p) {
            return List.of();
        }
    };

    /** The person's open alert cases ({@code AlertService.raised} rows: id, title, severity, stage, message, snoozedUntil, ...). */
    List<Map<String, Object>> alerts(Principal p);

    /** The approvals waiting for the person to decide. */
    List<ApprovalQueue.Entry> inbox(Principal p);

    /** The entities the person pinned: {@code {kind, id}}. */
    List<Map<String, Object>> pinned(Principal p);
}
