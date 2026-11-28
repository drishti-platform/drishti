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

import com.ash.drishti.api.error.DrishtiException;
import com.ash.drishti.api.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What one person has done to their Home, kept by the server as their own setting (the preference namespace {@code home}, key {@code state}):
 * the panels they pinned, hid or moved, what they snoozed, when they last visited, and (an administrator only) the Home they view as. The
 * start Home is the person's other Home setting ({@code PUT /api/v1/homes/me}).
 *
 * <p>Immutable: every change returns a new state, so nothing one person does is held where another could read it. The state only names panels of
 * the Home's own Sutra and a Home the person is offered; a name the Home has not got is never drawn, so nothing here can widen what a person
 * sees. Rows and figures are always read as the person, whatever the state says.
 */
public final class HomeState {

    public static final int MAX_PINS = 8;
    public static final int MAX_NAMES = 60;
    public static final int SNOOZE_HOURS = 4;
    public static final int VISIT_MINUTES = 30;
    /** The actions {@link #apply} takes. */
    public static final List<String> ACTIONS = List.of("pin", "unpin", "hide", "show", "up", "down", "snooze", "reset", "visited", "viewas");
    private static final Pattern PANEL = Pattern.compile("^[A-Za-z0-9][\\w-]{0,63}$");
    private static final Pattern HOME = Pattern.compile("^[a-z][a-z0-9-]{1,63}$");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<String> pins;
    private final List<String> hidden;
    private final List<String> order;
    private final Map<String, Instant> snooze;
    private final Instant lastVisit;
    private final Instant prevVisit;
    private final String viewAs;

    private HomeState(List<String> pins, List<String> hidden, List<String> order, Map<String, Instant> snooze, Instant lastVisit, Instant prevVisit, String viewAs) {
        this.pins = List.copyOf(pins);
        this.hidden = List.copyOf(hidden);
        this.order = List.copyOf(order);
        this.snooze = Map.copyOf(snooze);
        this.lastVisit = lastVisit;
        this.prevVisit = prevVisit;
        this.viewAs = viewAs == null ? "" : viewAs;
    }

    public static HomeState empty() {
        return new HomeState(List.of(), List.of(), List.of(), Map.of(), null, null, "");
    }

    /** The state as stored; whatever is malformed is left out (a stored state is never trusted to be well formed). */
    public static HomeState of(JsonNode stored) {
        if (stored == null || !stored.isObject()) {
            return empty();
        }
        Map<String, Instant> snooze = new LinkedHashMap<>();
        stored.path("snooze").fields().forEachRemaining(e -> {
            Instant at = instant(e.getValue().asText(""));
            if (at != null && PANEL.matcher(e.getKey()).matches() && snooze.size() < MAX_NAMES) {
                snooze.put(e.getKey(), at);
            }
        });
        String view = stored.path("viewAs").asText("");
        return new HomeState(names(stored.path("pins"), MAX_PINS), names(stored.path("hidden"), MAX_NAMES), names(stored.path("order"), MAX_NAMES), snooze,
                instant(stored.path("lastVisit").asText("")), instant(stored.path("prevVisit").asText("")), HOME.matcher(view).matches() ? view : "");
    }

