package io.github.jdubois.bootui.agent.bridge;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The resources sensor's tracker (PLAN-v2 §5.16, M5-5g, D46): the streams, channels, and sockets a request's or a job's
 * work opened, held weakly until they are closed, until the collector reclaims them, or until the sensor stops, so the
 * sensor can say which a request left open when it ended and which became unreachable while still open, without ever
 * keeping a resource, its class, or its class loader alive.
 *
 * <p><b>Not a {@code Cleaner}</b> (D46). Each entry is a {@link WeakReference} with primitive fields only, registered
 * with one {@link ReferenceQueue} that the drain thread polls, as {@link ThreadTracker} does for executors: a {@code
 * Cleaner}'s phantom reference cannot be found again by identity when the resource is closed, and each {@code Cleaner}
 * starts a thread. Nothing captures the resource: a close sets the entry's {@link Entry#closed} flag, and never clears
 * or enqueues the reference. A weak reference is cleared before finalization, so "reclaimed" means the resource became
 * unreachable while still open.
 *
 * <p><b>Close path, lock-free.</b> A close reads the count of entries of its kind (0 for most closes: return), then one
 * bucket of an {@link AtomicReferenceArray}, and walks its chain with {@link Reference#refersTo}. Writers (track,
 * unlink) hold one {@link ReentrantLock}, never a monitor, so a virtual thread never pins its carrier here; an unlinked
 * entry keeps its {@code next}, so a reader standing on it still reaches the rest of its chain. A close racing a track
 * is caught by the track's check of the resource's own open state after the insertion ({@link #track}).
 *
 * <p><b>Request ends.</b> As {@link ThreadTracker}'s: a lock-free ring of {@value #ENDS} ends, written by any thread
 * while an entry waits for its request's end, read by the drain thread once each end is at least the grace period old.
 *
 * <p><b>Caps.</b> At most {@value #MAX_ENTRIES} entries; at the cap a new open is left untracked, counted, and only the
 * drain thread evicts the oldest entries already reported open (pooled connections), counted.
 */
final class ResourceTracker {

    static final int MAX_ENTRIES = 1_024;

    static final int BUCKETS = 4_096;

    static final int ENDS = 4_096;
    static final int ENDED = 1_024;

    /** Ends read under one hold of the lock. */
    static final int ENDS_PER_HOLD = 256;

    /** An entry whose request's end never came stops waiting after this long, counted. */
    static final long WAIT_MILLIS = 600_000L;

    /** Resource kinds, as {@link Resources} names them: indexes of {@link #kindCounts}. */
    static final int KINDS = 8;

    /** What a sweep reports about an entry. */
    static final int LEFT_OPEN = 1;

    static final int CLOSED_LATE = 2;
    static final int RECLAIMED = 3;

    private final ReentrantLock lock = new ReentrantLock();
    private final ReferenceQueue<Object> queue = new ReferenceQueue<Object>();
    private final AtomicReferenceArray<Entry> buckets = new AtomicReferenceArray<Entry>(BUCKETS);
    /** Live entries per resource kind: the close path's first read. */
    private final AtomicIntegerArray kindCounts = new AtomicIntegerArray(KINDS);

    private final Map<Long, List<Entry>> waitingByRequest = new HashMap<Long, List<Entry>>();
    private final LinkedHashMap<Long, Boolean> ended = new LinkedHashMap<Long, Boolean>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
            return size() > ENDED;
        }
    };

    private final AtomicLongArray endRequests = new AtomicLongArray(ENDS);
    private final AtomicLongArray endNanos = new AtomicLongArray(ENDS);
    private final AtomicLongArray endSequences = new AtomicLongArray(ENDS);
    private final AtomicLong endsWritten = new AtomicLong();
    private long endsRead;

    private volatile int size;
    private volatile int waiting;
    private long generation = Long.MIN_VALUE;

    final LongAdder untracked = new LongAdder();
    final LongAdder duplicates = new LongAdder();
    final LongAdder closedBeforeTracked = new LongAdder();
    final LongAdder unresolved = new LongAdder();
    final LongAdder endsLost = new LongAdder();
    final LongAdder endsChecked = new LongAdder();
    final LongAdder evicted = new LongAdder();
    final LongAdder closeMissed = new LongAdder();
    final LongAdder dropped = new LongAdder();

    /** An open resource, with what its open recorded, copied into what a sweep reports. */
    static final class Entry extends WeakReference<Object> {

        final int hash;
        final int kind;
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
        volatile Entry next;
        /** Set by the resource's close, on any thread, never cleared. */
        volatile boolean closed;
        /** Under the lock: whether the entry is in its bucket. */
        boolean linked;

        boolean waitingForEnd;
        long waitingSince;
        /** Whether a report counted this resource already, and whether it was reported open after its request. */
        boolean counted;

        boolean reportedOpen;
        /** A sweep found the resource closed by its own state while {@link #closed} was not set yet. */
        boolean suspect;
        /** What a sweep found, set on a report's copy. */
        int reported;

        long reportedNanos;
        boolean first;

        Entry(
                Object referent,
                ReferenceQueue<Object> queue,
                int kind,
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
            this.hash = referent == null ? 0 : System.identityHashCode(referent);
            this.kind = kind;
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

        /** A copy without its referent, for what a sweep reports: never keeps the resource. */
        Entry report(int what, long nowNanos, boolean firstReport) {
            Entry copy = new Entry(
                    null,
                    null,
                    kind,
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
            copy.first = firstReport;
            return copy;
        }
    }

    /**
     * Tracks {@code resource} of {@code kind}, opened under {@code entryGeneration} by work of {@code request} or
     * {@code execution}: it waits for its request's end when {@code request} is not 0 and that request has not ended.
     * Returns the entry, or {@code null} when untracked (the cap, a duplicate, another generation's sweep running).
     * Never throws.
     */
    Entry track(
            Object resource,
            int kind,
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
            long createdMillis) {
        if (resource == null || kind <= 0 || kind >= KINDS) {
            return null;
        }
        long now = System.nanoTime();
        Entry entry;
        lock.lock();
        try {
            reset(entryGeneration);
            if (size >= MAX_ENTRIES) {
                untracked.increment();
                return null;
            }
            int hash = System.identityHashCode(resource);
            int bucket = hash & (BUCKETS - 1);
            for (Entry current = buckets.get(bucket); current != null; current = current.next) {
                if (current.linked && current.hash == hash && current.refersTo(resource)) {
                    duplicates.increment();
                    return null;
                }
            }
            entry = new Entry(
                    resource,
                    queue,
                    kind,
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
            entry.linked = true;
            entry.next = buckets.get(bucket);
            // Counted before it is visible: a close that finds the entry has read a count that includes it.
            kindCounts.incrementAndGet(kind);
            buckets.set(bucket, entry);
            size++;
            if (request != 0L && !ended.containsKey(Long.valueOf(request))) {
                entry.waitingForEnd = true;
                entry.waitingSince = createdMillis;
                List<Entry> list = waitingByRequest.get(Long.valueOf(request));
                if (list == null) {
                    list = new ArrayList<Entry>(2);
                    waitingByRequest.put(Long.valueOf(request), list);
                }
                list.add(entry);
                waiting++;
            }
        } finally {
            lock.unlock();
        }
        // Outside the lock, which a socket's own lock must never nest in: a close that ran before the insertion missed
        // the entry, and its resource reads closed now.
        if (Resources.closedNow(resource, kind) == Resources.STATE_CLOSED) {
            entry.closed = true;
            closedBeforeTracked.increment();
        }
        return entry;
    }

    /** {@code resource} of {@code kind} was closed: its entry, if any, is marked. Lock-free; never throws. */
    void closed(Object resource, int kind) {
        if (kind <= 0 || kind >= KINDS || kindCounts.get(kind) == 0) {
            return;
        }
        int hash = System.identityHashCode(resource);
        for (Entry entry = buckets.get(hash & (BUCKETS - 1)); entry != null; entry = entry.next) {
            if (entry.hash == hash && entry.refersTo(resource)) {
                entry.closed = true;
                return;
            }
        }
    }

    /** Whether any entry of {@code kind} is tracked: read without the lock. */
    boolean tracking(int kind) {
        return kind > 0 && kind < KINDS && kindCounts.get(kind) != 0;
    }

    /** A request ended, its response complete: written into the ring, without a lock, while an entry waits. */
    void ended(long request) {
        if (request == 0L || waiting == 0) {
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
     * The drain thread: reads the request ends at least {@code graceNanos} old, then reports, for each entry of an ended
     * request opened before its end and still open, {@link #LEFT_OPEN}; for each entry the collector cleared while not
     * closed, {@link #RECLAIMED} unless its kind is in {@code noReclaims}; and for each entry reported open and closed
     * since, {@link #CLOSED_LATE}. Closed entries are forgotten. Returns the kinds whose close a hook missed: their
     * resource read closed twice in a row while their entry was not marked. Never throws.
     */
    int sweep(long sweepGeneration, long nowNanos, long graceNanos, int noReclaims, List<Entry> reports) {
        List<Entry> candidates = new ArrayList<Entry>();
        List<Long> endsAt = new ArrayList<Long>();
        while (true) {
            long written = endsWritten.get();
            if (endsRead == written || !readEnds(sweepGeneration, written, nowNanos, graceNanos, candidates, endsAt)) {
                break;
            }
        }
        // Outside the lock: each candidate's own open state, which may take a socket's lock.
        int[] states = new int[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            Entry entry = candidates.get(i);
            Object referent = entry.get();
            states[i] = entry.closed || referent == null
                    ? Resources.STATE_CLOSED
                    : Resources.closedNow(referent, entry.kind);
        }
        List<Entry> live = new ArrayList<Entry>();
        int missed = 0;
        lock.lock();
        try {
            // A newer claim's sweep: the earlier generation's entries are forgotten, never reported in its run.
            reset(sweepGeneration);
            for (int i = 0; i < candidates.size(); i++) {
                Entry entry = candidates.get(i);
                if (!entry.linked || entry.closed || states[i] == Resources.STATE_CLOSED) {
                    // Closed, or reclaimed: the queue reports it.
                    continue;
                }
                if (entry.createdNanos - endsAt.get(i).longValue() <= 0) {
                    reports.add(entry.report(LEFT_OPEN, nowNanos, !entry.counted));
                    entry.counted = true;
                    entry.reportedOpen = true;
                }
            }
            Reference<?> reference;
            while ((reference = queue.poll()) != null) {
                Entry entry = (Entry) reference;
                if (!entry.linked || entry.generation != generation) {
                    continue;
                }
                unlink(entry);
                if (!entry.closed && (noReclaims & (1 << entry.kind)) == 0) {
                    reports.add(entry.report(RECLAIMED, nowNanos, !entry.counted));
                    entry.counted = true;
                }
            }
            long nowMillis = System.currentTimeMillis();
            for (int i = 0; i < BUCKETS && size > 0; i++) {
                for (Entry entry = buckets.get(i); entry != null; entry = entry.next) {
                    if (!entry.linked) {
                        continue;
                    }
                    if (entry.closed) {
                        unlink(entry);
                        if (entry.reportedOpen) {
                            reports.add(entry.report(CLOSED_LATE, nowNanos, false));
                        }
                        continue;
                    }
                    if (entry.waitingForEnd && nowMillis - entry.waitingSince >= WAIT_MILLIS) {
                        stopWaiting(entry);
                        unresolved.increment();
                    }
                    live.add(entry);
                }
            }
        } finally {
            lock.unlock();
        }
        // The canary, outside the lock: a resource that reads closed while its entry is not marked, at two sweeps in a
        // row, so a close between its own state change and its hook's exit is never taken for a miss.
        for (Entry entry : live) {
            Object referent = entry.get();
            if (referent == null || entry.closed) {
                continue;
            }
            int state = Resources.closedNow(referent, entry.kind);
            if (state != Resources.STATE_CLOSED) {
                entry.suspect = false;
                continue;
            }
            if (!entry.suspect) {
                entry.suspect = true;
                continue;
            }
            if (!entry.closed) {
                closeMissed.increment();
                missed |= 1 << entry.kind;
                entry.closed = true;
            }
        }
        if (size >= MAX_ENTRIES) {
            evictReported();
        }
        return missed;
    }

    /** One hold of the lock: at most {@value #ENDS_PER_HOLD} ends. Returns whether ends remain ready to read. */
    private boolean readEnds(
            long readGeneration,
            long written,
            long nowNanos,
            long graceNanos,
            List<Entry> candidates,
            List<Long> endsAt) {
        int read = 0;
        lock.lock();
        try {
            reset(readGeneration);
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
                List<Entry> list = waitingByRequest.remove(Long.valueOf(request));
                if (list == null) {
                    continue;
                }
                endsChecked.increment();
                for (Entry entry : list) {
                    if (!entry.waitingForEnd) {
                        continue;
                    }
                    entry.waitingForEnd = false;
                    waiting--;
                    candidates.add(entry);
                    endsAt.add(Long.valueOf(endedAt));
                }
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /** Oldest entries first: no lambda, as the bridge never uses invokedynamic. */
    private static final Comparator<Entry> OLDEST_FIRST = new OldestFirst();

    private static final class OldestFirst implements Comparator<Entry> {
        @Override
        public int compare(Entry a, Entry b) {
            return Long.compare(a.createdNanos, b.createdNanos);
        }
    }

    /** At the cap, on the drain thread: forgets the oldest entries already reported open, up to an eighth of the cap. */
    private void evictReported() {
        lock.lock();
        try {
            List<Entry> reported = new ArrayList<Entry>();
            for (int i = 0; i < BUCKETS; i++) {
                for (Entry entry = buckets.get(i); entry != null; entry = entry.next) {
                    if (entry.linked && entry.reportedOpen) {
                        reported.add(entry);
                    }
                }
            }
            reported.sort(OLDEST_FIRST);
            int limit = Math.min(reported.size(), MAX_ENTRIES / 8);
            for (int i = 0; i < limit; i++) {
                unlink(reported.get(i));
                evicted.increment();
            }
        } finally {
            lock.unlock();
        }
    }

    int size() {
        return size;
    }

    int waitingCount() {
        return waiting;
    }

    /** Forgets everything, counting what was tracked as dropped: the sensor disabled or released, or tests. */
    void clear() {
        lock.lock();
        try {
            dropped.add(size);
            reset(Long.MIN_VALUE);
            generation = Long.MIN_VALUE;
            endsRead = endsWritten.get();
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
            for (Entry entry = buckets.get(i); entry != null; entry = entry.next) {
                entry.linked = false;
                entry.clear();
            }
            buckets.set(i, null);
        }
        for (int i = 0; i < KINDS; i++) {
            kindCounts.set(i, 0);
        }
        while (queue.poll() != null) {
            // The previous generation's entries: dropped.
        }
        waitingByRequest.clear();
        ended.clear();
        size = 0;
        waiting = 0;
        generation = next;
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

    /**
     * Unlinks {@code entry} from its bucket, keeping its {@code next}, so a close walking the chain lock-free past it
     * still reaches the entries behind it.
     */
    private void unlink(Entry entry) {
        if (!entry.linked) {
            return;
        }
        int bucket = entry.hash & (BUCKETS - 1);
        Entry previous = null;
        for (Entry current = buckets.get(bucket); current != null; previous = current, current = current.next) {
            if (current == entry) {
                if (previous == null) {
                    buckets.set(bucket, entry.next);
                } else {
                    previous.next = entry.next;
                }
                break;
            }
        }
        entry.linked = false;
        size--;
        kindCounts.decrementAndGet(entry.kind);
        stopWaiting(entry);
        entry.clear();
    }
}
