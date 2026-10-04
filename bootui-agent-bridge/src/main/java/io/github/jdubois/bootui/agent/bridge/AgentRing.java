package io.github.jdubois.bootui.agent.bridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * The agent's transport (PLAN-v2 §5.13, M5-3): a bounded multi-producer, single-consumer ring of fixed-size records,
 * and a bounded table interning the strings records refer to. A record is {@value #RECORD} longs: the sensor id, the
 * record type, the claim generation, the time in epoch milliseconds, and four payload longs.
 *
 * <p>Producers follow Vyukov's bounded queue: a producer reads its slot's sequence and claims the slot by advancing the
 * producer cursor only when the slot is free, so a full ring drops the record without claiming anything, counted per
 * sensor, and never blocks. Before writing, it moves the slot's sequence from its position to a writing mark with a
 * compare-and-set, writes the record and then its position as a stamp, and publishes the slot in a {@code finally}. The
 * single consumer is whoever passes the current claim's token to {@link #drain}, one at a time; a slot claimed but not
 * yet being written across {@value #STUCK_DRAINS} drains and at least a second while a later slot is published (a
 * producer that died between claiming and writing) is skipped and counted lost. A producer reaching it afterwards finds
 * the writing mark's compare-and-set failing and drops its record rather than corrupting a later lap, and a record whose
 * stamp is not its position when the consumer has copied it (one left half-written) is dropped and counted lost too.
 * A drainer stops at the first record of a newer claim generation than its own, which belongs to the next drainer.
 *
 * <p>The ring is allocated at the first claim asking for a sensor that uses it, or the first method probe, with that
 * claim's capacity, and kept for the JVM's life; the intern table is replaced at each such claim or a probe's first in a
 * newer claim generation, so its ids are scoped to one claim generation. Every
 * entry point catches everything: none throws to its caller.
 */
public final class AgentRing {

    /** Longs per record. */
    public static final int RECORD = 8;

    /** Record layout: the sensor id. */
    public static final int SENSOR = 0;

    /** Record layout: the record type, per sensor. */
    public static final int TYPE = 1;

    /** Record layout: the claim generation the record was made under. */
    public static final int GENERATION = 2;

    /** Record layout: the time, in epoch milliseconds. */
    public static final int TIME = 3;

    /** Record layout: the first of four payload longs. */
    public static final int PAYLOAD = 4;

    /** Longs per slot: the record, then the position its producer stamped once it wrote it. */
    static final int STRIDE = RECORD + 1;

    /** Sensor ids: records of an unknown sensor are counted under {@code other}. */
    public static final int SENSOR_OTHER = 0;

    public static final int SENSOR_INVENTORY = 1;

    /** Method probes' hits (PLAN-v2 M5-8). */
    public static final int SENSOR_METHOD_PROBES = 2;

    static final String[] SENSOR_NAMES = {"other", CodeInventory.SENSOR, MethodProbes.SENSOR};

    public static final int DEFAULT_CAPACITY = 1 << 16;
    public static final int MIN_CAPACITY = 1 << 10;
    public static final int MAX_CAPACITY = 1 << 22;

    /** Interned strings per claim generation; id 0 is "unknown", returned on overflow. */
    public static final int DEFAULT_INTERNS = 1 << 14;

    /** Consecutive drains a claimed, unpublished slot may block a published later one before it is skipped. */
    static final int STUCK_DRAINS = 50;

    /**
     * And for at least this long, so a drainer polling in a tight loop never skips a slot whose producer is merely
     * between claiming and publishing. Tests shorten it.
     */
    static volatile long stuckNanos = 1_000_000_000L;

    private static final AtomicReference<Ring> RING = new AtomicReference<Ring>();
    private static final AtomicReference<Interns> INTERNS = new AtomicReference<Interns>();

    private static final LongAdder[] DROPPED = adders(SENSOR_NAMES.length);
    private static final LongAdder LOST = new LongAdder();
    private static final LongAdder LATE = new LongAdder();
    private static final LongAdder TORN = new LongAdder();
    private static final LongAdder NEWER_STOPS = new LongAdder();
    private static final LongAdder STALE_DRAINS = new LongAdder();
    private static final LongAdder BUSY_DRAINS = new LongAdder();
    private static final LongAdder DRAINED = new LongAdder();
    private static final LongAdder SINK_ERRORS = new LongAdder();
    private static final LongAdder INTERN_OVERFLOW = new LongAdder();

    private AgentRing() {}

    // ---- lifecycle -------------------------------------------------------------------------------------------------

    /**
     * A claim asking for a sensor that uses the ring: allocates the ring once, with {@code requestedCapacity}, and starts
     * a fresh intern table for {@code generation}.
     */
    static void newGeneration(long generation, int requestedCapacity) {
        if (RING.get() == null) {
            RING.compareAndSet(null, new Ring(capacity(requestedCapacity)));
        }
        // Claims racing each other may get here out of order: the table only ever moves to a newer generation.
        while (true) {
            Interns current = INTERNS.get();
            if (current != null && current.generation >= generation) {
                return;
            }
            if (INTERNS.compareAndSet(current, new Interns(generation, DEFAULT_INTERNS))) {
                return;
            }
        }
    }

    /** {@code requested} clamped to [{@value #MIN_CAPACITY}, {@value #MAX_CAPACITY}] and rounded up to a power of two. */
    static int capacity(int requested) {
        if (requested <= 0) {
            return DEFAULT_CAPACITY;
        }
        int clamped = Math.max(MIN_CAPACITY, Math.min(MAX_CAPACITY, requested));
        int power = Integer.highestOneBit(clamped);
        return power == clamped ? clamped : power << 1;
    }

    // ---- producers -------------------------------------------------------------------------------------------------

    /**
     * Publishes one record, or drops it when the ring is full or not allocated (counted per sensor). Never blocks,
     * never throws.
     */
    public static boolean publish(int sensor, int type, long generation, long time, long a, long b, long c, long d) {
        try {
            Ring ring = RING.get();
            int index = sensor >= 0 && sensor < SENSOR_NAMES.length ? sensor : SENSOR_OTHER;
            if (ring == null) {
                DROPPED[index].increment();
                return false;
            }
            long position = ring.claim();
            if (position < 0) {
                DROPPED[index].increment();
                return false;
            }
            return ring.write(position, sensor, type, generation, time, a, b, c, d);
        } catch (Throwable ex) {
            AgentBridge.error(ex);
            return false;
        }
    }

    // ---- the consumer ----------------------------------------------------------------------------------------------

    /**
     * Drains the published records in order, at most one ring's worth, into {@code sink}, which receives one reused
     * {@code long[]} of {@value #RECORD} longs per record and must copy what it keeps. Only the current claim's token
     * drains, and one caller at a time: a stale token or a concurrent second caller gets nothing. The drain is bound to
     * that claim's generation: it stops, leaving it in place, at the first record of a newer generation, which a newer
     * claim's drainer takes, while records of older generations still waiting are handed over with their generation for
     * the sink to judge. Returns how many records were drained.
     */
    public static int drain(long token, Consumer<long[]> sink) {
        try {
            Ring ring = RING.get();
            if (ring == null || sink == null) {
                return 0;
            }
            Claim claim = AgentBridge.current();
            if (claim == null || claim.token != token) {
                STALE_DRAINS.increment();
                return 0;
            }
            if (!ring.draining.compareAndSet(false, true)) {
                BUSY_DRAINS.increment();
                return 0;
            }
            try {
                return ring.drain(claim.generation, sink);
            } finally {
                ring.draining.set(false);
            }
        } catch (Throwable ex) {
            AgentBridge.error(ex);
            return 0;
        }
    }

    // ---- the intern table ------------------------------------------------------------------------------------------

    /**
     * The id of {@code text} in the current generation's table, from 1; 0 for {@code null}, before any claim that uses
     * the ring, or when the table is full (counted). Never throws.
     */
    public static int intern(String text) {
        try {
            Interns interns = INTERNS.get();
            if (text == null || interns == null) {
                return 0;
            }
            return interns.intern(text);
        } catch (Throwable ex) {
            AgentBridge.error(ex);
            return 0;
        }
    }

    /**
     * The interned strings of {@code generation} with ids {@code from} onwards, index 0 being id {@code from}: a copy,
     * with {@code null} for an id that was never filled. {@code null} when the current table belongs to another
     * generation.
     */
    public static String[] interned(long generation, int from) {
        try {
            Interns interns = INTERNS.get();
            if (interns == null || interns.generation != generation) {
                return null;
            }
            return interns.copy(Math.max(1, from));
        } catch (Throwable ex) {
            AgentBridge.error(ex);
            return null;
        }
    }

    // ---- status ----------------------------------------------------------------------------------------------------

    /** Records dropped for {@code sensor} because the ring was full or not allocated. */
    static long dropped(int sensor) {
        return DROPPED[sensor].sum();
    }

    static long lost() {
        return LOST.sum();
    }

    static long internOverflow() {
        return INTERN_OVERFLOW.sum();
    }

    /** Capacity, size, drops per sensor, lost records, and the intern table; JDK types only. */
    public static Map<String, Object> status() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        try {
            Ring ring = RING.get();
            map.put("capacity", Integer.valueOf(ring == null ? 0 : ring.capacity));
            map.put("size", Long.valueOf(ring == null ? 0L : ring.size()));
            Map<String, Object> dropped = new LinkedHashMap<String, Object>();
            for (int i = 0; i < SENSOR_NAMES.length; i++) {
                dropped.put(SENSOR_NAMES[i], Long.valueOf(DROPPED[i].sum()));
            }
            map.put("dropped", dropped);
            map.put("lost", Long.valueOf(LOST.sum()));
            map.put("latePublishes", Long.valueOf(LATE.sum()));
            map.put("tornRecords", Long.valueOf(TORN.sum()));
            map.put("newerGenerationStops", Long.valueOf(NEWER_STOPS.sum()));
            map.put("drained", Long.valueOf(DRAINED.sum()));
            map.put("staleDrains", Long.valueOf(STALE_DRAINS.sum()));
            map.put("busyDrains", Long.valueOf(BUSY_DRAINS.sum()));
            map.put("sinkErrors", Long.valueOf(SINK_ERRORS.sum()));
            Interns interns = INTERNS.get();
            map.put("internGeneration", interns == null ? null : Long.valueOf(interns.generation));
            map.put("interned", Integer.valueOf(interns == null ? 0 : interns.size()));
            map.put("internCapacity", Integer.valueOf(interns == null ? 0 : interns.max));
            map.put("internOverflow", Long.valueOf(INTERN_OVERFLOW.sum()));
        } catch (Throwable ex) {
            AgentBridge.error(ex);
        }
        return map;
    }

    /** Tests only: forgets the ring, the table, and the counters. */
    static void reset() {
        stuckNanos = 1_000_000_000L;
        RING.set(null);
        INTERNS.set(null);
        for (int i = 0; i < DROPPED.length; i++) {
            DROPPED[i].reset();
        }
        LOST.reset();
        LATE.reset();
        TORN.reset();
        NEWER_STOPS.reset();
        STALE_DRAINS.reset();
        BUSY_DRAINS.reset();
        DRAINED.reset();
        SINK_ERRORS.reset();
        INTERN_OVERFLOW.reset();
    }

    /** Tests only: the allocated ring, or {@code null}. */
    static Ring ring() {
        return RING.get();
    }

    private static LongAdder[] adders(int count) {
        LongAdder[] adders = new LongAdder[count];
        for (int i = 0; i < count; i++) {
            adders[i] = new LongAdder();
        }
        return adders;
    }

    /** The ring itself: {@code capacity} slots of {@value #STRIDE} longs, each with its sequence. */
    static final class Ring {

        final int capacity;
        final int mask;
        final AtomicLongArray records;
        /**
         * Per slot: {@code position} when free for that position, {@code ~position} (negative) while its producer
         * writes it, {@code position + 1} once published, and {@code position + capacity} once consumed or skipped.
         */
        final AtomicLongArray sequences;
        /** The next position a producer claims. */
        final AtomicLong tail = new AtomicLong();
        /** The next position the consumer reads; written only by the drainer that holds {@link #draining}. */
        final AtomicLong head = new AtomicLong();

        final AtomicBoolean draining = new AtomicBoolean();
        /** The position the consumer found stuck, and for how many drains; touched only by the drainer. */
        private long stuckPosition = -1L;

        private int stuckDrains;

        private long stuckSince;

        Ring(int capacity) {
            this.capacity = capacity;
            this.mask = capacity - 1;
            this.records = new AtomicLongArray(capacity * STRIDE);
            this.sequences = new AtomicLongArray(capacity);
            for (int i = 0; i < capacity; i++) {
                sequences.set(i, i);
                // No position stamps a slot before its first write.
                records.set(i * STRIDE + RECORD, -1L);
            }
        }

        /** Claims the next free slot: its position, or -1 when the ring is full. */
        long claim() {
            long position = tail.get();
            while (true) {
                long sequence = sequences.get((int) (position & mask));
                if (sequence < 0L) {
                    // Being written for position ~sequence: as good as published, for the claim's purposes.
                    sequence = ~sequence + 1L;
                }
                long difference = sequence - position;
                if (difference == 0L) {
                    if (tail.compareAndSet(position, position + 1L)) {
                        return position;
                    }
                    position = tail.get();
                } else if (difference < 0L) {
                    // The slot still holds the record of the previous lap (or is being written for it): full.
                    return -1L;
                } else {
                    position = tail.get();
                }
            }
        }

        /**
         * Writes the claimed slot and publishes it; false, writing nothing, when the consumer skipped it meanwhile. The
         * writing mark taken first makes the skip and the write exclusive: once it holds, the consumer waits for the
         * publication instead of skipping, and once the consumer skipped, the mark cannot be taken.
         */
        boolean write(long position, int sensor, int type, long generation, long time, long a, long b, long c, long d) {
            int slot = (int) (position & mask);
            if (!sequences.compareAndSet(slot, position, ~position)) {
                LATE.increment();
                return false;
            }
            int base = slot * STRIDE;
            try {
                records.set(base + SENSOR, sensor);
                records.set(base + TYPE, type);
                records.set(base + GENERATION, generation);
                records.set(base + TIME, time);
                records.set(base + PAYLOAD, a);
                records.set(base + PAYLOAD + 1, b);
                records.set(base + PAYLOAD + 2, c);
                records.set(base + PAYLOAD + 3, d);
                records.set(base + RECORD, position);
            } finally {
                // Published even when the write failed half-way: the consumer finds the stamp missing and drops it.
                sequences.set(slot, position + 1L);
            }
            return true;
        }

        long size() {
            return Math.max(0L, tail.get() - head.get());
        }

        /**
         * Drains up to one ring's worth of published records of {@code generation} or older; called only by the drainer
         * holding the flag.
         */
        int drain(long generation, Consumer<long[]> sink) {
            long[] record = new long[RECORD];
            long position = head.get();
            int drained = 0;
            for (int i = 0; i < capacity; i++) {
                int slot = (int) (position & mask);
                long sequence = sequences.get(slot);
                if (sequence == position + 1L) {
                    int base = slot * STRIDE;
                    for (int k = 0; k < RECORD; k++) {
                        record[k] = records.get(base + k);
                    }
                    long stamp = records.get(base + RECORD);
                    if (stamp == position && record[GENERATION] > generation) {
                        // A newer claim's record: left for that claim's drainer.
                        NEWER_STOPS.increment();
                        break;
                    }
                    sequences.set(slot, position + capacity);
                    position++;
                    head.set(position);
                    stuckPosition = -1L;
                    if (stamp != position - 1L) {
                        // Published without its stamp: its producer failed half-way through writing it.
                        TORN.increment();
                        LOST.increment();
                        continue;
                    }
                    drained++;
                    DRAINED.increment();
                    try {
                        sink.accept(record);
                    } catch (Throwable ex) {
                        SINK_ERRORS.increment();
                        AgentBridge.error(ex);
                    }
                    continue;
                }
                if (sequence == position && tail.get() > position + 1L && published(position + 1L)) {
                    // Claimed, not being written, and a later record is waiting behind it.
                    long now = System.nanoTime();
                    if (stuckPosition == position) {
                        stuckDrains++;
                    } else {
                        stuckPosition = position;
                        stuckDrains = 1;
                        stuckSince = now;
                    }
                    if (stuckDrains >= STUCK_DRAINS && now - stuckSince >= stuckNanos) {
                        if (sequences.compareAndSet(slot, position, position + capacity)) {
                            LOST.increment();
                            position++;
                            head.set(position);
                            stuckPosition = -1L;
                        }
                        // Either skipped, or its producer started writing it just now: read on.
                        continue;
                    }
                }
                break;
            }
            return drained;
        }

        private boolean published(long position) {
            return sequences.get((int) (position & mask)) == position + 1L;
        }

        /** Tests only: publishes {@code position} as a producer that failed before stamping it would. */
        void publishTorn(long position) {
            int slot = (int) (position & mask);
            if (sequences.compareAndSet(slot, position, ~position)) {
                records.set(slot * STRIDE + TYPE, 1L);
                sequences.set(slot, position + 1L);
            }
        }
    }

    /** One generation's intern table: ids from 1, at most {@code max}. */
    static final class Interns {

        final long generation;
        final int max;
        final ConcurrentHashMap<String, Integer> ids = new ConcurrentHashMap<String, Integer>();
        final AtomicReferenceArray<String> strings;
        final AtomicInteger next = new AtomicInteger();

        Interns(long generation, int max) {
            this.generation = generation;
            this.max = max;
            this.strings = new AtomicReferenceArray<String>(max + 1);
        }

        int intern(String text) {
            Integer known = ids.get(text);
            if (known != null) {
                return known.intValue();
            }
            if (next.get() >= max) {
                INTERN_OVERFLOW.increment();
                return 0;
            }
            int id = next.incrementAndGet();
            if (id > max) {
                INTERN_OVERFLOW.increment();
                return 0;
            }
            strings.set(id, text);
            Integer raced = ids.putIfAbsent(text, Integer.valueOf(id));
            if (raced != null) {
                // Another thread interned it first: this id stays a hole the reader sees as null.
                strings.set(id, null);
                return raced.intValue();
            }
            return id;
        }

        int size() {
            return Math.min(next.get(), max);
        }

        String[] copy(int from) {
            int last = size();
            if (from > last) {
                return new String[0];
            }
            String[] copy = new String[last - from + 1];
            for (int id = from; id <= last; id++) {
                copy[id - from] = strings.get(id);
            }
            return copy;
        }
    }

    /** Tests only: the strings of the current table, in id order. */
    static List<String> internedNow() {
        List<String> list = new ArrayList<String>();
        Interns interns = INTERNS.get();
        if (interns != null) {
            String[] copy = interns.copy(1);
            for (int i = 0; i < copy.length; i++) {
                list.add(copy[i]);
            }
        }
        return list;
    }
}
