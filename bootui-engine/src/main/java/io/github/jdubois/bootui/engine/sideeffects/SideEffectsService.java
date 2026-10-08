package io.github.jdubois.bootui.engine.sideeffects;

import io.github.jdubois.bootui.core.dto.PageMetadata;
import io.github.jdubois.bootui.core.dto.SideEffectsAgentReport;
import io.github.jdubois.bootui.core.dto.SideEffectsReport;
import io.github.jdubois.bootui.core.dto.SideEffectsRowDto;
import io.github.jdubois.bootui.core.dto.SideEffectsSensorDto;
import io.github.jdubois.bootui.core.dto.SideEffectsSensorReport;
import io.github.jdubois.bootui.engine.codepaths.CodePathStamps;
import io.github.jdubois.bootui.engine.correlation.RequestPhases;
import io.github.jdubois.bootui.engine.javaagent.AgentBridgeAccess;
import io.github.jdubois.bootui.engine.javaagent.AgentClaim;
import io.github.jdubois.bootui.engine.javaagent.AgentRecordDrainer;
import io.github.jdubois.bootui.engine.javaagent.AgentRequestValues;
import io.github.jdubois.bootui.engine.javaagent.AgentSensorSettings;
import io.github.jdubois.bootui.engine.javaagent.JavaAgentService;
import io.github.jdubois.bootui.engine.javaagent.SideEffectsSample;
import io.github.jdubois.bootui.engine.journal.AgentEvidence;
import io.github.jdubois.bootui.engine.journal.JournalSourcePanels;
import io.github.jdubois.bootui.engine.journal.RunSideEffects;
import io.github.jdubois.bootui.engine.model.EdgeType;
import io.github.jdubois.bootui.engine.model.HostOpen;
import io.github.jdubois.bootui.engine.model.NodeType;
import io.github.jdubois.bootui.engine.model.SideEffectAccess;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.support.StackFramePrefixes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Side Effects for one application ({@code docs/PLAN-v2.md} §5.16, M5-5a, M5-5c), shared by every adapter: for this run's
 * claim on the BootUI agent, routes the side-effect sensors' records from the claim's {@link AgentRecordDrainer} into a
 * {@link SideEffectsStore}, resolving their strings, their call site (the first application frame, else the first frame
 * outside the JDK), the bean method they happened inside (from their Code Paths stamp), and their request's route (from
 * the runtime journal's HTTP exchanges, as Code Inventory does). Records of another claim generation are dropped and
 * counted; a new claim generation starts a new store. Records BootUI's own code made are left out. Its reads serve the
 * Side Effects panel, {@code get_side_effects}, and {@code bootui side-effects}; they start no scan, network call, or
 * mutation.
 */
public final class SideEffectsService implements AutoCloseable {

    private static final Logger log = Logger.getLogger(SideEffectsService.class.getName());

    /** Rows one sensor read returns when it asks for none. */
    public static final int DEFAULT_LIMIT = 100;

    /** The most rows one sensor read returns. */
    public static final int MAX_LIMIT = 500;

    /** Approximate bytes of one resolved string and one method label, for the memory accounting. */
    static final long STRING_BYTES = 64L;

    static final long METHOD_BYTES = 96L;

    /** The most strings a run resolves: the agent's intern table per claim generation. */
    static final long MAX_STRINGS = 8_192L;

    /** How often, at most, the drain thread asks the journal for the routes of waiting requests. */
    static final long RESOLVE_MILLIS = 2_000L;

    /** How often, at most, reads ask it: each walks the journal's retained events. */
    static final long READ_RESOLVE_MILLIS = 250L;

    static final String LIMITATION_SCOPE = "Side Effects records only what the BootUI agent's side-effect sensors hook:"
            + " this version records the processes the application starts, through ProcessBuilder.start, which"
            + " Runtime.exec and ProcessBuilder.startPipeline also reach; its network: connects, datagram sends, and"
            + " the host names the JVM resolves; the blocking calls started on an event loop; and, opt-in, the files it"
            + " opens, deletes, moves, and copies, through FileInputStream, FileOutputStream, RandomAccessFile, the Files"
            + " methods, and FileChannel.open, and the environment variables and system properties it reads by name"
            + " through System.getenv and System.getProperty; and, opt-in, the threads it starts and the executors it"
            + " creates; and, opt-in, the thread locals a request or a job left set on its pooled thread, and request"
            + " input reaching SQL text, a command, a file path, or an outbound URL unchanged, deserialization without a"
            + " filter, weak algorithms, and trust managers and hostname verifiers (security sinks)."
            + " Resources left open are not available in this version.";

    static final String LIMITATION_THREADS =
            "Thread activity: Thread.start, VirtualThread.start, the ThreadPoolExecutor,"
                    + " ForkJoinPool, and thread-per-task executors' constructors, and their shutdown, shutdownNow, and close."
                    + " A pool's own workers are its executor's row, never threads of their own. A thread or an executor is the"
                    + " application's when the first frame outside the JDK that started or created it is in the application's"
                    + " packages, else a library's (a framework's pool, a client, an @Async executor), or the JDK's when the JDK"
                    + " created it, or in a static initializer; only the application's, started or created for a request, are"
                    + " reported left running: still alive, or not shut down, 250 ms after the request's response completed,"
                    + " checked once. An executor nothing references is reclaimed by the collector, or by the JDK's cleaner"
                    + " for newSingleThreadExecutor, without a shutdown. A start no request owns is counted under its starting"
                    + " thread's family with the call site of its first sighting. Never a thread-local, a task, or anything a"
                    + " thread holds.";

    static final String LIMITATION_THREAD_LOCALS = "Thread locals: found by scanning a pooled platform thread's"
            + " thread-local maps when a request's or a job's scope on it closes, against what they held when it opened:"
            + " a thread local with a value then that had none, or was absent, at the open is left set; a null value"
            + " counts as cleared. Scopes: a Spring MVC request on its worker, a request's task on a pool's own worker, a"
            + " Quarkus blocking resource method on its worker, a Quarkus managed executor's task, and a scheduled run."
            + " Event loops, virtual threads (not pooled), asynchronous dispatches, and threads the application starts"
            + " itself are not scanned. A thread local is reported once per pool thread until a scope clears it: a later"
            + " request that sets it again is not. No call site: it was set during the request. A row names the static"
            + " field holding it, found in an already-initialized class of the application, or of a known framework,"
            + " without initializing a class; else a hint, else its class (an instance field's, a library's, or the"
            + " JDK's). A thread local with an initial value (ThreadLocal.withInitial or an initialValue override) is a"
            + " per-thread cache filled by get(), shown only when its holder is in the application's packages. Never a"
            + " value or a toString(). A Spring Security context row may be an empty context: getContext() sets one"
            + " when it reads none.";

    static final String LIMITATION_THREAD_LOCALS_INVENTORY = "Thread locals: this JDK did not let the agent ask"
            + " whether a class is initialized, so holders are resolved only in application classes Code Inventory saw"
            + " run, and framework holders are not resolved.";

    /** The agent's time to name thread locals' holders, per {@value #HOLDER_WINDOW_MILLIS} ms. */
    static final long HOLDER_BUDGET_NANOS = 20_000_000L;

    static final long HOLDER_WINDOW_MILLIS = 1_000L;

    /**
     * How long a thread local waits for its holder, at most, and how many wait: longer than the agent's periodic index
     * rebuild (10 s), which answers a miss its throttled forced rebuild left for later.
     */
    static final long HOLDER_WAIT_MILLIS = 15_000L;

    static final int MAX_HOLDER_WAITING = 1_024;

    /** The answer of a thread local whose holder the agent had no time to name: "not resolved", never remembered. */
    private static final String[] GAVE_UP = {null, "false", "false", null};

    /** The holders and answers a run remembers, one per slot of the bridge's thread-local registry. */
    static final int MAX_HOLDERS = 1_024;

    static final String LIMITATION_NETWORK = "A network row shows a host and port, never a byte sent or received, nor a"
            + " URL's path or query. A non-blocking connect's time is known once it finishes. A name lookup is"
            + " recorded only when the JVM's address cache misses it (networkaddress.cache.ttl, 30 s by default), so its"
            + " time is the name service's. Asynchronous socket channels, a connected datagram channel's writes, and"
            + " connects made by native code are not seen. Datagrams a thread sends are counted in its table and may"
            + " lag until its next send.";

    static final String LIMITATION_CAPTURE =
            "Not captured by any panel: no visible panel shows the connection's work. A"
                    + " JDBC, messaging, or mail client's connection counts as captured while SQL Trace, its broker's panel, or"
                    + " Email is available and enabled, as BootUI then records that client's work, which a pool's"
                    + " connection carries later; a second DataSource BootUI does not wrap is not told apart. Any other connection is captured when a REST client"
                    + " call of the same request or execution, or, for unowned work, at the same time, names its host and port"
                    + " (or a proxy named by http.proxyHost, https.proxyHost, or socksProxyHost; a ProxySelector, an"
                    + " HttpClient.Builder proxy, a Reactor Netty proxy, or HTTPS_PROXY is not detected, so a call"
                    + " through one reads as not captured). Infrastructure clients, such as DNS resolvers, telemetry exporters, and"
                    + " container tooling, are no panel's to show.";

    static final String LIMITATION_BLOCKING = "Blocking rows are Thread.sleep, TimeUnit.sleep, Object.wait, and"
            + " LockSupport.park (a contended lock, a future's get) started on a thread the adapter identified as an event"
            + " loop: Reactor Netty's on Spring WebFlux, Vert.x's on Quarkus, each from the first request it handled,"
            + " and a WebClient's from the first response it delivered when the WebClient was built from Spring Boot's"
            + " WebClient.Builder with REST client tracing on; Reactor's parallel scheduler and worker threads are never"
            + " event loops."
            + " A park shorter than 1 ms is only counted. Thread.sleep and Object.wait are native on JDK 17 and end in"
            + " native methods on later JDKs, so they are seen at their call sites in the application's own classes"
            + " (bootui.agent.packages) only, on every JDK: a library's sleep or wait, a sleep through a method reference,"
            + " and Thread.join are not. A connect, a name lookup, or a DatagramSocket send the network sensor records,"
            + " and a file the files sensor records, is reported when it started on an event loop, never Netty's"
            + " non-blocking connect; Netty's DNS resolver reads /etc/hosts and /etc/resolv.conf on its first name"
            + " resolution, which can show once as a file read on an event loop. A call is reported, never refused.";

    static final String BLOCKING_NOT_APPLICABLE = "This application's server runs no event loop to block: Spring MVC,"
            + " or Spring WebFlux on a servlet container, serves each request on a thread of its own. A WebClient built"
            + " from Spring Boot's WebClient.Builder with REST client tracing on has its Reactor Netty event loop watched"
            + " once it delivered a response.";

    static final String LIMITATION_LOOPS_REFUSED = "The blocking sensor's event-loop table was full: some event loops"
            + " were not registered, and blocking calls on them are not reported.";

    static final String LIMITATION_CALL_SITES_FAILED = "The blocking sensor's Thread.sleep and Object.wait call-site"
            + " hooks failed their self-test and were removed: only parks are reported this run.";

    static final String LIMITATION_NO_EVENT_LOOP = "No event loop has handled a request yet: blocking calls are watched"
            + " on each event loop from the first request it handles.";

    static final String LIMITATION_VALUES = "A process row shows the command's file name only, never its arguments or"
            + " its environment, which can hold secrets; its exit status and lifetime come from Process.onExit, whose JDK"
            + " stage runs on the common ForkJoin pool before BootUI's own thread records the exit. On Windows, the JDK waits"
            + " for each watched live process on a reaper thread of its own, so up to 1,024 such threads. A file row shows a"
            + " path pattern, never the file's contents: the working directory as ./, the temporary directory as $TMPDIR,"
            + " the home as ~, ids and digits collapsed ({n}, {hex}, {uuid}, {id}, {token}), and a segment the secret"
            + " detector recognizes (a JWT, a PEM key, an AWS key, a credential URL) masked; a short secret matching none"
            + " of these rules is kept. An environment row shows the name read, never its value or a default.";

    static final String LIMITATION_FILES = "Files: class files, JAR, WAR, and JMOD files, paths in archive file"
            + " systems, Java's home, the class path's directories, and any other file a class loader reads are counted,"
            + " not recorded per route; the JDK's own files and logging appenders are grouped apart from the"
            + " application's files, and a library's files are shown but left out of the runtime model."
            + " A call site's frames are read once per run and file pattern, so two call sites of one pattern share the"
            + " first one's."
            + " File.delete, File.renameTo, File.createNewFile, AsynchronousFileChannel, memory-mapped access after the"
            + " open, and native code are not seen. A move or a copy records its source and its destination.";

