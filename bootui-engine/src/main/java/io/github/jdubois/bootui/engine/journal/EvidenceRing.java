package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.engine.correlation.ExecutionIds;
import io.github.jdubois.bootui.engine.retention.TieredCaptureBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * The journal's retained events: one array-backed ring for routine events and one for failed and slow events, bounded
 * together by a count and by estimated bytes ({@code docs/PLAN-v2.md} §5.2).
 *
 * <p>It evicts as {@link TieredCaptureBuffer} does: the oldest routine event first, while failed and slow events keep a
 * reserved share of the count and only compete by age beyond it. It evicts until both bounds hold, counting which bound
 * forced each eviction. An event too large to fit is counted as byte-evicted; newly interned dictionary strings may
 * still require evicting older evidence. Bytes that other
 * structures of the journal retain, such as the dictionary, count against the byte bound.</p>
 *
 * <p>Only the dispatcher adds; any thread may read. Every operation takes the ring's monitor.</p>
 */
final class EvidenceRing {

    /** The bound that forced an eviction. */
    enum Bound {
        COUNT,
        BYTES
    }

    private final int maxEvents;
    private final long maxBytes;
    private final int reservedCapacity;
    private final LongSupplier externalBytes;
    private final ArrayDeque<JournalEntry> routine = new ArrayDeque<>();
    private final ArrayDeque<JournalEntry> reserved = new ArrayDeque<>();
    private long retainedBytes;
    private long evictedByCount;
    private long evictedByBytes;
    private Bound lastBound;

    /** The most trace ids of evicted requests remembered. */
    static final int MAX_EVICTED_REQUEST_TRACES = 16_384;

    /**
     * The trace ids of the most recently evicted requests, so a reader never hands an AI call linked only by its trace
     * to another request sharing it when the request that made the call is gone ({@link AiCallOwners}).
     */
    private final LinkedHashSet<String> evictedRequestTraces = new LinkedHashSet<>();

    /**
     * The latest start, by the wall clock, of an event of a request, an execution, or a trace that the ring lost to
     * eviction, to the byte bound, or to a clear; {@link Long#MIN_VALUE} while it lost none. A unit of work that started
     * after it cannot have lost an event, since its events start no earlier than it does ({@link #lossHorizonMillis}).
     */
    private long lossHorizonMillis = Long.MIN_VALUE;

    /**
     * When the latest request whose HTTP event the ring lost ended, by the wall clock; {@link Long#MIN_VALUE} while it
     * lost none. A container logs a request's failure once its id is gone, so an error without an id written shortly
     * after may have been that lost request's.
     */
    private long lostRequestEndMillis = Long.MIN_VALUE;

    /**
     * Per source, the latest start of any event the ring lost, by the wall clock; {@link Long#MIN_VALUE} while it lost
     * none of that source. Unlike the loss horizon, it counts events of no unit of work too, such as a log written
     * outside any request.
     */
    private final long[] lostBySource = lostBySource();

    /** The most thread names of requests that lost events remembered. */
    static final int MAX_LOST_REQUEST_THREADS = 1_024;

    /**
     * The threads that ran the most recent requests the ring lost events of, so only an error without a request id
     * written on one of them is taken as possibly that request's, never a startup or pool thread's own error. Kept
     * across a clear, which loses events too.
     */
    private final LinkedHashSet<String> lostRequestThreads = new LinkedHashSet<>();

    EvidenceRing(int maxEvents, long maxBytes, int reservedSharePercent, LongSupplier externalBytes) {
        this.maxEvents = Math.max(1, maxEvents);
        this.maxBytes = Math.max(1, maxBytes);
        this.reservedCapacity = TieredCaptureBuffer.reservedCapacity(this.maxEvents, reservedSharePercent);
        this.externalBytes = externalBytes == null ? () -> 0L : externalBytes;
    }

    synchronized void add(JournalEntry entry) {
        if (entry.estimatedBytes() + externalBytes.getAsLong() > maxBytes) {
            rememberEvictedRequest(entry.event());
            lost(entry.event());
            lastBound = Bound.BYTES;
            evictedByBytes++;
            while (size() > 0 && retainedBytes + externalBytes.getAsLong() > maxBytes) {
                evict(Bound.BYTES);
            }
            return;
        }
        if (entry.event().failedOrSlow()) {
            reserved.addLast(entry);
        } else {
            routine.addLast(entry);
        }
        retainedBytes += entry.estimatedBytes();
        while (size() > 0) {
            Bound bound = size() > maxEvents
                    ? Bound.COUNT
                    : retainedBytes + externalBytes.getAsLong() > maxBytes ? Bound.BYTES : null;
            if (bound == null) {
                break;
            }
            evict(bound);
        }
    }

