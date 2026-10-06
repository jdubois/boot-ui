package io.github.jdubois.bootui.engine.insights;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.RuntimeRunComparisonDto;
import io.github.jdubois.bootui.core.dto.RuntimeSideEffectChangeDto;
import io.github.jdubois.bootui.core.dto.RuntimeSideEffectChangesDto;
import io.github.jdubois.bootui.core.dto.RuntimeSideEffectSensorDto;
import io.github.jdubois.bootui.engine.journal.RunSideEffects;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import org.junit.jupiter.api.Test;

/** M5-7b: what changed outside the JVM between two runs, said only where both runs recorded it whole. */
class SideEffectComparisonTests {

    private static final BiPredicate<String, String> ORDERS_EXERCISED =
            (scope, owner) -> "route".equals(scope) && "GET /orders".equals(owner);

    @Test
    void aNewHostOfARouteIsAddedAndAGoneOneRemovedOnlyWhereItsOwnerRanAgain() {
        RunSideEffects previous = run(
                whole(),
                key("network", "connect", "old.example.com:443", "route", "GET /orders", "JDK HttpClient", 3),
                key("files", "read", "/tmp/{file}", "route", "GET /reports", null, 1));
        RunSideEffects current = run(
                whole(), key("network", "connect", "api.example.com:443", "route", "GET /orders", "JDK HttpClient", 2));

        RuntimeSideEffectChangesDto changes =
                SideEffectComparison.compare(previous, current, ORDERS_EXERCISED, ORDERS_EXERCISED, false);

        assertThat(changes.available()).isTrue();
        assertThat(changes.partial()).isFalse();
        assertThat(changes.changes())
                .extracting(RuntimeSideEffectChangeDto::change, RuntimeSideEffectChangeDto::sentence)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                RuntimeSideEffectChangeDto.ADDED,
                                "`GET /orders` now connects to `api.example.com:443` (JDK HttpClient)."),
                        org.assertj.core.groups.Tuple.tuple(
                                RuntimeSideEffectChangeDto.REMOVED,
                                "`GET /orders` no longer connects to `old.example.com:443` (JDK HttpClient)."),
                        org.assertj.core.groups.Tuple.tuple(
                                RuntimeSideEffectChangeDto.NOT_EXERCISED,
                                "`GET /reports` read `/tmp/{file}` in the previous run, and was not exercised in this"
                                        + " run: not compared."));
        assertThat(changes.sensors())
                .extracting(RuntimeSideEffectSensorDto::sensor, RuntimeSideEffectSensorDto::status)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("network", RuntimeSideEffectSensorDto.COMPARED),
                        org.assertj.core.groups.Tuple.tuple("files", RuntimeSideEffectSensorDto.COMPARED),
                        org.assertj.core.groups.Tuple.tuple("processes", RuntimeSideEffectSensorDto.COMPARED),
                        org.assertj.core.groups.Tuple.tuple("environment", RuntimeSideEffectSensorDto.COMPARED));
        assertThat(changes.sensors().get(0).added()).isEqualTo(1);
        assertThat(changes.sensors().get(0).removed()).isEqualTo(1);
        assertThat(changes.sensors().get(1).notExercised()).isEqualTo(1);
    }

    @Test
    void aKeyOfARouteThePreviousRunNeverServedIsNotReportedNew() {
        RunSideEffects previous = run(whole());
        RunSideEffects current = run(whole(), key("network", "connect", "api.example.com:443", "route", "GET /new"));

        RuntimeSideEffectChangesDto changes =
                SideEffectComparison.compare(previous, current, ORDERS_EXERCISED, (scope, owner) -> true, false);

        assertThat(changes.changes()).singleElement().satisfies(change -> {
            assertThat(change.change()).isEqualTo(RuntimeSideEffectChangeDto.NOT_EXERCISED);
            assertThat(change.sentence())
                    .isEqualTo("`GET /new` connects to `api.example.com:443` in this run, and was not exercised in the"
                            + " previous run: not compared.");
        });
        assertThat(changes.sensors().get(0).added()).isZero();
        assertThat(changes.sensors().get(0).notExercised()).isEqualTo(1);
    }

    @Test
    void anOwnerThatDidSomethingElseOutsideTheJvmThisRunCountsAsExercised() {
        RunSideEffects previous = run(whole(), key("processes", "process", "git", "execution", "scheduled Sync.run"));
        RunSideEffects current =
                run(whole(), key("environment", "environment variable", "HOME", "execution", "scheduled Sync.run"));

        RuntimeSideEffectChangesDto changes = SideEffectComparison.compare(
                previous, current, (scope, owner) -> false, (scope, owner) -> false, false);

        assertThat(changes.changes())
                .extracting(RuntimeSideEffectChangeDto::sentence)
                .containsExactly(
                        "`scheduled Sync.run` now reads the environment variable `HOME` (its name only, never its"
                                + " value).",
                        "`scheduled Sync.run` no longer starts the process `git`.");
    }

    @Test
    void aSensorThatDidNotRecordEitherRunWholeIsNotComparedAndListsNothing() {
        RunSideEffects previous = run(
                List.of(
                        new RunSideEffects.Sensor("network", "the agent dropped 3 records: its ring was full", null, 0),
                        sensor("files"),
                        sensor("processes"),
                        sensor("environment")),
                key("network", "connect", "old:443", "route", "GET /orders"));
        RunSideEffects current = run(
                List.of(
                        sensor("network"),
                        sensor("files"),
                        new RunSideEffects.Sensor(
                                "processes",
                                "it was not claimed: bootui.agent.sensors does not include processes",
                                null,
                                0),
                        sensor("environment")),
                key("network", "connect", "new:443", "route", "GET /orders"),
                key("processes", "process", "git", "route", "GET /orders"));

        RuntimeSideEffectChangesDto changes =
                SideEffectComparison.compare(previous, current, ORDERS_EXERCISED, ORDERS_EXERCISED, false);

        assertThat(changes.changes()).isEmpty();
        assertThat(changes.sensors().get(0)).satisfies(sensor -> {
            assertThat(sensor.status()).isEqualTo(RuntimeSideEffectSensorDto.NOT_COMPARED);
            assertThat(sensor.reason())
                    .isEqualTo("Not compared: network was not recording the whole previous run: the agent dropped 3"
                            + " records: its ring was full.");
        });
        assertThat(changes.sensors().get(2).reason())
                .startsWith("Not compared: processes was not recording the whole of this run: it was not claimed");
        assertThat(changes.sensors().get(1).status()).isEqualTo(RuntimeSideEffectSensorDto.COMPARED);
    }

    @Test
    void startupKeysAreComparedOnlyWhenBothRunsRecordedStartupWhole() {
        RunSideEffects previous = run(
                List.of(
                        new RunSideEffects.Sensor(
                                "environment",
                                null,
                                "the agent was still installing it when the application started",
                                0),
                        sensor("network"),
                        sensor("files"),
                        sensor("processes")),
                key("environment", "environment variable", "OLD_FLAG", "startup", "startup"));
        RunSideEffects current = run(
                whole(),
                key("environment", "environment variable", "STRIPE_KEY", "startup", "startup"),
                key("environment", "environment variable", "REGION", "route", "GET /orders"));

        RuntimeSideEffectChangesDto changes =
                SideEffectComparison.compare(previous, current, ORDERS_EXERCISED, ORDERS_EXERCISED, false);

        assertThat(changes.changes())
                .extracting(RuntimeSideEffectChangeDto::target)
                .containsExactly("REGION");
        assertThat(changes.sensors())
                .filteredOn(sensor -> sensor.sensor().equals("environment"))
                .singleElement()
                .satisfies(sensor -> {
                    assertThat(sensor.status()).isEqualTo(RuntimeSideEffectSensorDto.COMPARED);
                    assertThat(sensor.reason())
                            .isEqualTo("Its startup is not compared: in the previous run, the agent was still"
                                    + " installing it when the application started.");
                });
    }

    @Test
    void aRunThatKeptPartOfItsKeysWithholdsOnlyTheRowsThatPartCouldMakeWrong() {
        RunSideEffects cutBefore = run(
                List.of(
                        new RunSideEffects.Sensor("network", null, null, 4),
                        sensor("files"),
                        sensor("processes"),
                        sensor("environment")),
                key("network", "connect", "gone:443", "route", "GET /orders"));
        RunSideEffects current = run(whole(), key("network", "connect", "new:443", "route", "GET /orders"));

        RuntimeSideEffectChangesDto previousCut =
                SideEffectComparison.compare(cutBefore, current, ORDERS_EXERCISED, ORDERS_EXERCISED, false);

        assertThat(previousCut.partial()).isTrue();
        assertThat(previousCut.changes())
                .extracting(RuntimeSideEffectChangeDto::change)
                .containsExactly(RuntimeSideEffectChangeDto.REMOVED);
        assertThat(previousCut.sensors().get(0).status()).isEqualTo(RuntimeSideEffectSensorDto.PARTIAL);
        assertThat(previousCut.sensors().get(0).reason()).contains("1 key not in it is not reported new");

        RunSideEffects cutNow = run(
                List.of(
                        new RunSideEffects.Sensor("network", null, null, 1),
                        sensor("files"),
                        sensor("processes"),
                        sensor("environment")),
                key("network", "connect", "new:443", "route", "GET /orders"));
        RuntimeSideEffectChangesDto currentCut = SideEffectComparison.compare(
                run(whole(), key("network", "connect", "gone:443", "route", "GET /orders")),
                cutNow,
                ORDERS_EXERCISED,
                ORDERS_EXERCISED,
                false);

        assertThat(currentCut.changes())
                .extracting(RuntimeSideEffectChangeDto::change)
                .containsExactly(RuntimeSideEffectChangeDto.ADDED);
        assertThat(currentCut.sensors().get(0).reason()).contains("1 key not in it is not reported gone");
    }

    @Test
    void routesHiddenInEitherRunAreOneHiddenRouteWhoseKeysAreNeverReportedGone() {
        RunSideEffects previous = new RunSideEffects(
                null,
                true,
                whole(),
                List.of(
                        key("network", "connect", "a:443", "route", SideEffectComparison.HIDDEN_ROUTE),
                        key("network", "connect", "b:443", "route", SideEffectComparison.HIDDEN_ROUTE)));
        RunSideEffects current = run(
                whole(),
                key("network", "connect", "a:443", "route", "GET /orders"),
                key("network", "connect", "c:443", "route", "GET /orders"));

        RuntimeSideEffectChangesDto changes =
                SideEffectComparison.compare(previous, current, ORDERS_EXERCISED, ORDERS_EXERCISED, false);

        assertThat(changes.changes())
                .extracting(RuntimeSideEffectChangeDto::target, RuntimeSideEffectChangeDto::change)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("b:443", RuntimeSideEffectChangeDto.NOT_EXERCISED),
                        org.assertj.core.groups.Tuple.tuple("c:443", RuntimeSideEffectChangeDto.NOT_EXERCISED));
        assertThat(changes.changes())
                .allSatisfy(change -> assertThat(change.owner()).isEqualTo(SideEffectComparison.HIDDEN_ROUTE));
        assertThat(changes.limitations()).contains(SideEffectComparison.LIMITATION_HIDDEN_ROUTES);
    }

    @Test
    void theChangesAreBoundedAndCounted() {
        List<RunSideEffects.Key> keys = new ArrayList<>();
        for (int i = 0; i < RuntimeRunComparisonDto.MAX_ROWS + 5; i++) {
            keys.add(key("network", "connect", "host-" + i + ":443", "route", "GET /orders"));
        }
        RuntimeSideEffectChangesDto changes = SideEffectComparison.compare(
                run(whole()),
                new RunSideEffects(null, false, whole(), keys),
                ORDERS_EXERCISED,
                ORDERS_EXERCISED,
                false);

        assertThat(changes.changes()).hasSize(RuntimeRunComparisonDto.MAX_ROWS);
        assertThat(changes.changesTotal()).isEqualTo(RuntimeRunComparisonDto.MAX_ROWS + 5);
        assertThat(Set.copyOf(changes.limitations()))
                .contains(SideEffectComparison.LIMITATION_VALUES, SideEffectComparison.LIMITATION_WHOLE);
    }

    @Test
    void sentencesNameWhatEachKindDid() {
        assertThat(SideEffectComparison.sentence(
                        key("files", "copy to", "./out/{n}.csv", "route", "POST /export"),
                        RuntimeSideEffectChangeDto.REMOVED))
                .isEqualTo("`POST /export` no longer copies files to `./out/{n}.csv`.");
        assertThat(SideEffectComparison.sentence(
                        key("files", "copy to", "./out/{n}.csv", "route", "POST /export"),
                        RuntimeSideEffectChangeDto.NOT_EXERCISED))
                .startsWith("`POST /export` copied files to `./out/{n}.csv` in the previous run");
        assertThat(SideEffectComparison.sentence(
                        key("network", "datagram", "statsd:8125", "startup", "startup"),
                        RuntimeSideEffectChangeDto.ADDED))
                .isEqualTo("`startup` now sends datagrams to `statsd:8125`.");
        assertThat(SideEffectComparison.sentence(
                        key("network", "lookup", "db.internal", "route", "GET /orders"),
                        RuntimeSideEffectChangeDto.NOT_EXERCISED))
                .startsWith("`GET /orders` resolved `db.internal` in the previous run");
    }

    private static RunSideEffects run(List<RunSideEffects.Sensor> sensors, RunSideEffects.Key... keys) {
        return new RunSideEffects(null, false, sensors, List.of(keys));
    }

    private static List<RunSideEffects.Sensor> whole() {
        return List.of(sensor("network"), sensor("files"), sensor("processes"), sensor("environment"));
    }

    private static RunSideEffects.Sensor sensor(String id) {
        return new RunSideEffects.Sensor(id, null, null, 0);
    }

    private static RunSideEffects.Key key(String sensor, String kind, String target, String scope, String owner) {
        return key(sensor, kind, target, scope, owner, null, 1);
    }

    private static RunSideEffects.Key key(
            String sensor, String kind, String target, String scope, String owner, String client, long count) {
        return new RunSideEffects.Key(sensor, kind, target, scope, owner, client, count);
    }
}
