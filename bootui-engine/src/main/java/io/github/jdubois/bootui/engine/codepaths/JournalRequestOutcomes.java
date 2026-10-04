package io.github.jdubois.bootui.engine.codepaths;

import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalEntry;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.sqltrace.RouteLabel;
import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Names the route and outcome of requests whose call trees settle ({@code docs/PLAN-v2.md} §5.14), from each request's
 * own {@code http} event in the runtime journal, as Live Activity and Runtime Insights name them, with the SQL, REST
 * client, cache, and AI calls each recorded and their code-paths stamps (M5-4c). Reads only the retained events, newest
 * first. Events are appended as they complete, so a request's calls, which end before its exchange does and after it
 * started, come after its exchange in that order: the read stops once every request asked for is named and it reaches
 * an event that <em>ended</em> a second before the oldest one started, however long that event ran. A request already
 * evicted, or without an exchange, is {@link RequestOutcome#UNKNOWN}, with whatever calls were read. A tree waiting for
 * its exchange is looked up again only in the events recorded since ({@link RequestOutcomeReader#exchangesAfter}).
 */
public final class JournalRequestOutcomes {

    private JournalRequestOutcomes() {}

    /**
     * @param journal the runtime journal, or {@code null}
     * @param declared the application's declared routes, which name a request's route when the framework recorded no
     *     template, or {@code null}
     */
    public static RequestOutcomeReader of(RuntimeJournal journal, Supplier<RouteTemplateResolver> declared) {
        return new Reader(journal, declared);
    }

    /** How long before the oldest request's start the read goes on, as clocks round: a request's calls end after it. */
    static final long STARTED_BEFORE_MILLIS = 1_000L;

    private static final class Reader implements RequestOutcomeReader {

        private final RuntimeJournal journal;
        private final Supplier<RouteTemplateResolver> declared;

        Reader(RuntimeJournal journal, Supplier<RouteTemplateResolver> declared) {
            this.journal = journal;
            this.declared = declared;
        }

        @Override
        public long watermark() {
            return journal == null ? NONE : journal.lastSequence();
        }

        @Override
        public Exchanges exchangesAfter(Set<String> requestIds, long after) {
            if (journal == null) {
                return null;
            }
            long watermark = after;
            Set<String> named = new HashSet<>();
            if (requestIds == null || requestIds.isEmpty()) {
                return new Exchanges(named, Math.max(watermark, journal.lastSequence()));
            }
            // Only the events recorded since the last look: a waiting tree costs no copy of the whole journal.
            for (JournalEntry entry : after == NONE ? journal.entries() : journal.entriesAfter(after)) {
                watermark = Math.max(watermark, entry.sequence());
                RuntimeEvent event = entry.event();
                if (event.source() == JournalSource.HTTP
                        && event.payload() instanceof HttpPayload
                        && event.requestId() != null
                        && requestIds.contains(event.requestId())) {
                    named.add(event.requestId());
                }
            }
            return new Exchanges(named, watermark);
        }

        @Override
        public Map<String, RequestOutcome> apply(Set<String> requestIds) {
            Map<String, RequestOutcome> outcomes = new HashMap<>();
            if (journal == null || requestIds == null || requestIds.isEmpty()) {
                return outcomes;
            }
            RouteTemplateResolver resolver;
            try {
                resolver = declared == null ? null : declared.get();
            } catch (RuntimeException ex) {
                resolver = null;
            }
            RouteTemplateResolver routes = resolver == null ? RouteTemplateResolver.empty() : resolver;
            Map<String, List<RequestOutcome.StampedCall>> calls = new HashMap<>();
            Map<String, long[]> unplaced = new HashMap<>();
            long oldest = Long.MAX_VALUE;
            for (JournalEntry entry : journal.entries()) {
                RuntimeEvent event = entry.event();
                // Appended at completion: an event that ended before the oldest request started, and everything older,
                // can belong to none of them, however long it ran.
                if (outcomes.size() == requestIds.size() && endMillis(event) < oldest - STARTED_BEFORE_MILLIS) {
                    break;
                }
                String requestId = event.requestId();
                if (requestId == null || !requestIds.contains(requestId)) {
                    continue;
                }
                if (event.source() == JournalSource.HTTP && event.payload() instanceof HttpPayload http) {
                    if (!outcomes.containsKey(requestId)) {
                        String route = RouteLabel.of(
                                        http.method(), http.path(), http.routeTemplate(), http.operation(), routes)
                                .id();
                        outcomes.put(requestId, new RequestOutcome(route, http.status(), http.status() >= 500));
                        oldest = Math.min(oldest, event.epochMillis());
                    }
                    continue;
                }
                int kind = CodePathStamps.kind(event.payload());
                if (kind < 0) {
                    continue;
                }
                long stamp = CodePathStamps.of(event.payload());
                long[] counts = unplaced.computeIfAbsent(requestId, id -> new long[4]);
                if (stamp == 0L) {
                    counts[0]++;
                    counts[1] += Math.max(0L, event.durationNanos());
                    continue;
                }
                if (!CodePathStamps.placed(stamp)) {
                    counts[2]++;
                    continue;
                }
                List<RequestOutcome.StampedCall> of = calls.computeIfAbsent(requestId, id -> new ArrayList<>());
                if (of.size() < RequestOutcome.MAX_CALLS) {
                    of.add(new RequestOutcome.StampedCall(kind, stamp, event.durationNanos()));
                } else {
                    counts[3]++;
                }
            }
            Set<String> withCalls = new HashSet<>(calls.keySet());
            withCalls.addAll(unplaced.keySet());
            for (String requestId : withCalls) {
                RequestOutcome named = outcomes.getOrDefault(requestId, RequestOutcome.UNKNOWN);
                long[] counts = unplaced.getOrDefault(requestId, new long[4]);
                outcomes.put(
                        requestId,
                        new RequestOutcome(
                                named.route(),
                                named.status(),
                                named.failed(),
                                calls.getOrDefault(requestId, List.of()),
                                new UnplacedCalls(counts[0], counts[1], counts[2], 0L, 0L, counts[3])));
            }
            return outcomes;
        }

        /** When {@code event} ended, in epoch milliseconds: its start, plus its duration when it has one. */
        private static long endMillis(RuntimeEvent event) {
            return event.epochMillis() + Math.max(0L, event.durationNanos()) / 1_000_000L;
        }
    }
}
