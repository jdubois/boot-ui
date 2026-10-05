package io.github.jdubois.bootui.agent.bridge;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The thread-activity sensor's tracker (PLAN-v2 §5.16, M5-5e): the threads a request started and the executors the
 * application created, held weakly until their request ends, their executor is shut down, or the collector reclaims
 * them, so the sensor can say which threads a request left running, which executors it left running, and which
 * executors were never shut down, without ever keeping a thread, an executor, or a class loader alive.
 *
 * <p>Entries are {@link WeakReference}s with primitive fields only, chained by identity hash, at most {@value
 * #MAX_ENTRIES}; a full table leaves new ones untracked, counted. One {@link ReentrantLock}, never a monitor, so a
 * virtual thread never pins its carrier here; it is the innermost lock taken on these paths ({@code Thread.start} holds
 * the started thread's monitor on JDK 17), and nothing under it calls into a thread's monitor or application code:
 * {@link Thread#isAlive()} is all it asks a tracked thread. A request's end is checked once: anything alive or not shut
 * down then was so when its response was complete, so a report is never false. A thread or executor of a request
 * already ended ({@value #ENDED} recent ends are remembered) is not waited for; one whose request's end never comes
 * stops waiting after {@value #WAIT_MILLIS} ms, counted. Not for the application's own use: the bridge serializes
 * nothing else through it.
 */
final class ThreadTracker {

    static final int MAX_ENTRIES = 1_024;
    static final int BUCKETS = 2_048;
    static final int ENDED = 256;
    static final long WAIT_MILLIS = 600_000L;

    /** What a check reports about an entry. */
    static final int THREAD_LEFT_RUNNING = 1;

    static final int EXECUTOR_LEFT_RUNNING = 2;
    static final int EXECUTOR_SHUT_DOWN = 3;
    static final int EXECUTOR_RECLAIMED = 4;

    private final ReentrantLock lock = new ReentrantLock();
    private final ReferenceQueue<Object> queue = new ReferenceQueue<Object>();
    private final Entry[] buckets = new Entry[BUCKETS];
    private final Map<Long, List<Entry>> waitingByRequest = new HashMap<Long, List<Entry>>();
    private final ArrayDeque<Entry> waitingOrder = new ArrayDeque<Entry>();
    private final long[] ended = new long[ENDED];
    private int endedNext;
    /** Read without the lock by the fast paths, which may miss an entry another thread is adding. */
    private volatile int size;
    private long generation = Long.MIN_VALUE;

    /** Entries waiting for their request's end: read without the lock by {@link #requestEnded}. */
    private volatile int waiting;

    final LongAdder untracked = new LongAdder();
    final LongAdder unresolved = new LongAdder();
    final LongAdder afterEnd = new LongAdder();

    /**
     * What the sensor recorded of a tracked thread or executor, copied into what a check reports: its record's owner,
     * target, stamp, frames, and flags, so a later record lands on the same row as its creation's.
     */
    static final class Entry extends WeakReference<Object> {

        final int hash;
        final boolean thread;
        final long generation;
        final long request;
        final long execution;
        final int executionKind;
        final int threadKind;
        final int threadName;
        final int target;
        final long stamp;
        final long frames;
        final long flags;
        final long createdNanos;
        final long createdMillis;
        Entry next;
        boolean waitingForEnd;
        long waitingSince;
        /** What a check found, set when the entry is reported. */
        int reported;

        long reportedNanos;

        Entry(
                Object referent,
                ReferenceQueue<Object> queue,
                boolean thread,
                long generation,
                long request,
                long execution,
                int executionKind,
                int threadKind,
                int threadName,
                int target,
                long stamp,
                long frames,
                long flags,
                long createdNanos,
                long createdMillis) {
            super(referent, queue);
            this.hash = System.identityHashCode(referent);
            this.thread = thread;
            this.generation = generation;
            this.request = request;
            this.execution = execution;
            this.executionKind = executionKind;
            this.threadKind = threadKind;
            this.threadName = threadName;
            this.target = target;
            this.stamp = stamp;
            this.frames = frames;
            this.flags = flags;
            this.createdNanos = createdNanos;
            this.createdMillis = createdMillis;
        }

        /** A copy without its referent, for what a check reports: never keeps the thread or executor. */
        Entry report(int what, long nowNanos) {
            Entry copy = new Entry(
                    null,
                    null,
                    thread,
                    generation,
                    request,
                    execution,
                    executionKind,
                    threadKind,
                    threadName,
                    target,
                    stamp,
                    frames,
                    flags,
                    createdNanos,
                    createdMillis);
            copy.reported = what;
            copy.reportedNanos = nowNanos;
            return copy;
        }
    }

    /**
     * Tracks {@code referent}, a thread a request started or an executor, under {@code generation}: it waits for its
     * request's end when {@code request} is not 0 and that request has not ended yet. Returns whether it is tracked.
     * Never throws.
     */
    boolean track(
            Object referent,
            boolean thread,
            long entryGeneration,
            long request,
            long execution,
            int executionKind,
            int threadKind,
            int threadName,
            int target,
            long stamp,
            long frames,
            long flags,
            long createdMillis,
            List<Entry> reports) {
        if (referent == null) {
            return false;
        }
        long now = System.nanoTime();
        lock.lock();
        try {
            reset(entryGeneration);
            expunge(reports, now);
            timeOut(createdMillis);
            boolean wait = request != 0L && !endedRecently(request);
            if (request != 0L && !wait) {
                afterEnd.increment();
            }
            if (thread && !wait) {
                // A thread is tracked only to tell, at its request's end, whether it still runs.
                return false;
            }
            if (size >= MAX_ENTRIES) {
                untracked.increment();
                return false;
            }
            Entry entry = new Entry(
                    referent,
                    queue,
                    thread,
                    entryGeneration,
                    request,
                    execution,
                    executionKind,
                    threadKind,
                    threadName,
                    target,
                    stamp,
                    frames,
                    flags,
                    now,
                    createdMillis);
            int bucket = entry.hash & (BUCKETS - 1);
            entry.next = buckets[bucket];
            buckets[bucket] = entry;
            size++;
            if (wait) {
                entry.waitingForEnd = true;
                entry.waitingSince = createdMillis;
                List<Entry> list = waitingByRequest.get(Long.valueOf(request));
                if (list == null) {
                    list = new ArrayList<Entry>(2);
                    waitingByRequest.put(Long.valueOf(request), list);
                }
                list.add(entry);
                waitingOrder.addLast(entry);
                waiting++;
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * An executor is being shut down: its entry, reported {@link #EXECUTOR_SHUT_DOWN}, or {@link #EXECUTOR_RECLAIMED}
     * when {@code reclaimed} (a cleaner or finalizer shuts it down because nothing references it), is added to {@code
     * reports} and forgotten; nothing when it is not tracked. Never throws.
     */
    void shutdown(Object executor, boolean reclaimed, long shutdownGeneration, List<Entry> reports) {
        if (executor == null || size == 0) {
            return;
        }
        long now = System.nanoTime();
        lock.lock();
        try {
            if (shutdownGeneration != generation) {
                return;
            }
            int hash = System.identityHashCode(executor);
            int bucket = hash & (BUCKETS - 1);
            Entry previous = null;
            for (Entry entry = buckets[bucket]; entry != null; previous = entry, entry = entry.next) {
                if (!entry.thread && entry.get() == executor) {
                    unlink(bucket, previous, entry);
                    reports.add(entry.report(reclaimed ? EXECUTOR_RECLAIMED : EXECUTOR_SHUT_DOWN, now));
                    return;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * A request ended, its response complete: each thread it started still alive is reported {@link
     * #THREAD_LEFT_RUNNING} and forgotten, each executor it created not shut down {@link #EXECUTOR_LEFT_RUNNING} and kept
     * for its shutdown. Returns at once when no entry waits. Never throws.
     */
    void requestEnded(long endGeneration, long request, List<Entry> reports) {
        if (request == 0L) {
            return;
        }
        long now = System.nanoTime();
        lock.lock();
        try {
            if (endGeneration != generation) {
                return;
            }
            ended[endedNext] = request;
            endedNext = (endedNext + 1) & (ENDED - 1);
            if (waiting == 0) {
                return;
            }
            List<Entry> list = waitingByRequest.remove(Long.valueOf(request));
            if (list == null) {
                return;
            }
            for (Entry entry : list) {
                if (!entry.waitingForEnd) {
                    continue;
                }
                entry.waitingForEnd = false;
                waiting--;
                Object referent = entry.get();
                if (entry.thread) {
                    if (referent instanceof Thread && ((Thread) referent).isAlive()) {
                        reports.add(entry.report(THREAD_LEFT_RUNNING, now));
                    }
                    remove(entry);
                } else if (referent != null) {
                    reports.add(entry.report(EXECUTOR_LEFT_RUNNING, now));
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /** Executors the collector reclaimed without a shutdown since the last call, reported. Never throws. */
    void expunge(List<Entry> reports) {
        if (size == 0) {
            return;
        }
        long now = System.nanoTime();
        lock.lock();
        try {
            expunge(reports, now);
        } finally {
            lock.unlock();
        }
    }

    /** Whether any entry waits for its request's end: read without the lock. */
    boolean anyWaiting() {
        return waiting > 0;
    }

    int size() {
        return size;
    }

    int waitingCount() {
        return waiting;
    }

    /** Forgets everything: a new claim generation, or tests. */
    void clear() {
        lock.lock();
        try {
            reset(Long.MIN_VALUE);
            generation = Long.MIN_VALUE;
        } finally {
            lock.unlock();
        }
    }

    // ---- under the lock --------------------------------------------------------------------------------------------

    private void reset(long next) {
        if (next == generation) {
            return;
        }
        for (int i = 0; i < BUCKETS; i++) {
            for (Entry entry = buckets[i]; entry != null; entry = entry.next) {
                entry.clear();
            }
            buckets[i] = null;
        }
        while (queue.poll() != null) {
            // Entries of the previous generation: dropped.
        }
        waitingByRequest.clear();
        waitingOrder.clear();
        java.util.Arrays.fill(ended, 0L);
        endedNext = 0;
        size = 0;
        waiting = 0;
        generation = next;
    }

    private boolean endedRecently(long request) {
        for (int i = 0; i < ENDED; i++) {
            if (ended[i] == request) {
                return true;
            }
        }
        return false;
    }

    private void expunge(List<Entry> reports, long now) {
        java.lang.ref.Reference<?> reference;
        while ((reference = queue.poll()) != null) {
            Entry entry = (Entry) reference;
            if (remove(entry) && !entry.thread && entry.generation == generation && reports != null) {
                reports.add(entry.report(EXECUTOR_RECLAIMED, now));
            }
        }
    }

    /** Entries waiting longer than {@value #WAIT_MILLIS} ms stop waiting: their request's end never came. */
    private void timeOut(long nowMillis) {
        if (waiting == 0 && waitingOrder.size() > 0) {
            waitingOrder.clear();
            return;
        }
        if (waitingOrder.size() > 2 * MAX_ENTRIES) {
            // Entries already resolved behind one still waiting: never more than the live ones, twice.
            Iterator<Entry> resolved = waitingOrder.iterator();
            while (resolved.hasNext()) {
                if (!resolved.next().waitingForEnd) {
                    resolved.remove();
                }
            }
        }
        Iterator<Entry> iterator = waitingOrder.iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (!entry.waitingForEnd) {
                iterator.remove();
                continue;
            }
            if (nowMillis - entry.waitingSince < WAIT_MILLIS) {
                break;
            }
            iterator.remove();
            stopWaiting(entry);
            unresolved.increment();
            if (entry.thread) {
                remove(entry);
            }
        }
    }

    private void stopWaiting(Entry entry) {
        if (!entry.waitingForEnd) {
            return;
        }
        entry.waitingForEnd = false;
        waiting--;
        List<Entry> list = waitingByRequest.get(Long.valueOf(entry.request));
        if (list != null) {
            list.remove(entry);
            if (list.isEmpty()) {
                waitingByRequest.remove(Long.valueOf(entry.request));
            }
        }
    }

    /** Unlinks {@code entry} from its bucket; returns whether it was there. */
    private boolean remove(Entry entry) {
        int bucket = entry.hash & (BUCKETS - 1);
        Entry previous = null;
        for (Entry current = buckets[bucket]; current != null; previous = current, current = current.next) {
            if (current == entry) {
                unlink(bucket, previous, entry);
                return true;
            }
        }
        return false;
    }

    private void unlink(int bucket, Entry previous, Entry entry) {
        if (previous == null) {
            buckets[bucket] = entry.next;
        } else {
            previous.next = entry.next;
        }
        entry.next = null;
        size--;
        stopWaiting(entry);
        entry.clear();
    }
}