    static final String LIMITATION_ENVIRONMENT = "Environment: a name is recorded the first time a thread reads it for"
            + " a request or an execution, so a row counts the requests and threads that read it, not every call; on a"
            + " thread no request scope owns, a name read again within a second counts once; a name the JDK read first for"
            + " the same request on the same thread is not recorded again. A configuration framework resolving its own"
            + " properties (SmallRye Config, Spring's Environment) is not a direct read and is not recorded, nor are reads"
            + " the JDK makes for"
            + " itself, as an XML or SSL factory looking up its property, are not recorded. A framework reading the whole"
            + " map (System.getenv() or System.getProperties()) and then a name from it shows as (all variables), or not"
            + " at all for properties.";

    static final String LIMITATION_ATTRIBUTION =
            "A row is attributed to its request's route as HTTP Exchanges names it, else to the execution no request owns"
                    + " that did it (a scheduled run, a consumed message, a WebSocket message) as the runtime journal"
                    + " names it, else to startup, or to its thread's family. Work handed to another thread without"
                    + " BootUI's context shows under its thread's family.";

    /** Said once the recording was cleared (M5-11). */
    static final String RECORDING_CLEARED = "The recording was cleared: rows recorded before then were dropped, and so"
            + " is a record the agent still held whose first occurrence came before the clear.";

    static final String LIMITATION_ROUTES_HIDDEN =
            "The HTTP Exchanges panel is disabled: Side Effects attributes rows to"
                    + " request routes through it, so route rows are merged under one hidden route, without request ids.";

    static final String LIMITATION_SECURITY_SINKS = "Security sinks: request input is matched only while"
            + " bootui.agent.security-sinks.request-values is on, against the current request's query and path parameter"
            + " values of 4 to 256 characters, at most 32 of them, never form values, headers, or bodies, and only"
            + " verbatim: a value encoded, trimmed, or changed in case is not seen. The values are compared, never stored,"
            + " and forgotten when the response completes. SQL text is checked where SQL Trace captures a statement,"
            + " never with R2DBC; a command where the processes sensor sees it start; a file path only with the files"
            + " sensor; an outbound URL where the REST client panel records the call. A task the request hands to a"
            + " managed executor still matches, until the response completes; a task the agent propagates never does. A"
            + " row's target is the redacted text, the value replaced by the parameter's name, and SQL literals masked;"
            + " when a text was scanned only in part or held more matches than could be redacted, no text is kept. A"
            + " value outside an SQL literal, or made of digits only, is shown once a second request confirms the text"
            + " varies with it.";

    static final String LIMITATION_SECURITY_CHECKS = "Security sinks' JDK checks: a deserialization is one outermost"
            + " ObjectInputStream.readObject whose stream has no ObjectInputFilter, named by the classes"
            + " ObjectInputStream.resolveClass resolved, at most 16, so a subclass that resolves classes itself names none;"
            + " readUnshared is left out. A weak algorithm is MessageDigest MD5, MD2, or SHA-1, or a Cipher with DES,"
            + " DESede, RC4, or a block cipher in ECB mode, which a bare AES, Blowfish, or RC2 defaults to; KeyGenerator,"
            + " Signature, Mac, SecureRandom, and PBE algorithms are left out. What the JDK asks for itself, as"
            + " UUID.nameUUIDFromBytes, SecureRandom, TLS, or jar verification, is counted, never shown; a library's"
            + " request is shown apart. A trust manager is shown when its class is the application's; a default hostname"
            + " verifier or SSL socket factory when the application installed it. A check group whose JDK hook failed its"
            + " self-test is off alone.";

    /** A deserialization row whose classes no {@code resolveClass} named, as a subclass resolving them itself. */
    static final String CLASSES_NOT_NAMED = "(classes not named)";

    /** A security-sinks row whose redacted text the holder could not keep. */
    static final String TEXT_NOT_KEPT = "(text not kept)";

    /** A target the agent's table could not keep. */
    static final String UNKNOWN_TARGET = "(unknown)";

    /** The sensors a run comparison compares, in catalog order (M5-7b). */
    public static final List<String> COMPARED_SENSORS = List.of(
            SideEffectsCatalog.NETWORK_ID,
            SideEffectsCatalog.FILES_ID,
            SideEffectsCatalog.PROCESSES_ID,
            SideEffectsCatalog.ENVIRONMENT_ID);

    /**
     * The runtime-switchable sensors whose hooks ride on the side-effect transformer that {@link #COMPARED_SENSORS} share
     * (M5-14): switching one reinstalls that transformer, pausing every sensor on it while its self-test runs again.
     */
    static final java.util.Set<String> SHARED_TRANSFORMER_SWITCHES = java.util.Set.of(
            SideEffectsCatalog.FILES_ID, SideEffectsCatalog.ENVIRONMENT_ID, AgentSensorSettings.SECURITY_SINKS);

    /** Why a compared sensor's run is not whole after a switch reinstalled the transformer it shares. */
    static final String PAUSED_FOR_A_SWITCH =
            "its hooks paused while the agent reinstalled them for a runtime switch of another sensor";

    /** A files row's target past the agent's quota of distinct path patterns. */
    static final String TOO_MANY_PATHS = "(path not kept: too many distinct paths or strings)";

    /** An environment row's target past the agent's quota of distinct names. */
    static final String TOO_MANY_NAMES = "(name not kept: too many distinct names or strings)";

    private final AgentBridgeAccess access;
    private final Supplier<AgentClaim> claims;
    private final Supplier<String> unavailable;
    private final Function<String, JavaAgentService.SideEffectsCoverage> coverage;
    private final LongSupplier clock;
    private final SideEffectsNormalizer normalizer;
    private volatile Function<Set<String>, Map<String, String>> requestRoutes = ids -> Map.of();
    private volatile Function<Set<String>, Map<String, String>> executionLabels = ids -> Map.of();
    private volatile java.util.function.BooleanSupplier serverEventLoops = () -> true;
    private volatile NetworkCapture networkCapture = NetworkCapture.NONE;
    private volatile Set<String> exporterEndpoints = Set.of();
    private final AgentEvidence evidence;
    private volatile ThreadLocalHolders.Resolver threadLocalHolders = ThreadLocalHolders.AGENT;
    private final AgentEvidence.Store store = new Store();
    /** Code Paths' panel, whose evidence a row's bean method is: read for its visibility only, never registered. */
    private final AgentEvidence.Store codePathsPanel = new CodePathsPanel();
    // What the current run holds, published on every change for the evidence store, which never waits on the lock.
    private volatile long usageBytes;
    private volatile long usageMaxBytes;
    private volatile long usageRows;
    private volatile long usageWaiting;
    private volatile long usageIndexBytes;

    private final Object lock = new Object();
    private Run run;
    /** The latest Clear recording's time, which a run started after it takes as its watermark. */
    private long lastClearedAt = Long.MIN_VALUE;

    private boolean closed;

    /** The current run's claim while it asks for the thread-activity sensor, read on every request's end. */
    private volatile AgentClaim threadActivityClaim;

    private RequestPhases phases;
    private final Consumer<String> requestEnd = this::requestEnded;

    /** This application run's side effects, frozen by {@link #endRun()}; {@code null} before. */
    private RunSideEffects ended;

    /** Whether a run of a new claim generation replaced an earlier one during this application run. */
    private boolean replaced;

    /** Reads a sensor's state from the bridge now, and when the claim was armed (M5-7b); replaced by tests. */
    private volatile Supplier<Map<String, SideEffectsSample>> sampler = this::bridgeSamples;

    private volatile java.util.function.BiFunction<AgentClaim, String, SideEffectsSample> armedSampler =
            AgentClaim::armedSideEffects;

    /**
     * @param access the bridge
     * @param claims this application's current claim, or a supplier of {@code null}
     * @param unavailable why Side Effects records nothing for this application, {@code null} when it does, such as
     *     {@link JavaAgentService#sideEffectsUnavailableReason()}
     * @param coverage each sensor's coverage, such as {@link JavaAgentService#sideEffectsCoverage}
     * @param evidence the agent evidence contract this service's rows are read, cleared, bounded, and counted under
     *     (M5-11)
     */
    public SideEffectsService(
            AgentBridgeAccess access,
            Supplier<AgentClaim> claims,
            Supplier<String> unavailable,
            Function<String, JavaAgentService.SideEffectsCoverage> coverage,
            AgentEvidence evidence) {
        this(
                access,
                claims,
                unavailable,
                coverage,
                evidence,
                System::currentTimeMillis,
                System.getProperty("user.home"));
    }

    SideEffectsService(
            AgentBridgeAccess access,
            Supplier<AgentClaim> claims,
            Supplier<String> unavailable,
            Function<String, JavaAgentService.SideEffectsCoverage> coverage,
            AgentEvidence evidence,
            LongSupplier clock,
            String home) {
        this.access = access == null ? AgentBridgeAccess.absent() : access;
        this.claims = claims == null ? () -> null : claims;
        this.unavailable = unavailable == null ? () -> null : unavailable;
        this.coverage = coverage;
        this.clock = clock;
        this.normalizer = new SideEffectsNormalizer(home);
        this.evidence = java.util.Objects.requireNonNull(evidence, "evidence");
        evidence.register(store);
    }

    /** Installs how a request's route is named by request id, such as {@code JournalRequestRoutes.of}. */
    public void setRequestRoutes(Function<Set<String>, Map<String, String>> requestRoutes) {
        this.requestRoutes = requestRoutes == null ? ids -> Map.of() : requestRoutes;
    }

    /**
     * Installs how an execution no request owns is named by its id, such as {@link JournalExecutions#of}: a scheduled
     * run, a consumed message, or a WebSocket message.
     */
    public void setExecutionLabels(Function<Set<String>, Map<String, String>> executionLabels) {
        this.executionLabels = executionLabels == null ? ids -> Map.of() : executionLabels;
    }

    /**
     * Installs what tells whether a panel shows a network connection's work, such as {@link JournalNetworkCapture#of}
     * (M5-5b).
     */
    public void setNetworkCapture(NetworkCapture networkCapture) {
        this.networkCapture = networkCapture == null ? NetworkCapture.NONE : networkCapture;
        synchronized (lock) {
            if (run != null) {
                run.store.setCapture(this.networkCapture);
            }
        }
    }

    /**
     * Reads, once, the telemetry exporter endpoints the application configures ({@code management.otlp.*},
     * {@code otel.exporter.*}, {@code quarkus.otel.exporter.*}), whose connections are infrastructure, such as {@code
     * environment::getProperty} (M5-5b).
     */
    public void setExporterEndpoints(Function<String, String> properties) {
        this.exporterEndpoints = NetworkClients.endpoints(properties);
    }

    /**
     * Tells whether this application's server handles requests on event loops (Spring WebFlux on Reactor Netty,
     * Quarkus), the default, or not (Spring MVC, WebFlux on a servlet container, a non-web application), where the
     * {@code blocking} sensor is not applicable until a WebClient's event loop is registered.
     */
    public void setServerEventLoops(boolean serverEventLoops) {
        this.serverEventLoops = () -> serverEventLoops;
    }

    /**
     * As {@link #setServerEventLoops(boolean)}, asked on each read, as when the server starts after this service: a
     * supplier that throws counts as event loops.
     */
    public void setServerEventLoops(java.util.function.BooleanSupplier serverEventLoops) {
        this.serverEventLoops = serverEventLoops == null ? () -> true : serverEventLoops;
    }

    /** Names waiting keys: request ids through the routes, execution keys through the execution labels. */
    private Map<String, String> names(Set<String> keys) {
        Set<String> requests = new java.util.HashSet<>();
        Set<String> executions = new java.util.HashSet<>();
        for (String key : keys) {
            if (key.startsWith(SideEffectsStore.EXECUTION_KEY)) {
                executions.add(key.substring(SideEffectsStore.EXECUTION_KEY.length()));
            } else {
                requests.add(key);
            }
        }
        Map<String, String> named = new LinkedHashMap<>();
        if (!requests.isEmpty()) {
            Map<String, String> routes = requestRoutes.apply(requests);
            if (routes != null) {
                named.putAll(routes);
            }
        }
        if (!executions.isEmpty()) {
            Map<String, String> labels = executionLabels.apply(executions);
            if (labels != null) {
                for (Map.Entry<String, String> label : labels.entrySet()) {
                    if (label.getKey() != null && label.getValue() != null) {
                        // A consumed message's destination may carry ids: normalized as targets are.
                        String value = label.getValue().startsWith("consumed ")
                                ? normalizer.target(label.getValue())
                                : label.getValue();
                        named.put(SideEffectsStore.EXECUTION_KEY + label.getKey(), value);
                    }
                }
            }
        }
        return named;
    }

