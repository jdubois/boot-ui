package io.github.jdubois.bootui.engine.inventory;

import io.github.jdubois.bootui.engine.javaagent.AgentBridgeAccess;
import io.github.jdubois.bootui.engine.javaagent.AgentRecordDrainer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The inventory sensor's records for one run ({@code docs/PLAN-v2.md} §5.15): each method's first call and each code
 * source's first class in the run, with the request and route that made them and the time, routed here by the
 * {@link AgentRecordDrainer}. The executed set itself comes from the bridge's hit flags, which are exact; these records
 * only add the first request, route, and time, and a dropped record loses only those. Records of another claim
 * generation are counted and ignored. First calls are kept in primitive slots indexed by method id, so they are bounded
 * by the agent's method limit ({@value #MAX_METHODS} ids, about 21 bytes each) and none is dropped for memory.
 *
 * <p>A record carrying a request without its route, as when the framework matched the route only after the handler
 * started, is named from the journal once ({@link #routes}): the route found is written into every record of that
 * request, and a request not found yet, as one still in flight, is looked up again at most {@value #MAX_LOOKUPS} times,
 * never the whole journal for every read.
 *
 * <p><b>Clear recording</b> ({@link #clear}, M5-11) drops every request and route recorded before the clear, those still
 * queued in the agent's ring included: a record time-stamped before it keeps only its time. Which methods executed and
 * when each first ran are facts of the run, not of the requests recorded, and are kept.
 */
public final class InventoryRecords implements Consumer<long[]> {

    /** A method's first call in the run ({@code CodeInventory.FIRST_HIT}). */
    static final int FIRST_HIT = 1;

    /** A code source's first class in the run ({@code CodeInventory.CLASS_LOAD}). */
    static final int CLASS_LOAD = 2;

    /** The agent's method limit ({@code CodeInventory.MAX_METHODS}): a method id past it is ignored. */
    static final int MAX_METHODS = 1 << 18;

    /** The estimated bytes of one first-call slot: a request, a route index, a time, and a presence bit. */
    static final int SLOT_BYTES = 21;

    /** The estimated bytes of one first load, one pending lookup, and one route name's overhead. */
    static final int LOAD_BYTES = 160;

    static final int LOOKUP_BYTES = 64;
    static final int ROUTE_BYTES = 64;

    /** How many requests whose route is not found yet are remembered, the oldest forgotten first. */
    static final int MAX_PENDING_LOOKUPS = 8_192;

    /** How many code sources' first loads are kept; past it, a source's first load is not recorded, and counted. */
    static final int MAX_LOADS = 16_384;

    /** How many requests' routes are remembered, the least recent forgotten first, for their records drained later. */
    static final int MAX_REQUEST_ROUTES = 4_096;

    /** How many distinct route names are kept; past it, a new route is not named, and counted. */
    static final int MAX_ROUTE_NAMES = 4_096;

    /** The estimated bytes of one remembered request route. */
    static final int REQUEST_ROUTE_BYTES = 64;

    /**
     * When a method first ran, or a code source first loaded a class, in this run, and for which request.
     *
     * @param requestId the request, as 16 hexadecimal digits, or {@code null} outside a request or after a clear
     * @param route its route, or {@code null}
     * @param epochMillis when
     */
    public record First(String requestId, String route, long epochMillis) {}

    private final long generation;
    private final AgentBridgeAccess access;
    private long[] hitRequest = new long[0];
    private int[] hitRoute = new int[0];
    private long[] hitTime = new long[0];
    private long[] hitPresent = new long[0];
    private int hitCount;
    private int withRequest;
    private final Map<Integer, long[]> firstLoads = new HashMap<>();
    // Route names by index + 1 in the slots; 0 means none.
    private final List<String> routeNames = new ArrayList<>();
    private final Map<String, Integer> routeIndex = new HashMap<>();
    private long routeChars;
    private final LinkedHashMap<Long, Integer> lookups = new LinkedHashMap<>(16, 0.75f, false);
    // Each request's route slot, as a record or the journal named it, for its records drained in a later batch.
    private final LinkedHashMap<Long, Integer> requestRoutes = new LinkedHashMap<>(16, 0.75f, true);
    private long unrecorded;
    private String[] interns = new String[] {null};
    private long otherGenerations;
    private long outOfRange;
    private long version;
    private long clearedBefore = Long.MIN_VALUE;
    private Long clearedAt;
    private long clears;
    private volatile long retainedBytes;
    private volatile long firstCalls;
    private volatile long firstCallsWithRequest;
    private volatile long firstLoadCount;
    private volatile long internBytes;

    /** How many times a request's route is looked up before it is given up, as one evicted from the journal. */
    static final int MAX_LOOKUPS = 30;

    /**
     * @param generation the run's claim generation
     * @param access the bridge, whose intern table names the routes
     */
    public InventoryRecords(long generation, AgentBridgeAccess access) {
        this.generation = generation;
        this.access = access == null ? AgentBridgeAccess.absent() : access;
    }

    @Override
    public synchronized void accept(long[] record) {
        if (record[AgentRecordDrainer.GENERATION] != generation) {
            otherGenerations++;
            return;
        }
        int type = (int) record[AgentRecordDrainer.TYPE];
        int id = (int) record[AgentRecordDrainer.PAYLOAD];
        long time = record[AgentRecordDrainer.TIME];
        // Recorded before the latest clear, or in its millisecond, though drained after it: only its time is kept.
        boolean cleared = time <= clearedBefore;
        long request = cleared ? 0L : record[AgentRecordDrainer.PAYLOAD + 1];
        int route = cleared ? 0 : (int) record[AgentRecordDrainer.PAYLOAD + 2];
        int slot = routeSlot(intern(route));
        if (request != 0) {
            slot = rememberRoute(request, slot);
        }
        if (type == FIRST_HIT) {
            if (id < 0 || id >= MAX_METHODS) {
                outOfRange++;
                return;
            }
            if (present(id)) {
                return;
            }
            ensure(id);
            hitPresent[id >>> 6] |= 1L << id;
            hitRequest[id] = request;
            hitRoute[id] = slot;
            hitTime[id] = time;
            hitCount++;
            if (request != 0) {
                withRequest++;
            }
            version++;
        } else if (type == CLASS_LOAD && !firstLoads.containsKey(id)) {
            if (firstLoads.size() >= MAX_LOADS) {
                unrecorded++;
            } else {
                firstLoads.put(id, new long[] {request, slot, time});
                version++;
            }
        }
        publish();
    }

    private boolean present(int id) {
        int word = id >>> 6;
        return word < hitPresent.length && (hitPresent[word] & (1L << id)) != 0;
    }

    private void ensure(int id) {
        if (id < hitRequest.length) {
            return;
        }
        int capacity = Math.min(MAX_METHODS, Math.max(1_024, Integer.highestOneBit(id) << 1));
        hitRequest = Arrays.copyOf(hitRequest, capacity);
        hitRoute = Arrays.copyOf(hitRoute, capacity);
        hitTime = Arrays.copyOf(hitTime, capacity);
        hitPresent = Arrays.copyOf(hitPresent, (capacity + 63) >>> 6);
    }

    /**
     * {@code slot} when it names a route, remembered as {@code request}'s; else the route remembered for {@code request},
     * as one a record of the same request carried in an earlier batch, or 0.
     */
    private int rememberRoute(long request, int slot) {
        if (slot != 0) {
            requestRoutes.put(request, slot);
            while (requestRoutes.size() > MAX_REQUEST_ROUTES) {
                requestRoutes.remove(requestRoutes.keySet().iterator().next());
            }
            return slot;
        }
        Integer known = requestRoutes.get(request);
        return known == null ? 0 : known;
    }

    /** The slot value of {@code route}: its index + 1, or 0 for none or past {@value #MAX_ROUTE_NAMES} names. */
    private int routeSlot(String route) {
        if (route == null) {
            return 0;
        }
        Integer index = routeIndex.get(route);
        if (index == null) {
            if (routeNames.size() >= MAX_ROUTE_NAMES) {
                unrecorded++;
                return 0;
            }
            index = routeNames.size();
            routeNames.add(route);
            routeIndex.put(route, index);
            routeChars += route.length();
        }
        return index + 1;
    }

    private String routeName(int slot) {
        return slot <= 0 || slot > routeNames.size() ? null : routeNames.get(slot - 1);
    }

    /**
     * The routes of {@code requestIds} that {@code lookup} names now, by request id, among those never looked up or not
     * found fewer than {@value #MAX_LOOKUPS} times; each route found is written into every record of its request, so it
     * is not asked for again. Only those are looked up, outside this object's lock.
     */
    public Map<String, String> routes(Set<String> requestIds, Function<Set<String>, Map<String, String>> lookup) {
        Set<String> unseen = new HashSet<>();
        long clearsBefore;
        synchronized (this) {
            clearsBefore = clears;
            for (String id : requestIds) {
                Long request = requestValue(id);
                if (request != null && lookups.getOrDefault(request, 0) < MAX_LOOKUPS) {
                    unseen.add(id);
                }
            }
        }
        if (unseen.isEmpty() || lookup == null) {
            return Map.of();
        }
        Map<String, String> found;
        try {
            found = lookup.apply(Set.copyOf(unseen));
        } catch (RuntimeException ex) {
            found = null;
        }
        Map<String, String> known = new HashMap<>();
        synchronized (this) {
            if (clears != clearsBefore) {
                // Cleared during the lookup: those requests are no longer named.
                return Map.of();
            }
            Map<Long, Integer> slots = new HashMap<>();
            for (String id : unseen) {
                long request = requestValue(id);
                String route = found == null ? null : found.get(id);
                if (route != null) {
                    lookups.remove(request);
                    slots.put(request, rememberRoute(request, routeSlot(route)));
                    known.put(id, route);
                } else {
                    lookups.merge(request, 1, Integer::sum);
                    while (lookups.size() > MAX_PENDING_LOOKUPS) {
                        lookups.remove(lookups.keySet().iterator().next());
                    }
                }
            }
            if (!slots.isEmpty()) {
                name(slots);
                version++;
            }
            publish();
        }
        return known;
    }

    /** Writes each request's route slot into its records without a route, in one pass. */
    private void name(Map<Long, Integer> slots) {
        for (int id = 0; id < hitRequest.length; id++) {
            if (hitRequest[id] != 0 && hitRoute[id] == 0) {
                Integer slot = slots.get(hitRequest[id]);
                if (slot != null) {
                    hitRoute[id] = slot;
                }
            }
        }
        for (long[] load : firstLoads.values()) {
            if (load[0] != 0 && load[1] == 0) {
                Integer slot = slots.get(load[0]);
                if (slot != null) {
                    load[1] = slot;
                }
            }
        }
    }

    /**
     * Drops every request and route recorded before {@code epochMillis}, those of records still queued included, which
     * keep only their time; the pending lookups are forgotten. Which methods executed is kept.
     *
     * @return how many first calls and loads lost their request
     */
    public synchronized int clear(long epochMillis) {
        int dropped = 0;
        for (int id = 0; id < hitRequest.length; id++) {
            if (hitRequest[id] != 0 || hitRoute[id] != 0) {
                dropped++;
            }
            hitRequest[id] = 0L;
            hitRoute[id] = 0;
        }
        for (long[] load : firstLoads.values()) {
            if (load[0] != 0 || load[1] != 0) {
                dropped++;
            }
            load[0] = 0L;
            load[1] = 0;
        }
        withRequest = 0;
        lookups.clear();
        requestRoutes.clear();
        routeNames.clear();
        routeIndex.clear();
        routeChars = 0;
        clearedBefore = Math.max(clearedBefore, epochMillis);
        clearedAt = epochMillis;
        clears++;
        version++;
        publish();
        return dropped;
    }

    /** How many times the records were cleared. */
    public synchronized long clears() {
        return clears;
    }

    /** When the recording was last cleared, in epoch milliseconds, or {@code null}. */
    public synchronized Long clearedAt() {
        return clearedAt;
    }

    private void publish() {
        firstCalls = hitCount;
        firstCallsWithRequest = withRequest;
        firstLoadCount = firstLoads.size();
        retainedBytes = (long) hitRequest.length * SLOT_BYTES
                + (long) firstLoads.size() * LOAD_BYTES
                + (long) lookups.size() * LOOKUP_BYTES
                + (long) requestRoutes.size() * REQUEST_ROUTE_BYTES
                + (long) routeNames.size() * ROUTE_BYTES
                + routeChars * 2;
        internBytes = (long) interns.length * ROUTE_BYTES;
    }

    /** The estimated bytes these records retain, read without the lock. */
    public long retainedBytes() {
        return retainedBytes;
    }

    /**
     * The estimated bytes of the agent's interned strings this reads its routes from, read without the lock: they name
     * routes and code, kept through a clear, so they are counted apart.
     */
    public long internBytes() {
        return internBytes;
    }

    /** How many first loads or route names were not recorded, past their bounds. */
    public synchronized long unrecorded() {
        return unrecorded;
    }

    /** The methods with a first call recorded, read without the lock. */
    public long firstCalls() {
        return firstCalls;
    }

    /** The first calls that still name their request, read without the lock. */
    public long firstCallsWithRequest() {
        return firstCallsWithRequest;
    }

    /** The code sources with a first load recorded, read without the lock. */
    public long firstLoads() {
        return firstLoadCount;
    }

    /** A number that changes whenever a record or a resolved route is added, or the records are cleared. */
    public synchronized long version() {
        return version;
    }

    /** The first call of method {@code id} in this run, or {@code null} when none was recorded. */
    public synchronized First firstHit(int id) {
        if (id < 0 || !present(id)) {
            return null;
        }
        return new First(requestId(hitRequest[id]), routeName(hitRoute[id]), hitTime[id]);
    }

    /** The first class code source {@code id} loaded in this run, or {@code null} when none was recorded. */
    public synchronized First firstLoad(int id) {
        long[] load = firstLoads.get(id);
        return load == null ? null : new First(requestId(load[0]), routeName((int) load[1]), load[2]);
    }

    /** How many records belonged to another claim generation. */
    public synchronized long otherGenerations() {
        return otherGenerations;
    }

    /** How many first calls named a method id past the agent's method limit, and were ignored. */
    public synchronized long outOfRange() {
        return outOfRange;
    }

    /** The run's claim generation. */
    public long generation() {
        return generation;
    }

    /** A 64-bit request id as its 16 hexadecimal digits, or {@code null} for none. */
    static String requestId(long value) {
        return value == 0 ? null : String.format("%016x", value);
    }

    /** The 64 bits of a request id written as 16 hexadecimal digits, or {@code null} when it is not one. */
    private static Long requestValue(String id) {
        if (id == null || id.length() != 16) {
            return null;
        }
        try {
            long value = Long.parseUnsignedLong(id, 16);
            return value == 0 ? null : value;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** The string the record's intern id names, fetching the ids the table added since the last read. */
    private String intern(int id) {
        if (id <= 0) {
            return null;
        }
        if (id >= interns.length) {
            String[] added = access.interned(generation, interns.length);
            if (added != null && added.length > 0) {
                String[] grown = Arrays.copyOf(interns, interns.length + added.length);
                System.arraycopy(added, 0, grown, interns.length, added.length);
                interns = grown;
            }
        }
        if (id < interns.length && interns[id] == null) {
            // A slot that was not filled yet when the table was last read.
            String[] again = access.interned(generation, id);
            if (again != null && again.length > 0) {
                interns[id] = again[0];
            }
        }
        return id < interns.length ? interns[id] : null;
    }
}
