package io.github.jdubois.bootui.engine.journal;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * The runtime journal's bounded FIFO queue for one recording generation ({@code docs/PLAN-v2.md} §5.2, §8): application
 * threads offer events, and the dispatcher takes them in the order they were offered.
 *
 * <p>An offer takes one lock acquisition (M4-18d): it checks the bound that applies to the event, a lower one for
 * routine events so the last share of the queue is reserved for failed or slow events, and inserts it, atomically.
 * <b>Clear recording</b> replaces the journal's queue and then detaches this one in constant time under the same lock,
 * so an offer either lands before the detach, and is cleared with the recording, or sees the queue detached and is
 * retried on the replacement. An accepted event is counted under the same lock, before the dispatcher can take it, so
 * the journal never counts an event processed before it counts it accepted.</p>
 */
final class JournalQueue {

    /** {@link #offer} took the event. */
    static final int ACCEPTED = 0;

    /** {@link #offer} found no room for the event under the bound that applies to it. */
    static final int FULL = 1;

    /** {@link #offer} found the queue detached by a clear: the event belongs on the replacement. */
    static final int DETACHED = 2;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition filling = lock.newCondition();
    private final int routineLimit;
    private final int wakeAt;
    private final Runnable beforeOffer;
    private final IntConsumer onAccepted;
    private final boolean refusing;

    private RuntimeEvent[] items;
    private int takeIndex;
    private int putIndex;
    private int count;
    private boolean detached;

    /**
     * @param capacity the most events queued at once
     * @param routineLimit the depth from which only failed or slow events are taken
     * @param beforeOffer a test hook run with the lock held, before the event is checked, or {@code null}
     * @param onAccepted told the source ordinal of each event taken, with the lock held, or {@code null}
     */
    JournalQueue(int capacity, int routineLimit, Runnable beforeOffer, IntConsumer onAccepted) {
        this(capacity, routineLimit, beforeOffer, onAccepted, false);
    }

    private JournalQueue(
            int capacity, int routineLimit, Runnable beforeOffer, IntConsumer onAccepted, boolean refusing) {
        this.items = new RuntimeEvent[Math.max(1, capacity)];
        this.routineLimit = routineLimit;
        this.wakeAt = Math.max(1, routineLimit / 2);
        this.beforeOffer = beforeOffer;
        this.onAccepted = onAccepted;
        this.refusing = refusing;
    }

    /**
     * The queue of a closed journal: it refuses every event as {@link #FULL}, so an event offered after the run ended is
     * counted as dropped rather than accepted and never processed, and it is never detached.
     */
    static JournalQueue closed() {
        return new JournalQueue(1, 0, null, null, true);
    }

    /** Whether this is a closed journal's queue, which a clear leaves in place. */
    boolean refusing() {
        return refusing;
    }

    /**
     * Offers {@code event} without waiting for room: {@link #ACCEPTED}, {@link #FULL}, or {@link #DETACHED}. The bound
     * check, the insert, and the count of the accepted event happen under one lock acquisition, so routine events never
     * overshoot their share.
     */
    int offer(RuntimeEvent event, boolean failedOrSlow) {
        ReentrantLock lock = this.lock;
        lock.lock();
        try {
            if (beforeOffer != null) {
                beforeOffer.run();
            }
            if (detached) {
                return DETACHED;
            }
            if (refusing || count >= (failedOrSlow ? items.length : routineLimit)) {
                return FULL;
            }
            // Counted first: a counter failing, as on a stack overflow, then leaves nothing queued uncounted.
            if (onAccepted != null) {
                onAccepted.accept(event.source().ordinal());
            }
            items[putIndex] = event;
            putIndex = putIndex + 1 == items.length ? 0 : putIndex + 1;
            count++;
            notEmpty.signal();
            if (count >= wakeAt) {
                filling.signal();
            }
            return ACCEPTED;
        } finally {
            lock.unlock();
        }
    }

    /**
     * The oldest event, waiting up to {@code timeout} for one; {@code null} when none came, or at once when the queue is
     * or becomes detached, so a dispatcher waiting on it moves to the replacement.
     */
    RuntimeEvent poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (detached || nanos <= 0L) {
                    return null;
                }
                nanos = notEmpty.awaitNanos(nanos);
            }
            return take();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Waits up to {@code nanos} while the queue holds less than half its routine share, so a dispatcher pausing between
     * batches lets events gather but resumes as soon as a burst fills the queue. Returns at once when the queue already
     * holds that many or is detached. Unlike a bare park, no other wait of the dispatcher can consume the offer's
     * wake-up, since it is a condition of this queue's own lock.
     */
    void awaitFilling(long nanos) throws InterruptedException {
        ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (count < wakeAt && !detached && nanos > 0L) {
                nanos = filling.awaitNanos(nanos);
            }
        } finally {
            lock.unlock();
        }
    }

    /** Moves up to {@code max} of the oldest events to {@code batch}, in order; returns how many. */
    int drainTo(List<RuntimeEvent> batch, int max) {
        ReentrantLock lock = this.lock;
        lock.lock();
        try {
            int moved = Math.min(max, count);
            for (int i = 0; i < moved; i++) {
                batch.add(take());
            }
            return moved;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Detaches this queue for a clear, in constant time: it takes no more events, drops those it holds, and wakes a
     * dispatcher waiting on it. Returns what it dropped, to be visited after the lock is released.
     */
    Detached detach() {
        ReentrantLock lock = this.lock;
        lock.lock();
        try {
            if (detached || refusing) {
                return Detached.NONE;
            }
            detached = true;
            Detached dropped = new Detached(items, takeIndex, count);
            items = new RuntimeEvent[0];
            takeIndex = 0;
            putIndex = 0;
            count = 0;
            notEmpty.signalAll();
            filling.signalAll();
            return dropped;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Seals this queue as the journal closes: it takes no more events, an offer that finds it sealed moves to the
     * closed journal's queue, and the events it holds stay to be drained.
     */
    void seal() {
        ReentrantLock lock = this.lock;
        lock.lock();
        try {
            detached = true;
            notEmpty.signalAll();
            filling.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * The events a detach dropped: the detached ring buffer, which no offer or take touches any more, where its oldest
     * event was, and how many it held.
     */
    record Detached(RuntimeEvent[] items, int takeIndex, int count) {

        static final Detached NONE = new Detached(new RuntimeEvent[0], 0, 0);

        /** Visits each dropped event, oldest first. */
        void forEach(Consumer<RuntimeEvent> action) {
            for (int i = 0; i < count; i++) {
                RuntimeEvent event = items[(takeIndex + i) % items.length];
                if (event != null) {
                    action.accept(event);
                }
            }
        }
    }

    /** How many events are queued now. */
    int size() {
        ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return count;
        } finally {
            lock.unlock();
        }
    }

    private RuntimeEvent take() {
        RuntimeEvent event = items[takeIndex];
        items[takeIndex] = null;
        takeIndex = takeIndex + 1 == items.length ? 0 : takeIndex + 1;
        count--;
        return event;
    }
}