    private void evict(Bound bound) {
        JournalEntry evicted = evictOne();
        retainedBytes -= evicted.estimatedBytes();
        rememberEvictedRequest(evicted.event());
        lost(evicted.event());
        lastBound = bound;
        if (bound == Bound.COUNT) {
            evictedByCount++;
        } else {
            evictedByBytes++;
        }
    }

    private JournalEntry evictOne() {
        if (reserved.size() > reservedCapacity) {
            JournalEntry oldestRoutine = routine.peekFirst();
            JournalEntry oldestReserved = reserved.peekFirst();
            if (oldestRoutine == null || oldestReserved.sequence() < oldestRoutine.sequence()) {
                return reserved.pollFirst();
            }
            return routine.pollFirst();
        }
        return routine.isEmpty() ? reserved.pollFirst() : routine.pollFirst();
    }

    private void rememberEvictedRequest(RuntimeEvent event) {
        if (event.source() != JournalSource.HTTP || event.traceId() == null) {
            return;
        }
        evictedRequestTraces.remove(event.traceId());
        evictedRequestTraces.add(event.traceId());
        if (evictedRequestTraces.size() > MAX_EVICTED_REQUEST_TRACES) {
            evictedRequestTraces.remove(evictedRequestTraces.iterator().next());
        }
    }

    /** Moves the loss horizon past {@code event} when it belonged to a request, an execution, or a trace. */
    synchronized void lost(RuntimeEvent event) {
        if (event == null) {
            return;
        }
        long anyStarted = Math.min(event.epochMillis(), System.currentTimeMillis());
        int source = event.source().ordinal();
        if (anyStarted > lostBySource[source]) {
            lostBySource[source] = anyStarted;
        }
        if (event.requestId() == null && event.executionId() == null && event.traceId() == null) {
            return;
        }
        // Never past now: a span from a skewed clock, or a wall clock stepped back, would otherwise hide every request
        // until the restart.
        long started = Math.min(event.epochMillis(), System.currentTimeMillis());
        if (started > lossHorizonMillis) {
            lossHorizonMillis = started;
        }
        if (event.requestId() != null
                && event.thread() != null
                && !event.thread().isBlank()) {
            lostRequestThreads.remove(event.thread());
            lostRequestThreads.add(event.thread());
            if (lostRequestThreads.size() > MAX_LOST_REQUEST_THREADS) {
                lostRequestThreads.remove(lostRequestThreads.iterator().next());
            }
        }
        if (event.source() == JournalSource.HTTP && event.requestId() != null) {
            long ended = Math.min(
                    event.epochMillis() + Math.max(0, event.durationNanos()) / 1_000_000, System.currentTimeMillis());
            if (ended > lostRequestEndMillis) {
                lostRequestEndMillis = ended;
            }
        }
    }

    /** Whether a request the ring lost events of ran on {@code thread}, as far as the ring remembers. */
    synchronized boolean lostARequestOn(String thread) {
        return thread != null && lostRequestThreads.contains(thread);
    }

    /** When the latest request whose HTTP event the ring lost ended, or {@code null} while it lost none. */
    synchronized Long lostRequestEndMillis() {
        return lostRequestEndMillis == Long.MIN_VALUE ? null : lostRequestEndMillis;
    }

    /**
     * The latest start of an event of a unit of work the ring lost, or {@code null} while it lost none: a request or an
     * execution that started then or before may have lost some of its events, so it is incomplete evidence.
     */
    synchronized Long lossHorizonMillis() {
        return lossHorizonMillis == Long.MIN_VALUE ? null : lossHorizonMillis;
    }

    /** The latest start of an event of {@code source} the ring lost, or {@code null} while it lost none. */
    synchronized Long lostMillis(JournalSource source) {
        long lost = lostBySource[source.ordinal()];
        return lost == Long.MIN_VALUE ? null : lost;
    }

    private static long[] lostBySource() {
        long[] lost = new long[JournalSource.values().length];
        java.util.Arrays.fill(lost, Long.MIN_VALUE);
        return lost;
    }

    /** Whether a request recorded with {@code traceId} was evicted, as far as the ring remembers. */
    synchronized boolean evictedARequestOf(String traceId) {
        return traceId != null && evictedRequestTraces.contains(traceId);
    }

