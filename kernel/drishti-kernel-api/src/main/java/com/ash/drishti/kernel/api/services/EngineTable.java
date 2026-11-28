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

/**
 * One output's table of an {@link EngineAnswer}, read as columns in place: the engine's own arrays, never copied or boxed. A column is
 * named as the engine's descriptor names it; a cell that is null reads {@code null} (text), {@code NaN} (number), {@code 0} (integer),
 * {@code false} (flag) or {@code null} (list), with {@link #isNull} saying which.
 */
public interface EngineTable {

    /** The output's name. */
    String name();

    /** The columns, in the engine's order. */
    List<String> columns();

    int rows();

    String text(String column, int row);

    double number(String column, int row);

    long integer(String column, int row);

    boolean flag(String column, int row);

    double[] list(String column, int row);

    boolean isNull(String column, int row);

    /** The cell as an object (String, Long, Double, Boolean, double[] or null): for a reader that shapes a few rows generically. */
    Object value(String column, int row);

    /**
     * The first row whose {@code keyColumn} (a text or integer column) is {@code key}, or -1: an index by that column, built once per
     * answer and kept with it in the host's cache.
     */
    int find(String keyColumn, String key);
}