    /**
     * The read of this service's panels now ({@code docs/PLAN-v2.md} §8, M5-11): the Side Effects panel, and HTTP
     * Exchanges, which owns request routes; while it is hidden, route rows are merged under one hidden route without
     * request ids.
     */
    public AgentEvidence.Read read() {
        return evidence.read(store);
    }

    /** Why nothing of Side Effects is shown under {@code read}: the agent does not record, or the panel is hidden. */
    private String shownReason(AgentEvidence.Read read) {
        String reason = unavailableReason();
        return reason != null ? reason : read.hiddenReason();
    }

    /**
     * Routes this run's records, once the claim is armed: the adapter calls it when its context refreshed or Quarkus
     * started, which this run's store takes as the end of startup, and a read calls it again. Idempotent per claim
     * generation. Never throws.
     */
    public void start() {
        try {
            synchronized (lock) {
                if (closed) {
                    return;
                }
                AgentClaim claim = claims.get();
                if (claim == null
                        || !claim.armed()
                        || claim.generation() == null
                        || claim.activeSensors().stream().noneMatch(AgentSensorSettings.SIDE_EFFECT_SENSORS::contains)
                        || !access.sideEffectsSupported()) {
                    return;
                }
                if (run != null && run.generation == claim.generation()) {
                    return;
                }
                if (ended != null) {
                    return;
                }
                if (run != null) {
                    run.close();
                    replaced = true;
                }
                // The thread that finished starting may hold the end of startup's records in its table (M5-7b).
                access.sideEffectsFlushThread();
                // Whatever the claim asked for: thread-activity can be switched on at run time, and the bridge
                // ignores a request's end while the sensor is off, with one volatile read.
                threadActivityClaim = access.threadActivitySupported() ? claim : null;
                run = new Run(claim, clock.getAsLong());
                // The bridge's thread-activity counters last for the JVM: the panel reports this run's increase.
                run.threadBaseline = sensorCounters(AgentSensorSettings.THREAD_ACTIVITY);
                publish(run);
                run.start();
            }
        } catch (RuntimeException ex) {
            log.log(Level.WARNING, "BootUI could not start Side Effects", ex);
        }
    }

    /** Stops routing this run's records. Idempotent; the service starts nothing afterwards. */
    @Override
    public void close() {
        // Normally frozen already, when the adapter ended the run before disarming its claim.
        endRun();
        synchronized (lock) {
            closed = true;
            threadActivityClaim = null;
            if (phases != null) {
                phases.removeEndListener(requestEnd);
                phases = null;
            }
            if (run != null) {
                run.close();
                run = null;
            }
        }
    }

    /**
     * Ends this application run's side effects ({@code docs/PLAN-v2.md} M5-7b): publishes the calling thread's buffered
     * records, drains the agent's ring, names what waits for its route, and freezes the run's side-effect keys and
     * whether each sensor recorded the whole run, for its summary ({@link #runSideEffects()}). The adapter calls it when
     * the application stops, before it disarms its claim, so the sensors' state is still this run's. Idempotent; the
     * rows stay readable. Never throws.
     */
    public void endRun() {
        try {
            Run current;
            synchronized (lock) {
                if (ended != null || closed) {
                    return;
                }
                current = run;
            }
            if (current == null) {
                return;
            }
            access.sideEffectsFlushThread();
            current.drainNow();
            synchronized (lock) {
                if (ended != null) {
                    return;
                }
                current.store.resolve(SideEffectsService.this::names, clock.getAsLong(), 0L);
                ended = snapshot(current, read());
                publish(current);
            }
        } catch (RuntimeException ex) {
            log.log(Level.WARNING, "BootUI could not end the run's side effects", ex);
        }
    }

    /**
     * Hears each request's end from {@code requestPhases}, where the adapters mark it once the response is complete,
     * so the thread-activity sensor checks what the request left running (M5-5e). Idempotent; {@link #close()} stops
     * listening.
     */
    public void listenToRequestEnds(RequestPhases requestPhases) {
        synchronized (lock) {
            if (closed || requestPhases == null || phases == requestPhases) {
                return;
            }
            if (phases != null) {
                phases.removeEndListener(requestEnd);
            }
            phases = requestPhases;
            requestPhases.addEndListener(requestEnd);
        }
    }

    /**
     * A request ended: when this run claimed the thread-activity sensor, the bridge notes it without a lock. Called on
     * the thread that ended the request, an event loop on Spring WebFlux and Quarkus: one volatile read otherwise.
     */
    void requestEnded(String requestId) {
        AgentClaim claim = threadActivityClaim;
        if (claim == null || requestId == null || requestId.length() != 16) {
            return;
        }
        try {
            claim.threadActivityRequestEnded(Long.parseUnsignedLong(requestId, 16));
        } catch (RuntimeException ex) {
            // Not a BootUI request id: nothing to check.
        }
    }

    /**
     * This application run's side effects for a run summary or a comparison ({@code docs/PLAN-v2.md} M5-7b): what
     * {@link #endRun()} froze, else this run's so far; {@code null} when no Side Effects run started, as without the
     * agent or a side-effect sensor. Names and masked patterns only. Never throws.
     */
    public RunSideEffects runSideEffects() {
        try {
            synchronized (lock) {
                if (ended != null) {
                    return ended;
                }
            }
            AgentEvidence.Read read = read();
            Run current = shownReason(read) == null ? settledRun() : current();
            if (current == null) {
                return null;
            }
            synchronized (lock) {
                return ended != null ? ended : snapshot(current, read);
            }
        } catch (RuntimeException ex) {
            log.log(Level.FINE, "BootUI could not read the run's side effects", ex);
            return null;
        }
    }

    /**
     * Marks sensor {@code id} as switched during this run, so the run is not compared for it: for a runtime sensor
     * switch (M5-14), which changes what a sensor records without a new claim. A switch of a sensor on the shared
     * side-effect transformer ({@link #SHARED_TRANSFORMER_SWITCHES}) also leaves every compared sensor out, since the
     * agent pauses them while it reinstalls that transformer.
     */
    public void sensorSwitched(String id) {
        synchronized (lock) {
            if (run != null && id != null && ended == null) {
                run.switched.add(id);
                if (SHARED_TRANSFORMER_SWITCHES.contains(id)) {
                    run.reinstalled = true;
                }
            }
        }
    }

    /** The run's side effects now, under the lock: its keys, and each compared sensor's verdicts. */
    private RunSideEffects snapshot(Run current, AgentEvidence.Read read) {
        String hidden = shownReason(read);
        if (hidden != null) {
            return RunSideEffects.unavailable(
                    "Side Effects was not shown when the run's side effects were read: " + hidden);
        }
        boolean routesHidden = !read.requests();
        SideEffectsStore.Keys keys = current.store.keys();
        Map<String, List<SideEffectsStore.KeyCount>> bySensor = new LinkedHashMap<>();
        Map<String, Map<String, SideEffectsStore.KeyCount>> merged = new LinkedHashMap<>();
        for (SideEffectsStore.KeyCount key : keys.keys()) {
            if (!COMPARED_SENSORS.contains(key.sensor())) {
                continue;
            }
            String owner = SideEffectOrigins.maskPath(
                    routesHidden && SideEffectsRowDto.ROUTE.equals(key.scope())
                            ? SideEffectsStore.ROUTE_HIDDEN
                            : key.owner());
            String target = SideEffectOrigins.maskPath(key.target());
            SideEffectsStore.KeyCount masked = new SideEffectsStore.KeyCount(
                    key.sensor(), key.kind(), target, key.scope(), owner, key.client(), key.count());
            String identity = key.kind() + '\u0000' + target + '\u0000' + key.scope() + '\u0000' + owner;
            Map<String, SideEffectsStore.KeyCount> sensorKeys =
                    merged.computeIfAbsent(key.sensor(), id -> new LinkedHashMap<>());
            SideEffectsStore.KeyCount existing = sensorKeys.get(identity);
            sensorKeys.put(
                    identity,
                    existing == null
                            ? masked
                            : new SideEffectsStore.KeyCount(
                                    key.sensor(),
                                    key.kind(),
                                    target,
                                    key.scope(),
                                    owner,
                                    existing.client() != null ? existing.client() : key.client(),
                                    existing.count() + key.count()));
        }
        merged.forEach((sensor, values) -> bySensor.put(sensor, new ArrayList<>(values.values())));
        Map<String, SideEffectsSample> end = samples();
        List<RunSideEffects.Sensor> sensors = new ArrayList<>();
        List<RunSideEffects.Key> kept = new ArrayList<>();
        for (String id : COMPARED_SENSORS) {
            List<SideEffectsStore.KeyCount> sensorKeys = new ArrayList<>(bySensor.getOrDefault(id, List.of()));
            sensorKeys.sort(Comparator.comparingLong(SideEffectsStore.KeyCount::count)
                    .reversed()
                    .thenComparing(SideEffectsStore.KeyCount::scope)
                    .thenComparing(SideEffectsStore.KeyCount::owner)
                    .thenComparing(SideEffectsStore.KeyCount::target)
                    .thenComparing(SideEffectsStore.KeyCount::kind));
            long omitted = keys.omitted().getOrDefault(id, 0L);
            for (int i = 0; i < sensorKeys.size(); i++) {
                SideEffectsStore.KeyCount key = sensorKeys.get(i);
                if (i < RunSideEffects.MAX_KEYS_PER_SENSOR) {
                    kept.add(new RunSideEffects.Key(
                            key.sensor(),
                            key.kind(),
                            key.target(),
                            key.scope(),
                            key.owner(),
                            key.client(),
                            key.count()));
                } else {
                    omitted++;
                }
            }
            String reason = reason(current, id, end.getOrDefault(id, SideEffectsSample.NONE));
            String startupReason = reason != null ? reason : startupReason(current, id);
            sensors.add(new RunSideEffects.Sensor(id, reason, startupReason, omitted));
        }
        return new RunSideEffects(null, routesHidden, sensors, kept);
    }

    /**
     * Why sensor {@code id} did not record this run whole once the application started, or {@code null}: it recorded
     * from then to {@code end}, lost nothing, and its recording was neither cleared nor switched.
     */
    private String reason(Run current, String id, SideEffectsSample end) {
        // The sensors the claim uses, a runtime switch the claim carried over from the previous run included (M5-14).
        if (!current.claim.uses(id)) {
            return Boolean.FALSE.equals(current.claim.sensorOverrides().get(id))
                    ? "it was switched off at run time"
                    : "it was not claimed: bootui.agent.sensors does not include " + id;
        }
        SideEffectsSample start = current.started.getOrDefault(id, SideEffectsSample.NONE);
        if (!start.recording()) {
            return "it was not recording when the application finished starting";
        }
        if (!end.recording()) {
            return "it was not recording when the run ended";
        }
        if ((start.generation() >= 0 && start.generation() != current.generation)
                || (end.generation() >= 0 && end.generation() != current.generation)) {
            return "the agent recorded for another claim during the run";
        }
        if (current.switched.contains(id)) {
            return "it was switched during the run";
        }
        if (replaced) {
            return "the agent's claim changed during the run";
        }
        if (current.clears > 0) {
            return "the recording was cleared during the run";
        }
        String lost = lost(start, end, id);
        if (lost != null) {
            return lost;
        }
        long unknown = current.unknownTargets.getOrDefault(id, 0L);
        if (unknown > 0) {
            return unknown + (unknown == 1 ? " record's target was" : " records' targets were")
                    + " not kept: the agent reached its bound of distinct targets";
        }
        if (current.malformed > 0) {
            return current.malformed + (current.malformed == 1 ? " record" : " records") + " could not be read";
        }
        long dropped = current.store.dropped(id);
        if (dropped > 0) {
            return "BootUI dropped " + dropped + (dropped == 1 ? " operation" : " operations")
                    + ": its waiting queue or its rows were full";
        }
        if (current.reinstalled) {
            return PAUSED_FOR_A_SWITCH;
        }
        return null;
    }

    /**
     * Why sensor {@code id} did not record this run's startup whole, or {@code null}: it already recorded when the claim
     * was armed, and lost nothing until the application finished starting.
     */
    private String startupReason(Run current, String id) {
        SideEffectsSample armed;
        try {
            armed = armedSampler.apply(current.claim, id);
        } catch (RuntimeException ex) {
            armed = SideEffectsSample.NONE;
        }
        if (!armed.recording()) {
            return "the agent was still installing it when the application started, so the start of the run went"
                    + " unrecorded";
        }
        String lost = lost(armed, current.started.getOrDefault(id, SideEffectsSample.NONE), id);
        if (lost != null) {
            return lost + " while the application started";
        }
        long unknown = current.unknownStartupTargets.getOrDefault(id, 0L);
        if (unknown > 0) {
            return unknown + (unknown == 1 ? " startup record's target was" : " startup records' targets were")
                    + " not kept: the agent reached its bound of distinct targets";
        }
        return null;
    }

