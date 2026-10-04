package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.engine.retention.TieredCaptureBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
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

    EvidenceRing(int maxEvents, long maxBytes, int reservedSharePercent, LongSupplier externalBytes) {
        this.maxEvents = Math.max(1, maxEvents);
        this.maxBytes = Math.max(1, maxBytes);
        this.reservedCapacity = TieredCaptureBuffer.reservedCapacity(this.maxEvents, reservedSharePercent);
        this.externalBytes = externalBytes == null ? () -> 0L : externalBytes;
    }

    synchronized void add(JournalEntry entry) {
        if (entry.estimatedBytes() + externalBytes.getAsLong() > maxBytes) {
            rememberEvictedRequest(entry.event());
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

    /** Whether a request recorded with {@code traceId} was evicted, as far as the ring remembers. */
    synchronized boolean evictedARequestOf(String traceId) {
        return traceId != null && evictedRequestTraces.contains(traceId);
    }

    private int size() {
        return routine.size() + reserved.size();
    }

    /** Drops every retained event. Eviction counts are kept: they report events lost to the bounds since startup. */
    synchronized void clear() {
        routine.clear();
        reserved.clear();
        evictedRequestTraces.clear();
        retainedBytes = 0;
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

    /** The ring's counts at one instant. */
    synchronized Counts counts() {
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
                size(),
                retainedBytes,
                Math.min(reserved.size(), reservedCapacity),
                reservedCapacity,
                evictedByCount,
                evictedByBytes,
                lastBound,
                oldest);
    }

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
