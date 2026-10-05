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
 * route row under {@value #ROUTE_HIDDEN}, without exemplar request ids.
 *
 * <p>A network connect or datagram (M5-5b) is also keyed by its client and how a panel captures it. A client whose
 * work SQL Trace, a messaging panel, or Email shows is keyed by that category, and whether that panel captured it is
 * decided on read, from whether that panel is visible, as a pool opens its connections before its first statement. Any other connect waits until a REST client call of the same owner, or, unowned, at the same time, names
 * its host and port, or until it no longer can: {@value #CAPTURE_GRACE_MILLIS} ms after its owner was named (its
 * request ended, or since the connect when later), or, for an execution, {@value #UNOWNED_HTTP_CAPTURE_MILLIS} ms, since
 * any of its events names it while it runs; an unowned one waits {@value #UNOWNED_CAPTURE_MILLIS} ms, or {@value
 * #UNOWNED_HTTP_CAPTURE_MILLIS} ms for a recognized HTTP client; then it is not captured. Not thread-safe:
 * its service serializes it.
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

    /** How long an owned connect whose owner was named still waits for a REST client call to name it. */
    static final long CAPTURE_GRACE_MILLIS = 2_000L;

    /** How long an unowned connect waits for a REST client call at the same time. */
    static final long UNOWNED_CAPTURE_MILLIS = 10_000L;

    /** How a network row is captured, as rows are keyed: decided now, by category on read, or waiting. */
    static final String CAPTURE_INFRASTRUCTURE = "infrastructure";

    static final String CAPTURE_SQL = "sql";
    static final String CAPTURE_MAIL = "mail";
    static final String CAPTURE_MESSAGING = "messaging:";
    static final String REST_CAPTURED = "rest:captured";
    static final String REST_NOT_CAPTURED = "rest:not-captured";
    static final String REST_WAITING = "rest:waiting";

    /** An HTTP client's connection waiting for a REST client call, which may take long to be recorded. */
    static final String REST_WAITING_HTTP = "rest:waiting-http";

    /** How long an unowned HTTP client's connect waits: its call is recorded once it completes. */
    static final long UNOWNED_HTTP_CAPTURE_MILLIS = 60_000L;

    /** The connect decisions kept for their finish records at most. */
    static final int MAX_CONNECT_DECISIONS = 4_096;

    /**
     * What a store observes: a record, its strings resolved and its target normalized; for a network record, its
     * recognized client, how it is captured ({@code captureKey}), and its host and port.
     */
    record Observation(
            SideEffectRecord record,
            String sensor,
            String kind,
            String target,
            String callSite,
            String insideMethod,
            String threadFamily,
            String client,
            String captureKey,
            String host,
            int port,
            String origin,
            String location) {

        /** A network observation: no origin or location. */
        Observation(
                SideEffectRecord record,
                String sensor,
                String kind,
                String target,
                String callSite,
                String insideMethod,
                String threadFamily,
                String client,
                String captureKey,
                String host,
                int port) {
            this(
                    record,
                    sensor,
                    kind,
                    target,
                    callSite,
                    insideMethod,
                    threadFamily,
                    client,
                    captureKey,
                    host,
                    port,
                    null,
                    null);
        }

        /** A files or environment observation, with its origin and, for a file, its location. */
        Observation(
                SideEffectRecord record,
                String sensor,
                String kind,
                String target,
                String callSite,
                String insideMethod,
                String threadFamily,
                String origin,
                String location) {
            this(
                    record,
                    sensor,
                    kind,
                    target,
                    callSite,
                    insideMethod,
                    threadFamily,
                    null,
                    null,
                    null,
                    -1,
                    origin,
                    location);
        }

        /** An observation of a sensor that is not captured by a panel. */
        Observation(
                SideEffectRecord record,
                String sensor,
                String kind,
                String target,
                String callSite,
                String insideMethod,
                String threadFamily) {
            this(record, sensor, kind, target, callSite, insideMethod, threadFamily, null, null, null, -1, null, null);
        }

        Observation withCapture(String decided) {
            return new Observation(
                    record,
                    sensor,
                    kind,
                    target,
                    callSite,
                    insideMethod,
                    threadFamily,
                    client,
                    decided,
                    host,
                    port,
                    origin,
                    location);
        }

        boolean waiting() {
            return REST_WAITING.equals(captureKey) || REST_WAITING_HTTP.equals(captureKey);
        }
    }

    private record Key(
            String scope,
            String attribution,
            String sensor,
            String kind,
            String target,
            String callSite,
            String insideMethod,
            String client,
            String captureKey,
            String origin,
            String location) {}

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
            if (record.sensor() == SideEffectsCatalog.RECORD_NETWORK) {
                network(record);
            } else if (SideEffectsCatalog.processExit(record.sensor(), record.kind())) {
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
                if (SideEffectsCatalog.failed(record.outcome())) {
                    // For blocking, an interrupted call too.
                    failed += record.count();
                }
                if (record.sensor() == SideEffectsCatalog.RECORD_FILES
                        || record.sensor() == SideEffectsCatalog.RECORD_BLOCKING) {
                    // A file operation's own time, never reading what it opened; or how long the event loop was
                    // blocked, in all and at most.
                    nanos += record.nanos();
                    maxNanos = Math.max(maxNanos, record.maxNanos());
                }
            }
            firstSeen = Math.min(firstSeen, record.firstMillis());
            lastSeen = Math.max(lastSeen, record.lastMillis());
            if (requestId != null && exemplars.size() < EXEMPLARS && !exemplars.contains(requestId)) {
                exemplars.add(requestId);
            }
        }

        /**
         * A network record: a connect counts an attempt, established or failed, with its time when it was blocking;
         * a non-blocking connect's finish, established or failed, with its time; a datagram or a lookup counts with
         * its time.
         */
        private void network(SideEffectRecord record) {
            boolean failure = SideEffectsCatalog.failed(record.outcome());
            if (record.kind() != SideEffectsCatalog.KIND_CONNECT_FINISH) {
                count += record.count();
            }
            if (failure) {
                failed += record.count();
            } else if (record.outcome() == SideEffectsCatalog.OUTCOME_CONNECTED) {
                completed += record.count();
            }
            if (record.outcome() != SideEffectsCatalog.OUTCOME_PENDING) {
                nanos += record.nanos();
                maxNanos = Math.max(maxNanos, record.maxNanos());
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

        SideEffectsRowDto dto(String[] capture) {
            return new SideEffectsRowDto(
                    key.scope(),
                    key.attribution(),
                    key.sensor(),
                    key.kind(),
                    key.target(),
                    key.callSite(),
                    key.insideMethod(),
                    key.origin(),
                    key.location(),
                    count,
                    failed,
                    completed,
                    nonZeroExits,
                    lastExitStatus,
                    nanos / 1_000_000L,
                    maxNanos / 1_000_000L,
                    firstSeen == Long.MAX_VALUE ? 0L : firstSeen,
                    lastSeen,
                    exemplars,
                    key.client(),
                    capture == null ? null : capture[0],
                    capture == null ? null : capture[1]);
        }
    }

    /**
     * An observation waiting for its owner's name: a request's route, or an execution's label, under {@code key}, the
     * request id, or {@value #EXECUTION_KEY} and the execution id; or, with no key, an unowned connect waiting only to
     * know whether a REST client call captured it.
     */
    private record Pending(Observation observation, String key, long since) {

        boolean execution() {
            return key != null && key.startsWith(EXECUTION_KEY);
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

    /**
     * When each waiting key was first named, by key: a waiting connect's grace runs from then, or from its own time when
     * later, never from before its owner was named.
     */
    private final Map<String, Long> namedAt = new HashMap<>();

    /** When the reader last did not name a waiting request, by request id: asked again only after a while. */
    private final Map<String, Long> misses = new HashMap<>();

    private long observations;
    private long folded;
    private long version;

    /**
     * How each waiting connect was decided, by its owner, target, call site, and start, so the finish record of a
     * non-blocking connect is decided as its connect was, never into a row of its own.
     */
    private final LinkedHashMap<String, String> connectDecisions = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_CONNECT_DECISIONS;
        }
    };

    private NetworkCapture capture = NetworkCapture.NONE;

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

    /** Installs what decides whether a REST client call captured a network observation. */
    void setCapture(NetworkCapture capture) {
        this.capture = capture == null ? NetworkCapture.NONE : capture;
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
        namedAt.clear();
        connectDecisions.clear();
        folded = 0;
        version++;
    }

    /** Changes whenever a row is added to or the store is cleared: a cheap fingerprint of its rows. */
    long version() {
        return version;
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
            if (name != null && !observation.waiting()) {
                attribute(observation, key, name);
                return;
            }
            if (pending.size() >= maxPending) {
                if (name != null) {
                    // Full: decided now rather than dropped.
                    attribute(decide(observation, true), key, name);
                } else {
                    drop(observation.sensor(), record.count());
                }
                return;
            }
            pending.add(new Pending(observation, key, record.lastMillis()));
            return;
        }
        if (observation.waiting()) {
            if (pending.size() >= maxPending) {
                attributeUnowned(decide(observation, true));
            } else {
                pending.add(new Pending(observation, null, record.lastMillis()));
            }
            return;
        }
        attributeUnowned(observation);
    }

    /**
     * {@code observation} with its capture decided: captured when a REST client call names it, else not captured when
     * {@code force}; {@code null} while it still waits.
     */
    private Observation decide(Observation observation, boolean force) {
        if (!observation.waiting()) {
            return observation;
        }
        SideEffectRecord record = observation.record();
        String connect = connectKey(observation);
        if (record.kind() == SideEffectsCatalog.KIND_CONNECT_FINISH) {
            String decided = connectDecisions.get(connect);
            if (decided != null) {
                return observation.withCapture(decided);
            }
            if (!force) {
                // Its connect, published first, is decided first.
                return null;
            }
        }
        boolean matched;
        try {
            matched = capture.restClient(
                    observation.host(),
                    observation.port(),
                    record.requestId(),
                    record.executionId(),
                    record.firstMillis(),
                    record.lastMillis());
        } catch (RuntimeException ex) {
            matched = false;
        }
        String decided = matched ? REST_CAPTURED : force ? REST_NOT_CAPTURED : null;
        if (decided != null && record.kind() == SideEffectsCatalog.KIND_CONNECT) {
            connectDecisions.put(connect, decided);
        }
        return decided == null ? null : observation.withCapture(decided);
    }

    /** What a connect and its finish record share: owner, target, call site, and start. */
    private static String connectKey(Observation observation) {
        SideEffectRecord record = observation.record();
        return record.request() + "|" + record.execution() + "|" + observation.target() + "|" + observation.callSite()
                + "|" + record.firstMillis();
    }

    /** An unowned observation: startup's, its thread family's, or unattributed. */
    private void attributeUnowned(Observation observation) {
        SideEffectRecord record = observation.record();
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
            if (waiting.key() != null) {
                ids.add(waiting.key());
            }
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
        boolean refreshed = false;
        Iterator<Pending> iterator = pending.iterator();
        while (iterator.hasNext()) {
            Pending waiting = iterator.next();
            Observation observation = waiting.observation();
            if (observation.waiting() && !refreshed) {
                refreshCapture();
                refreshed = true;
            }
            long age = now - waiting.since();
            if (waiting.key() == null) {
                long wait = REST_WAITING_HTTP.equals(observation.captureKey())
                        ? UNOWNED_HTTP_CAPTURE_MILLIS
                        : UNOWNED_CAPTURE_MILLIS;
                Observation decided = decide(observation, age >= wait);
                if (decided != null) {
                    attributeUnowned(decided);
                    iterator.remove();
                }
                continue;
            }
            String name = routes.get(waiting.key());
            if (name != null) {
                // A request is named once it ended, so its REST calls are recorded: a short grace. An execution may be
                // named by any of its events while it still runs, so its calls may be recorded much later.
                long namedSince = namedAt.computeIfAbsent(waiting.key(), key -> now);
                long grace = waiting.execution() ? UNOWNED_HTTP_CAPTURE_MILLIS : CAPTURE_GRACE_MILLIS;
                Observation decided = decide(observation, now - Math.max(namedSince, waiting.since()) >= grace);
                if (decided != null) {
                    attribute(decided, waiting.key(), name);
                    iterator.remove();
                }
            } else if (age >= PENDING_MILLIS) {
                attribute(decide(observation, true), waiting.key(), waiting.execution() ? BACKGROUND : UNKNOWN_ROUTE);
                iterator.remove();
            }
        }
    }

    private void refreshCapture() {
        try {
            capture.refresh();
        } catch (RuntimeException ex) {
            // Decided from what was indexed before.
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
        Set<String> waiting = pendingRequests();
        misses.keySet().retainAll(waiting);
        namedAt.keySet().retainAll(waiting);
    }

    private void aggregate(Observation observation, String scope, String attribution, String requestId) {
        version++;
        String sensor = observation.sensor();
        Key key = new Key(
                scope,
                attribution,
                sensor,
                observation.kind(),
                observation.target(),
                observation.callSite(),
                observation.insideMethod(),
                observation.client(),
                observation.captureKey(),
                observation.origin(),
                observation.location());
        Row row = rows.get(key);
        if (row == null) {
            int perSensor = rowsPerSensor.getOrDefault(sensor, 0);
            if (perSensor >= maxRowsPerSensor || rows.size() >= maxRows) {
                Key other = new Key(
                        SideEffectsRowDto.OTHER,
                        OTHER,
                        sensor,
                        observation.kind(),
                        OTHER,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
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
        return rows(sensor, routesVisible, codePathsVisible, captureKey -> null);
    }

    /**
     * {@link #rows(String, boolean, boolean)}, a network row's capture key read through {@code captures} as {@code
     * {capture, capturedBy}}, which says on read whether a visible panel shows it.
     */
    List<SideEffectsRowDto> rows(
            String sensor, boolean routesVisible, boolean codePathsVisible, Function<String, String[]> captures) {
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
                    hideMethod ? null : row.key.insideMethod(),
                    row.key.client(),
                    row.key.captureKey(),
                    row.key.origin(),
                    row.key.location());
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
            list.add(row.dto(row.key.captureKey() == null ? null : captures.apply(row.key.captureKey())));
        }
        list.sort(Comparator.comparing((SideEffectsRowDto row) -> SideEffectsRowDto.OTHER.equals(row.scope()))
                .thenComparing(Comparator.comparingLong((SideEffectsRowDto row) -> row.count() + row.completed())
                        .reversed())
                .thenComparing(SideEffectsRowDto::lastSeen, Comparator.reverseOrder()));
        return list;
    }

    /**
     * One network row an execution or a call site opened: its scope and attribution, its target, its call site, and its
     * count; for the runtime model's {@code OPENS} edges.
     */
    record Opened(String scope, String attribution, String target, String callSite, long count) {}

    /** The connects and datagrams of {@code sensor}'s rows, route rows only while {@code routesVisible}. */
    List<Opened> opened(String sensor, boolean routesVisible) {
        List<Opened> opened = new ArrayList<>();
        for (Row row : rows.values()) {
            Key key = row.key;
            if (!key.sensor().equals(sensor)
                    || SideEffectsRowDto.OTHER.equals(key.scope())
                    || SideEffectsCatalog.LOOKUP.equals(key.kind())
                    || row.count + row.completed == 0) {
                continue;
            }
            if (SideEffectsRowDto.ROUTE.equals(key.scope()) && !routesVisible) {
                continue;
            }
            opened.add(new Opened(key.scope(), key.attribution(), key.target(), key.callSite(), row.count));
        }
        return opened;
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
