package io.github.jdubois.bootui.engine.codepaths;

import io.github.jdubois.bootui.core.dto.CodePathsProbeDto;
import io.github.jdubois.bootui.core.dto.CodePathsProbeHitDto;
import io.github.jdubois.bootui.core.dto.CodePathsProbesReport;
import io.github.jdubois.bootui.engine.javaagent.AgentBridgeAccess;
import io.github.jdubois.bootui.engine.javaagent.AgentClaim;
import io.github.jdubois.bootui.engine.javaagent.AgentRecordDrainer;
import io.github.jdubois.bootui.engine.journal.AgentEvidence;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Method probes for one application ({@code docs/PLAN-v2.md} §5.14, M5-8): starts and stops probes through this run's
 * claim on the BootUI agent, routes their recorded invocations from the claim's {@link AgentRecordDrainer}, and keeps
 * this run's last {@value #MAX_PROBES} probes with at most {@value #MAX_HITS} invocations each. Metadata only: the
 * agent records no argument or return value. The bounds (invocations, window, probes at once) are the agent bridge's,
 * enforced where the method runs; this service keeps only JDK types and the bridge's answers, never a class or a class
 * loader. Starting and stopping are actions: adapters block them under read-only policy before calling here. The
 * probes and their invocations are a store of the agent evidence contract ({@link AgentEvidence}, M5-11): hidden with
 * the Code Paths panel, their request ids left out while HTTP Exchanges is, counted, and cleared by <b>Clear
 * recording</b>.
 */
public final class MethodProbeService implements AutoCloseable {

    /** Probes kept per run, newest first. */
    public static final int MAX_PROBES = 25;

    /** Invocations kept per probe: the bridge's bound. */
    public static final int MAX_HITS = 20;

    /** Probes at once, invocations, and window: the bridge's bounds, as the panel shows them. */
    public static final int MAX_ACTIVE = 5;

    public static final int MAX_INVOCATIONS = 20;
    public static final long WINDOW_SECONDS = 60;

    /** Return types whose method returns before its work runs: a probe times their assembly only. */
    static final Set<String> ASYNC_TYPES = Set.of(
            "Lreactor/core/publisher/Mono;",
            "Lreactor/core/publisher/Flux;",
            "Lorg/reactivestreams/Publisher;",
            "Ljava/util/concurrent/CompletionStage;",
            "Ljava/util/concurrent/CompletableFuture;",
            "Ljava/util/concurrent/Future;",
            "Lio/smallrye/mutiny/Uni;",
            "Lio/smallrye/mutiny/Multi;",
            "Lio/reactivex/rxjava3/core/Single;",
            "Lio/reactivex/rxjava3/core/Flowable;",
            "Lio/reactivex/rxjava3/core/Observable;",
            "Lio/reactivex/rxjava3/core/Completable;",
            "Lio/reactivex/rxjava3/core/Maybe;");

    static final String LIMITATION_METADATA = "A probe records each invocation's duration, thread kind, request id,"
            + " outcome (returned, or the thrown exception's type), and calling frame: never an argument or a return"
            + " value.";

    static final String LIMITATION_BOUNDS = "A probe records at most 20 invocations, for at most 60 seconds, five"
            + " probes at once, and ends with the run (a restart or a live reload); the agent enforces the bounds where"
            + " the method runs, even if it could not remove its instrumentation afterwards.";

    static final String LIMITATION_ASYNC = "For a method returning a reactive or asynchronous result (Mono, Flux,"
            + " CompletionStage, Uni, ...), a probe times the result's assembly and sees only what the method throws"
            + " itself, not the work that runs later; its request id is known only on the thread that captured it.";

    static final String LIMITATION_CALLER = "The calling frame is the first frame of the application's packages above"
            + " the method, past proxies and interceptors, else the frame right above it.";

    static final String LIMITATION_REQUESTS = "The HTTP Exchanges panel is disabled: request ids are left out.";

    /** Estimated bytes of one kept probe and of one recorded invocation, for the evidence contract's accounting. */
    static final long PROBE_BYTES = 512;

    static final long HIT_BYTES = 320;

    private final AgentBridgeAccess access;
    private final Supplier<AgentClaim> claims;
    private final Supplier<String> unavailable;
    private final AgentEvidence evidence;
    private final AgentEvidence.Store store = new Store();
    private final Object lock = new Object();
    private Run run;
    private boolean closed;

