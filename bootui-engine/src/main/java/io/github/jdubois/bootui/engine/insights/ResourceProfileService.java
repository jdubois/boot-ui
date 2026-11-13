package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.core.dto.RuntimeResourceProfileDto;
import io.github.jdubois.bootui.core.dto.RuntimeResourceProfileFrameDto;
import io.github.jdubois.bootui.core.dto.RuntimeResourceProfileRouteDto;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalEntry;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.JournalSourcePanels;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.resources.JfrProfiler;
import io.github.jdubois.bootui.engine.resources.JfrProfiler.RequestSamples;
import io.github.jdubois.bootui.engine.resources.JfrProfiler.Snapshot;
import io.github.jdubois.bootui.engine.resources.JfrProfiler.State;
import io.github.jdubois.bootui.engine.sqltrace.RouteLabel;
import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Runtime Insights' <b>Profile resources</b> ({@code docs/PLAN-v2.md} §5.11, D17): starts and stops the JVM's {@link
 * JfrProfiler} session only when the developer asks, and reports its samples by route, naming each request's route from
 * the runtime journal. Reading its status starts nothing.
 *
 * <p>The per-route rows are HTTP Exchanges evidence ({@code docs/PLAN-v2.md} §8): while that panel is off, each read
 * keeps the session's sample totals and withholds the rows, saying why.</p>
 */
public final class ResourceProfileService {

    /** The route of requests the journal no longer retains. */
    static final String NOT_RETAINED = "Requests no longer retained";

    /** Why a session cannot run without the journal's request segments. */
    static final String RESOURCES_OFF = "Profile resources joins samples to the request segments the runtime journal's"
            + " resources source measures: add resources to bootui.runtime-journal.sources.";

    /** Why a completed session lists no route while the panel owning HTTP requests is off. */
    static final String ROUTES_HIDDEN = "The http-exchanges panel is disabled, so the samples are not listed by"
            + " route; the session's totals still count every sampled request.";

    /** Why {@code 0} virtual-thread pins on JDK 24+ does not mean {@code synchronized} blocks were absent. */
    static final String JEP_491_NOTE = "On JDK 24 and later (JEP 491), synchronized blocks no longer pin virtual"
            + " threads; jdk.VirtualThreadPinned records only native frames and class initializers.";

    private final RuntimeJournal journal;
    private final Supplier<RouteTemplateResolver> routes;
    private final Duration maxDuration;
    private final JfrProfiler profiler;
    private final Predicate<String> panelEnabled;
    private final int featureVersion;

    private Instant mappedFor;
    private List<RuntimeResourceProfileRouteDto> mappedRoutes = List.of();
    private int mappedOmitted;

    /**
     * @param panelEnabled whether a panel, by its id, may be shown; {@code null} enables every panel
     */
    public ResourceProfileService(
            RuntimeJournal journal,
            Supplier<RouteTemplateResolver> routes,
            Duration maxDuration,
            Predicate<String> panelEnabled) {
        this(
                journal,
                routes,
                maxDuration,
                panelEnabled,
                JfrProfiler.shared(),
                Runtime.version().feature());
    }

    ResourceProfileService(
            RuntimeJournal journal,
            Supplier<RouteTemplateResolver> routes,
            Duration maxDuration,
            Predicate<String> panelEnabled,
            JfrProfiler profiler) {
        this(
                journal,
                routes,
                maxDuration,
                panelEnabled,
                profiler,
                Runtime.version().feature());
    }

    ResourceProfileService(
            RuntimeJournal journal,
            Supplier<RouteTemplateResolver> routes,
            Duration maxDuration,
            Predicate<String> panelEnabled,
            JfrProfiler profiler,
            int featureVersion) {
        this.journal = journal;
        this.panelEnabled = panelEnabled == null ? panel -> true : panelEnabled;
        this.routes = routes == null ? RouteTemplateResolver::empty : routes;
        this.maxDuration = Objects.requireNonNull(maxDuration, "maxDuration");
        this.profiler = profiler;
        this.featureVersion = featureVersion;
    }

    /** The session's state and the last session's results; starts nothing. */
    public RuntimeResourceProfileDto status() {
        return report(profiler.snapshot());
    }

    /** Starts a session of {@code bootui.resources.jfr.max-duration}, unless one runs or segments are not measured. */
    public RuntimeResourceProfileDto start() {
        String unavailable = unavailableReason();
        if (unavailable != null) {
            return unavailable(unavailable);
        }
        return report(profiler.start(maxDuration));
    }

