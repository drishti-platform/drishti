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
package com.ash.drishti.server.home;

import com.ash.drishti.api.EntityRef;
import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.kernel.api.home.HomeDefinition;
import com.ash.drishti.kernel.api.security.Principal;
import com.ash.drishti.rachana.SutraRegistry;
import com.ash.drishti.server.security.Entitlements;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.yaml.snakeyaml.Yaml;

/**
 * The one Home page's document (docs/architecture/ROLE_HOME_SUTRAS.md): the Home's Sutra, bound over the home tables the server serves
 * <em>for the person</em> (their row rules and masks, their packs), as a ViewModel, with the person's own state applied (hidden and snoozed
 * panels left out, pinned and moved panels first, the buttons that change that). Every panel is a library fragment of the Home's Sutra;
 * nothing is drawn or computed here. The Sutra's {@code data:} entries name the tables ({@code kpis: {table: home_kpis}}); the document handed
 * to the binder is {@code {kpis: [rows...]}}, which is what {@code $.kpis} reads.
 */
public final class HomeComposer {

    /** The panels that carry a "Snooze" button: what needs the person's attention. */
    static final Set<String> SNOOZABLE = Set.of("exc", "needs", "loads");

    private final HomeTables tables;
    private final HomeCatalogue catalogue;
    private final SutraRegistry sutras;
    private final ViewPipeline pipeline;
    private final Entitlements entitlements;
    private final com.ash.drishti.common.JsonCodec codec;
    private final ObjectMapper json;

    public HomeComposer(HomeTables tables, HomeCatalogue catalogue, SutraRegistry sutras, ViewPipeline pipeline, Entitlements entitlements,
            com.ash.drishti.common.JsonCodec codec, ObjectMapper json) {
        this.tables = tables;
        this.catalogue = catalogue;
        this.sutras = sutras;
        this.pipeline = pipeline;
        this.entitlements = entitlements;
        this.codec = codec;
        this.json = json;
    }

    /** The Home as the person sees it: the ViewModel, with the panels they hid listed apart. Every read is as the person. */
    public Composed compose(Principal p, HomeDefinition d, HomeState state, Instant now) {
        ObjectNode doc = json.createObjectNode();
        Instant window = state.digestSince(now);
        for (Map.Entry<String, String> e : aliases(d.sutraText()).entrySet()) {
            List<Map<String, Object>> rows = tables.rows(e.getValue(), p, d, "home_digest".equals(e.getValue()) ? window : null).orElse(List.of());
            doc.set(e.getKey(), json.valueToTree(rows));
        }
        var sutra = sutras.check(stripData(catalogue.sutraFor(d)));
        var entity = new com.ash.drishti.api.EntityDocument(EntityRef.of("home", d.name()), codec.read(doc.toString()),
                new com.ash.drishti.api.Provenance("home tables of " + d.name(), 0, now, false));
        var built = entitlements.restrict(p, pipeline.preview(Optional.of(sutra), entity, entitlements.viewer(p)));
        ObjectNode view = (ObjectNode) json.valueToTree(built);
        List<Map<String, String>> hidden = applyState(view, state, now, d.arrange(), json);
        return new Composed(view, hidden);
    }

    /** The ViewModel and the panels the person has hidden (so the page can bring them back). */
    public record Composed(ObjectNode view, List<Map<String, String>> hidden) {
        public List<String> panelIds() {
            List<String> out = new ArrayList<>();
            view.path("panels").forEach(x -> out.add(x.path("id").asText()));
            return out;
        }
    }

    // ---- the Sutra's tables ----------------------------------------------------------------------------------------------------------

    /** The {@code data:} entries of a Sutra: alias to the home table it reads (an entry that names no table is not one). */
    @SuppressWarnings("unchecked")
    public static Map<String, String> aliases(String sutraText) {
        Map<String, String> out = new LinkedHashMap<>();
        Object root = new Yaml().load(sutraText);
        if (root instanceof Map<?, ?> m && m.get("data") instanceof Map<?, ?> data) {
            ((Map<String, Object>) data).forEach((alias, spec) -> {
                if (spec instanceof Map<?, ?> s && s.get("table") != null) {
                    out.put(alias, String.valueOf(s.get("table")));
                }
            });
        }
        return out;
    }

    /** The Sutra without its top-level {@code data:} block: the tables are read here, so the binder takes the document as it is. */
    public static String stripData(String sutraText) {
        StringBuilder out = new StringBuilder();
        boolean in = false;
        for (String line : sutraText.split("\n", -1)) {
            if (line.startsWith("data:")) {
                in = true;
                continue;
            }
            if (in && (line.isBlank() || line.startsWith(" ") || line.startsWith("\t") || line.startsWith("#"))) {
                continue;
            }
            in = false;
            out.append(line).append('\n');
        }
        return out.toString();
    }

    // ---- the person's state ------------------------------------------------------------------------------------------------------------

