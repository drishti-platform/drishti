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
package com.ash.drishti.enginehost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.engine.Answer;
import com.ash.engine.EngineException;
import com.ash.engine.Health;
import com.ash.engine.LifecycleState;
import com.ash.engine.Status;
import com.ash.engine.Table;
import com.ash.engine.Tables;
import com.ash.engine.host.HealthBoard;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The drop-in proof inside Drishti's host (ENGINE_INTEGRATION_DESIGN 0.11): the sample engine's JAR copied into a temporary lib folder is
 * found, checked, bound from its engine file and run; it answers through the host's inputs, its health and stats reach the board; suspend
 * and resume keep its answers; a licence that does not grant its product and a JAR built for a newer API are refused in words; a removed
 * JAR is "not installed" at the next start, and nothing else is affected by any of it.
 */
class EngineHostTest {

    private static final HostedEngine.Ask TOTALS = new HostedEngine.Ask(List.of("totals"), Map.of(), Map.of(), null, false);
    private static final ViewerAccess ANN = Fixtures.viewer("ann", Map.of(), Set.of());

    private static List<Object[]> rows(Answer a) {
        Table t = a.table("totals");
        return Tables.rows(t);
    }

    @Test
    void aDroppedJarRunsHealthyAnswersThroughTheHostAndIsCounted(@TempDir Path root) throws Exception {
        EngineHostSettings s = Fixtures.home(root, Fixtures.sampleJar(), Map.of("group-totals", Fixtures.GROUP_TOTALS), null);
        Fixtures.Kinds data = Fixtures.items();
        try (EngineHost host = Fixtures.host(s, data)) {
            host.start();
            HostedEngine gt = host.require("group-totals");
            assertThat(gt.state()).isEqualTo("running");
            assertThat(gt.lifecycle()).isEqualTo(LifecycleState.RUNNING);
            assertThat(gt.health().state()).isEqualTo(Health.State.UP);
            assertThat(gt.binding().inputs().get("items").kinds()).containsExactly("item");
            assertThat(gt.binding().inputs().get("items").rule()).isEqualTo(1);
            assertThat(gt.isolation()).isEqualTo("per-viewer");

            Answer a = gt.ask(TOTALS, ANN);
            assertThat(a.status()).isEqualTo(Status.COMPLETE);
            assertThat(rows(a)).containsExactly(new Object[] {"A", 2L, 3L, 4.0}, new Object[] {"B", 2L, 7L, 7.0});
            assertThat(a.stamp().engine()).isEqualTo("group-totals");
            assertThat(a.stamp().version()).isEqualTo("1.0.0");
            assertThat(a.stamp().inputs()).containsEntry("items", "item=v1");

            host.poll();
            assertThat(gt.stats().sinceStart().asked()).isEqualTo(1);
            assertThat(gt.stats().sinceStart().answered()).isEqualTo(1);
            HealthBoard.Report r = host.report();
            assertThat(r.engines()).anySatisfy(e -> {
                assertThat(e.engineId()).isEqualTo("group-totals");
                assertThat(e.health().state()).isEqualTo(Health.State.UP);
                assertThat(e.stats().sinceStart().asked()).isEqualTo(1);
            });

            // the JAR's other engine has no file: installed, not configured, with a template from its descriptor
            HostedEngine wmean = host.require("wmean");
            assertThat(wmean.state()).isEqualTo("not configured");
            assertThat(wmean.code()).isEqualTo("ENG-5034");
            assertThat(host.template("wmean")).contains("wmean");
            assertThat(host.jars()).singleElement().satisfies(j -> {
                assertThat(j.engines()).contains("group-totals 1.0.0", "wmean 1.0.0");
                assertThat(j.sha384()).hasSize(96);
            });
            assertThat(host.platform()).startsWith("provides engine API 0.1");
            assertThat(gt.engineClassLoader()).isNotSameAs(getClass().getClassLoader());
        }
    }

    @Test
    void theCallersRowRulesAndMasksApplyBeforeTheEngineSeesARow(@TempDir Path root) throws Exception {
        EngineHostSettings s = Fixtures.home(root, Fixtures.sampleJar(), Map.of("group-totals", Fixtures.GROUP_TOTALS), null);
        try (EngineHost host = Fixtures.host(s, Fixtures.items())) {
            host.start();
            HostedEngine gt = host.require("group-totals");
            ViewerAccess bob = Fixtures.viewer("bob", Map.of("item", Fixtures.eq("GROUP", "A")), Set.of());
            assertThat(rows(gt.ask(TOTALS, bob))).containsExactly(new Object[] {"A", 2L, 3L, 4.0});
            // another caller has their own session: ann still sees both groups
            assertThat(rows(gt.ask(TOTALS, ANN))).hasSize(2);
            assertThat(gt.sessionsOpen()).isEqualTo(2);
        }
    }

