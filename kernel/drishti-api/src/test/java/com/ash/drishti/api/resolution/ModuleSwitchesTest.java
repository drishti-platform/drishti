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
package com.ash.drishti.api.resolution;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The one module-switch decision: one parse for a setting and a file, one rule for a required module that is off, both callers. */
class ModuleSwitchesTest {

    @TempDir
    Path tmp;

    private static PackResolver.Module module(String id, String... requires) {
        return new PackResolver.Module(id, null, null, List.of(), "test", null, null, List.of(requires));
    }

    @Test
    void aValueIsOnForTrueOnYesInAnyCaseInASettingAndInAFile() {
        for (String on : new String[] {"true", "TRUE", "on", "On", "yes", " yes "}) {
            assertThat(ModuleSwitches.parse(on)).as(on).isTrue();
            assertThat(ModuleSwitches.setting(k -> on, "x")).as("setting " + on).isTrue();
        }
        for (String off : new String[] {"false", "off", "no", "0"}) {
            assertThat(ModuleSwitches.parse(off)).as(off).isFalse();
        }
        assertThat(ModuleSwitches.parse(null)).isNull();
        assertThat(ModuleSwitches.parse(Boolean.FALSE)).isFalse();
        assertThat(ModuleSwitches.setting(k -> " ", "x")).isNull();
        assertThat(ModuleSwitches.setting(k -> null, "x")).isNull();
    }

    @Test
    void aRequirementThatIsOffOrUnknownIsNamedAndCoreIsAlwaysOn() {
        Map<String, Boolean> state = Map.of("a", true, "b", false);
        assertThat(ModuleSwitches.firstRequirementOff(List.of("core", "a"), state::get)).isNull();
        assertThat(ModuleSwitches.firstRequirementOff(List.of("a", "b"), state::get)).isEqualTo("b");
        assertThat(ModuleSwitches.firstRequirementOff(List.of("a", "zz"), state::get)).isEqualTo("zz");
    }

    @Test
    void theOfflineDecisionReadsAnOnInTheFileAsOnAndTurnsOffWhoNeedsAnOffModule() throws Exception {
        Path sw = tmp.resolve("modules");
        Files.createDirectories(sw);
        Files.writeString(sw.resolve("base.yaml"), "x");
        Files.writeString(sw.resolve("mid.yaml"), "x");
        Map<String, String> files = Map.of("base.yaml", "on", "mid.yaml", "off");     // what the YAML reader would answer
        Map<String, PackResolver.Module> modules = Map.of("base", module("base"), "mid", module("mid", "base"),
                "top", module("top", "mid", "core"), "free", module("free", "absent"));
        var on = ModuleSwitches.decide(modules, null, sw, k -> null, p -> Map.of("enabled", files.get(p.getFileName().toString())));
        assertThat(on.test("base")).isTrue();
        assertThat(on.test("mid")).isFalse();
        assertThat(on.test("top")).isFalse();
        assertThat(on.test("free")).isTrue();                    // a requirement not found here is ignored
        assertThat(on.test("core")).isTrue();
        var setting = ModuleSwitches.decide(modules, null, sw, k -> k.equals("drishti.modules.mid.enabled") ? "yes" : null,
                p -> Map.of("enabled", p.getFileName().toString().equals("mid.yaml") ? "off" : "on"));
        assertThat(setting.test("mid")).isTrue();                // the setting wins over the file
        assertThat(setting.test("top")).isTrue();
    }
}