    /**
     * Applies the person's state to a Home's ViewModel in place: a hidden or sleeping panel is removed (from the panels, the phone order, the
     * places and the layout picture), pinned panels then the person's own order come first, and each panel gets its buttons (Pin, Up, Down, Hide,
     * and Snooze for what needs attention). Returns the hidden panels (id, title). With {@code arrange} false (a locked Home) the order is the Sutra's.
     */
    public static List<Map<String, String>> applyState(ObjectNode view, HomeState st, Instant now, boolean arrange, ObjectMapper json) {
        ArrayNode panels = (ArrayNode) view.path("panels");
        List<JsonNode> kept = new ArrayList<>();
        List<Map<String, String>> hidden = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        List<String> droppedAreas = new ArrayList<>();
        for (JsonNode p : panels) {
            String id = p.path("id").asText();
            if (arrange && st.hidden().contains(id)) {
                hidden.add(Map.of("id", id, "title", p.path("title").asText(id)));
            }
            if (arrange && st.hidden().contains(id) || st.asleep(id, now)) {
                dropped.add(id);
                droppedAreas.add(p.path("area").asText(id));
            } else {
                kept.add(p);
            }
        }
        if (arrange) {
            List<String> want = new ArrayList<>(st.pins());
            st.order().stream().filter(x -> !want.contains(x)).forEach(want::add);
            kept.sort((a, b) -> Integer.compare(rank(want, a.path("id").asText()), rank(want, b.path("id").asText())));   // stable: the Sutra's order for the rest
        }
        ArrayNode out = json.createArrayNode();
        for (int i = 0; i < kept.size(); i++) {
            ObjectNode p = (ObjectNode) kept.get(i);
            String id = p.path("id").asText();
            ArrayNode actions = p.path("actions").isArray() ? (ArrayNode) p.get("actions") : p.putArray("actions");
            if (arrange) {
                actions.add(action(json, id + "-pin", st.pins().contains(id) ? "Unpin" : "Pin", st.pins().contains(id) ? "unpin" : "pin", id));
                if (i > 0) {
                    actions.add(action(json, id + "-up", "Up", "up", id));
                }
                if (i < kept.size() - 1) {
                    actions.add(action(json, id + "-down", "Down", "down", id));
                }
                actions.add(action(json, id + "-hide", "Hide", "hide", id));
            }
            if (SNOOZABLE.contains(id)) {
                ObjectNode a = action(json, id + "-snooze", "Snooze for " + HomeState.SNOOZE_HOURS + " hours", "snooze", id);
                ((ObjectNode) a.path("effects").get(0).path("with")).putArray("keys").add(id);
                actions.add(a);
            }
            out.add(p);
        }
        view.set("panels", out);
        JsonNode layout = view.path("layout");
        if (layout instanceof ObjectNode lay) {
            List<String> ids = new ArrayList<>();
            kept.forEach(p -> ids.add(p.path("id").asText()));
            if (lay.path("phone").isArray()) {
                ArrayNode phone = json.createArrayNode();
                ids.forEach(phone::add);
                lay.set("phone", phone);
            }
            if (lay.path("place") instanceof ObjectNode place) {
                dropped.forEach(place::remove);
            }
            if (lay.path("areas").isArray()) {
                List<String> alive = new ArrayList<>();
                kept.forEach(p -> alive.add(p.path("area").asText(p.path("id").asText())));
                ArrayNode areas = json.createArrayNode();
                for (JsonNode row : lay.path("areas")) {
                    String fixed = fixRow(row.asText(), droppedAreas, alive);
                    if (!fixed.isEmpty()) {
                        areas.add(fixed);
                    }
                }
                lay.set("areas", areas);
            }
        }
        return hidden;
    }

    private static int rank(List<String> want, String id) {
        int i = want.indexOf(id);
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    /** One row of the layout picture without the areas of dropped panels: each such cell takes its neighbour's name; a row left with none is empty. */
    static String fixRow(String row, List<String> droppedAreas, List<String> aliveAreas) {
        String[] cells = row.trim().split("\\s+");
        List<String> out = new ArrayList<>();
        for (String c : cells) {
            if (!droppedAreas.contains(c) || aliveAreas.contains(c)) {
                out.add(c);
            }
        }
        if (out.isEmpty()) {
            return "";
        }
        // keep the row's width: a gap takes the name of the nearest cell that remains (the one before it, else the one after)
        List<String> filled = new ArrayList<>();
        String last = null;
        for (String c : cells) {
            boolean gone = droppedAreas.contains(c) && !aliveAreas.contains(c);
            if (gone) {
                filled.add(last == null ? "" : last);
            } else {
                filled.add(c);
                last = c;
            }
        }
        String first = out.get(0);
        return String.join(" ", filled.stream().map(x -> x.isEmpty() ? first : x).toList());
    }

    private static ObjectNode action(ObjectMapper json, String id, String label, String action, String panel) {
        ObjectNode a = json.createObjectNode().put("id", id).put("label", label);
        ObjectNode effect = a.putArray("effects").addObject().put("do", "host").put("action", action);
        effect.putObject("with").put("panel", panel);
        return a;
    }
}