    synchronized List<String> lostRequestTraces() {
        return List.copyOf(evictedRequestTraces);
    }

    synchronized List<String> lostRequestThreads() {
        return List.copyOf(lostRequestThreads);
    }

    synchronized void durableCaptureLost(JournalEntry entry) {
        if (entry == null || entry.event() == null) {
            return;
        }
        rememberEvictedRequest(entry.event());
        lost(entry.event());
    }

    private int size() {
        return routine.size() + reserved.size();
    }

    /**
     * Drops every retained event. Eviction counts are kept: they report events lost to the bounds since startup. The
     * loss horizon moves past them, since a request still running loses the events it recorded before the clear.
     */
    synchronized void clear() {
        routine.forEach(entry -> lost(entry.event()));
        reserved.forEach(entry -> lost(entry.event()));
        routine.clear();
        reserved.clear();
        evictedRequestTraces.clear();
        retainedBytes = 0;
    }

    /**
     * Removes events after the durable capture has advanced through them, without treating their absence as new loss.
     * The capture may have explicitly shed some entries before advancing; {@link #durableCaptureLost(JournalEntry)}
     * records that loss first. The newest {@code keepNewest} events stay in memory, as do all events of a request or
     * execution whose completion anchor has not itself been processed through {@code sequence}.
     *
     * @return the number of entries removed
     */
    synchronized int offloadDurableThrough(long sequence, int keepNewest) {
        if (sequence <= 0 || size() == 0) {
            return 0;
        }
        List<JournalEntry> all = newestFirst();
        Set<String> completedRequests = new HashSet<>();
        Set<String> completedExecutions = new HashSet<>();
        Set<String> completedAsync = new HashSet<>();
        for (JournalEntry entry : all) {
            if (entry.sequence() > sequence) {
                continue;
            }
            RuntimeEvent event = entry.event();
            if (event.requestId() != null && event.source() == JournalSource.HTTP) {
                completedRequests.add(event.requestId());
            }
            if (event.requestId() == null && event.executionId() != null && opensExecution(event)) {
                completedExecutions.add(event.executionId());
            }
            if (event.payload() instanceof AsyncHandoffPayload handoff && handoff.executionId() != null) {
                completedAsync.add(handoff.executionId());
            }
        }
        Set<String> requestsWithOpenAsync = new HashSet<>();
        for (JournalEntry entry : all) {
            RuntimeEvent event = entry.event();
            if (event.requestId() != null
                    && ExecutionIds.isAsync(event.executionId())
                    && !completedAsync.contains(event.executionId())) {
                requestsWithOpenAsync.add(event.requestId());
            }
        }
        Set<Long> keptByAge = new HashSet<>();
        for (int i = 0; i < Math.min(keepNewest, all.size()); i++) {
            keptByAge.add(all.get(i).sequence());
        }
        int removed = 0;
        removed += removeOffloaded(
                routine,
                sequence,
                keptByAge,
                completedRequests,
                completedExecutions,
                completedAsync,
                requestsWithOpenAsync);
        removed += removeOffloaded(
                reserved,
                sequence,
                keptByAge,
                completedRequests,
                completedExecutions,
                completedAsync,
                requestsWithOpenAsync);
        return removed;
    }

    private int removeOffloaded(
            ArrayDeque<JournalEntry> entries,
            long through,
            Set<Long> recent,
            Set<String> completedRequests,
            Set<String> completedExecutions,
            Set<String> completedAsync,
            Set<String> requestsWithOpenAsync) {
        int removed = 0;
        for (Iterator<JournalEntry> iterator = entries.iterator(); iterator.hasNext(); ) {
            JournalEntry entry = iterator.next();
            if (entry.sequence() > through || recent.contains(entry.sequence())) {
                continue;
            }
            RuntimeEvent event = entry.event();
            boolean openRequest = event.requestId() != null && !completedRequests.contains(event.requestId());
            boolean openExecution = event.requestId() == null
                    && event.executionId() != null
                    && !ExecutionIds.isAsync(event.executionId())
                    && !completedExecutions.contains(event.executionId());
            boolean openAsync = event.executionId() != null
                    && ExecutionIds.isAsync(event.executionId())
                    && !completedAsync.contains(event.executionId());
            boolean requestHasOpenAsync =
                    event.requestId() != null && requestsWithOpenAsync.contains(event.requestId());
            boolean managedTask = ExecutionIds.isTask(event.executionId());
            if (openRequest || openExecution || openAsync || requestHasOpenAsync || managedTask) {
                continue;
            }
            iterator.remove();
            retainedBytes -= entry.estimatedBytes();
            removed++;
        }
        return removed;
    }

