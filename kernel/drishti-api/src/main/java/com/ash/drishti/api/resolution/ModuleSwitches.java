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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The one decision of a module's switch, for the server (the access registry) and for the tools and the loader process (no server to
 * ask): how a switch value is read, where the setting {@code drishti.modules.<id>.enabled} comes from, and when a module is off because
 * a module it requires is off. A value is on when it is {@code true}, {@code on} or {@code yes} (any case), in a file as in a setting.
 */
public final class ModuleSwitches {

    private ModuleSwitches() {
    }

    /** A switch value: null when there is none; a boolean as it is; else on for {@code true}, {@code on} or {@code yes}. */
    public static Boolean parse(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(value).strip().toLowerCase(Locale.ROOT);
        return "true".equals(s) || "on".equals(s) || "yes".equals(s);
    }

    /** The setting {@code drishti.modules.<id>.enabled} (command line or environment: it wins over a file), null when not set. */
    public static Boolean setting(Function<String, String> property, String id) {
        String v = property.apply("drishti.modules." + id + ".enabled");
        return v == null || v.isBlank() ? null : parse(v);
    }

    /**
     * The first module in {@code requires} that is unknown or off, null when all are on; {@code core} is always on. {@code state} answers
     * a module's switch, null for a module not known here.
     */
    public static String firstRequirementOff(Collection<String> requires, Function<String, Boolean> state) {
        for (String need : requires) {
            if (!"core".equals(need) && !Boolean.TRUE.equals(state.apply(need))) {
                return need;
            }
        }
        return null;
    }

    /**
     * The modules that are on when there is no server to ask: every module found, less those an administrator switched off (the setting,
     * else {@code <switches>/<id>.yaml} {@code enabled:}, the release's {@code defaultsDir} read first and the site's after it), less
     * those whose required module is off (a requirement that is not found here is ignored). The licence is not evaluated here.
     */
    public static Predicate<String> decide(Map<String, PackResolver.Module> modules, Path defaultsDir, Path switchesDir,
            Function<String, String> property, Function<Path, Map<String, Object>> yaml) {
        Map<String, Boolean> on = new TreeMap<>();
        for (String id : modules.keySet()) {
            Boolean set = setting(property, id);
            if (set == null) {
                for (Path dir : new Path[] {defaultsDir, switchesDir}) {   // the release's file first, the site's after it (it wins)
                    if (dir != null && Files.isRegularFile(dir.resolve(id + ".yaml"))) {
                        try {
                            Boolean f = parse(yaml.apply(dir.resolve(id + ".yaml")).get("enabled"));
                            set = f == null ? set : f;
                        } catch (RuntimeException e) {
                            set = null;                                  // an unreadable switch: the module's own default
                        }
                    }
                }
            }
            on.put(id, set == null || set);
        }
        boolean changed = true;
        while (changed) {                                               // a module whose requirement is off is off
            changed = false;
            for (PackResolver.Module m : modules.values()) {
                List<String> requires = m.requires();
                if (on.get(m.id()) && firstRequirementOff(requires, r -> modules.containsKey(r) ? on.get(r) : Boolean.TRUE) != null) {
                    on.put(m.id(), false);
                    changed = true;
                }
            }
        }
        return id -> "core".equals(id) || on.getOrDefault(id == null ? "" : id.strip().toLowerCase(Locale.ROOT), false);
    }
}
