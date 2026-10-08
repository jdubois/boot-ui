package io.github.jdubois.bootui.agent.bridge;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiFunction;

/**
 * Submit-time snapshots keyed by the identity of the task an executor received, held weakly (PLAN-v2 D32), so the task
 * the executor queues, returns from {@code shutdownNow}, or passes to a rejection handler stays the application's own.
 * Each entry counts its pending submissions: a submission by another owner, or an unowned one, while any is pending makes
 * the entry ambiguous until the count drops to zero, so a run never takes another owner's snapshot. Payloads are flat
 * arrays of JDK values stamped with the claim generation, so the map can never pin a class loader. A new claim stops
 * counting the entries of earlier claims against the cap ({@link #releaseEarlierClaims}), so a backlog from before a
 * restart cannot hold it; they stay in the map, which keeps a task submitted across claims ambiguous, until their tasks
 * run, are released, or are reclaimed.
 *
 * <p>Each registry holds at most {@link #MAX_PENDING} tasks of the current claim, admitted atomically: past that, a new owned submission is
 * refused, counted by its caller ({@link #overflowed()}), records nothing, and so runs unowned, exactly like an unowned submission of
 * a task with no entry. A submission of a task that already has an entry is never refused, so the ambiguity rules are
 * unchanged. As with an unowned first submission, a refused submission followed by an admitted one of the same task
 * object lets the first run take the admitted snapshot. Stale entries are expunged at most {@link #EXPUNGE_BATCH} at a
 * time on application threads, before the admission check, and fully when the status is read. No lambdas, no
 * synchronized.
 */
final class TaskSnapshots {

    /** Returned by {@link #take}: several owners submitted the task; it runs unowned. */
    static final Object AMBIGUOUS = new Object();

    /** The owner fields of a snapshot compared to tell owners apart: request, execution, trace, and span ids. */
    static final int OWNER_FIELDS = 4;

    /** The most tasks a registry holds pending at once. */
    static final int MAX_PENDING = 32768;

    /** The most reclaimed tasks one submission or release expunges, so an application thread does bounded work. */
    static final int EXPUNGE_BATCH = 64;

    /** {@link #put}: the submission is the entry's owner. */
    static final int OWNED = 0;

    /** {@link #put}: the entry is (now) ambiguous. */
    static final int AMBIGUOUS_PUT = 1;

    /** {@link #put}: the registry is full; nothing was recorded. */
    static final int REFUSED = 2;

    /** The snapshots of tasks handed to executors. */
    static final TaskSnapshots TASKS = new TaskSnapshots(MAX_PENDING);

    /** The snapshots of threads started from owned work, kept apart so pool workers never crowd the task map. */
    static final TaskSnapshots THREADS = new TaskSnapshots(MAX_PENDING);

    private final ConcurrentHashMap<Object, Entry> snapshots = new ConcurrentHashMap<Object, Entry>();
    private final ReferenceQueue<Object> queue = new ReferenceQueue<Object>();
    private final LongAdder neverAppliedCount = new LongAdder();
    private final LongAdder overflowCount = new LongAdder();
    private final int maxPending;

    /** The entries in {@link #snapshots}: changed only where an entry is added to or removed from the map. */
    final AtomicInteger entries = new AtomicInteger();

    TaskSnapshots(int maxPending) {
        this.maxPending = maxPending;
    }

    /**
     * Records a self-test marker, past the cap too, so a full registry can never fail the self-test; see {@link
     * #put(Object, long, Object[], long)}.
     */
    int putSelfTest(Object task, long generation, Object[] payload) {
        return put(task, generation, payload, 0L, false);
    }

    /**
     * Records an owned submission made while the code-paths node {@code stamp} was open on the submitting thread (0 when
     * unknown): {@link #OWNED}, {@link #AMBIGUOUS_PUT} when the entry is (now) ambiguous, or {@link #REFUSED} when the
     * task has no entry and the registry is full. Submissions of one task by one owner from different nodes keep no
     * stamp.
     */
    int put(Object task, long generation, Object[] payload, long stamp) {
        return put(task, generation, payload, stamp, true);
    }

