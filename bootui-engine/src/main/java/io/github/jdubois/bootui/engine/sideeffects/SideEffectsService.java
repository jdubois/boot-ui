package io.github.jdubois.bootui.engine.sideeffects;

import io.github.jdubois.bootui.core.dto.PageMetadata;
import io.github.jdubois.bootui.core.dto.SideEffectsAgentReport;
import io.github.jdubois.bootui.core.dto.SideEffectsReport;
import io.github.jdubois.bootui.core.dto.SideEffectsRowDto;
import io.github.jdubois.bootui.core.dto.SideEffectsSensorDto;
import io.github.jdubois.bootui.core.dto.SideEffectsSensorReport;
import io.github.jdubois.bootui.engine.codepaths.CodePathStamps;
import io.github.jdubois.bootui.engine.javaagent.AgentBridgeAccess;
import io.github.jdubois.bootui.engine.javaagent.AgentClaim;
import io.github.jdubois.bootui.engine.javaagent.AgentRecordDrainer;
import io.github.jdubois.bootui.engine.javaagent.JavaAgentService;
import io.github.jdubois.bootui.engine.journal.AgentEvidence;
import io.github.jdubois.bootui.engine.journal.JournalSourcePanels;
import io.github.jdubois.bootui.engine.model.HostOpen;
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
 * Side Effects for one application ({@code docs/PLAN-v2.md} §5.16, M5-5a), shared by every adapter: for this run's
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
            + " Runtime.exec and ProcessBuilder.startPipeline also reach, and its network: connects, datagram sends, and"
            + " the host names the JVM resolves. Files, environment, threads, blocking, and security sinks are not"
            + " available in this version.";

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

    static final String LIMITATION_VALUES = "A process row shows the command's file name only, never its arguments or"
            + " its environment, which can hold secrets; its exit status and lifetime come from Process.onExit, whose JDK"
            + " stage runs on the common ForkJoin pool before BootUI's own thread records the exit. On Windows, the JDK waits"
            + " for each watched live process on a reaper thread of its own, so up to 1,024 such threads.";

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

    private final AgentBridgeAccess access;
    private final Supplier<AgentClaim> claims;
    private final Supplier<String> unavailable;
    private final Function<String, JavaAgentService.SideEffectsCoverage> coverage;
    private final LongSupplier clock;
    private final SideEffectsNormalizer normalizer;
    private volatile Function<Set<String>, Map<String, String>> requestRoutes = ids -> Map.of();
    private volatile Function<Set<String>, Map<String, String>> executionLabels = ids -> Map.of();
    private volatile NetworkCapture networkCapture = NetworkCapture.NONE;
    private volatile Set<String> exporterEndpoints = Set.of();
    private final AgentEvidence evidence;
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
            if (run != null) {
                run.close();
                run = null;
            }
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
                all = current.store.rows(sensor.id(), read.requests(), codePathsShown(), captures);
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
                    for (SideEffectsRowDto row :
                            current.store.rows(sensor.id(), read.requests(), codePaths, captures)) {
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
                LIMITATION_SCOPE, LIMITATION_VALUES, LIMITATION_NETWORK, LIMITATION_CAPTURE, LIMITATION_ATTRIBUTION));
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
            }
        }
        return limitations;
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
            return strings.size() * STRING_BYTES + methods.size() * METHOD_BYTES;
        }

        long maxIndexBytes() {
            return MAX_STRINGS * STRING_BYTES + (long) store.maxRows() * METHOD_BYTES;
        }

        long records;
        long stale;
        long malformed;
        long bootUi;
        long clears;
        long cleared;
        long clearedAt = lastClearedAt;
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
                    store.add(network(record, sensor, target, outside, application));
                } else {
                    store.add(new SideEffectsStore.Observation(
                            record,
                            sensor.id(),
                            SideEffectsCatalog.kind(record.sensor(), record.kind()),
                            target == null ? "(unknown)" : normalizer.target(target),
                            application != null ? application : outside,
                            insideMethod(record.stamp()),
                            normalizer.threadFamily(string(record.threadName()))));
                }
                resolve(false);
                publish(this);
            }
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
