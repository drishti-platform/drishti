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
package com.ash.drishti.server.profile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.core.env.PropertyResolver;

/**
 * The production profile (docs/admin/PRODUCTION_STANDARD.md, section 1): {@code drishti.profile=production} (or the Spring profile
 * {@code prod} / {@code production}). Its safe defaults come from two switches {@link ProductionProfileProcessor} sets and
 * {@code application.yaml} reads; this class is the other half, the start check: it lists every unsafe setting, one line each naming
 * the setting and the fix, and the server does not start while the list is not empty. Pure: it reads a resolver and the files named.
 */
public final class ProductionProfile {

    /** The profile names. */
    public static final String PRODUCTION = "production";
    public static final String DEMO = "demo";
    /** The setting that names the profile. */
    public static final String KEY = "drishti.profile";
    /** The built-in first administrator's name and the password it ships with (never in production). */
    public static final String BUILT_IN_ADMIN = "drishti-dev-admin";

    private ProductionProfile() {
    }

    /** The one list of profile names, shared with the console and the upgrade check: {@code config/profiles/profile-names.json}, built into the jar. */
    static final String NAMES = "drishti/profile-names.json";
    private static volatile Map<String, String> spellings;

    /**
     * The profile this start runs: {@code production}, {@code demo}, or {@code development} (nothing named: today's open defaults). A name
     * that is not in the one list ({@link #NAMES}) refuses the start: a mistyped {@code prodution} must never start an open server.
     */
    public static String name(PropertyResolver env) {
        String v = env.getProperty(KEY);
        String n = v == null ? "" : v.strip().toLowerCase(Locale.ROOT);
        if (n.isEmpty() && env instanceof org.springframework.core.env.Environment e
                && e.acceptsProfiles(org.springframework.core.env.Profiles.of("prod", "production"))) {
            return PRODUCTION;
        }
        String canonical = spellings().get(n);
        if (canonical == null) {
            throw new IllegalStateException("Drishti will not start: drishti.profile (DRISHTI_PROFILE) is '" + v.strip() + "', which is not a profile name. Known: "
                    + known() + ". Fix: set one of these (production for a production server), or remove it for development");
        }
        return canonical;
    }

    /** The known names in words, each with its other spellings ({@code development (or blank), demo, production (or prod)}). */
    public static String known() {
        Map<String, List<String>> by = new java.util.LinkedHashMap<>();
        spellings().forEach((spelt, name) -> by.computeIfAbsent(name, k -> new ArrayList<>()).add(spelt));
        List<String> out = new ArrayList<>();
        by.forEach((name, spelt) -> {
            List<String> other = spelt.stream().filter(x -> !x.equals(name)).map(x -> x.isEmpty() ? "blank" : x).toList();
            out.add(other.isEmpty() ? name : name + " (or " + String.join(", ", other) + ")");
        });
        return String.join(", ", out);
    }

