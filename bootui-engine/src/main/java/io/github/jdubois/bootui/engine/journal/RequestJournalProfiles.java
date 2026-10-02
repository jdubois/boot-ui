package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.core.dto.RequestGcPauseDto;
import io.github.jdubois.bootui.core.dto.RequestJournalProfileDto;
import io.github.jdubois.bootui.core.dto.RequestOrmDto;
import io.github.jdubois.bootui.core.dto.RequestResourcesDto;
import io.github.jdubois.bootui.core.dto.RequestTimelineItemDto;
import io.github.jdubois.bootui.core.dto.RouteComparisonDto;
import io.github.jdubois.bootui.core.dto.TouchedResourcesDto;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.RouteStats;
import io.github.jdubois.bootui.engine.resources.GcPauseRange;
import io.github.jdubois.bootui.engine.resources.ResourceUsage;
import io.github.jdubois.bootui.engine.sqltrace.RouteLabel;
import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;
import io.github.jdubois.bootui.engine.sqltrace.SqlShapes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * One request as the runtime journal recorded it ({@code docs/PLAN-v2.md} §5.3, §5.11), shared by every adapter: its
 * work on one timeline, the collections that completed while it ran, its measured resources, how it compares with its
 * route, and what it touched.
 *
 * <p>Every child belongs to the request by its request id, and an AI call, which carries none, by the request's trace
 * id when no other retained request shares it. The timeline places each event at its start, which every source stamps
 * as the event's time ({@link RuntimeEvent}). Rows of a disabled panel are left out, as Live Activity leaves them out.
 * </p>
 */
public final class RequestJournalProfiles {

    /** The requests a route needs before the comparison says where one of them stands. */
    public static final int MINIMUM_ROUTE_REQUESTS = 5;

    /** The most collections joined to one request. */
    public static final int MAX_GC_PAUSES = 64;

    /** The most entries each list of touched resources keeps. */
    public static final int MAX_TOUCHED = 50;

    static final String DISABLED = JournalActivityReports.DISABLED;

    private final RuntimeJournal journal;
    private final JournalAggregates aggregates;
    private final JournalActivityFeed feed;
    private final Supplier<RouteTemplateResolver> declaredRoutes;
    private final Predicate<String> panelEnabled;

    /**
     * @param journal the journal, or {@code null} when the adapter created none
     * @param aggregates its aggregates, or {@code null}
     * @param panelEnabled whether a panel, by its id, is enabled; {@code null} enables every panel
     */
    public RequestJournalProfiles(
            RuntimeJournal journal,
            JournalAggregates aggregates,
            long requestSlowThresholdMs,
            int nPlusOneThreshold,
            Predicate<String> panelEnabled) {
        this.journal = journal;
        this.aggregates = aggregates;
        this.declaredRoutes = aggregates == null ? RouteTemplateResolver::empty : aggregates.declaredRoutes();
        this.feed = new JournalActivityFeed(requestSlowThresholdMs, nPlusOneThreshold, declaredRoutes);
        this.panelEnabled = panelEnabled == null ? panel -> true : panelEnabled;
    }