    /**
     * @param access the bridge
     * @param claims this application's current claim, or a supplier of {@code null}
     * @param unavailable why Code Paths is unavailable, or {@code null}: probes are part of it
     * @param evidence the agent evidence contract the probes are read, cleared, and counted under
     */
    public MethodProbeService(
            AgentBridgeAccess access,
            Supplier<AgentClaim> claims,
            Supplier<String> unavailable,
            AgentEvidence evidence) {
        this.access = access == null ? AgentBridgeAccess.absent() : access;
        this.claims = claims == null ? () -> null : claims;
        this.unavailable = unavailable == null ? () -> null : unavailable;
        this.evidence = evidence == null ? AgentEvidence.open() : evidence;
        this.evidence.register(store);
    }

    /** Why probes cannot be started in this run, or {@code null}. */
    public String unavailableReason() {
        String reason;
        try {
            reason = unavailable.get();
        } catch (RuntimeException ex) {
            reason = null;
        }
        if (reason != null) {
            return reason;
        }
        if (!access.methodProbesSupported()) {
            return "The attached BootUI agent predates method probes: update it to this BootUI's version.";
        }
        AgentClaim claim = claims.get();
        if (claim == null || !claim.armed()) {
            return "This run holds no claim on the BootUI agent.";
        }
        return null;
    }

    /**
     * This run's probes, newest first, after draining what is waiting: none while the Code Paths panel is hidden, and
     * without request ids while HTTP Exchanges is.
     */
    public CodePathsProbesReport report() {
        AgentEvidence.Read read = evidence.read(store);
        // The agent's own reason first, as Code Paths' reads give it: a panel unavailable without the agent is hidden
        // too.
        String reason = unavailableReason();
        if (reason == null && !read.shown()) {
            reason = read.hiddenReason();
        }
        Run current = reason == null ? current() : null;
        List<CodePathsProbeDto> probes = current == null ? List.of() : current.probes(read);
        List<String> limitations =
                new ArrayList<>(List.of(LIMITATION_METADATA, LIMITATION_BOUNDS, LIMITATION_ASYNC, LIMITATION_CALLER));
        if (read.shown() && !read.requests()) {
            limitations.add(LIMITATION_REQUESTS);
        }
        return new CodePathsProbesReport(
                reason == null, reason, MAX_ACTIVE, MAX_INVOCATIONS, WINDOW_SECONDS, probes, limitations);
    }

    /**
     * Starts a probe on {@code method}, {@code binary.Class#name} with its descriptor for an overloaded method. The probe
     * starts {@code starting}: the agent installs it off this thread, so the call never blocks.
     *
     * @throws IllegalArgumentException when the method is not a probeable method of the application
     * @throws IllegalStateException when probes are unavailable, or the agent refused it (five already run, the method is
     *     already probed, or it failed at once)
     */
    public CodePathsProbeDto start(String method) {
        String key = method == null ? "" : method.trim();
        if (key.isEmpty()) {
            throw new IllegalArgumentException("Name the method to probe, as com.example.PriceService#quote.");
        }
        String reason = unavailableReason();
        if (reason != null) {
            throw new IllegalStateException(reason);
        }
        AgentClaim claim = claims.get();
        int hash = key.indexOf('#');
        String className = hash > 0 ? key.substring(0, hash) : key;
        if (hash > 0 && !inPackages(className, claim.claimedPackages())) {
            throw new IllegalArgumentException(
                    className + " is not in the application's packages " + claim.claimedPackages() + ".");
        }
        Run current = current();
        Map<String, Object> answer = claim.startMethodProbe(Map.of("method", key));
        String status = String.valueOf(answer.get("status"));
        String why = text(answer, "reason");
        Map<String, Object> probe = map(answer.get("probe"));
        if (probe != null && current != null) {
            current.merge(probe);
        }
        return switch (status) {
            case "started" -> current == null ? dto(probe, List.of()) : current.probe(id(probe), evidence.read(store));
            case "invalid" -> throw new IllegalArgumentException(sentence(why));
            // The agent refused it at once: the failed probe stays listed, and the start answers why.
            default -> throw new IllegalStateException(sentence(why == null ? status : why));
        };
    }

