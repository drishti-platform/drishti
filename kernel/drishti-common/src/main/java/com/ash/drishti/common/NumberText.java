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
package com.ash.drishti.common;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * The one grouped-number text of the Java side: thousands separators and at most {@code maxFractionDigits} decimals (none when the
 * value is whole). Named formats of a Sutra's {@code fmt:} are Rachana's {@code Formats}; this is for the places that have no format
 * name (a table cell, an explanation line, a document). A guard keeps {@code new DecimalFormat} out of every other class.
 */
public final class NumberText {

    private NumberText() {
    }

    /** {@code value} grouped with at most {@code maxFractionDigits} decimals, symbols of {@code locale}. */
    public static String grouped(Number value, int maxFractionDigits, Locale locale) {
        StringBuilder pattern = new StringBuilder("#,##0");
        if (maxFractionDigits > 0) {
            pattern.append('.').append("#".repeat(maxFractionDigits));
        }
        DecimalFormat f = new DecimalFormat(pattern.toString(), DecimalFormatSymbols.getInstance(locale));
        return value instanceof java.math.BigDecimal b ? f.format(b) : f.format(value.doubleValue());
    }
}
