package io.github.jdubois.bootui.engine.inventory;

import io.github.jdubois.bootui.core.dto.CodeInventoryAgentReport;
import io.github.jdubois.bootui.core.dto.CodeInventoryChangeCountsDto;
import io.github.jdubois.bootui.core.dto.CodeInventoryChangesReport;
import io.github.jdubois.bootui.core.dto.CodeInventoryClassDto;
import io.github.jdubois.bootui.core.dto.CodeInventoryDependenciesReport;
import io.github.jdubois.bootui.core.dto.CodeInventoryDependencyCountsDto;
import io.github.jdubois.bootui.core.dto.CodeInventoryDependencyDto;
import io.github.jdubois.bootui.core.dto.CodeInventoryMethodCountsDto;
import io.github.jdubois.bootui.core.dto.CodeInventoryMethodDto;
import io.github.jdubois.bootui.core.dto.CodeInventoryMethodsReport;
import io.github.jdubois.bootui.core.dto.CodeInventoryPackageDto;
import io.github.jdubois.bootui.core.dto.CodeInventoryReport;
import io.github.jdubois.bootui.core.dto.CodeInventoryRunDto;
import io.github.jdubois.bootui.core.dto.CodeInventoryScanDto;
import io.github.jdubois.bootui.core.dto.DependencyDto;
import io.github.jdubois.bootui.core.dto.PageMetadata;
import io.github.jdubois.bootui.engine.inventory.ClassFileHasher.MethodHash;
import io.github.jdubois.bootui.engine.inventory.ClassScanner.ScannedClass;
import io.github.jdubois.bootui.engine.inventory.CodeInventoryHistory.KeptRun;
import io.github.jdubois.bootui.engine.inventory.InventoryRecords.First;
import io.github.jdubois.bootui.engine.javaagent.AgentBridgeAccess;
import io.github.jdubois.bootui.engine.javaagent.AgentClaim;
import io.github.jdubois.bootui.engine.javaagent.JavaAgentService;
import io.github.jdubois.bootui.engine.support.PagedList;
import io.github.jdubois.bootui.engine.vulnerabilities.DependencyInventory;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Code Inventory for one application ({@code docs/PLAN-v2.md} §5.15, M5-3), shared by every adapter: for this run's
 * claim on the BootUI agent, which application methods executed, which changed since the previous run, and which
 * dependencies loaded classes.
 *
 * <p>Per run, {@link #start()} starts the {@link AgentRecordDrainer} for the claim and scans the application's class
 * files off the calling thread ({@link ClassScanner}), comparing their method hashes with the previous run's kept in the
 * {@link CodeInventoryHistory}. A read drains the agent's records once more, then joins three sources: the scan, which
 * gives the denominator and the change set; the bridge's hit flags, which say exactly which tracked methods executed;
 * and the drained first-hit and class-load records, which add each one's first request, route, and time.
 *
 * <p>A method counts as tracked, executed or never executed, only when the agent instrumented it in this run (the
 * bridge's per-run mark), or when its class did not load in this run at all, so none of its methods could have run. A
 * method on disk the agent could not have seen is <b>not tracked</b>, with its reason, and never counted as executed or
 * never executed: a static initializer, an abstract method, a {@code $}-prefixed name, a synthetic class, a class the
 * agent excludes by name (the bridge's own list), a class whose transformation failed or that ran past the agent's
 * method limit, or a method its loaded class lacks. A method whose class the agent instrumented only after it loaded is
 * not tracked unless it executed since: it ran before instrumentation, unseen. A key that executed but has no class
 * file in the scanned roots is <b>generated</b>. Methods called before BootUI claimed the agent are not seen.
 */
public final class CodeInventoryService implements AutoCloseable {

    public static final String EXECUTED = "EXECUTED";
    public static final String NEVER_EXECUTED = "NEVER_EXECUTED";
    public static final String NOT_TRACKED = "NOT_TRACKED";
    public static final String GENERATED = "GENERATED";

    public static final String LOADED = "LOADED";
    public static final String LOADED_EARLIER = "LOADED_EARLIER";
    public static final String NOT_LOADED = "NOT_LOADED";

    public static final String STARTUP = "STARTUP";
    public static final String AFTER_STARTUP = "AFTER_STARTUP";

    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";

    static final String STATIC_INITIALIZER = "static initializer: never instrumented";
    static final String DOLLAR_PREFIXED = "name starts with $: never instrumented";
    static final String SYNTHETIC_CLASS = "synthetic or generated proxy class: never instrumented";
    static final String TRANSFORM_FAILED = "transform failed";
    static final String OVER_THE_LIMIT = "over the agent's method limit";
    static final String RAN_BEFORE_INSTRUMENTATION = "ran before instrumentation: its class loaded before the agent"
            + " instrumented it, so earlier calls were not seen";
    static final String NOT_INSTRUMENTED = "not instrumented in this run: its loaded class lacks it, as when the class"
            + " file changed since the class loaded";
    static final String DISABLED = "the agent stopped recording for this run";
    static final String ABSTRACT = "abstract: no code to instrument";
    static final String EXCLUDED_CLASS = "never instrumented: BootUI's own, the JDK's, or a generated proxy class";
    static final String UNKNOWN_TRACKING = "unknown: the agent failed to instrument some classes or ran out of method"
            + " ids, so whether this class loaded uninstrumented cannot be told";

    /** Why changes are not compared yet, while the scan runs. */
    public static final String SCAN_RUNNING = "The scan of the application's class files is still running: changes"
            + " since the previous run are compared once it ends.";

    /** Why changes are not compared, after the scan failed; followed by its reason. */
    public static final String SCAN_FAILED = "The scan of the application's class files failed, so no change since"
            + " the previous run can be compared.";

    /** Always true, and always said. */
    public static final String NOT_SEEN_BEFORE_CLAIM = "Methods called before BootUI claimed the agent are not seen:"
            + " the main class's early work and anything that ran before the claim may read as never executed.";

    static final String NO_PREVIOUS_RUN = "No previous run of this application was kept in this JVM: change detection"
            + " starts with the next DevTools restart or Quarkus live reload.";

    static final String MIXED_RUNS = "Mixed runs: another application claimed the BootUI agent in this JVM since this"
            + " application's previous run, which is still the run compared with.";

    static final String NO_DECLARED = "The application's declared dependencies could not be read.";

    private static final Logger log = Logger.getLogger(CodeInventoryService.class.getName());

    /** How long one computed view is reused across reads. */
    static final long VIEW_TTL_NANOS = 1_000_000_000L;

    private final AgentBridgeAccess access;
    private final Supplier<AgentClaim> claims;
    private final Supplier<String> agentUnavailable;
    private final Supplier<ClassLoader> loader;
    private final Supplier<DependencyInventory> declared;
    private final Supplier<Long> readyAt;
    private final CodeInventorySettings settings;
    private final CodeInventoryHistory history;
    private final LongSupplier nanoTime;

    private final Object lock = new Object();
    private Run run;
    private boolean closed;
    private volatile Function<Set<String>, Map<String, String>> requestRoutes = ids -> Map.of();

    /** One view build at a time; it also guards the method-key index below, which only a build reads and grows. */
    private final Object buildLock = new Object();

    // Method keys by id: ids are stable for the agent's life, so the keys only grow. Guarded by buildLock.
    private String[] keys = new String[0];
    private final Map<String, Integer> ids = new HashMap<>();
    private final Set<String> classesWithIds = new HashSet<>();

    /**
     * @param access the bridge
     * @param claims this application's current claim, or a supplier of {@code null}
     * @param agentUnavailable why the inventory sensor does not record for this application, {@code null} when it does,
     *     such as {@link JavaAgentService#inventoryUnavailableReason()}
     * @param loader the application class loader whose class files are scanned
     * @param declared the application's declared dependencies, or {@code null}
     * @param readyAt when the application was ready, in epoch milliseconds, or {@code null} when unknown
     * @param settings the settings
     * @param history the run history, usually {@link CodeInventoryHistory#shared()}
     */
    public CodeInventoryService(
            AgentBridgeAccess access,
            Supplier<AgentClaim> claims,
            Supplier<String> agentUnavailable,
            Supplier<ClassLoader> loader,
            Supplier<DependencyInventory> declared,
            Supplier<Long> readyAt,
            CodeInventorySettings settings,
            CodeInventoryHistory history) {
        this(access, claims, agentUnavailable, loader, declared, readyAt, settings, history, System::nanoTime);
    }

    CodeInventoryService(
            AgentBridgeAccess access,
            Supplier<AgentClaim> claims,
            Supplier<String> agentUnavailable,
            Supplier<ClassLoader> loader,
            Supplier<DependencyInventory> declared,
            Supplier<Long> readyAt,
            CodeInventorySettings settings,
            CodeInventoryHistory history,
            LongSupplier nanoTime) {
        this.access = access == null ? AgentBridgeAccess.absent() : access;
        this.claims = claims == null ? () -> null : claims;
        this.agentUnavailable = agentUnavailable == null ? () -> null : agentUnavailable;
        this.loader = loader == null ? () -> Thread.currentThread().getContextClassLoader() : loader;
        this.declared = declared;
        this.readyAt = readyAt == null ? () -> null : readyAt;
        this.settings = settings == null ? CodeInventorySettings.defaults() : settings;
        this.history = history == null ? CodeInventoryHistory.shared() : history;
        this.nanoTime = nanoTime;
    }

    /**
     * Installs how a request id names its route, for first calls whose record carries the request alone, as when the
     * framework matched the route only after the handler started, such as {@link JournalRequestRoutes#of}.
     */
    public void setRequestRoutes(Function<Set<String>, Map<String, String>> requestRoutes) {
        this.requestRoutes = requestRoutes == null ? ids -> Map.of() : requestRoutes;
    }

    // ---- lifecycle -------------------------------------------------------------------------------------------------

    /**
     * Starts this run's drainer and disk scan, once the claim is armed and the adapter's engine is ready: the adapter
     * calls it when its context refreshed or Quarkus started, and a read calls it again. Idempotent per claim
     * generation; a claim refined with more packages since the scan scans again. Never throws.
     */
    public void start() {
        try {
            synchronized (lock) {
                if (closed) {
                    return;
                }
                AgentClaim claim = claims.get();
                if (claim == null || !claim.armed() || claim.generation() == null || !access.inventorySupported()) {
                    return;
                }
                if (run == null || run.generation != claim.generation()) {
                    if (run != null) {
                        run.close();
                    }
                    run = new Run(claim, access);
                    run.drainer.start();
                }
                List<String> packages = claim.claimedPackages();
                if (!packages.equals(run.scanPackages)) {
                    scan(run, packages);
                }
            }
        } catch (RuntimeException ex) {
            log.log(Level.WARNING, "BootUI could not start Code Inventory", ex);
        }
    }

    /** Stops this run's drainer and scan. Idempotent; the service starts nothing afterwards. */
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

    /** The drainer of the current run, or {@code null}; for tests. */
    AgentRecordDrainer drainer() {
        synchronized (lock) {
            return run == null ? null : run.drainer;
        }
    }

    /** The current run's scan thread, or {@code null}; for tests. */
    Thread scanThread() {
        synchronized (lock) {
            return run == null ? null : run.scanThread;
        }
    }

    /** Waits for the current run's scan, for tests. */
    void awaitScan() throws InterruptedException {
        Thread thread;
        synchronized (lock) {
            thread = run == null ? null : run.scanThread;
        }
        if (thread != null) {
            thread.join(60_000);
        }
    }

    /** Whether a previous run of {@code slot} before {@code generation} is kept, never throwing. */
    private boolean previousRunKept(String slot, long generation) {
        try {
            return history.unavailableReason() == null && history.previous(slot, generation) != null;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private void scan(Run current, List<String> packages) {
        current.scanPackages = List.copyOf(packages);
        current.scan = ScanState.running(current.scan, previousRunKept(current.claim.slot(), current.generation));
        String slot = current.claim.slot();
        long generation = current.generation;
        Thread thread = new Thread(
                () -> {
                    boolean previous = access.bootUiWork(true);
                    try {
                        ClassScanner.Result result = ClassScanner.scan(
                                loader.get(),
                                packages,
                                settings.maxClasses(),
                                settings.scanTimeout(),
                                history.cache(),
                                () -> current.closed);
                        String unavailable = history.unavailableReason();
                        KeptRun kept = unavailable == null ? history.previous(slot, generation) : null;
                        boolean mixed = unavailable == null && history.mixed(slot, generation);
                        boolean failed = ClassScanner.FAILED.equals(result.status());
                        // A failed scan is never compared: it would read as nothing changed.
                        CodeChanges changes = failed ? null : CodeChanges.diff(result, kept);
                        // Nor is a cancelled one kept: the run ended while it scanned, and a newer run may be kept.
                        if (unavailable == null && !failed && !current.closed) {
                            history.record(slot, KeptRun.of(generation, result));
                        }
                        String note = unavailable != null
                                ? unavailable
                                : kept == null ? NO_PREVIOUS_RUN : mixed ? MIXED_RUNS : null;
                        current.scan =
                                new ScanState(result.status(), result.reason(), result, changes, note, kept != null);
                    } catch (Throwable ex) {
                        // Never an absolute path or a message: the exception's type says enough, and stays local.
                        current.scan = new ScanState(
                                ClassScanner.FAILED,
                                "The scan failed: " + ex.getClass().getSimpleName() + ".",
                                null,
                                null,
                                null,
                                previousRunKept(slot, generation));
                        if (ex instanceof VirtualMachineError error) {
                            throw error;
                        }
                    } finally {
                        access.bootUiWork(previous);
                    }
                },
                "bootui-code-inventory-scan");
        thread.setDaemon(true);
        thread.setContextClassLoader(null);
        current.scanThread = thread;
        thread.start();
    }

    // ---- availability --------------------------------------------------------------------------------------------

    /**
     * Why Code Inventory is unavailable for this application, starting with
     * {@value JavaAgentService#INVENTORY_REQUIREMENT}, or {@code null} when the inventory sensor records this run.
     * Never throws.
     */
    public String unavailableReason() {
        try {
            String reason = agentUnavailable.get();
            if (reason != null) {
                return reason;
            }
            AgentClaim claim = claims.get();
            if (claim == null || !claim.armed()) {
                return JavaAgentService.INVENTORY_REQUIREMENT + ": this application has not claimed the agent.";
            }
            if (!access.inventorySupported()) {
                return JavaAgentService.INVENTORY_REQUIREMENT
                        + ": the attached agent's bridge has no inventory sensor.";
            }
            return null;
        } catch (RuntimeException ex) {
            return JavaAgentService.INVENTORY_REQUIREMENT + ".";
        }
    }

    // ---- reads ---------------------------------------------------------------------------------------------------

    /** The summary. */
    public CodeInventoryReport report() {
        String reason = unavailableReason();
        View view = reason == null ? view() : null;
        if (view == null) {
            return CodeInventoryReport.unavailable(reason == null ? unavailableNow() : reason);
        }
        return new CodeInventoryReport(
                true,
                null,
                view.run,
                view.scanDto(),
                view.counts,
                view.changeCounts,
                view.dependencies().counts(),
                view.limitations());
    }

    /** The changed and added methods, not executed first. */
    public CodeInventoryChangesReport changes(Integer offset, Integer limit) {
        String reason = unavailableReason();
        View view = reason == null ? view() : null;
        if (view == null) {
            return new CodeInventoryChangesReport(
                    false, reason == null ? unavailableNow() : reason, null, List.of(), emptyPage(limit));
        }
        PagedList.Result<CodeInventoryMethodDto> page = PagedList.from(view.changes, offset, limit);
        return new CodeInventoryChangesReport(true, null, view.changeCounts, page.items(), page.page());
    }

    /**
     * The methods matching {@code packageName} (the package or a parent of it), {@code className} (its binary name or
     * simple name), and {@code status} ({@code executed}, {@code never-executed}, {@code not-tracked}, or
     * {@code generated}); every filter is optional.
     */
    public CodeInventoryMethodsReport methods(
            String packageName, String className, String status, Integer offset, Integer limit) {
        String reason = unavailableReason();
        View view = reason == null ? view() : null;
        if (view == null) {
            return new CodeInventoryMethodsReport(
                    false,
                    reason == null ? unavailableNow() : reason,
                    List.of(),
                    List.of(),
                    List.of(),
                    emptyPage(limit));
        }
        String wantedStatus = statusFilter(status);
        String wantedPackage = blank(packageName) ? null : packageName.trim();
        String wantedClass = blank(className) ? null : className.trim();
        Predicate<CodeInventoryMethodDto> matches =
                method -> (wantedStatus == null || wantedStatus.equals(method.status()))
                        && (wantedPackage == null
                                || method.packageName().equals(wantedPackage)
                                || method.packageName().startsWith(wantedPackage + "."))
                        && (wantedClass == null
                                || method.className().equals(wantedClass)
                                || simpleName(method.className()).equals(wantedClass));
        List<CodeInventoryMethodDto> matched =
                view.methods.stream().filter(matches).toList();
        Map<String, int[]> packages = new TreeMap<>();
        Map<String, Set<String>> packageClasses = new HashMap<>();
        Map<String, int[]> classes = new TreeMap<>();
        for (CodeInventoryMethodDto method : matched) {
            count(packages.computeIfAbsent(method.packageName(), name -> new int[5]), method);
            packageClasses
                    .computeIfAbsent(method.packageName(), name -> new HashSet<>())
                    .add(method.className());
            if (wantedPackage != null || wantedClass != null) {
                count(classes.computeIfAbsent(method.className(), name -> new int[5]), method);
            }
        }
        List<CodeInventoryPackageDto> packageRows = new ArrayList<>();
        packages.forEach((name, counts) -> packageRows.add(new CodeInventoryPackageDto(
                name, packageClasses.get(name).size(), counts[0], counts[1], counts[2], counts[3])));
        List<CodeInventoryClassDto> classRows = new ArrayList<>();
        classes.forEach((name, counts) -> classRows.add(new CodeInventoryClassDto(
                packageOf(name), name, counts[0], counts[1], counts[2], counts[3], counts[4])));
        PagedList.Result<CodeInventoryMethodDto> page = PagedList.from(matched, offset, limit);
        return new CodeInventoryMethodsReport(true, null, packageRows, classRows, page.items(), page.page());
    }

    /** The dependency use, declared dependencies not loaded first; {@code status} filters by status when given. */
    public CodeInventoryDependenciesReport dependencies(String status, Integer offset, Integer limit) {
        String reason = unavailableReason();
        View view = reason == null ? view() : null;
        if (view == null) {
            return new CodeInventoryDependenciesReport(
                    false, reason == null ? unavailableNow() : reason, null, List.of(), emptyPage(limit));
        }
        Dependencies dependencies = view.dependencies();
        String wanted =
                blank(status) ? null : status.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        PagedList.Result<CodeInventoryDependencyDto> page = PagedList.from(
                dependencies.rows(), row -> wanted == null || wanted.equals(row.status()), offset, limit);
        return new CodeInventoryDependenciesReport(true, null, dependencies.counts(), page.items(), page.page());
    }

    /**
     * Code Inventory for agents: the summary, then at most {@code limit} rows of {@code query}: {@code changed} (the
     * default, not executed first), {@code never-executed}, {@code not-tracked}, {@code executed},
     * {@code dependencies}, or a package or class name.
     */
    public CodeInventoryAgentReport agentReport(String query, Integer limit) {
        CodeInventoryReport summary = report();
        int max = limit == null || limit <= 0 ? CodeInventoryAgentReport.DEFAULT_LIMIT : limit;
        String q = blank(query) ? "changed" : query.trim();
        String normalized = q.toLowerCase(Locale.ROOT);
        if (!summary.available()) {
            return new CodeInventoryAgentReport(summary, normalized, query, List.of(), List.of(), 0, 0);
        }
        View view = view();
        if (view == null) {
            return new CodeInventoryAgentReport(summary, normalized, query, List.of(), List.of(), 0, 0);
        }
        if ("dependencies".equals(normalized)) {
            List<CodeInventoryDependencyDto> rows = view.dependencies().rows();
            List<CodeInventoryDependencyDto> page = rows.subList(0, Math.min(max, rows.size()));
            return new CodeInventoryAgentReport(
                    summary, "dependencies", query, List.of(), page, rows.size(), rows.size() - page.size());
        }
        List<CodeInventoryMethodDto> rows;
        String viewName;
        switch (normalized) {
            case "changed" -> {
                rows = view.changes;
                viewName = "changed";
            }
            case "never-executed", "executed", "not-tracked", "generated" -> {
                String wanted = statusFilter(normalized);
                rows = view.methods.stream()
                        .filter(method -> method.status().equals(wanted))
                        .toList();
                viewName = normalized;
            }
            default -> {
                rows = view.methods.stream()
                        .filter(method -> method.className().equals(q)
                                || simpleName(method.className()).equals(q)
                                || method.packageName().equals(q)
                                || method.packageName().startsWith(q + ".")
                                || method.key().startsWith(q + "#"))
                        .toList();
                viewName = "package";
            }
        }
        List<CodeInventoryMethodDto> page = rows.subList(0, Math.min(max, rows.size()));
        return new CodeInventoryAgentReport(
                summary, viewName, query, page, List.of(), rows.size(), rows.size() - page.size());
    }

    /**
     * What {@code changed-code-not-executed} reads: per class, its changed and added methods with their status, and the
     * routes known to have executed its methods, with the scan's status; with the reason, and nothing else, when the
     * sensor does not record this run.
     */
    public ChangedCode changedCode() {
        String reason = unavailableReason();
        if (reason != null) {
            return new ChangedCode(reason, false, null, List.of(), 0L, null, null);
        }
        View view = view();
        if (view == null) {
            return new ChangedCode(unavailableNow(), false, null, List.of(), 0L, null, null);
        }
        Map<String, List<CodeInventoryMethodDto>> byClass = new LinkedHashMap<>();
        for (CodeInventoryMethodDto method : view.changes) {
            byClass.computeIfAbsent(method.className(), name -> new ArrayList<>())
                    .add(method);
        }
        Map<String, Set<String>> routes = new HashMap<>();
        for (CodeInventoryMethodDto method : view.methods) {
            if (method.firstRoute() != null && byClass.containsKey(method.className())) {
                routes.computeIfAbsent(method.className(), name -> new LinkedHashSet<>())
                        .add(method.firstRoute());
            }
        }
        List<ChangedClass> classes = new ArrayList<>();
        long fingerprint = view.run.generation() * 31 + Objects.hashCode(view.scan.status());
        for (Map.Entry<String, List<CodeInventoryMethodDto>> entry : byClass.entrySet()) {
            List<String> classRoutes = new ArrayList<>(routes.getOrDefault(entry.getKey(), Set.of()));
            classes.add(new ChangedClass(entry.getKey(), entry.getValue(), classRoutes));
            for (CodeInventoryMethodDto method : entry.getValue()) {
                fingerprint = fingerprint * 31
                        + method.key().hashCode() * 7L
                        + method.status().hashCode();
            }
        }
        return new ChangedCode(
                null,
                view.changeCounts.previousRun(),
                view.changeCounts.note(),
                classes,
                fingerprint,
                view.scan.status(),
                view.scan.reason());
    }

    /**
     * A cheap number that changes whenever {@link #changedCode()} may answer something else: this application's
     * availability, the run, the bridge's flags and tracking ({@code CodeInventory.version}), the scan's state, the run
     * history, and the drained records and resolved routes. Unlike {@link #changedCode()}, it builds no view.
     */
    public long changesFingerprint() {
        try {
            String reason = unavailableReason();
            if (reason != null) {
                return reason.hashCode();
            }
            Run current;
            synchronized (lock) {
                current = run;
            }
            if (current == null) {
                return 1L;
            }
            long fingerprint = current.generation;
            fingerprint = fingerprint * 31 + access.inventoryVersion();
            fingerprint = fingerprint * 31 + System.identityHashCode(current.scan);
            fingerprint = fingerprint * 31 + history.version();
            fingerprint = fingerprint * 31 + current.records.version();
            return fingerprint;
        } catch (RuntimeException ex) {
            return 0L;
        }
    }

    /**
     * What {@code changed-code-not-executed} reads.
     *
     * @param unavailableReason why the sensor does not record this run, or {@code null}
     * @param previousRun whether there is a previous run to compare with
     * @param note why there is no comparison, or what limits it
     * @param classes the classes with changed or added methods
     * @param fingerprint changes whenever any of it does
     * @param scanStatus the scan's status, {@link #PENDING}, {@link #RUNNING}, {@link ClassScanner#COMPLETE},
     *     {@link ClassScanner#PARTIAL}, or {@link ClassScanner#FAILED}, or {@code null} when unavailable
     * @param scanReason why the scan is partial or failed, or {@code null}
     */
    public record ChangedCode(
            String unavailableReason,
            boolean previousRun,
            String note,
            List<ChangedClass> classes,
            long fingerprint,
            String scanStatus,
            String scanReason) {

        public ChangedCode {
            classes = List.copyOf(classes);
        }

        /** Whether the scan has not compared this run with the previous one yet: it is pending or running. */
        public boolean scanInProgress() {
            return PENDING.equals(scanStatus) || RUNNING.equals(scanStatus);
        }

        /** Whether the scan failed, so nothing was compared. */
        public boolean scanFailed() {
            return ClassScanner.FAILED.equals(scanStatus);
        }
    }

    /**
     * One class's changed and added methods.
     *
     * @param className the class
     * @param methods its changed and added methods, with their status
     * @param routes the routes whose requests executed one of its methods first, when known
     */
    public record ChangedClass(String className, List<CodeInventoryMethodDto> methods, List<String> routes) {

        public ChangedClass {
            methods = List.copyOf(methods);
            routes = List.copyOf(routes);
        }
    }

    // ---- the view ------------------------------------------------------------------------------------------------

    private String unavailableNow() {
        return JavaAgentService.INVENTORY_REQUIREMENT + ": the inventory sensor has not recorded this run yet.";
    }

    /**
     * The current run's view: reused while fresh, else built, one build at a time. The build lock also guards the
     * method-key index, which only a build reads and grows; a reader waiting on it reuses the view the build before it
     * made, when still fresh.
     */
    private View view() {
        start();
        Run current;
        synchronized (lock) {
            current = run;
        }
        if (current == null) {
            return null;
        }
        View cached = current.view;
        if (fresh(cached, current, nanoTime.getAsLong())) {
            return cached;
        }
        synchronized (buildLock) {
            long now = nanoTime.getAsLong();
            cached = current.view;
            if (fresh(cached, current, now)) {
                return cached;
            }
            current.drainer.drainNow();
            Map<String, Object> snapshot = access.inventorySnapshot(current.generation);
            if (snapshot == null) {
                return null;
            }
            View view = build(current, snapshot, now);
            current.view = view;
            return view;
        }
    }

    private static boolean fresh(View cached, Run current, long now) {
        return cached != null && cached.scan == current.scan && now - cached.builtNanos < VIEW_TTL_NANOS;
    }

    /** What the bridge's snapshot says about the agent's tracking in this run, for {@link #status}. */
    private record Tracking(
            long[] executed,
            long[] late,
            long[] trackedThisRun,
            byte[] states,
            Set<String> failedClasses,
            Set<String> overLimitClasses,
            Set<String> classesThisRun,
            long overflow,
            long failures) {

        /** Whether the agent may have left a class with no method id uninstrumented without naming it. */
        boolean unattributed() {
            return overflow > 0 || failures > 0;
        }
    }

    /** A method's status, and why it is not tracked. */
    private record Status(String status, String reason) {

        static Status of(String status) {
            return new Status(status, null);
        }

        static Status notTracked(String reason) {
            return new Status(NOT_TRACKED, reason);
        }
    }

    /**
     * The status of {@code method} of {@code className}, whose id is {@code id} ({@code null} when the agent never gave
     * it one), against what the agent tracked in this run. A method counts as tracked, so executed or never executed,
     * only when the agent instrumented it in this run, or when its class did not load in this run at all: such a class
     * would be instrumented as it loads, so none of its methods ran yet. Every exclusion the agent applies is mirrored,
     * and a class the agent failed to instrument or that ran past its method limit is never counted.
     */
    private Status status(
            String className,
            MethodHash method,
            boolean syntheticClass,
            boolean excludedClass,
            Integer id,
            Tracking tracking) {
        if ("<clinit>".equals(method.name())) {
            return Status.notTracked(STATIC_INITIALIZER);
        }
        if (method.isAbstract()) {
            return Status.notTracked(ABSTRACT);
        }
        if (method.name().startsWith("$")) {
            return Status.notTracked(DOLLAR_PREFIXED);
        }
        if (syntheticClass) {
            return Status.notTracked(SYNTHETIC_CLASS);
        }
        if (excludedClass) {
            return Status.notTracked(EXCLUDED_CLASS);
        }
        if (id != null && bit(tracking.executed(), id)) {
            // Only live advice sets the flag: it ran, in this run.
            return Status.of(EXECUTED);
        }
        boolean failed = tracking.failedClasses().contains(className)
                || (id != null && id < tracking.states().length && tracking.states()[id] == 2);
        if (failed) {
            return Status.notTracked(TRANSFORM_FAILED);
        }
        if (id == null && tracking.overLimitClasses().contains(className)) {
            return Status.notTracked(OVER_THE_LIMIT);
        }
        if (id != null && bit(tracking.trackedThisRun(), id)) {
            return bit(tracking.late(), id) ? Status.notTracked(RAN_BEFORE_INSTRUMENTATION) : Status.of(NEVER_EXECUTED);
        }
        if (tracking.classesThisRun().contains(className)) {
            // Its class was instrumented in this run without it: the class file changed since the class loaded, or the
            // method ran past the limit.
            return Status.notTracked(id == null && tracking.overflow() > 0 ? OVER_THE_LIMIT : NOT_INSTRUMENTED);
        }
        if (!classesWithIds.contains(className) && tracking.unattributed()) {
            // No method of the class ever got an id, and the agent left some class uninstrumented without naming it.
            return Status.notTracked(UNKNOWN_TRACKING);
        }
        // Its class did not load in this run: the agent instruments it as it loads, so none of its methods ran yet.
        return Status.of(NEVER_EXECUTED);
    }

    private View build(Run current, Map<String, Object> snapshot, long now) {
        int methodCount = intValue(snapshot.get("methods"));
        boolean disabled = Boolean.TRUE.equals(snapshot.get("disabled"));
        Map<String, Object> counters = AgentStatus.inventory(access.status());
        refreshKeys(methodCount);
        long[] trackedThisRun = longs(snapshot.get("trackedThisRun"));
        Set<String> classesThisRun = new HashSet<>();
        for (int id = 0; id < Math.min(methodCount, keys.length); id++) {
            String key = keys[id];
            if (key != null && bit(trackedThisRun, id)) {
                classesThisRun.add(classOf(key));
            }
        }
        Tracking tracking = new Tracking(
                longs(snapshot.get("executed")),
                longs(snapshot.get("late")),
                trackedThisRun,
                snapshot.get("tracking") instanceof byte[] bytes ? bytes : new byte[0],
                names(snapshot.get("failedClasses")),
                names(snapshot.get("overLimitClasses")),
                classesThisRun,
                longValue(snapshot.get("methodOverflow")),
                longValue(snapshot.get("transformFailures")));
        long overflow = tracking.overflow();
        long[] executed = tracking.executed();

        ScanState scan = current.scan;
        ClassScanner.Result result = scan.result();
        List<CodeInventoryMethodDto> methods = new ArrayList<>();
        Set<String> diskKeys = new HashSet<>();
        Set<String> packages = new HashSet<>();
        int[] tally = new int[4];
        Map<String, String> changes =
                scan.changes() == null ? Map.of() : scan.changes().kinds();
        if (result != null) {
            for (ScannedClass scanned : result.classes().values()) {
                String className = scanned.className();
                packages.add(packageOf(className));
                boolean syntheticClass = scanned.hashes().synthetic() || className.contains("$$");
                // The agent's own list, read from its bridge, so the two never disagree.
                boolean excludedClass = access.excluded(className);
                for (MethodHash method : ClassScanner.inventoried(scanned.hashes())) {
                    String key = method.key(className);
                    diskKeys.add(key);
                    Integer id = ids.get(key);
                    Status status = status(className, method, syntheticClass, excludedClass, id, tracking);
                    if (disabled && NEVER_EXECUTED.equals(status.status())) {
                        status = Status.notTracked(DISABLED);
                    }
                    First first = id == null ? null : current.records.firstHit(id);
                    methods.add(
                            method(key, className, method, status.status(), status.reason(), changes.get(key), first));
                    tally(tally, status.status());
                }
            }
        }
        int generated = 0;
        if (result != null) {
            for (int id = 0; id < Math.min(methodCount, keys.length); id++) {
                String key = keys[id];
                if (key == null || !bit(executed, id) || diskKeys.contains(key)) {
                    continue;
                }
                String className = classOf(key);
                boolean covered = result.complete()
                        ? ClassScanner.inPackages(className, result.packages())
                        : result.classes().containsKey(className);
                if (!covered) {
                    continue;
                }
                int hash = key.indexOf('#');
                int paren = key.indexOf('(', hash);
                String name = paren < 0 ? key.substring(hash + 1) : key.substring(hash + 1, paren);
                String descriptor = paren < 0 ? "" : key.substring(paren);
                First first = current.records.firstHit(id);
                methods.add(new CodeInventoryMethodDto(
                        key,
                        packageOf(className),
                        className,
                        name,
                        descriptor,
                        GENERATED,
                        null,
                        null,
                        first == null ? null : first.requestId(),
                        first == null ? null : first.route(),
                        first == null ? null : first.epochMillis()));
                generated++;
            }
        }
        methods = withRoutes(current, methods);
        CodeInventoryMethodCountsDto counts = new CodeInventoryMethodCountsDto(
                packages.size(),
                result == null ? 0 : result.classes().size(),
                diskKeys.size(),
                tally[0] + tally[1],
                tally[0],
                tally[1],
                tally[2],
                generated);

        Map<String, CodeInventoryMethodDto> byKey = new HashMap<>();
        for (CodeInventoryMethodDto method : methods) {
            byKey.put(method.key(), method);
        }
        List<CodeInventoryMethodDto> changed = new ArrayList<>();
        int changedCount = 0;
        int addedCount = 0;
        int changedExecuted = 0;
        int changedNotExecuted = 0;
        for (Map.Entry<String, String> entry : changes.entrySet()) {
            CodeInventoryMethodDto method = byKey.get(entry.getKey());
            if (method == null) {
                continue;
            }
            changed.add(method);
            if (CodeChanges.CHANGED.equals(entry.getValue())) {
                changedCount++;
            } else {
                addedCount++;
            }
            if (EXECUTED.equals(method.status())) {
                changedExecuted++;
            } else if (NEVER_EXECUTED.equals(method.status())) {
                changedNotExecuted++;
            }
        }
        changed.sort(Comparator.comparing((CodeInventoryMethodDto method) -> EXECUTED.equals(method.status()))
                .thenComparing(CodeInventoryMethodDto::className)
                .thenComparing(CodeInventoryMethodDto::name)
                .thenComparing(CodeInventoryMethodDto::descriptor));
        CodeChanges diff = scan.changes();
        boolean previousRun = diff != null ? diff.previousRun() : scan.previousRun();
        CodeInventoryChangeCountsDto changeCounts = new CodeInventoryChangeCountsDto(
                previousRun,
                changesNote(scan),
                changedCount,
                addedCount,
                diff == null || !diff.previousRun() || diff.removed() < 0 ? null : diff.removed(),
                changedExecuted,
                changedNotExecuted,
                diff != null && diff.partial(),
                scan.status());

        AgentClaim claim = current.claim;
        CodeInventoryRunDto runDto = new CodeInventoryRunDto(
                current.generation,
                claim.application(),
                claim.mode(),
                claim.armedAt(),
                safeReadyAt(),
                current.scanPackages == null ? claim.claimedPackages() : current.scanPackages);

        List<String> limitations = new ArrayList<>();
        limitations.add(NOT_SEEN_BEFORE_CLAIM);
        if (scan.reason() != null && !ClassScanner.COMPLETE.equals(scan.status())) {
            limitations.add(scan.reason());
        }
        long dropped = longValue(counters.get("ringDropped")) + longValue(counters.get("ringLost"));
        if (dropped > 0) {
            limitations.add("Incomplete: " + dropped + " first-call or class-load records were dropped, so some"
                    + " first requests and routes are missing; which methods executed is still exact.");
        }
        if (overflow > 0) {
            limitations.add(overflow + " methods were left uninstrumented past the agent's method limit.");
        }
        if (tracking.failures() > 0) {
            limitations.add("The agent failed to instrument some classes: their methods are not tracked.");
        }
        if (disabled) {
            String disabledReason = Objects.toString(counters.get("disabledReason"), null);
            limitations.add("The agent stopped recording for this run"
                    + (disabledReason == null ? "." : ": " + disabledReason));
        }
        if (generated > 0) {
            limitations.add(generated + " executed methods have no class file in the scanned roots, such as generated"
                    + " classes: they are counted apart, as generated.");
        }
        return new View(
                now,
                scan,
                runDto,
                counts,
                changeCounts,
                List.copyOf(methods),
                List.copyOf(changed),
                limitations,
                current);
    }

    /** What the changes say instead of a comparison: the scan in progress or failed, else the scan's note. */
    private static String changesNote(ScanState scan) {
        if (scan.changes() == null && scan.inProgress()) {
            return SCAN_RUNNING;
        }
        if (scan.failed()) {
            return SCAN_FAILED + (scan.reason() == null ? "" : " " + scan.reason());
        }
        return scan.note();
    }

    /** The methods, with the route of each first request whose record named none, from {@link #requestRoutes}. */
    private List<CodeInventoryMethodDto> withRoutes(Run current, List<CodeInventoryMethodDto> methods) {
        Set<String> missing = new HashSet<>();
        for (CodeInventoryMethodDto method : methods) {
            if (method.firstRequestId() != null && method.firstRoute() == null) {
                missing.add(method.firstRequestId());
            }
        }
        Map<String, String> routes = routesOf(current, missing);
        if (routes.isEmpty()) {
            return methods;
        }
        List<CodeInventoryMethodDto> resolved = new ArrayList<>(methods.size());
        for (CodeInventoryMethodDto method : methods) {
            String route = method.firstRoute() == null && method.firstRequestId() != null
                    ? routes.get(method.firstRequestId())
                    : null;
            resolved.add(
                    route == null
                            ? method
                            : new CodeInventoryMethodDto(
                                    method.key(),
                                    method.packageName(),
                                    method.className(),
                                    method.name(),
                                    method.descriptor(),
                                    method.status(),
                                    method.notTrackedReason(),
                                    method.change(),
                                    method.firstRequestId(),
                                    route,
                                    method.firstHitEpochMillis()));
        }
        return resolved;
    }

    /** The routes of {@code requestIds}: those the run resolved before, and the others looked up once more. */
    private Map<String, String> routesOf(Run current, Set<String> requestIds) {
        if (requestIds.isEmpty()) {
            return Map.of();
        }
        try {
            return current.records.routes(requestIds, requestRoutes);
        } catch (RuntimeException ex) {
            return Map.of();
        }
    }

    private static CodeInventoryMethodDto method(
            String key, String className, MethodHash method, String status, String reason, String change, First first) {
        return new CodeInventoryMethodDto(
                key,
                packageOf(className),
                className,
                method.name(),
                method.descriptor(),
                status,
                reason,
                change,
                first == null ? null : first.requestId(),
                first == null ? null : first.route(),
                first == null ? null : first.epochMillis());
    }

    /** Reads the method keys the bridge assigned since the last build; called under the build lock only. */
    private void refreshKeys(int methodCount) {
        if (methodCount <= keys.length) {
            return;
        }
        String[] added = access.methodKeys(keys.length, methodCount - keys.length);
        int from = keys.length;
        String[] grown = Arrays.copyOf(keys, from + added.length);
        for (int i = 0; i < added.length; i++) {
            String key = added[i];
            grown[from + i] = key;
            if (key != null) {
                ids.put(key, from + i);
                int hash = key.indexOf('#');
                if (hash > 0) {
                    classesWithIds.add(key.substring(0, hash));
                }
            }
        }
        keys = grown;
    }

    private static String classOf(String key) {
        return key.substring(0, Math.max(0, key.indexOf('#')));
    }

    private static long[] longs(Object value) {
        return value instanceof long[] bits ? bits : new long[0];
    }

    private static Set<String> names(Object value) {
        return value instanceof String[] names ? new HashSet<>(Arrays.asList(names)) : Set.of();
    }

    // ---- dependencies --------------------------------------------------------------------------------------------

    private record Dependencies(List<CodeInventoryDependencyDto> rows, CodeInventoryDependencyCountsDto counts) {}

    private Dependencies dependencies(Run current, ClassScanner.Result scan) {
        Long ready = safeReadyAt();
        Set<String> applicationRoots = new HashSet<>();
        if (scan != null) {
            for (String root : scan.roots()) {
                applicationRoots.add(canonical(root));
            }
        }
        DependencyRead declaredRead = current.declared(this);
        List<DependencyDto> declaredDependencies = declaredRead.dependencies();
        List<Map<String, Object>> sources = access.codeSources();
        List<Source> seen = new ArrayList<>();
        for (Map<String, Object> source : sources) {
            String location = Objects.toString(source.get("location"), null);
            if (location == null || applicationRoots.contains(canonical(location))) {
                continue;
            }
            CodeSources.Identity identity = CodeSources.of(location);
            if (identity.bootUi()) {
                continue;
            }
            seen.add(new Source(intValue(source.get("id")), identity, source));
        }
        List<CodeInventoryDependencyDto> rows = new ArrayList<>();
        Set<Source> matched = new HashSet<>();
        for (DependencyDto dependency : declaredDependencies) {
            if (CodeSources.BOOTUI_GROUP.equals(dependency.groupId())) {
                continue;
            }
            Source source = match(dependency, seen);
            if (source != null) {
                matched.add(source);
            }
            rows.add(row(current, dependency, source, ready));
        }
        for (Source source : seen) {
            if (!matched.contains(source) && !source.identity().directory()) {
                rows.add(row(current, null, source, ready));
            }
        }
        rows = withLoadRoutes(current, rows);
        rows.sort(Comparator.comparing((CodeInventoryDependencyDto row) -> !row.declared())
                .thenComparing(row -> switch (row.status()) {
                    case NOT_LOADED -> 0;
                    case LOADED_EARLIER -> 1;
                    default -> 2;
                })
                .thenComparing(row -> Objects.toString(row.artifactId(), Objects.toString(row.jar(), ""))));
        int declaredCount = 0;
        int loaded = 0;
        int earlier = 0;
        int notLoaded = 0;
        int undeclared = 0;
        for (CodeInventoryDependencyDto row : rows) {
            if (row.declared()) {
                declaredCount++;
                if (NOT_LOADED.equals(row.status())) {
                    notLoaded++;
                }
            } else {
                undeclared++;
            }
            if (LOADED.equals(row.status())) {
                loaded++;
            } else if (LOADED_EARLIER.equals(row.status())) {
                earlier++;
            }
        }
        return new Dependencies(
                List.copyOf(rows),
                new CodeInventoryDependencyCountsDto(
                        declaredCount, loaded, earlier, notLoaded, undeclared, declaredRead.reason()));
    }

    private record Source(int id, CodeSources.Identity identity, Map<String, Object> counters) {}

    private List<CodeInventoryDependencyDto> withLoadRoutes(Run current, List<CodeInventoryDependencyDto> rows) {
        Set<String> missing = new HashSet<>();
        for (CodeInventoryDependencyDto row : rows) {
            if (row.firstRequestId() != null && row.firstRoute() == null) {
                missing.add(row.firstRequestId());
            }
        }
        Map<String, String> routes = routesOf(current, missing);
        if (routes.isEmpty()) {
            return rows;
        }
        List<CodeInventoryDependencyDto> resolved = new ArrayList<>(rows.size());
        for (CodeInventoryDependencyDto row : rows) {
            String route =
                    row.firstRoute() == null && row.firstRequestId() != null ? routes.get(row.firstRequestId()) : null;
            resolved.add(
                    route == null
                            ? row
                            : new CodeInventoryDependencyDto(
                                    row.jar(),
                                    row.groupId(),
                                    row.artifactId(),
                                    row.version(),
                                    row.declared(),
                                    row.status(),
                                    row.classesLoaded(),
                                    row.classesLoadedTotal(),
                                    row.loadedAt(),
                                    row.firstLoadEpochMillis(),
                                    route,
                                    row.firstRequestId()));
        }
        return resolved;
    }

    private static Source match(DependencyDto dependency, List<Source> seen) {
        String coordinate = dependency.groupId() + ":" + dependency.artifactId();
        for (Source source : seen) {
            CodeSources.Identity identity = source.identity();
            if (dependency.groupId() != null
                    && dependency.groupId().equals(identity.groupId())
                    && Objects.equals(dependency.artifactId(), identity.artifactId())) {
                return source;
            }
        }
        for (Source source : seen) {
            if (source.identity().groupId() == null
                    && source.identity().coordinates().contains(coordinate)) {
                return source;
            }
        }
        for (Source source : seen) {
            CodeSources.Identity identity = source.identity();
            if (identity.groupId() == null
                    && CodeSources.fileNameMatches(
                            identity.fileName(), dependency.groupId(), dependency.artifactId(), dependency.version())) {
                return source;
            }
        }
        return null;
    }

    private CodeInventoryDependencyDto row(Run current, DependencyDto dependency, Source source, Long ready) {
        long loadedThisRun = 0;
        long total = 0;
        Long firstLoad = null;
        First first = null;
        if (source != null) {
            loadedThisRun = longValue(source.counters().get("loaded"));
            total = longValue(source.counters().get("total"))
                    + longValue(source.counters().get("beforeClaim"));
            Object firstMillis = source.counters().get("firstLoadMillis");
            firstLoad = firstMillis instanceof Number number ? number.longValue() : null;
            first = current.records.firstLoad(source.id());
        }
        String status = loadedThisRun > 0 ? LOADED : total > 0 ? LOADED_EARLIER : NOT_LOADED;
        String loadedAt = null;
        if (loadedThisRun > 0 && firstLoad != null && ready != null) {
            loadedAt = firstLoad <= ready ? STARTUP : AFTER_STARTUP;
        }
        CodeSources.Identity identity = source == null ? null : source.identity();
        return new CodeInventoryDependencyDto(
                identity == null ? null : identity.fileName(),
                dependency != null ? dependency.groupId() : identity.groupId(),
                dependency != null ? dependency.artifactId() : identity.artifactId(),
                dependency != null ? dependency.version() : identity.version(),
                dependency != null,
                status,
                loadedThisRun,
                total,
                loadedAt,
                loadedThisRun > 0 ? firstLoad : null,
                loadedThisRun > 0 && first != null ? first.route() : null,
                loadedThisRun > 0 && first != null ? first.requestId() : null);
    }

    private record DependencyRead(List<DependencyDto> dependencies, String reason) {}

    private DependencyRead readDeclared() {
        if (declared == null) {
            return new DependencyRead(List.of(), NO_DECLARED);
        }
        boolean previous = access.bootUiWork(true);
        try {
            DependencyInventory inventory = declared.get();
            return inventory == null
                    ? new DependencyRead(List.of(), NO_DECLARED)
                    : new DependencyRead(inventory.dependencies(), null);
        } catch (RuntimeException ex) {
            return new DependencyRead(
                    List.of(), NO_DECLARED + " " + ex.getClass().getSimpleName() + ".");
        } finally {
            access.bootUiWork(previous);
        }
    }

    /** A location comparable across {@code file:} URLs, paths, and {@code jar:} wrappers. */
    static String canonical(String location) {
        String value = location;
        if (value.startsWith("jar:")) {
            value = value.substring(4);
        }
        if (value.endsWith("!/")) {
            value = value.substring(0, value.length() - 2);
        }
        if (value.startsWith("file:")) {
            try {
                return Path.of(URI.create(value)).toAbsolutePath().normalize().toString();
            } catch (RuntimeException ex) {
                return value;
            }
        }
        return value;
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private Long safeReadyAt() {
        try {
            return readyAt.get();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static void tally(int[] tally, String status) {
        switch (status) {
            case EXECUTED -> tally[0]++;
            case NEVER_EXECUTED -> tally[1]++;
            default -> tally[2]++;
        }
    }

    private static void count(int[] counts, CodeInventoryMethodDto method) {
        counts[0]++;
        switch (method.status()) {
            case EXECUTED -> counts[1]++;
            case NEVER_EXECUTED -> counts[2]++;
            case NOT_TRACKED -> counts[3]++;
            default -> {
                // Generated methods count in the total only.
            }
        }
        if (method.change() != null) {
            counts[4]++;
        }
    }

    private static String statusFilter(String status) {
        if (blank(status)) {
            return null;
        }
        return switch (status.trim().toLowerCase(Locale.ROOT)) {
            case "executed" -> EXECUTED;
            case "never-executed", "never_executed" -> NEVER_EXECUTED;
            case "not-tracked", "not_tracked" -> NOT_TRACKED;
            case "generated" -> GENERATED;
            default -> status.trim().toUpperCase(Locale.ROOT);
        };
    }

    private static boolean bit(long[] bits, int id) {
        int word = id >>> 6;
        return word < bits.length && (bits[word] & (1L << (id & 63))) != 0;
    }

    static String packageOf(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? "" : className.substring(0, dot);
    }

    static String simpleName(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static PageMetadata emptyPage(Integer limit) {
        return new PageMetadata(0, 0, 0, limit == null ? 0 : Math.max(0, limit), 0, false);
    }

    /** The bridge's inventory counters, from its status. */
    private static final class AgentStatus {

        static Map<String, Object> inventory(Map<String, Object> status) {
            Object value = status == null ? null : status.get("inventory");
            Map<String, Object> map = new LinkedHashMap<>();
            if (value instanceof Map<?, ?> raw) {
                raw.forEach((key, item) -> map.put(String.valueOf(key), item));
            }
            return map;
        }
    }

    // ---- state ---------------------------------------------------------------------------------------------------

    /**
     * The disk scan's state for one run: {@code changes} is {@code null} until a scan that did not fail compared it with
     * the previous run; {@code previousRun} says whether a previous run is kept, known before the scan ends.
     */
    private record ScanState(
            String status,
            String reason,
            ClassScanner.Result result,
            CodeChanges changes,
            String note,
            boolean previousRun) {

        static ScanState running(ScanState previous, boolean previousRun) {
            // A rescan after a refine keeps the previous result until the new one is ready.
            if (previous != null && previous.result() != null) {
                return new ScanState(
                        RUNNING,
                        previous.reason(),
                        previous.result(),
                        previous.changes(),
                        previous.note(),
                        previous.previousRun() || previousRun);
            }
            return new ScanState(RUNNING, null, null, null, null, previousRun);
        }

        /** Whether the scan has not produced a result yet, or is producing a new one. */
        boolean inProgress() {
            return PENDING.equals(status) || RUNNING.equals(status);
        }

        boolean failed() {
            return ClassScanner.FAILED.equals(status);
        }
    }

    /** One run: its claim, records, drainer, and scan. */
    private static final class Run {

        final AgentClaim claim;
        final long generation;
        final InventoryRecords records;
        final AgentRecordDrainer drainer;
        volatile List<String> scanPackages;
        volatile ScanState scan = new ScanState(PENDING, null, null, null, null, false);
        volatile Thread scanThread;
        volatile View view;
        volatile boolean closed;
        private DependencyRead declared;

        Run(AgentClaim claim, AgentBridgeAccess access) {
            this.claim = claim;
            this.generation = claim.generation();
            this.records = new InventoryRecords(generation, access);
            this.drainer = new AgentRecordDrainer(claim, access, Map.of(AgentRecordDrainer.SENSOR_INVENTORY, records));
        }

        synchronized DependencyRead declared(CodeInventoryService service) {
            if (declared == null) {
                declared = service.readDeclared();
            }
            return declared;
        }

        void close() {
            closed = true;
            drainer.close();
        }
    }

    /** One computed view of a run, reused for {@link #VIEW_TTL_NANOS}. */
    private final class View {

        final long builtNanos;
        final ScanState scan;
        final CodeInventoryRunDto run;
        final CodeInventoryMethodCountsDto counts;
        final CodeInventoryChangeCountsDto changeCounts;
        final List<CodeInventoryMethodDto> methods;
        final List<CodeInventoryMethodDto> changes;
        private final List<String> limitations;
        private final Run owner;
        private Dependencies dependencies;

        View(
                long builtNanos,
                ScanState scan,
                CodeInventoryRunDto run,
                CodeInventoryMethodCountsDto counts,
                CodeInventoryChangeCountsDto changeCounts,
                List<CodeInventoryMethodDto> methods,
                List<CodeInventoryMethodDto> changes,
                List<String> limitations,
                Run owner) {
            this.builtNanos = builtNanos;
            this.scan = scan;
            this.run = run;
            this.counts = counts;
            this.changeCounts = changeCounts;
            this.methods = methods;
            this.changes = changes;
            this.limitations = limitations;
            this.owner = owner;
        }

        synchronized Dependencies dependencies() {
            if (dependencies == null) {
                dependencies = CodeInventoryService.this.dependencies(owner, scan.result());
            }
            return dependencies;
        }

        List<String> limitations() {
            List<String> all = new ArrayList<>(limitations);
            String declaredReason = dependencies().counts().declaredReason();
            if (declaredReason != null) {
                all.add(declaredReason);
            }
            return all;
        }

        CodeInventoryScanDto scanDto() {
            ClassScanner.Result result = scan.result();
            return new CodeInventoryScanDto(
                    scan.status(),
                    scan.reason(),
                    result == null ? 0 : result.roots().size(),
                    result == null ? 0 : result.classes().size(),
                    result == null ? 0 : result.reused(),
                    result == null ? 0 : result.skipped(),
                    result == null || RUNNING.equals(scan.status()) ? null : result.durationMillis());
        }
    }
}
