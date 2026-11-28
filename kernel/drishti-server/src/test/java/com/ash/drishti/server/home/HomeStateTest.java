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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ash.drishti.api.error.DrishtiException;
import com.ash.drishti.api.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The person's own Home state (pins, hidden, order, snooze, visits, view as): the rules the server keeps and checks. */
class HomeStateTest {

    static final Instant NOW = Instant.parse("2026-11-28T10:00:00Z");
    static final List<String> OFFERED = List.of("trader", "risk-manager");

    HomeState act(HomeState s, String action, String panel) {
        return s.apply(action, panel, NOW, List.of(), List.of(), false, OFFERED);
    }

    @Test
    void pinHideShowAndTheirLimits() {
        HomeState s = act(act(HomeState.empty(), "pin", "kpis"), "pin", "exc");
        assertEquals(List.of("kpis", "exc"), s.pins());
        s = act(s, "hide", "kpis");
        assertEquals(List.of("exc"), s.pins(), "a hidden panel is no longer pinned");
        assertEquals(List.of("kpis"), s.hidden());
        assertEquals(List.of(), act(s, "show", "kpis").hidden());
        HomeState many = HomeState.empty();
        for (int i = 0; i < 12; i++) {
            many = act(many, "pin", "p" + i);
        }
        assertEquals(HomeState.MAX_PINS, many.pins().size());
        assertEquals("p11", many.pins().get(HomeState.MAX_PINS - 1), "the newest pin stays, the oldest goes");
    }

    @Test
    void moveWithinThePinnedOrTheRest() {
        HomeState s = act(act(HomeState.empty(), "pin", "a"), "pin", "b");
        List<String> visible = List.of("a", "b", "c", "d");
        s = s.apply("up", "b", NOW, List.of(), visible, false, OFFERED);
        assertEquals(List.of("b", "a"), s.pins());
        s = s.apply("down", "c", NOW, List.of(), visible, false, OFFERED);
        assertEquals(List.of("d", "c"), s.order());
    }

    @Test
    void snoozeWakesAfterFourHoursAndResetClearsTheArrangementOnly() {
        HomeState s = act(HomeState.empty(), "visited", "").apply("snooze", "", NOW, List.of("exc", "bad key!"), List.of(), false, OFFERED);
        assertTrue(s.asleep("exc", NOW.plusSeconds(3 * 3600)));
        assertFalse(s.asleep("exc", NOW.plusSeconds(4 * 3600 + 1)));
        assertFalse(s.asleep("bad key!", NOW), "a name that is not a panel id is never kept");
        HomeState r = act(act(s, "pin", "kpis"), "reset", "");
        assertEquals(List.of(), r.pins());
        assertFalse(r.asleep("exc", NOW));
        assertEquals(NOW, r.lastVisit(), "reset keeps the visit times");
    }

    @Test
    void theDigestReadsAgainstTheVisitBeforeThisOne() {
        assertNull(HomeState.empty().digestSince(NOW), "a first visit: the Home's cut-off is used");
        Instant first = Instant.parse("2026-11-27T09:00:00Z");
        HomeState s = HomeState.empty().apply("visited", "", first, List.of(), List.of(), false, OFFERED);
        assertEquals(first, s.digestSince(NOW), "a later visit reads since the last one");
        HomeState again = s.apply("visited", "", NOW, List.of(), List.of(), false, OFFERED);
        assertEquals(first, again.digestSince(NOW.plusSeconds(60)), "the visit just recorded does not move the window");
    }

    @Test
    void viewAsIsAnAdministratorsAndNamesAnOfferedHome() {
        DrishtiException refused = assertThrows(DrishtiException.class, () -> act(HomeState.empty(), "viewas", "trader"));
        assertEquals(ErrorCode.FORBIDDEN, refused.errorCode());
        HomeState s = HomeState.empty().apply("viewas", "trader", NOW, List.of(), List.of(), true, OFFERED);
        assertEquals("trader", s.viewAs());
        assertThrows(DrishtiException.class, () -> HomeState.empty().apply("viewas", "genomicist", NOW, List.of(), List.of(), true, OFFERED));
        assertThrows(DrishtiException.class, () -> HomeState.empty().apply("viewas", "Not A Home", NOW, List.of(), List.of(), true, OFFERED));
        assertEquals("", s.apply("viewas", "", NOW, List.of(), List.of(), true, OFFERED).viewAs(), "empty goes back to the person's own Home");
    }

    @Test
    void aStoredViewAsShowsOnlyForAnAdministratorAndOnlyWhileOffered() {
        HomeState s = HomeState.empty().apply("viewas", "trader", NOW, List.of(), List.of(), true, OFFERED);
        assertEquals("trader", s.homeToShow("risk-manager", true, OFFERED));
        assertEquals("risk-manager", s.homeToShow("risk-manager", false, OFFERED), "a person who is no longer an administrator is back on their own Home");
        assertEquals("risk-manager", s.homeToShow("risk-manager", true, List.of("risk-manager")), "a Home no longer offered is ignored");
    }

    @Test
    void anUnknownActionOrAMissingPanelIsRefused() {
        assertThrows(DrishtiException.class, () -> act(HomeState.empty(), "teleport", ""));
        assertThrows(DrishtiException.class, () -> act(HomeState.empty(), "pin", ""));
        assertThrows(DrishtiException.class, () -> act(HomeState.empty(), "hide", "not a panel"));
    }

    @Test
    void storedStateIsReadWarilyAndRoundTrips() throws Exception {
        ObjectMapper json = new ObjectMapper();
        HomeState s = HomeState.of(json.readTree("{\"pins\":[\"a\",\"a\",\"bad id\",7],\"hidden\":\"x\",\"snooze\":{\"k\":\"tomorrow\",\"j\":\"2026-11-29T00:00:00Z\"},"
                + "\"lastVisit\":\"yesterday\",\"viewAs\":\"Nope Nope\"}"));
        assertEquals(List.of("a"), s.pins());
        assertEquals(List.of(), s.hidden());
        assertTrue(s.asleep("j", NOW));
        assertFalse(s.asleep("k", NOW));
        assertNull(s.lastVisit());
        assertEquals("", s.viewAs());
        HomeState again = HomeState.of(act(act(s, "pin", "z"), "hide", "y").toJson());
        assertEquals(List.of("a", "z"), again.pins());
        assertEquals(List.of("y"), again.hidden());
        assertEquals(HomeState.empty().toJson().toString(), HomeState.of(null).toJson().toString());
    }
}
