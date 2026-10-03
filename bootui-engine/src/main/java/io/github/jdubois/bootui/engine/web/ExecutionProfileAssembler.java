package io.github.jdubois.bootui.engine.web;

import io.github.jdubois.bootui.core.dto.ExceptionDetailDto;
import io.github.jdubois.bootui.core.dto.ExceptionGroupDto;
import io.github.jdubois.bootui.core.dto.ExceptionOccurrenceDto;
import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import io.github.jdubois.bootui.core.dto.RequestProfileCacheAccessDto;
import io.github.jdubois.bootui.core.dto.RequestProfileDto;
import io.github.jdubois.bootui.core.dto.RequestProfileExceptionDto;
import io.github.jdubois.bootui.core.dto.RequestProfileSectionDto;
import io.github.jdubois.bootui.core.dto.RequestProfileSecurityDto;
import io.github.jdubois.bootui.core.dto.RequestProfileTierDto;
import io.github.jdubois.bootui.core.dto.RequestProfileTimingDto;
import io.github.jdubois.bootui.core.dto.RestClientTraceEntryDto;
import io.github.jdubois.bootui.core.dto.SecurityLogEventDto;
import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import io.github.jdubois.bootui.core.dto.SqlTraceGroupDto;
import io.github.jdubois.bootui.core.dto.TraceDetailDto;
import io.github.jdubois.bootui.engine.cache.CacheActivityEvent;
import io.github.jdubois.bootui.engine.correlation.ExecutionIds;
import io.github.jdubois.bootui.engine.correlation.HandoffWindow;
import io.github.jdubois.bootui.engine.sqltrace.SqlDurations;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceGrouping;
import io.github.jdubois.bootui.engine.support.BlankStrings;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/**
 * Framework-neutral assembly of an execution profile: one anchor — today an HTTP request — correlated
 * with the SQL, exceptions, security events, REST client calls, and cache accesses it produced.
 *
 * <p>Every adapter serves {@code GET /bootui/api/activity/request/{id}} through this one class, so the
 * correlation policy, child ordering, bounds, timing, N+1 grouping, and notes cannot drift between Spring
 * MVC, Spring WebFlux, and Quarkus. An adapter contributes only what it alone can observe: the evidence
 * each source panel already captured ({@link ProfileEvidence}) and the tiers it can prove
 * ({@link ProfileCapabilities}).</p>
 *
 * <p>Correlation is tiered, strongest first, and a child attaches to at most one anchor:</p>
 *
 * <ol>
 *   <li>{@link CorrelationTier#REQUEST_ID} on every adapter: a child carrying the BootUI request id one
 *       captured request carries belongs to that request, whatever thread it ran on.</li>
 *   <li>{@link CorrelationTier#PROPAGATED}: the same, for a child recorded in a task the BootUI agent
 *       propagated ({@code async-…} execution id), within the handoff window of {@link HandoffWindow};
 *       other children are left out.</li>
 *   <li>{@link CorrelationTier#TRACE_ID} on every adapter: a child whose trace id is carried by exactly
 *       one anchor of any type whose trace window contains it (see {@link TraceCorrelationIndex}). A trace
 *       two such anchors carry attaches nothing by trace id.</li>
 *   <li>{@link CorrelationTier#SERVING_THREAD} where the adapter has a thread-per-request model: a child
 *       recorded on the one thread that served exactly one anchor, inside its window.</li>
 *   <li>{@link CorrelationTier#TIME_WINDOW} as a labelled last resort on the same adapters: a child
 *       inside exactly one anchor's window, which marks the profile approximate.</li>
 * </ol>
 *
 * <p>A child that more than one anchor could equally claim at the tier that decides it stays out of
 * every profile and is counted in the notes. HTTP anchors keep the request-profile policy each section
 * has always used: SQL falls back to the time window only when no exact tier matched any statement;
 * exceptions add the request method and path to the thread and window tiers; security events add the
 * principal, sharpened by the adapter's serving-thread classifier; REST client calls and cache accesses,
 * like the Live Activity feed, use the exact tiers only.</p>
 *
 * <p>Building a profile reads only the evidence handed in and never captures, calls the network, or
 * mutates anything.</p>
 */
public final class ExecutionProfileAssembler {

    /** Maximum number of children each profile section shows; the rest are counted as truncated. */
    public static final int DEFAULT_MAX_CHILDREN_PER_SECTION = 200;

    static final String NO_TRACE_ID_REASON = "No distributed trace id and no BootUI request id was captured for "
            + "this request; per-request profiling on this adapter needs one of them, for example a trace id from an "
            + "active distributed tracing integration (such as OpenTelemetry) or an inbound propagation header.";

    static final String REDUCED_PROFILE_NOTE = "This is a reduced profile: this adapter has no time-window or "
            + "serving-thread correlation available, so only signals carrying this request's BootUI request id or "
            + "its exact trace id are shown.";

    private static final String TYPE_SQL = "SQL";
    private static final String TYPE_EXCEPTION = "EXCEPTION";
    private static final String TYPE_SECURITY = "SECURITY";
    private static final String TYPE_REST_CLIENT = "REST_CLIENT";
    private static final String TYPE_CACHE = "CACHE";