    /**
     * Stops the probe {@code id}: it ends at once and the agent removes it.
     *
     * @throws IllegalArgumentException when {@code id} is not a probe id
     * @throws java.util.NoSuchElementException when this run has no such probe
     */
    public CodePathsProbeDto stop(String id) {
        long value = parseId(id);
        Run current = current();
        if (current == null || !current.has(value)) {
            throw new java.util.NoSuchElementException("This run has no method probe " + id + ".");
        }
        AgentClaim claim = claims.get();
        if (claim != null) {
            Map<String, Object> answer = claim.stopMethodProbe(value);
            Map<String, Object> probe = map(answer.get("probe"));
            if (probe != null) {
                current.merge(probe);
            }
        }
        return current.probe(value, evidence.read(store));
    }

    /**
     * The probe {@code id} with its invocations, after draining what is waiting.
     *
     * @throws IllegalArgumentException when {@code id} is not a probe id
     * @throws java.util.NoSuchElementException when this run has no such probe
     */
    public CodePathsProbeDto probe(String id) {
        long value = parseId(id);
        AgentEvidence.Read read = evidence.read(store);
        if (!read.shown()) {
            String reason = unavailableReason();
            throw new IllegalStateException(reason != null ? reason : read.hiddenReason());
        }
        Run current = current();
        if (current == null || !current.has(value)) {
            throw new java.util.NoSuchElementException("This run has no method probe " + id + ".");
        }
        current.refresh();
        return current.probe(value, read);
    }

