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
package com.ash.drishti.identity;

import java.time.Instant;
import java.util.Optional;

/**
 * Which packs administrators switched off (Admin → Packs): a pack's state is the {@code pack} entry of the switches ledger, the same
 * table as the commands' switches, spread to every node of a cluster by Drishti*net, so nodes with databases of their own agree. Every
 * access check reads the ledger's snapshot (lock-free); a change is serialised by the ledger, audited, and applies at the next request.
 */
public final class PackStateStore {

    /** One pack's state as last set by an administrator. */
    public record State(String name, boolean enabled, Instant updatedAt, String updatedBy) {}

    private final AuditLog audit;
    private final SwitchLedger ledger;

    public PackStateStore(AuditLog audit, SwitchLedger ledger) {
        this.audit = audit;
        this.ledger = ledger;
    }

    /** Moves whenever the states change (a pack switched on or off, here or on a peer): callers that cache what follows from them compare it. */
    public long version() {
        return ledger.version();
    }

    /** True unless an administrator switched the pack off. */
    public boolean enabled(String pack) {
        return ledger.on(SwitchLedger.PACK, pack);
    }

    public Optional<State> find(String pack) {
        return ledger.find(SwitchLedger.PACK, pack).map(e -> new State(pack, e.on(), e.at(), e.actor()));
    }

    public State set(String pack, boolean enabled, String actor) {
        return set(pack, enabled, actor, "");
    }

    /** Switches the pack on or off for everyone, with the reason the audit log keeps. */
    public State set(String pack, boolean enabled, String actor, String reason) {
        ledger.set(java.util.List.of(new SwitchLedger.Change(SwitchLedger.PACK, pack, enabled)), reason, actor);
        audit.record(actor, enabled ? "pack-enabled" : "pack-disabled", pack, reason == null ? "" : reason);
        return find(pack).orElseThrow();
    }
}