    /** The profile of {@code requestId}, or why the journal cannot give it. */
    public RequestJournalProfileDto profile(String requestId) {
        if (journal == null || !journal.settings().enabled()) {
            return RequestJournalProfileDto.unavailable(requestId, DISABLED);
        }
        if (requestId == null || requestId.isBlank()) {
            return RequestJournalProfileDto.unavailable(requestId, "No request id was given.");
        }
        List<JournalEntry> all = journal.entries();
        JournalEntry request = null;
        List<JournalEntry> children = new ArrayList<>();
        List<JournalEntry> aiCalls = new ArrayList<>();
        Map<String, JournalEntry> collections = new HashMap<>();
        AiCallOwners aiCallOwners = new AiCallOwners(journal::evictedARequestOf);
        for (JournalEntry entry : all) {
            RuntimeEvent event = entry.event();
            aiCallOwners.learn(event);
            if (event.payload() instanceof GcPayload gc) {
                collections.put(gc.collector() + '#' + gc.gcId(), entry);
            } else if (requestId.equals(event.requestId())) {
                if (event.source() == JournalSource.HTTP) {
                    request = entry;
                } else if (visible(event)) {
                    children.add(entry);
                }
            } else if (AiCallOwners.linksByTrace(event) && visible(event)) {
                aiCalls.add(entry);
            }
        }
        // An AI call carries only its span's trace id, so it joins the request with that trace id whose time span
        // contains its start, unless another such request shares the trace and which one made the call is unknown.
        for (JournalEntry call : aiCalls) {
            if (requestId.equals(aiCallOwners.ownerOf(call.event()))) {
                children.add(call);
            }
        }
        if (request == null) {
            return RequestJournalProfileDto.unavailable(
                    requestId,
                    "The runtime journal does not retain request " + requestId
                            + ": it was evicted, it has not completed, or it is not an HTTP request of this run.");
        }
        RuntimeEvent http = request.event();
        HttpPayload payload = (HttpPayload) http.payload();
        long start = http.epochMillis();
        RouteLabel label = RouteLabel.of(
                payload.method(), payload.path(), payload.routeTemplate(), payload.operation(), resolver());
        List<String> notes = new ArrayList<>();

        List<JournalEntry> rendered = new ArrayList<>(children);
        rendered.add(request);
        Map<String, ActivityEntryDto> rows = new HashMap<>();
        for (ActivityEntryDto row : feed.render(
                        rendered, journal::eventId, journal.run().id(), JournalActivityFeed.Filter.NONE, 0)
                .entries()) {
            rows.put(row.id(), row);
        }
        List<Timed> timed = new ArrayList<>();
        for (JournalEntry child : children) {
            RequestTimelineItemDto item = item(child, rows.get(journal.eventId(child)), start);
            if (item != null) {
                timed.add(new Timed(item, child.sequence()));
            }
        }
        for (JournalEntry child : children) {
            for (RequestTimelineItemDto flush : flushes(child.event(), start)) {
                timed.add(new Timed(flush, child.sequence()));
            }
        }
        timed.sort(
                Comparator.comparingLong((Timed t) -> t.item().offsetMillis()).thenComparingLong(Timed::sequence));

        ResourceUsage usage = payload.resources();
        if (usage == null) {
            notes.add("CPU time, memory, and GC pauses are not measured: the runtime journal does not record the"
                    + " resources source (bootui.runtime-journal.sources).");
        } else if (usage.gcPauseRangesTruncated()) {
            notes.add("More collections completed during this request than it keeps, so its GC pause count is a"
                    + " floor.");
        }
        JournalStatus status = journal.status();
        if (status.oldestRetainedEpochMillis() != null
                && status.oldestRetainedEpochMillis() > start
                && status.evictedByCount() + status.evictedByBytes() > 0) {
            notes.add("The journal has evicted events from before this request completed, so some of its work may be"
                    + " missing from the timeline.");
        }

        return new RequestJournalProfileDto(
                true,
                null,
                requestId,
                label.id(),
                start,
                Math.max(0, http.durationNanos()) / 1_000,
                payload.status(),
                resources(usage),
                timed.stream().map(Timed::item).toList(),
                gcPauses(usage, collections, start),
                routeComparison(label.id(), Math.max(0, http.durationNanos()) / 1_000),
                touched(children),
                notes,
                orm(children));
    }

    /**
     * Each flush a Hibernate session kept on its timeline (M4-9), placed from the session's start: auto-flushes that
     * wrote before a query, and full flushes, timed without the statements they executed.
     */
    private static List<RequestTimelineItemDto> flushes(RuntimeEvent event, long requestStart) {
        if (!(event.payload() instanceof OrmPayload orm) || orm.flushTimeline().isEmpty()) {
            return List.of();
        }
        List<RequestTimelineItemDto> items = new ArrayList<>();
        for (OrmPayload.Flush flush : orm.flushTimeline()) {
            items.add(new RequestTimelineItemDto(
                    event.source().propertyName(),
                    flush.auto() ? "Auto-flush before a query" : "Flush",
                    flush.entities() + (flush.entities() == 1 ? " entity" : " entities") + " in context",
                    event.epochMillis() + flush.offsetNanos() / 1_000_000 - requestStart,
                    flush.durationNanos() / 1_000,
                    flush.auto() ? JournalActivityFeed.SEVERITY_WARN : JournalActivityFeed.SEVERITY_OK,
                    event.thread(),
                    event.threadKind() == null ? null : event.threadKind().name()));
        }
        return items;
    }