    /** The compared sensors' states now, from one read of the bridge; not recording when it cannot be read. */
    private Map<String, SideEffectsSample> samples() {
        try {
            Map<String, SideEffectsSample> samples = sampler.get();
            return samples == null ? Map.of() : samples;
        } catch (RuntimeException ex) {
            return Map.of();
        }
    }

    private Map<String, SideEffectsSample> bridgeSamples() {
        Map<String, Object> status = access.status();
        Map<String, SideEffectsSample> samples = new HashMap<>();
        for (String id : COMPARED_SENSORS) {
            samples.put(id, SideEffectsSample.read(status, id));
        }
        return samples;
    }

    /** Replaces how sensors' states are read, now and at arm time: for tests. */
    void setSamplers(
            Supplier<Map<String, SideEffectsSample>> now,
            java.util.function.BiFunction<AgentClaim, String, SideEffectsSample> armed) {
        this.sampler = now;
        this.armedSampler = armed;
    }

    /** What the agent lost of sensor {@code id} between two samples, or {@code null} when nothing. */
    private static String lost(SideEffectsSample from, SideEffectsSample to, String id) {
        long ring = to.lost() - from.lost();
        if (ring > 0) {
            return "the agent's ring lost " + ring + (ring == 1 ? " record" : " records");
        }
        long dropped = to.dropped() - from.dropped();
        if (dropped > 0) {
            return "the agent dropped " + dropped + (dropped == 1 ? " record" : " records") + ": its ring was full";
        }
        // A missing frame or thread name changes a record's origin, client, or owner, so whether it is a key.
        long interns = (to.internOverflow() - from.internOverflow()) + (to.internRefused() - from.internRefused());
        if (interns > 0) {
            return "the agent's string table refused " + interns + (interns == 1 ? " string" : " strings")
                    + ", so records lost a call site, a thread, or a target";
        }
        if (SideEffectsCatalog.FILES_ID.equals(id) || SideEffectsCatalog.ENVIRONMENT_ID.equals(id)) {
            long sightings = to.sightingsFull() - from.sightingsFull();
            if (sightings > 0) {
                return "the agent's table of call sites was full, so " + sightings
                        + (sightings == 1 ? " record" : " records") + " lost their origin";
            }
        }
        return null;
    }

    /** Why Side Effects records nothing for this application, or {@code null} when it does. */
    public String unavailableReason() {
        try {
            return unavailable.get();
        } catch (RuntimeException ex) {
            return JavaAgentService.SIDE_EFFECTS_REQUIREMENT + ".";
        }
    }

    /** The panel's summary: every sensor with its tab, coverage, and counts. */
    public SideEffectsReport report() {
        return report(read());
    }

    private SideEffectsReport report(AgentEvidence.Read read) {
        String reason = shownReason(read);
        Run current = reason == null ? settledRun() : null;
        List<SideEffectsSensorDto> sensors = new ArrayList<>();
        for (SideEffectsCatalog.Sensor sensor : SideEffectsCatalog.SENSORS) {
            sensors.add(sensorDto(sensor, reason, current));
        }
        return new SideEffectsReport(reason == null, reason, sensors, limitations(current, read));
    }

    /**
     * One sensor's rows, most frequent first, {@code limit} of them from {@code offset}.
     *
     * @throws IllegalArgumentException for a sensor id the catalog does not know, or a negative offset or limit
     */
    public SideEffectsSensorReport sensor(String id, Integer offset, Integer limit) {
        SideEffectsCatalog.Sensor sensor = SideEffectsCatalog.sensor(id == null ? null : id.trim());
        if (sensor == null) {
            List<String> ids = new ArrayList<>();
            SideEffectsCatalog.SENSORS.forEach(known -> ids.add(known.id()));
            throw new IllegalArgumentException(
                    "Unknown Side Effects sensor '" + id + "'; the sensors are " + String.join(", ", ids) + ".");
        }
        if ((offset != null && offset < 0) || (limit != null && limit < 0)) {
            throw new IllegalArgumentException("offset and limit must not be negative.");
        }
        int from = offset == null ? 0 : offset;
        int size = limit == null || limit == 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        AgentEvidence.Read read = read();
        String reason = shownReason(read);
        Run current = reason == null ? settledRun() : null;
        List<SideEffectsRowDto> all = List.of();
        if (current != null && sensor.available()) {
            Function<String, String[]> captures = captures();
            synchronized (lock) {
                all = rowsOf(current, sensor.id(), read.requests(), codePathsShown(), captures);
            }
        }
        int start = Math.min(from, all.size());
        int end = Math.min(all.size(), start + size);
        List<SideEffectsRowDto> page = all.subList(start, end);
        return new SideEffectsSensorReport(
                reason == null,
                reason,
                sensorDto(sensor, reason, current),
                page,
                new PageMetadata(all.size(), all.size(), start, size, page.size(), end < all.size()),
                limitations(current, read));
    }

    /**
     * Side Effects for agents: every sensor's coverage, then the rows of the shipped sensors matching {@code query}, a
     * sensor id, part of a row's attribution, target, or call site, or a request id among its exemplars, most frequent
     * first, at most {@code limit}.
     */
    public SideEffectsAgentReport agentReport(String query, Integer limit) {
        String asked = query == null ? "" : query.trim();
        int max = limit == null || limit <= 0 ? SideEffectsAgentReport.DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        AgentEvidence.Read read = read();
        SideEffectsReport report = report(read);
        boolean bySensor = SideEffectsCatalog.sensor(asked) != null;
        List<SideEffectsSensorDto> sensors = agentSensors(report.sensors(), bySensor ? asked : null);
        if (!report.available()) {
            return new SideEffectsAgentReport(
                    false, report.unavailableReason(), asked, sensors, 0, List.of(), 0, List.of());
        }
        String needle = asked.toLowerCase(Locale.ROOT);
        List<SideEffectsRowDto> matching = new ArrayList<>();
        Run current = settledRun();
        if (current != null) {
            Function<String, String[]> captures = captures();
            boolean codePaths = codePathsShown();
            synchronized (lock) {
                for (SideEffectsCatalog.Sensor sensor : SideEffectsCatalog.SENSORS) {
                    if (!sensor.available() || (bySensor && !sensor.id().equals(asked))) {
                        continue;
                    }
                    for (SideEffectsRowDto row : rowsOf(current, sensor.id(), read.requests(), codePaths, captures)) {
                        if (bySensor || needle.isEmpty() || matches(row, needle)) {
                            matching.add(row);
                        }
                    }
                }
            }
        }
        matching.sort(Comparator.comparingLong((SideEffectsRowDto row) -> row.count() + row.completed())
                .reversed());
        List<SideEffectsRowDto> listed = matching.subList(0, Math.min(max, matching.size()));
        Set<String> shown = new HashSet<>();
        listed.forEach(row -> shown.add(row.sensor()));
        if (bySensor) {
            shown.add(asked);
        }
        List<String> limitations = agentLimitations(report.limitations(), shown);
        if (matching.isEmpty() && !needle.isEmpty()) {
            limitations.add("No row matched \"" + asked + "\": call get_side_effects without a query to list them."
                    + (needle.matches("[0-9a-f]{16}")
                            ? " A request id matches only the rows naming it among their exemplar requests, at most"
                                    + " three each."
                            : ""));
        }
        return new SideEffectsAgentReport(
                true,
                null,
                asked,
                sensors,
                matching.size(),
                List.copyOf(listed),
                matching.size() - listed.size(),
                limitations);
    }

    /** The sensors whose rows each fixed limitation describes; the others apply to every row. */
    private static final Map<String, Set<String>> LIMITATION_SENSORS = Map.of(
            LIMITATION_VALUES,
            Set.of(SideEffectsCatalog.PROCESSES_ID, SideEffectsCatalog.FILES_ID, SideEffectsCatalog.ENVIRONMENT_ID),
            LIMITATION_NETWORK,
            Set.of(SideEffectsCatalog.NETWORK_ID),
            LIMITATION_CAPTURE,
            Set.of(SideEffectsCatalog.NETWORK_ID),
            LIMITATION_FILES,
            Set.of(SideEffectsCatalog.FILES_ID),
            LIMITATION_ENVIRONMENT,
            Set.of(SideEffectsCatalog.ENVIRONMENT_ID),
            LIMITATION_BLOCKING,
            Set.of(AgentSensorSettings.BLOCKING),
            LIMITATION_THREADS,
            Set.of(SideEffectsCatalog.THREAD_ACTIVITY_ID),
            LIMITATION_THREAD_LOCALS,
            Set.of(SideEffectsCatalog.THREAD_LOCALS_ID));

    static final String LIMITATION_AGENT_OMITTED =
            "What the rows of a sensor without listed rows cannot see is left" + " out: query its id for it.";

    /**
     * The limitations for an agent: those of this run and those every row shares, and the fixed description of what a
     * sensor's rows cannot see only for the sensors in {@code shown}, whose rows are listed or which the query named.
     */
    static List<String> agentLimitations(List<String> limitations, Set<String> shown) {
        List<String> kept = new ArrayList<>(limitations.size());
        boolean omitted = false;
        for (String limitation : limitations) {
            Set<String> sensors = LIMITATION_SENSORS.get(limitation);
            if (sensors == null || sensors.stream().anyMatch(shown::contains)) {
                kept.add(limitation);
            } else {
                omitted = true;
            }
        }
        if (omitted) {
            kept.add(LIMITATION_AGENT_OMITTED);
        }
        return kept;
    }

    /**
     * The sensors for an agent, summary first: each one's state, reason, counters, and runtime switch, and its hooks
     * only for {@code detailed}, the sensor a query named, or for none.
     */
    static List<SideEffectsSensorDto> agentSensors(List<SideEffectsSensorDto> sensors, String detailed) {
        List<SideEffectsSensorDto> summarized = new ArrayList<>(sensors.size());
        for (SideEffectsSensorDto sensor : sensors) {
            summarized.add(
                    sensor.id().equals(detailed)
                            ? sensor
                            : new SideEffectsSensorDto(
                                    sensor.id(),
                                    sensor.group(),
                                    sensor.label(),
                                    sensor.state(),
                                    sensor.reason(),
                                    sensor.rows(),
                                    sensor.occurrences(),
                                    sensor.dropped(),
                                    List.of(),
                                    sensor.toggle()));
        }
        return List.copyOf(summarized);
    }

    /** {@code sensor}'s rows of {@code current}, under the lock: the store's, then, for files, its bucket rows. */
    private List<SideEffectsRowDto> rowsOf(
            Run current,
            String sensor,
            boolean routesVisible,
            boolean codePathsVisible,
            Function<String, String[]> captures) {
        List<SideEffectsRowDto> rows = current.store.rows(sensor, routesVisible, codePathsVisible, captures);
        if (!SideEffectsCatalog.FILES_ID.equals(sensor)) {
            return rows;
        }
        List<SideEffectsRowDto> all = new ArrayList<>(rows);
        all.addAll(bucketRows(current));
        return all;
    }

    /** The bucket labels, by the bridge's counter name. */
    static final Map<String, String> BUCKET_LABELS = bucketLabels();

