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
package com.ash.drishti.kernel.api.services;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A hosted engine's answer as a module reads it (ENGINES_IN_DRISHTI.md section 9; R2_ENGINE_SPEED_DESIGN 2.1): status, what was not
 * found, the input stamps it was computed over, its identity, and each output's table read as columns in place ({@link EngineTable}):
 * nothing is boxed and one row of a large table is one {@link EngineTable#find}.
 *
 * <p>{@link #shaped} is how a module keeps what it derives from an answer (an index of the date, the rows by id, figures by node) in the
 * host's one answer cache: built once per answer, kept with the answer's cache entry, so it is evicted, purged, counted and listed with it.
 * The entry's key is the answer's identity, which carries the caller's visibility signature (their row rules and masks) and the input data
 * versions: a shaped value is reachable only by callers who see the same rows over the same data. <b>Rule</b> (a guard test holds every
 * module to it): a shape function reads the answer and reference data that is the same for every viewer; anything per caller (masking a
 * label, an access check) is applied after, on each ask.
 */
public interface EngineAnswer {

    /** {@code FINAL}, {@code PARTIAL} or {@code INTERIM} (the engine's words). */
    String status();

    /** What the engine did not find, in words. */
    List<String> missing();

    /** The engine's notes, in words. */
    List<String> notes();

    /** The input stamps the answer names: each input (or {@code input:table}) to the data versions (the lake commits) it was read at. */
    Map<String, String> inputStamps();

    /** The answer's identity: engine, version, descriptor, options, the input stamps and the request with the caller's visibility. */
    String key();

    /** The table of {@code output}, or null when the output was not answered. */
    EngineTable table(String output);

    /**
     * The value {@code shape} builds from this answer under {@code name}: built once per answer (the first reader builds it, later readers
     * of the same answer get it) and kept with the answer in the host's cache. A value that implements {@link Sized} is counted by its own
     * estimate; any other by a fixed small amount. {@code name} names everything the value depends on besides the answer (a confidence,
     * a setting).
     */
    <T> T shaped(String name, Function<EngineAnswer, T> shape);

    /** A shaped value that knows its own size, so the host's cache counts it in bytes. */
    interface Sized {

        /** About how many bytes the value holds. */
        long bytes();
    }
}