    /** Stops routing this run's probe records. Idempotent. */
    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            if (run != null) {
                run.close();
                run = null;
            }
        }
    }

    /** This run's probes, routed from its claim's drainer: a new claim generation starts afresh. */
    private Run current() {
        synchronized (lock) {
            if (closed) {
                return null;
            }
            AgentClaim claim = claims.get();
            if (claim == null || claim.generation() == null || !access.methodProbesSupported()) {
                return run;
            }
            if (run != null && run.generation == claim.generation()) {
                return run;
            }
            if (run != null) {
                run.close();
            }
            run = new Run(claim);
            run.start();
            return run;
        }
    }

    private static long parseId(String id) {
        try {
            return Long.parseLong(id == null ? "" : id.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("A method probe id is a number, as get_method_probe returns it: " + id);
        }
    }

    private static boolean inPackages(String className, List<String> packages) {
        for (String name : packages) {
            if (className.startsWith(name + ".")) {
                return true;
            }
        }
        return false;
    }

    private static String sentence(String text) {
        if (text == null || text.isBlank()) {
            return "The BootUI agent refused the probe.";
        }
        String trimmed = text.trim();
        String capitalized = Character.toUpperCase(trimmed.charAt(0)) + trimmed.substring(1);
        return capitalized.endsWith(".") ? capitalized : capitalized + ".";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private static long id(Map<String, Object> probe) {
        Long value = value(probe, "id");
        return value == null ? -1L : value;
    }

    /** Whether a method of {@code descriptor} returns a reactive or asynchronous result, or is a suspending function. */
    static boolean async(String descriptor) {
        if (descriptor == null) {
            return false;
        }
        int close = descriptor.indexOf(')');
        if (close < 0) {
            return false;
        }
        return ASYNC_TYPES.contains(descriptor.substring(close + 1))
                || descriptor.substring(0, close).endsWith("Lkotlin/coroutines/Continuation;");
    }

    /** The hits as {@code read} shows them: without request ids while HTTP Exchanges is hidden. */
    static List<CodePathsProbeHitDto> shown(List<CodePathsProbeHitDto> hits, AgentEvidence.Read read) {
        if (read.requests()) {
            return List.copyOf(hits);
        }
        List<CodePathsProbeHitDto> list = new ArrayList<>(hits.size());
        for (CodePathsProbeHitDto hit : hits) {
            list.add(new CodePathsProbeHitDto(
                    hit.time(),
                    hit.durationMicros(),
                    hit.threadKind(),
                    null,
                    hit.outcome(),
                    hit.exceptionType(),
                    hit.caller()));
        }
        return list;
    }

    static CodePathsProbeDto dto(Map<String, Object> probe, List<CodePathsProbeHitDto> hits) {
        String descriptor = text(probe, "descriptor");
        String className = text(probe, "className");
        String methodName = text(probe, "methodName");
        String method = descriptor == null ? text(probe, "method") : className + "#" + methodName + descriptor;
        String state = text(probe, "state");
        long windowMillis = number(probe, "windowMillis");
        return new CodePathsProbeDto(
                String.valueOf(id(probe)),
                method,
                className,
                methodName,
                descriptor,
                state,
                text(probe, "endReason"),
                text(probe, "failure"),
                text(probe, "removal"),
                "active".equals(state) && !flag(probe, "advised"),
                async(descriptor),
                (int) number(probe, "maxInvocations"),
                Math.max(1L, (windowMillis + 999L) / 1000L),
                instant(probe, "requestedAt"),
                instant(probe, "endsAt"),
                instant(probe, "endedAt"),
                (int) number(probe, "invocations"),
                (int) number(probe, "recorded"),
                (int) number(probe, "dropped"),
                hits);
    }

    /** Whether {@code probe} ended before {@code epochMillis}. */
    private static boolean endedBefore(Map<String, Object> probe, long epochMillis) {
        String state = text(probe, "state");
        Long ended = value(probe, "endedAt");
        Long requested = value(probe, "requestedAt");
        long at = ended != null ? ended : requested == null ? Long.MAX_VALUE : requested;
        return ("ended".equals(state) || "failed".equals(state)) && at < epochMillis;
    }

    /** The probes as a store of the agent evidence contract: counted, and cleared with the journal. */
    private final class Store implements AgentEvidence.Store {

        @Override
        public String id() {
            return "method-probes";
        }

        @Override
        public String panel() {
            return BootUiPanels.CODE_PATHS;
        }

        @Override
        public String title() {
            return "Code Paths";
        }

        @Override
        public String unavailableReason() {
            return MethodProbeService.this.unavailableReason();
        }

        @Override
        public AgentEvidence.Usage usage() {
            Run current;
            synchronized (lock) {
                current = run;
            }
            long probes = current == null ? 0 : current.probeCount();
            long hits = current == null ? 0 : current.hitCount();
            Map<String, Long> counts = new LinkedHashMap<>();
            counts.put("probes", probes);
            counts.put("probeHits", hits);
            return new AgentEvidence.Usage(
                    probes * PROBE_BYTES + hits * HIT_BYTES, AgentEvidence.Part.METHOD_PROBES.ceilingBytes(), counts);
        }

        @Override
        public String clear(long epochMillis) {
            Run current;
            synchronized (lock) {
                current = run;
            }
            if (current == null) {
                return null;
            }
            int dropped = current.clear(epochMillis);
            return dropped == 0 ? null : dropped + (dropped == 1 ? " method probe" : " method probes");
        }
    }

    private static long number(Map<String, Object> probe, String key) {
        Long value = value(probe, key);
        return value == null ? 0L : value;
    }

    private static String text(Map<String, ?> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Long value(Map<String, ?> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value instanceof Number number ? number.longValue() : null;
    }

    private static boolean flag(Map<String, ?> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value instanceof Boolean bool && bool;
    }

    private static String instant(Map<String, Object> probe, String key) {
        Long value = value(probe, key);
        return value == null ? null : Instant.ofEpochMilli(value).toString();
    }

    /** One run's probes and their hits, routed from the claim's drainer. */
    private final class Run implements Consumer<long[]> {

        final long generation;
        final AgentClaim claim;
        private final LinkedHashMap<Long, Map<String, Object>> probes = new LinkedHashMap<>();
        private final Map<Long, List<CodePathsProbeHitDto>> hits = new LinkedHashMap<>();
        private String[] interns = new String[1];
        private long otherGenerations;
        /** The latest clear, in epoch milliseconds: what ended or was recorded before it is dropped. */
        private long clearedAt = Long.MIN_VALUE;

        private AgentRecordDrainer drainer;

        Run(AgentClaim claim) {
            this.claim = claim;
            this.generation = claim.generation();
        }

        void start() {
            AgentRecordDrainer routed = claim.drainer();
            if (routed != null) {
                drainer = routed;
                routed.route(AgentRecordDrainer.SENSOR_METHOD_PROBES, this);
            }
        }

        void close() {
            if (drainer != null) {
                drainer.unroute(AgentRecordDrainer.SENSOR_METHOD_PROBES, this);
            }
        }

        /** Drains waiting records, then merges the bridge's probes of this generation. */
        void refresh() {
            if (drainer != null) {
                drainer.drainNow();
            }
            for (Map<String, Object> probe : access.methodProbes()) {
                if (Long.valueOf(generation).equals(value(probe, "generation"))) {
                    merge(probe);
                }
            }
        }

        synchronized void merge(Map<String, Object> probe) {
            long id = id(probe);
            if (id < 0) {
                return;
            }
            if (endedBefore(probe, clearedAt)) {
                // Ended before a Clear recording, as one still ending or starting at the clear: dropped, never kept.
                probes.remove(id);
                hits.remove(id);
                return;
            }
            probes.remove(id);
            probes.put(id, probe);
            while (probes.size() > MAX_PROBES) {
                Long oldest = probes.keySet().iterator().next();
                probes.remove(oldest);
                hits.remove(oldest);
            }
        }

        synchronized boolean has(long id) {
            if (!probes.containsKey(id)) {
                refreshUnlocked();
            }
            return probes.containsKey(id);
        }

        private void refreshUnlocked() {
            for (Map<String, Object> probe : access.methodProbes()) {
                if (Long.valueOf(generation).equals(value(probe, "generation"))) {
                    merge(probe);
                }
            }
        }

        List<CodePathsProbeDto> probes(AgentEvidence.Read read) {
            refresh();
            synchronized (this) {
                List<CodePathsProbeDto> list = new ArrayList<>();
                List<Long> ids = new ArrayList<>(probes.keySet());
                ids.sort((a, b) -> Long.compare(b, a));
                for (Long id : ids) {
                    list.add(dto(probes.get(id), shown(hits.getOrDefault(id, List.of()), read)));
                }
                return list;
            }
        }

        synchronized CodePathsProbeDto probe(long id, AgentEvidence.Read read) {
            Map<String, Object> probe = probes.get(id);
            return probe == null ? null : dto(probe, shown(hits.getOrDefault(id, List.of()), read));
        }

        synchronized long probeCount() {
            return probes.size();
        }

        synchronized long hitCount() {
            long count = 0;
            for (List<CodePathsProbeHitDto> list : hits.values()) {
                count += list.size();
            }
            return count;
        }

        /**
         * Drops the probes that ended before {@code epochMillis}, and every invocation recorded before it, including what
         * is still queued: a live probe stays, with the invocations it records from now on.
         */
        synchronized int clear(long epochMillis) {
            clearedAt = Math.max(clearedAt, epochMillis);
            int dropped = 0;
            for (java.util.Iterator<Map.Entry<Long, Map<String, Object>>> it =
                            probes.entrySet().iterator();
                    it.hasNext(); ) {
                Map.Entry<Long, Map<String, Object>> entry = it.next();
                if (endedBefore(entry.getValue(), clearedAt)) {
                    it.remove();
                    hits.remove(entry.getKey());
                    dropped++;
                }
            }
            for (List<CodePathsProbeHitDto> list : hits.values()) {
                list.removeIf(hit -> Instant.parse(hit.time()).toEpochMilli() < epochMillis);
            }
            return dropped;
        }

        @Override
        public synchronized void accept(long[] record) {
            if (record[AgentRecordDrainer.GENERATION] != generation) {
                otherGenerations++;
                return;
            }
            if (record[AgentRecordDrainer.TIME] < clearedAt) {
                // Recorded before a Clear recording, and still queued then.
                return;
            }
            long flags = record[AgentRecordDrainer.PAYLOAD];
            long id = flags >>> 8;
            List<CodePathsProbeHitDto> list = hits.computeIfAbsent(id, key -> new ArrayList<>());
            if (list.size() >= MAX_HITS) {
                return;
            }
            long names = record[AgentRecordDrainer.PAYLOAD + 3];
            long request = record[AgentRecordDrainer.PAYLOAD + 2];
            boolean threw = ((flags >>> 2) & 1L) == 1L;
            list.add(new CodePathsProbeHitDto(
                    Instant.ofEpochMilli(record[AgentRecordDrainer.TIME]).toString(),
                    record[AgentRecordDrainer.PAYLOAD + 1] / 1000.0,
                    (flags & 3L) == 2L ? "virtual" : "platform",
                    request == 0L ? null : String.format("%016x", request),
                    threw ? "threw" : "returned",
                    intern((int) (names & 0xFFFFFFFFL)),
                    intern((int) (names >>> 32))));
        }

        /** The string an intern id names, fetching the ids the table added since the last read. */
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
                String[] again = access.interned(generation, id);
                if (again != null && again.length > 0) {
                    interns[id] = again[0];
                }
            }
            return id < interns.length ? interns[id] : null;
        }
    }
}