    /** Ends the running session now and returns its results. */
    public RuntimeResourceProfileDto stop() {
        return report(profiler.stop());
    }

    private String unavailableReason() {
        if (journal == null || !journal.settings().enabled()) {
            return RuntimeInsightsService.DISABLED;
        }
        return journal.records(JournalSource.RESOURCES) ? null : RESOURCES_OFF;
    }

    private RuntimeResourceProfileDto unavailable(String reason) {
        return new RuntimeResourceProfileDto(
                State.UNAVAILABLE.name(),
                reason,
                null,
                null,
                null,
                null,
                maxDuration.toSeconds(),
                0,
                0,
                0,
                false,
                0,
                0,
                0,
                List.of(),
                0,
                List.of());
    }

    private synchronized RuntimeResourceProfileDto report(Snapshot snapshot) {
        String unavailable = unavailableReason();
        if (unavailable != null && snapshot.state() != State.RUNNING) {
            return unavailable(unavailable);
        }
        JfrProfiler.Analysis analysis = snapshot.analysis();
        if (snapshot.state() == State.COMPLETED && !Objects.equals(mappedFor, snapshot.finishedAt())) {
            map(analysis.requests());
            mappedFor = snapshot.finishedAt();
        }
        boolean completed = snapshot.state() == State.COMPLETED;
        // Evaluated on every read, so disabling the panel after the session completed withholds its cached rows too.
        boolean routesShown = completed && routesVisible();
        List<String> limitations = completed ? limitations(snapshot, featureVersion) : List.of();
        if (completed && !routesShown) {
            limitations = new ArrayList<>(limitations);
            limitations.add(ROUTES_HIDDEN);
        }
        return new RuntimeResourceProfileDto(
                snapshot.state().name(),
                snapshot.reason(),
                snapshot.sampler(),
                millis(snapshot.startedAt()),
                millis(snapshot.endsAt()),
                millis(snapshot.finishedAt()),
                maxDuration.toSeconds(),
                analysis.cpuSamples(),
                analysis.outsideSamples(),
                analysis.requests().size(),
                snapshot.virtualThreadPinningSupported(),
                analysis.pinnedEvents(),
                analysis.outsidePinnedEvents(),
                analysis.pinnedDurationMicros(),
                routesShown ? mappedRoutes : List.of(),
                routesShown ? mappedOmitted : 0,
                limitations);
    }

    private boolean routesVisible() {
        for (String panel : JournalSourcePanels.panelsOf(JournalSource.HTTP)) {
            try {
                if (!panelEnabled.test(panel)) {
                    return false;
                }
            } catch (RuntimeException ex) {
                return false;
            }
        }
        return true;
    }

    /** Names each request's route from the journal once, when the session completes, before it may evict them. */
    private void map(Map<String, RequestSamples> requests) {
        Map<String, String> routeOf = routesOf(requests);
        Map<String, Route> byRoute = new LinkedHashMap<>();
        requests.forEach((requestId, samples) -> byRoute.computeIfAbsent(
                        routeOf.getOrDefault(requestId, NOT_RETAINED), Route::new)
                .add(samples));
        Comparator<Route> byCpuThenPinning = Comparator.comparingLong((Route route) -> route.cpuSamples)
                .reversed()
                .thenComparing(Comparator.comparingLong((Route route) -> route.pinnedDurationMicros)
                        .reversed())
                .thenComparing(Comparator.comparingLong((Route route) -> route.pinnedEvents)
                        .reversed())
                .thenComparing(Comparator.comparingLong((Route route) -> route.allocatedBytes)
                        .reversed())
                .thenComparing(route -> route.name);
        List<Route> sorted = byRoute.values().stream().sorted(byCpuThenPinning).toList();
        List<Route> selected;
        if (sorted.size() <= RuntimeResourceProfileDto.MAX_ROUTES) {
            selected = sorted;
        } else {
            selected = new ArrayList<>(sorted.subList(0, RuntimeResourceProfileDto.MAX_ROUTES));
            List<Route> overflowPinned = sorted.subList(RuntimeResourceProfileDto.MAX_ROUTES, sorted.size()).stream()
                    .filter(route -> route.pinnedEvents > 0)
                    .sorted(Comparator.comparingLong((Route route) -> route.pinnedDurationMicros)
                            .reversed()
                            .thenComparing(Comparator.comparingLong((Route route) -> route.pinnedEvents)
                                    .reversed())
                            .thenComparing(byCpuThenPinning))
                    .toList();
            int replaceIdx = selected.size() - 1;
            for (Route pinnedRoute : overflowPinned) {
                while (replaceIdx >= 0 && selected.get(replaceIdx).pinnedEvents > 0) {
                    replaceIdx--;
                }
                if (replaceIdx < 0) {
                    break;
                }
                selected.set(replaceIdx--, pinnedRoute);
            }
            selected.sort(byCpuThenPinning);
        }
        mappedRoutes = selected.stream().map(Route::dto).toList();
        mappedOmitted = sorted.size() - mappedRoutes.size();
    }

