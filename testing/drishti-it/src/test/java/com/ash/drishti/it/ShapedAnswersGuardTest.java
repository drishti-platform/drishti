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
package com.ash.drishti.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * What a module shapes from an engine's answer is the same for every viewer of that answer (ENGINES_IN_DRISHTI.md, the host's answer
 * cache; R2_ENGINE_SPEED_DESIGN 2.1): the value is kept with the answer in the host's one cache and served to every caller whose stamp is
 * the same, so a shape function ({@code EngineAnswer.shaped(name, shape)}) reads the answer and reference data only. It never names the
 * caller, their access or masks: those are applied after, per ask. Every {@code .shaped(} call in main code is read here and its shape
 * argument checked for the caller's words.
 */
class ShapedAnswersGuardTest {

    private static final Pattern SHAPED = Pattern.compile("\\.shaped\\(");
    /** What a shape function may not read: the person asking, their entitlements, masks or views. */
    private static final Pattern PER_CALLER = Pattern.compile(
            "\\b(p|principal|person|viewer|caller)\\b(?!\\s*->)|Principal|Entitlements|entitlements|\\.masked\\(|\\.access\\(\\)|seesPositions|\\.view\\(");

    /** Each {@code .shaped(} call of main code whose arguments read the caller: {@code file:line: the call}. */
    static List<String> offending() throws IOException {
        Path root = RepositoryFiles.root();
        List<String> out = new ArrayList<>();
        for (Path p : RepositoryFiles.withSuffixes(Set.of(".java"))) {
            String rel = root.relativize(p).toString().replace('\\', '/');
            if (!rel.contains("/src/main/")) {
                continue;
            }
            String code = Files.readString(p, StandardCharsets.UTF_8);
            Matcher m = SHAPED.matcher(code);
            while (m.find()) {
                String args = arguments(code, m.end());
                if (PER_CALLER.matcher(args).find()) {
                    long line = code.substring(0, m.start()).chars().filter(c -> c == '\n').count() + 1;
                    out.add(rel + ":" + line + ": " + args.replaceAll("\\s+", " ").substring(0, Math.min(160, args.length())));
                }
            }
        }
        return out;
    }

    /** The call's arguments: up to the parenthesis that closes it. */
    private static String arguments(String code, int from) {
        int depth = 1;
        for (int i = from; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return code.substring(from, i);
            }
        }
        return code.substring(from);
    }

    @Test
    void noShapeFunctionReadsTheCaller() throws IOException {
        assertThat(offending()).as("a shape function reads the answer and reference data only; apply anything per caller after, per ask")
                .isEmpty();
    }

    @Test
    void theGuardSeesACallerInAShape() {
        String bad = "a.shaped(\"x\", y -> label(y, p))";
        assertThat(PER_CALLER.matcher(arguments(bad, bad.indexOf("shaped(") + 7)).find()).isTrue();
        String good = "a.shaped(\"var.scope-book\", EngineScopeBook::new)";
        assertThat(PER_CALLER.matcher(arguments(good, good.indexOf("shaped(") + 7)).find()).isFalse();
    }
}