    private int put(Object task, long generation, Object[] payload, long stamp, boolean capped) {
        expunge(EXPUNGE_BATCH);
        Put put = new Put(this, generation, payload, stamp, capped);
        snapshots.compute(new Key(task, queue), put);
        if (put.refused) {
            return REFUSED;
        }
        return put.ambiguous ? AMBIGUOUS_PUT : OWNED;
    }

    /** Records an unowned submission of a task with a pending owned one: the entry becomes ambiguous. */
    boolean putUnowned(Object task) {
        if (snapshots.isEmpty() || snapshots.get(new Lookup(task)) == null) {
            return false;
        }
        Unowned unowned = new Unowned();
        snapshots.computeIfPresent(new Lookup(task), unowned);
        return unowned.touched;
    }

    /** Where the task runs: null (unowned), {@link #AMBIGUOUS}, or the {@link Entry} to reopen. */
    Object take(Object task) {
        if (snapshots.isEmpty() || snapshots.get(new Lookup(task)) == null) {
            return null;
        }
        Take take = new Take(this);
        snapshots.computeIfPresent(new Lookup(task), take);
        return take.result;
    }

    /** The payload of the task's unambiguous pending entry, without taking it; {@code null} otherwise. */
    Object[] peek(Object task) {
        if (snapshots.isEmpty()) {
            return null;
        }
        Entry entry = snapshots.get(new Lookup(task));
        return entry == null || entry.ambiguous ? null : entry.payload;
    }

    /** A submission that will not run (the queue refused it, its worker did not start, it was removed). */
    void release(Object task) {
        if (snapshots.isEmpty() || snapshots.get(new Lookup(task)) == null) {
            return;
        }
        snapshots.computeIfPresent(new Lookup(task), new Take(this));
    }

    boolean isEmpty() {
        return snapshots.isEmpty();
    }

    /** Read with the status, off the application's threads: expunges every reclaimed task first. */
    int size() {
        expunge(Integer.MAX_VALUE);
        return snapshots.size();
    }

    /** Entries whose task was reclaimed while still pending: keyed but never run where a hook applies it. */
    long neverApplied() {
        expunge(Integer.MAX_VALUE);
        return neverAppliedCount.sum();
    }

    /**
     * Counts a refused submission, once the caller knows the task was accepted: a task the executor turned down never
     * runs, and one hand-off may try several key points.
     */
    void overflowed() {
        overflowCount.increment();
    }

    /** Owned submissions refused because the registry already held {@code MAX_PENDING} tasks; they ran unowned. */
    long overflow() {
        return overflowCount.sum();
    }

    /**
     * Drops every entry, as when the sensor is disabled, and keeps the counters, like the sensor's other counters. Each
     * removal is counted, so the admission count stays exact while submissions race with it.
     */
    void clear() {
        for (Object key : snapshots.keySet()) {
            Entry removed = snapshots.remove(key);
            if (removed != null && removed.counted) {
                entries.decrementAndGet();
            }
        }
    }

    /** Tests only: the entries counted against the cap, read from the map itself. */
    int countedEntries() {
        int counted = 0;
        for (Entry entry : snapshots.values()) {
            if (entry.counted) {
                counted++;
            }
        }
        return counted;
    }

    /** Tests only: drops every entry and the counters. */
    void reset() {
        clear();
        neverAppliedCount.reset();
        overflowCount.reset();
    }

    /**
     * Stops counting the entries of claims before {@code generation} against the cap; returns how many. A full scan, run
     * once per claim, never on submission.
     */
    int releaseEarlierClaims(long generation) {
        Uncount uncount = new Uncount(this, generation);
        for (Object key : snapshots.keySet()) {
            snapshots.computeIfPresent(key, uncount);
        }
        return uncount.released;
    }

    /** Bounded: called on the application thread that released a submission. */
    void expungeStale() {
        expunge(EXPUNGE_BATCH);
    }

    /** Expunges at most {@code budget} reclaimed tasks; returns how many entries it removed. */
    int expunge(int budget) {
        int removed = 0;
        Object stale;
        for (int i = 0; i < budget && (stale = queue.poll()) != null; i++) {
            Entry entry = snapshots.remove(stale);
            if (entry != null) {
                if (entry.counted) {
                    entries.decrementAndGet();
                }
                removed++;
                if (entry.pending > 0) {
                    neverAppliedCount.increment();
                }
            }
        }
        return removed;
    }