    private static boolean opensExecution(RuntimeEvent event) {
        return event.payload() instanceof ScheduledPayload
                || event.payload() instanceof MessagingPayload message && !message.sent()
                || event.payload() instanceof WebSocketPayload webSocket && webSocket.opensExecution();
    }

    /** The retained events, newest first, merged across both rings by sequence. */
    synchronized List<JournalEntry> newestFirst() {
        List<JournalEntry> merged = new ArrayList<>(size());
        Iterator<JournalEntry> routineIterator = routine.descendingIterator();
        Iterator<JournalEntry> reservedIterator = reserved.descendingIterator();
        JournalEntry nextRoutine = routineIterator.hasNext() ? routineIterator.next() : null;
        JournalEntry nextReserved = reservedIterator.hasNext() ? reservedIterator.next() : null;
        while (nextRoutine != null || nextReserved != null) {
            if (nextReserved == null || (nextRoutine != null && nextRoutine.sequence() > nextReserved.sequence())) {
                merged.add(nextRoutine);
                nextRoutine = routineIterator.hasNext() ? routineIterator.next() : null;
            } else {
                merged.add(nextReserved);
                nextReserved = reservedIterator.hasNext() ? reservedIterator.next() : null;
            }
        }
        return Collections.unmodifiableList(merged);
    }

    /** The retained events, oldest first, merged across both rings by sequence. */
    synchronized List<JournalEntry> oldestFirst() {
        List<JournalEntry> merged = new ArrayList<>(newestFirst());
        Collections.reverse(merged);
        return List.copyOf(merged);
    }

    /**
     * The retained events whose sequence is above {@code sequence}, newest first, merged across both rings: a reader
     * that already read up to {@code sequence} copies only what is new.
     */
    synchronized List<JournalEntry> newestFirstAfter(long sequence) {
        List<JournalEntry> merged = new ArrayList<>();
        Iterator<JournalEntry> routineIterator = routine.descendingIterator();
        Iterator<JournalEntry> reservedIterator = reserved.descendingIterator();
        JournalEntry nextRoutine = after(routineIterator, sequence);
        JournalEntry nextReserved = after(reservedIterator, sequence);
        while (nextRoutine != null || nextReserved != null) {
            if (nextReserved == null || (nextRoutine != null && nextRoutine.sequence() > nextReserved.sequence())) {
                merged.add(nextRoutine);
                nextRoutine = after(routineIterator, sequence);
            } else {
                merged.add(nextReserved);
                nextReserved = after(reservedIterator, sequence);
            }
        }
        return Collections.unmodifiableList(merged);
    }

    /** The iterator's next entry, newest first, while its sequence is above {@code sequence}; else {@code null}. */
    private static JournalEntry after(Iterator<JournalEntry> newestFirst, long sequence) {
        if (!newestFirst.hasNext()) {
            return null;
        }
        JournalEntry next = newestFirst.next();
        return next.sequence() > sequence ? next : null;
    }

    /** The ring's counts at one instant. */
    synchronized Counts counts() {
        return counts(size());
    }

    private Counts counts(int size) {
        JournalEntry oldestRoutine = routine.peekFirst();
        JournalEntry oldestReserved = reserved.peekFirst();
        Long oldest = null;
        if (oldestRoutine != null) {
            oldest = oldestRoutine.event().epochMillis();
        }
        if (oldestReserved != null && (oldest == null || oldestReserved.event().epochMillis() < oldest)) {
            oldest = oldestReserved.event().epochMillis();
        }
        return new Counts(
                size,
                retainedBytes,
                Math.min(reserved.size(), reservedCapacity),
                reservedCapacity,
                evictedByCount,
                evictedByBytes,
                lastBound,
                oldest);
    }

    /** The retained entries and matching bounds at one instant. */
    synchronized Snapshot snapshot() {
        return new Snapshot(newestFirst(), counts(size()));
    }

    record Snapshot(List<JournalEntry> entries, Counts counts) {}

    record Counts(
            int retained,
            long retainedBytes,
            int reserved,
            int reservedCapacity,
            long evictedByCount,
            long evictedByBytes,
            Bound lastBound,
            Long oldestRetainedEpochMillis) {}
}