    /** The default {@code bootui.agent.executors.max-handoff} ({@link HandoffWindow}). */
    public static final long DEFAULT_MAX_HANDOFF_MILLIS = HandoffWindow.DEFAULT_MAX_HANDOFF_MILLIS;

    private final int nPlusOneThreshold;
    private final int maxChildrenPerSection;
    private final long maxHandoffMillis;

    /** An assembler with the default N+1 threshold and section bound. */
    public ExecutionProfileAssembler() {
        this(SqlTraceGrouping.DEFAULT_N_PLUS_ONE_THRESHOLD);
    }

    /** An assembler with the given N+1 threshold and the default section bound. */
    public ExecutionProfileAssembler(int nPlusOneThreshold) {
        this(nPlusOneThreshold, DEFAULT_MAX_CHILDREN_PER_SECTION);
    }

    public ExecutionProfileAssembler(int nPlusOneThreshold, int maxChildrenPerSection) {
        this(nPlusOneThreshold, maxChildrenPerSection, DEFAULT_MAX_HANDOFF_MILLIS);
    }

    /**
     * @param maxHandoffMillis {@code bootui.agent.executors.max-handoff}: a task the agent propagated belongs to its
     *     request when it started no later than this after the request ended, and its work recorded no later than
     *     this after it started is attributed ({@link HandoffWindow})
     */
    public ExecutionProfileAssembler(int nPlusOneThreshold, int maxChildrenPerSection, long maxHandoffMillis) {
        this.nPlusOneThreshold = nPlusOneThreshold;
        this.maxChildrenPerSection = Math.max(1, maxChildrenPerSection);
        this.maxHandoffMillis = HandoffWindow.millis(maxHandoffMillis);
    }

    /**
     * Builds the profile of the request with id {@code requestId}.
     *
     * @param requestId the Live Activity id of the request to profile
     * @param evidence the captured evidence, including every captured request as a potential anchor
     * @param capabilities the correlation tiers the calling adapter can prove
     * @return the profile, or an unavailable profile explaining why none can be built
     */
    public RequestProfileDto requestProfile(
            String requestId, ProfileEvidence evidence, ProfileCapabilities capabilities) {
        HttpExchangeDto request = findRequest(requestId, evidence.requests());
        if (request == null) {
            return RequestProfileDto.unavailable("Request " + requestId + " is no longer in the buffer");
        }
        String traceId = BlankStrings.blankToNull(request.traceId());
        Context context =
                context(request, evidence.requests(), capabilities, maxHandoffMillis, handoffStarts(evidence));
        if (traceId == null && !context.carriesRequestId() && !context.self().hasHeuristicTier()) {
            return RequestProfileDto.unavailable(NO_TRACE_ID_REASON);
        }
        List<String> notes = new ArrayList<>();
        if (capabilities.traceIdOnlyAdapter()) {
            notes.add(REDUCED_PROFILE_NOTE);
        }
        if (traceId != null && context.index().isSharedByRequests(traceId)) {
            notes.add(
                    capabilities.traceIdOnlyAdapter()
                            ? "This request's trace id " + traceId + " is shared by more than one captured request, "
                                    + "so trace-id correlation was skipped to avoid attributing another request's "
                                    + "signals to this one; only signals carrying this request's BootUI request id "
                                    + "are attributed."
                            : "This request's trace id " + traceId + " is shared by more than one captured request, "
                                    + "so trace-id correlation was skipped and only the request-id, serving-thread, "
                                    + "and time-window tiers were used.");
        }

        Section<SqlTraceEntryDto> sql = correlateSql(context, evidence.sql());
        sqlNotes(context, sql, notes);
        Section<RequestProfileExceptionDto> exceptions = correlateExceptions(context, evidence.exceptions());
        exceptionNotes(context, exceptions, notes);
        Section<RequestProfileSecurityDto> security = correlateSecurity(context, evidence.security());
        securityNotes(context, security, notes);
        Section<RestClientTraceEntryDto> restCalls = correlateByExactTiers(
                context,
                TYPE_REST_CLIENT,
                evidence.restCalls(),
                RestClientTraceEntryDto::requestId,
                RestClientTraceEntryDto::executionId,
                RestClientTraceEntryDto::traceId,
                RestClientTraceEntryDto::thread,
                RestClientTraceEntryDto::timestamp);
        exactTierNotes(context, restCalls, "REST client calls are", "REST client call(s)", notes);
        Section<CacheActivityEvent> cache = correlateByExactTiers(
                context,
                TYPE_CACHE,
                evidence.cacheAccesses(),
                CacheActivityEvent::requestId,
                CacheActivityEvent::executionId,
                CacheActivityEvent::traceId,
                CacheActivityEvent::thread,
                CacheActivityEvent::timestampMillis);
        exactTierNotes(context, cache, "Cache accesses are", "cache access(es)", notes);

        TraceDetailDto trace = traceId == null || evidence.traces() == null
                ? null
                : evidence.traces().apply(traceId);
        TraceDetailDto matchedTrace = trace != null && traceId.equals(trace.traceId()) ? trace : null;
        if (matchedTrace != null) {
            notes.add("Trace matched by id " + traceId + ".");
        }

        List<SqlTraceEntryDto> allSql = sql.records();
        List<SqlTraceGroupDto> sqlGroups = SqlTraceGrouping.group(allSql, nPlusOneThreshold);
        if (sqlGroups.size() > maxChildrenPerSection) {
            notes.add("Showing the " + maxChildrenPerSection + " most repeated of " + sqlGroups.size()
                    + " distinct SQL statements.");
        }
        List<RestClientTraceEntryDto> allRestCalls = restCalls.records();
        List<Section<?>> sections = List.of(sql, exceptions, security, restCalls, cache);
        boolean approximate = sections.stream().anyMatch(section -> section.tier() == CorrelationTier.TIME_WINDOW);

        return new RequestProfileDto(
                true,
                null,
                request,
                bounded(allSql),
                bounded(sqlGroups),
                sql.tier() == CorrelationTier.TIME_WINDOW,
                bounded(exceptions.records()),
                bounded(security.records()),
                matchedTrace,
                timing(request, allSql, allRestCalls),
                notes,
                bounded(allRestCalls),
                bounded(cache.records()).stream()
                        .map(ExecutionProfileAssembler::toCacheAccess)
                        .toList(),
                sections.stream().map(this::toSectionDto).toList(),
                tiers(capabilities),
                approximate);
    }