    /** Every accepted spelling, lower case, to its profile name. */
    static Map<String, String> spellings() {
        Map<String, String> m = spellings;
        if (m == null) {
            try (java.io.InputStream in = ProductionProfile.class.getClassLoader().getResourceAsStream(NAMES)) {
                if (in == null) {
                    throw new IllegalStateException("the profile names (" + NAMES + ") are not in the server's jar: the build is incomplete");
                }
                Map<String, String> out = new java.util.LinkedHashMap<>();
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(in).path("names").fields().forEachRemaining(e -> {
                    String name = e.getKey().toLowerCase(Locale.ROOT);
                    out.put(name, name);
                    e.getValue().forEach(x -> out.put(x.asText().strip().toLowerCase(Locale.ROOT), name));
                });
                spellings = m = Map.copyOf(out);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
        return m;
    }

    public static boolean production(PropertyResolver env) {
        return PRODUCTION.equals(name(env));
    }

    /** Every unsafe setting of this configuration, one line each (empty: safe to start). Empty too when the profile is not production. */
    public static List<String> refusals(PropertyResolver env) {
        List<String> out = new ArrayList<>();
        String profile = name(env);
        if (!PRODUCTION.equals(profile)) {
            return out;
        }
        if (!bool(env, "drishti.security.enabled", false)) {
            out.add("drishti.security.enabled is off: no one signs in. Fix: set DRISHTI_SECURITY_ENABLED=true (or remove it: production defaults to on)");
        }
        String secret = env.getProperty("drishti.security.secret", "");
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            out.add("drishti.security.secret (DRISHTI_TOKEN_SECRET) is missing or shorter than 32 bytes. Fix: set a random secret of at least 32 bytes, the same on the console");
        }
        if (bool(env, "drishti.identity.seed-admin", false)) {
            String user = env.getProperty("drishti.identity.seed-username", BUILT_IN_ADMIN);
            String password = env.getProperty("drishti.identity.seed-password", "");
            if (BUILT_IN_ADMIN.equals(user)) {
                out.add("drishti.identity.seed-admin creates the built-in administrator '" + BUILT_IN_ADMIN + "'. Fix: set DRISHTI_SEED_USERNAME to the first administrator you choose, or DRISHTI_SEED_ADMIN=false");
            }
            if (password.isBlank() || password.equals(com.ash.drishti.identity.IdentityProperties.DEFAULT_SEED_PASSWORD)) {
                out.add("drishti.identity.seed-password is empty or the built-in default. Fix: set DRISHTI_SEED_PASSWORD to your own (from the secrets file), or DRISHTI_SEED_ADMIN=false");
            }
        }
        if (bool(env, "drishti.identity.seed-demo-users", false)) {
            out.add("drishti.identity.seed-demo-users is on: the demo accounts have published passwords. Fix: remove DRISHTI_SEED_DEMO_USERS (production never creates them)");
        }
        if (bool(env, "drishti.sources.plugins.demo.enabled", false)) {
            out.add("drishti.sources.plugins.demo.enabled is on: the demo source would serve sample data. Fix: set DRISHTI_DEMO_ENABLED=false (or remove it: production defaults to off)");
        }
        if (bool(env, "drishti.modules.dev", false)) {
            out.add("drishti.modules.dev is on: unverified module folders would load. Fix: remove DRISHTI_MODULES_DEV");
        }
        if (bool(env, "drishti.rachana.studio-save", false)) {
            out.add("drishti.rachana.studio-save is on: Sutra Studio would write files on the server. Fix: remove DRISHTI_STUDIO_SAVE (Sutras go live by propose and approve)");
        }
        if (bool(env, "springdoc.api-docs.enabled", false) || bool(env, "springdoc.swagger-ui.enabled", false)) {
            out.add("springdoc.api-docs.enabled (the API description and its UI) is on. Fix: set DRISHTI_API_DOCS=false (or remove it: production defaults to off)");
        }
        if (!"pinned".equalsIgnoreCase(env.getProperty("drishti.engine-lib.trust", "folder").strip())) {
            out.add("drishti.engine-lib.trust is 'folder': any jar in the engine folder may run. Fix: set DRISHTI_ENGINE_LIB_TRUST=pinned and pin each engine (version, sha384)");
        }
        missingFile(env, "drishti.engine-lib.licence", "DRISHTI_ENGINE_LIB_LICENCE", "the licence file of the engines", out);
        String access = env.getProperty("drishti.access.dir");
        if (access == null || access.isBlank() || !Files.isRegularFile(Path.of(access).resolve("licence.yaml"))) {
            out.add("the licence file is missing: " + (access == null || access.isBlank() ? "drishti.access.dir is not set" : access + "/licence.yaml is not a file")
                    + ", so every module would run. Fix: put the licence you were issued there (DRISHTI_ACCESS_DIR)");
        }
        String site = env.getProperty(com.ash.drishti.api.Roots.SITE, "").strip();
        String home = env.getProperty(com.ash.drishti.api.Roots.HOME, "").strip();
        String config = env.getProperty(com.ash.drishti.api.Roots.CONFIG, home.isEmpty() ? "" : home + "/config").strip();
        if (site.isEmpty()) {
            out.add("drishti.site.dir is not set: Admin's changes and the site's files would be written into the release folder. Fix: name the site folder outside the release (DRISHTI_SITE_DIR, /etc/drishti)");
        } else if (!config.isEmpty() && Path.of(site).toAbsolutePath().normalize().startsWith(Path.of(config).toAbsolutePath().normalize())) {
            out.add("drishti.site.dir (" + site + ") is the release's configuration folder (" + config + "), which is never written. Fix: name a site folder outside the release (/etc/drishti)");
        } else if (!home.isEmpty() && Path.of(site).toAbsolutePath().normalize().startsWith(Path.of(home).toAbsolutePath().normalize())) {
            out.add("drishti.site.dir (" + site + ") is inside the release folder (" + home + "), which is never written. Fix: name a site folder outside the release (/etc/drishti)");
        }
        String data = env.getProperty(com.ash.drishti.api.DataDir.PROPERTY, "").strip();
        if (data.isEmpty()) {
            out.add("drishti.data.dir is not set: the data folder would be <home>/data, inside the release, which an upgrade replaces. Fix: name the data folder "
                    + "outside the release (DRISHTI_DATA_DIR, /srv/drishti/data)");
        } else if (!Path.of(data).isAbsolute()) {
            out.add("drishti.data.dir (" + data + ") is a relative path. Fix: name the data folder by its absolute path outside the release (/srv/drishti/data)");
        } else if (!home.isEmpty() && Path.of(data).normalize().startsWith(Path.of(home).toAbsolutePath().normalize())) {
            out.add("drishti.data.dir (" + data + ") is inside the release folder (" + home + "), which is never written. Fix: name a data folder outside the release (/srv/drishti/data)");
        }
        String db = env.getProperty("drishti.identity.database-url", "");
        if (db.startsWith("jdbc:sqlite") && clustered(env)) {
            out.add("drishti.identity.database-url is SQLite but this server is a node of a cluster. Fix: point every node at one PostgreSQL identity database (db.identity.url)");
        }
        String plain = plainNode(env);
        if (plain != null && !"mesh".equals(env.getProperty("drishti.production.net-tls", "").strip().toLowerCase(Locale.ROOT))) {
            out.add("this node advertises " + plain + " over plain HTTP: server-to-server calls and the shared net secret would travel unencrypted. Fix: advertise an https:// address "
                    + "(advertise-url in node.yaml, with tls), or declare that a private mesh encrypts node traffic with drishti.production.net-tls=mesh (DRISHTI_PRODUCTION_NET_TLS=mesh)");
        }
        String tls = env.getProperty("drishti.production.tls", "").strip().toLowerCase(Locale.ROOT);
        boolean ssl = bool(env, "server.ssl.enabled", false);
        if (!ssl && !tls.equals("proxy")) {
            out.add("TLS is not stated: the server speaks plain HTTP. Fix: enable server.ssl (key store), or declare that a proxy ends TLS in front with drishti.production.tls=proxy (DRISHTI_PRODUCTION_TLS=proxy)");
        }
        return out;
    }

    /** The one exception text of a refused start: a heading, then each refusal on its own line. */
    public static String message(List<String> refusals) {
        StringBuilder b = new StringBuilder("Drishti will not start under the production profile (docs/admin/PRODUCTION_STANDARD.md): ")
                .append(refusals.size()).append(refusals.size() == 1 ? " setting is unsafe" : " settings are unsafe");
        refusals.forEach(r -> b.append("\n  - ").append(r));
        return b.toString();
    }

    /** The {@code http://} address a clustered node advertises (node.yaml {@code advertise-url}), or null when it is not clustered or advertises https. */
    private static String plainNode(PropertyResolver env) {
        String dir = env.getProperty("drishti.net.dir");
        if (!clustered(env)) {
            return null;
        }
        try {
            for (String l : Files.readAllLines(Path.of(dir).resolve("node.yaml"))) {
                String t = l.strip();
                if (t.startsWith("advertise-url:")) {
                    String v = t.substring("advertise-url:".length()).strip().replaceAll("^[\"']|[\"']$", "");
                    return v.toLowerCase(Locale.ROOT).startsWith("http://") ? v : null;
                }
            }
        } catch (java.io.IOException | RuntimeException e) {
            return "an address node.yaml does not say (it could not be read: " + e.getMessage() + ")";
        }
        // no advertise-url: the node advertises this machine's address with the server's own scheme (NetConfiguration), http without server.ssl
        return bool(env, "server.ssl.enabled", false) ? null : "http://<this machine>:" + env.getProperty("server.port", "18480") + " (node.yaml names no advertise-url)";
    }

    private static boolean clustered(PropertyResolver env) {
        String dir = env.getProperty("drishti.net.dir");
        if (dir == null || dir.isBlank()) {
            return false;
        }
        try {
            Path f = Path.of(dir).resolve("node.yaml");
            return Files.isRegularFile(f) && Files.readAllLines(f).stream().anyMatch(l -> l.strip().replaceAll("\\s+", " ").equals("enabled: true"));
        } catch (java.io.IOException | RuntimeException e) {
            return false;
        }
    }

    private static void missingFile(PropertyResolver env, String key, String var, String what, List<String> out) {
        String v = env.getProperty(key);
        if (v == null || v.isBlank() || !Files.isRegularFile(Path.of(v))) {
            out.add(key + " (" + var + ") is not a file" + (v == null || v.isBlank() ? "" : ": " + v) + ", so " + what + " would not be enforced. Fix: install the file you were issued and name it");
        }
    }

    private static boolean bool(PropertyResolver env, String key, boolean dflt) {
        String v = env.getProperty(key);
        return v == null || v.isBlank() ? dflt : Boolean.parseBoolean(v.strip());
    }
}