    private static List<String> names(JsonNode n, int max) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (n != null && n.isArray()) {
            for (JsonNode x : n) {
                if (x.isTextual() && PANEL.matcher(x.asText()).matches() && out.size() < max) {
                    out.add(x.asText());
                }
            }
        }
        return new ArrayList<>(out);
    }

    private static Instant instant(String text) {
        try {
            return text == null || text.isBlank() ? null : Instant.parse(text);
        } catch (DateTimeException e) {
            return null;
        }
    }

    public List<String> pins() {
        return pins;
    }

    public List<String> hidden() {
        return hidden;
    }

    public List<String> order() {
        return order;
    }

    public Instant lastVisit() {
        return lastVisit;
    }

    public String viewAs() {
        return viewAs;
    }

    /** The Home to show: the one viewed as (an administrator's, and a Home that is offered) or the person's start Home. */
    public String homeToShow(String start, boolean admin, List<String> offered) {
        return admin && !viewAs.isEmpty() && offered.contains(viewAs) ? viewAs : start;
    }

    /** Whether a panel is asleep at {@code now} (snoozed and not yet woken). */
    public boolean asleep(String panel, Instant now) {
        Instant until = snooze.get(panel);
        return until != null && until.isAfter(now);
    }

    /**
     * Where "since you were here" starts: the visit before this one while this visit is the one just recorded (a refresh, a pin or a move does
     * not move the window), otherwise the last visit; {@code null} on a first visit (the Home's cut-off is then used).
     */
    public Instant digestSince(Instant now) {
        if (lastVisit == null) {
            return null;
        }
        return Duration.between(lastVisit, now).toMinutes() < VISIT_MINUTES ? prevVisit : lastVisit;
    }

    /**
     * The state after one action. {@code panel} names the panel for pin, unpin, hide, show, up and down, and the Home for {@code viewas} (empty:
     * back to the person's own); {@code keys} are the panels a snooze puts to sleep; {@code visible} is the order the person sees (what up and down
     * move within). {@code viewas} is refused unless {@code admin}, and {@code offered} must hold the Home.
     */
    public HomeState apply(String action, String panel, Instant now, List<String> keys, List<String> visible, boolean admin, List<String> offered) {
        if (!ACTIONS.contains(action)) {
            throw bad("action is one of " + String.join(", ", ACTIONS));
        }
        String p = panel == null ? "" : panel;
        boolean named = List.of("pin", "unpin", "hide", "show", "up", "down").contains(action);
        if (named && !PANEL.matcher(p).matches()) {
            throw bad("the action " + action + " names a panel of your Home");
        }
        List<String> pin = new ArrayList<>(pins);
        List<String> hid = new ArrayList<>(hidden);
        List<String> ord = new ArrayList<>(order);
        Map<String, Instant> sleep = new LinkedHashMap<>(snooze);
        Instant last = lastVisit;
        Instant prev = prevVisit;
        String as = viewAs;
        switch (action) {
            case "reset" -> {
                pin.clear();
                hid.clear();
                ord.clear();
                sleep.clear();
            }
            case "snooze" -> {
                for (String k : keys == null ? List.<String>of() : keys) {
                    if (PANEL.matcher(k).matches() && sleep.size() < MAX_NAMES) {
                        sleep.put(k, now.plus(Duration.ofHours(SNOOZE_HOURS)));
                    }
                }
            }
            case "visited" -> {
                if (last != null) {
                    prev = last;                       // the digest of this visit is read against the visit before it
                }
                last = now;
            }
            case "viewas" -> {
                if (!admin) {
                    throw new DrishtiException(ErrorCode.FORBIDDEN, "only an administrator may view as another Home; choose your start Home instead");
                }
                if (!p.isEmpty() && (!HOME.matcher(p).matches() || !offered.contains(p))) {
                    throw bad("no Home " + p + " is offered to you");
                }
                as = p;
            }
            case "pin" -> {
                pin.remove(p);
                pin.add(p);
                while (pin.size() > MAX_PINS) {
                    pin.remove(0);
                }
            }
            case "unpin" -> pin.remove(p);
            case "hide" -> {
                hid.remove(p);
                hid.add(p);
                pin.remove(p);
            }
            case "show" -> hid.remove(p);
            default -> {                               // up, down: a pinned panel moves among the pinned, any other among the rest
                List<String> seq = new ArrayList<>(visible == null ? List.<String>of() : visible);
                seq.removeAll(hid);
                boolean pinned = pin.contains(p);
                List<String> pinsNow = List.copyOf(pin);
                List<String> within = new ArrayList<>(seq.stream().filter(x -> pinsNow.contains(x) == pinned).toList());
                int i = within.indexOf(p);
                int j = "up".equals(action) ? i - 1 : i + 1;
                if (i >= 0 && j >= 0 && j < within.size()) {
                    within.set(i, within.get(j));
                    within.set(j, p);
                }
                if (i >= 0) {
                    if (pinned) {
                        pin = within;
                    } else {
                        ord = within;
                    }
                }
            }
        }
        return new HomeState(pin, hid, ord, sleep, last, prev, as);
    }

    /** What is stored. */
    public ObjectNode toJson() {
        ObjectNode out = JSON.createObjectNode();
        for (Map.Entry<String, List<String>> e : Map.of("pins", pins, "hidden", hidden, "order", order).entrySet()) {
            ArrayNode a = out.putArray(e.getKey());
            e.getValue().forEach(a::add);
        }
        ObjectNode s = out.putObject("snooze");
        snooze.forEach((k, v) -> s.put(k, v.toString()));
        if (lastVisit != null) {
            out.put("lastVisit", lastVisit.toString());
        }
        if (prevVisit != null) {
            out.put("prevVisit", prevVisit.toString());
        }
        if (!viewAs.isEmpty()) {
            out.put("viewAs", viewAs);
        }
        return out;
    }

    private static DrishtiException bad(String why) {
        return new DrishtiException(ErrorCode.BAD_REQUEST, why);
    }
}