    private static HttpExchangeDto findRequest(String requestId, List<HttpExchangeDto> requests) {
        if (requestId == null) {
            return null;
        }
        for (HttpExchangeDto exchange : requests) {
            if (requestId.equals(exchange.id())) {
                return exchange;
            }
        }
        for (HttpExchangeDto exchange : requests) {
            if (requestId.equals(exchange.requestId())) {
                return exchange;
            }
        }
        return null;
    }

    /**
     * Whether {@code id} names {@code exchange}: its exchange id, which the 1.x feed uses, or BootUI's request id, which
     * the feed rendered from the runtime journal uses ({@code docs/PLAN-v2.md} §5.3).
     */
    public static boolean identifies(HttpExchangeDto exchange, String id) {
        return exchange != null && id != null && (id.equals(exchange.id()) || id.equals(exchange.requestId()));
    }

    /**
     * When each propagated execution in the evidence started. The panels' evidence carries no handoffs, so each
     * execution's earliest recorded work stands for its start ({@link HandoffWindow.Starts}).
     */
    private static HandoffWindow.Starts handoffStarts(ProfileEvidence evidence) {
        HandoffWindow.Starts starts = new HandoffWindow.Starts();
        for (SqlTraceEntryDto entry : evidence.sql().records()) {
            starts.recorded(entry.executionId(), entry.timestamp());
        }
        for (RestClientTraceEntryDto call : evidence.restCalls().records()) {
            starts.recorded(call.executionId(), call.timestamp());
        }
        for (CacheActivityEvent access : evidence.cacheAccesses().records()) {
            starts.recorded(access.executionId(), access.timestampMillis());
        }
        for (ExceptionDetailDto detail : evidence.exceptions().records()) {
            for (ExceptionOccurrenceDto occurrence : detail.occurrences()) {
                starts.recorded(occurrence.executionId(), occurrence.timestamp());
            }
        }
        return starts;
    }

    private static Context context(
            HttpExchangeDto request,
            List<HttpExchangeDto> requests,
            ProfileCapabilities capabilities,
            long maxHandoffMillis,
            HandoffWindow.Starts handoffStarts) {
        List<ProfileAnchor> anchors = new ArrayList<>(requests.size());
        Map<String, ProfileAnchor> byRequestId = new HashMap<>();
        Set<String> sharedRequestIds = new HashSet<>();
        ProfileAnchor self = null;
        String selfRequestId = null;
        for (HttpExchangeDto exchange : requests) {
            ProfileAnchor anchor =
                    ProfileAnchor.request(exchange, servingThread(exchange, capabilities), capabilities.timeWindow());
            anchors.add(anchor);
            String requestId = BlankStrings.blankToNull(exchange.requestId());
            if (requestId != null && byRequestId.putIfAbsent(requestId, anchor) != null) {
                sharedRequestIds.add(requestId);
            }
            if (self == null && exchange == request) {
                self = anchor;
                selfRequestId = requestId;
            }
        }
        sharedRequestIds.forEach(byRequestId::remove);
        return new Context(
                self,
                anchors,
                TraceCorrelationIndex.ofAnchors(anchors),
                capabilities,
                byRequestId,
                selfRequestId != null && byRequestId.containsKey(selfRequestId),
                maxHandoffMillis,
                handoffStarts);
    }