    /** The request's Hibernate sessions, summed, or {@code null} when it recorded none (M4-9). */
    private static RequestOrmDto orm(List<JournalEntry> children) {
        int sessions = 0;
        int statements = 0;
        long statementNanos = 0;
        int acquisitions = 0;
        int flushes = 0;
        int autoFlushes = 0;
        long flushNanos = 0;
        long autoFlushNanos = 0;
        int dirty = 0;
        int entities = -1;
        int hits = 0;
        int misses = 0;
        int puts = 0;
        for (JournalEntry entry : children) {
            if (entry.event().payload() instanceof OrmPayload orm) {
                sessions++;
                statements += orm.statements();
                statementNanos += Math.max(0, orm.statementNanos());
                acquisitions += orm.connectionAcquisitions();
                flushes += orm.flushes();
                autoFlushes += orm.partialFlushes();
                flushNanos += Math.max(0, orm.flushNanos());
                autoFlushNanos += Math.max(0, orm.partialFlushNanos());
                dirty += orm.dirtyEntities();
                entities = Math.max(entities, orm.entitiesInContext());
                hits += orm.l2Hits();
                misses += orm.l2Misses();
                puts += orm.l2Puts();
            }
        }
        if (sessions == 0) {
            return null;
        }
        return new RequestOrmDto(
                sessions,
                statements,
                statementNanos / 1_000,
                acquisitions,
                flushes,
                autoFlushes,
                flushNanos / 1_000,
                autoFlushNanos / 1_000,
                dirty,
                entities,
                hits,
                misses,
                puts);
    }

    private RequestTimelineItemDto item(JournalEntry entry, ActivityEntryDto row, long requestStart) {
        RuntimeEvent event = entry.event();
        Long durationMicros = event.durationNanos() < 0 ? null : event.durationNanos() / 1_000;
        // Every source stamps when its work started (RuntimeEvent), so the offset needs no per-source correction.
        long startMillis = event.epochMillis();
        String threadKind =
                event.threadKind() == null ? null : event.threadKind().name();
        if (event.payload() instanceof ConnectionPayload connection) {
            return new RequestTimelineItemDto(
                    event.source().propertyName(),
                    "Connection held" + (connection.dataSource() == null ? "" : " from " + connection.dataSource()),
                    "waited " + connection.waitNanos() / 1_000 + " µs, " + connection.statements()
                            + (connection.statements() == 1 ? " statement" : " statements"),
                    startMillis - requestStart,
                    durationMicros,
                    JournalActivityFeed.SEVERITY_OK,
                    event.thread(),
                    threadKind);
        }
        if (row == null) {
            return null;
        }
        return new RequestTimelineItemDto(
                event.source().propertyName(),
                row.summary(),
                row.detail(),
                startMillis - requestStart,
                durationMicros,
                row.severity(),
                event.thread(),
                threadKind);
    }

    private static RequestResourcesDto resources(ResourceUsage usage) {
        if (usage == null) {
            return null;
        }
        return new RequestResourcesDto(
                usage.availability().name(),
                usage.unmeasuredReason() == null
                        ? null
                        : usage.unmeasuredReason().name(),
                usage.cpuNanos(),
                usage.allocatedBytes(),
                usage.segments(),
                usage.unmeasuredSegments(),
                usage.gcPauses(),
                usage.gcPauseRangesTruncated());
    }

    private static List<RequestGcPauseDto> gcPauses(
            ResourceUsage usage, Map<String, JournalEntry> collections, long requestStart) {
        List<RequestGcPauseDto> pauses = new ArrayList<>();
        if (usage == null) {
            return pauses;
        }
        for (GcPauseRange range : usage.gcPauseRanges()) {
            for (long id = range.afterId() + 1; id <= range.lastId() && pauses.size() < MAX_GC_PAUSES; id++) {
                JournalEntry entry = collections.get(range.collector() + '#' + id);
                if (entry == null) {
                    pauses.add(new RequestGcPauseDto(range.collector(), id, false, null, null, null));
                } else {
                    RuntimeEvent event = entry.event();
                    GcPayload gc = (GcPayload) event.payload();
                    pauses.add(new RequestGcPauseDto(
                            range.collector(),
                            id,
                            true,
                            event.epochMillis() - requestStart,
                            Math.max(0, event.durationNanos()) / 1_000_000,
                            gc.cause()));
                }
            }
        }
        return pauses;
    }

