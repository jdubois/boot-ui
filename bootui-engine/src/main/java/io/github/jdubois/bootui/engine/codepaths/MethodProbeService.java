package io.github.jdubois.bootui.engine.codepaths;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.CodePathsProbeDto;
import io.github.jdubois.bootui.core.dto.CodePathsProbeHitDto;
import io.github.jdubois.bootui.core.dto.CodePathsProbesReport;
import io.github.jdubois.bootui.core.dto.CodePathsValueShapeDto;
import io.github.jdubois.bootui.engine.javaagent.AgentBridgeAccess;
import io.github.jdubois.bootui.engine.javaagent.AgentClaim;
import io.github.jdubois.bootui.engine.javaagent.AgentRecordDrainer;
import io.github.jdubois.bootui.engine.journal.AgentEvidence;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.spi.ExposurePolicy;
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
 * this run's last {@value #MAX_PROBES} probes with at most {@value #MAX_HITS} invocations each. The agent records no
 * argument or return value; a probe started with shapes also records their shapes (D44), which only this service's UI
 * reads show, by the live exposure: none under {@code METADATA_ONLY}; types, nullness, sizes, and presence under
 * {@code MASKED}; and a string's length, a {@code char[]} or {@code byte[]} length, and an enum constant's name under
 * {@code FULL} only. Its agent reads ({@link #startForAgents}, {@link #probeForAgents}), behind MCP and the CLI, never
 * show a shape, in any exposure (D24, §5.17). The bounds (invocations, window, probes at once) are the agent bridge's,
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

    static final String LIMITATION_SHAPES = "A probe started with argument and return shapes also records the type of"
            + " its first nine arguments, taken at entry, and of its return value, with a size for JDK collections,"
            + " maps, and arrays, and whether an Optional is present, read without running any application method:"
            + " application collections, proxies, and other objects show their type only, numbers and booleans never"
            + " their value. A string's length, a char[], byte[], Character[], or Byte[] length, and an enum constant's"
            + " name need bootui.expose-values=FULL, or MASKED with bootui.mask-secrets=false. MCP and the CLI never see"
            + " shapes.";

    static final String SHAPES_HIDDEN_METADATA =
            "Argument and return shapes are hidden while bootui.expose-values is METADATA_ONLY.";

    static final String SHAPES_HIDDEN_AGENTS =
            "Argument and return shapes are shown in BootUI's Code Paths panel only, never to MCP or the CLI.";

    static final String SHAPES_UNSUPPORTED =
            "The attached BootUI agent predates argument and return shapes: update it" + " to this BootUI's version.";

    /** Estimated bytes of one probe's shapes table, for the evidence contract's accounting. */
    static final long SHAPES_BYTES = MAX_HITS * 11L * Long.BYTES + 64;

    /** Ring record types and shapes layout: the bridge's {@code MethodProbes} and {@code ProbeShapes}. */
    static final int PROBE_HIT = 1;

    static final int PROBE_SHAPES = 2;
    static final int RETURN_PART = 3;
    static final int MAX_SHAPED_ARGUMENTS = 9;
    static final int SHAPE_ABSENT = 0;
    static final int SHAPE_NULL = 1;
    static final int SHAPE_TYPE = 2;
    static final int SHAPE_STRING = 3;
    static final int SHAPE_COLLECTION = 4;
    static final int SHAPE_MAP = 5;
    static final int SHAPE_ARRAY = 6;
    static final int SHAPE_OPTIONAL = 7;
    static final int SHAPE_ENUM = 8;
    static final int MAX_SUMMARY = (1 << 28) - 1;

    /** A shapes row: the arguments, the return value, and the bit set of parts received. */
    private static final int RETURN_SLOT = MAX_SHAPED_ARGUMENTS;

    private static final int PARTS_SLOT = MAX_SHAPED_ARGUMENTS + 1;

    /** Array types whose length may be a secret's: shown under FULL exposure only. */
    static final Set<String> VALUE_LENGTH_ARRAYS =
            Set.of("char[]", "byte[]", "java.lang.Character[]", "java.lang.Byte[]");

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
    private volatile ExposurePolicy exposure;

    /** How much of a shape a read shows. */
    enum ShapeView {
        /** MCP and the CLI: never a shape. */
        AGENTS,
        /** {@code METADATA_ONLY}, or a failing policy. */
        HIDDEN,
        MASKED,
        FULL
    }

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

    /** The live exposure policy shapes are shown by; without one, {@code MASKED}, the policy's default. */
    public void setExposure(ExposurePolicy exposure) {
        this.exposure = exposure;
    }

    /** How much of a shape the UI's reads show under the live exposure; a failing policy hides them. */
    ShapeView shapeView() {
        ExposurePolicy policy = exposure;
        if (policy == null) {
            return ShapeView.MASKED;
        }
        try {
            ValueExposure value = policy.valueExposure();
            if (value == null || value == ValueExposure.METADATA_ONLY) {
                return ShapeView.HIDDEN;
            }
            return value == ValueExposure.FULL || !policy.maskSecrets() ? ShapeView.FULL : ShapeView.MASKED;
        } catch (RuntimeException ex) {
            return ShapeView.HIDDEN;
        }
    }

    /** Why a probe started now cannot record shapes, or {@code null}. */
    public String shapesUnavailableReason() {
        String reason = unavailableReason();
        if (reason != null) {
            return reason;
        }
        if (!access.methodProbeShapesSupported()) {
            return SHAPES_UNSUPPORTED;
        }
        return shapeView() == ShapeView.MASKED || shapeView() == ShapeView.FULL ? null : SHAPES_HIDDEN_METADATA;
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
        ShapeView view = shapeView();
        List<CodePathsProbeDto> probes = current == null ? List.of() : current.probes(read, view);
        List<String> limitations = new ArrayList<>(List.of(
                LIMITATION_METADATA, LIMITATION_SHAPES, LIMITATION_BOUNDS, LIMITATION_ASYNC, LIMITATION_CALLER));
        if (read.shown() && !read.requests()) {
            limitations.add(LIMITATION_REQUESTS);
        }
        String shapesReason = reason != null ? reason : shapesUnavailableReason();
        return new CodePathsProbesReport(
                reason == null,
                reason,
                MAX_ACTIVE,
                MAX_INVOCATIONS,
                WINDOW_SECONDS,
                probes,
                limitations,
                shapesReason == null,
                shapesReason);
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
        return start(method, false, shapeView());
    }

    /**
     * Starts a probe on {@code method}, recording its argument and return shapes when {@code recordShapes}, as
     * {@link #start(String)} does.
     *
     * @throws IllegalStateException also when {@code recordShapes} and shapes are unavailable: the exposure is
     *     {@code METADATA_ONLY}, or the agent predates them
     */
    public CodePathsProbeDto start(String method, boolean recordShapes) {
        return start(method, recordShapes, shapeView());
    }

    /**
     * Starts a metadata-only probe for MCP's {@code start_method_probe} and the CLI, as {@link #start(String)} does; its
     * answer never shows a shape.
     */
    public CodePathsProbeDto startForAgents(String method) {
        return start(method, false, ShapeView.AGENTS);
    }

    private CodePathsProbeDto start(String method, boolean recordShapes, ShapeView view) {
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
        if (recordShapes) {
            String shapes = shapesUnavailableReason();
            if (shapes != null) {
                throw new IllegalStateException(shapes);
            }
        }
        Run current = current();
        Map<String, Object> answer = claim.startMethodProbe(
                recordShapes ? Map.of("method", key, "shapes", Boolean.TRUE) : Map.of("method", key));
        String status = String.valueOf(answer.get("status"));
        String why = text(answer, "reason");
        Map<String, Object> probe = map(answer.get("probe"));
        if (probe != null && current != null) {
            current.merge(probe);
        }
        return switch (status) {
            case "started" ->
                current == null
                        ? dto(probe, List.of(), hiddenReason(probe, view))
                        : current.probe(id(probe), evidence.read(store), view);
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
        return current.probe(value, evidence.read(store), shapeView());
    }

    /**
     * The probe {@code id} with its invocations, after draining what is waiting.
     *
     * @throws IllegalArgumentException when {@code id} is not a probe id
     * @throws java.util.NoSuchElementException when this run has no such probe
     */
    public CodePathsProbeDto probe(String id) {
        return probe(id, shapeView());
    }

    /**
     * The probe {@code id} for MCP's {@code get_method_probe} and the CLI, as {@link #probe(String)} returns it but never
     * with a shape.
     */
    public CodePathsProbeDto probeForAgents(String id) {
        return probe(id, ShapeView.AGENTS);
    }

    private CodePathsProbeDto probe(String id, ShapeView view) {
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
        return current.probe(value, read, view);
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

    /** Why {@code view} shows none of {@code probe}'s shapes, or {@code null}. */
    static String hiddenReason(Map<String, Object> probe, ShapeView view) {
        if (!flag(probe, "shapes")) {
            return null;
        }
        return switch (view) {
            case AGENTS -> SHAPES_HIDDEN_AGENTS;
            case HIDDEN -> SHAPES_HIDDEN_METADATA;
            default -> null;
        };
    }

    static CodePathsProbeDto dto(Map<String, Object> probe, List<CodePathsProbeHitDto> hits, String shapesHidden) {
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
                hits,
                flag(probe, "shapes"),
                shapesHidden,
                (int) number(probe, "shapesDropped"));
    }

    /** The descriptor's parameter types, as descriptor tokens ({@code I}, {@code Ljava/lang/String;}, {@code [J}). */
    static List<String> parameterTypes(String descriptor) {
        List<String> types = new ArrayList<>();
        int close = descriptor == null ? -1 : descriptor.indexOf(')');
        if (close < 0 || descriptor.charAt(0) != '(') {
            return types;
        }
        int i = 1;
        while (i < close) {
            int start = i;
            while (i < close && descriptor.charAt(i) == '[') {
                i++;
            }
            if (i < close && descriptor.charAt(i) == 'L') {
                int end = descriptor.indexOf(';', i);
                i = end < 0 ? close : end + 1;
            } else {
                i++;
            }
            types.add(descriptor.substring(start, Math.min(i, close)));
        }
        return types;
    }

    /** The descriptor's return type token, or {@code null}. */
    static String returnType(String descriptor) {
        int close = descriptor == null ? -1 : descriptor.indexOf(')');
        return close < 0 ? null : descriptor.substring(close + 1);
    }

    /**
     * A type as Java source names it: a descriptor token ({@code I}, {@code Ljava/lang/String;}, {@code [[J}) or a
     * {@code Class.getName()} ({@code java.lang.String}, {@code [I}, {@code [Ljava.lang.String;}).
     */
    static String typeName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        int dimensions = 0;
        while (dimensions < name.length() && name.charAt(dimensions) == '[') {
            dimensions++;
        }
        String component = name.substring(dimensions);
        String base;
        if (dimensions > 0 || component.length() == 1) {
            base = switch (component) {
                case "Z" -> "boolean";
                case "B" -> "byte";
                case "C" -> "char";
                case "S" -> "short";
                case "I" -> "int";
                case "J" -> "long";
                case "F" -> "float";
                case "D" -> "double";
                case "V" -> "void";
                default ->
                    component.startsWith("L") && component.endsWith(";")
                            ? component.substring(1, component.length() - 1).replace('/', '.')
                            : component.replace('/', '.');
            };
        } else {
            base = component.startsWith("L") && component.endsWith(";")
                    ? component.substring(1, component.length() - 1).replace('/', '.')
                    : component.replace('/', '.');
        }
        return base + "[]".repeat(dimensions);
    }

    /** Whether a descriptor token names a primitive type. */
    static boolean primitive(String token) {
        return token != null && token.length() == 1 && "ZBCSIJFD".indexOf(token.charAt(0)) >= 0;
    }

    /**
     * One shape as {@code view} shows it: {@code packed} is the bridge's {@code ProbeShapes} layout, {@code token} the
     * declared type's descriptor token, and {@code names} resolves intern ids.
     */
    static CodePathsValueShapeDto shape(
            long packed, String token, ShapeView view, java.util.function.IntFunction<String> names) {
        String declared = typeName(token);
        int kind = (int) (packed & 0xFL);
        if (kind == SHAPE_ABSENT) {
            return new CodePathsValueShapeDto("unknown", declared, null, null, null, null, false);
        }
        if (kind == SHAPE_NULL) {
            return new CodePathsValueShapeDto("null", declared, null, null, null, null, false);
        }
        if (primitive(token)) {
            return new CodePathsValueShapeDto("primitive", declared, declared, null, null, null, false);
        }
        int summary = (int) ((packed >>> 4) & MAX_SUMMARY);
        String type = typeName(names.apply((int) (packed >>> 32)));
        boolean full = view == ShapeView.FULL;
        return switch (kind) {
            case SHAPE_STRING ->
                new CodePathsValueShapeDto("string", declared, type, full ? summary : null, null, null, !full);
            case SHAPE_COLLECTION ->
                new CodePathsValueShapeDto("collection", declared, type, summary, null, null, false);
            case SHAPE_MAP -> new CodePathsValueShapeDto("map", declared, type, summary, null, null, false);
            case SHAPE_ARRAY -> {
                boolean withheld = !full && (type == null || VALUE_LENGTH_ARRAYS.contains(type));
                yield new CodePathsValueShapeDto(
                        "array", declared, type, withheld ? null : summary, null, null, withheld);
            }
            case SHAPE_OPTIONAL ->
                new CodePathsValueShapeDto("optional", declared, type, null, summary == 1, null, false);
            case SHAPE_ENUM ->
                new CodePathsValueShapeDto(
                        "enum", declared, type, null, null, full ? names.apply(summary) : null, !full);
            case SHAPE_TYPE -> new CodePathsValueShapeDto("type", declared, type, null, null, null, false);
            default -> new CodePathsValueShapeDto("unknown", declared, null, null, null, null, false);
        };
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
            long shapes = current == null ? 0 : current.shapesCount();
            Map<String, Long> counts = new LinkedHashMap<>();
            counts.put("probes", probes);
            counts.put("probeHits", hits);
            return new AgentEvidence.Usage(
                    probes * PROBE_BYTES + hits * HIT_BYTES + shapes * SHAPES_BYTES,
                    AgentEvidence.Part.METHOD_PROBES.ceilingBytes(),
                    counts);
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

    /** One recorded invocation: its index in its probe, for its shapes, and its metadata. */
    private record Hit(int index, CodePathsProbeHitDto metadata) {}

    /** One run's probes and their hits, routed from the claim's drainer. */
    private final class Run implements Consumer<long[]> {

        final long generation;
        final AgentClaim claim;
        private final LinkedHashMap<Long, Map<String, Object>> probes = new LinkedHashMap<>();
        private final Map<Long, List<Hit>> hits = new LinkedHashMap<>();
        /** Per probe, per invocation index: argument shapes, the return shape, and the parts received. */
        private final Map<Long, long[][]> shapes = new LinkedHashMap<>();

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
                forget(id);
                return;
            }
            probes.remove(id);
            probes.put(id, probe);
            while (probes.size() > MAX_PROBES) {
                forget(probes.keySet().iterator().next());
            }
        }

        private void forget(Long id) {
            probes.remove(id);
            hits.remove(id);
            shapes.remove(id);
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

        List<CodePathsProbeDto> probes(AgentEvidence.Read read, ShapeView view) {
            refresh();
            synchronized (this) {
                List<CodePathsProbeDto> list = new ArrayList<>();
                List<Long> ids = new ArrayList<>(probes.keySet());
                ids.sort((a, b) -> Long.compare(b, a));
                for (Long id : ids) {
                    list.add(probe(id, read, view));
                }
                return list;
            }
        }

        synchronized CodePathsProbeDto probe(long id, AgentEvidence.Read read, ShapeView view) {
            Map<String, Object> probe = probes.get(id);
            if (probe == null) {
                return null;
            }
            String hidden = hiddenReason(probe, view);
            boolean shown = flag(probe, "shapes") && hidden == null;
            String descriptor = text(probe, "descriptor");
            long[][] table = shapes.get(id);
            List<CodePathsProbeHitDto> list = new ArrayList<>();
            for (Hit hit : hits.getOrDefault(id, List.of())) {
                list.add(shown(hit, shown ? descriptor : null, shown ? table : null, read, view));
            }
            return dto(probe, list, hidden);
        }

        /**
         * One hit as {@code read} shows it: without its request id while HTTP Exchanges is hidden, with its shapes
         * joined by index when {@code descriptor} is given.
         */
        private CodePathsProbeHitDto shown(
                Hit hit, String descriptor, long[][] table, AgentEvidence.Read read, ShapeView view) {
            CodePathsProbeHitDto metadata = hit.metadata();
            String request = read.requests() ? metadata.requestId() : null;
            List<CodePathsValueShapeDto> arguments = null;
            int notRecorded = 0;
            CodePathsValueShapeDto returned = null;
            boolean incomplete = false;
            if (descriptor != null) {
                long[] row = table == null || hit.index() >= table.length ? null : table[hit.index()];
                long parts = row == null ? 0L : row[PARTS_SLOT];
                List<String> types = parameterTypes(descriptor);
                int recorded = Math.min(types.size(), MAX_SHAPED_ARGUMENTS);
                notRecorded = types.size() - recorded;
                arguments = new ArrayList<>(recorded);
                for (int i = 0; i < recorded; i++) {
                    boolean received = (parts & (1L << (i / 3))) != 0L;
                    // A part lost, or a shape the agent could not take inside one that arrived.
                    incomplete |= !received || (row[i] & 0xFL) == SHAPE_ABSENT;
                    arguments.add(shape(received ? row[i] : 0L, types.get(i), view, this::intern));
                }
                String result = returnType(descriptor);
                if ("returned".equals(metadata.outcome()) && result != null && !"V".equals(result)) {
                    boolean received = (parts & (1L << RETURN_PART)) != 0L;
                    incomplete |= !received || (row[RETURN_SLOT] & 0xFL) == SHAPE_ABSENT;
                    returned = shape(received ? row[RETURN_SLOT] : 0L, result, view, this::intern);
                }
            }
            return new CodePathsProbeHitDto(
                    metadata.time(),
                    metadata.durationMicros(),
                    metadata.threadKind(),
                    request,
                    metadata.outcome(),
                    metadata.exceptionType(),
                    metadata.caller(),
                    arguments,
                    notRecorded,
                    returned,
                    incomplete);
        }

        synchronized long probeCount() {
            return probes.size();
        }

        synchronized long shapesCount() {
            return shapes.size();
        }

        synchronized long hitCount() {
            long count = 0;
            for (List<Hit> list : hits.values()) {
                count += list.size();
            }
            return count;
        }

        /**
         * Drops the probes that ended before {@code epochMillis}, and every invocation recorded before it, including what
         * is still queued, with its shapes and those of an invocation running across it: a live probe stays, with the
         * invocations it records from now on.
         */
        synchronized int clear(long epochMillis) {
            clearedAt = Math.max(clearedAt, epochMillis);
            int dropped = 0;
            for (Long id : new ArrayList<>(probes.keySet())) {
                if (endedBefore(probes.get(id), clearedAt)) {
                    forget(id);
                    dropped++;
                }
            }
            for (List<Hit> list : hits.values()) {
                list.removeIf(hit -> Instant.parse(hit.metadata().time()).toEpochMilli() < epochMillis);
            }
            for (long[][] table : shapes.values()) {
                java.util.Arrays.fill(table, null);
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
            long type = record[AgentRecordDrainer.TYPE];
            if (type == PROBE_SHAPES) {
                acceptShapes(record);
                return;
            }
            if (type != PROBE_HIT) {
                return;
            }
            long flags = record[AgentRecordDrainer.PAYLOAD];
            long id = flags >>> 8;
            List<Hit> list = hits.computeIfAbsent(id, key -> new ArrayList<>());
            if (list.size() >= MAX_HITS) {
                return;
            }
            long names = record[AgentRecordDrainer.PAYLOAD + 3];
            long request = record[AgentRecordDrainer.PAYLOAD + 2];
            boolean threw = ((flags >>> 2) & 1L) == 1L;
            list.add(new Hit(
                    (int) ((flags >>> 3) & 31L),
                    new CodePathsProbeHitDto(
                            Instant.ofEpochMilli(record[AgentRecordDrainer.TIME])
                                    .toString(),
                            record[AgentRecordDrainer.PAYLOAD + 1] / 1000.0,
                            (flags & 3L) == 2L ? "virtual" : "platform",
                            request == 0L ? null : String.format("%016x", request),
                            threw ? "threw" : "returned",
                            intern((int) (names & 0xFFFFFFFFL)),
                            intern((int) (names >>> 32)))));
        }

        /** Up to three shapes of one invocation, kept by its index: at most {@value #MAX_HITS} rows a probe. */
        private void acceptShapes(long[] record) {
            long header = record[AgentRecordDrainer.PAYLOAD];
            long id = header >>> 8;
            int index = (int) ((header >>> 3) & 31L);
            int part = (int) (header & 7L);
            if (index >= MAX_HITS || part > RETURN_PART || !probes.containsKey(id)) {
                // A probe this run no longer keeps (evicted, or ended before a Clear recording): nothing to join.
                return;
            }
            long[][] table = shapes.computeIfAbsent(id, key -> new long[MAX_HITS][]);
            long[] row = table[index];
            if (row == null) {
                row = new long[PARTS_SLOT + 1];
                table[index] = row;
            }
            if (part == RETURN_PART) {
                row[RETURN_SLOT] = record[AgentRecordDrainer.PAYLOAD + 1];
            } else {
                for (int i = 0; i < 3; i++) {
                    row[part * 3 + i] = record[AgentRecordDrainer.PAYLOAD + 1 + i];
                }
            }
            row[PARTS_SLOT] |= 1L << part;
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
