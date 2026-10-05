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
import io.github.jdubois.bootui.engine.javaagent.AgentSensorSettings;
import io.github.jdubois.bootui.engine.javaagent.JavaAgentService;
import io.github.jdubois.bootui.engine.journal.AgentEvidence;
import io.github.jdubois.bootui.engine.journal.JournalSourcePanels;
import io.github.jdubois.bootui.engine.model.EdgeType;
import io.github.jdubois.bootui.engine.model.HostOpen;
import io.github.jdubois.bootui.engine.model.NodeType;
import io.github.jdubois.bootui.engine.model.SideEffectAccess;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.support.StackFramePrefixes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
            + " creates; and, opt-in, the thread locals a request or a job left set on its pooled thread. Resources left"
            + " open and security sinks are not available in this version.";

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

    /** How long a thread local waits for its holder, at most, and how many wait. */
    static final long HOLDER_WAIT_MILLIS = 10_000L;

    static final int MAX_HOLDER_WAITING = 1_024;

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
                        || !claim.sensors().sideEffects()
                        || !access.sideEffectsSupported()) {
                    return;
                }
                if (run != null && run.generation == claim.generation()) {
                    return;
                }
                if (run != null) {
                    run.close();
                }
                threadActivityClaim = claim.sensors().threadActivity() ? claim : null;
                run = new Run(claim, clock.getAsLong());
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
     * sensor id or part of a row's attribution, target, or call site, most frequent first, at most {@code limit}.
     */
    public SideEffectsAgentReport agentReport(String query, Integer limit) {
        String asked = query == null ? "" : query.trim();
        int max = limit == null || limit <= 0 ? SideEffectsAgentReport.DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        AgentEvidence.Read read = read();
        SideEffectsReport report = report(read);
        if (!report.available()) {
            return new SideEffectsAgentReport(
                    false, report.unavailableReason(), asked, report.sensors(), 0, List.of(), 0, List.of());
        }
        String needle = asked.toLowerCase(Locale.ROOT);
        boolean bySensor = SideEffectsCatalog.sensor(asked) != null;
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
        List<String> limitations = new ArrayList<>(report.limitations());
        if (matching.isEmpty() && !needle.isEmpty()) {
            limitations.add("No row matched \"" + asked + "\": call get_side_effects without a query to list them.");
        }
        return new SideEffectsAgentReport(
                true,
                null,
                asked,
                report.sensors(),
                matching.size(),
                List.copyOf(listed),
                matching.size() - listed.size(),
                limitations);
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
                || contains(row.callSite(), needle)
                || contains(row.insideMethod(), needle)
                || contains(row.sensor(), needle)
                || contains(row.client(), needle)
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
                covered.hooks());
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
        if (current != null && current.claim.sensors().threadActivity()) {
            long untracked = sensorCounter(AgentSensorSettings.THREAD_ACTIVITY, "untracked");
            if (untracked > 0) {
                limitations.add(untracked + (untracked == 1 ? " thread or executor was" : " threads or executors were")
                        + " not tracked: the sensor tracks at most 1,024 threads waiting for their request's end and"
                        + " 1,024 executors at a time, so what they left running is not reported.");
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
            return strings.size() * STRING_BYTES + (methods.size() + holders.size()) * METHOD_BYTES;
        }

        /** The thread locals' holders this run named, by registry id and hash code: at most the bridge's registry. */
        final Map<Long, ThreadLocalHolders.Holder> holders = new HashMap<>();

        /** Thread-locals records waiting for the agent's time to name their holder. */
        final java.util.ArrayDeque<WaitingHolder> holderWaiting = new java.util.ArrayDeque<>();

        /** Thread locals dropped, by framework holder or reason, with how often they were left set. */
        final Map<String, Long> excludedHolders = new java.util.TreeMap<>();

        long holderWindowStart = Long.MIN_VALUE / 2;
        long holderSpentNanos;

        long maxIndexBytes() {
            return MAX_STRINGS * STRING_BYTES + ((long) store.maxRows() + 1_024L) * METHOD_BYTES;
        }

        long records;
        long stale;
        long malformed;
        long bootUi;
        long clears;
        long cleared;
        long clearedAt = lastClearedAt;
        Map<String, Long> bucketBaseline = Map.of();
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
            retryHolders();
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
                if (record.sensor() == SideEffectsCatalog.RECORD_NETWORK) {
                    // Ahead of the context below: a network record's bits 32-63 are its client frame, not a context.
                    store.add(network(record, sensor, target, outside, application));
                } else if (record.sensor() == SideEffectsCatalog.RECORD_PROCESSES) {
                    store.add(new SideEffectsStore.Observation(
                            record,
                            sensor.id(),
                            SideEffectsCatalog.kind(record.sensor(), record.kind()),
                            target == null ? "(unknown)" : normalizer.target(target),
                            application != null ? application : outside,
                            insideMethod(record.stamp()),
                            normalizer.threadFamily(string(record.threadName()))));
                } else if (record.sensor() == SideEffectsCatalog.RECORD_THREADS) {
                    store.add(threads(record, sensor, target, outside, application));
                } else if (record.sensor() == SideEffectsCatalog.RECORD_THREAD_LOCALS) {
                    threadLocal(record, sensor, target, clock.getAsLong());
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
         * A thread-locals record: its thread local's holder, named once per run by the agent within its time budget,
         * decides its row, or drops it as a framework's or a per-thread cache, counted; while the agent has no time, it
         * waits, at most {@value #HOLDER_WAIT_MILLIS} ms, then shows as not resolved.
         */
        private void threadLocal(SideEffectRecord record, SideEffectsCatalog.Sensor sensor, String target, long now) {
            ThreadLocalHolders.Holder holder = holder(record, target, now, false);
            if (holder == null) {
                if (holderWaiting.size() >= MAX_HOLDER_WAITING) {
                    holder = holder(record, target, now, true);
                } else {
                    holderWaiting.add(new WaitingHolder(record, sensor, target, now));
                    return;
                }
            }
            observeThreadLocal(record, sensor, holder);
        }

        /** The waiting thread locals the agent has time for, and those waiting too long, as not resolved. */
        void retryHolders() {
            long now = clock.getAsLong();
            while (!holderWaiting.isEmpty()) {
                WaitingHolder waiting = holderWaiting.peek();
                boolean late = now - waiting.since() > HOLDER_WAIT_MILLIS;
                ThreadLocalHolders.Holder holder = holder(waiting.record(), waiting.target(), now, late);
                if (holder == null) {
                    return;
                }
                holderWaiting.poll();
                if (waiting.record().firstMillis() > clearedAt) {
                    observeThreadLocal(waiting.record(), waiting.sensor(), holder);
                }
            }
        }

        /** Its holder, named by the agent within the budget; {@code null} when out of time, unless {@code giveUp}. */
        private ThreadLocalHolders.Holder holder(SideEffectRecord record, String target, long now, boolean giveUp) {
            int detail = record.exitStatus() & 0xFF;
            int id = (record.exitStatus() >>> 8) & 0xFFFF;
            int hash = (int) record.nanos();
            long key = ((long) id << 32) | (hash & 0xFFFFFFFFL);
            ThreadLocalHolders.Holder known = holders.get(key);
            if (known != null) {
                return known;
            }
            String[] answer = null;
            if (id != 0 && !giveUp) {
                if (now - holderWindowStart >= HOLDER_WINDOW_MILLIS) {
                    holderWindowStart = now;
                    holderSpentNanos = 0L;
                }
                long left = HOLDER_BUDGET_NANOS - holderSpentNanos;
                if (left <= 0L) {
                    return null;
                }
                long started = System.nanoTime();
                answer = threadLocalHolders.holder(
                        generation,
                        id,
                        hash,
                        claim.packages().toArray(new String[0]),
                        ThreadLocalHolders.HOLDER_CLASSES.toArray(new String[0]),
                        left);
                holderSpentNanos += System.nanoTime() - started;
                if (answer == null) {
                    return null;
                }
            }
            ThreadLocalHolders.Holder holder = ThreadLocalHolders.decide(answer, target, detail);
            if (id != 0 && (answer != null || giveUp)) {
                holders.put(key, holder);
            }
            if (holder.excludedBy() != null && id != 0) {
                // Skipped by the bridge from now on, so it never takes an application's thread local's place.
                threadLocalHolders.exclude(generation, id, hash);
            }
            return holder;
        }

        private void observeThreadLocal(
                SideEffectRecord record, SideEffectsCatalog.Sensor sensor, ThreadLocalHolders.Holder holder) {
            if (holder.excludedBy() != null) {
                excludedHolders.merge(holder.excludedBy(), record.count(), Long::sum);
                return;
            }
            store.add(new SideEffectsStore.Observation(
                    record,
                    sensor.id(),
                    holder.kind(),
                    holder.target(),
                    null,
                    null,
                    normalizer.threadFamily(string(record.threadName())),
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
            String normalized = target == null ? "(unknown)" : normalizer.networkTarget(target);
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
