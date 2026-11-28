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
package com.ash.drishti.api.error;

/**
 * An engine is preparing the snapshot asked ({@link ErrorCode#ENGINE_PREPARING}, ENGINE_SPEC 7.2 rule 5): its session is opening and the
 * caller's wait ended. The words a page shows (as the busy message: a title, what is happening, a countdown) and when to ask again.
 */
public class EnginePreparing extends DrishtiException {

    private static final long serialVersionUID = 1L;
    private final String title;
    private final String because;
    private final long retryAfterSeconds;

    /**
     * @param title {@code Preparing 2026-09-30}
     * @param because the stage the engine named ({@code reading 62,000 of 100,000 rows}), or what is happening
     * @param retryAfterSeconds when to ask again
     */
    public EnginePreparing(String title, String because, long retryAfterSeconds, String message) {
        super(ErrorCode.ENGINE_PREPARING, message);
        this.title = title;
        this.because = because;
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public String title() {
        return title;
    }

    public String because() {
        return because;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