    private static ProfileCapabilities.ServingThread servingThread(
            HttpExchangeDto exchange, ProfileCapabilities capabilities) {
        if (capabilities.servingThreads() == null || exchange.method() == null || exchange.path() == null) {
            return null;
        }
        long start = exchange.timestamp() == null ? 0L : exchange.timestamp().toEpochMilli();
        long end = exchange.durationMs() == null ? start : start + exchange.durationMs();
        try {
            return capabilities.servingThreads().resolve(exchange.method(), exchange.path(), start, end);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    // --- SQL -----------------------------------------------------------------------------------------

    /**
     * SQL keeps the request profile's section-wide tiering: the request-id and trace-id tiers when any statement
     * matched them, otherwise the serving-thread tier when any statement matched that, otherwise the time window.
     * Statements another anchor claims at a stronger tier never fall through to a weaker one.
     */
    private static Section<SqlTraceEntryDto> correlateSql(
            Context context, ProfileEvidence.Source<SqlTraceEntryDto> source) {
        Section<SqlTraceEntryDto> section = new Section<>(TYPE_SQL, source);
        List<SqlTraceEntryDto> records = source.records();
        List<Decision> decisions = new ArrayList<>(records.size());
        boolean anyExact = false;
        for (SqlTraceEntryDto entry : records) {
            Decision decision = byRequestIdThenTrace(
                    context, entry.requestId(), entry.executionId(), entry.traceId(), entry.timestamp());
            decisions.add(decision);
            anyExact |= decision.outcome() == Outcome.OURS;
        }
        if (anyExact) {
            for (int i = 0; i < records.size(); i++) {
                SqlTraceEntryDto entry = records.get(i);
                section.accept(decisions.get(i), () -> entry);
            }
            section.ambiguous((int) decisions.stream()
                    .filter(decision -> decision.outcome() == Outcome.AMBIGUOUS)
                    .count());
            return section.sorted(SqlTraceEntryDto::timestamp);
        }

        List<SqlTraceEntryDto> byThread = new ArrayList<>();
        int ambiguous = 0;
        for (int i = 0; i < records.size(); i++) {
            SqlTraceEntryDto entry = records.get(i);
            Decision decision = decisions.get(i);
            if (decision.outcome() == Outcome.OTHER) {
                continue;
            }
            Decision thread = byThread(context, entry.thread(), entry.timestamp());
            if (thread.outcome() == Outcome.OURS) {
                byThread.add(entry);
                decisions.set(i, thread);
            } else if (thread.outcome() == Outcome.UNDECIDED) {
                if (decision.outcome() == Outcome.AMBIGUOUS) {
                    ambiguous++;
                }
            } else {
                decisions.set(i, thread);
                if (thread.outcome() == Outcome.AMBIGUOUS) {
                    ambiguous++;
                }
            }
        }
        section.ambiguous(ambiguous);
        if (!byThread.isEmpty()) {
            byThread.forEach(entry -> section.add(entry, CorrelationTier.SERVING_THREAD));
            return section.sorted(SqlTraceEntryDto::timestamp);
        }

        if (context.self().timeWindowTier()) {
            for (int i = 0; i < records.size(); i++) {
                if (decisions.get(i).outcome() != Outcome.UNDECIDED) {
                    continue;
                }
                SqlTraceEntryDto entry = records.get(i);
                Decision window = pick(
                        context,
                        anchor -> anchor.timeWindowTier() && anchor.contains(entry.timestamp()),
                        anchor -> true,
                        CorrelationTier.TIME_WINDOW);
                if (window.outcome() == Outcome.OURS) {
                    section.add(entry, CorrelationTier.TIME_WINDOW);
                } else if (window.outcome() == Outcome.AMBIGUOUS) {
                    section.ambiguous(section.ambiguous() + 1);
                }
            }
        }
        return section.sorted(SqlTraceEntryDto::timestamp);
    }

    private static void sqlNotes(Context context, Section<SqlTraceEntryDto> section, List<String> notes) {
        if (section.uses(CorrelationTier.REQUEST_ID)) {
            notes.add(requestIdNote("SQL statements"));
        }
        if (section.uses(CorrelationTier.PROPAGATED)) {
            notes.add(propagatedNote("SQL statements"));
        }
        if (section.uses(CorrelationTier.TRACE_ID)) {
            notes.add("SQL is correlated exactly by trace id " + context.self().traceId() + ".");
        } else if (section.tier() == CorrelationTier.SERVING_THREAD) {
            notes.add("SQL is correlated exactly by the request's serving thread within its window.");
        } else if (section.tier() == CorrelationTier.TIME_WINDOW) {
            notes.add("SQL is correlated by time window only (no matching trace id or serving thread), so it may "
                    + "include or miss statements under concurrent requests.");
        }
        ambiguityNote(section, "SQL statement(s)", notes);
    }

    // --- Exceptions ----------------------------------------------------------------------------------

    /**
     * Exceptions match per occurrence. An anchor with only the trace-id tier keeps every occurrence its
     * trace id attaches. An HTTP anchor with heuristic tiers keeps today's gate — the occurrence's request
     * method and path within the request window — and within it the trace id decides first, then the
     * serving thread, whose owner an occurrence thrown on another thread cannot be, then the time window.
     */
    private static Section<RequestProfileExceptionDto> correlateExceptions(
            Context context, ProfileEvidence.Source<ExceptionDetailDto> source) {
        Section<RequestProfileExceptionDto> section = new Section<>(TYPE_EXCEPTION, source);
        for (ExceptionDetailDto detail : source.records()) {
            ExceptionGroupDto group = detail.group();
            for (ExceptionOccurrenceDto occurrence : detail.occurrences()) {
                Decision decision = correlateOccurrence(context, occurrence);
                section.accept(
                        decision,
                        () -> new RequestProfileExceptionDto(
                                group.exceptionClassName(),
                                group.message(),
                                group.location(),
                                occurrence.timestamp(),
                                occurrence.thread(),
                                occurrence.handler(),
                                occurrence.source(),
                                group.id()));
            }
        }
        return section.sorted(RequestProfileExceptionDto::timestamp);
    }

    private static Decision correlateOccurrence(Context context, ExceptionOccurrenceDto occurrence) {
        long timestamp = occurrence.timestamp();
        Decision byRequestId = byRequestId(context, occurrence.requestId(), occurrence.executionId(), timestamp);
        if (byRequestId.decided()) {
            return byRequestId;
        }
        Decision decision = byTrace(context, occurrence.traceId(), timestamp);
        ProfileAnchor self = context.self();
        if (!self.hasHeuristicTier()) {
            return decision;
        }
        Predicate<ProfileAnchor> sameRequest =
                anchor -> anchor.admitsRequestContext(occurrence.requestMethod(), occurrence.requestPath())
                        && anchor.covers(timestamp);
        if (!sameRequest.test(self)) {
            return Decision.OTHER;
        }
        if (decision.decided()) {
            return decision;
        }
        String thread = occurrence.thread();
        if (thread != null
                && self.servingThread() != null
                && !self.servingThread().equals(thread)) {
            // The request's own thread is known and the occurrence was thrown on another one.
            return Decision.OTHER;
        }
        if (thread != null) {
            Decision byThread = pick(
                    context,
                    anchor -> sameRequest.test(anchor) && anchor.servedOn(thread, timestamp),
                    anchor -> anchor.servingStartMillis() <= timestamp && timestamp <= anchor.servingEndMillis(),
                    CorrelationTier.SERVING_THREAD);
            if (byThread.outcome() != Outcome.UNDECIDED) {
                return byThread;
            }
        }
        if (decision.outcome() == Outcome.AMBIGUOUS || !self.timeWindowTier()) {
            return decision;
        }
        // Requests known to have been served on another thread than the occurrence's cannot have thrown it.
        return pick(
                context,
                anchor -> sameRequest.test(anchor)
                        && anchor.timeWindowTier()
                        && (thread == null
                                || anchor.servingThread() == null
                                || anchor.servingThread().equals(thread)),
                anchor -> anchor.contains(timestamp),
                CorrelationTier.TIME_WINDOW);
    }

    private static void exceptionNotes(
            Context context, Section<RequestProfileExceptionDto> section, List<String> notes) {
        if (section.uses(CorrelationTier.REQUEST_ID)) {
            notes.add(requestIdNote("Exception occurrences"));
        }
        if (section.uses(CorrelationTier.PROPAGATED)) {
            notes.add(propagatedNote("Exception occurrences"));
        }
        if (section.uses(CorrelationTier.TRACE_ID)) {
            notes.add("Exceptions are correlated exactly by trace id "
                    + context.self().traceId() + ".");
        }
        if (section.uses(CorrelationTier.SERVING_THREAD)) {
            notes.add("Exceptions are matched exactly by the request's serving thread, within its window and "
                    + "alongside the request method and path.");
        }
        if (section.uses(CorrelationTier.TIME_WINDOW)) {
            notes.add("Exceptions are matched by request method, path and time window.");
        }
        ambiguityNote(section, "exception occurrence(s)", notes);
    }

    // --- Security ------------------------------------------------------------------------------------

    /**
     * Security events match per event: trace id first; otherwise the request window and principal, pinned
     * exactly to the serving thread when the adapter captured the event there and excluded when it proved
     * the event fired on another thread.
     */
    private static Section<RequestProfileSecurityDto> correlateSecurity(
            Context context, ProfileEvidence.Source<SecurityLogEventDto> source) {
        Section<RequestProfileSecurityDto> section = new Section<>(TYPE_SECURITY, source);
        String requestPrincipal = context.self().principal();
        for (SecurityLogEventDto event : source.records()) {
            long timestamp = parseEpochMillis(event.timestamp());
            Decision decision = correlateSecurityEvent(context, event, timestamp);
            boolean principalMatched = principalMatches(requestPrincipal, event.principal());
            boolean threadMatched = decision.tier() == CorrelationTier.SERVING_THREAD;
            section.accept(
                    decision,
                    () -> new RequestProfileSecurityDto(
                            event.type(), event.principal(), timestamp, principalMatched, threadMatched));
        }
        return section.sorted(RequestProfileSecurityDto::timestamp);
    }

    private static Decision correlateSecurityEvent(Context context, SecurityLogEventDto event, long timestamp) {
        Decision byRequestId = byRequestId(context, event.requestId());
        if (byRequestId.decided()) {
            return byRequestId;
        }
        Decision decision = byTrace(context, event.traceId(), timestamp);
        ProfileAnchor self = context.self();
        if (!self.hasHeuristicTier()) {
            return decision;
        }
        Predicate<ProfileAnchor> sameWindow =
                anchor -> anchor.covers(timestamp) && anchor.admitsPrincipal(event.principal());
        // An HTTP request keeps today's window-and-principal gate even for a trace match.
        if (self.type() == ProfileAnchor.Type.REQUEST && !sameWindow.test(self)) {
            return Decision.OTHER;
        }
        if (decision.decided()) {
            return decision;
        }
        ProfileCapabilities.SecurityThreadClassifier classifier =
                context.capabilities().securityThreads();
        List<ProfileAnchor> onTheirThread = new ArrayList<>();
        List<ProfileAnchor> unknownThread = new ArrayList<>();
        for (ProfileAnchor anchor : context.anchors()) {
            if (!sameWindow.test(anchor)) {
                continue;
            }
            ProfileCapabilities.ThreadMatch match = anchor.servingThread() == null || classifier == null
                    ? ProfileCapabilities.ThreadMatch.UNKNOWN
                    : classify(classifier, anchor.servingThread(), event.type(), timestamp);
            if (match == ProfileCapabilities.ThreadMatch.OURS) {
                onTheirThread.add(anchor);
            } else if (match == ProfileCapabilities.ThreadMatch.UNKNOWN && anchor.timeWindowTier()) {
                unknownThread.add(anchor);
            }
        }
        if (!onTheirThread.isEmpty()) {
            return pick(
                    context,
                    onTheirThread::contains,
                    anchor -> anchor.contains(timestamp),
                    CorrelationTier.SERVING_THREAD);
        }
        if (decision.outcome() == Outcome.AMBIGUOUS || !self.timeWindowTier()) {
            return decision;
        }
        return pick(
                context, unknownThread::contains, anchor -> anchor.contains(timestamp), CorrelationTier.TIME_WINDOW);
    }

    private static ProfileCapabilities.ThreadMatch classify(
            ProfileCapabilities.SecurityThreadClassifier classifier, String thread, String type, long timestamp) {
        try {
            ProfileCapabilities.ThreadMatch match = classifier.classify(thread, type, timestamp);
            return match == null ? ProfileCapabilities.ThreadMatch.UNKNOWN : match;
        } catch (RuntimeException ex) {
            return ProfileCapabilities.ThreadMatch.UNKNOWN;
        }
    }

    private static void securityNotes(Context context, Section<RequestProfileSecurityDto> section, List<String> notes) {
        if (section.uses(CorrelationTier.REQUEST_ID)) {
            notes.add(requestIdNote("Security events"));
        }
        if (section.uses(CorrelationTier.TRACE_ID)) {
            notes.add(
                    context.capabilities().traceIdOnlyAdapter()
                            ? "Security events are correlated exactly by trace id "
                                    + context.self().traceId()
                                    + " (this adapter has no per-request serving-thread identity to further "
                                    + "disambiguate them)."
                            : "Security events are correlated exactly by trace id "
                                    + context.self().traceId() + ".");
        }
        if (section.uses(CorrelationTier.SERVING_THREAD)) {
            notes.add("Security events are matched exactly by the request's serving thread; events emitted on "
                    + "other threads (for example a concurrent request sharing the principal) are excluded.");
        }
        if (section.uses(CorrelationTier.TIME_WINDOW)) {
            String principal = context.self().principal();
            notes.add("Security events are matched by time window"
                    + (principal == null ? "." : " and the request principal " + principal + "."));
        }
        ambiguityNote(section, "security event(s)", notes);
    }

    // --- REST client calls and cache accesses --------------------------------------------------------

    /**
     * REST client calls and cache accesses run on the thread that triggered them, so, like the Live
     * Activity feed, they attach by trace id or serving thread only and never by time window.
     */
    private static <T> Section<T> correlateByExactTiers(
            Context context,
            String type,
            ProfileEvidence.Source<T> source,
            Function<T, String> requestId,
            Function<T, String> executionId,
            Function<T, String> traceId,
            Function<T, String> thread,
            ToLongFunction<T> timestamp) {
        Section<T> section = new Section<>(type, source);
        for (T record : source.records()) {
            long at = timestamp.applyAsLong(record);
            Decision decision = byRequestIdThenTrace(
                    context, requestId.apply(record), executionId.apply(record), traceId.apply(record), at);
            if (!decision.decided()) {
                decision = decision.orElse(byThread(context, thread.apply(record), at));
            }
            section.accept(decision, () -> record);
        }
        return section.sorted(timestamp);
    }

    private static void exactTierNotes(
            Context context, Section<?> section, String subject, String countNoun, List<String> notes) {
        if (section.uses(CorrelationTier.REQUEST_ID)) {
            notes.add(requestIdNote(subject.substring(0, subject.length() - " are".length())));
        }
        if (section.uses(CorrelationTier.PROPAGATED)) {
            notes.add(propagatedNote(subject.substring(0, subject.length() - " are".length())));
        }
        if (section.uses(CorrelationTier.TRACE_ID)) {
            notes.add(subject + " correlated exactly by trace id "
                    + context.self().traceId() + ".");
        }
        if (section.uses(CorrelationTier.SERVING_THREAD)) {
            notes.add(subject + " correlated exactly by the request's serving thread within its window.");
        }
        ambiguityNote(section, countNoun, notes);
    }

    // --- Shared tiers --------------------------------------------------------------------------------

    /**
     * Resolves a child by the BootUI request id it carries: exact, since each request has its own. A child carrying
     * another captured request's id belongs to that request; an id no captured request carries decides nothing, so
     * the weaker tiers still may.
     */
    private static Decision byRequestId(Context context, String requestId) {
        return byRequestId(context, requestId, null, 0L);
    }

    /**
     * Resolves a child by its request id, at the {@link CorrelationTier#PROPAGATED} tier when it ran in a task the agent
     * propagated: such a child outside the handoff window ({@link HandoffWindow}) belongs to no request.
     */
    private static Decision byRequestId(Context context, String requestId, String executionId, long timestamp) {
        String stamped = BlankStrings.blankToNull(requestId);
        ProfileAnchor owner = stamped == null ? null : context.byRequestId().get(stamped);
        if (owner == null) {
            return Decision.UNDECIDED;
        }
        if (owner != context.self()) {
            return Decision.OTHER;
        }
        if (ExecutionIds.isAsync(executionId)) {
            return context.handoffStarts()
                            .attributed(executionId, timestamp, owner.endMillis(), context.maxHandoffMillis())
                    ? Decision.ours(CorrelationTier.PROPAGATED)
                    : Decision.OTHER;
        }
        return Decision.ours(CorrelationTier.REQUEST_ID);
    }

    /** The request-id tier, then the trace-id tier when the request id decides nothing. */
    private static Decision byRequestIdThenTrace(
            Context context, String requestId, String executionId, String traceId, long timestamp) {
        Decision decision = byRequestId(context, requestId, executionId, timestamp);
        return decision.decided() ? decision : byTrace(context, traceId, timestamp);
    }

    /** Resolves a child by trace id against every anchor. */
    private static Decision byTrace(Context context, String traceId, long timestamp) {
        TraceCorrelationIndex.Match match = context.index().match(traceId, timestamp);
        return switch (match.status()) {
            case ATTACHED ->
                match.anchor() == context.self() ? Decision.ours(CorrelationTier.TRACE_ID) : Decision.OTHER;
            case AMBIGUOUS -> match.claimedBy(context.self()) ? Decision.AMBIGUOUS : Decision.OTHER;
            // Another execution's trace id: a weaker tier must not hand the child to this one.
            case OUTSIDE_WINDOW -> match.carriedBy(context.self()) ? Decision.UNDECIDED : Decision.OTHER;
            case UNCLAIMED -> Decision.UNDECIDED;
        };
    }

    /** Resolves a child by the serving thread of every anchor whose adapter identified one. */
    private static Decision byThread(Context context, String thread, long timestamp) {
        if (thread == null) {
            return Decision.UNDECIDED;
        }
        return pick(
                context,
                anchor -> anchor.servedOn(thread, timestamp),
                anchor -> anchor.servingStartMillis() <= timestamp && timestamp <= anchor.servingEndMillis(),
                CorrelationTier.SERVING_THREAD);
    }

    /**
     * The unique-candidate rule shared by the thread and window tiers: the anchors {@code eligible} admits
     * decide the child when exactly one remains, first as is and then narrowed to those whose exact
     * window, without slack, holds it. A child two anchors still claim is ambiguous when this request is
     * one of them, and another request's otherwise.
     */
    private static Decision pick(
            Context context, Predicate<ProfileAnchor> eligible, Predicate<ProfileAnchor> exact, CorrelationTier tier) {
        List<ProfileAnchor> candidates = new ArrayList<>();
        for (ProfileAnchor anchor : context.anchors()) {
            if (eligible.test(anchor)) {
                candidates.add(anchor);
            }
        }
        if (candidates.isEmpty()) {
            return Decision.UNDECIDED;
        }
        if (candidates.size() > 1) {
            List<ProfileAnchor> narrowed = candidates.stream().filter(exact).toList();
            if (narrowed.size() == 1) {
                return narrowed.get(0) == context.self() ? Decision.ours(tier) : Decision.OTHER;
            }
            return candidates.contains(context.self()) ? Decision.AMBIGUOUS : Decision.OTHER;
        }
        return candidates.get(0) == context.self() ? Decision.ours(tier) : Decision.OTHER;
    }

    private static String propagatedNote(String subject) {
        return subject
                + " recorded in tasks the BootUI agent propagated from this request to an executor are correlated"
                + " exactly by its request id.";
    }

    private static String requestIdNote(String subject) {
        return subject + " carrying this request's BootUI request id are correlated exactly, with or without tracing.";
    }

    private static void ambiguityNote(Section<?> section, String noun, List<String> notes) {
        if (section.ambiguous() > 0) {
            notes.add(section.ambiguous() + " " + noun + " could equally belong to another captured request, so "
                    + "they are attributed to neither.");
        }
    }

    // --- Output --------------------------------------------------------------------------------------

    private <T> List<T> bounded(List<T> records) {
        return records.size() <= maxChildrenPerSection ? records : records.subList(0, maxChildrenPerSection);
    }

    private RequestProfileSectionDto toSectionDto(Section<?> section) {
        int total = section.records().size();
        return new RequestProfileSectionDto(
                section.type(),
                section.source().available(),
                section.source().unavailableReason(),
                section.tier() == null ? null : section.tier().name(),
                bounded(section.childTiers()).stream().map(Enum::name).toList(),
                total,
                Math.max(0, total - maxChildrenPerSection),
                section.ambiguous());
    }

    private static List<RequestProfileTierDto> tiers(ProfileCapabilities capabilities) {
        List<RequestProfileTierDto> tiers = new ArrayList<>();
        for (CorrelationTier tier : CorrelationTier.values()) {
            boolean available = capabilities.provides(tier);
            tiers.add(new RequestProfileTierDto(tier.name(), available, capabilities.unavailableReason(tier)));
        }
        return tiers;
    }

    /**
     * Summed in microseconds, the resolution SQL Trace records: on a local database almost every statement
     * runs in well under a millisecond, so summing rounded milliseconds would report a request that spent
     * real time in the database as spending none.
     */
    private static RequestProfileTimingDto timing(
            HttpExchangeDto request, List<SqlTraceEntryDto> sql, List<RestClientTraceEntryDto> restCalls) {
        long sqlMicros =
                sql.stream().mapToLong(SqlTraceEntryDto::durationMicros).sum();
        double sqlMs = SqlDurations.millis(sqlMicros);
        Double sqlPercent = (request.durationMs() != null && request.durationMs() > 0)
                ? Math.round(10000.0 * sqlMs / request.durationMs()) / 100.0
                : null;
        long restMs = restCalls.stream()
                .mapToLong(RestClientTraceEntryDto::durationMillis)
                .sum();
        return new RequestProfileTimingDto(
                request.durationMs(), sqlMs, sql.size(), sqlPercent, restCalls.size(), restMs);
    }

    private static RequestProfileCacheAccessDto toCacheAccess(CacheActivityEvent event) {
        return new RequestProfileCacheAccessDto(
                event.timestampMillis(),
                event.managerName(),
                event.cacheName(),
                event.operation() == null ? null : event.operation().name(),
                event.keyHash(),
                event.thread());
    }

    private static boolean principalMatches(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    /** Parse an ISO-8601 instant to epoch millis, returning {@code 0} for null/blank/unparseable input. */
    private static long parseEpochMillis(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return 0L;
        }
        try {
            return Instant.parse(timestamp).toEpochMilli();
        } catch (DateTimeParseException ex) {
            return 0L;
        }
    }

    // --- Internal model ------------------------------------------------------------------------------

    /** The anchor being profiled, every anchor that could claim a child instead, and the adapter tiers. */
    private record Context(
            ProfileAnchor self,
            List<ProfileAnchor> anchors,
            TraceCorrelationIndex index,
            ProfileCapabilities capabilities,
            Map<String, ProfileAnchor> byRequestId,
            boolean carriesRequestId,
            long maxHandoffMillis,
            HandoffWindow.Starts handoffStarts) {}

    private enum Outcome {
        /** The child belongs to the profiled anchor. */
        OURS,
        /** The child belongs to another anchor. */
        OTHER,
        /** The profiled anchor and another anchor could equally claim the child. */
        AMBIGUOUS,
        /** This tier cannot decide; a weaker tier may. */
        UNDECIDED
    }

    private record Decision(Outcome outcome, CorrelationTier tier) {

        static final Decision OTHER = new Decision(Outcome.OTHER, null);
        static final Decision AMBIGUOUS = new Decision(Outcome.AMBIGUOUS, null);
        static final Decision UNDECIDED = new Decision(Outcome.UNDECIDED, null);

        static Decision ours(CorrelationTier tier) {
            return new Decision(Outcome.OURS, tier);
        }

        /** Whether this tier settled the child: it belongs to the profiled anchor or to another one. */
        boolean decided() {
            return outcome == Outcome.OURS || outcome == Outcome.OTHER;
        }

        /**
         * The weaker tier's decision, unless it could not decide: then an ambiguous trace-id match stays
         * ambiguous rather than becoming undecided.
         */
        Decision orElse(Decision weaker) {
            return weaker.outcome() == Outcome.UNDECIDED ? this : weaker;
        }
    }

    /** One child section: the children correlated to the profiled anchor, and the tier that matched each. */
    private static final class Section<T> {

        private final String type;
        private final ProfileEvidence.Source<?> source;
        private final List<Attributed<T>> children = new ArrayList<>();
        private CorrelationTier tier;
        private final boolean[] tiersUsed = new boolean[CorrelationTier.values().length];
        private int ambiguous;

        Section(String type, ProfileEvidence.Source<?> source) {
            this.type = type;
            this.source = source;
        }

        String type() {
            return type;
        }

        ProfileEvidence.Source<?> source() {
            return source;
        }

        List<T> records() {
            return children.stream().map(Attributed::record).toList();
        }

        List<CorrelationTier> childTiers() {
            return children.stream().map(Attributed::tier).toList();
        }

        CorrelationTier tier() {
            return tier;
        }

        boolean uses(CorrelationTier candidate) {
            return tiersUsed[candidate.ordinal()];
        }

        int ambiguous() {
            return ambiguous;
        }

        void ambiguous(int count) {
            ambiguous = count;
        }

        void add(T record, CorrelationTier matchedBy) {
            children.add(new Attributed<>(record, matchedBy));
            tiersUsed[matchedBy.ordinal()] = true;
            if (matchedBy.weakerThan(tier)) {
                tier = matchedBy;
            }
        }

        void accept(Decision decision, Supplier<T> record) {
            if (decision.outcome() == Outcome.OURS) {
                add(record.get(), decision.tier());
            } else if (decision.outcome() == Outcome.AMBIGUOUS) {
                ambiguous++;
            }
        }

        Section<T> sorted(ToLongFunction<T> timestamp) {
            children.sort(Comparator.comparingLong(child -> timestamp.applyAsLong(child.record())));
            return this;
        }

        private record Attributed<T>(T record, CorrelationTier tier) {}
    }
}
