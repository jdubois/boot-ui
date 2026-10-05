package io.github.jdubois.bootui.engine.insights;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.RuntimeInsightCheckDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightsReportDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDto;
import io.github.jdubois.bootui.engine.insights.ObservationFixtures.Fixture;
import io.github.jdubois.bootui.engine.insights.ObservationFixtures.Role;
import io.github.jdubois.bootui.engine.journal.JournalEntry;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.JournalSourcePanels;
import io.github.jdubois.bootui.engine.journal.LogPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.engine.journal.SynchronousJournals;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * The cross-observation counterexample harness ({@code docs/PLAN-v2.md} §2.2's honesty measure, M4-18e): every Runtime
 * Insights kind against its seeded case and its counterexamples ({@link ObservationFixtures}), and every other kind
 * against them too, so a kind that fires on any fixture it was not seeded for fails the build.
 *
 * <p>Each fixture is also replayed with its evidence incomplete, where an observation must say so rather than report a
 * confident finding: each of its events dropped in turn, the recording cleared at every point, the ring overflowing at
 * every point, the capture a stack does not have, SQL that is not recorded, and a source not recorded or its panel
 * disabled.</p>
 *
 * <p>Every kind of {@link RuntimeInsightsService#defaultObservations()} passes it, including D29's four
 * ({@link #D29_KINDS}), whose counterexample fixtures D29 requires to pass before they are listed by default.</p>
 */
class ObservationHonestyHarnessTests {

    /** D29's kinds, which the default list shows because their counterexample fixtures pass this harness. */
    static final Set<String> D29_KINDS = Set.of(
            TransactionalListenerSkipped.KIND, AfterCommitWrites.KIND, OrmAutoFlush.KIND, LargePersistenceContext.KIND);

    /** Where each kind's capture does not exist, so its check is not applicable whatever was recorded (§5.5). */
    static final Map<String, Set<InsightsStack>> NOT_APPLICABLE_ON = Map.of(
            EventLoopBlocking.KIND, Set.of(InsightsStack.SPRING_MVC),
            LazySqlAfterHandler.KIND, Set.of(InsightsStack.SPRING_WEBFLUX, InsightsStack.QUARKUS),
            TransactionAcrossRemoteCall.KIND, Set.of(InsightsStack.QUARKUS),
            SplitTransactionWrites.KIND, Set.of(InsightsStack.QUARKUS),
            ProxyBypass.KIND, Set.of(InsightsStack.QUARKUS),
            TransactionalListenerSkipped.KIND, Set.of(InsightsStack.QUARKUS),
            AfterCommitWrites.KIND, Set.of(InsightsStack.QUARKUS));

    private static final List<Fixture> FIXTURES = ObservationFixtures.all();

    private static final Map<String, Observation> KINDS = RuntimeInsightsService.defaultObservations().stream()
            .collect(Collectors.toMap(Observation::kind, Function.identity(), (a, b) -> a, LinkedHashMap::new));

    /**
     * The kinds whose needs-more-traffic row may appear on a fixture seeded for another kind: a route's breakdown waits
     * for five warm requests, and saying so is not a finding.
     */
    private static final Set<String> INSUFFICIENT_ELSEWHERE = Set.of(RouteTimeBreakdown.KIND);

    @Test
    void everyKindHasItsSeededCaseAndACounterexample() {
        Map<String, Set<Role>> roles = FIXTURES.stream()
                .collect(Collectors.groupingBy(
                        Fixture::kind,
                        Collectors.mapping(Fixture::role, Collectors.toCollection(() -> EnumSet.noneOf(Role.class)))));

        assertThat(roles.keySet()).as("fixtures only for real kinds").isSubsetOf(KINDS.keySet());
        assertThat(KINDS.keySet())
                .allSatisfy(kind -> assertThat(roles.get(kind))
                        .as(kind + " has a seeded case and a counterexample")
                        .containsExactlyInAnyOrder(Role.POSITIVE, Role.COUNTEREXAMPLE));
        assertThat(NOT_APPLICABLE_ON.keySet()).isSubsetOf(KINDS.keySet());
    }

    @Test
    void d29KindsPassTheHarnessWithTheirCounterexamples() {
        assertThat(D29_KINDS)
                .allSatisfy(kind -> assertThat(FIXTURES)
                        .filteredOn(fixture -> fixture.kind().equals(kind) && fixture.role() == Role.COUNTEREXAMPLE)
                        .as(kind + "'s counterexamples")
                        .hasSizeGreaterThanOrEqualTo(2));
    }

    /** Each seeded case fires its kind, each counterexample does not, and no fixture fires a kind it does not declare. */
    @TestFactory
    Stream<DynamicNode> everyKindAgainstEveryFixture() {
        return FIXTURES.stream()
                .map(fixture -> DynamicTest.dynamicTest(fixture.toString(), () -> {
                    RuntimeInsightsReportDto report = replay(fixture, Replay.whole());
                    if (fixture.role() == Role.POSITIVE) {
                        assertThat(rows(report, fixture.kind()))
                                .as("%s is observed", fixture)
                                .anySatisfy(row -> assertThat(row.status()).isEqualTo("OBSERVED"));
                    }
                    assertHonest(fixture, report, "whole");
                }));
    }

    /**
     * With any one of its events dropped, a check reading the dropped source never claims a complete evaluation, and a
     * row the whole fixture does not show is only ever {@code PARTIAL}.
     */
    @TestFactory
    Stream<DynamicNode> eachDroppedEventMakesItsReadersPartial() {
        return FIXTURES.stream().filter(fixture -> !fixture.events().isEmpty()).map(fixture -> {
            RuntimeInsightsReportDto whole = replay(fixture, Replay.whole());
            return DynamicContainer.dynamicContainer(
                    fixture.toString(),
                    IntStream.range(0, fixture.events().size())
                            .mapToObj(index -> DynamicTest.dynamicTest(
                                    "drop event " + index + " ("
                                            + fixture.events()
                                                    .get(index)
                                                    .source()
                                                    .propertyName() + ")",
                                    () -> assertDropIsPartial(fixture, whole, index))));
        });
    }

    private static void assertDropIsPartial(Fixture fixture, RuntimeInsightsReportDto whole, int index) {
        JournalSource dropped = fixture.events().get(index).source();
        RuntimeInsightsReportDto report = replay(fixture, Replay.dropping(index));
        assertThat(report.window().droppedEvents()).isEqualTo(1);
        Map<String, String> shown = whole.observations().stream()
                .collect(Collectors.toMap(RuntimeObservationDto::id, RuntimeObservationDto::status));
        for (Observation observation : KINDS.values()) {
            RuntimeInsightCheckDto check = check(report, observation.kind());
            if (completenessSources(observation).contains(dropped)) {
                assertThat(check.status())
                        .as("%s reads the dropped %s event", observation.kind(), dropped)
                        .isNotEqualTo("EVALUATED");
                if ("PARTIAL".equals(check.status())) {
                    assertThat(check.reason()).contains("dropped 1 events");
                }
            }
            for (RuntimeObservationDto row : rows(report, observation.kind())) {
                if ("PARTIAL".equals(row.status())) {
                    assertThat(check.status())
                            .as("%s is partial under a partial check", row.id())
                            .isEqualTo("PARTIAL");
                } else if (!"INSUFFICIENT".equals(row.status())) {
                    assertThat(shown.get(row.id()))
                            .as(
                                    "%s, which the whole fixture does not observe, is not reported confidently: %s",
                                    row.id(), row.sentence())
                            .isEqualTo(row.status());
                }
            }
        }
    }

    /** The sources whose drops make a check partial (§5.5): those it reads, and the anchors of the work it examines. */
    private static Set<JournalSource> completenessSources(Observation observation) {
        Set<JournalSource> sources = EnumSet.noneOf(JournalSource.class);
        sources.addAll(observation.reads());
        sources.addAll(observation.optionalReads());
        if (observation.unitKinds().contains(ProjectedRequest.Kind.HTTP)) {
            sources.add(JournalSource.HTTP);
        }
        if (observation.unitKinds().contains(ProjectedRequest.Kind.SCHEDULED)) {
            sources.add(JournalSource.SCHEDULED);
        }
        if (observation.unitKinds().contains(ProjectedRequest.Kind.MESSAGE)) {
            sources.add(JournalSource.MESSAGING);
            sources.add(JournalSource.WEBSOCKET);
        }
        return sources;
    }

    /**
     * The recording cleared at any point, even in the middle of a request, and whether the events before it were
     * processed or still queued, reports exactly what the events after it report once the requests and executions that
     * may have lost events to the clear are left out.
     */
    @TestFactory
    Stream<DynamicNode> aClearAtAnyPointReportsOnlyCompleteWork() {
        return FIXTURES.stream()
                .filter(fixture -> fixture.events().size() > 1)
                .map(fixture -> DynamicContainer.dynamicContainer(
                        fixture.toString(),
                        IntStream.range(1, fixture.events().size())
                                .boxed()
                                .flatMap(cut -> Stream.of(true, false)
                                        .map(processed -> DynamicTest.dynamicTest(
                                                "clear after " + cut + " events, "
                                                        + (processed ? "processed" : "still queued"),
                                                () -> assertOnlyCompleteWork(
                                                        fixture,
                                                        Replay.clearingAfter(cut, processed),
                                                        lostBefore(cut)))))));
    }

    /**
     * The ring evicting events at any point, oldest first or keeping failed events longer in its reserved share, reports
     * exactly what the retained events report once the requests and executions that may have lost events are left out.
     */
    @TestFactory
    Stream<DynamicNode> anEvictionAtAnyPointReportsOnlyCompleteWork() {
        return FIXTURES.stream()
                .filter(fixture -> fixture.events().size() > 1)
                .map(fixture -> DynamicContainer.dynamicContainer(
                        fixture.toString(),
                        IntStream.range(1, fixture.events().size())
                                .boxed()
                                .flatMap(evicted -> Stream.of(0, 50)
                                        .map(share -> DynamicTest.dynamicTest(
                                                "evict " + evicted + " events, " + share + " % reserved for failures",
                                                () -> assertOnlyCompleteWork(
                                                        fixture, Replay.evicting(evicted, share), null))))));
    }

    private static Set<Integer> lostBefore(int cut) {
        return IntStream.range(0, cut).boxed().collect(Collectors.toSet());
    }

    /**
     * Compares a truncated replay with a fresh replay of only what it kept of complete units: a unit is incomplete when
     * it started at or before the latest start of a lost event of any unit, give or take the service's slack, since its
     * own events start no earlier. Independently of that rule, nothing fires that the fixture does not justify, and
     * nothing is observed that the whole fixture does not observe.
     *
     * @param lost the lost events' indexes, or {@code null} to read them from the journal's retained sequences
     */
    private static void assertOnlyCompleteWork(Fixture fixture, Replay replay, Set<Integer> lost) {
        Replayed truncated = replayKeepingJournal(fixture, replay);
        List<RuntimeEvent> events = fixture.events();
        Set<Integer> missing = lost != null
                ? lost
                : IntStream.range(0, events.size())
                        .filter(index -> !truncated.retainedSequences().contains(index + 1L))
                        .boxed()
                        .collect(Collectors.toSet());
        long horizon = missing.stream()
                .map(events::get)
                .filter(event -> event.requestId() != null || event.executionId() != null || event.traceId() != null)
                .mapToLong(RuntimeEvent::epochMillis)
                .max()
                .orElse(Long.MIN_VALUE);
        long reach = horizon == Long.MIN_VALUE ? horizon : horizon + RuntimeInsightsService.LOSS_HORIZON_SLACK_MILLIS;
        Set<String> incomplete = new HashSet<>();
        Set<String> incompleteTraces = new HashSet<>();
        long leftOut = 0;
        for (int index = 0; index < events.size(); index++) {
            RuntimeEvent event = events.get(index);
            if (isAnchor(event) && event.epochMillis() <= reach) {
                incomplete.add(unitOf(event));
                if (event.traceId() != null) {
                    incompleteTraces.add(event.traceId());
                }
                if (!missing.contains(index)) {
                    leftOut++;
                }
            }
        }
        // Each thread's retained requests, by start: an ERROR logged without an id after a left-out request ended
        // belongs to it while the thread served no other (framework-warnings-by-route's rule).
        Map<String, TreeMap<Long, RuntimeEvent>> byThread = new HashMap<>();
        for (int index = 0; index < events.size(); index++) {
            RuntimeEvent event = events.get(index);
            if (!missing.contains(index) && event.source() == JournalSource.HTTP && event.thread() != null) {
                byThread.computeIfAbsent(event.thread(), thread -> new TreeMap<>())
                        .put(event.epochMillis(), event);
            }
        }
        long lostRequestEnd = missing.stream()
                .map(events::get)
                .filter(event -> event.source() == JournalSource.HTTP && event.requestId() != null)
                .mapToLong(event -> event.epochMillis() + event.durationNanos() / 1_000_000)
                .max()
                .orElse(Long.MIN_VALUE);
        Set<String> lostThreads = missing.stream()
                .map(events::get)
                .filter(event -> event.requestId() != null && event.thread() != null)
                .map(RuntimeEvent::thread)
                .collect(Collectors.toSet());
        List<RuntimeEvent> kept = IntStream.range(0, events.size())
                .filter(index -> !missing.contains(index))
                .mapToObj(events::get)
                .filter(event -> !incomplete.contains(unitOf(event)))
                .filter(event -> !(unitOf(event) == null
                        && event.traceId() == null
                        && event.payload() instanceof LogPayload log
                        && "ERROR".equals(log.level())
                        && reach != Long.MIN_VALUE
                        && ((lostThreads.contains(event.thread())
                                        && (event.epochMillis() <= reach
                                                || (lostRequestEnd != Long.MIN_VALUE
                                                        && event.epochMillis()
                                                                <= lostRequestEnd
                                                                        + FrameworkWarningsByRoute
                                                                                .AFTER_REQUEST_MILLIS)))
                                || afterLeftOutRequest(event, byThread, incomplete))))
                .filter(event -> unitOf(event) != null
                        || event.traceId() == null
                        || (event.epochMillis() > reach && !incompleteTraces.contains(event.traceId())))
                .toList();
        Fixture survivors = new Fixture(
                fixture.kind(),
                fixture.role(),
                fixture.name(),
                fixture.stack(),
                fixture.alsoFires(),
                fixture.tolerated(),
                fixture.setup(),
                kept);
        RuntimeInsightsReportDto expected = replay(survivors, Replay.whole().afterAClear());

        assertThat(summary(truncated.report()))
                .as("%s with events %s lost reports only its complete work", fixture, new TreeSet<>(missing))
                .isEqualTo(summary(expected));
        if (leftOut > 0) {
            String counted = leftOut + (leftOut == 1 ? " request or execution " : " requests or executions ");
            assertThat(truncated.report().limitations())
                    .anySatisfy(limitation ->
                            assertThat(limitation).startsWith(counted).contains("left out"));
        }
        assertHonest(fixture, truncated.report(), "truncated");
        Map<String, String> whole = replay(fixture, Replay.whole()).observations().stream()
                .collect(Collectors.toMap(RuntimeObservationDto::id, RuntimeObservationDto::status));
        assertThat(truncated.report().observations())
                .filteredOn(row -> "OBSERVED".equals(row.status()))
                .allSatisfy(row -> assertThat(whole.get(row.id()))
                        .as("%s is observed only where the whole fixture observes it: %s", row.id(), row.sentence())
                        .isEqualTo("OBSERVED"));
    }

    private static boolean afterLeftOutRequest(
            RuntimeEvent log, Map<String, TreeMap<Long, RuntimeEvent>> byThread, Set<String> incomplete) {
        TreeMap<Long, RuntimeEvent> requests = byThread.get(log.thread());
        Map.Entry<Long, RuntimeEvent> last = requests == null ? null : requests.floorEntry(log.epochMillis());
        if (last == null || !incomplete.contains(unitOf(last.getValue()))) {
            return false;
        }
        long end = last.getKey() + last.getValue().durationNanos() / 1_000_000;
        return log.epochMillis() <= end + FrameworkWarningsByRoute.AFTER_REQUEST_MILLIS;
    }

    /** A request's HTTP event, or the event that opens a scheduled run, a consumed message, or a WebSocket handler. */
    private static boolean isAnchor(RuntimeEvent event) {
        return (event.source() == JournalSource.HTTP && event.requestId() != null)
                || (event.requestId() == null && event.executionId() != null && InsightsSnapshot.opensExecution(event));
    }

    private static String unitOf(RuntimeEvent event) {
        if (event.requestId() != null) {
            return "request:" + event.requestId();
        }
        return event.executionId() == null ? null : "execution:" + event.executionId();
    }

    /** What a report claims: each check's status and counts, and each row's status and counts. */
    private static List<String> summary(RuntimeInsightsReportDto report) {
        List<String> lines = new ArrayList<>();
        for (RuntimeInsightCheckDto check : report.checks()) {
            lines.add("check " + check.kind() + " " + check.status() + " " + check.eligibleRequests() + " "
                    + check.findings());
        }
        for (RuntimeObservationDto row : report.observations()) {
            lines.add("row " + row.id() + " " + row.status() + " " + row.affected() + "/" + row.eligible() + " "
                    + row.sentence());
        }
        return lines;
    }

    /**
     * A startup error on {@code main}, kept in the journal's reserved share, stays in the No request row however much
     * routine request traffic the ring evicts after it: the loss horizon only leaves out errors on a lost request's
     * thread.
     */
    @TestFactory
    Stream<DynamicNode> aStartupErrorStaysInTheNoRequestRowWhateverIsEvictedAfterIt() {
        Fixture fixture = FIXTURES.stream()
                .filter(candidate -> candidate.name().equals(ObservationFixtures.STARTUP_ERROR))
                .findFirst()
                .orElseThrow();
        return IntStream.range(1, fixture.events().size())
                .mapToObj(evicted ->
                        DynamicTest.dynamicTest("evict " + evicted + " events, 50 % reserved for failures", () -> {
                            Replayed replayed = replayKeepingJournal(fixture, Replay.evicting(evicted, 50));
                            RuntimeInsightsReportDto report = replayed.report();
                            assertThat(report.window().evictedEvents()).isEqualTo(evicted);
                            // The error is the fixture's first event; past the reserved share, it is evicted too.
                            Assumptions.assumeTrue(replayed.retainedSequences().contains(1L));
                            assertThat(rows(report, FrameworkWarningsByRoute.KIND))
                                    .as("the startup error is retained in the reserved share and still listed")
                                    .anySatisfy(row -> {
                                        assertThat(row.subject()).isEqualTo(FrameworkWarningsByRoute.NO_REQUEST);
                                        assertThat(row.status()).isEqualTo("OBSERVED");
                                    });
                        }));
    }

    /** A seeded case replayed on a stack without its capture is not applicable, never an empty success or a finding. */
    @TestFactory
    Stream<DynamicNode> aStackWithoutTheCaptureIsNotApplicable() {
        return positives()
                .map(fixture -> DynamicContainer.dynamicContainer(
                        fixture.toString(),
                        Stream.of(InsightsStack.SPRING_MVC, InsightsStack.SPRING_WEBFLUX, InsightsStack.QUARKUS, null)
                                .map(stack ->
                                        DynamicTest.dynamicTest(stack == null ? "unknown stack" : stack.name(), () -> {
                                            RuntimeInsightsReportDto report = replay(
                                                    new Fixture(
                                                            fixture.kind(),
                                                            fixture.role(),
                                                            fixture.name(),
                                                            stack,
                                                            fixture.alsoFires(),
                                                            fixture.tolerated(),
                                                            fixture.setup(),
                                                            fixture.events()),
                                                    Replay.whole());
                                            RuntimeInsightCheckDto check = check(report, fixture.kind());
                                            if (stack != null
                                                    && NOT_APPLICABLE_ON
                                                            .getOrDefault(fixture.kind(), Set.of())
                                                            .contains(stack)) {
                                                assertThat(check.status()).isEqualTo("NOT_APPLICABLE");
                                                assertThat(check.reason()).isNotBlank();
                                                assertThat(rows(report, fixture.kind()))
                                                        .isEmpty();
                                            } else {
                                                assertThat(check.status()).isNotEqualTo("NOT_APPLICABLE");
                                            }
                                        }))));
    }

    /** Where the application's SQL is not recorded, as over R2DBC, every SQL reader is unavailable and silent. */
    @TestFactory
    Stream<DynamicNode> unrecordedSqlMakesItsReadersUnavailable() {
        return positives()
                .map(fixture -> DynamicTest.dynamicTest(fixture.toString(), () -> {
                    RuntimeInsightsReportDto report = replay(
                            fixture,
                            Replay.whole()
                                    .with(service -> service.setSqlCapture(
                                            () -> SqlCapture.notRecorded(SqlCapture.R2DBC_ONLY))));
                    for (Observation observation : KINDS.values()) {
                        if (observation.reads().contains(JournalSource.SQL)
                                || observation.reads().contains(JournalSource.CONNECTION)) {
                            assertThat(check(report, observation.kind()).status())
                                    .as(observation.kind())
                                    .isIn("UNAVAILABLE", "NOT_APPLICABLE");
                            assertThat(rows(report, observation.kind()))
                                    .as(observation.kind())
                                    .isEmpty();
                        }
                    }
                    assertHonest(fixture, report, "sql not recorded");
                }));
    }

    /** A source the journal does not record, or whose panel is disabled, makes its readers not applicable and silent. */
    @TestFactory
    Stream<DynamicNode> anUnrecordedOrHiddenSourceMakesItsReadersNotApplicable() {
        return positives()
                .filter(fixture -> !KINDS.get(fixture.kind()).reads().isEmpty())
                .map(fixture -> DynamicContainer.dynamicContainer(
                        fixture.toString(),
                        KINDS.get(fixture.kind()).reads().stream().flatMap(source -> {
                            Set<JournalSource> recorded = EnumSet.allOf(JournalSource.class);
                            recorded.remove(source);
                            List<String> panels = JournalSourcePanels.panelsOf(source);
                            Stream<DynamicTest> unrecorded = Stream.of(DynamicTest.dynamicTest(
                                    "without " + source.propertyName(),
                                    () -> assertSilentlyNotApplicable(
                                            fixture,
                                            replay(fixture, Replay.whole().recording(recorded)))));
                            Stream<DynamicTest> hidden = panels.isEmpty()
                                    ? Stream.empty()
                                    : Stream.of(DynamicTest.dynamicTest(
                                            "with " + String.join(", ", panels) + " disabled",
                                            () -> assertSilentlyNotApplicable(
                                                    fixture,
                                                    replay(
                                                            fixture,
                                                            Replay.whole().hiding(panel -> panels.contains(panel))))));
                            return Stream.concat(unrecorded, hidden);
                        })));
    }

    private static Stream<Fixture> positives() {
        return FIXTURES.stream().filter(fixture -> fixture.role() == Role.POSITIVE);
    }

    private static void assertSilentlyNotApplicable(Fixture fixture, RuntimeInsightsReportDto report) {
        RuntimeInsightCheckDto check = check(report, fixture.kind());
        assertThat(check.status()).isEqualTo("NOT_APPLICABLE");
        assertThat(check.reason()).isNotBlank();
        assertThat(rows(report, fixture.kind())).isEmpty();
    }

    /**
     * No kind fires that the fixture does not justify: a counterexample shows no row of its kind but those it tolerates,
     * and no other kind shows a row unless the fixture declares that it fires it.
     */
    private static void assertHonest(Fixture fixture, RuntimeInsightsReportDto report, String replay) {
        for (String kind : KINDS.keySet()) {
            if (kind.equals(fixture.kind())) {
                if (fixture.role() == Role.COUNTEREXAMPLE) {
                    assertThat(rows(report, kind))
                            .as("%s [%s] must not be reported", fixture, replay)
                            .allSatisfy(row -> assertThat(fixture.tolerated().test(row)
                                            || (!replay.equals("whole") && "INSUFFICIENT".equals(row.status())))
                                    .as("%s: %s", row.status(), row.sentence())
                                    .isTrue());
                }
            } else if (!fixture.alsoFires().contains(kind)) {
                assertThat(rows(report, kind))
                        .as("%s [%s] must not show %s", fixture, replay, kind)
                        .allSatisfy(row -> assertThat(row.status())
                                .as(row.sentence())
                                .isNotIn("OBSERVED", "PARTIAL")
                                .satisfies(status -> assertThat(
                                                INSUFFICIENT_ELSEWHERE.contains(kind) || !replay.equals("whole"))
                                        .as("an insufficient row of %s: %s", kind, row.sentence())
                                        .isTrue()));
            }
        }
    }

    private static List<RuntimeObservationDto> rows(RuntimeInsightsReportDto report, String kind) {
        return report.observations().stream()
                .filter(row -> row.kind().equals(kind))
                .toList();
    }

    private static RuntimeInsightCheckDto check(RuntimeInsightsReportDto report, String kind) {
        return report.checks().stream()
                .filter(check -> check.kind().equals(kind))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No check of " + kind));
    }

    // ---- replaying a fixture ------------------------------------------------------------------------------------

    /** How a fixture's events reach the journal, and the service that reads them. */
    record Replay(
            int drop,
            int clearAfter,
            boolean processedBeforeClear,
            boolean primingClear,
            int evict,
            int reservedShare,
            InsightsStack stack,
            Set<JournalSource> sources,
            Predicate<String> hidden,
            Consumer<RuntimeInsightsService> setup) {

        static Replay whole() {
            return new Replay(
                    -1, -1, true, false, 0, 0, null, EnumSet.allOf(JournalSource.class), panel -> false, service -> {});
        }

        static Replay dropping(int index) {
            Replay whole = whole();
            return new Replay(index, -1, true, false, 0, 0, null, whole.sources(), whole.hidden(), whole.setup());
        }

        static Replay clearingAfter(int events, boolean processed) {
            Replay whole = whole();
            return new Replay(-1, events, processed, false, 0, 0, null, whole.sources(), whole.hidden(), whole.setup());
        }

        static Replay evicting(int events, int reservedShare) {
            Replay whole = whole();
            return new Replay(
                    -1, -1, true, false, events, reservedShare, null, whole.sources(), whole.hidden(), whole.setup());
        }

        /** The same replay in a recording cleared once before it, so no first request is known to be cold. */
        Replay afterAClear() {
            return new Replay(
                    drop, clearAfter, processedBeforeClear, true, evict, reservedShare, stack, sources, hidden, setup);
        }

        Replay on(InsightsStack target) {
            return new Replay(
                    drop,
                    clearAfter,
                    processedBeforeClear,
                    primingClear,
                    evict,
                    reservedShare,
                    target,
                    sources,
                    hidden,
                    setup);
        }

        Replay recording(Set<JournalSource> recorded) {
            return new Replay(
                    drop,
                    clearAfter,
                    processedBeforeClear,
                    primingClear,
                    evict,
                    reservedShare,
                    stack,
                    recorded,
                    hidden,
                    setup);
        }

        Replay hiding(Predicate<String> panels) {
            return new Replay(
                    drop,
                    clearAfter,
                    processedBeforeClear,
                    primingClear,
                    evict,
                    reservedShare,
                    stack,
                    sources,
                    panels,
                    setup);
        }

        Replay with(Consumer<RuntimeInsightsService> extra) {
            return new Replay(
                    drop,
                    clearAfter,
                    processedBeforeClear,
                    primingClear,
                    evict,
                    reservedShare,
                    stack,
                    sources,
                    hidden,
                    extra);
        }
    }

    /** A replay's report, and the sequences its journal retained. */
    private record Replayed(RuntimeInsightsReportDto report, Set<Long> retainedSequences) {}

    private static RuntimeInsightsReportDto replay(Fixture fixture, Replay replay) {
        return replayKeepingJournal(fixture, replay).report();
    }

    /**
     * Replays {@code fixture} into a journal without a dispatcher thread, which processes the offered events only when
     * told to, so the replay decides exactly which offer is dropped and which events a clear finds still queued.
     */
    private static Replayed replayKeepingJournal(Fixture fixture, Replay replay) {
        List<RuntimeEvent> events = fixture.events();
        int maxEvents = replay.evict() > 0 ? events.size() - replay.evict() : 10_000;
        RuntimeJournalSettings settings = new RuntimeJournalSettings(
                true, maxEvents, 50_000_000, 10_000, replay.reservedShare(), 0, replay.sources());
        try (RuntimeJournal journal = SynchronousJournals.create(settings, offer -> offer == replay.drop())) {
            if (replay.primingClear()) {
                journal.clear();
            }
            for (int i = 0; i < events.size(); i++) {
                if (i == replay.clearAfter()) {
                    if (replay.processedBeforeClear()) {
                        SynchronousJournals.dispatch(journal);
                    }
                    journal.clear();
                }
                boolean accepted = journal.offer(events.get(i));
                if (replay.sources().contains(events.get(i).source())) {
                    assertThat(accepted).as("offer %s", i).isEqualTo(i != replay.drop());
                }
            }
            SynchronousJournals.dispatch(journal);
            RuntimeInsightsService service = new RuntimeInsightsService(
                    journal,
                    null,
                    panel -> !replay.hidden().test(panel),
                    replay.stack() != null ? replay.stack() : fixture.stack(),
                    List::of);
            fixture.setup().accept(service);
            replay.setup().accept(service);
            return new Replayed(
                    service.report(),
                    journal.entries().stream().map(JournalEntry::sequence).collect(Collectors.toSet()));
        }
    }
}
