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
package com.ash.drishti.engines.changes;

import com.ash.engine.Control;
import com.ash.engine.Descriptor;
import com.ash.engine.Descriptors;
import com.ash.engine.Engine;
import com.ash.engine.EngineContext;
import com.ash.engine.Inputs;
import com.ash.engine.Session;
import com.ash.engine.Snapshot;

/**
 * What changed since a reference cut-off. A pure function of the reference values, the current values, the events and the attribution rows
 * it is given: it never reads a clock, never stores the reference (the host keeps the cut-off snapshot) and never runs another engine
 * (the host runs the measure's own engine with one input group swapped and hands the differences in as {@code drivers}).
 */
public final class ChangesEngine implements Engine {

    static final String ID = "changes";

    private volatile EngineContext context;

    /** The jar's descriptor, read at the first call (ENGINE_SPEC 4.1: constant, at constant cost); every later call returns it. */
    private volatile Descriptor descriptor;

    @Override
    public Descriptor descriptor() {
        Descriptor d = descriptor;
        if (d == null) {
            d = Descriptors.of(ChangesEngine.class, ID);
            descriptor = d;
        }
        return d;
    }

    @Override
    public void open(EngineContext context) {
        this.context = context;
    }

    @Override
    public Session session(Snapshot snapshot, Inputs inputs, Control control) {
        return new ChangesSession(context, descriptor().schema("changes"), snapshot, inputs);
    }

    @Override
    public void close() {
        context = null;
    }
}