    @Test
    void anAnswerAskedAgainComesFromTheHostsCacheByStampAndNeverAcrossViewers(@TempDir Path root) throws Exception {
        EngineHostSettings s = Fixtures.home(root, Fixtures.sampleJar(), Map.of("group-totals", Fixtures.GROUP_TOTALS), null);
        try (EngineHost host = Fixtures.host(s, Fixtures.items())) {
            host.start();
            HostedEngine gt = host.require("group-totals");
            Answer first = gt.ask(TOTALS, ANN);
            Answer again = gt.ask(TOTALS, ANN);
            assertThat(again.stamp().text()).isEqualTo(first.stamp().text());
            assertThat(rows(again)).containsExactlyElementsOf(rows(first));
            host.poll();
            assertThat(gt.stats().sinceStart().asked()).as("the engine asked once").isEqualTo(1);
            assertThat(gt.answerCache()[0]).as("entries").isEqualTo(1L);
            assertThat(gt.answerCache()[1]).as("bytes").isPositive();
            assertThat(gt.answerCache()[2]).as("hits").isEqualTo(1L);
            assertThat(gt.answerCache()[3]).as("misses").isEqualTo(1L);
            // what a reader derives from an answer is kept with it: built once, read by the next ask of the same answer
            HostedEngine.Answered kept = gt.answer(TOTALS, ANN);
            Object shaped = new Object();
            assertThat(kept.keep("groups", shaped, 100)).isSameAs(shaped);
            assertThat(gt.answer(TOTALS, ANN).kept("groups")).as("the same answer, the same shaped value").isSameAs(shaped);
            assertThat(gt.answerCache()[1]).as("the shaped value counted").isGreaterThan(100L);
            // another viewer (another row rule): another stamp, the engine asked, the rows the viewer's own
            ViewerAccess bob = Fixtures.viewer("bob", Map.of("item", Fixtures.eq("GROUP", "A")), Set.of());
            assertThat(rows(gt.ask(TOTALS, bob))).containsExactly(new Object[] {"A", 2L, 3L, 4.0});
            assertThat(gt.answer(TOTALS, bob).kept("groups")).as("another viewer never reaches a value shaped from another's rows").isNull();
            assertThat(rows(gt.ask(TOTALS, ANN))).hasSize(2);
            assertThat(gt.answerCache()[2]).isEqualTo(5L);
            assertThat(gt.answerCache()[3]).isEqualTo(2L);
            gt.purgeAnswers(null);
            gt.ask(TOTALS, ANN);
            assertThat(gt.answerCache()[3]).as("after a purge the engine answers again").isEqualTo(3L);
            gt.purgeAnswers(java.time.LocalDate.parse("2001-01-01"));
            assertThat(gt.answerCache()[0]).as("a purge of another business date keeps them").isPositive();
        }
    }