    private RouteComparisonDto routeComparison(String route, long durationMicros) {
        if (aggregates == null || route == null) {
            return null;
        }
        for (RouteStats stats : aggregates.snapshot().routes()) {
            if (!stats.route().equals(route)) {
                continue;
            }
            Long p50 = stats.latency().percentileMicros(50);
            Long p95 = stats.latency().percentileMicros(95);
            String standing = null;
            if (stats.requests() >= MINIMUM_ROUTE_REQUESTS && p50 != null && p95 != null) {
                standing = durationMicros > p95 ? "ABOVE_P95" : durationMicros > p50 ? "ABOVE_P50" : "AT_OR_BELOW_P50";
            }
            return new RouteComparisonDto(
                    route, stats.requests(), p50, p95, durationMicros, standing, MINIMUM_ROUTE_REQUESTS);
        }
        return null;
    }

    private static TouchedResourcesDto touched(List<JournalEntry> children) {
        Set<String> tables = new LinkedHashSet<>();
        Set<String> dataSources = new LinkedHashSet<>();
        Set<String> transactions = new LinkedHashSet<>();
        Set<String> caches = new LinkedHashSet<>();
        Set<String> messages = new LinkedHashSet<>();
        Set<String> restCalls = new LinkedHashSet<>();
        Set<String> logTemplates = new LinkedHashSet<>();
        Set<String> models = new LinkedHashSet<>();
        for (JournalEntry entry : children) {
            RuntimeEventPayload payload = entry.event().payload();
            if (payload instanceof SqlPayload sql) {
                for (String table : SqlShapes.tables(sql.sql())) {
                    add(tables, table);
                }
                add(dataSources, sql.dataSource());
            } else if (payload instanceof ConnectionPayload connection) {
                add(dataSources, connection.dataSource());
            } else if (payload instanceof TransactionPayload transaction) {
                add(
                        transactions,
                        transaction.method() == null
                                ? null
                                : transaction.method()
                                        + (transaction.rolledBack() ? " (rolled back)" : " (committed)"));
            } else if (payload instanceof CachePayload cache) {
                add(caches, cache.cacheName() == null ? null : cache.cacheName() + " (" + cache.operation() + ")");
            } else if (payload instanceof MessagingPayload message && message.sent()) {
                add(
                        messages,
                        message.destination() == null ? null : message.destination() + " (" + message.broker() + ")");
            } else if (payload instanceof RestClientPayload rest) {
                add(restCalls, rest.authority());
            } else if (payload instanceof LogPayload log) {
                add(logTemplates, log.template());
            } else if (payload instanceof AiPayload ai) {
                add(
                        models,
                        ai.model() == null || ai.model().isBlank()
                                ? null
                                : ai.model() + (ai.provider() == null ? "" : " (" + ai.provider() + ")"));
            }
        }
        return new TouchedResourcesDto(
                List.copyOf(tables),
                List.copyOf(dataSources),
                List.copyOf(transactions),
                List.copyOf(caches),
                List.copyOf(messages),
                List.copyOf(restCalls),
                List.copyOf(logTemplates),
                List.copyOf(models));
    }

    private static void add(Set<String> values, String value) {
        if (value != null && !value.isBlank() && values.size() < MAX_TOUCHED) {
            values.add(value);
        }
    }

    private boolean visible(RuntimeEvent event) {
        String panel = JournalActivityReports.panelOf(event);
        try {
            return panel == null || panelEnabled.test(panel);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private RouteTemplateResolver resolver() {
        try {
            RouteTemplateResolver resolver = declaredRoutes.get();
            return resolver == null ? RouteTemplateResolver.empty() : resolver;
        } catch (RuntimeException ex) {
            return RouteTemplateResolver.empty();
        }
    }

    private record Timed(RequestTimelineItemDto item, long sequence) {}
}