    static boolean sameOwner(Object[] left, Object[] right) {
        for (int i = 0; i < OWNER_FIELDS; i++) {
            Object a = i < left.length ? left[i] : null;
            Object b = i < right.length ? right[i] : null;
            if (a == null ? b != null : !a.equals(b)) {
                return false;
            }
        }
        return true;
    }

    /** One task's pending submissions. Mutated only inside the map's per-bin compute. */
    static final class Entry {

        final long generation;
        final Object[] payload;
        int pending;
        boolean ambiguous;

        /** The code-paths stamp of the submitting node ({@code CodePaths.stamp()}), 0 when unknown. */
        long stamp;

        /** Whether the entry counts against the cap: an earlier claim's entry no longer does once a new claim armed. */
        boolean counted = true;

        Entry(long generation, Object[] payload, long stamp) {
            this.generation = generation;
            this.payload = payload;
            this.stamp = stamp;
            this.pending = 1;
        }
    }

    static final class Put implements BiFunction<Object, Entry, Entry> {

        private final TaskSnapshots owner;
        private final long generation;
        private final Object[] payload;
        private final long stamp;
        private final boolean capped;
        boolean ambiguous;
        boolean refused;

        Put(TaskSnapshots owner, long generation, Object[] payload, long stamp, boolean capped) {
            this.owner = owner;
            this.generation = generation;
            this.payload = payload;
            this.stamp = stamp;
            this.capped = capped;
        }

        @Override
        public Entry apply(Object key, Entry existing) {
            if (existing == null) {
                // Admitted atomically: returning null for an absent key adds nothing to the map.
                if (owner.entries.incrementAndGet() > owner.maxPending && capped) {
                    owner.entries.decrementAndGet();
                    refused = true;
                    return null;
                }
                try {
                    return new Entry(generation, payload, stamp);
                } catch (Throwable ex) {
                    owner.entries.decrementAndGet();
                    throw ex;
                }
            }
            existing.pending++;
            if (existing.stamp != stamp) {
                existing.stamp = 0L;
            }
            if (!existing.ambiguous && (existing.generation != generation || !sameOwner(existing.payload, payload))) {
                existing.ambiguous = true;
            }
            ambiguous = existing.ambiguous;
            return existing;
        }
    }

    static final class Unowned implements BiFunction<Object, Entry, Entry> {

        boolean touched;

        @Override
        public Entry apply(Object key, Entry existing) {
            existing.pending++;
            existing.ambiguous = true;
            touched = true;
            return existing;
        }
    }

    /** Stops counting an earlier claim's entry against the cap, keeping it; runs inside the map's per-bin compute. */
    static final class Uncount implements BiFunction<Object, Entry, Entry> {

        private final TaskSnapshots owner;
        private final long generation;
        int released;

        Uncount(TaskSnapshots owner, long generation) {
            this.owner = owner;
            this.generation = generation;
        }

        @Override
        public Entry apply(Object key, Entry existing) {
            if (existing.counted && existing.generation < generation) {
                existing.counted = false;
                owner.entries.decrementAndGet();
                released++;
            }
            return existing;
        }
    }

    static final class Take implements BiFunction<Object, Entry, Entry> {

        private final TaskSnapshots owner;
        Object result;

        Take(TaskSnapshots owner) {
            this.owner = owner;
        }

        @Override
        public Entry apply(Object key, Entry existing) {
            result = existing.ambiguous ? AMBIGUOUS : existing;
            existing.pending--;
            if (existing.pending <= 0) {
                if (existing.counted) {
                    owner.entries.decrementAndGet();
                }
                return null;
            }
            return existing;
        }
    }

    static final class Key extends WeakReference<Object> {

        private final int hash;

        Key(Object referent, ReferenceQueue<Object> queue) {
            super(referent, queue);
            this.hash = System.identityHashCode(referent);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (other instanceof Lookup) {
                return other.equals(this);
            }
            if (!(other instanceof Key)) {
                return false;
            }
            Object mine = get();
            return mine != null && mine == ((Key) other).get();
        }
    }

    /** A lookup-only key: never stored, no reference object allocated. */
    static final class Lookup {

        private final Object task;
        private final int hash;

        Lookup(Object task) {
            this.task = task;
            this.hash = System.identityHashCode(task);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key && ((Key) other).get() == task;
        }
    }
}
