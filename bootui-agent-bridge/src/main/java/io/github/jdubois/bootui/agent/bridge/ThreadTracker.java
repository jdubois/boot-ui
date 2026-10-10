package io.github.jdubois.bootui.agent.bridge;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The thread-activity sensor's tracker (PLAN-v2 §5.16, M5-5e): the threads a request's application code started and the
 * executors it created, held weakly until their request ended, their executor was shut down, or the collector reclaimed
 * them, so the sensor can say which threads and executors a request left running, without ever keeping a thread, an
 * executor, or a class loader alive.
 *
 * <p>Entries are {@link WeakReference}s with primitive fields only, chained by identity hash, at most {@value
 * #MAX_ENTRIES} threads and as many executors, so executors never shut down never stop threads from being tracked; a
 * full kind leaves new ones untracked, counted. One {@link ReentrantLock}, never a monitor, so a
 * virtual thread never pins its carrier here, taken only on rare paths (a tracked start or creation, a shutdown of a
 * tracked executor) and by the drain thread; it is the innermost lock on these paths ({@code Thread.start} holds the
 * started thread's monitor on JDK 17), and nothing under it calls into a thread's monitor or application code: {@link
 * Thread#isAlive()} is all it asks a tracked thread.
 *
 * <p><b>Request ends.</b> An adapter's request end ({@link #ended}) takes no lock, and returns after one volatile read
 * while no entry waits for a request's end: otherwise it writes the request and the time into a lock-free ring of
 * {@value #ENDS} entries, which the drain thread reads ({@link #processEnds}), {@value #ENDS_PER_HOLD} at a time, once each end is
 * at least the grace period old, so a thread still unwinding as the response completes is not reported. Only then does
 * it ask each thread the request started before its end whether it is still alive: anything alive or not shut down
 * then, after the grace, was so when the response was complete, so a report is never false. The {@value #ENDED}
 * latest ended requests are remembered, so a thread one of them starts afterwards is not waited for; a waiting entry
 * whose request's end never came stops waiting after {@value #WAIT_MILLIS} ms, counted.
 */
final class ThreadTracker {

    /** Entries at most, of each kind: threads waiting for their request, and executors, apart. */
    static final int MAX_ENTRIES = 1_024;

    static final int BUCKETS = 4_096;

    /** Ends read under one hold of the lock, which a tracked start contends on: the rest at the next hold. */
    static final int ENDS_PER_HOLD = 256;

    static final int ENDS = 4_096;
    static final int ENDED = 1_024;
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
    private final LinkedHashMap<Long, Boolean> ended = new LinkedHashMap<Long, Boolean>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
            return size() > ENDED;
        }
    };

    /** The lock-free ring of request ends: written by any thread, read by the drain thread only. */
    private final AtomicLongArray endRequests = new AtomicLongArray(ENDS);

    private final AtomicLongArray endNanos = new AtomicLongArray(ENDS);
    private final AtomicLongArray endSequences = new AtomicLongArray(ENDS);
    private final AtomicLong endsWritten = new AtomicLong();
    private long endsRead;

    /** Read without the lock by the fast paths, which may miss an entry another thread is adding. */
    private volatile int size;

    /** The thread entries and the executor entries, each bounded by {@value #MAX_ENTRIES}. */
    private int threads;

    private int executors;

    private long generation = Long.MIN_VALUE;

    /** Entries waiting for their request's end: read without the lock. */
    private volatile int waiting;

    final LongAdder untracked = new LongAdder();
    final LongAdder unresolved = new LongAdder();
    final LongAdder afterEnd = new LongAdder();
    final LongAdder endsLost = new LongAdder();
    final LongAdder endsChecked = new LongAdder();
    /** Threads and executors still waiting for their request's end when the sensor was disabled or released. */
    final LongAdder dropped = new LongAdder();

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
        final int detail;
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
                int detail,
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
            this.detail = detail;
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
                    detail,
                    createdNanos,
                    createdMillis);
            copy.reported = what;
            copy.reportedNanos = nowNanos;
            return copy;
        }
    }

    /**
     * Tracks {@code referent}, a thread a request's application code started or an executor a request or an execution
     * created, under {@code generation}: it waits for its request's end when {@code request} is not 0 and that request
     * has not ended yet. A thread is tracked only while it waits. Returns whether it is tracked. Never throws.
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
            int detail,
            long createdMillis,
            List<Entry> reports) {
        if (referent == null) {
            return false;
        }
        long now = System.nanoTime();
        lock.lock();
        try {
            if (!reset(entryGeneration)) {
                return false;
            }
            expunge(reports, now);
            timeOut(createdMillis);
            boolean wait = request != 0L && !ended.containsKey(Long.valueOf(request));
            if (request != 0L && !wait) {
                afterEnd.increment();
            }
            if (thread && !wait) {
                return false;
            }
            if ((thread ? threads : executors) >= MAX_ENTRIES) {
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
                    detail,
                    now,
                    createdMillis);
            int bucket = entry.hash & (BUCKETS - 1);
            entry.next = buckets[bucket];
            buckets[bucket] = entry;
            size++;
            if (thread) {
                threads++;
            } else {
                executors++;
            }
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
     * when {@code reclaimed} (a cleaner or a finalizer shuts it down because nothing references it), is added to {@code
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
     * A request ended, its response complete: written into the ring, without a lock, for the drain thread. Request ids
     * are random 64-bit values, so an end of an earlier generation read under a later one names no request of it.
     * Never throws.
     */
    void ended(long request) {
        if (request == 0L || waiting == 0) {
            // Nothing waits: no request's end matters, so none is written. A thread one of these requests starts later
            // is waited for until its timeout, counted unresolved.
            return;
        }
        long sequence = endsWritten.getAndIncrement();
        int slot = (int) (sequence & (ENDS - 1));
        endSequences.set(slot, -1L);
        endRequests.set(slot, request);
        endNanos.set(slot, System.nanoTime());
        endSequences.set(slot, sequence + 1);
    }

    /**
     * The drain thread: reads the request ends at least {@code graceNanos} old and reports, for each, the threads its
     * application code started before it ended that are still alive ({@link #THREAD_LEFT_RUNNING}), forgotten, and the
     * executors it created that are not shut down ({@link #EXECUTOR_LEFT_RUNNING}), kept for their shutdown. Ends the
     * ring overwrote before they were read are counted lost. Never throws.
     */
    void processEnds(long processGeneration, long nowNanos, long graceNanos, List<Entry> reports) {
        while (true) {
            long written = endsWritten.get();
            if (endsRead == written || !processEnds(processGeneration, written, nowNanos, graceNanos, reports)) {
                return;
            }
        }
    }

    /** One hold of the lock: at most {@value #ENDS_PER_HOLD} ends. Returns whether ends remain ready to read. */
    private boolean processEnds(
            long processGeneration, long written, long nowNanos, long graceNanos, List<Entry> reports) {
        int read = 0;
        lock.lock();
        try {
            if (!reset(processGeneration)) {
                return false;
            }
            if (written - endsRead > ENDS) {
                endsLost.add(written - endsRead - ENDS);
                endsRead = written - ENDS;
            }
            while (endsRead < written) {
                if (read++ >= ENDS_PER_HOLD) {
                    return true;
                }
                int slot = (int) (endsRead & (ENDS - 1));
                long sequence = endSequences.get(slot);
                if (sequence != endsRead + 1) {
                    if (sequence > endsRead + 1) {
                        endsLost.increment();
                        endsRead++;
                        continue;
                    }
                    // Not written yet: read again at the next drain.
                    break;
                }
                long request = endRequests.get(slot);
                long endedAt = endNanos.get(slot);
                if (endSequences.get(slot) != sequence) {
                    endsLost.increment();
                    endsRead++;
                    continue;
                }
                if (nowNanos - endedAt < graceNanos) {
                    break;
                }
                endsRead++;
                ended.put(Long.valueOf(request), Boolean.TRUE);
                check(request, endedAt, nowNanos, reports);
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    private void check(long request, long endedAt, long nowNanos, List<Entry> reports) {
        if (waiting == 0) {
            return;
        }
        List<Entry> list = waitingByRequest.remove(Long.valueOf(request));
        if (list == null) {
            return;
        }
        endsChecked.increment();
        for (Entry entry : list) {
            if (!entry.waitingForEnd) {
                continue;
            }
            entry.waitingForEnd = false;
            waiting--;
            Object referent = entry.get();
            boolean before = entry.createdNanos - endedAt <= 0;
            if (entry.thread) {
                if (before && referent instanceof Thread && ((Thread) referent).isAlive()) {
                    reports.add(entry.report(THREAD_LEFT_RUNNING, nowNanos));
                }
                remove(entry);
            } else if (referent != null && before) {
                reports.add(entry.report(EXECUTOR_LEFT_RUNNING, nowNanos));
            }
        }
    }

    /** Executors the collector reclaimed without a shutdown since the last call, reported. Never throws. */
    void expunge(long expectedGeneration, List<Entry> reports) {
        if (size == 0) {
            return;
        }
        long now = System.nanoTime();
        lock.lock();
        try {
            if (expectedGeneration != generation) {
                return;
            }
            expunge(reports, now);
            timeOut(System.currentTimeMillis());
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

    /**
     * Forgets everything, counting what still waited for its request's end as dropped, never checked: the sensor
     * disabled or released, or tests.
     */
    void clear() {
        lock.lock();
        try {
            dropped.add(waiting);
            clearState();
            generation = Long.MIN_VALUE;
            endsRead = endsWritten.get();
        } finally {
            lock.unlock();
        }
    }

    // ---- under the lock --------------------------------------------------------------------------------------------

    private boolean reset(long next) {
        if (next == generation) {
            return true;
        }
        if (next < generation) {
            return false;
        }
        clearState();
        generation = next;
        return true;
    }

    private void clearState() {
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
        ended.clear();
        size = 0;
        threads = 0;
        executors = 0;
        waiting = 0;
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
        if (waiting == 0) {
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
        if (entry.thread) {
            threads--;
        } else {
            executors--;
        }
        stopWaiting(entry);
        entry.clear();
    }
}
