package io.github.jdubois.bootui.engine.retention;

import io.github.jdubois.bootui.core.dto.CaptureRetentionDto;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Bounded, thread-safe capture buffer that keeps failure evidence longer than routine evidence.
 *
 * <p>The buffer holds at most {@link #capacity()} records. A bounded share of that capacity,
 * {@link #reservedCapacity()}, is reserved for records the caller flags as failed or slow when it adds them. When the
 * buffer is full, the oldest record outside the reserved share is evicted: a routine record, or a flagged record that
 * the {@code reservedCapacity} newer flagged records have pushed out of the reservation. Flagged records therefore
 * never displace each other while the reserved share has room, and a flood of routine records cannot evict the most
 * recent {@code reservedCapacity} flagged ones. The reservation is carved out of the capacity, never added to it, and
 * it is capped at {@code capacity - 1} so the newest record is always retained, whatever its classification.</p>
 *
 * <p>Classification happens once, at insertion, from data already on the record, so a read never re-evaluates it.
 * Reads return the records newest-first (or oldest-first) across both tiers, ordered by insertion.</p>
 *
 * <p>Every operation takes the buffer's own monitor, so writers on any number of threads and readers never observe a
 * half-applied eviction, and a {@link Snapshot} reconciles its records with its counts.</p>
 *
 * @param <T> the captured record type
 */
public final class TieredCaptureBuffer<T> {

    /** Share of each buffer's capacity reserved for failed or slow records unless configured otherwise. */
    public static final int DEFAULT_RESERVED_SHARE_PERCENT = 25;

    private final int capacity;
    private final int reservedCapacity;
    private final ArrayDeque<Node<T>> routine = new ArrayDeque<>();
    private final ArrayDeque<Node<T>> flagged = new ArrayDeque<>();
    private long sequence;
    private long evicted;

    /**
     * @param capacity maximum retained records, clamped to at least {@code 1}
     * @param reservedSharePercent share of the capacity reserved for flagged records, clamped to {@code 0..100};
     *     {@code 0} disables the reservation so the buffer evicts strictly oldest first
     */
    public TieredCaptureBuffer(int capacity, int reservedSharePercent) {
        this.capacity = Math.max(1, capacity);
        this.reservedCapacity = reservedCapacity(this.capacity, reservedSharePercent);
    }

    /**
     * The number of records reserved for failed or slow records in a buffer of {@code capacity}: the configured share,
     * rounded down, and never the whole buffer, so routine traffic always keeps at least one slot.
     */
    public static int reservedCapacity(int capacity, int reservedSharePercent) {
        int bounded = Math.max(1, capacity);
        int share = Math.min(100, Math.max(0, reservedSharePercent));
        long reserved = (long) bounded * share / 100L;
        return (int) Math.min(reserved, bounded - 1L);
    }

    public int capacity() {
        return capacity;
    }

    public int reservedCapacity() {
        return reservedCapacity;
    }

    /**
     * Adds a record, evicting per the class contract when the buffer is full. A {@code null} record is ignored.
     *
     * @param value the record to retain
     * @param failedOrSlow whether the record is eligible for the reserved share
     */
    public synchronized void add(T value, boolean failedOrSlow) {
        if (value == null) {
            return;
        }
        Node<T> node = new Node<>(++sequence, value);
        if (failedOrSlow) {
            flagged.addLast(node);
        } else {
            routine.addLast(node);
        }
        while (routine.size() + flagged.size() > capacity) {
            evictOne();
            evicted++;
        }
    }

    private void evictOne() {
        if (flagged.size() > reservedCapacity) {
            // The oldest flagged record is outside the reservation, so it competes with routine records by age.
            Node<T> oldestRoutine = routine.peekFirst();
            Node<T> oldestFlagged = flagged.peekFirst();
            if (oldestRoutine == null || oldestFlagged.sequence() < oldestRoutine.sequence()) {
                flagged.pollFirst();
            } else {
                routine.pollFirst();
            }
        } else if (!routine.isEmpty()) {
            routine.pollFirst();
        } else {
            flagged.pollFirst();
        }
    }

    /** Drops every retained record. The eviction count is kept: it reports records lost to capacity since startup. */
    public synchronized void clear() {
        routine.clear();
        flagged.clear();
    }

    public synchronized int size() {
        return routine.size() + flagged.size();
    }

    /** Records dropped since startup because the buffer was full. */
    public synchronized long evicted() {
        return evicted;
    }

    /** The retained records, newest first. */
    public List<T> newestFirst() {
        return snapshot().newestFirst();
    }

    /** The retained records, oldest first. */
    public List<T> oldestFirst() {
        return snapshot().oldestFirst();
    }

    /** An atomic view of the retained records and the counts that describe them. */
    public synchronized Snapshot<T> snapshot() {
        List<T> newestFirst = new ArrayList<>(routine.size() + flagged.size());
        Iterator<Node<T>> routineIterator = routine.descendingIterator();
        Iterator<Node<T>> flaggedIterator = flagged.descendingIterator();
        Node<T> nextRoutine = routineIterator.hasNext() ? routineIterator.next() : null;
        Node<T> nextFlagged = flaggedIterator.hasNext() ? flaggedIterator.next() : null;
        while (nextRoutine != null || nextFlagged != null) {
            if (nextFlagged == null || (nextRoutine != null && nextRoutine.sequence() > nextFlagged.sequence())) {
                newestFirst.add(nextRoutine.value());
                nextRoutine = routineIterator.hasNext() ? routineIterator.next() : null;
            } else {
                newestFirst.add(nextFlagged.value());
                nextFlagged = flaggedIterator.hasNext() ? flaggedIterator.next() : null;
            }
        }
        return new Snapshot<>(
                Collections.unmodifiableList(newestFirst),
                capacity,
                reservedCapacity,
                Math.min(flagged.size(), reservedCapacity),
                evicted);
    }

    /**
     * The retained records at one instant, with the counts that describe them.
     *
     * @param newestFirst the retained records, newest first
     * @param capacity the buffer's capacity
     * @param reservedCapacity the part of that capacity reserved for failed or slow records
     * @param reserved failed or slow records currently held in the reserved share
     * @param evicted records dropped since startup because the buffer was full
     * @param <T> the captured record type
     */
    public record Snapshot<T>(List<T> newestFirst, int capacity, int reservedCapacity, int reserved, long evicted) {

        public int retained() {
            return newestFirst.size();
        }

        /** The same records, oldest first. */
        public List<T> oldestFirst() {
            List<T> oldestFirst = new ArrayList<>(newestFirst);
            Collections.reverse(oldestFirst);
            return oldestFirst;
        }

        /**
         * The public retention counts of a BootUI-owned buffer.
         *
         * @param slowThresholdMillis the buffer's slow threshold, or {@code 0} when slow classification is disabled
         */
        public CaptureRetentionDto retention(long slowThresholdMillis) {
            return new CaptureRetentionDto(
                    false,
                    capacity,
                    reservedCapacity,
                    retained(),
                    reserved,
                    evicted,
                    Math.max(0L, slowThresholdMillis));
        }
    }

    private record Node<T>(long sequence, T value) {}
}
