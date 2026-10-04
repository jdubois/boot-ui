package io.github.jdubois.bootui.engine.inventory;

import io.github.jdubois.bootui.engine.javaagent.AgentBridgeAccess;
import io.github.jdubois.bootui.engine.javaagent.AgentRecordDrainer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The inventory sensor's records for one run ({@code docs/PLAN-v2.md} §5.15): each method's first call and each code
 * source's first class in the run, with the request and route that made them and the time, routed here by the
 * {@link AgentRecordDrainer}. The executed set itself comes from the bridge's hit flags, which are exact; these records
 * only add the first request, route, and time, and a dropped record loses only those. Records of another claim
 * generation are counted and ignored. Bounded by the bridge's method and code-source limits.
 *
 * <p>A record carrying a request without its route, as when the framework matched the route only after the handler
 * started, is named from the journal once ({@link #routes}): the route found is kept by request id, and a request not
 * found yet, as one still in flight, is looked up again at most {@value #MAX_LOOKUPS} times, never the whole journal for
 * every read.
 */
public final class InventoryRecords implements Consumer<long[]> {

    /** A method's first call in the run ({@code CodeInventory.FIRST_HIT}). */
    static final int FIRST_HIT = 1;

    /** A code source's first class in the run ({@code CodeInventory.CLASS_LOAD}). */
    static final int CLASS_LOAD = 2;

    /**
     * When a method first ran, or a code source first loaded a class, in this run, and for which request.
     *
     * @param requestId the request, as 16 hexadecimal digits, or {@code null} outside a request
     * @param route its route, or {@code null}
     * @param epochMillis when
     */
    public record First(String requestId, String route, long epochMillis) {}

    private final long generation;
    private final AgentBridgeAccess access;
    private final Map<Integer, First> firstHits = new HashMap<>();
    private final Map<Integer, First> firstLoads = new HashMap<>();
    private final Map<String, String> requestRoutes = new HashMap<>();
    private final Map<String, Integer> lookups = new HashMap<>();
    private String[] interns = new String[] {null};
    private long otherGenerations;
    private long version;

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
        long request = record[AgentRecordDrainer.PAYLOAD + 1];
        int route = (int) record[AgentRecordDrainer.PAYLOAD + 2];
        long time = record[AgentRecordDrainer.TIME];
        Map<Integer, First> target =
                switch (type) {
                    case FIRST_HIT -> firstHits;
                    case CLASS_LOAD -> firstLoads;
                    default -> null;
                };
        if (target != null && !target.containsKey(id)) {
            target.put(id, new First(requestId(request), intern(route), time));
            version++;
        }
    }

    /**
     * The routes of {@code requestIds}, by request id, as far as known: those resolved before, and those {@code lookup}
     * names now among the ones never looked up or not found fewer than {@value #MAX_LOOKUPS} times. Only those are
     * looked up, outside this object's lock.
     */
    public Map<String, String> routes(Set<String> requestIds, Function<Set<String>, Map<String, String>> lookup) {
        Map<String, String> known = new HashMap<>();
        Set<String> unseen = new HashSet<>();
        synchronized (this) {
            for (String id : requestIds) {
                String route = requestRoutes.get(id);
                if (route != null) {
                    known.put(id, route);
                } else if (lookups.getOrDefault(id, 0) < MAX_LOOKUPS) {
                    unseen.add(id);
                }
            }
        }
        if (unseen.isEmpty() || lookup == null) {
            return known;
        }
        Map<String, String> found;
        try {
            found = lookup.apply(Set.copyOf(unseen));
        } catch (RuntimeException ex) {
            found = null;
        }
        synchronized (this) {
            for (String id : unseen) {
                String route = found == null ? null : found.get(id);
                if (route != null) {
                    requestRoutes.put(id, route);
                    lookups.remove(id);
                    known.put(id, route);
                    version++;
                } else {
                    lookups.merge(id, 1, Integer::sum);
                }
            }
        }
        return known;
    }

    /** A number that changes whenever a record or a resolved route is added. */
    public synchronized long version() {
        return version;
    }

    /** The first call of method {@code id} in this run, or {@code null} when none was recorded. */
    public synchronized First firstHit(int id) {
        return firstHits.get(id);
    }

    /** The first class code source {@code id} loaded in this run, or {@code null} when none was recorded. */
    public synchronized First firstLoad(int id) {
        return firstLoads.get(id);
    }

    /** How many records belonged to another claim generation. */
    public synchronized long otherGenerations() {
        return otherGenerations;
    }

    /** The run's claim generation. */
    public long generation() {
        return generation;
    }

    /** A 64-bit request id as its 16 hexadecimal digits, or {@code null} for none. */
    static String requestId(long value) {
        return value == 0 ? null : String.format("%016x", value);
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