    private static Map<String, String> bucketLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("classFiles", "(class files)");
        labels.put("archives", "(JAR, WAR, and JMOD files)");
        labels.put("archiveFileSystems", "(paths in archive file systems)");
        labels.put("classPathDirectories", "(files in class path directories)");
        labels.put("javaHome", "(files in Java's home)");
        labels.put("classLoading", "(other files class loaders read)");
        return labels;
    }

    /**
     * The files the agent counted in buckets since the claim or the last Clear recording, one row per bucket, grouped
     * apart: never per route, thread, or call site.
     */
    private List<SideEffectsRowDto> bucketRows(Run current) {
        Map<String, Long> counted = coverage(SideEffectsCatalog.FILES_ID, null).buckets();
        List<SideEffectsRowDto> rows = new ArrayList<>();
        for (Map.Entry<String, String> bucket : BUCKET_LABELS.entrySet()) {
            long now = counted.getOrDefault(bucket.getKey(), 0L);
            long base = current.bucketBaseline.getOrDefault(bucket.getKey(), 0L);
            long count = now >= base ? now - base : now;
            if (count <= 0) {
                continue;
            }
            boolean javaHome = "javaHome".equals(bucket.getKey());
            rows.add(new SideEffectsRowDto(
                    SideEffectsRowDto.UNATTRIBUTED,
                    BUCKETS_ATTRIBUTION,
                    SideEffectsCatalog.FILES_ID,
                    "open",
                    bucket.getValue(),
                    null,
                    null,
                    javaHome ? SideEffectOrigins.JDK : SideEffectOrigins.CLASS_PATH,
                    javaHome ? SideEffectOrigins.JAVA_HOME : null,
                    count,
                    0,
                    0,
                    0,
                    null,
                    0,
                    0,
                    0,
                    0,
                    List.of(),
                    null,
                    null,
                    null,
                    0,
                    0));
        }
        return rows;
    }

    /** Who a bucket row's files were opened by: every thread, counted, never attributed. */
    static final String BUCKETS_ATTRIBUTION = "all threads (counted)";

    /**
     * The runtime model's edges from this run's files and environment rows ({@code docs/PLAN-v2.md} §5.4, §5.16):
     * {@link EdgeType#OPENS} from a route, GraphQL operation, or scheduled job to a {@link NodeType#FILE_PATTERN}, and
     * {@link EdgeType#READS} to an {@link NodeType#ENVIRONMENT_VARIABLE}, for the application's own rows only, never a
     * library's, class loading, the JDK's, or logging; empty while Side Effects is hidden. Route rows only while
     * HTTP Exchanges is visible too. Never starts a drain.
     */
    public List<SideEffectAccess> modelAccesses() {
        try {
            AgentEvidence.Read read = read();
            if (!read.shown() || shownReason(read) != null) {
                return List.of();
            }
            Run current;
            synchronized (lock) {
                current = run;
            }
            if (current == null) {
                return List.of();
            }
            List<SideEffectAccess> accesses = new ArrayList<>();
            synchronized (lock) {
                current.resolve(true);
                for (String sensor : List.of(SideEffectsCatalog.FILES_ID, SideEffectsCatalog.ENVIRONMENT_ID)) {
                    for (SideEffectsRowDto row : current.store.rows(sensor, read.requests(), false)) {
                        SideEffectAccess access = access(row);
                        if (access != null) {
                            accesses.add(access);
                        }
                    }
                }
            }
            return accesses;
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    /** A cheap fingerprint of what {@link #modelAccesses()} reads: changes with every observation and clear. */
    public long modelFingerprint() {
        AgentEvidence.Read read = read();
        if (!read.shown() || shownReason(read) != null) {
            return read.key();
        }
        synchronized (lock) {
            Run current = run;
            if (current == null) {
                return read.key();
            }
            // The store's version, not its observations: a request's waits for its route before it becomes a row.
            current.resolve(true);
            return (current.generation * 1_000_003L + current.store.version()) * 31 + current.clears * 7 + read.key();
        }
    }

    private static SideEffectAccess access(SideEffectsRowDto row) {
        if (!SideEffectOrigins.APPLICATION.equals(row.origin())) {
            // A library's own files, as a pool's or a server's work directory, are infrastructure, not the route's.
            return null;
        }
        NodeType from;
        String fromKey;
        if (SideEffectsRowDto.ROUTE.equals(row.scope())
                && row.attribution() != null
                && !SideEffectsStore.ROUTE_HIDDEN.equals(row.attribution())
                && !SideEffectsStore.UNKNOWN_ROUTE.equals(row.attribution())) {
            from = row.attribution().contains(" (") ? NodeType.GRAPHQL_OPERATION : NodeType.ROUTE;
            fromKey = row.attribution();
        } else if (SideEffectsRowDto.EXECUTION.equals(row.scope())
                && row.attribution() != null
                && row.attribution().startsWith("scheduled ")) {
            from = NodeType.SCHEDULED_JOB;
            fromKey = row.attribution().substring("scheduled ".length());
        } else {
            return null;
        }
        if (SideEffectsCatalog.FILES_ID.equals(row.sensor())) {
            return new SideEffectAccess(
                    from,
                    fromKey,
                    EdgeType.OPENS,
                    NodeType.FILE_PATTERN,
                    row.target(),
                    row.count(),
                    row.firstSeen(),
                    row.lastSeen());
        }
        String prefix = SideEffectsCatalog.SYSTEM_PROPERTY.equals(row.kind()) ? "property:" : "env:";
        return new SideEffectAccess(
                from,
                fromKey,
                EdgeType.READS,
                NodeType.ENVIRONMENT_VARIABLE,
                prefix + row.target(),
                row.count(),
                row.firstSeen(),
                row.lastSeen());
    }

    private static boolean matches(SideEffectsRowDto row, String needle) {
        return contains(row.attribution(), needle)
                || contains(row.target(), needle)
                || contains(row.parameter(), needle)
                || contains(row.kind(), needle)
                || contains(row.callSite(), needle)
                || contains(row.insideMethod(), needle)
                || contains(row.sensor(), needle)
                || contains(row.client(), needle)
                || row.exemplarRequestIds().stream().anyMatch(id -> id.equalsIgnoreCase(needle))
                || (SideEffectsRowDto.NOT_CAPTURED.equals(row.capture())
                        && ("not captured by any panel".contains(needle)
                                || "not-captured".contains(needle)
                                || "hidden outbound calls".contains(needle)));
    }

    /**
     * How a network row's capture key reads now, as {@code {capture, capturedBy}}: a JDBC, messaging, or mail client's
     * connection is captured by SQL Trace, its broker's panel, or Email while that panel is available and enabled, as
     * BootUI then records that client's work, which a pool's connection carries later than its connect; a REST client
     * capture holds while REST Client Trace is visible; any other is not captured, or infrastructure.
     */
    private Function<String, String[]> captures() {
        Map<String, Boolean> visible = new HashMap<>();
        Function<String, Boolean> shown = panel -> visible.computeIfAbsent(
                panel, id -> evidence.read(new PanelProbe(id)).shown());
        return key -> {
            if (key == null) {
                return null;
            }
            if (SideEffectsStore.CAPTURE_INFRASTRUCTURE.equals(key)) {
                return new String[] {SideEffectsRowDto.INFRASTRUCTURE, null};
            }
            String panel = null;
            if (SideEffectsStore.REST_CAPTURED.equals(key)) {
                panel = BootUiPanels.REST_CLIENT_TRACE;
            } else if (SideEffectsStore.CAPTURE_SQL.equals(key)) {
                panel = BootUiPanels.SQL_TRACE;
            } else if (SideEffectsStore.CAPTURE_MAIL.equals(key)) {
                panel = BootUiPanels.EMAIL;
            } else if (key.startsWith(SideEffectsStore.CAPTURE_MESSAGING)) {
                panel = JournalSourcePanels.messagingPanel(key.substring(SideEffectsStore.CAPTURE_MESSAGING.length()));
            }
            if (panel != null && shown.apply(panel)) {
                return new String[] {SideEffectsRowDto.CAPTURED, panel};
            }
            return new String[] {SideEffectsRowDto.NOT_CAPTURED, null};
        };
    }

    /** What a registry slot named, for the thread local whose hash code it was asked with. */
    record Named<T>(int hash, T value) {

        /** {@code named}'s value when it names the thread local with {@code hash}; {@code null} for another one. */
        static <T> T of(Named<T> named, int hash) {
            return named != null && named.hash() == hash ? named.value() : null;
        }
    }

    /**
     * Remembers {@code value} for registry slot {@code id}, replacing what a slot the bridge reused named before;
     * nothing more once {@value #MAX_HOLDERS} slots are known, which the bridge's registry never exceeds.
     */
    static <T> void remember(Map<Integer, Named<T>> cache, int id, int hash, T value) {
        if (cache.size() < MAX_HOLDERS || cache.containsKey(id)) {
            cache.put(id, new Named<>(hash, value));
        }
    }

    /** One panel, for its visibility only. */
    private record PanelProbe(String panel) implements AgentEvidence.Store {

        @Override
        public String id() {
            return panel;
        }

        @Override
        public String title() {
            return panel;
        }

        @Override
        public String unavailableReason() {
            return null;
        }

        @Override
        public AgentEvidence.Usage usage() {
            return new AgentEvidence.Usage(0L, 0L, Map.of());
        }

        @Override
        public String clear(long epochMillis) {
            return null;
        }
    }

    /**
     * The hosts this run's network rows opened, from routes (while HTTP Exchanges is visible), scheduled jobs, and
     * application call sites, for the runtime model's {@code OPENS} edges ({@code docs/PLAN-v2.md} §5.16): empty while
     * Side Effects is hidden or records nothing.
     */
    public List<HostOpen> hostOpens() {
        AgentEvidence.Read read = read();
        if (shownReason(read) != null) {
            return List.of();
        }
        Run current = current();
        if (current == null) {
            return List.of();
        }
        List<HostOpen> opens = new ArrayList<>();
        synchronized (lock) {
            for (SideEffectsStore.Opened opened :
                    current.store.opened(SideEffectsCatalog.NETWORK_ID, read.requests())) {
                if (SideEffectsRowDto.ROUTE.equals(opened.scope())
                        && !SideEffectsStore.UNKNOWN_ROUTE.equals(opened.attribution())) {
                    opens.add(new HostOpen(HostOpen.ROUTE, opened.attribution(), opened.target(), opened.count()));
                } else if (SideEffectsRowDto.EXECUTION.equals(opened.scope())
                        && opened.attribution().startsWith("scheduled ")) {
                    opens.add(new HostOpen(
                            HostOpen.SCHEDULED_JOB,
                            opened.attribution().substring("scheduled ".length()),
                            opened.target(),
                            opened.count()));
                }
                String callSite = opened.callSite();
                int hash = callSite == null ? -1 : callSite.indexOf('#');
                // Only an application class's: a library's frame, which a call site falls back to, is no bean's edge.
                if (hash > 0 && application(current.claim, callSite.substring(0, hash))) {
                    opens.add(
                            new HostOpen(HostOpen.CLASS, callSite.substring(0, hash), opened.target(), opened.count()));
                }
            }
        }
        return opens;
    }

    /** Whether {@code className} is in the claim's application packages. */
    private static boolean application(AgentClaim claim, String className) {
        if (claim == null || claim.packages() == null) {
            return false;
        }
        for (String prefix : claim.packages()) {
            if (prefix != null
                    && !prefix.isEmpty()
                    && (className.equals(prefix)
                            || className.startsWith(prefix.endsWith(".") ? prefix : prefix + "."))) {
                return true;
            }
        }
        return false;
    }

    /** A cheap fingerprint of {@link #hostOpens()}: changes when the run, its rows, or their counts change. */
    public long hostOpensFingerprint() {
        Run current;
        synchronized (lock) {
            current = run;
        }
        if (current == null) {
            return 0L;
        }
        synchronized (lock) {
            return current.generation * 31 + current.store.version() * 17 + current.clears;
        }
    }

    private static boolean contains(String text, String needle) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(needle);
    }

    private SideEffectsSensorDto sensorDto(SideEffectsCatalog.Sensor sensor, String reason, Run current) {
        if (!sensor.available()) {
            return new SideEffectsSensorDto(
                    sensor.id(),
                    sensor.group(),
                    sensor.label(),
                    SideEffectsSensorDto.NOT_AVAILABLE,
                    SideEffectsCatalog.NOT_IN_THIS_VERSION,
                    0,
                    0,
                    0,
                    List.of());
        }
        JavaAgentService.SideEffectsCoverage covered = coverage(sensor.id(), reason);
        if (AgentSensorSettings.BLOCKING.equals(sensor.id())
                && SideEffectsSensorDto.RECORDING.equals(covered.state())
                && !serverEventLoops()
                && eventLoops() == 0) {
            covered = new JavaAgentService.SideEffectsCoverage(
                    SideEffectsSensorDto.NOT_APPLICABLE, BLOCKING_NOT_APPLICABLE, covered.hooks(), covered.dropped());
        }
        long rows = 0;
        long occurrences = 0;
        long dropped = covered.dropped();
        if (current != null) {
            synchronized (lock) {
                rows = current.store.rowCount(sensor.id());
                occurrences = current.store.occurrences(sensor.id());
                dropped += current.store.dropped(sensor.id());
            }
        }
        return new SideEffectsSensorDto(
                sensor.id(),
                sensor.group(),
                sensor.label(),
                covered.state(),
                covered.reason(),
                rows,
                occurrences,
                dropped,
                covered.hooks(),
                covered.toggle());
    }

    private JavaAgentService.SideEffectsCoverage coverage(String id, String reason) {
        if (reason != null) {
            return new JavaAgentService.SideEffectsCoverage(SideEffectsSensorDto.UNAVAILABLE, reason, List.of(), 0L);
        }
        try {
            JavaAgentService.SideEffectsCoverage covered = coverage == null ? null : coverage.apply(id);
            if (covered != null) {
                return covered;
            }
        } catch (RuntimeException ex) {
            // Reported as unavailable below.
        }
        return new JavaAgentService.SideEffectsCoverage(
                SideEffectsSensorDto.UNAVAILABLE, JavaAgentService.SIDE_EFFECTS_REQUIREMENT + ".", List.of(), 0L);
    }

    private List<String> limitations(Run current, AgentEvidence.Read read) {
        List<String> limitations = new ArrayList<>(List.of(
                LIMITATION_SCOPE,
                LIMITATION_VALUES,
                LIMITATION_NETWORK,
                LIMITATION_CAPTURE,
                LIMITATION_FILES,
                LIMITATION_ENVIRONMENT,
                LIMITATION_BLOCKING,
                LIMITATION_THREADS,
                LIMITATION_THREAD_LOCALS,
                LIMITATION_SECURITY_SINKS,
                LIMITATION_SECURITY_CHECKS,
                LIMITATION_ATTRIBUTION));
        if (current != null && current.claim.sensors().threadLocals()) {
            if ("inventory".equals(threadLocalsStatus("initializationCheck"))) {
                limitations.add(LIMITATION_THREAD_LOCALS_INVENTORY);
            }
            Object virtual = threadLocalsStatus("virtualSkipped");
            if (virtual instanceof Number skipped && skipped.longValue() > 0) {
                limitations.add("Thread locals: " + skipped.longValue() + " scopes ran on virtual threads, which are"
                        + " not pooled and not scanned; with spring.threads.virtual.enabled, requests run on them.");
            }
        }
        if (current != null && current.claim.sensors().blocking()) {
            if (serverEventLoops() && eventLoops() == 0) {
                limitations.add(LIMITATION_NO_EVENT_LOOP);
            }
            if (blockingCounter("eventLoopRegistrationsRefused") > 0) {
                limitations.add(LIMITATION_LOOPS_REFUSED);
            }
            boolean callSitesFailed = coverage(AgentSensorSettings.BLOCKING, null).hooks().stream()
                    .anyMatch(hook -> hook.id() != null
                            && hook.id().endsWith("call sites")
                            && hook.selfTest() != null
                            && hook.selfTest().startsWith("failed"));
            if (callSitesFailed) {
                limitations.add(LIMITATION_CALL_SITES_FAILED);
            }
        }
        if (current != null
                && (current.claim.uses(AgentSensorSettings.THREAD_ACTIVITY)
                        || current.claim.sensorOverrides().containsKey(AgentSensorSettings.THREAD_ACTIVITY))) {
            // Also once switched off at run time, when what it dropped is reported.
            Map<String, Object> counters = sensorCounters(AgentSensorSettings.THREAD_ACTIVITY);
            Map<String, Object> baseline = current.threadBaseline;
            long untracked = increase(counters, baseline, "untracked");
            if (untracked > 0) {
                limitations.add(untracked + (untracked == 1 ? " thread or executor was" : " threads or executors were")
                        + " not tracked: the sensor tracks at most 1,024 threads waiting for their request's end and"
                        + " 1,024 executors at a time, so what they left running is not reported.");
            }
            long unresolved = increase(counters, baseline, "unresolved");
            if (unresolved > 0) {
                limitations.add(
                        unresolved + (unresolved == 1 ? " thread or executor was" : " threads or executors were")
                                + " not checked: their request's end never reached the sensor within 10 minutes, so whether"
                                + " they outlived it is unknown.");
            }
            long endsLost = increase(counters, baseline, "requestEndsLost");
            if (endsLost > 0) {
                limitations.add(endsLost + (endsLost == 1 ? " request's end was" : " requests' ends were")
                        + " lost: more requests ended at once than the sensor holds, so what they started waits 10"
                        + " minutes, then is counted not checked.");
            }
            long dropped = increase(counters, baseline, "dropped");
            if (dropped > 0) {
                limitations.add(dropped + (dropped == 1 ? " thread or executor was" : " threads or executors were")
                        + " never checked: they still waited for their request's end when the thread-activity sensor"
                        + " was switched off.");
            }
        }
        if (read.shown() && !read.requests()) {
            limitations.add(LIMITATION_ROUTES_HIDDEN);
        }
        if (current != null) {
            synchronized (lock) {
                long folded = current.store.folded();
                if (folded > 0) {
                    limitations.add(folded + (folded == 1 ? " operation is" : " operations are")
                            + " counted in an Other row: a sensor keeps at most " + current.store.maxRowsPerSensor()
                            + " rows and the run " + current.store.maxRows() + ".");
                }
                if (current.clears > 0) {
                    limitations.add(RECORDING_CLEARED);
                }
                String requestValues =
                        holderLine(AgentRequestValues.enabled() ? AgentRequestValues.status() : Map.of());
                if (requestValues != null) {
                    limitations.add(requestValues);
                }
                long unconfirmed = current.store.unconfirmed();
                if (unconfirmed > 0) {
                    limitations.add(unconfirmed
                            + (unconfirmed == 1 ? " security-sinks match is" : " security-sinks" + " matches are")
                            + " not shown yet: a value outside an SQL literal, a number, or one whose place in the text"
                            + " is not known may be a word the text always holds, until a second request confirms the"
                            + " text varies with it.");
                }
                if (current.stale > 0) {
                    limitations.add(current.stale + " records of an earlier run were dropped.");
                }
                if (!current.excludedHolders.isEmpty()) {
                    List<String> dropped = new ArrayList<>();
                    current.excludedHolders.forEach((holder, count) -> dropped.add(holder + " " + count));
                    limitations.add("Thread locals left set but not shown, as frameworks clear them or as per-thread"
                            + " caches: " + String.join(", ", dropped) + ".");
                }
            }
        }
        return limitations;
    }

    /**
     * The request value holder's counters as one limitation (M5-6b): how many requests held values and how many sink
     * checks ran, then what it skipped or could not keep, each only when it happened; {@code null} without the holder.
     */
    static String holderLine(Map<String, Object> holder) {
        if (holder == null || holder.isEmpty()) {
            return null;
        }
        StringBuilder line = new StringBuilder("Request-value matching: ")
                .append(count(holder, "requests"))
                .append(" requests held values, ")
                .append(count(holder, "checks"))
                .append(" sink checks ran.");
        append(line, holder, "valuesTooLong", " values longer than 256 characters were not held.");
        append(line, holder, "valuesOverCount", " values past 32 in a request were not held.");
        append(line, holder, "tableFull", " requests held nothing: 128 requests held values already.");
        append(line, holder, "stopped", " requests reached their matching budget: later sinks were not checked.");
        append(line, holder, "partial", " texts were checked in their first 16 KB only.");
        append(line, holder, "busy", " checks were skipped while another check of the same request ran.");
        append(line, holder, "notKept", " targets or names were not kept: too many distinct ones in this run.");
        append(line, holder, "dropped", " matches were dropped: the agent's ring was full.");
        return line.toString();
    }

    private static long count(Map<String, Object> holder, String key) {
        return holder.get(key) instanceof Number number ? number.longValue() : 0L;
    }

    private static void append(StringBuilder line, Map<String, Object> holder, String key, String text) {
        long value = count(holder, key);
        if (value > 0) {
            line.append(' ').append(value).append(text);
        }
    }

    /** Whether this application's server handles requests on event loops, as its adapter says. */
    private boolean serverEventLoops() {
        try {
            return serverEventLoops.getAsBoolean();
        } catch (RuntimeException ex) {
            return true;
        }
    }

    /** The event loops the adapters registered with the agent for this run, from the bridge's blocking counters. */
    private long eventLoops() {
        return blockingCounter("eventLoops");
    }

    /** One of the bridge's blocking counters, 0 when unavailable. */
    private Object threadLocalsStatus(String name) {
        try {
            return AgentBridgeAccess.map(access.status(), AgentSensorSettings.THREAD_LOCALS)
                    .get(name);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** Tests only: how thread locals' holders are named. */
    void threadLocalHolders(ThreadLocalHolders.Resolver resolver) {
        this.threadLocalHolders = resolver == null ? ThreadLocalHolders.AGENT : resolver;
    }

    private long blockingCounter(String name) {
        return sensorCounter(AgentSensorSettings.BLOCKING, name);
    }

    /** A side-effect sensor's counter in the bridge's status, 0 when absent. */
    /** The bridge's counters of {@code sensor}, read once; empty when it reports none. */
    private Map<String, Object> sensorCounters(String sensor) {
        try {
            return AgentBridgeAccess.map(access.status(), sensor);
        } catch (RuntimeException ex) {
            return Map.of();
        }
    }

    /** How much counter {@code name} grew since {@code baseline}. */
    private static long increase(Map<String, Object> counters, Map<String, Object> baseline, String name) {
        return Math.max(0L, counter(counters, name) - counter(baseline, name));
    }

    private static long counter(Map<String, Object> counters, String name) {
        Object value = counters.get(name);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private long sensorCounter(String sensor, String name) {
        try {
            Object value = AgentBridgeAccess.map(access.status(), sensor).get(name);
            return value instanceof Number number ? number.longValue() : 0L;
        } catch (RuntimeException ex) {
            return 0L;
        }
    }

    /** This run's counters, for status and tests: JDK types. */
    public Map<String, Object> status() {
        Map<String, Object> map = new LinkedHashMap<>();
        Run current = current();
        map.put("generation", current == null ? null : current.generation);
        if (current != null) {
            synchronized (lock) {
                map.put("records", current.records);
                map.put("staleRecords", current.stale);
                map.put("malformedRecords", current.malformed);
                map.put("bootUiRecords", current.bootUi);
                map.put("observations", current.store.observations());
                map.put("pending", current.store.pendingCount());
                map.put("rows", current.store.rowCount());
                map.put("retainedBytes", current.store.retainedBytes());
                map.put("folded", current.store.folded());
                map.put("clearedRecords", current.cleared);
            }
        }
        return map;
    }

    private Run settledRun() {
        Run current = current();
        if (current == null) {
            return null;
        }
        current.drainNow();
        // Outside the lock: the agent may take its time budget to name thread locals' holders.
        current.retryHolders();
        synchronized (lock) {
            current.resolve(true);
            publish(current);
        }
        return current;
    }

    /** Publishes what {@code current} holds, for the evidence store's usage, which never waits on the lock. */
    private void publish(Run current) {
        usageIndexBytes = current.indexBytes();
        usageBytes = current.store.retainedBytes() + usageIndexBytes;
        usageMaxBytes = current.store.maxBytes() + current.maxIndexBytes();
        usageRows = current.store.rowCount();
        usageWaiting = current.store.pendingCount();
    }

    private Run current() {
        start();
        synchronized (lock) {
            return run;
        }
    }

    /** Whether Code Paths, whose stamp names the bean method a row happened inside, is visible now. */
    private boolean codePathsShown() {
        return evidence.read(codePathsPanel).shown();
    }

    /** The bridge's shared target past its bound of distinct network targets. */
    static final String OTHER_HOSTS = "(other hosts)";

    /** Whether a frame is the JDK's. */
    static boolean jdk(String frame) {
        return frame.startsWith("java.")
                || frame.startsWith("javax.")
                || frame.startsWith("jdk.")
                || frame.startsWith("sun.")
                || frame.startsWith("com.sun.");
    }

    /** How a connect or datagram of {@code client} is captured: by category, now, or once a REST call names it. */
    static String captureKey(NetworkClients.Client client) {
        if (client == null) {
            return SideEffectsStore.REST_WAITING;
        }
        return switch (client.category()) {
            case NetworkClients.INFRASTRUCTURE -> SideEffectsStore.CAPTURE_INFRASTRUCTURE;
            case NetworkClients.SQL -> SideEffectsStore.CAPTURE_SQL;
            case NetworkClients.MAIL -> SideEffectsStore.CAPTURE_MAIL;
            case NetworkClients.MESSAGING -> SideEffectsStore.CAPTURE_MESSAGING + client.broker();
            case NetworkClients.HTTP -> SideEffectsStore.REST_WAITING_HTTP;
            default -> SideEffectsStore.REST_WAITING;
        };
    }

    /** Code Paths' panel, for its read only. */
    private static final class CodePathsPanel implements AgentEvidence.Store {

        @Override
        public String id() {
            return BootUiPanels.CODE_PATHS;
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
            return null;
        }

        @Override
        public AgentEvidence.Usage usage() {
            return new AgentEvidence.Usage(0L, 0L, Map.of());
        }

        @Override
        public String clear(long epochMillis) {
            return null;
        }
    }

    /** This service's rows as a store of the agent evidence contract: counted, and cleared with the journal. */
    private final class Store implements AgentEvidence.Store {

        @Override
        public String id() {
            return BootUiPanels.SIDE_EFFECTS;
        }

        @Override
        public String panel() {
            return BootUiPanels.SIDE_EFFECTS;
        }

        @Override
        public String title() {
            return "Side Effects";
        }

        @Override
        public String unavailableReason() {
            return SideEffectsService.this.unavailableReason();
        }

        @Override
        public AgentEvidence.Usage usage() {
            Map<String, Long> counts = new LinkedHashMap<>();
            counts.put("sideEffectRows", usageRows);
            counts.put("sideEffectsWaiting", usageWaiting);
            counts.put("indexBytes", usageIndexBytes);
            return new AgentEvidence.Usage(usageBytes, usageMaxBytes, counts);
        }

        /**
         * Drops every row and waiting observation of the run, under the lock the drain thread takes for each record;
         * from now on, a record whose first occurrence came at or before {@code epochMillis}, still held in the agent's
         * ring or a thread's table, is dropped too. The counts since the claim are kept.
         */
        @Override
        public String clear(long epochMillis) {
            synchronized (lock) {
                // Kept for a run that starts later: a record the agent still holds from before the clear is dropped.
                lastClearedAt = Math.max(lastClearedAt, epochMillis);
                Run current = run;
                if (current == null) {
                    return null;
                }
                int rows = current.store.rowCount();
                current.store.clear();
                current.excludedHolders.clear();
                current.givenUp.clear();
                current.claim.sideEffectsRecordingCleared();
                // Buckets are the bridge's counters since the claim: counted from now on.
                current.bucketBaseline = new HashMap<>(
                        coverage(SideEffectsCatalog.FILES_ID, null).buckets());
                current.clearedAt = Math.max(current.clearedAt, epochMillis);
                current.clears++;
                publish(current);
                return rows == 0 ? null : rows + (rows == 1 ? " row" : " rows") + " of Side Effects";
            }
        }
    }

    /** One run: its claim, drainer route, strings, and store. */
    private final class Run implements Consumer<long[]> {

        final AgentClaim claim;
        final long generation;
        final AgentRecordDrainer drainer;
        final SideEffectsStore store;
        final List<String> strings = new ArrayList<>();
        final Map<Integer, String> methods = new HashMap<>();

        /**
         * The bytes of the strings and method labels this run resolved, kept beside its rows through a clear: about
         * {@value #STRING_BYTES} bytes a string, at most the agent's intern table, and {@value #METHOD_BYTES} a method,
         * at most the rows the store keeps.
         */
        long indexBytes() {
            return strings.size() * STRING_BYTES
                    + (methods.size() + holders.size() + givenUp.size()) * METHOD_BYTES
                    + holderWaiting.size() * SideEffectsStore.PENDING_BYTES;
        }

        /**
         * The thread locals' holders this run named, by registry id, each with the hash code of the thread local it
         * named: a slot the bridge reuses for another thread local replaces its entry, so there are at most
         * {@value #MAX_HOLDERS}.
         */
        final Map<Integer, Named<ThreadLocalHolders.Holder>> holders = new HashMap<>();

        /** Thread-locals records waiting for the agent's time to name their holder. */
        final java.util.ArrayDeque<WaitingHolder> holderWaiting = new java.util.ArrayDeque<>();

        /** Thread locals dropped, by framework holder or reason, with how often they were left set. */
        final Map<String, Long> excludedHolders = new java.util.TreeMap<>();

        /**
         * The thread locals whose holder the engine gave up on, by registry id with their hash code: their rows are
         * marked, and move to their holder, or go when it is a framework's, once a later record resolves it.
         */
        final Map<Integer, Integer> givenUp = new HashMap<>();

        /** The agent's answers, by registry id with their thread local's hash code, asked for outside the lock. */
        final Map<Integer, Named<String[]>> answers = new java.util.concurrent.ConcurrentHashMap<>();

        /** Guards the holders' time budget, never held while the agent resolves. */
        final Object holderBudget = new Object();

        long holderWindowStart = Long.MIN_VALUE / 2;
        long holderSpentNanos;

        long maxIndexBytes() {
            return MAX_STRINGS * STRING_BYTES + ((long) store.maxRows() + 1_024L) * METHOD_BYTES;
        }

        long records;
        long stale;
        long malformed;
        long bootUi;
        Map<String, Object> threadBaseline = Map.of();
        long clears;
        long cleared;
        long clearedAt = lastClearedAt;
        Map<String, Long> bucketBaseline = Map.of();

        /** Each compared sensor's state when this run started (M5-7b). */
        final Map<String, SideEffectsSample> started = new HashMap<>();

        /** Records whose target the agent could not keep, per sensor, after startup and during it. */
        final Map<String, Long> unknownTargets = new HashMap<>();

        final Map<String, Long> unknownStartupTargets = new HashMap<>();

        /** The sensors a runtime switch changed during this run (M5-14). */
        final java.util.Set<String> switched = new java.util.HashSet<>();

        /** Whether a runtime switch reinstalled the side-effect transformer the compared sensors share. */
        boolean reinstalled;

        long drainResolvedAt = Long.MIN_VALUE / 2;

        Run(AgentClaim claim, long readyAt) {
            this.claim = claim;
            this.generation = claim.generation();
            this.drainer = claim.drainer();
            // Shrunk in proportion by a configured agent evidence bound (M5-11).
            this.store = new SideEffectsStore(
                    readyAt,
                    evidence.scaled(SideEffectsStore.MAX_ROWS, 100),
                    evidence.scaled(SideEffectsStore.MAX_ROWS_PER_SENSOR, 50),
                    evidence.scaled(SideEffectsStore.MAX_PENDING, 500));
            this.store.setCapture(networkCapture);
            Map<String, SideEffectsSample> now = samples();
            for (String id : COMPARED_SENSORS) {
                started.put(id, now.getOrDefault(id, SideEffectsSample.NONE));
            }
            // Only the thread that finished starting owns startup's keys: other threads' early work may land on either
            // side of the end of startup from one run to the next (M5-7b).
            this.store.setStartupThread(
                    normalizer.threadFamily(Thread.currentThread().getName()));
        }

        void start() {
            if (drainer != null) {
                drainer.routeSideEffects(this);
            }
        }

        void drainNow() {
            if (drainer != null) {
                drainer.drainNow();
            }
        }

        void close() {
            if (drainer != null) {
                drainer.unrouteSideEffects(this);
            }
        }

        /**
         * Asks the journal for the routes of requests that started waiting, and of those it did not name, again after
         * {@value #RESOLVE_MILLIS} ms from the drain thread or {@value #READ_RESOLVE_MILLIS} ms for a read.
         */
        void resolve(boolean read) {
            if (store.pendingCount() == 0) {
                return;
            }
            long now = clock.getAsLong();
            if (!read && now - drainResolvedAt < RESOLVE_MILLIS) {
                // The drain thread walks the journal at most this often; a read asks for what it shows.
                return;
            }
            if (!read) {
                drainResolvedAt = now;
            }
            store.resolve(SideEffectsService.this::names, now, read ? READ_RESOLVE_MILLIS : RESOLVE_MILLIS);
        }

        /** One record, on the drain thread: the reused array is read here and not kept. */
        @Override
        public void accept(long[] raw) {
            SideEffectRecord record = SideEffectRecord.decode(raw);
            String[] answer = null;
            if (record != null
                    && record.sensor() == SideEffectsCatalog.RECORD_THREAD_LOCALS
                    && record.generation() == generation) {
                // Outside the lock: reads and Clear recording never wait for the agent's time budget.
                retryHolders();
                answer = answer(record, false);
            }
            synchronized (lock) {
                if (record == null) {
                    malformed++;
                    return;
                }
                if (record.generation() != generation) {
                    stale++;
                    return;
                }
                SideEffectsCatalog.Sensor sensor = SideEffectsCatalog.byRecordId(record.sensor());
                if (sensor == null) {
                    malformed++;
                    return;
                }
                records++;
                if (record.firstMillis() <= clearedAt) {
                    // Recorded before Clear recording, and still held by the agent then.
                    cleared++;
                    return;
                }
                String outside = string(record.outsideFrame());
                String application = string(record.applicationFrame());
                if (bootUi(outside) || bootUi(application)) {
                    bootUi++;
                    return;
                }
                String target = string(record.target());
                if (target == null || OTHER_HOSTS.equals(target)) {
                    // The agent could not keep this record's target: the run is not compared for its sensor.
                    (record.firstMillis() < store.readyAt() ? unknownStartupTargets : unknownTargets)
                            .merge(sensor.id(), 1L, Long::sum);
                }
                if (SideEffectsCatalog.check(record.sensor(), record.kind())) {
                    store.add(check(record, sensor, target, outside, application));
                } else if (record.sensor() == SideEffectsCatalog.RECORD_SECURITY_SINKS) {
                    store.add(sink(record, sensor, target, outside, application));
                } else if (record.sensor() == SideEffectsCatalog.RECORD_NETWORK) {
                    // Ahead of the context below: a network record's bits 32-63 are its client frame, not a context.
                    store.add(network(record, sensor, target, outside, application));
                } else if (record.sensor() == SideEffectsCatalog.RECORD_PROCESSES) {
                    store.add(new SideEffectsStore.Observation(
                            record,
                            sensor.id(),
                            SideEffectsCatalog.kind(record.sensor(), record.kind()),
                            target == null ? UNKNOWN_TARGET : normalizer.target(target),
                            application != null ? application : outside,
                            insideMethod(record.stamp()),
                            normalizer.threadFamily(string(record.threadName()))));
                } else if (record.sensor() == SideEffectsCatalog.RECORD_THREADS) {
                    store.add(threads(record, sensor, target, outside, application));
                } else if (record.sensor() == SideEffectsCatalog.RECORD_THREAD_LOCALS) {
                    threadLocal(record, sensor, target, answer, clock.getAsLong());
                } else if (record.sensor() == SideEffectsCatalog.RECORD_BLOCKING) {
                    // The target is the event loop's thread name: shown as its family, as a thread row's is.
                    String loop = normalizer.threadFamily(target);
                    store.add(new SideEffectsStore.Observation(
                            record,
                            sensor.id(),
                            SideEffectsCatalog.kind(record.sensor(), record.kind()),
                            loop == null ? "(unknown)" : loop,
                            application != null ? application : outside,
                            insideMethod(record.stamp()),
                            normalizer.threadFamily(string(record.threadName()))));
                } else {
                    String origin = SideEffectOrigins.origin(record.context(), outside, application);
                    String location = null;
                    String shown;
                    if (record.sensor() == SideEffectsCatalog.RECORD_FILES) {
                        // The agent made the pattern ($TMPDIR, ./, ~, ids collapsed); masked per segment here.
                        shown = target == null ? TOO_MANY_PATHS : SideEffectOrigins.maskPath(normalizer.target(target));
                        location = SideEffectOrigins.location(target);
                    } else {
                        // A name, never normalized: its digits are part of it, and its value never reaches BootUI.
                        shown = target == null ? TOO_MANY_NAMES : SideEffectOrigins.maskName(target);
                    }
                    store.add(new SideEffectsStore.Observation(
                            record,
                            sensor.id(),
                            SideEffectsCatalog.kind(record.sensor(), record.kind()),
                            shown,
                            application != null ? application : outside,
                            insideMethod(record.stamp()),
                            normalizer.threadFamily(string(record.threadName())),
                            origin,
                            location));
                }
                resolve(false);
                publish(this);
            }
        }

        /** A thread-locals record waiting for its holder, since {@code since}. */
        record WaitingHolder(SideEffectRecord record, SideEffectsCatalog.Sensor sensor, String target, long since) {}

        /**
         * A thread-locals record, under the lock, with the agent's {@code answer} naming its holder, which decides its
         * row or drops it as a framework's or a per-thread cache, counted; {@code null} when the agent had no time: it
         * waits, at most {@value #HOLDER_WAIT_MILLIS} ms, then shows as not resolved.
         */
        private void threadLocal(
                SideEffectRecord record, SideEffectsCatalog.Sensor sensor, String target, String[] answer, long now) {
            if (answer == null) {
                if (holderWaiting.size() < MAX_HOLDER_WAITING) {
                    holderWaiting.add(new WaitingHolder(record, sensor, target, now));
                    return;
                }
                answer = answer(record, true);
            }
            observeThreadLocal(record, sensor, target, answer);
        }

        /**
         * The waiting thread locals the agent has time for, and those waiting too long, as not resolved: asked outside
         * the lock, observed under it.
         */
        void retryHolders() {
            while (true) {
                WaitingHolder waiting;
                long now;
                synchronized (lock) {
                    waiting = holderWaiting.peek();
                    now = clock.getAsLong();
                }
                if (waiting == null) {
                    return;
                }
                // Asked again first: retries are as sparse as the records, so one past the wait may be its first.
                String[] answer = answer(waiting.record(), false);
                if (answer == null && now - waiting.since() > HOLDER_WAIT_MILLIS) {
                    answer = answer(waiting.record(), true);
                }
                if (answer == null) {
                    return;
                }
                synchronized (lock) {
                    if (holderWaiting.peek() != waiting) {
                        // Another thread observed it meanwhile.
                        continue;
                    }
                    holderWaiting.poll();
                    if (waiting.record().firstMillis() > clearedAt) {
                        observeThreadLocal(waiting.record(), waiting.sensor(), waiting.target(), answer);
                    }
                    publish(this);
                }
            }
        }

        /**
         * The agent's answer naming a record's holder, within the time budget, never under the lock: {@code null} when
         * out of time, unless {@code giveUp}, which answers "not resolved" without asking, and is never remembered.
         */
        String[] answer(SideEffectRecord record, boolean giveUp) {
            int id = (record.exitStatus() >>> 8) & 0xFFFF;
            int hash = (int) record.nanos();
            String[] known = Named.of(answers.get(id), hash);
            if (known != null) {
                return known;
            }
            if (id == 0 || giveUp) {
                // Never remembered: a later record of the same thread local asks the agent again.
                return GAVE_UP;
            }
            long left;
            synchronized (holderBudget) {
                long now = clock.getAsLong();
                if (now - holderWindowStart >= HOLDER_WINDOW_MILLIS) {
                    holderWindowStart = now;
                    holderSpentNanos = 0L;
                }
                left = HOLDER_BUDGET_NANOS - holderSpentNanos;
            }
            if (left <= 0L) {
                return null;
            }
            long started = System.nanoTime();
            String[] answer = threadLocalHolders.holder(
                    generation,
                    id,
                    hash,
                    claim.packages().toArray(new String[0]),
                    ThreadLocalHolders.HOLDER_CLASSES.toArray(new String[0]),
                    left);
            synchronized (holderBudget) {
                holderSpentNanos += System.nanoTime() - started;
            }
            if (answer != null) {
                remember(answers, id, hash, answer);
            }
            return answer;
        }

        /** Its holder, under the lock: decided once per thread local, whose exclusion the bridge is told once. */
        private ThreadLocalHolders.Holder holder(SideEffectRecord record, String target, String[] answer) {
            int detail = record.exitStatus() & 0xFF;
            int id = (record.exitStatus() >>> 8) & 0xFFFF;
            int hash = (int) record.nanos();
            ThreadLocalHolders.Holder known = Named.of(holders.get(id), hash);
            if (known != null) {
                return known;
            }
            ThreadLocalHolders.Holder holder = ThreadLocalHolders.decide(answer, target, detail);
            if (id != 0 && answer != GAVE_UP) {
                remember(holders, id, hash, holder);
                Integer gaveUpOn = givenUp.get(id);
                if (gaveUpOn != null && gaveUpOn == hash) {
                    // Resolved after a give-up: its "not resolved" rows move to the holder, or go with it.
                    givenUp.remove(id);
                    long removed = store.resolveThreadLocal(
                            SideEffectsStore.unresolvedThreadLocal(id, hash),
                            holder.kind(),
                            holder.target(),
                            holder.origin(),
                            holder.excludedBy() != null);
                    if (removed > 0) {
                        excludedHolders.merge(holder.excludedBy(), removed, Long::sum);
                    }
                }
                if (holder.excludedBy() != null) {
                    // Skipped by the bridge from now on, so it never takes an application's thread local's place.
                    threadLocalHolders.exclude(generation, id, hash);
                }
            }
            return holder;
        }

        private void observeThreadLocal(
                SideEffectRecord record, SideEffectsCatalog.Sensor sensor, String target, String[] answer) {
            ThreadLocalHolders.Holder holder = holder(record, target, answer);
            if (holder.excludedBy() != null) {
                excludedHolders.merge(holder.excludedBy(), record.count(), Long::sum);
                return;
            }
            int id = (record.exitStatus() >>> 8) & 0xFFFF;
            int hash = (int) record.nanos();
            String marker = null;
            if (answer == GAVE_UP && id != 0) {
                // Marked, so a later record resolving the same thread local can move or drop these rows.
                marker = SideEffectsStore.unresolvedThreadLocal(id, hash);
                if (givenUp.size() < MAX_HOLDERS || givenUp.containsKey(id)) {
                    givenUp.put(id, hash);
                }
            }
            store.add(new SideEffectsStore.Observation(
                    record,
                    sensor.id(),
                    holder.kind(),
                    holder.target(),
                    null,
                    null,
                    normalizer.threadFamily(string(record.threadName())),
                    null,
                    marker,
                    null,
                    -1,
                    holder.origin(),
                    null));
        }

        /**
         * A thread-activity record's observation: a thread's or an executor's, its target the started thread's family
         * or the executor's class, its origin from the bridge's walk, and its call site the first application frame,
         * else the first frame outside the JDK. A follow-up carries its creation's target, frames, and detail, so it
         * lands on its creation's row.
         */
        private SideEffectsStore.Observation threads(
                SideEffectRecord record,
                SideEffectsCatalog.Sensor sensor,
                String target,
                String outside,
                String application) {
            int detail = record.exitStatus();
            String kind = SideEffectsCatalog.kind(record.sensor(), record.kind());
            if (SideEffectsCatalog.THREAD.equals(kind) && (detail & SideEffectsCatalog.DETAIL_VIRTUAL) != 0) {
                kind = SideEffectsCatalog.VIRTUAL_THREAD;
            }
            String origin =
                    switch (detail & SideEffectsCatalog.DETAIL_ORIGIN) {
                        case SideEffectsCatalog.ORIGIN_APPLICATION -> SideEffectOrigins.APPLICATION;
                        case SideEffectsCatalog.ORIGIN_JDK -> SideEffectOrigins.JDK;
                        default -> SideEffectOrigins.LIBRARY;
                    };
            String shown = target == null
                    ? "(unknown)"
                    : SideEffectsCatalog.EXECUTOR.equals(kind) ? target : normalizer.target(target);
            return new SideEffectsStore.Observation(
                    record,
                    sensor.id(),
                    kind,
                    shown,
                    application != null ? application : outside,
                    insideMethod(record.stamp()),
                    normalizer.threadFamily(string(record.threadName())),
                    origin,
                    null);
        }

        /**
         * A network record's observation: its client recognized from its frames and thread, its call site the first
         * application frame, else the client's frame outside the JDK, else the first frame outside the JDK, and how a
         * panel captures it: a lookup never, an infrastructure client never needs one, a SQL, messaging, or mail
         * client's by category on read, and any other once a REST client call names it, or not.
         */
        private SideEffectsStore.Observation network(
                SideEffectRecord record,
                SideEffectsCatalog.Sensor sensor,
                String target,
                String outside,
                String application) {
            String client = string(record.clientFrame());
            String thread = normalizer.threadFamily(string(record.threadName()));
            String normalized = target == null ? UNKNOWN_TARGET : normalizer.networkTarget(target);
            NetworkClients.Client recognized =
                    NetworkClients.recognize(client, outside, application, thread, target, exporterEndpoints);
            String kind = SideEffectsCatalog.kind(record.sensor(), record.kind());
            String callSite = application != null
                    ? application
                    : client != null && !jdk(client) ? client : outside != null ? outside : client;
            String captureKey = null;
            String host = null;
            int port = -1;
            if (!SideEffectsCatalog.LOOKUP.equals(kind)) {
                String[] hostPort = JournalNetworkCapture.hostPort(target);
                if (hostPort != null) {
                    host = hostPort[0];
                    port = hostPort[1] == null ? -1 : Integer.parseInt(hostPort[1]);
                }
                // A target past the sensor's bound names no host: whether a panel shows it is unknown.
                captureKey = OTHER_HOSTS.equals(target) ? null : captureKey(recognized);
            }
            return new SideEffectsStore.Observation(
                    record,
                    sensor.id(),
                    kind,
                    normalized,
                    callSite,
                    insideMethod(record.stamp()),
                    thread,
                    recognized == null ? null : recognized.label(),
                    captureKey,
                    host,
                    port);
        }

        /**
         * A security-sinks record's observation (M5-6b): the sink, its redacted target (a file's pattern masked per
         * segment as the files sensor's), where in an SQL text the value sat, and the parameter's name; never a value.
         */
        private SideEffectsStore.Observation sink(
                SideEffectRecord record,
                SideEffectsCatalog.Sensor sensor,
                String target,
                String outside,
                String application) {
            String kind = SideEffectsCatalog.kind(record.sensor(), record.kind());
            String shown;
            if (target == null) {
                shown = TEXT_NOT_KEPT;
            } else if (record.kind() == SideEffectsCatalog.KIND_SINK_FILE) {
                shown = SideEffectOrigins.maskPath(normalizer.target(target));
            } else if (record.kind() == SideEffectsCatalog.KIND_SINK_URL) {
                // A path segment the secret detector recognizes is masked, as a file path's is.
                shown = SideEffectOrigins.maskPath(target);
            } else {
                shown = target;
            }
            String location = null;
            int position = record.outcome() & 0x3;
            if (record.kind() == SideEffectsCatalog.KIND_SINK_SQL
                    && position != SideEffectsCatalog.SINK_POSITION_UNKNOWN) {
                location = position == SideEffectsCatalog.SINK_OUTSIDE_LITERAL
                        ? SideEffectsCatalog.OUTSIDE_LITERAL
                        : SideEffectsCatalog.INSIDE_LITERAL;
            }
            String parameter = string(record.exitStatus());
            return SideEffectsStore.Observation.sink(
                    record,
                    sensor.id(),
                    kind,
                    shown,
                    application != null ? application : outside,
                    insideMethod(record.stamp()),
                    normalizer.threadFamily(string(record.threadName())),
                    location,
                    parameter == null ? "(name not kept)" : parameter);
        }

        /**
         * A security-sinks JDK check's observation (M5-6b2): what was asked for or installed, at the application frame,
         * else the first frame outside the JDK; a library's request names that library frame as its location and is
         * grouped as a library's; a deserialization carries the other classes it read.
         */
        private SideEffectsStore.Observation check(
                SideEffectRecord record,
                SideEffectsCatalog.Sensor sensor,
                String target,
                String outside,
                String application) {
            boolean library = (record.outcome() & 0x3) == SideEffectsCatalog.CHECK_LIBRARY;
            String shown = target != null
                    ? target
                    : record.kind() == SideEffectsCatalog.KIND_CHECK_DESERIALIZATION && record.target() == 0
                            ? CLASSES_NOT_NAMED
                            : TEXT_NOT_KEPT;
            return SideEffectsStore.Observation.check(
                    record,
                    sensor.id(),
                    SideEffectsCatalog.kind(record.sensor(), record.kind()),
                    shown,
                    application != null ? application : outside,
                    insideMethod(record.stamp()),
                    normalizer.threadFamily(string(record.threadName())),
                    library ? SideEffectOrigins.LIBRARY : SideEffectOrigins.APPLICATION,
                    library ? outside : null,
                    record.kind() == SideEffectsCatalog.KIND_CHECK_DESERIALIZATION
                            ? string(record.exitStatus())
                            : null);
        }

        /** A frame of BootUI's own modules, never an application's, as the sample apps' are. */
        private boolean bootUi(String frame) {
            return frame != null && StackFramePrefixes.isBootUiModule(frame);
        }

        /** The string of id {@code id} in this run's table, fetching what is new; {@code null} when unknown. */
        String string(int id) {
            if (id <= 0) {
                return null;
            }
            if (id > strings.size()) {
                String[] more = claim.sideEffectsInterned(strings.size() + 1);
                if (more != null) {
                    // An id an application thread claimed but has not filled yet reads null: kept for a later read.
                    for (int i = 0; i < more.length && more[i] != null; i++) {
                        strings.add(more[i]);
                    }
                }
            }
            return id <= strings.size() ? strings.get(id - 1) : null;
        }

        /** The bean method a Code Paths stamp names, as Code Paths labels it, or {@code null}. */
        String insideMethod(long stamp) {
            if (stamp <= 0L) {
                return null;
            }
            int method = CodePathStamps.method(stamp);
            if (method < 0) {
                return null;
            }
            String known = methods.get(method);
            if (known != null) {
                return known;
            }
            String[] key = access.methodKeys(method, 1);
            String label = key.length == 1 && key[0] != null ? CodePathStamps.label(key[0]) : null;
            if (label != null && methods.size() < store.maxRows()) {
                methods.put(method, label);
            }
            return label;
        }
    }
}
