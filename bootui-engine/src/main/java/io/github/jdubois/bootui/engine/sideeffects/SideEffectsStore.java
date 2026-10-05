package io.github.jdubois.bootui.engine.sideeffects;

import io.github.jdubois.bootui.core.dto.SideEffectsRowDto;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * One run's Side Effects rows ({@code docs/PLAN-v2.md} §5.16, M5-5a), kept outside the runtime journal under the agent
 * evidence contract ({@code AgentEvidence}, M5-11): <b>Clear recording</b> clears them, their memory is accounted, and
 * their bounds shrink with a configured agent evidence bound.
 *
 * <p>Each observation is attributed, in this order, to its request's route, to its execution by kind (a scheduled run,
 * a consumed message, a WebSocket message), to startup, or to its thread's family, else it is unattributed. A request's
 * route comes from the runtime journal's HTTP exchange of that request, and an execution's label from its own event,
 * both recorded once the work is over, so observations wait, at most {@value #MAX_PENDING} of them, until named or
 * {@value #PENDING_MILLIS} ms passed, when a request's count under {@value #UNKNOWN_ROUTE} and an execution's under
 * {@value #BACKGROUND}. Rows are keyed by attribution, sensor, kind, normalized target, call site, and the bean method
 * it happened inside; a sensor keeps at most {@value #MAX_ROWS_PER_SENSOR} rows and the store {@value #MAX_ROWS} by
 * default, and what does not fit is counted in the sensor's one Other row. Route attribution is HTTP Exchanges
 * evidence: while that panel is hidden, reads show every
 * route row under {@value #ROUTE_HIDDEN}, without exemplar request ids. Not thread-safe: its service serializes it.
 */
final class SideEffectsStore {

    static final int MAX_ROWS_PER_SENSOR = 500;
    static final int MAX_ROWS = 2_000;
    static final int MAX_PENDING = 10_000;
    static final long PENDING_MILLIS = 30_000L;
    static final int EXEMPLARS = 3;
    static final int ROUTE_CACHE = 4_096;

    static final String UNKNOWN_ROUTE = "(unknown route)";
    static final String ROUTE_HIDDEN = "(route hidden: HTTP Exchanges is disabled)";
    static final String BACKGROUND = "work no request owns";
    static final String STARTUP = "startup";
    static final String UNATTRIBUTED = "(unattributed)";
    static final String OTHER = "Other";

    /** Approximate bytes of one row and of one waiting observation, for the memory accounting. */
    static final long ROW_BYTES = 640L;

    static final long PENDING_BYTES = 320L;

    /** Approximate bytes of one cached request route. */
    static final long ROUTE_BYTES = 96L;

    /** What a store observes: a record, its strings resolved and its target normalized. */
    record Observation(
            SideEffectRecord record,
            String sensor,
            String kind,
            String target,
            String callSite,
            String insideMethod,
            String threadFamily) {}

    private record Key(
            String scope,
            String attribution,
            String sensor,
            String kind,
            String target,
            String callSite,
            String insideMethod) {}

    private static final class Row {
        final Key key;
        long count;
        long failed;
        long completed;
        long nonZeroExits;
        Integer lastExitStatus;
        long nanos;
        long maxNanos;
        long firstSeen = Long.MAX_VALUE;
        long lastSeen;
        final List<String> exemplars = new ArrayList<>(EXEMPLARS);

        Row(Key key) {
            this.key = key;
        }

        void add(Observation observation, String requestId) {
            SideEffectRecord record = observation.record();
            if (record.kind() == SideEffectsCatalog.KIND_PROCESS_EXIT) {
                completed += record.count();
                if (record.outcome() == SideEffectsCatalog.OUTCOME_EXITED) {
                    if (record.exitStatus() != 0) {
                        nonZeroExits += record.count();
                    }
                    lastExitStatus = record.exitStatus();
                }
                nanos += record.nanos();
                maxNanos = Math.max(maxNanos, record.maxNanos());
            } else {
                count += record.count();
                if (record.outcome() == SideEffectsCatalog.OUTCOME_IO_ERROR
                        || record.outcome() == SideEffectsCatalog.OUTCOME_ERROR) {
                    failed += record.count();
                }
            }
            firstSeen = Math.min(firstSeen, record.firstMillis());
            lastSeen = Math.max(lastSeen, record.lastMillis());
            if (requestId != null && exemplars.size() < EXEMPLARS && !exemplars.contains(requestId)) {
                exemplars.add(requestId);
            }
        }

        void merge(Row other, boolean withExemplars) {
            count += other.count;
            failed += other.failed;
            completed += other.completed;
            nonZeroExits += other.nonZeroExits;
            if (other.lastExitStatus != null && (lastExitStatus == null || other.lastSeen >= lastSeen)) {
                lastExitStatus = other.lastExitStatus;
            }
            nanos += other.nanos;
            maxNanos = Math.max(maxNanos, other.maxNanos);
            firstSeen = Math.min(firstSeen, other.firstSeen);
            lastSeen = Math.max(lastSeen, other.lastSeen);
            if (withExemplars) {
                for (String id : other.exemplars) {
                    if (exemplars.size() < EXEMPLARS && !exemplars.contains(id)) {
                        exemplars.add(id);
                    }
                }
            }
        }

        SideEffectsRowDto dto() {
            return new SideEffectsRowDto(
                    key.scope(),
                    key.attribution(),
                    key.sensor(),
                    key.kind(),
                    key.target(),
                    key.callSite(),
                    key.insideMethod(),
                    count,
                    failed,
                    completed,
                    nonZeroExits,
                    lastExitStatus,
                    nanos / 1_000_000L,
                    maxNanos / 1_000_000L,
                    firstSeen == Long.MAX_VALUE ? 0L : firstSeen,
                    lastSeen,
                    exemplars);
        }
    }

    /**
     * An observation waiting for its owner's name: a request's route, or an execution's label, under {@code key}, the
     * request id, or {@value #EXECUTION_KEY} and the execution id.
     */
    private record Pending(Observation observation, String key, long since) {

        boolean execution() {
            return key.startsWith(EXECUTION_KEY);
        }
    }

    /** The key prefix of an execution no request owns, among the names the store waits for. */
    static final String EXECUTION_KEY = "execution:";

    private final long readyAt;
    private final int maxRows;
    private final int maxRowsPerSensor;
    private final int maxPending;
    private final Map<Key, Row> rows = new LinkedHashMap<>();
    private final Map<String, Integer> rowsPerSensor = new HashMap<>();
    private final Map<String, Long> droppedPerSensor = new HashMap<>();
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private final LinkedHashMap<String, String> routes = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > ROUTE_CACHE;
        }
    };

    /** When the reader last did not name a waiting request, by request id: asked again only after a while. */
    private final Map<String, Long> misses = new HashMap<>();

    private long observations;
    private long folded;

    /** @param readyAt when the application finished starting, in epoch milliseconds: earlier observations are startup's */
    SideEffectsStore(long readyAt) {
        this(readyAt, MAX_ROWS, MAX_ROWS_PER_SENSOR, MAX_PENDING);
    }

    /** With the bounds a configured agent evidence bound scaled (M5-11). */
    SideEffectsStore(long readyAt, int maxRows, int maxRowsPerSensor, int maxPending) {
        this.readyAt = readyAt;
        this.maxRows = maxRows;
        this.maxRowsPerSensor = Math.min(maxRowsPerSensor, maxRows);
        this.maxPending = maxPending;
    }

    /** The rows retained, Other rows included. */
    int rowCount() {
        return rows.size();
    }

    /** The estimated bytes retained: rows, waiting observations, and the route cache. */
    long retainedBytes() {
        return rows.size() * ROW_BYTES + pending.size() * PENDING_BYTES + routes.size() * ROUTE_BYTES;
    }

    /** The most bytes this store holds under its bounds. */
    long maxBytes() {
        return (maxRows + SideEffectsCatalog.SENSORS.size()) * ROW_BYTES
                + (long) maxPending * PENDING_BYTES
                + ROUTE_CACHE * ROUTE_BYTES;
    }

    /** Drops every row and waiting observation, and the routes it named: <b>Clear recording</b>. */
    void clear() {
        rows.clear();
        rowsPerSensor.clear();
        pending.clear();
        routes.clear();
        misses.clear();
        folded = 0;
    }

    /** Adds one observation: a request's waits for its route, any other is attributed now. */
    void add(Observation observation) {
        observations++;
        SideEffectRecord record = observation.record();
        String requestId = record.requestId();
        String executionId = requestId == null ? record.executionId() : null;
        String key = requestId != null ? requestId : executionId == null ? null : EXECUTION_KEY + executionId;
        if (key != null) {
            String name = routes.get(key);
            if (name != null) {
                attribute(observation, key, name);
                return;
            }
            if (pending.size() >= maxPending) {
                drop(observation.sensor(), record.count());
                return;
            }
            pending.add(new Pending(observation, key, record.lastMillis()));
            return;
        }
        if (record.firstMillis() < readyAt) {
            aggregate(observation, SideEffectsRowDto.STARTUP, STARTUP, null);
        } else if (observation.threadFamily() != null) {
            aggregate(observation, SideEffectsRowDto.THREAD, observation.threadFamily(), null);
        } else {
            aggregate(observation, SideEffectsRowDto.UNATTRIBUTED, UNATTRIBUTED, null);
        }
    }

    /** The observations waiting for their request's route. */
    int pendingCount() {
        return pending.size();
    }

    /** The keys waiting for a name: request ids, and {@value #EXECUTION_KEY} execution ids. */
    Set<String> pendingRequests() {
        Set<String> ids = new HashSet<>();
        for (Pending waiting : pending) {
            ids.add(waiting.key());
        }
        return ids;
    }

    /** A named observation: a request's under its route, an execution's under its label. */
    private void attribute(Observation observation, String key, String name) {
        if (key.startsWith(EXECUTION_KEY)) {
            aggregate(observation, SideEffectsRowDto.EXECUTION, name, null);
        } else {
            aggregate(observation, SideEffectsRowDto.ROUTE, name, key);
        }
    }

    /**
     * Attributes the waiting observations whose route or execution label {@code named} names, and those waiting since
     * before {@code now - PENDING_MILLIS} to {@value #UNKNOWN_ROUTE} or {@value #BACKGROUND}.
     */
    void resolve(Map<String, String> named, long now) {
        if (named != null) {
            for (Map.Entry<String, String> entry : named.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    routes.put(entry.getKey(), entry.getValue());
                }
            }
        }
        Iterator<Pending> iterator = pending.iterator();
        while (iterator.hasNext()) {
            Pending waiting = iterator.next();
            String name = routes.get(waiting.key());
            if (name != null) {
                attribute(waiting.observation(), waiting.key(), name);
                iterator.remove();
            } else if (now - waiting.since() >= PENDING_MILLIS) {
                attribute(waiting.observation(), waiting.key(), waiting.execution() ? BACKGROUND : UNKNOWN_ROUTE);
                iterator.remove();
            }
        }
    }

    /**
     * {@link #resolve(Map, long)} with the routes {@code reader} names for the waiting requests: a request is asked for
     * when it first waits, and again only once {@code retryMillis} passed since the reader last did not name it.
     */
    void resolve(Function<Set<String>, Map<String, String>> reader, long now, long retryMillis) {
        if (pending.isEmpty()) {
            return;
        }
        Set<String> ids = new HashSet<>();
        for (String id : pendingRequests()) {
            Long missed = misses.get(id);
            if (!routes.containsKey(id) && (missed == null || now - missed >= retryMillis)) {
                ids.add(id);
            }
        }
        Map<String, String> named = Map.of();
        if (!ids.isEmpty() && reader != null) {
            try {
                Map<String, String> read = reader.apply(ids);
                named = read == null ? Map.of() : read;
            } catch (RuntimeException ex) {
                named = Map.of();
            }
            for (String id : ids) {
                if (!named.containsKey(id)) {
                    misses.put(id, now);
                }
            }
        }
        resolve(named, now);
        misses.keySet().retainAll(pendingRequests());
    }

    private void aggregate(Observation observation, String scope, String attribution, String requestId) {
        String sensor = observation.sensor();
        Key key = new Key(
                scope,
                attribution,
                sensor,
                observation.kind(),
                observation.target(),
                observation.callSite(),
                observation.insideMethod());
        Row row = rows.get(key);
        if (row == null) {
            int perSensor = rowsPerSensor.getOrDefault(sensor, 0);
            if (perSensor >= maxRowsPerSensor || rows.size() >= maxRows) {
                Key other = new Key(SideEffectsRowDto.OTHER, OTHER, sensor, observation.kind(), OTHER, null, null);
                row = rows.get(other);
                if (row == null) {
                    if (rows.size() >= maxRows + SideEffectsCatalog.SENSORS.size()) {
                        drop(sensor, observation.record().count());
                        return;
                    }
                    row = new Row(other);
                    rows.put(other, row);
                }
                folded += observation.record().count();
                row.add(observation, null);
                return;
            }
            row = new Row(key);
            rows.put(key, row);
            rowsPerSensor.put(sensor, perSensor + 1);
        }
        row.add(observation, requestId);
    }

    private void drop(String sensor, long count) {
        droppedPerSensor.merge(sensor, count, Long::sum);
    }

    /**
     * {@code sensor}'s rows, most frequent first, the Other row last. While {@code routesVisible} is false, route rows
     * are merged under {@value #ROUTE_HIDDEN} and lose their exemplar request ids; while {@code codePathsVisible} is
     * false, rows lose the bean method they happened inside, which is Code Paths' evidence, and merge without it.
     */
    List<SideEffectsRowDto> rows(String sensor, boolean routesVisible, boolean codePathsVisible) {
        Map<Key, Row> merged = new LinkedHashMap<>();
        for (Row row : rows.values()) {
            if (!row.key.sensor().equals(sensor)) {
                continue;
            }
            boolean hideRoute = !routesVisible && SideEffectsRowDto.ROUTE.equals(row.key.scope());
            boolean hideMethod = !codePathsVisible && row.key.insideMethod() != null;
            Key shown = new Key(
                    row.key.scope(),
                    hideRoute ? ROUTE_HIDDEN : row.key.attribution(),
                    row.key.sensor(),
                    row.key.kind(),
                    row.key.target(),
                    row.key.callSite(),
                    hideMethod ? null : row.key.insideMethod());
            // Always a copy: a read never changes the store's own rows.
            Row target = merged.get(shown);
            if (target == null) {
                target = new Row(shown);
                merged.put(shown, target);
            }
            target.merge(row, !hideRoute);
        }
        List<SideEffectsRowDto> list = new ArrayList<>();
        for (Row row : merged.values()) {
            list.add(row.dto());
        }
        list.sort(Comparator.comparing((SideEffectsRowDto row) -> SideEffectsRowDto.OTHER.equals(row.scope()))
                .thenComparing(Comparator.comparingLong((SideEffectsRowDto row) -> row.count() + row.completed())
                        .reversed())
                .thenComparing(SideEffectsRowDto::lastSeen, Comparator.reverseOrder()));
        return list;
    }

    /** {@code sensor}'s rows, the Other row included. */
    long rowCount(String sensor) {
        long count = 0;
        for (Key key : rows.keySet()) {
            if (key.sensor().equals(sensor)) {
                count++;
            }
        }
        return count;
    }

    /** The operations {@code sensor}'s rows count: starts, for processes. */
    long occurrences(String sensor) {
        long count = 0;
        for (Row row : rows.values()) {
            if (row.key.sensor().equals(sensor)) {
                count += row.count;
            }
        }
        return count;
    }

    /** What {@code sensor} saw that no row counts. */
    long dropped(String sensor) {
        return droppedPerSensor.getOrDefault(sensor, 0L);
    }

    /** The rows a sensor keeps apart before its Other row. */
    int maxRowsPerSensor() {
        return maxRowsPerSensor;
    }

    /** The rows the run keeps apart before Other rows. */
    int maxRows() {
        return maxRows;
    }

    /** Observations added since the run started. */
    long observations() {
        return observations;
    }

    /** Operations counted in an Other row because their sensor or the store was at its cap, since the last clear. */
    long folded() {
        return folded;
    }
}
