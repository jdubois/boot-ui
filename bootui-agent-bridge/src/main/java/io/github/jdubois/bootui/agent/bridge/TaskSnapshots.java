package io.github.jdubois.bootui.agent.bridge;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiFunction;

/**
 * Submit-time snapshots keyed by the identity of the task an executor received, held weakly (PLAN-v2 D32), so the task
 * the executor queues, returns from {@code shutdownNow}, or passes to a rejection handler stays the application's own.
 * Each entry counts its pending submissions: a submission by another owner, or an unowned one, while any is pending makes
 * the entry ambiguous until the count drops to zero, so a run never takes another owner's snapshot. Payloads are flat
 * arrays of JDK values stamped with the claim generation, so the map can never pin a class loader and is never cleared.
 * No lambdas, no synchronized.
 */
final class TaskSnapshots {

    /** Returned by {@link #take}: several owners submitted the task; it runs unowned. */
    static final Object AMBIGUOUS = new Object();

    /** The owner fields of a snapshot compared to tell owners apart: request, execution, trace, and span ids. */
    static final int OWNER_FIELDS = 4;

    /** The snapshots of tasks handed to executors. */
    static final TaskSnapshots TASKS = new TaskSnapshots();

    /** The snapshots of threads started from owned work, kept apart so pool workers never crowd the task map. */
    static final TaskSnapshots THREADS = new TaskSnapshots();

    private final ConcurrentHashMap<Object, Entry> snapshots = new ConcurrentHashMap<Object, Entry>();
    private final ReferenceQueue<Object> queue = new ReferenceQueue<Object>();
    private final LongAdder neverAppliedCount = new LongAdder();

    private TaskSnapshots() {}

    /** Records an owned submission; returns false when the entry is (now) ambiguous. */
    boolean put(Object task, long generation, Object[] payload) {
        expunge();
        Put put = new Put(generation, payload);
        snapshots.compute(new Key(task, queue), put);
        return !put.ambiguous;
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
        Take take = new Take();
        snapshots.computeIfPresent(new Lookup(task), take);
        return take.result;
    }

    /** A submission that will not run (the queue refused it, its worker did not start, it was removed). */
    void release(Object task) {
        if (snapshots.isEmpty() || snapshots.get(new Lookup(task)) == null) {
            return;
        }
        snapshots.computeIfPresent(new Lookup(task), new Take());
    }

    boolean isEmpty() {
        return snapshots.isEmpty();
    }

    int size() {
        expunge();
        return snapshots.size();
    }

    /** Entries whose task was reclaimed while still pending: keyed but never run where a hook applies it. */
    long neverApplied() {
        expunge();
        return neverAppliedCount.sum();
    }

    /** Tests only. */
    void reset() {
        snapshots.clear();
        neverAppliedCount.reset();
    }

    void expungeStale() {
        expunge();
    }

    private void expunge() {
        Object stale;
        while ((stale = queue.poll()) != null) {
            Entry entry = snapshots.remove(stale);
            if (entry != null && entry.pending > 0) {
                neverAppliedCount.increment();
            }
        }
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

        Entry(long generation, Object[] payload) {
            this.generation = generation;
            this.payload = payload;
            this.pending = 1;
        }
    }

    static final class Put implements BiFunction<Object, Entry, Entry> {

        private final long generation;
        private final Object[] payload;
        boolean ambiguous;

        Put(long generation, Object[] payload) {
            this.generation = generation;
            this.payload = payload;
        }

        @Override
        public Entry apply(Object key, Entry existing) {
            if (existing == null) {
                return new Entry(generation, payload);
            }
            existing.pending++;
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

    static final class Take implements BiFunction<Object, Entry, Entry> {

        Object result;

        @Override
        public Entry apply(Object key, Entry existing) {
            result = existing.ambiguous ? AMBIGUOUS : existing;
            existing.pending--;
            return existing.pending <= 0 ? null : existing;
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
