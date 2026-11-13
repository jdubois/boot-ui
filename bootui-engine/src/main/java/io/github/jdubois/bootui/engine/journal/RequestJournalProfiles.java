package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.core.dto.RequestBaselineCandidateDto;
import io.github.jdubois.bootui.core.dto.RequestBaselineDiffDto;
import io.github.jdubois.bootui.core.dto.RequestGcPauseDto;
import io.github.jdubois.bootui.core.dto.RequestHandoffDto;
import io.github.jdubois.bootui.core.dto.RequestJournalProfileDto;
import io.github.jdubois.bootui.core.dto.RequestOrmDto;
import io.github.jdubois.bootui.core.dto.RequestResourcesDto;
import io.github.jdubois.bootui.core.dto.RequestTimelineItemDto;
import io.github.jdubois.bootui.core.dto.RouteComparisonDto;
import io.github.jdubois.bootui.core.dto.TouchedResourcesDto;
import io.github.jdubois.bootui.engine.correlation.ExecutionIds;
import io.github.jdubois.bootui.engine.correlation.HandoffWindow;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.RouteStats;
import io.github.jdubois.bootui.engine.resources.GcPauseRange;
import io.github.jdubois.bootui.engine.resources.ResourceUsage;
import io.github.jdubois.bootui.engine.sqltrace.RouteLabel;
import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;
import io.github.jdubois.bootui.engine.sqltrace.SqlShapes;
import io.github.jdubois.bootui.engine.support.Percentiles;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
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

    /** The most distinguishing SQL shapes a baseline comparison lists. */
    public static final int MAX_DISTINGUISHING_SQL_SHAPES = 5;

    /** The most retained baseline candidates a comparison lists for interactive selection. */
    public static final int MAX_BASELINE_CANDIDATES = 20;

    static final String DISABLED = JournalActivityReports.DISABLED;

    /** Why the journal has no profile of {@code requestId}: it records no such request or execution. */
    static String notRetainedReason(String requestId) {
        return "The runtime journal does not retain a visible completed request or execution " + requestId
                + " (it may have been evicted or its source panel is disabled).";
    }

    /**
     * Whether {@code profile} says the enabled journal records no request or execution {@code requestId}, as opposed
     * to the journal being off or the request's panel hiding it.
     */
    public static boolean notRetained(RequestJournalProfileDto profile, String requestId) {
        return profile != null
                && !profile.available()
                && notRetainedReason(requestId).equals(profile.unavailableReason());
    }

    private final RuntimeJournal journal;
    private final JournalAggregates aggregates;
    private final long requestSlowThresholdMs;
    private final JournalActivityFeed feed;
    private final Supplier<RouteTemplateResolver> declaredRoutes;
    private final Predicate<String> panelEnabled;
    private volatile long maxHandoffMillis = DEFAULT_MAX_HANDOFF_MILLIS;

    /** The default {@code bootui.agent.executors.max-handoff}. */
    public static final long DEFAULT_MAX_HANDOFF_MILLIS = HandoffWindow.DEFAULT_MAX_HANDOFF_MILLIS;

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
        this(journal, aggregates, requestSlowThresholdMs, nPlusOneThreshold, panelEnabled, null);
    }

    /**
     * Profiles whose timeline rows and touched log templates show text as the live {@code exposure} policy allows
     * when each profile is read ({@code PLAN-v2} §8).
     *
     * @param exposure the live exposure policy; {@code null} renders as {@link JournalTextExposure#masked()}
     */
    public RequestJournalProfiles(
            RuntimeJournal journal,
            JournalAggregates aggregates,
            long requestSlowThresholdMs,
            int nPlusOneThreshold,
            Predicate<String> panelEnabled,
            ExposurePolicy exposure) {
        this.journal = journal;
        this.aggregates = aggregates;
        this.requestSlowThresholdMs = requestSlowThresholdMs;
        this.declaredRoutes = aggregates == null ? RouteTemplateResolver::empty : aggregates.declaredRoutes();
        this.feed = new JournalActivityFeed(requestSlowThresholdMs, nPlusOneThreshold, declaredRoutes, exposure);
        this.panelEnabled = panelEnabled == null ? panel -> true : panelEnabled;
    }

    /**
     * Sets {@code bootui.agent.executors.max-handoff} ({@link HandoffWindow}): a handoff starting later than this after
     * its request ended is only counted, and work a handoff recorded later than this after it started is left out.
     */
    public RequestJournalProfiles maxHandoff(java.time.Duration maxHandoff) {
        this.maxHandoffMillis = HandoffWindow.millis(maxHandoff);
        return this;
    }

    /** The profile of {@code requestId}, or why the journal cannot give it. */
    public RequestJournalProfileDto profile(String requestId) {
        return profile(requestId, null);
    }

    /**
     * The profile of {@code requestId}, optionally comparing it against {@code compareWith} when that id names another
     * retained, complete HTTP request on the same route.
     */
    public RequestJournalProfileDto profile(String requestId, String compareWith) {
        if (journal == null || !journal.settings().enabled()) {
            return RequestJournalProfileDto.unavailable(requestId, DISABLED);
        }
        if (requestId == null || requestId.isBlank()) {
            return RequestJournalProfileDto.unavailable(requestId, "No request id was given.");
        }
        List<JournalEntry> all = journal.entries();
        JournalEntry request = null;
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
                }
            } else if (event.requestId() == null && requestId.equals(event.executionId())) {
                if (event.payload() instanceof ScheduledPayload
                        || event.payload() instanceof MessagingPayload message && !message.sent()
                        || event.payload() instanceof WebSocketPayload webSocket && webSocket.opensExecution()) {
                    request = entry;
                }
            } else if (AiCallOwners.linksByTrace(event) && visible(event)) {
                aiCalls.add(entry);
            }
        }
        if (request == null) {
            return RequestJournalProfileDto.unavailable(requestId, notRetainedReason(requestId));
        }
        // The opening event names the unit's route, status, timing, and resources: the evidence of the panel owning
        // it, which every other surface withholds while that panel is off (docs/PLAN-v2.md §8).
        if (!visible(request.event())) {
            return RequestJournalProfileDto.unavailable(
                    requestId,
                    "The " + JournalActivityReports.panelOf(request.event()) + " panel is disabled or unavailable, so "
                            + requestId + " is not shown.");
        }
        RuntimeEvent http = request.event();
        HttpPayload payload = http.payload() instanceof HttpPayload found ? found : null;
        long start = http.epochMillis();
        SelectedChildren selectedChildren = selectChildren(requestId, http, all, aiCalls, aiCallOwners);
        List<JournalEntry> children = selectedChildren.children();
        int late = selectedChildren.lateHandoffs();
        RouteTemplateResolver routeResolver = resolver();
        RouteLabel label = payload == null
                ? null
                : RouteLabel.of(
                        payload.method(), payload.path(), payload.routeTemplate(), payload.operation(), routeResolver);
        List<String> notes = new ArrayList<>();
        if (children.stream()
                .anyMatch(entry -> entry.event().payload() instanceof AsyncHandoffPayload handoff
                        && java.util.Objects.equals(handoff.executionId(), handoff.parentExecutionId()))) {
            notes.add("These handoffs share their parent execution id; their SQL, REST client, and messaging counts"
                    + " cannot be attributed to individual tasks.");
        }
        if (children.stream().anyMatch(entry -> entry.event().payload() instanceof SqlPayload sql && !sql.executed())) {
            notes.add("Unverified SQL captures do not establish execution, database time, or touched tables.");
        }
        List<RequestHandoffDto> handoffs =
                handoffs(children, start, Math.max(0, http.durationNanos()), payload != null);

        List<JournalEntry> rendered = new ArrayList<>(children);
        rendered.add(request);
        Map<String, ActivityEntryDto> rows = new HashMap<>();
        JournalTextExposure text = feed.exposure();
        for (ActivityEntryDto row : feed.render(
                        rendered,
                        journal::eventId,
                        journal.run().id(),
                        JournalActivityFeed.Filter.NONE,
                        0,
                        JournalRowDetails.NONE,
                        traceId -> false,
                        List.of(),
                        text)
                .entries()) {
            rows.put(row.id(), row);
        }
        List<Timed> timed = new ArrayList<>();
        for (JournalEntry child : children) {
            RequestTimelineItemDto item = item(child, rows.get(journal.eventId(child)), start);
            if (item != null) {
                timed.add(new Timed(item, child.sequence(), startNanos(child.event())));
            }
        }
        for (JournalEntry child : children) {
            for (RequestTimelineItemDto flush : flushes(child.event(), start)) {
                timed.add(new Timed(flush, child.sequence(), startNanos(child.event())));
            }
        }
        timed.sort((left, right) -> {
            int byOffset = Long.compare(left.item().offsetMillis(), right.item().offsetMillis());
            if (byOffset != 0) {
                return byOffset;
            }
            if (left.startNanos() != null && right.startNanos() != null) {
                int byStart = Long.compare(left.startNanos(), right.startNanos());
                if (byStart != 0) {
                    return byStart;
                }
            }
            return Long.compare(left.sequence(), right.sequence());
        });

        ResourceUsage usage = payload == null ? null : payload.resources();
        if (payload == null) {
            notes.add("CPU, allocation, and GC pauses are not measured for this execution.");
        } else if (usage == null) {
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
        long durationMicros = Math.max(0, http.durationNanos()) / 1_000;
        RouteComparisonDto routeComparison = label == null ? null : routeComparison(label.id(), durationMicros);
        RequestOrmDto targetOrm = orm(children);
        RequestBaselineDiffDto baselineComparison = label == null
                ? null
                : baselineComparison(
                        requestId,
                        http,
                        payload,
                        label.id(),
                        children,
                        targetOrm,
                        routeComparison,
                        status,
                        all,
                        aiCalls,
                        aiCallOwners,
                        routeResolver,
                        compareWith,
                        notes);

        return new RequestJournalProfileDto(
                true,
                null,
                requestId,
                label == null ? executionLabel(http) : label.id(),
                start,
                durationMicros,
                payload == null ? null : payload.status(),
                resources(usage),
                timed.stream().map(Timed::item).toList(),
                gcPauses(usage, collections, start),
                routeComparison,
                touched(children, text),
                notes,
                targetOrm,
                handoffs,
                late == 0 ? null : late,
                baselineComparison);
    }

    private SelectedChildren selectChildren(
            String unitId,
            RuntimeEvent anchor,
            List<JournalEntry> all,
            List<JournalEntry> aiCalls,
            AiCallOwners aiCallOwners) {
        List<JournalEntry> children = new ArrayList<>();
        Set<Long> selected = new LinkedHashSet<>();
        Set<String> executionHandoffs = new LinkedHashSet<>();
        for (JournalEntry entry : all) {
            RuntimeEvent event = entry.event();
            if (event.requestId() == null
                    && event.payload() instanceof AsyncHandoffPayload handoff
                    && unitId.equals(handoff.parentExecutionId())
                    && visible(event)) {
                executionHandoffs.add(event.executionId());
                if (selected.add(entry.sequence())) {
                    children.add(entry);
                }
            }
            if (event.payload() instanceof GcPayload) {
                continue;
            }
            if (unitId.equals(event.requestId())) {
                if (event.source() != JournalSource.HTTP && visible(event) && selected.add(entry.sequence())) {
                    children.add(entry);
                }
            } else if (event.requestId() == null && unitId.equals(event.executionId())) {
                if (!(event.payload() instanceof ScheduledPayload)
                        && !(event.payload() instanceof MessagingPayload message && !message.sent())
                        && !(event.payload() instanceof WebSocketPayload webSocket && webSocket.opensExecution())
                        && visible(event)
                        && selected.add(entry.sequence())) {
                    children.add(entry);
                }
            }
        }
        for (JournalEntry entry : all) {
            RuntimeEvent event = entry.event();
            if (event.requestId() == null
                    && executionHandoffs.contains(event.executionId())
                    && !unitId.equals(event.executionId())
                    && !(event.payload() instanceof AsyncHandoffPayload)
                    && visible(event)
                    && selected.add(entry.sequence())) {
                children.add(entry);
            }
        }
        // An AI call carries only its span's trace id, so it joins the request with that trace id whose time span
        // contains its start, unless another such request shares the trace and which one made the call is unknown.
        for (JournalEntry call : aiCalls) {
            if (unitId.equals(aiCallOwners.ownerOf(call.event())) && selected.add(call.sequence())) {
                children.add(call);
            }
        }
        long start = anchor.epochMillis();
        long endMillis = start + Math.max(0, anchor.durationNanos()) / 1_000_000L;
        long maxHandoff = maxHandoffMillis;
        // A handoff starting past max-handoff after the request ended is only counted, and its work, like any work a
        // handoff recorded past max-handoff after it started, belongs to no request (M5-2): neither is drawn.
        HandoffWindow.Starts starts = new HandoffWindow.Starts();
        for (JournalEntry child : children) {
            RuntimeEvent event = child.event();
            if (event.payload() instanceof AsyncHandoffPayload) {
                starts.handoff(event.executionId(), event.epochMillis());
            } else {
                starts.recorded(event.executionId(), event.epochMillis());
            }
        }
        int late = 0;
        for (Iterator<JournalEntry> it = children.iterator(); it.hasNext(); ) {
            RuntimeEvent event = it.next().event();
            if (!ExecutionIds.isAsync(event.executionId())) {
                continue;
            }
            if (event.payload() instanceof AsyncHandoffPayload) {
                if (!HandoffWindow.attaches(endMillis, event.epochMillis(), maxHandoff)) {
                    late++;
                    it.remove();
                }
            } else if (!starts.attributed(event.executionId(), event.epochMillis(), endMillis, maxHandoff)) {
                it.remove();
            }
        }
        return new SelectedChildren(children, late);
    }

    private RequestBaselineDiffDto baselineComparison(
            String targetRequestId,
            RuntimeEvent targetHttp,
            HttpPayload targetPayload,
            String route,
            List<JournalEntry> targetChildren,
            RequestOrmDto targetOrm,
            RouteComparisonDto routeComparison,
            JournalStatus status,
            List<JournalEntry> all,
            List<JournalEntry> aiCalls,
            AiCallOwners aiCallOwners,
            RouteTemplateResolver routeResolver,
            String compareWith,
            List<String> notes) {
        if (targetPayload == null || route == null) {
            return null;
        }
        boolean evictionsOccurred = status.evictedByCount() + status.evictedByBytes() > 0;
        boolean explicitSelection = compareWith != null && !compareWith.isBlank();
        if (isPartiallyEvicted(targetHttp, status, evictionsOccurred)) {
            if (explicitSelection) {
                notes.add("Baseline comparison is unavailable because this request started before the journal's loss"
                        + " horizon, so some of its work may be missing.");
            }
            return null;
        }
        List<JournalEntry> routeEntries = new ArrayList<>();
        for (JournalEntry entry : all) {
            RuntimeEvent event = entry.event();
            if (event.source() == JournalSource.HTTP
                    && event.requestId() != null
                    && event.payload() instanceof HttpPayload candidatePayload) {
                RouteLabel candidateLabel = RouteLabel.of(
                        candidatePayload.method(),
                        candidatePayload.path(),
                        candidatePayload.routeTemplate(),
                        candidatePayload.operation(),
                        routeResolver);
                if (route.equals(candidateLabel.id())) {
                    routeEntries.add(entry);
                }
            }
        }
        routeEntries.sort(
                Comparator.comparingLong((JournalEntry entry) -> entry.event().epochMillis())
                        .thenComparingLong(JournalEntry::sequence));
        String coldRequestId = aggregates != null ? aggregates.coldRequestId(route) : null;
        if (coldRequestId == null && !evictionsOccurred && status.clears() == 0 && !routeEntries.isEmpty()) {
            coldRequestId = routeEntries.get(0).event().requestId();
        }
        List<Long> warm2xxDurations = new ArrayList<>();
        List<JournalEntry> warm2xxCandidates = new ArrayList<>();
        for (JournalEntry entry : routeEntries) {
            RuntimeEvent event = entry.event();
            if (coldRequestId != null && coldRequestId.equals(event.requestId())) {
                continue;
            }
            HttpPayload payload = (HttpPayload) event.payload();
            if (payload.status() < 200 || payload.status() >= 300) {
                continue;
            }
            if (isPartiallyEvicted(event, status, evictionsOccurred)) {
                continue;
            }
            warm2xxDurations.add(Math.max(0, event.durationNanos()) / 1_000L);
            if (!targetRequestId.equals(event.requestId())) {
                warm2xxCandidates.add(entry);
            }
        }
        Long routeP50Micros = routeComparison != null ? routeComparison.p50Micros() : null;
        Long warmMedianMicros = Percentiles.of(warm2xxDurations, 50);
        Long referenceMicros = routeComparison != null && routeComparison.standing() != null && routeP50Micros != null
                ? routeP50Micros
                : warmMedianMicros;
        if (referenceMicros != null) {
            long ref = referenceMicros;
            warm2xxCandidates.sort(Comparator.comparingLong((JournalEntry entry) ->
                            Math.abs(Math.max(0, entry.event().durationNanos()) / 1_000L - ref))
                    .thenComparing(
                            Comparator.comparingLong(JournalEntry::sequence).reversed()));
        } else {
            warm2xxCandidates.sort(
                    Comparator.comparingLong(JournalEntry::sequence).reversed());
        }

        JournalEntry baselineEntry = null;
        if (explicitSelection) {
            String requestedId = compareWith.trim();
            String safeRequestedId = safeRequestId(requestedId);
            if (requestedId.equals(targetRequestId)) {
                notes.add("Baseline comparison is unavailable: a request cannot be compared with itself.");
            } else {
                JournalEntry match = routeEntries.stream()
                        .filter(entry -> requestedId.equals(entry.event().requestId()))
                        .findFirst()
                        .orElse(null);
                if (match == null) {
                    notes.add("Baseline comparison request " + safeRequestedId + " is not a retained request on route "
                            + route + ".");
                } else if (isPartiallyEvicted(match.event(), status, evictionsOccurred)) {
                    notes.add("Baseline comparison request " + safeRequestedId
                            + " started before the journal's oldest retained event, so its work may be incomplete.");
                } else {
                    baselineEntry = match;
                }
            }
        } else {
            long targetDurationMicros = Math.max(0, targetHttp.durationNanos()) / 1_000L;
            boolean slowStanding = routeComparison != null
                    && ("ABOVE_P50".equals(routeComparison.standing())
                            || "ABOVE_P95".equals(routeComparison.standing()));
            boolean slowWithoutAggregates =
                    routeComparison == null && referenceMicros != null && targetDurationMicros > referenceMicros;
            boolean slowByThreshold =
                    requestSlowThresholdMs > 0 && targetDurationMicros >= requestSlowThresholdMs * 1_000L;
            boolean failedOrSlow = targetPayload.status() >= 400
                    || targetHttp.failedOrSlow()
                    || slowStanding
                    || slowWithoutAggregates
                    || slowByThreshold;
            if (failedOrSlow && !warm2xxCandidates.isEmpty()) {
                baselineEntry = warm2xxCandidates.get(0);
            }
        }
        if (baselineEntry == null) {
            return null;
        }

        List<RequestBaselineCandidateDto> candidateDtos = new ArrayList<>();
        Set<String> seenCandidateIds = new LinkedHashSet<>();
        for (JournalEntry candidate : warm2xxCandidates) {
            if (candidateDtos.size() >= MAX_BASELINE_CANDIDATES) {
                break;
            }
            RuntimeEvent event = candidate.event();
            HttpPayload payload = (HttpPayload) event.payload();
            if (seenCandidateIds.add(event.requestId())) {
                candidateDtos.add(new RequestBaselineCandidateDto(
                        event.requestId(),
                        event.epochMillis(),
                        Math.max(0, event.durationNanos()) / 1_000L,
                        payload.status()));
            }
        }
        String baselineId = baselineEntry.event().requestId();
        if (!seenCandidateIds.contains(baselineId)) {
            RuntimeEvent event = baselineEntry.event();
            HttpPayload payload = (HttpPayload) event.payload();
            candidateDtos.add(
                    0,
                    new RequestBaselineCandidateDto(
                            baselineId,
                            event.epochMillis(),
                            Math.max(0, event.durationNanos()) / 1_000L,
                            payload.status()));
            if (candidateDtos.size() > MAX_BASELINE_CANDIDATES) {
                candidateDtos.remove(candidateDtos.size() - 1);
            }
        }

        RuntimeEvent baselineHttp = baselineEntry.event();
        HttpPayload baselinePayload = (HttpPayload) baselineHttp.payload();
        List<JournalEntry> baselineChildren = selectChildren(baselineId, baselineHttp, all, aiCalls, aiCallOwners)
                .children();
        RequestOrmDto baselineOrm = orm(baselineChildren);

        long targetDurationMicros = Math.max(0, targetHttp.durationNanos()) / 1_000L;
        long baselineDurationMicros = Math.max(0, baselineHttp.durationNanos()) / 1_000L;
        long durationDeltaMicros = targetDurationMicros - baselineDurationMicros;

        RequestMetrics targetMetrics = RequestMetrics.of(targetChildren);
        RequestMetrics baselineMetrics = RequestMetrics.of(baselineChildren);

        SqlCaptureScopes.Snapshot sqlScope = journal.sqlCaptureScope();
        boolean verifiedSqlCoverage =
                !sqlScope.executions().isEmpty() && sqlScope.preparations().isEmpty() && !sqlScope.incomplete();
        boolean sqlVerified = sourceAvailable(JournalSource.SQL)
                && !targetMetrics.hasUnverifiedSql
                && !baselineMetrics.hasUnverifiedSql
                && (targetMetrics.sqlExecutions > 0 || baselineMetrics.sqlExecutions > 0 || verifiedSqlCoverage);
        Integer sqlExecutionsDelta = sqlVerified ? targetMetrics.sqlExecutions - baselineMetrics.sqlExecutions : null;
        Long sqlDurationMicrosDelta =
                sqlVerified ? targetMetrics.sqlDurationMicros - baselineMetrics.sqlDurationMicros : null;
        List<String> distinctSqlShapes =
                sqlVerified ? distinguishingSqlShapes(targetMetrics, baselineMetrics) : List.of();

        boolean connectionAvailable = sourceAvailable(JournalSource.CONNECTION)
                && (targetMetrics.hasConnection || baselineMetrics.hasConnection);
        Long connectionWaitMicrosDelta =
                connectionAvailable ? targetMetrics.connectionWaitMicros - baselineMetrics.connectionWaitMicros : null;

        boolean cacheAvailable =
                sourceAvailable(JournalSource.CACHE) && (targetMetrics.hasCache || baselineMetrics.hasCache);
        Integer cacheHitsDelta = cacheAvailable ? targetMetrics.cacheHits - baselineMetrics.cacheHits : null;
        Integer cacheMissesDelta = cacheAvailable ? targetMetrics.cacheMisses - baselineMetrics.cacheMisses : null;

        boolean restAvailable = sourceAvailable(JournalSource.REST_CLIENT)
                && (targetMetrics.restClientCalls > 0 || baselineMetrics.restClientCalls > 0);
        Integer restClientCallsDelta =
                restAvailable ? targetMetrics.restClientCalls - baselineMetrics.restClientCalls : null;
        Long restClientDurationMicrosDelta = restAvailable
                ? targetMetrics.restClientDurationMicros - baselineMetrics.restClientDurationMicros
                : null;

        boolean ormAvailable = sourceAvailable(JournalSource.ORM) && (targetOrm != null || baselineOrm != null);
        Integer ormFlushesDelta = ormAvailable
                ? (targetOrm == null ? 0 : targetOrm.flushes()) - (baselineOrm == null ? 0 : baselineOrm.flushes())
                : null;
        Integer ormAutoFlushesDelta = ormAvailable
                ? (targetOrm == null ? 0 : targetOrm.autoFlushes())
                        - (baselineOrm == null ? 0 : baselineOrm.autoFlushes())
                : null;
        Integer ormMaxEntitiesDelta = ormAvailable
                        && (targetOrm == null || targetOrm.entitiesInContext() >= 0)
                        && (baselineOrm == null || baselineOrm.entitiesInContext() >= 0)
                ? (targetOrm == null ? 0 : targetOrm.entitiesInContext())
                        - (baselineOrm == null ? 0 : baselineOrm.entitiesInContext())
                : null;

        ResourceUsage targetUsage = targetPayload.resources();
        ResourceUsage baselineUsage = baselinePayload.resources();
        boolean gcAvailable = journal.records(JournalSource.RESOURCES) && targetUsage != null && baselineUsage != null;
        Long gcPausesTarget = gcAvailable ? targetUsage.gcPauses() : null;
        Long gcPausesBaseline = gcAvailable ? baselineUsage.gcPauses() : null;

        return new RequestBaselineDiffDto(
                baselineId,
                baselineDurationMicros,
                routeP50Micros,
                durationDeltaMicros,
                sqlExecutionsDelta,
                sqlDurationMicrosDelta,
                connectionWaitMicrosDelta,
                cacheHitsDelta,
                cacheMissesDelta,
                restClientCallsDelta,
                restClientDurationMicrosDelta,
                ormFlushesDelta,
                ormAutoFlushesDelta,
                ormMaxEntitiesDelta,
                gcPausesTarget,
                gcPausesBaseline,
                distinctSqlShapes,
                candidateDtos);
    }

    private static List<String> distinguishingSqlShapes(RequestMetrics targetMetrics, RequestMetrics baselineMetrics) {
        record ShapeDiff(String shape, boolean onlyInTarget, int countDelta, long targetMicros, int order) {}
        List<ShapeDiff> diffs = new ArrayList<>();
        int index = 0;
        for (Map.Entry<String, int[]> entry : targetMetrics.sqlShapes.entrySet()) {
            String shape = entry.getKey();
            int targetCount = entry.getValue()[0];
            int baselineCount = baselineMetrics.sqlShapes.getOrDefault(shape, new int[1])[0];
            int delta = targetCount - baselineCount;
            if (delta > 0) {
                long targetMicros = targetMetrics.sqlShapeMicros.getOrDefault(shape, 0L);
                diffs.add(new ShapeDiff(shape, baselineCount == 0, delta, targetMicros, index));
            }
            index++;
        }
        diffs.sort((left, right) -> {
            if (left.onlyInTarget() != right.onlyInTarget()) {
                return left.onlyInTarget() ? -1 : 1;
            }
            int byDelta = Integer.compare(right.countDelta(), left.countDelta());
            if (byDelta != 0) {
                return byDelta;
            }
            int byMicros = Long.compare(right.targetMicros(), left.targetMicros());
            if (byMicros != 0) {
                return byMicros;
            }
            return Integer.compare(left.order(), right.order());
        });
        return diffs.stream()
                .limit(MAX_DISTINGUISHING_SQL_SHAPES)
                .map(ShapeDiff::shape)
                .toList();
    }

    private boolean isPartiallyEvicted(RuntimeEvent http, JournalStatus status, boolean evictionsOccurred) {
        if (evictionsOccurred
                && status.oldestRetainedEpochMillis() != null
                && status.oldestRetainedEpochMillis() > http.epochMillis()) {
            return true;
        }
        Long lossHorizon = journal.lossHorizonMillis();
        return lossHorizon != null && http.epochMillis() <= lossHorizon;
    }

    private static String safeRequestId(String id) {
        if (id == null) {
            return "";
        }
        String sanitized = id.replaceAll("[\\p{Cntrl}]", "");
        return sanitized.length() <= 64 ? sanitized : sanitized.substring(0, 64) + "…";
    }

    private boolean sourceAvailable(JournalSource source) {
        if (!journal.records(source)) {
            return false;
        }
        for (String panel : JournalSourcePanels.panelsOf(source)) {
            try {
                if (!panelEnabled.test(panel)) {
                    return false;
                }
            } catch (RuntimeException ex) {
                return false;
            }
        }
        return true;
    }

    private record SelectedChildren(List<JournalEntry> children, int lateHandoffs) {}

    private static final class RequestMetrics {
        int sqlExecutions;
        long sqlDurationMicros;
        boolean hasUnverifiedSql;
        final Map<String, int[]> sqlShapes = new LinkedHashMap<>();
        final Map<String, Long> sqlShapeMicros = new HashMap<>();
        boolean hasConnection;
        long connectionWaitMicros;
        boolean hasCache;
        int cacheHits;
        int cacheMisses;
        int restClientCalls;
        long restClientDurationMicros;

        static RequestMetrics of(List<JournalEntry> children) {
            RequestMetrics metrics = new RequestMetrics();
            for (JournalEntry entry : children) {
                RuntimeEvent event = entry.event();
                RuntimeEventPayload payload = event.payload();
                if (payload instanceof SqlPayload sql) {
                    if (sql.executed()) {
                        metrics.sqlExecutions++;
                        long micros = Math.max(0, event.durationNanos()) / 1_000L;
                        metrics.sqlDurationMicros += micros;
                        String shape = JournalTextExposure.displayShape(sql.sql());
                        if (!shape.isBlank()) {
                            metrics.sqlShapes.computeIfAbsent(shape, key -> new int[1])[0]++;
                            metrics.sqlShapeMicros.merge(shape, micros, Long::sum);
                        }
                    } else {
                        metrics.hasUnverifiedSql = true;
                    }
                } else if (payload instanceof ConnectionPayload connection) {
                    metrics.hasConnection = true;
                    metrics.connectionWaitMicros += Math.max(0, connection.waitNanos()) / 1_000L;
                } else if (payload instanceof CachePayload cache) {
                    metrics.hasCache = true;
                    if ("HIT".equalsIgnoreCase(cache.operation())) {
                        metrics.cacheHits++;
                    } else if ("MISS".equalsIgnoreCase(cache.operation())) {
                        metrics.cacheMisses++;
                    }
                } else if (payload instanceof RestClientPayload) {
                    metrics.restClientCalls++;
                    metrics.restClientDurationMicros += Math.max(0, event.durationNanos()) / 1_000L;
                }
            }
            return metrics;
        }
    }

    private static String executionLabel(RuntimeEvent event) {
        if (event.payload() instanceof ScheduledPayload scheduled) {
            return "Scheduled: " + (scheduled.task() == null ? "task" : scheduled.task());
        }
        if (event.payload() instanceof WebSocketPayload webSocket) {
            return "WebSocket: "
                    + (webSocket.destination() != null
                            ? webSocket.destination()
                            : webSocket.endpoint() == null ? "message" : webSocket.endpoint());
        }
        MessagingPayload message = (MessagingPayload) event.payload();
        return "Message: " + (message.destination() == null ? "consumer" : message.destination());
    }

    /**
     * The request's handoffs (M5-2), each with what was recorded under its execution id, from children already limited
     * to the handoff window.
     */
    private static List<RequestHandoffDto> handoffs(
            List<JournalEntry> children, long requestStart, long requestDurationNanos, boolean httpRequest) {
        Map<String, int[]> work = new HashMap<>();
        Map<String, Long> lastWorkEnds = new HashMap<>();
        List<RuntimeEvent> handoffEvents = new ArrayList<>();
        for (JournalEntry child : children) {
            RuntimeEvent event = child.event();
            if (event.payload() instanceof AsyncHandoffPayload) {
                handoffEvents.add(event);
            } else if (ExecutionIds.isAsync(event.executionId())) {
                int[] counts = work.computeIfAbsent(event.executionId(), id -> new int[3]);
                if (event.payload() instanceof SqlPayload sql && sql.executed()) {
                    counts[0]++;
                } else if (event.payload() instanceof RestClientPayload) {
                    counts[1]++;
                } else if (event.payload() instanceof MessagingPayload) {
                    counts[2]++;
                }
                JournalActivityFeed.recordWorkEnd(lastWorkEnds, event);
            }
        }
        handoffEvents.sort(Comparator.comparingLong(RuntimeEvent::epochMillis));
        List<RequestHandoffDto> attached = new ArrayList<>();
        for (RuntimeEvent event : handoffEvents) {
            AsyncHandoffPayload handoff = (AsyncHandoffPayload) event.payload();
            long durationNanos = Math.max(0, event.durationNanos());
            long lastWorkEnd = JournalActivityFeed.lastWorkEnd(lastWorkEnds, handoff);
            Boolean worked = handoff.workedAfterResponse(lastWorkEnd);
            boolean afterResponse;
            Long afterResponseMicros;
            if (!httpRequest) {
                afterResponse = false;
                afterResponseMicros = null;
            } else if (worked != null) {
                afterResponse = worked;
                afterResponseMicros = handoff.workedAfterResponseMicros(lastWorkEnd);
            } else {
                long endMicros = event.epochMillis() * 1_000L + durationNanos / 1_000L;
                long requestEndMicros = requestStart * 1_000L + requestDurationNanos / 1_000L;
                afterResponse = endMicros > requestEndMicros;
                afterResponseMicros =
                        afterResponse ? endMicros - Math.max(event.epochMillis() * 1_000L, requestEndMicros) : 0L;
            }
            int[] counts = work.getOrDefault(handoff.executionId(), new int[3]);
            attached.add(new RequestHandoffDto(
                    handoff.executionId(),
                    handoff.parentExecutionId(),
                    event.thread(),
                    (event.epochMillis() - requestStart) * 1_000L,
                    durationNanos / 1_000L,
                    Math.max(0, handoff.queuedNanos()) / 1_000L,
                    handoff.taskClass(),
                    handoff.hook(),
                    handoff.failed(),
                    handoff.exceptionClass(),
                    afterResponse,
                    afterResponseMicros,
                    handoff.capped(),
                    counts[0],
                    counts[1],
                    counts[2],
                    handoff.allocatedBytes()));
        }
        return attached;
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
        if (event.payload() instanceof SqlPayload sql && !sql.executed()) {
            durationMicros = null;
        }
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

    private static Long startNanos(RuntimeEvent event) {
        if (!(event.payload() instanceof SqlPayload sql) || sql.completedNanos() < 0 || event.durationNanos() < 0) {
            return null;
        }
        try {
            return Math.subtractExact(sql.completedNanos(), event.durationNanos());
        } catch (ArithmeticException ex) {
            return null;
        }
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

    private static TouchedResourcesDto touched(List<JournalEntry> children, JournalTextExposure text) {
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
            if (payload instanceof SqlPayload sql && sql.executed()) {
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
                                        + (!transaction.begun()
                                                ? " (begin failed)"
                                                : transaction.rolledBack() ? " (rolled back)" : " (committed)"));
            } else if (payload instanceof CachePayload cache) {
                add(caches, cache.cacheName() == null ? null : cache.cacheName() + " (" + cache.operation() + ")");
            } else if (payload instanceof MessagingPayload message && message.sent()) {
                add(
                        messages,
                        message.destination() == null ? null : message.destination() + " (" + message.broker() + ")");
            } else if (payload instanceof RestClientPayload rest) {
                add(restCalls, rest.authority());
            } else if (payload instanceof LogPayload log) {
                add(logTemplates, text.message(log.template()));
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

    private record Timed(RequestTimelineItemDto item, long sequence, Long startNanos) {}
}