    /**
     * Single flight: two callers asking the same question at once (the same outputs, parameters, snapshot and visibility: one stamp) have
     * it computed once; the second waits for the first's answer and is counted a hit, with the same cache entry.
     */
    @Test
    void twoCallersAskingTheSameQuestionAtOnceComputeItOnce(@TempDir Path root) throws Exception {
        EngineHostSettings s = Fixtures.home(root, Fixtures.sampleJar(), Map.of("group-totals", Fixtures.GROUP_TOTALS), null);
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Fixtures.Kinds data = new Fixtures.Kinds() {
            @Override
            public java.util.Optional<com.ash.drishti.api.ColumnSet> columns(String kind, java.util.Collection<String> paths,
                    java.time.LocalDate date) {
                entered.countDown();                                    // the first caller's engine is reading its rows: held here
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.columns(kind, paths, date);
            }
        };
        data.sets.putAll(Fixtures.items().sets);
        data.stamps.putAll(Fixtures.items().stamps);
        try (EngineHost host = Fixtures.host(s, data)) {
            host.start();
            HostedEngine gt = host.require("group-totals");
            java.util.concurrent.ExecutorService two = java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                java.util.concurrent.Future<HostedEngine.Answered> first = two.submit(() -> gt.answer(TOTALS, ANN));
                assertThat(entered.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                Thread[] waiter = new Thread[1];
                java.util.concurrent.Future<HostedEngine.Answered> second = two.submit(() -> {
                    waiter[0] = Thread.currentThread();
                    return gt.answer(TOTALS, ANN);
                });
                long until = System.nanoTime() + 30_000_000_000L;      // the second caller waits on the first's flight
                while (System.nanoTime() < until && (waiter[0] == null || waiter[0].getState() != Thread.State.TIMED_WAITING
                        && waiter[0].getState() != Thread.State.WAITING)) {
                    Thread.onSpinWait();
                }
                release.countDown();
                HostedEngine.Answered a = first.get(30, java.util.concurrent.TimeUnit.SECONDS);
                HostedEngine.Answered b = second.get(30, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(rows(b.answer())).containsExactlyElementsOf(rows(a.answer()));
                assertThat(b.kept()).as("the same cache entry").isSameAs(a.kept());
                host.poll();
                assertThat(gt.stats().sinceStart().asked()).as("the engine asked once").isEqualTo(1);
                assertThat(gt.answerCache()[3]).as("misses").isEqualTo(1L);
                assertThat(gt.answerCache()[2]).as("hits").isEqualTo(1L);
            } finally {
                release.countDown();
                two.shutdownNow();
            }
        }
    }

    @Test
    void anAuditLineNamesALongListByItsCountNeverItsValues() {
        java.util.SortedMap<String, Object> params = new java.util.TreeMap<>();
        params.put("rows", java.util.stream.IntStream.range(0, 200_000).boxed().toList());
        params.put("parts", List.of(1, 2, 3));
        params.put("window", 250);
        assertThat(HostedEngine.auditParams(params)).isEqualTo("{parts=[1, 2, 3], rows=list:200000 items, window=250}");
    }

    @Test
    void suspendRefusesInWordsAndResumeAnswersTheSame(@TempDir Path root) throws Exception {
        EngineHostSettings s = Fixtures.home(root, Fixtures.sampleJar(), Map.of("group-totals", Fixtures.GROUP_TOTALS), null);
        try (EngineHost host = Fixtures.host(s, Fixtures.items())) {
            host.start();
            HostedEngine gt = host.require("group-totals");
            List<Object[]> before = rows(gt.ask(TOTALS, ANN));

            host.transition("group-totals", "suspend", "ops", "maintenance");
            assertThat(gt.state()).isEqualTo("suspended");
            assertThat(gt.health().state()).isEqualTo(Health.State.SUSPENDED);
            assertThatThrownBy(() -> gt.ask(TOTALS, ANN)).isInstanceOf(EngineException.class)
                    .satisfies(e -> assertThat(((EngineException) e).code()).isEqualTo("ENG-5023")).hasMessageContaining("maintenance");
            assertThat(gt.refusedByHost()).isEqualTo(1);
            assertThat(gt.sessionsOpen()).as("a suspend keeps the sessions").isEqualTo(1);

            host.transition("group-totals", "resume", "ops", null);
            assertThat(gt.state()).isEqualTo("running");
            assertThat(rows(gt.ask(TOTALS, ANN))).containsExactlyElementsOf(before);

            assertThatThrownBy(() -> host.transition("group-totals", "resume", "ops", null)).isInstanceOf(EngineException.class)
                    .satisfies(e -> assertThat(((EngineException) e).code()).isEqualTo("ENG-5020"));
            host.transition("group-totals", "shutdown", "ops", null);
            assertThat(gt.state()).isEqualTo("off");
            host.transition("group-totals", "start", "ops", null);
            assertThat(rows(gt.ask(TOTALS, ANN))).containsExactlyElementsOf(before);
        }
    }

    @Test
    void aLicenceThatDoesNotGrantTheProductBlocksTheEngineInOneSentence(@TempDir Path root) throws Exception {
        EngineHostSettings s = Fixtures.home(root, Fixtures.sampleJar(), Map.of("group-totals", Fixtures.GROUP_TOTALS),
                "licence:\n  customer: Example\n  products: [ drishti-risk ]\n");
        try (EngineHost host = Fixtures.host(s, Fixtures.items())) {
            host.start();
            HostedEngine gt = host.require("group-totals");
            assertThat(gt.state()).isEqualTo("blocked");
            assertThat(gt.code()).isEqualTo("ENG-1014");
            assertThat(gt.words()).contains("group-totals 1.0.0").contains("the product drishti-engine-samples")
                    .contains("which this installation's licence does not grant");
            assertThatThrownBy(() -> gt.ask(TOTALS, ANN)).isInstanceOf(EngineException.class)
                    .satisfies(e -> assertThat(((EngineException) e).code()).isEqualTo("ENG-1014"));
            assertThat(host.licence().products()).containsExactly("drishti-risk");
        }
    }

    @Test
    void aJarBuiltForANewerApiIsBlockedWithBothVersionsAndTheOtherEngineIsNot(@TempDir Path root) throws Exception {
        Path next = root.resolve("engine-sample-next.jar");
        Fixtures.rewrite(Fixtures.sampleJar(), next, "group-totals", "api: \"0.1\"", "api: \"0.9\"");
        EngineHostSettings s = Fixtures.home(root.resolve("home"), next, Map.of("group-totals", Fixtures.GROUP_TOTALS), null);
        try (EngineHost host = Fixtures.host(s, Fixtures.items())) {
            host.start();
            HostedEngine gt = host.require("group-totals");
            assertThat(gt.state()).isEqualTo("blocked");
            assertThat(gt.code()).isEqualTo("ENG-1002");
            assertThat(gt.words()).contains("was built for engine API 0.9").contains("provides 0.1").contains("Nothing else is affected");
            assertThat(host.require("wmean").state()).as("the JAR's other engine is untouched").isEqualTo("not configured");
        }
    }

    @Test
    void aRemovedJarIsNotInstalledAtTheNextStartAndItsFileSaysSo(@TempDir Path root) throws Exception {
        EngineHostSettings s = Fixtures.home(root, Fixtures.sampleJar(), Map.of("group-totals", Fixtures.GROUP_TOTALS), null);
        try (EngineHost host = Fixtures.host(s, Fixtures.items())) {
            host.start();
            assertThat(host.require("group-totals").state()).isEqualTo("running");
        }
        try (var jars = Files.list(s.libPath())) {
            for (Path j : jars.toList()) {
                Files.delete(j);
            }
        }
        try (EngineHost host = Fixtures.host(s, Fixtures.items())) {
            host.start();
            HostedEngine gt = host.require("group-totals");
            assertThat(gt.state()).isEqualTo("not installed");
            assertThat(gt.code()).isEqualTo("ENG-5030");
            assertThat(gt.health().state()).isEqualTo(Health.State.DOWN);
            assertThatThrownBy(() -> gt.ask(TOTALS, ANN)).isInstanceOf(EngineException.class)
                    .satisfies(e -> assertThat(((EngineException) e).code()).isEqualTo("ENG-5030"));
            assertThat(host.engine("wmean")).as("an engine with neither JAR nor file is unknown").isEmpty();
            assertThat(host.jars()).isEmpty();
        }
    }

    @Test
    void aSwitchedOffEngineIsOffAndStartsLive(@TempDir Path root) throws Exception {
        EngineHostSettings s = Fixtures.home(root, Fixtures.sampleJar(),
                Map.of("group-totals", Fixtures.GROUP_TOTALS.replace("enabled: true", "enabled: false")), null);
        try (EngineHost host = Fixtures.host(s, Fixtures.items())) {
            host.start();
            HostedEngine gt = host.require("group-totals");
            assertThat(gt.state()).isEqualTo("off");
            assertThatThrownBy(() -> gt.ask(TOTALS, ANN)).isInstanceOf(EngineException.class)
                    .satisfies(e -> assertThat(((EngineException) e).code()).isEqualTo("ENG-5031"));
            host.transition("group-totals", "start", "ops", null);
            assertThat(gt.state()).isEqualTo("running");
            assertThat(gt.ask(TOTALS, ANN).status()).isEqualTo(Status.COMPLETE);
        }
    }

    @Test
    void anEngineFileWithAnUnknownKeyBlocksOnlyThatEngine(@TempDir Path root) throws Exception {
        EngineHostSettings s = Fixtures.home(root, Fixtures.sampleJar(),
                Map.of("group-totals", Fixtures.GROUP_TOTALS.replace("      enabled: true", "      enabled: true\n      colour: blue")), null);
        try (EngineHost host = Fixtures.host(s, Fixtures.items())) {
            host.start();
            HostedEngine gt = host.require("group-totals");
            assertThat(gt.state()).isEqualTo("blocked");
            assertThat(gt.words()).contains("colour");
        }
    }
}
