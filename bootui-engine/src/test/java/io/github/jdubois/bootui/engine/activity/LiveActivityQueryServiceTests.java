package io.github.jdubois.bootui.engine.activity;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.core.dto.ExplorerSetupDto;
import io.github.jdubois.bootui.core.dto.LiveActivityReport;
import io.github.jdubois.bootui.engine.explorer.ExplorerService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class LiveActivityQueryServiceTests {
    private static final List<String> SOURCES = List.of(
            "HTTP Exchanges",
            "SQL Trace",
            "Exceptions",
            "Security Logs",
            "Cache",
            "Scheduled Tasks",
            "Email",
            "REST Client",
            "Fault Tolerance",
            "Kafka",
            "RabbitMQ",
            "JMS");
    private static final List<String> TYPES = List.of(
            "REQUEST",
            "SQL",
            "EXCEPTION",
            "SECURITY",
            "CACHE",
            "SCHEDULED",
            "MESSAGING",
            "MAIL",
            "REST_CLIENT",
            "FAULT_TOLERANCE",
            "FUTURE");
    private final ActivityPersistenceSettings settings = new ActivityPersistenceSettings(
            false,
            ActivityPersistenceSettings.DataSourceMode.SHARED,
            null,
            null,
            null,
            null,
            "bootui_activity",
            Duration.ofHours(1),
            200,
            Duration.ofDays(1),
            "app",
            Duration.ofSeconds(1));

    @Test
    void sameCanonicalSnapshotAndEveryTypeAreUsedWithoutAnotherSourceAggregation() {
        List<ActivityEntryDto> events = events();
        LiveActivityReport live =
                new LiveActivityReport(true, events, Map.of("REQUEST", 1), null, SOURCES, List.of("source paused"));
        var queries = new LiveActivityQueryService(
                (type, severity, since, limit) -> live,
                new SwitchableActivityStore(new InMemoryActivityStore(100)),
                settings,
                () -> false,
                entry -> true);
        var canonical = queries.report(null, null, 0, 0, null, null, null, 0);
        var explorer =
                new ExplorerService().report(canonical, new ExplorerSetupDto(false, false, "off", 1000, List.of()));
        assertThat(explorer.activity()).isSameAs(canonical);
        assertThat(canonical.entries()).containsExactlyElementsOf(events);
        for (ActivityEntryDto event : events) {
            assertThat(queries.select(event.id()).event()).isSameAs(event);
        }
    }

    @Test
    void historyLookupDoesNotScanPagesAndHonorsCurrentSourcePolicyAndInstancePartition() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:explorer_history;DB_CLOSE_DELAY=-1");
        JdbcActivityStore durable = new JdbcActivityStore(ds, "explorer_history");
        List<ActivityEntryDto> events = events();
        for (int index = 0; index < events.size(); index++) {
            durable.append(new StoredActivityEntry("app", index + 1, events.get(index)));
        }
        durable.append(new StoredActivityEntry(
                "other-instance",
                99,
                new ActivityEntryDto(
                        "event-1",
                        "SQL",
                        999,
                        "ERROR",
                        "another tenant",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                        null,
                        null,
                        false)));
        AtomicReference<Set<String>> disabled = new AtomicReference<>(Set.of());
        try (var store = new SwitchableActivityStore(
                new BufferedActivityStore(new InMemoryActivityStore(2), durable, Duration.ofHours(1), 200))) {
            var queries = new LiveActivityQueryService(
                    (type, severity, since, limit) ->
                            new LiveActivityReport(true, List.of(), Map.of(), null, SOURCES, List.of()),
                    store,
                    settings,
                    () -> true,
                    entry -> ActivitySourcePolicy.permitted(
                            entry, panel -> !disabled.get().contains(panel)));
            var page = queries.report(null, null, 0, 0, null, null, null, 2);
            assertThat(page.entries()).hasSize(2);
            assertThat(page.pageInfo().hasMore()).isTrue();
            assertThat(queries.select("event-1").event()).isEqualTo(events.get(1));
            assertThat(queries.report("SQL", null, 0, 0, "safe", null, null, 2).entries())
                    .containsExactly(events.get(1));
            disabled.set(Set.of("sql-trace"));
            assertThat(queries.select("event-1").event()).isNull();
            assertThat(queries.report("SQL", null, 0, 0, null, null, null, 2).entries())
                    .isEmpty();
            disabled.set(Set.of("activity"));
            assertThat(queries.select("event-0").event()).isNull();
        }
    }

    @Test
    void exactSelectorsAreIdenticalOnInMemoryAndJdbcStoresAndComposeWithPaging() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:explorer_selectors;DB_CLOSE_DELAY=-1");
        for (ActivityStore store :
                List.of(new InMemoryActivityStore(100), new JdbcActivityStore(ds, "explorer_selectors"))) {
            ActivityEntryDto event = new ActivityEntryDto(
                    "old-sql", "SQL", 1234, "OK", "safe", null, 3L, "trace", null, null, null, null, false, "request",
                    null, false);
            store.append(new StoredActivityEntry("app", 1, event));
            ActivityQuery query = ActivityQuery.firstPage("app")
                    .withEventId("old-sql")
                    .withCorrelationId("trace")
                    .withParentId("request")
                    .withPageSize(1)
                    .withCursor(null);
            assertThat(store.query(query).entryDtos()).containsExactly(event);
            assertThat(store.query(query.withParentId("wrong")).entries()).isEmpty();
            assertThat(store.query(query.withCorrelationId("wrong")).entries()).isEmpty();
            assertThat(store.query(query.withEventId("wrong")).entries()).isEmpty();
        }
    }

    @Test
    void persistentModeAndExactTimestampNeverSubstituteAReusedLiveSourceId() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:explorer_versions;DB_CLOSE_DELAY=-1");
        JdbcActivityStore durable = new JdbcActivityStore(ds, "explorer_versions");
        var old = version(1000, "old-trace");
        var current = version(2000, "new-trace");
        durable.append(new StoredActivityEntry("app", 1, old));
        try (var store = new SwitchableActivityStore(
                new BufferedActivityStore(new InMemoryActivityStore(10), durable, Duration.ofHours(1), 200))) {
            var queries = new LiveActivityQueryService(
                    (type, severity, since, limit) ->
                            new LiveActivityReport(true, List.of(current), Map.of(), null, SOURCES, List.of()),
                    store,
                    settings,
                    () -> true,
                    entry -> true);
            assertThat(queries.select("sql-1").event()).isEqualTo(old);
            assertThat(queries.select("sql-1", 1000L).event()).isEqualTo(old);
            assertThat(queries.select("sql-1", 2000L).event()).isNull();
            durable.append(new StoredActivityEntry("app", 2, current));
            assertThat(queries.select("sql-1").event()).isEqualTo(current);
            assertThat(queries.select("sql-1", 1000L).event()).isEqualTo(old);
            assertThat(queries.select("sql-1", 1000L).entries()).containsExactly(old);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void historicalParentAndSiblingReadsNeverMixRestartedScheduledRunVersions(boolean persistNewRun) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:explorer_parent_versions_" + persistNewRun + ";DB_CLOSE_DELAY=-1");
        JdbcActivityStore durable = new JdbcActivityStore(ds, "explorer_parent_versions");
        ActivityEntryDto oldRun = family("sched-1", "SCHEDULED", 1000, null, 10L);
        ActivityEntryDto oldException = family("exc-1", "EXCEPTION", 1005, "sched-1", null);
        ActivityEntryDto oldSibling = family("exc-2", "EXCEPTION", 1006, "sched-1", null);
        ActivityEntryDto newRun = family("sched-1", "SCHEDULED", 100_000, null, 10L);
        ActivityEntryDto newException = family("exc-1", "EXCEPTION", 100_005, "sched-1", null);
        durable.appendBatch(List.of(
                new StoredActivityEntry("app", 1, oldRun),
                new StoredActivityEntry("app", 2, oldException),
                new StoredActivityEntry("app", 3, oldSibling)));
        if (persistNewRun) {
            durable.appendBatch(List.of(
                    new StoredActivityEntry("app", 4, newRun), new StoredActivityEntry("app", 5, newException)));
        }
        try (var store = new SwitchableActivityStore(
                new BufferedActivityStore(new InMemoryActivityStore(10), durable, Duration.ofHours(1), 200))) {
            var queries = new LiveActivityQueryService(
                    (type, severity, since, limit) -> new LiveActivityReport(
                            true, List.of(newRun, newException), Map.of(), null, SOURCES, List.of()),
                    store,
                    settings,
                    () -> true,
                    entry -> true);
            var selectedParent = queries.select("sched-1", 1000L);
            assertThat(selectedParent.event()).isEqualTo(oldRun);
            assertThat(selectedParent.entries()).containsExactly(oldRun, oldException, oldSibling);
            assertThat(selectedParent.partial()).isTrue();
            var selectedChild = queries.select("exc-1", 1005L);
            assertThat(selectedChild.event()).isEqualTo(oldException);
            assertThat(selectedChild.entries()).containsExactly(oldRun, oldException, oldSibling);
            if (persistNewRun) {
                assertThat(queries.select("exc-1", 100_005L).entries()).containsExactly(newRun, newException);
            }
        }
    }

    @Test
    void independentlyEvictedParentCannotMakeHistoricalChildrenAttachToALiveRestartedRun() {
        ActivityEntryDto oldException = family("exc-old", "EXCEPTION", 1005, "sched-1", null);
        ActivityEntryDto newRun = family("sched-1", "SCHEDULED", 100_000, null, 10L);
        ActivityEntryDto newException = family("exc-new", "EXCEPTION", 100_005, "sched-1", null);
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:explorer_missing_parent;DB_CLOSE_DELAY=-1");
        JdbcActivityStore durable = new JdbcActivityStore(ds, "explorer_missing_parent");
        durable.append(new StoredActivityEntry("app", 1, oldException));
        try (var store = new SwitchableActivityStore(
                new BufferedActivityStore(new InMemoryActivityStore(10), durable, Duration.ofHours(1), 200))) {
            var queries = new LiveActivityQueryService(
                    (type, severity, since, limit) -> new LiveActivityReport(
                            true, List.of(newRun, newException), Map.of(), null, SOURCES, List.of()),
                    store,
                    settings,
                    () -> true,
                    entry -> true);
            var selection = queries.select("exc-old", 1005L);
            assertThat(selection.entries()).containsExactly(oldException);
            assertThat(selection.partial()).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void overlappingOrUnknownParentVersionsRemainAmbiguousInsteadOfChoosingTheNewestOne(boolean unknownDuration) {
        ActivityEntryDto run = family("sched-1", "SCHEDULED", 1000, null, unknownDuration ? null : 100L);
        ActivityEntryDto reused = family("sched-1", "SCHEDULED", 1001, null, 100L);
        ActivityEntryDto child = family("exc-1", "EXCEPTION", 1010, "sched-1", null);
        var queries = new LiveActivityQueryService(
                (type, severity, since, limit) ->
                        new LiveActivityReport(true, List.of(run, reused, child), Map.of(), null, SOURCES, List.of()),
                new SwitchableActivityStore(new InMemoryActivityStore(10)),
                settings,
                () -> false,
                entry -> true);
        assertThat(queries.select("sched-1", 1000L).entries()).containsExactly(run);
        assertThat(queries.select("exc-1", 1010L).entries()).containsExactly(child);
        assertThat(queries.select("exc-1", 1010L).partial()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
        "request, REQUEST, HTTP Exchanges",
        "sql-1, SQL, SQL Trace",
        "exc-1, EXCEPTION, Exceptions",
        "security-1, SECURITY, Security Logs",
        "cache-1, CACHE, Cache",
        "sched-1, SCHEDULED, Scheduled Tasks",
        "mail-1, MAIL, Email",
        "rest-1, REST_CLIENT, REST Client",
        "fault-1, FAULT_TOLERANCE, Fault Tolerance",
        "kafka-1, MESSAGING, Kafka",
        "rabbit-1, MESSAGING, RabbitMQ",
        "jms-1, MESSAGING, JMS"
    })
    void unavailableLiveSourcesCannotBeReadThroughHistoryEvenWhenTheirPanelIsEnabled(
            String id, String type, String source) {
        ActivityEntryDto historical = family(id, type, 1000, null, 1L);
        InMemoryActivityStore durable = new InMemoryActivityStore(10);
        durable.append(new StoredActivityEntry("app", 1, historical));
        AtomicReference<List<String>> sources = new AtomicReference<>(List.of(source));
        try (var store = new SwitchableActivityStore(
                new BufferedActivityStore(new InMemoryActivityStore(10), durable, Duration.ofHours(1), 200))) {
            var queries = new LiveActivityQueryService(
                    (eventType, severity, since, limit) ->
                            new LiveActivityReport(true, List.of(), Map.of(type, 0), null, sources.get(), List.of()),
                    store,
                    settings,
                    () -> true,
                    entry -> true);
            assertThat(queries.select(id, 1000L).event()).isEqualTo(historical);
            sources.set(List.of("HTTP Exchanges".equals(source) ? "SQL Trace" : "HTTP Exchanges"));
            assertThat(queries.select(id, 1000L).event()).isNull();
            var report = queries.report(null, null, 0, 0, null, null, null, 200);
            assertThat(report.entries()).isEmpty();
            assertThat(report.typeCounts()).containsExactlyEntriesOf(Map.of(type, 0));
            assertThat(report.sources()).containsExactlyElementsOf(sources.get());
        }
    }

    private static ActivityEntryDto family(String id, String type, long timestamp, String parent, Long duration) {
        return new ActivityEntryDto(
                id,
                type,
                timestamp,
                "ERROR",
                "safe",
                null,
                duration,
                null,
                null,
                null,
                null,
                "scheduler-1",
                false,
                parent,
                null,
                false);
    }

    private static ActivityEntryDto version(long timestamp, String trace) {
        return new ActivityEntryDto(
                "sql-1", "SQL", timestamp, "OK", "safe", null, 1L, trace, null, null, null, null, false, null, null,
                false);
    }

    private static List<ActivityEntryDto> events() {
        List<ActivityEntryDto> result = new ArrayList<>();
        for (int i = 0; i < TYPES.size(); i++) {
            result.add(new ActivityEntryDto(
                    "event-" + i,
                    TYPES.get(i),
                    i + 1,
                    i % 2 == 0 ? "OK" : "WARN",
                    "safe " + TYPES.get(i),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    false,
                    null,
                    null,
                    false));
        }
        return result;
    }
}
