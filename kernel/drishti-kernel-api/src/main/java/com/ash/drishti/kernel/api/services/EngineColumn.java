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

/**
 * One column of an {@link EngineTable}, resolved once ({@link EngineTable#column}): its cells read by row without looking the column up
 * again, for a reader that walks every row of a large table (an index of 100,000 rows read cell by cell). The same null rules as the
 * table's: a null cell reads {@code null} (text), {@code NaN} (number), {@code 0} (integer), {@code false} (flag) or {@code null} (list).
 */
public interface EngineColumn {

    /** The column's name, as the engine's descriptor names it. */
    String name();

    String text(int row);

    double number(int row);

    long integer(int row);

    boolean flag(int row);

    double[] list(int row);

    boolean isNull(int row);

    /** The cell as an object (String, Long, Double, Boolean, double[] or null). */
    Object value(int row);
}