    private Map<String, String> routesOf(Map<String, RequestSamples> requests) {
        Map<String, String> routeOf = new HashMap<>();
        if (journal == null || requests.isEmpty()) {
            return routeOf;
        }
        RouteTemplateResolver resolver;
        try {
            resolver = routes.get();
        } catch (RuntimeException ex) {
            resolver = null;
        }
        if (resolver == null) {
            resolver = RouteTemplateResolver.empty();
        }
        for (JournalEntry entry : journal.entries()) {
            if (entry.event().source() == JournalSource.HTTP
                    && entry.event().requestId() != null
                    && requests.containsKey(entry.event().requestId())
                    && entry.event().payload() instanceof HttpPayload http) {
                routeOf.put(
                        entry.event().requestId(),
                        RouteLabel.of(http.method(), http.path(), http.routeTemplate(), http.operation(), resolver)
                                .id());
            }
        }
        return routeOf;
    }

    private static List<String> limitations(Snapshot snapshot, int featureVersion) {
        List<String> limitations = new ArrayList<>();
        limitations.add(
                JfrProfiler.CPU_TIME_SAMPLER.equals(snapshot.sampler())
                        ? "CPU is counted in samples, one per 10 ms of each thread's CPU time (jdk.CPUTimeSample), not"
                                + " as a measured time."
                        : "CPU is counted in samples of running threads every 10 ms (jdk.ExecutionSample), not as a"
                                + " measured time; JFR samples a bounded number of threads each time.");
        limitations.add("Allocated bytes are JFR's estimate from its allocation samples.");
        limitations.add("Work a request ran on a thread no BootUI scope covers, such as a raw executor, counts as"
                + " outside requests.");
        if (snapshot.virtualThreadPinningSupported() && featureVersion >= 24) {
            limitations.add(JEP_491_NOTE);
        }
        if (snapshot.otherRecording()) {
            limitations.add("Another JFR recording ran at the same time; JFR applies the most detailed settings of"
                    + " both, so either may have sampled more often.");
        }
        if (snapshot.analysis().requestsTruncated()) {
            limitations.add("Samples of requests beyond the first 10,000 were not joined.");
        }
        return limitations;
    }

    private static Long millis(Instant instant) {
        return instant == null ? null : instant.toEpochMilli();
    }

    private static final class Route {

        private final String name;
        private long requests;
        private long cpuSamples;
        private long allocatedBytes;
        private boolean virtualThreads;
        private final Map<String, Long> frames = new HashMap<>();
        private long pinnedEvents;
        private long pinnedDurationMicros;
        private final Map<String, Long> pinnedFrames = new HashMap<>();

        Route(String name) {
            this.name = name;
        }

        void add(RequestSamples samples) {
            requests++;
            cpuSamples += samples.cpuSamples();
            allocatedBytes += samples.allocatedBytes();
            virtualThreads |= samples.virtualThread();
            samples.frames().forEach((frame, count) -> frames.merge(frame, count, Long::sum));
            pinnedEvents += samples.pinnedEvents();
            pinnedDurationMicros += samples.pinnedDurationMicros();
            samples.pinnedFrames().forEach((frame, count) -> pinnedFrames.merge(frame, count, Long::sum));
        }

        RuntimeResourceProfileRouteDto dto() {
            return new RuntimeResourceProfileRouteDto(
                    name,
                    requests,
                    cpuSamples,
                    allocatedBytes,
                    virtualThreads,
                    topFrames(frames),
                    pinnedEvents,
                    pinnedDurationMicros,
                    topFrames(pinnedFrames));
        }

        private static List<RuntimeResourceProfileFrameDto> topFrames(Map<String, Long> counts) {
            return counts.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue()
                            .reversed()
                            .thenComparing(Map.Entry.comparingByKey()))
                    .limit(RuntimeResourceProfileRouteDto.MAX_FRAMES)
                    .map(entry -> new RuntimeResourceProfileFrameDto(entry.getKey(), entry.getValue()))
                    .toList();
        }
    }
}
