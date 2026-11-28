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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** What the person's state does to a Home's ViewModel, and how a Home's Sutra is read for its tables (no Spring, no files). */
class HomeComposerTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Instant NOW = Instant.parse("2026-11-28T10:00:00Z");
    static final List<String> OFFERED = List.of("trader");

    /** A Home of four panels in three rows, the last two sharing the area {@code rail}. */
    ObjectNode view() throws Exception {
        return (ObjectNode) JSON.readTree("""
                {"panels":[{"id":"kpis","title":"Figures","area":"kpis"},{"id":"exc","title":"Exceptions","area":"exc"},
                           {"id":"mnem","title":"Quick mnemonics","area":"rail"},{"id":"loads","title":"Data loads","area":"rail"}],
                 "layout":{"areas":["kpis kpis","exc rail","rail rail"],"phone":["kpis","exc","mnem","loads"],
                           "place":{"kpis":{"area":"kpis"},"exc":{"area":"exc"},"mnem":{"area":"rail"},"loads":{"area":"rail"}}}}""");
    }

    HomeState act(HomeState s, String action, String panel) {
        return s.apply(action, panel, NOW, List.of(), List.of(), false, OFFERED);
    }

    static List<String> ids(ObjectNode v) {
        List<String> out = new ArrayList<>();
        v.path("panels").forEach(p -> out.add(p.path("id").asText()));
        return out;
    }

    static List<String> actionLabels(ObjectNode v, String id) {
        List<String> out = new ArrayList<>();
        v.path("panels").forEach(p -> {
            if (id.equals(p.path("id").asText())) {
                p.path("actions").forEach(a -> out.add(a.path("label").asText()));
            }
        });
        return out;
    }

    @Test
    void aHiddenPanelIsGoneFromEveryPartOfTheLayoutAndListedForBringingBack() throws Exception {
        ObjectNode v = view();
        List<Map<String, String>> hidden = HomeComposer.applyState(v, act(HomeState.empty(), "hide", "exc"), NOW, true, JSON);
        assertEquals(List.of("kpis", "mnem", "loads"), ids(v));
        assertEquals(List.of(Map.of("id", "exc", "title", "Exceptions")), hidden);
        assertEquals("[\"kpis\",\"mnem\",\"loads\"]", v.path("layout").path("phone").toString());
        assertFalse(v.path("layout").path("place").has("exc"));
        assertEquals("[\"kpis kpis\",\"rail rail\",\"rail rail\"]", v.path("layout").path("areas").toString(), "the gap takes the neighbour's name; the width stays");
    }

    @Test
    void aPanelWholeRowGoesWhenItsAreaIsTheOnlyOne() throws Exception {
        ObjectNode v = (ObjectNode) JSON.readTree("{\"panels\":[{\"id\":\"a\",\"area\":\"a\"},{\"id\":\"b\",\"area\":\"b\"}],\"layout\":{\"areas\":[\"a a\",\"b b\"],\"phone\":[\"a\",\"b\"]}}");
        HomeComposer.applyState(v, act(HomeState.empty(), "hide", "b"), NOW, true, JSON);
        assertEquals("[\"a a\"]", v.path("layout").path("areas").toString());
    }

    @Test
    void pinnedPanelsComeFirstThenTheirOwnOrderAndEachPanelHasItsButtons() throws Exception {
        ObjectNode v = view();
        HomeState s = act(act(HomeState.empty(), "pin", "loads"), "pin", "mnem");
        HomeComposer.applyState(v, s, NOW, true, JSON);
        assertEquals(List.of("loads", "mnem", "kpis", "exc"), ids(v));
        assertEquals("[\"loads\",\"mnem\",\"kpis\",\"exc\"]", v.path("layout").path("phone").toString());
        assertEquals(List.of("Unpin", "Down", "Hide", "Snooze for 4 hours"), actionLabels(v, "loads"), "the first has no Up; loads can be snoozed");
        assertEquals(List.of("Pin", "Up", "Hide", "Snooze for 4 hours"), actionLabels(v, "exc"), "the last has no Down");
        String effect = v.path("panels").get(0).path("actions").get(0).path("effects").get(0).toString();
        assertEquals("{\"do\":\"host\",\"action\":\"unpin\",\"with\":{\"panel\":\"loads\"}}", effect);
    }

    @Test
    void aSnoozedPanelSleepsUntilItWakes() throws Exception {
        HomeState s = HomeState.empty().apply("snooze", "", NOW, List.of("exc"), List.of(), false, OFFERED);
        ObjectNode asleep = view();
        assertTrue(HomeComposer.applyState(asleep, s, NOW.plusSeconds(3600), true, JSON).isEmpty());
        assertFalse(ids(asleep).contains("exc"));
        ObjectNode awake = view();
        HomeComposer.applyState(awake, s, NOW.plusSeconds(5 * 3600), true, JSON);
        assertTrue(ids(awake).contains("exc"));
    }

    @Test
    void aLockedHomeKeepsTheSutrasOrderAndOffersNoArrangeButtons() throws Exception {
        ObjectNode v = view();
        HomeComposer.applyState(v, act(act(HomeState.empty(), "pin", "loads"), "hide", "kpis"), NOW, false, JSON);
        assertEquals(List.of("kpis", "exc", "mnem", "loads"), ids(v));
        assertEquals(List.of("Snooze for 4 hours"), actionLabels(v, "exc"));
        assertEquals(List.of(), actionLabels(v, "kpis"));
    }

    @Test
    void theSutrasDataBlockIsReadForItsTablesAndLeftOutOfWhatIsBound() {
        String sutra = """
                rachana: 1.2
                sutra: home-x
                # a comment
                data:
                  meta:      { table: home_meta }
                  kpis:      { table: home_kpis,      key: [LABEL] }

                  # inside
                  digest:    { table: home_digest }
                layout:
                  user-can-arrange: true
                panels:
                  - use: core/kpi-strip@1
                """;
        assertEquals(Map.of("meta", "home_meta", "kpis", "home_kpis", "digest", "home_digest"), HomeComposer.aliases(sutra));
        String bound = HomeComposer.stripData(sutra);
        assertFalse(bound.contains("table:"));
        assertTrue(bound.contains("layout:") && bound.contains("panels:") && bound.contains("# a comment"));
    }
}
