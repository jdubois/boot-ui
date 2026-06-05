package io.github.jdubois.bootui.autoconfigure.web;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.dto.CopilotActivityBucket;
import io.github.jdubois.bootui.core.dto.CopilotActivityEvent;
import io.github.jdubois.bootui.core.dto.CopilotDashboardDto;
import io.github.jdubois.bootui.core.dto.CopilotInsightCounts;
import io.github.jdubois.bootui.core.dto.CopilotMetricCount;
import io.github.jdubois.bootui.core.dto.CopilotSessionDetail;
import io.github.jdubois.bootui.core.dto.CopilotSessionListDto;
import io.github.jdubois.bootui.core.dto.CopilotSessionSummary;
import io.github.jdubois.bootui.core.dto.CopilotTurn;
import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared session store for sanitized local CLI agent logs (Copilot CLI, Claude Code, etc.).
 * Only allowlisted fields are surfaced to callers; JSONL raw events are read back from disk
 * only for the opt-in raw-reveal endpoint.
 *
 * <p>This store is intentionally tolerant of unknown JSON shapes: when an expected
 * field is missing, the parser still produces a session summary and flags
 * {@code schemaDrift=true} so the panel degrades to an explanatory empty state
 * instead of failing outright. CLI agent session-state formats are internal
 * and subject to change without notice.</p>
 *
 * <p>A single daemon thread watches the directory via {@link WatchService} with a
 * configurable debounce, then notifies subscribers. When the directory does not
 * exist on startup the thread polls every five seconds until it appears.</p>
 *
 * <p>Subclasses ({@link CopilotSessionStore}, {@link ClaudeCodeSessionStore}) supply
 * agent-specific {@link BootUiProperties.Copilot} configuration; all parsing and
 * dashboard logic is shared.</p>
 */
public abstract class AgentSessionStore {

    private static final Logger log = LoggerFactory.getLogger(AgentSessionStore.class);

    private static final String EVENTS_JSONL = "events.jsonl";
    private static final long HOUR_MILLIS = 60L * 60L * 1000L;
    private static final long DAY_MILLIS = 24L * HOUR_MILLIS;
    private static final int DASHBOARD_BUCKET_COUNT = 24;
    private static final int DASHBOARD_DAILY_BUCKET_COUNT = 7;
    private static final int DASHBOARD_TOP_LIMIT = 10;
    private static final int DASHBOARD_RECENT_SESSION_LIMIT = 8;
    private static final int HARD_MAX_SESSION_EXPLORER_ITEMS = 1000;

    /** Largest individual session-state file we will parse, in bytes. */
    private static final long MAX_FILE_BYTES = 32L * 1024 * 1024;

    private final BootUiProperties.Copilot properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Clock clock;
    private final Map<String, ParsedSession> sessions = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<RefreshEvent>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong refreshCounter = new AtomicLong();
    private final Path sessionStateDir;
    private final String sourceName;

    private ExecutorService watcherExecutor;
    private volatile boolean stopped;
    private volatile CopilotDashboardDto dashboardCache;
    private volatile int availableSessionFileCount;
    private volatile int selectedSessionFileCount;
    private volatile int selectedSessionFileLimit;

    protected AgentSessionStore(BootUiProperties.Copilot properties) {
        this(properties, Clock.systemDefaultZone());
    }

    protected AgentSessionStore(BootUiProperties.Copilot properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.sessionStateDir = resolveDir(properties);
        this.sourceName = properties.getSessionSourceName();
    }

    /**
     * Resolve the configured session-state directory. Defaults to
     * {@code ${user.home}/.copilot/session-state}. Tilde-expansion is supported.
     */
    static Path resolveDir(BootUiProperties.Copilot properties) {
        String configured = properties.getSessionStateDir();
        if (configured == null || configured.isBlank()) {
            return properties.defaultSessionStateDir().toAbsolutePath().normalize();
        }
        String expanded = configured;
        if (expanded.startsWith("~")) {
            expanded = System.getProperty("user.home", "") + expanded.substring(1);
        }
        return Paths.get(expanded).toAbsolutePath().normalize();
    }

    public Path getSessionStateDir() {
        return sessionStateDir;
    }

    /** True when the configured directory currently exists and is readable. */
    public boolean isDirectoryAvailable() {
        return Files.isDirectory(sessionStateDir) && Files.isReadable(sessionStateDir);
    }

    public boolean isEnabled() {
        BootUiProperties.Mode mode = properties.getEnabled();
        return mode == BootUiProperties.Mode.ON || (mode == BootUiProperties.Mode.AUTO && isDirectoryAvailable());
    }

    /**
     * Force a re-scan of the directory. Returns the number of sessions in the cache.
     */
    public synchronized int refresh() {
        if (!isDirectoryAvailable()) {
            sessions.clear();
            dashboardCache = unavailableDashboard();
            availableSessionFileCount = 0;
            selectedSessionFileCount = 0;
            selectedSessionFileLimit = 0;
            return 0;
        }
        List<SessionFileCandidate> candidates = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(sessionStateDir)) {
            for (Path entry : stream) {
                collectEntry(entry, candidates);
            }
        } catch (IOException ex) {
            log.warn("BootUI {}: failed to list {}: {}", sourceName, sessionStateDir, ex.toString());
        }
        int maxParsedSessions = effectiveMaxParsedSessions();
        List<SessionFileCandidate> selectedCandidates = candidates.stream()
                .sorted(sessionFileCandidateComparator())
                .limit(maxParsedSessions)
                .toList();
        Set<String> seen = new HashSet<>();
        for (SessionFileCandidate candidate : selectedCandidates) {
            scanCandidate(candidate, seen);
        }
        // remove sessions whose files disappeared or are outside the parse cap
        sessions.keySet().removeIf(id -> !seen.contains(id));
        availableSessionFileCount = candidates.size();
        selectedSessionFileCount = selectedCandidates.size();
        selectedSessionFileLimit = maxParsedSessions;
        dashboardCache = buildDashboard();
        return sessions.size();
    }

    private void collectEntry(Path entry, List<SessionFileCandidate> candidates) {
        if (Files.isSymbolicLink(entry)) {
            return;
        }
        if (properties.isProjectSessionDirectoryLayout()) {
            collectProjectLayoutEntry(entry, candidates);
            return;
        }
        if (Files.isDirectory(entry)) {
            Path eventsFile = entry.resolve(EVENTS_JSONL);
            if (Files.isRegularFile(eventsFile)) {
                addCandidate(entry.getFileName().toString(), eventsFile, SessionFileFormat.JSONL, candidates);
            }
            return;
        }
        String name = entry.getFileName().toString();
        if (Files.isRegularFile(entry) && name.toLowerCase(Locale.ROOT).endsWith(".json")) {
            addCandidate(sessionIdFor(entry), entry, SessionFileFormat.LEGACY_JSON, candidates);
        }
    }

    private void collectProjectLayoutEntry(Path entry, List<SessionFileCandidate> candidates) {
        if (Files.isDirectory(entry)) {
            collectProjectDirectory(entry, candidates);
            return;
        }
        String name = entry.getFileName().toString().toLowerCase(Locale.ROOT);
        if (Files.isRegularFile(entry) && name.endsWith(".jsonl")) {
            addCandidate(jsonlSessionIdFor(entry), entry, SessionFileFormat.JSONL, candidates);
        }
    }

    private void collectProjectDirectory(Path directory, List<SessionFileCandidate> candidates) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path file : stream) {
                if (Files.isSymbolicLink(file)) {
                    continue;
                }
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (Files.isRegularFile(file) && name.endsWith(".jsonl")) {
                    addCandidate(jsonlSessionIdFor(file), file, SessionFileFormat.JSONL, candidates);
                }
            }
        } catch (IOException ex) {
            log.debug("BootUI {}: failed to list {}: {}", sourceName, directory, ex.toString());
        }
    }

    private void addCandidate(String id, Path file, SessionFileFormat format, List<SessionFileCandidate> candidates) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            if (attrs.size() > MAX_FILE_BYTES) {
                log.warn(
                        "BootUI {}: skipping {} ({} bytes exceeds {} byte limit)",
                        sourceName,
                        file.getFileName(),
                        attrs.size(),
                        MAX_FILE_BYTES);
                return;
            }
            candidates.add(new SessionFileCandidate(id, file, attrs, format));
        } catch (IOException ex) {
            log.debug("BootUI {}: failed to inspect {}: {}", sourceName, file, ex.toString());
        }
    }

    private void scanCandidate(SessionFileCandidate candidate, Set<String> seen) {
        seen.add(candidate.id());
        if (candidate.format() == SessionFileFormat.LEGACY_JSON) {
            scanLegacyJsonSession(candidate);
            return;
        }
        scanJsonlSession(candidate);
    }

    private void scanLegacyJsonSession(SessionFileCandidate candidate) {
        String id = candidate.id();
        Path file = candidate.file();
        BasicFileAttributes attrs = candidate.attrs();
        try {
            long mtime = attrs.lastModifiedTime().toMillis();
            ParsedSession existing = sessions.get(id);
            if (existing != null && existing.fileSize == attrs.size() && existing.fileMtime == mtime) {
                return;
            }
            JsonNode root = objectMapper.readTree(file.toFile());
            ParsedSession parsed = parse(id, file, root, attrs);
            sessions.put(id, parsed);
        } catch (RuntimeException ex) {
            log.debug("BootUI {}: failed to parse {}: {}", sourceName, file, ex.toString());
        }
    }

    private void scanJsonlSession(SessionFileCandidate candidate) {
        String id = candidate.id();
        Path file = candidate.file();
        BasicFileAttributes attrs = candidate.attrs();
        try {
            long mtime = attrs.lastModifiedTime().toMillis();
            ParsedSession existing = sessions.get(id);
            if (existing != null && existing.fileSize == attrs.size() && existing.fileMtime == mtime) {
                return;
            }
            sessions.put(id, parseJsonl(id, file, attrs));
        } catch (IOException ex) {
            log.debug("BootUI {}: failed to parse {}: {}", sourceName, file, ex.toString());
        }
    }

    /**
     * Start the directory watcher in a daemon thread. Safe to call once at bean init.
     */
    public synchronized void start() {
        if (watcherExecutor != null && !watcherExecutor.isShutdown()) {
            return;
        }
        stopped = false;
        refresh();
        watcherExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, properties.getWatcherThreadName());
            t.setDaemon(true);
            return t;
        });
        watcherExecutor.submit(this::runWatcher);
    }

    @PreDestroy
    public synchronized void stop() {
        stopped = true;
        if (watcherExecutor != null) {
            watcherExecutor.shutdownNow();
            watcherExecutor = null;
        }
    }

    private void runWatcher() {
        long debounceMs = Math.max(50L, properties.getStreamDebounce().toMillis());
        if (properties.isProjectSessionDirectoryLayout()) {
            runPollingWatcher(Math.max(5_000L, debounceMs));
            return;
        }
        long lastPollRefresh = 0L;
        while (!stopped && !Thread.currentThread().isInterrupted()) {
            try {
                if (!isDirectoryAvailable()) {
                    Thread.sleep(5_000);
                    continue;
                }
                try (WatchService ws = sessionStateDir.getFileSystem().newWatchService()) {
                    sessionStateDir.register(ws, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
                    while (!stopped && Files.isDirectory(sessionStateDir)) {
                        WatchKey key = ws.poll(2, TimeUnit.SECONDS);
                        if (key == null) {
                            long now = System.currentTimeMillis();
                            if (now - lastPollRefresh >= 5_000L) {
                                refresh();
                                notifyListeners();
                                lastPollRefresh = now;
                            }
                            continue;
                        }
                        // debounce: drain additional events before refreshing
                        Thread.sleep(debounceMs);
                        key.pollEvents();
                        key.reset();
                        // drain any other pending keys
                        WatchKey extra;
                        while ((extra = ws.poll()) != null) {
                            extra.pollEvents();
                            extra.reset();
                        }
                        refresh();
                        notifyListeners();
                        lastPollRefresh = System.currentTimeMillis();
                    }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ex) {
                log.debug("BootUI {} watcher error: {}", sourceName, ex.toString());
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void runPollingWatcher(long intervalMs) {
        while (!stopped && !Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(intervalMs);
                refresh();
                notifyListeners();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ex) {
                log.debug("BootUI {} polling watcher error: {}", sourceName, ex.toString());
            }
        }
    }

    private void notifyListeners() {
        RefreshEvent event = new RefreshEvent(refreshCounter.incrementAndGet(), sessions.size());
        for (Consumer<RefreshEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException ex) {
                log.debug("BootUI {} listener error: {}", sourceName, ex.toString());
            }
        }
    }

    public Runnable subscribe(Consumer<RefreshEvent> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** Build the list payload returned by {@code GET /bootui/api/copilot/sessions}. */
    public CopilotSessionListDto listSessions() {
        return listSessions(null, null);
    }

    /** Build the list payload, optionally limited to sessions active in a time window. */
    public CopilotSessionListDto listSessions(Long sinceEpochMillis, Long untilEpochMillis) {
        int maxSessions = effectiveMaxSessions();
        String displaySessionStateDir = displaySessionStateDir();
        if (!isDirectoryAvailable()) {
            return new CopilotSessionListDto(
                    false,
                    sourceName + " session directory not found at " + displaySessionStateDir,
                    displaySessionStateDir,
                    0,
                    0,
                    maxSessions,
                    List.of(),
                    List.of());
        }
        List<CopilotSessionSummary> sorted = sessions.values().stream()
                .filter(ps -> matchesActivityWindow(ps, sinceEpochMillis, untilEpochMillis))
                .map(ps -> ps.summary)
                .sorted(sessionSummaryComparator())
                .toList();
        List<CopilotSessionSummary> limited = sorted.stream().limit(maxSessions).toList();
        List<String> warnings = new ArrayList<>();
        addParsedSessionCapWarning(warnings);
        if (sorted.size() > limited.size()) {
            warnings.add("Showing the "
                    + limited.size()
                    + " most recent "
                    + properties.getPanelTitle()
                    + " sessions out of "
                    + sorted.size()
                    + ".");
        }
        if (sorted.stream().anyMatch(CopilotSessionSummary::schemaDrift)) {
            warnings.add("One or more sessions did not match the expected "
                    + sourceName
                    + " schema; some details may be missing.");
        }
        return new CopilotSessionListDto(
                true,
                null,
                displaySessionStateDir,
                sorted.size(),
                limited.size(),
                maxSessions,
                limited,
                List.copyOf(warnings));
    }

    private int effectiveMaxSessions() {
        return Math.min(Math.max(1, properties.getMaxSessions()), HARD_MAX_SESSION_EXPLORER_ITEMS);
    }

    private int effectiveMaxParsedSessions() {
        return Math.min(Math.max(1, properties.getMaxParsedSessions()), HARD_MAX_SESSION_EXPLORER_ITEMS);
    }

    private static Comparator<SessionFileCandidate> sessionFileCandidateComparator() {
        return Comparator.comparingLong(SessionFileCandidate::lastModifiedTimeMillis)
                .reversed()
                .thenComparing(SessionFileCandidate::id)
                .thenComparing(candidate -> candidate.file().toString());
    }

    private void addParsedSessionCapWarning(List<String> warnings) {
        if (availableSessionFileCount <= selectedSessionFileCount || selectedSessionFileLimit <= 0) {
            return;
        }
        warnings.add("Loaded the "
                + selectedSessionFileCount
                + " most recently modified "
                + properties.getPanelTitle()
                + " session files out of "
                + availableSessionFileCount
                + "; increase "
                + properties.maxParsedSessionsPropertyName()
                + " to inspect older sessions.");
    }

    private static boolean matchesActivityWindow(ParsedSession session, Long sinceEpochMillis, Long untilEpochMillis) {
        if (sinceEpochMillis == null && untilEpochMillis == null) {
            return true;
        }
        for (Long bucketStart : session.activityByHour.keySet()) {
            if (isInWindow(bucketStart, sinceEpochMillis, untilEpochMillis)) {
                return true;
            }
        }
        Long fallback = firstLong(session.counts.lastActivityEpochMillis(), session.summary.updatedAtEpochMillis());
        return isInWindow(fallback, sinceEpochMillis, untilEpochMillis);
    }

    private static boolean isInWindow(Long timestamp, Long sinceEpochMillis, Long untilEpochMillis) {
        if (timestamp == null) {
            return false;
        }
        if (sinceEpochMillis != null && timestamp < sinceEpochMillis) {
            return false;
        }
        return untilEpochMillis == null || timestamp < untilEpochMillis;
    }

    /** Build the aggregate dashboard payload returned by {@code GET /bootui/api/copilot/dashboard}. */
    public CopilotDashboardDto dashboard() {
        if (!isDirectoryAvailable()) {
            return unavailableDashboard();
        }
        CopilotDashboardDto cached = dashboardCache;
        if (cached != null) {
            return cached;
        }
        return buildDashboard();
    }

    private CopilotDashboardDto unavailableDashboard() {
        String displaySessionStateDir = displaySessionStateDir();
        return new CopilotDashboardDto(
                false,
                sourceName + " session directory not found at " + displaySessionStateDir,
                displaySessionStateDir,
                0,
                0,
                0,
                null,
                null,
                0,
                0,
                0,
                0,
                null,
                List.of(),
                List.of(),
                List.of(),
                0,
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    private CopilotDashboardDto buildDashboard() {
        long now = clock.millis();
        long activeDayCutoff = now - DAY_MILLIS;
        long activeWeekCutoff = now - (7L * DAY_MILLIS);
        long firstBucketStart = hourStart(now) - ((DASHBOARD_BUCKET_COUNT - 1L) * HOUR_MILLIS);
        long firstDayBucketStart = dayStart(now) - ((DASHBOARD_DAILY_BUCKET_COUNT - 1L) * DAY_MILLIS);

        List<ParsedSession> snapshot = new ArrayList<>(sessions.values());
        Map<String, Integer> categoryCounts = new LinkedHashMap<>();
        Map<String, Integer> modelCounts = new LinkedHashMap<>();
        Map<String, Integer> toolCounts = new LinkedHashMap<>();
        Map<Long, BucketAccumulator> buckets = new LinkedHashMap<>();
        Map<Long, BucketAccumulator> dailyBuckets = new LinkedHashMap<>();
        for (int i = 0; i < DASHBOARD_BUCKET_COUNT; i++) {
            long start = firstBucketStart + (i * HOUR_MILLIS);
            buckets.put(start, new BucketAccumulator());
        }
        for (int i = 0; i < DASHBOARD_DAILY_BUCKET_COUNT; i++) {
            long start = firstDayBucketStart + (i * DAY_MILLIS);
            dailyBuckets.put(start, new BucketAccumulator());
        }

        int eventCount = 0;
        int turnCount = 0;
        int errorCount = 0;
        Long totalInputTokens = null;
        Long totalOutputTokens = null;
        int activeLast24Hours = 0;
        int activeLast7Days = 0;
        int sessionsWithSchemaDrift = 0;
        Long lastActivity = null;

        for (ParsedSession session : snapshot) {
            CopilotSessionSummary summary = session.summary;
            eventCount += summary.eventCount();
            turnCount += summary.turnCount();
            errorCount += summary.errorCount();
            totalInputTokens = addNullable(totalInputTokens, summary.inputTokens());
            totalOutputTokens = addNullable(totalOutputTokens, summary.outputTokens());
            if (summary.schemaDrift()) {
                sessionsWithSchemaDrift++;
            }
            Long sessionActivity = firstLong(session.counts.lastActivityEpochMillis(), summary.updatedAtEpochMillis());
            if (sessionActivity != null) {
                if (sessionActivity >= activeDayCutoff) {
                    activeLast24Hours++;
                }
                if (sessionActivity >= activeWeekCutoff) {
                    activeLast7Days++;
                }
                if (lastActivity == null || sessionActivity > lastActivity) {
                    lastActivity = sessionActivity;
                }
            }
            mergeCounts(categoryCounts, session.counts.byCategory());
            mergeCount(modelCounts, firstNonBlank(summary.model(), "Unknown"), 1);
            mergeCounts(toolCounts, session.toolCounts);
            for (Map.Entry<Long, BucketCounts> entry : session.activityByHour.entrySet()) {
                BucketAccumulator bucket = buckets.get(entry.getKey());
                if (bucket != null) {
                    bucket.add(entry.getValue());
                }
                BucketAccumulator dailyBucket = dailyBuckets.get(dayStart(entry.getKey()));
                if (dailyBucket != null) {
                    dailyBucket.add(entry.getValue());
                }
            }
        }

        List<CopilotMetricCount> topTools =
                metricCounts(toolCounts).stream().limit(DASHBOARD_TOP_LIMIT).toList();
        int visibleToolEvents =
                topTools.stream().mapToInt(CopilotMetricCount::count).sum();
        int allToolEvents =
                toolCounts.values().stream().mapToInt(Integer::intValue).sum();

        List<CopilotActivityBucket> activityBuckets = buckets.entrySet().stream()
                .map(entry -> new CopilotActivityBucket(
                        entry.getKey(),
                        entry.getKey() + HOUR_MILLIS,
                        entry.getValue().eventCount,
                        entry.getValue().errorCount,
                        entry.getValue().inputTokens,
                        entry.getValue().outputTokens))
                .toList();
        List<CopilotActivityBucket> dailyActivityBuckets = dailyBuckets.entrySet().stream()
                .map(entry -> new CopilotActivityBucket(
                        entry.getKey(),
                        entry.getKey() + DAY_MILLIS,
                        entry.getValue().eventCount,
                        entry.getValue().errorCount,
                        entry.getValue().inputTokens,
                        entry.getValue().outputTokens))
                .toList();
        List<CopilotSessionSummary> recentSessions = snapshot.stream()
                .map(ps -> ps.summary)
                .sorted(sessionSummaryComparator())
                .limit(DASHBOARD_RECENT_SESSION_LIMIT)
                .toList();
        List<String> warnings = new ArrayList<>();
        addParsedSessionCapWarning(warnings);
        if (sessionsWithSchemaDrift > 0) {
            warnings.add(sessionsWithSchemaDrift
                    + " "
                    + properties.getPanelTitle()
                    + " sessions did not match the expected schema; some metrics may be incomplete.");
        }

        return new CopilotDashboardDto(
                true,
                null,
                displaySessionStateDir(),
                snapshot.size(),
                eventCount,
                turnCount,
                totalInputTokens,
                totalOutputTokens,
                errorCount,
                activeLast24Hours,
                activeLast7Days,
                sessionsWithSchemaDrift,
                lastActivity,
                metricCounts(categoryCounts),
                metricCounts(modelCounts),
                topTools,
                allToolEvents - visibleToolEvents,
                activityBuckets,
                dailyActivityBuckets,
                recentSessions,
                List.copyOf(warnings));
    }

    private String displaySessionStateDir() {
        return displayPath(sessionStateDir);
    }

    private static String displayPath(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            return normalized.toString();
        }
        Path homePath = Paths.get(home).toAbsolutePath().normalize();
        if (normalized.equals(homePath)) {
            return "~";
        }
        if (normalized.startsWith(homePath)) {
            return "~/" + homePath.relativize(normalized).toString().replace('\\', '/');
        }
        return normalized.toString();
    }

    public CopilotSessionDetail getSession(String id) {
        ParsedSession ps = sessions.get(id);
        if (ps == null) {
            return null;
        }
        int returned = Math.min(ps.events.size(), 200);
        List<CopilotActivityEvent> recent =
                ps.events.subList(Math.max(0, ps.events.size() - returned), ps.events.size());
        List<String> warnings = new ArrayList<>();
        if (ps.summary.schemaDrift()) {
            warnings.add("Session did not match the expected " + sourceName + " schema; some details may be missing.");
        }
        if (ps.summary.errorCount() > ps.failureEvents.size()) {
            warnings.add("Showing "
                    + ps.failureEvents.size()
                    + " retained failures out of "
                    + ps.summary.errorCount()
                    + " total failures.");
        }
        return new CopilotSessionDetail(
                ps.summary, ps.counts, ps.turns, recent, ps.failureEvents, List.copyOf(warnings));
    }

    private static Comparator<CopilotSessionSummary> sessionSummaryComparator() {
        return Comparator.comparing(
                        CopilotSessionSummary::updatedAtEpochMillis, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(CopilotSessionSummary::id);
    }

    private static List<CopilotMetricCount> metricCounts(Map<String, Integer> counts) {
        return counts.entrySet().stream()
                .map(entry -> new CopilotMetricCount(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingInt(CopilotMetricCount::count)
                        .reversed()
                        .thenComparing(CopilotMetricCount::label))
                .toList();
    }

    private static void mergeCounts(Map<String, Integer> target, Map<String, Integer> source) {
        for (Map.Entry<String, Integer> entry : source.entrySet()) {
            mergeCount(target, entry.getKey(), entry.getValue());
        }
    }

    private static void mergeCount(Map<String, Integer> target, String label, int count) {
        if (label == null || label.isBlank() || count <= 0) {
            return;
        }
        target.merge(label, count, Integer::sum);
    }

    private static long hourStart(long epochMillis) {
        return Math.floorDiv(epochMillis, HOUR_MILLIS) * HOUR_MILLIS;
    }

    private static long dayStart(long epochMillis) {
        return Math.floorDiv(epochMillis, DAY_MILLIS) * DAY_MILLIS;
    }

    /**
     * Returns events for the given session, optionally filtered by category and timestamp.
     * The returned list is bounded by {@code limit} and contains the most recent events.
     */
    public List<CopilotActivityEvent> listEvents(String id, String category, Long sinceEpochMillis, int limit) {
        ParsedSession ps = sessions.get(id);
        if (ps == null) {
            return null;
        }
        List<CopilotActivityEvent> events = ps.events;
        if (category != null && !category.isBlank()) {
            String normalized = category.trim().toUpperCase(Locale.ROOT);
            events =
                    events.stream().filter(e -> normalized.equals(e.category())).toList();
        }
        if (sinceEpochMillis != null) {
            events = events.stream()
                    .filter(e -> e.timestampEpochMillis() != null && e.timestampEpochMillis() >= sinceEpochMillis)
                    .toList();
        }
        int bounded = Math.min(events.size(), Math.max(1, limit));
        return events.subList(Math.max(0, events.size() - bounded), events.size());
    }

    public int totalEvents(String id, String category, Long sinceEpochMillis) {
        ParsedSession ps = sessions.get(id);
        if (ps == null) {
            return 0;
        }
        if ((category == null || category.isBlank()) && sinceEpochMillis == null) {
            return ps.events.size();
        }
        return listEvents(id, category, sinceEpochMillis, Integer.MAX_VALUE).size();
    }

    /** Return the raw JSON of the requested event, or {@code null} if not found. */
    public String getRawEventJson(String sessionId, String eventId) {
        ParsedSession ps = sessions.get(sessionId);
        if (ps == null) {
            return null;
        }
        RawEventReference reference = ps.rawById.get(eventId);
        if (reference == null) {
            return null;
        }
        if (reference instanceof JsonNodeRawEventReference jsonNodeReference) {
            return jsonToString(jsonNodeReference.node());
        }
        if (reference instanceof JsonlLineRawEventReference jsonlReference) {
            return readJsonlRawEvent(jsonlReference);
        }
        return null;
    }

    private String readJsonlRawEvent(JsonlLineRawEventReference reference) {
        try (BufferedReader reader = Files.newBufferedReader(reference.file(), StandardCharsets.UTF_8)) {
            String line;
            int current = 0;
            while ((line = reader.readLine()) != null) {
                current++;
                if (current == reference.lineNumber()) {
                    JsonNode node = objectMapper.readTree(line);
                    return jsonToString(node);
                }
            }
        } catch (IOException ex) {
            log.debug(
                    "BootUI Copilot: failed to read raw event {}:{}: {}",
                    reference.file(),
                    reference.lineNumber(),
                    ex.toString());
        }
        return null;
    }

    private String jsonToString(JsonNode node) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception ex) {
            return node.toString();
        }
    }

    // ── Parser ────────────────────────────────────────────────────────────────

    private ParsedSession parse(String id, Path file, JsonNode root, BasicFileAttributes attrs) {
        long mtime = attrs.lastModifiedTime().toMillis();
        String model = stringField(root, "model", "active_model", "activeModel");
        String cwd = stringField(root, "cwd", "working_dir", "workingDirectory", "workdir");
        String status = stringField(root, "status", "state");
        Long startedAt = longField(root, "created_at", "started_at", "startedAt", "createdAt");
        Long updatedAt = longField(root, "updated_at", "updatedAt");
        if (updatedAt == null) {
            updatedAt = mtime;
        }

        EventsExtraction extracted = extractEvents(root, properties.getMaxEventsPerSession());
        List<CopilotActivityEvent> events = extracted.events;
        Map<String, RawEventReference> rawById = extracted.rawById;
        List<CopilotTurn> turns = extracted.turns;
        List<CopilotActivityEvent> failureEvents = events.stream()
                .filter(event -> Boolean.FALSE.equals(event.success()))
                .toList();

        Map<String, Integer> byCategory = new LinkedHashMap<>();
        int errors = 0;
        Long lastActivity = null;
        String lastSummary = null;
        for (CopilotActivityEvent e : events) {
            byCategory.merge(e.category(), 1, Integer::sum);
            if (Boolean.FALSE.equals(e.success())) {
                errors++;
            }
            if (e.timestampEpochMillis() != null && (lastActivity == null || e.timestampEpochMillis() > lastActivity)) {
                lastActivity = e.timestampEpochMillis();
                lastSummary = e.summary();
            }
        }
        if (lastSummary == null && !events.isEmpty()) {
            lastSummary = events.get(events.size() - 1).summary();
        }

        CopilotInsightCounts counts = new CopilotInsightCounts(events.size(), byCategory, errors, lastActivity);

        CopilotSessionSummary summary = new CopilotSessionSummary(
                id,
                file.getFileName().toString(),
                startedAt,
                updatedAt,
                model,
                cwd,
                status,
                events.size(),
                turns.size(),
                extracted.tokenUsage.inputTokens(),
                extracted.tokenUsage.outputTokens(),
                errors,
                lastSummary,
                extracted.schemaDrift);

        SessionAggregates aggregates = aggregateEvents(events);
        Map<Long, BucketCounts> activityByHour = new LinkedHashMap<>(aggregates.activityByHour());
        mergeBucketCounts(activityByHour, extracted.tokenActivityByHour);
        return new ParsedSession(
                summary,
                counts,
                turns,
                events,
                failureEvents,
                rawById,
                aggregates.toolCounts(),
                activityByHour,
                attrs.size(),
                mtime);
    }

    private ParsedSession parseJsonl(String id, Path file, BasicFileAttributes attrs) throws IOException {
        int maxEvents = Math.max(1, properties.getMaxEventsPerSession());
        long mtime = attrs.lastModifiedTime().toMillis();
        List<CopilotActivityEvent> events = new ArrayList<>();
        List<CopilotActivityEvent> failureEvents = new ArrayList<>();
        Map<String, RawEventReference> rawById = new LinkedHashMap<>();
        Map<String, TurnAccumulator> turnAccumulators = new LinkedHashMap<>();
        Map<String, Integer> byCategory = new LinkedHashMap<>();
        Map<String, Integer> toolCounts = new LinkedHashMap<>();
        Map<Long, BucketCounts> activityByHour = new LinkedHashMap<>();
        Map<Long, BucketCounts> incrementalTokenActivityByHour = new LinkedHashMap<>();
        Map<Long, BucketCounts> authoritativeTokenActivityByHour = new LinkedHashMap<>();
        Map<String, String> toolNameByEventId = new HashMap<>();

        String model = null;
        String cwd = null;
        String status = null;
        Long startedAt = null;
        Long updatedAt = null;
        Long lastActivity = null;
        String lastSummary = null;
        TokenUsage sessionTokenUsage = TokenUsage.empty();
        boolean sessionTokenUsageAuthoritative = false;
        int totalEvents = 0;
        int errors = 0;
        int lineNumber = 0;
        boolean schemaDrift = false;
        boolean parsedAnyLine = false;

        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node;
                try {
                    node = objectMapper.readTree(line);
                } catch (RuntimeException ex) {
                    schemaDrift = true;
                    log.debug("BootUI {}: failed to parse {} line {}: {}", sourceName, file, lineNumber, ex.toString());
                    continue;
                }
                parsedAnyLine = true;
                JsonNode data = child(node, "data");
                JsonNode message = child(node, "message");
                String type = stringField(node, "type", "kind", "event", "role");
                Long timestamp = firstLong(
                        longField(node, "timestamp", "ts", "created_at", "createdAt", "time"),
                        longField(data, "timestamp", "ts", "created_at", "createdAt", "time", "startTime"));
                if (timestamp != null) {
                    if (startedAt == null || timestamp < startedAt) {
                        startedAt = timestamp;
                    }
                    if (updatedAt == null || timestamp > updatedAt) {
                        updatedAt = timestamp;
                    }
                }
                JsonNode context = child(data, "context");
                model = firstNonBlank(
                        model, stringField(data, "model"), stringField(node, "model"), stringField(message, "model"));
                cwd = firstNonBlank(cwd, stringField(context, "cwd"), stringField(node, "cwd"));
                status = firstNonBlank(
                        status,
                        stringField(data, "status"),
                        stringField(node, "status", "state"),
                        stringField(message, "stop_reason"));

                TurnAccumulator turnAccumulator = turnAccumulatorFor(turnAccumulators, node, data, type, timestamp);
                int turnIndex = turnAccumulator.index;
                updateTurn(turnAccumulator, type, timestamp);
                TokenUsage tokenUsage = jsonlTokenUsage(node, data, message);
                if (tokenUsage.hasAny()) {
                    if (tokenUsage.sessionTotal()) {
                        sessionTokenUsage = tokenUsage;
                        sessionTokenUsageAuthoritative = true;
                        authoritativeTokenActivityByHour.clear();
                        recordTokenUsage(authoritativeTokenActivityByHour, timestamp, tokenUsage);
                    } else {
                        turnAccumulator.addTokenUsage(tokenUsage);
                        if (!sessionTokenUsageAuthoritative) {
                            sessionTokenUsage = sessionTokenUsage.plus(tokenUsage);
                            recordTokenUsage(incrementalTokenActivityByHour, timestamp, tokenUsage);
                        }
                    }
                }

                List<CopilotActivityEvent> lineEvents =
                        jsonlActivityEvents(node, toolNameByEventId, turnIndex, lineNumber);
                if (lineEvents.isEmpty()) {
                    continue;
                }
                for (CopilotActivityEvent event : lineEvents) {
                    rememberToolName(toolNameByEventId, node, event);
                    totalEvents++;
                    lastSummary = event.summary();
                    if (event.timestampEpochMillis() != null) {
                        lastActivity = event.timestampEpochMillis();
                    }
                    if (Boolean.FALSE.equals(event.success())) {
                        errors++;
                        retainFailure(failureEvents, event, maxEvents);
                    }
                    byCategory.merge(event.category(), 1, Integer::sum);
                    if (event.toolName() != null) {
                        toolCounts.merge(event.toolName(), 1, Integer::sum);
                    }
                    recordActivity(activityByHour, event);
                    retainEvent(events, rawById, event, new JsonlLineRawEventReference(file, lineNumber), maxEvents);
                }
            }
        }

        if (updatedAt == null) {
            updatedAt = mtime;
        }
        if (lastSummary == null && !events.isEmpty()) {
            lastSummary = events.get(events.size() - 1).summary();
        }
        mergeBucketCounts(
                activityByHour,
                sessionTokenUsageAuthoritative ? authoritativeTokenActivityByHour : incrementalTokenActivityByHour);

        List<CopilotTurn> turns =
                turnAccumulators.values().stream().map(TurnAccumulator::toTurn).toList();
        CopilotInsightCounts counts = new CopilotInsightCounts(totalEvents, byCategory, errors, lastActivity);
        CopilotSessionSummary summary = new CopilotSessionSummary(
                id,
                file.getParent().getFileName() + "/" + file.getFileName(),
                startedAt,
                updatedAt,
                model,
                cwd,
                status,
                totalEvents,
                turns.size(),
                sessionTokenUsage.inputTokens(),
                sessionTokenUsage.outputTokens(),
                errors,
                lastSummary,
                schemaDrift || !parsedAnyLine);
        return new ParsedSession(
                summary,
                counts,
                turns,
                events,
                failureEvents,
                rawById,
                toolCounts,
                activityByHour,
                attrs.size(),
                mtime);
    }

    private static TurnAccumulator turnAccumulatorFor(
            Map<String, TurnAccumulator> turnAccumulators, JsonNode node, JsonNode data, String type, Long timestamp) {
        String key = turnKey(node, data, type);
        if (key == null) {
            key = "__session";
        }
        return turnAccumulators.computeIfAbsent(
                key, ignored -> new TurnAccumulator(turnAccumulators.size(), timestamp));
    }

    private static void updateTurn(TurnAccumulator accumulator, String type, Long timestamp) {
        if (timestamp != null) {
            if (accumulator.startedAt == null || timestamp < accumulator.startedAt) {
                accumulator.startedAt = timestamp;
            }
            if (accumulator.lastSeenAt == null || timestamp > accumulator.lastSeenAt) {
                accumulator.lastSeenAt = timestamp;
            }
        }
        accumulator.eventCount++;
        if (accumulator.summary == null && type != null && !type.isBlank()) {
            accumulator.summary = type;
        }
    }

    private static String turnKey(JsonNode node, JsonNode data, String type) {
        String turnId = stringField(data, "turnId", "turn_id");
        if (turnId != null) {
            return turnId;
        }
        if (type != null && type.startsWith("assistant.turn")) {
            return stringField(data, "id", "messageId");
        }
        return stringField(node, "uuid", "parentUuid", "parent_uuid");
    }

    private List<CopilotActivityEvent> jsonlActivityEvents(
            JsonNode node, Map<String, String> toolNameByEventId, int turnIndex, int lineNumber) {
        List<CopilotActivityEvent> claudeCodeEvents =
                claudeCodeActivityEvents(node, toolNameByEventId, turnIndex, lineNumber);
        if (!claudeCodeEvents.isEmpty()) {
            return claudeCodeEvents;
        }
        String type = stringField(node, "type", "kind", "event", "role");
        if (isSensitiveMessageEvent(type)) {
            return List.of();
        }
        JsonNode data = child(node, "data");
        String tool = jsonlToolName(node, data, toolNameByEventId);
        if (type == null && tool == null) {
            return List.of();
        }
        Long ts = firstLong(
                longField(node, "timestamp", "ts", "created_at", "createdAt", "time"),
                longField(data, "timestamp", "ts", "created_at", "createdAt", "time", "startTime"));
        Boolean success = firstBoolean(booleanField(data, "success", "ok"), booleanField(node, "success", "ok"));
        String status = firstNonBlank(stringField(data, "status"), stringField(node, "status"));
        if (success == null && status != null) {
            String lower = status.toLowerCase(Locale.ROOT);
            if (lower.contains("error") || lower.contains("fail")) {
                success = Boolean.FALSE;
            } else if (lower.contains("ok") || lower.contains("success") || lower.contains("complete")) {
                success = Boolean.TRUE;
            }
        }
        String category = categorize(type, tool);
        String summary = sanitizedJsonlSummary(type, tool, category, data, success);
        String eventId = eventIdFor(node, lineNumber, turnIndex, tool);
        return List.of(new CopilotActivityEvent(
                eventId,
                turnIndex,
                ts,
                truncate(type, 64),
                truncate(tool, 96),
                category,
                truncate(summary, 240),
                success));
    }

    private static List<CopilotActivityEvent> claudeCodeActivityEvents(
            JsonNode node, Map<String, String> toolNameByEventId, int turnIndex, int lineNumber) {
        JsonNode message = child(node, "message");
        JsonNode content = child(message, "content");
        if (message == null || content == null) {
            return List.of();
        }
        Long ts = longField(node, "timestamp", "createdAt", "created_at");
        List<CopilotActivityEvent> events = new ArrayList<>();
        if (content.isArray()) {
            for (int i = 0; i < content.size(); i++) {
                addClaudeCodeContentEvent(events, content.get(i), ts, toolNameByEventId, turnIndex, lineNumber, i);
            }
        } else if (content.isObject()) {
            addClaudeCodeContentEvent(events, content, ts, toolNameByEventId, turnIndex, lineNumber, 0);
        }
        return events;
    }

    private static void addClaudeCodeContentEvent(
            List<CopilotActivityEvent> events,
            JsonNode block,
            Long timestamp,
            Map<String, String> toolNameByEventId,
            int turnIndex,
            int lineNumber,
            int blockIndex) {
        if (block == null || block.isNull()) {
            return;
        }
        String blockType = stringField(block, "type");
        if ("tool_use".equals(blockType)) {
            String tool = stringField(block, "name");
            if (tool == null) {
                return;
            }
            String id = firstNonBlank(stringField(block, "id"), "l" + lineNumber + "-b" + blockIndex);
            String category = categorize("tool_use", tool);
            String summary = sanitizedJsonlSummary("tool_use", tool, category, null, Boolean.TRUE);
            CopilotActivityEvent event = new CopilotActivityEvent(
                    id,
                    turnIndex,
                    timestamp,
                    "tool_use",
                    truncate(tool, 96),
                    category,
                    truncate(summary, 240),
                    Boolean.TRUE);
            events.add(event);
            toolNameByEventId.put(event.id(), event.toolName());
            return;
        }
        if ("tool_result".equals(blockType)) {
            String toolUseId = stringField(block, "tool_use_id", "toolUseId");
            String tool = toolUseId == null ? null : toolNameByEventId.get(toolUseId);
            Boolean success = booleanField(block, "is_error");
            if (success != null) {
                success = !success;
            }
            String category = categorize("tool_result", tool);
            String summary = sanitizedJsonlSummary("tool_result", tool, category, null, success);
            String id = firstNonBlank(toolUseId, "l" + lineNumber + "-b" + blockIndex + "-tool-result");
            events.add(new CopilotActivityEvent(
                    id + "-result",
                    turnIndex,
                    timestamp,
                    "tool_result",
                    truncate(tool, 96),
                    category,
                    truncate(summary, 240),
                    success));
        }
    }

    private static boolean isSensitiveMessageEvent(String type) {
        if (type == null) {
            return false;
        }
        String normalized = type.toLowerCase(Locale.ROOT);
        return normalized.equals("user")
                || normalized.equals("assistant")
                || normalized.equals("system")
                || normalized.equals("summary")
                || normalized.equals("user.message")
                || normalized.equals("assistant.message")
                || normalized.equals("system.message");
    }

    private static String jsonlToolName(JsonNode node, JsonNode data, Map<String, String> toolNameByEventId) {
        String tool = stringField(data, "toolName", "tool_name", "name");
        if (tool != null) {
            return tool;
        }
        JsonNode input = child(data, "input");
        tool = stringField(input, "toolName", "tool_name", "name");
        if (tool != null) {
            return tool;
        }
        JsonNode toolRequests = child(data, "toolRequests");
        if (toolRequests != null && toolRequests.isArray() && toolRequests.size() > 0) {
            return stringField(toolRequests.get(0), "name", "toolName", "tool_name");
        }
        String parentId = stringField(node, "parentId", "parent_id");
        if (parentId != null) {
            return toolNameByEventId.get(parentId);
        }
        return null;
    }

    private static String sanitizedJsonlSummary(
            String type, String tool, String category, JsonNode data, Boolean success) {
        StringBuilder sb = new StringBuilder();
        sb.append(category).append(" · ").append(tool != null ? tool : type != null ? type : "event");
        if (Boolean.FALSE.equals(success)) {
            sb.append(" · failed");
        }
        String extension = normalizeExtensionHint(firstNonBlank(
                stringField(child(child(data, "toolTelemetry"), "properties"), "fileExtension"),
                stringField(
                        child(child(child(child(data, "input"), "toolResult"), "toolTelemetry"), "properties"),
                        "fileExtension")));
        if (extension != null) {
            sb.append(" · *.").append(extension);
        }
        return sb.toString();
    }

    private static String normalizeExtensionHint(String extension) {
        if (extension == null || extension.isBlank()) {
            return null;
        }
        String normalized = extension
                .trim()
                .replace("[", "")
                .replace("]", "")
                .replace("\"", "")
                .replace("'", "");
        int comma = normalized.indexOf(',');
        if (comma >= 0) {
            normalized = normalized.substring(0, comma);
        }
        normalized = normalized.trim().replaceFirst("^\\.", "");
        return normalized.isBlank() ? null : normalized;
    }

    private static void rememberToolName(
            Map<String, String> toolNameByEventId, JsonNode node, CopilotActivityEvent event) {
        if (event.toolName() == null) {
            return;
        }
        toolNameByEventId.put(event.id(), event.toolName());
        String id = stringField(node, "id", "eventId", "messageId");
        if (id != null) {
            toolNameByEventId.put(id, event.toolName());
        }
    }

    private static String eventIdFor(JsonNode node, int lineNumber, int turnIndex, String tool) {
        String id = stringField(node, "id", "eventId", "messageId");
        if (id != null) {
            return id;
        }
        return "l" + lineNumber + "-t" + turnIndex + "-" + safeIdSuffix(tool);
    }

    private static String safeIdSuffix(String value) {
        if (value == null || value.isBlank()) {
            return "event";
        }
        return truncate(value.replaceAll("[^A-Za-z0-9_.-]", "_"), 48);
    }

    private static void retainEvent(
            List<CopilotActivityEvent> events,
            Map<String, RawEventReference> rawById,
            CopilotActivityEvent event,
            RawEventReference rawEventReference,
            int maxEvents) {
        if (events.size() >= maxEvents) {
            CopilotActivityEvent evicted = events.remove(0);
            rawById.remove(evicted.id());
        }
        events.add(event);
        rawById.put(event.id(), rawEventReference);
    }

    private static void retainFailure(
            List<CopilotActivityEvent> failureEvents, CopilotActivityEvent event, int maxEvents) {
        if (failureEvents.size() >= maxEvents) {
            failureEvents.remove(0);
        }
        failureEvents.add(event);
    }

    private static SessionAggregates aggregateEvents(List<CopilotActivityEvent> events) {
        Map<String, Integer> toolCounts = new LinkedHashMap<>();
        Map<Long, BucketCounts> activityByHour = new LinkedHashMap<>();
        for (CopilotActivityEvent event : events) {
            if (event.toolName() != null) {
                toolCounts.merge(event.toolName(), 1, Integer::sum);
            }
            recordActivity(activityByHour, event);
        }
        return new SessionAggregates(toolCounts, activityByHour);
    }

    private static void recordActivity(Map<Long, BucketCounts> activityByHour, CopilotActivityEvent event) {
        if (event.timestampEpochMillis() == null) {
            return;
        }
        long bucketStart = hourStart(event.timestampEpochMillis());
        int errorIncrement = Boolean.FALSE.equals(event.success()) ? 1 : 0;
        activityByHour.merge(bucketStart, new BucketCounts(1, errorIncrement, null, null), BucketCounts::plus);
    }

    private static void recordTokenUsage(
            Map<Long, BucketCounts> activityByHour, Long timestamp, TokenUsage tokenUsage) {
        if (timestamp == null || tokenUsage == null || !tokenUsage.hasAny()) {
            return;
        }
        long bucketStart = hourStart(timestamp);
        activityByHour.merge(
                bucketStart,
                new BucketCounts(0, 0, tokenUsage.inputTokens(), tokenUsage.outputTokens()),
                BucketCounts::plus);
    }

    private static void mergeBucketCounts(Map<Long, BucketCounts> target, Map<Long, BucketCounts> source) {
        for (Map.Entry<Long, BucketCounts> entry : source.entrySet()) {
            target.merge(entry.getKey(), entry.getValue(), BucketCounts::plus);
        }
    }

    private static String sessionIdFor(Path file) {
        String name = file.getFileName().toString();
        if (name.toLowerCase(Locale.ROOT).endsWith(".json")) {
            return name.substring(0, name.length() - ".json".length());
        }
        return name;
    }

    private static String jsonlSessionIdFor(Path file) {
        String name = file.getFileName().toString();
        if (name.toLowerCase(Locale.ROOT).endsWith(".jsonl")) {
            return name.substring(0, name.length() - ".jsonl".length());
        }
        return name;
    }

    static String stringField(JsonNode node, String... names) {
        if (node == null) {
            return null;
        }
        for (String n : names) {
            JsonNode v = node.get(n);
            if (v != null && !v.isNull() && v.isValueNode()) {
                String s = v.asString();
                if (s != null && !s.isBlank()) {
                    return s;
                }
            }
        }
        return null;
    }

    private static JsonNode child(JsonNode node, String name) {
        if (node == null) {
            return null;
        }
        JsonNode child = node.get(name);
        return child == null || child.isNull() ? null : child;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static Long firstLong(Long... values) {
        for (Long value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Boolean firstBoolean(Boolean... values) {
        for (Boolean value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    static Long longField(JsonNode node, String... names) {
        if (node == null) {
            return null;
        }
        for (String n : names) {
            JsonNode v = node.get(n);
            if (v == null || v.isNull()) {
                continue;
            }
            if (v.canConvertToLong()) {
                long candidate = v.asLong();
                return normalizeEpochMillis(candidate);
            }
            if (v.isString()) {
                Long parsed = parseTimestamp(v.asString());
                if (parsed != null) {
                    return parsed;
                }
            }
        }
        return null;
    }

    private static long normalizeEpochMillis(long value) {
        // Treat values that look like epoch-seconds (< year ~2286 in seconds) as seconds.
        if (value > 0 && value < 10_000_000_000L) {
            return value * 1000L;
        }
        return value;
    }

    static Long parseTimestamp(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return java.time.Instant.parse(text).toEpochMilli();
        } catch (Exception ignore) {
            // fall through
        }
        try {
            return java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli();
        } catch (Exception ignore) {
            // fall through
        }
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException ignore) {
            return null;
        }
    }

    /** Extract sanitized events from common Copilot session-state shapes. */
    private EventsExtraction extractEvents(JsonNode root, int maxEvents) {
        List<CopilotActivityEvent> out = new ArrayList<>();
        Map<String, RawEventReference> rawById = new LinkedHashMap<>();
        List<CopilotTurn> turns = new ArrayList<>();
        Map<Long, BucketCounts> tokenActivityByHour = new LinkedHashMap<>();
        TokenUsage tokenUsage = TokenUsage.empty();
        boolean schemaDrift = false;

        if (root == null || root.isMissingNode()) {
            return new EventsExtraction(out, rawById, turns, tokenActivityByHour, tokenUsage, true);
        }

        // Shape 1: top-level "events" or "activity" array
        JsonNode events = firstArray(root, "events", "activity", "tool_calls", "toolCalls");
        if (events != null) {
            for (int i = 0; i < events.size() && out.size() < maxEvents; i++) {
                JsonNode event = events.get(i);
                TokenUsage eventTokenUsage = tokenUsageForNode(event);
                tokenUsage = tokenUsage.plus(eventTokenUsage);
                recordTokenUsage(tokenActivityByHour, nodeTimestamp(event), eventTokenUsage);
                addEvent(out, rawById, event, 0, i);
            }
        }

        // Shape 2: "turns" array, each with its own events
        JsonNode turnsNode = root.get("turns");
        if (turnsNode != null && turnsNode.isArray()) {
            for (int t = 0; t < turnsNode.size(); t++) {
                JsonNode turn = turnsNode.get(t);
                JsonNode turnEvents = firstArray(turn, "events", "activity", "tool_calls", "toolCalls");
                int beforeCount = out.size();
                Long turnStart = longField(turn, "started_at", "startedAt", "timestamp", "created_at");
                TokenUsage turnTokenUsage = tokenUsageForNode(turn);
                boolean turnHasAggregateTokenUsage = turnTokenUsage.hasAny();
                if (turnEvents != null) {
                    for (int i = 0; i < turnEvents.size() && out.size() < maxEvents; i++) {
                        JsonNode event = turnEvents.get(i);
                        if (!turnHasAggregateTokenUsage) {
                            TokenUsage eventTokenUsage = tokenUsageForNode(event);
                            turnTokenUsage = turnTokenUsage.plus(eventTokenUsage);
                        }
                        addEvent(out, rawById, event, t, i);
                    }
                }
                tokenUsage = tokenUsage.plus(turnTokenUsage);
                recordTokenUsage(tokenActivityByHour, turnStart, turnTokenUsage);
                int eventCount = out.size() - beforeCount;
                String turnSummary = stringField(turn, "summary", "title", "intent");
                Long durationMs = longField(turn, "duration_ms", "durationMs", "duration");
                turns.add(new CopilotTurn(
                        t,
                        turnStart,
                        durationMs,
                        turnSummary,
                        eventCount,
                        turnTokenUsage.inputTokens(),
                        turnTokenUsage.outputTokens()));
            }
        }

        // Shape 3: "messages" array - common in chat-like session formats
        if (out.isEmpty()) {
            JsonNode messages = firstArray(root, "messages", "history");
            if (messages != null) {
                for (int i = 0; i < messages.size() && out.size() < maxEvents; i++) {
                    JsonNode msg = messages.get(i);
                    if (msg == null) {
                        continue;
                    }
                    String role = stringField(msg, "role", "type");
                    if (role == null || "user".equalsIgnoreCase(role)) {
                        // skip user prompts - never surfaced sanitized
                        continue;
                    }
                    TokenUsage messageTokenUsage = tokenUsageForNode(msg);
                    tokenUsage = tokenUsage.plus(messageTokenUsage);
                    recordTokenUsage(tokenActivityByHour, nodeTimestamp(msg), messageTokenUsage);
                    addEvent(out, rawById, msg, 0, i);
                }
            }
        }

        if (!tokenUsage.hasAny()) {
            tokenUsage = tokenUsageForNode(root);
            recordTokenUsage(
                    tokenActivityByHour,
                    firstLong(
                            longField(root, "updated_at", "updatedAt"),
                            longField(root, "created_at", "started_at", "startedAt", "createdAt"),
                            nodeTimestamp(root)),
                    tokenUsage);
        }

        if (out.isEmpty() && events == null && turnsNode == null) {
            schemaDrift = true;
        }

        return new EventsExtraction(out, rawById, turns, tokenActivityByHour, tokenUsage, schemaDrift);
    }

    private void addEvent(
            List<CopilotActivityEvent> out,
            Map<String, RawEventReference> rawById,
            JsonNode node,
            int turnIndex,
            int indexInTurn) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        String type = stringField(node, "type", "kind", "event", "role");
        String tool = stringField(node, "tool", "name", "tool_name", "toolName", "function");
        if (type == null && tool == null) {
            return;
        }
        Long ts = longField(node, "timestamp", "ts", "created_at", "createdAt", "time");
        Boolean success = booleanField(node, "success", "ok");
        if (success == null) {
            String status = stringField(node, "status");
            if (status != null) {
                String lower = status.toLowerCase(Locale.ROOT);
                if (lower.contains("error") || lower.contains("fail")) {
                    success = Boolean.FALSE;
                } else if (lower.contains("ok") || lower.contains("success") || lower.contains("complete")) {
                    success = Boolean.TRUE;
                }
            }
        }
        String category = categorize(type, tool);
        String summary = sanitizedSummary(type, tool, category, node);
        String id = "t" + turnIndex + "-i" + indexInTurn + "-" + (tool == null ? "" : tool);
        CopilotActivityEvent event = new CopilotActivityEvent(
                id, turnIndex, ts, truncate(type, 64), truncate(tool, 96), category, truncate(summary, 240), success);
        out.add(event);
        rawById.put(id, new JsonNodeRawEventReference(node));
    }

    private static JsonNode firstArray(JsonNode parent, String... names) {
        if (parent == null) {
            return null;
        }
        for (String n : names) {
            JsonNode v = parent.get(n);
            if (v != null && v.isArray()) {
                return v;
            }
        }
        return null;
    }

    private static Boolean booleanField(JsonNode node, String... names) {
        if (node == null) {
            return null;
        }
        for (String n : names) {
            JsonNode v = node.get(n);
            if (v != null && v.isBoolean()) {
                return v.asBoolean();
            }
        }
        return null;
    }

    private static Long nodeTimestamp(JsonNode node) {
        return longField(node, "timestamp", "ts", "created_at", "createdAt", "time", "startTime");
    }

    private static TokenUsage jsonlTokenUsage(JsonNode node, JsonNode data, JsonNode message) {
        TokenUsage tokenDetailsUsage = tokenDetailsTokenUsage(data);
        if (tokenDetailsUsage.hasAny()) {
            return tokenDetailsUsage.asSessionTotal();
        }
        TokenUsage modelMetricsUsage = modelMetricsTokenUsage(data);
        if (modelMetricsUsage.hasAny()) {
            return modelMetricsUsage.asSessionTotal();
        }
        return firstTokenUsage(
                usageObjectTokenUsage(child(data, "usage")),
                usageObjectTokenUsage(child(data, "usageMetadata")),
                usageObjectTokenUsage(child(data, "usage_metadata")),
                usageObjectTokenUsage(child(message, "usage")),
                usageObjectTokenUsage(child(message, "usageMetadata")),
                usageObjectTokenUsage(child(message, "usage_metadata")),
                directTokenUsage(data),
                directTokenUsage(message),
                usageObjectTokenUsage(child(node, "usage")),
                directTokenUsage(node));
    }

    private static TokenUsage tokenUsageForNode(JsonNode node) {
        TokenUsage tokenDetailsUsage = tokenDetailsTokenUsage(node);
        if (tokenDetailsUsage.hasAny()) {
            return tokenDetailsUsage;
        }
        TokenUsage modelMetricsUsage = modelMetricsTokenUsage(node);
        if (modelMetricsUsage.hasAny()) {
            return modelMetricsUsage;
        }
        return firstTokenUsage(
                usageObjectTokenUsage(child(node, "usage")),
                usageObjectTokenUsage(child(node, "usageMetadata")),
                usageObjectTokenUsage(child(node, "usage_metadata")),
                directTokenUsage(node));
    }

    private static TokenUsage modelMetricsTokenUsage(JsonNode node) {
        JsonNode modelMetrics = child(node, "modelMetrics");
        if (modelMetrics == null || !modelMetrics.isObject()) {
            return TokenUsage.empty();
        }
        TokenUsage total = TokenUsage.empty();
        for (Map.Entry<String, JsonNode> entry : modelMetrics.properties()) {
            total = total.plus(usageObjectTokenUsage(child(entry.getValue(), "usage"), false)
                    .withoutSessionTotal());
        }
        return total;
    }

    private static TokenUsage tokenDetailsTokenUsage(JsonNode node) {
        JsonNode tokenDetails = child(node, "tokenDetails");
        if (tokenDetails == null || !tokenDetails.isObject()) {
            return TokenUsage.empty();
        }
        Long inputTokens = tokenDetailCount(tokenDetails, "input", "inputTokens", "input_tokens");
        inputTokens = addNullable(
                inputTokens,
                tokenDetailCount(
                        tokenDetails,
                        "cache_read",
                        "cacheRead",
                        "cacheReadTokens",
                        "cache_read_tokens",
                        "cache_write",
                        "cacheWrite",
                        "cacheWriteTokens",
                        "cache_write_tokens"));
        Long outputTokens = tokenDetailCount(tokenDetails, "output", "outputTokens", "output_tokens");
        return new TokenUsage(inputTokens, outputTokens, false);
    }

    private static Long tokenDetailCount(JsonNode tokenDetails, String... names) {
        Long sum = null;
        for (String name : names) {
            JsonNode bucket = child(tokenDetails, name);
            if (bucket == null) {
                continue;
            }
            if (bucket.isObject()) {
                sum = addNullable(sum, firstCountField(bucket, "tokenCount", "token_count", "tokens", "count"));
            } else {
                sum = addNullable(sum, countField(tokenDetails, name));
            }
        }
        return sum;
    }

    private static TokenUsage usageObjectTokenUsage(JsonNode usage) {
        return usageObjectTokenUsage(usage, true);
    }

    private static TokenUsage usageObjectTokenUsage(JsonNode usage, boolean includeCacheInputTokens) {
        if (usage == null || !usage.isObject()) {
            return TokenUsage.empty();
        }
        Long inputTokens = firstCountField(
                usage,
                "inputTokens",
                "input_tokens",
                "promptTokens",
                "prompt_tokens",
                "totalInputTokens",
                "total_input_tokens",
                "usage_input_tokens",
                "inputTokenCount",
                "input_token_count");
        Long outputTokens = firstCountField(
                usage,
                "outputTokens",
                "output_tokens",
                "completionTokens",
                "completion_tokens",
                "totalOutputTokens",
                "total_output_tokens",
                "usage_output_tokens",
                "outputTokenCount",
                "output_token_count");
        if (includeCacheInputTokens) {
            inputTokens = addNullable(
                    inputTokens,
                    sumCountFields(
                            usage,
                            "cacheCreationInputTokens",
                            "cache_creation_input_tokens",
                            "cacheReadInputTokens",
                            "cache_read_input_tokens"));
        }
        return new TokenUsage(inputTokens, outputTokens, false);
    }

    private static TokenUsage directTokenUsage(JsonNode node) {
        if (node == null || !node.isObject()) {
            return TokenUsage.empty();
        }
        Long inputTokens = firstCountField(
                node,
                "inputTokens",
                "input_tokens",
                "promptTokens",
                "prompt_tokens",
                "totalInputTokens",
                "total_input_tokens",
                "usage_input_tokens",
                "inputTokenCount",
                "input_token_count");
        Long outputTokens = firstCountField(
                node,
                "outputTokens",
                "output_tokens",
                "completionTokens",
                "completion_tokens",
                "totalOutputTokens",
                "total_output_tokens",
                "usage_output_tokens",
                "outputTokenCount",
                "output_token_count");
        return new TokenUsage(inputTokens, outputTokens, false);
    }

    private static TokenUsage firstTokenUsage(TokenUsage... usages) {
        for (TokenUsage usage : usages) {
            if (usage.hasAny()) {
                return usage;
            }
        }
        return TokenUsage.empty();
    }

    private static Long firstCountField(JsonNode node, String... names) {
        for (String name : names) {
            Long value = countField(node, name);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Long sumCountFields(JsonNode node, String... names) {
        Long sum = null;
        for (String name : names) {
            sum = addNullable(sum, countField(node, name));
        }
        return sum;
    }

    private static Long countField(JsonNode node, String name) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        Long parsed = null;
        if (value.canConvertToLong()) {
            parsed = value.asLong();
        } else if (value.isString()) {
            try {
                parsed = Long.parseLong(value.asString().trim());
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return parsed != null && parsed >= 0 ? parsed : null;
    }

    private static Long addNullable(Long current, Long next) {
        if (next == null) {
            return current;
        }
        if (current == null) {
            return next;
        }
        return current + next;
    }

    /**
     * Map an event type / tool name to one of the curated categories used by the UI filters.
     *
     * <p>Exact tool-name matches for known Claude Code tools take priority over the substring
     * heuristics, because some canonical PascalCase names collide with substring rules
     * (e.g. {@code TodoWrite} contains "write", {@code WebSearch} contains "search",
     * {@code ReadMcpResourceTool} contains "read"). The substring heuristics still apply
     * to Copilot CLI tool names (which use snake_case) and to unknown tools.</p>
     */
    static String categorize(String type, String tool) {
        String canonical = claudeCodeCanonicalCategory(tool);
        if (canonical != null) {
            return canonical;
        }
        String t = (type == null ? "" : type).toLowerCase(Locale.ROOT);
        String n = (tool == null ? "" : tool).toLowerCase(Locale.ROOT);
        if (n.startsWith("mcp.") || n.startsWith("mcp_") || t.contains("mcp")) {
            return "MCP";
        }
        if (t.contains("hook") || n.contains("hook")) {
            return "HOOK";
        }
        if (t.contains("skill") || n.contains("skill")) {
            return "SKILL";
        }
        if (t.contains("sub_agent") || t.contains("subagent") || n.contains("sub_agent") || n.equals("task")) {
            return "SUB_AGENT";
        }
        if (n.contains("apply_patch") || n.contains("edit") || n.contains("write") || n.contains("create")) {
            return "FILE_EDIT";
        }
        if (n.equals("view") || n.contains("read") || n.contains("cat") || n.contains("head") || n.contains("tail")) {
            return "FILE_READ";
        }
        if (n.contains("grep") || n.contains("glob") || n.contains("search") || n.equals("rg") || n.equals("find")) {
            return "SEARCH";
        }
        if (n.contains("shell")
                || n.contains("bash")
                || n.contains("terminal")
                || n.contains("run_command")
                || n.contains("execute")) {
            return "SHELL";
        }
        if (n.contains("web_fetch") || n.contains("web_search") || n.contains("fetch") || n.contains("http")) {
            return "WEB";
        }
        if (n.contains("github") || n.contains("docs")) {
            return "DOCS";
        }
        if (t.contains("fallback") || n.contains("fallback")) {
            return "FALLBACK";
        }
        if (t.contains("ask") || t.contains("intent") || t.contains("plan") || t.contains("schedule")) {
            return "ASK";
        }
        return "OTHER";
    }

    /**
     * Exact-name categorization for the canonical Claude Code tool palette. Returns
     * {@code null} when the tool name is unknown, so the generic substring heuristics
     * still apply (this is what handles every Copilot CLI tool name as well as third-party
     * tools we haven't seen). Mirrors the curated mapping used by the cc-lens project so
     * tools like {@code WebSearch}, {@code TodoWrite}, and {@code EnterPlanMode} land in
     * meaningful buckets instead of the substring-rule false positives.
     */
    private static String claudeCodeCanonicalCategory(String tool) {
        if (tool == null) {
            return null;
        }
        return switch (tool) {
            case "Bash" -> "SHELL";
            case "Read", "NotebookRead" -> "FILE_READ";
            case "Write", "Edit", "MultiEdit", "NotebookEdit" -> "FILE_EDIT";
            case "Glob", "Grep" -> "SEARCH";
            case "Task",
                    "TaskCreate",
                    "TaskUpdate",
                    "TaskList",
                    "TaskOutput",
                    "TaskStop",
                    "TaskGet",
                    "Agent",
                    "SubAgent" -> "SUB_AGENT";
            case "WebSearch", "WebFetch" -> "WEB";
            case "EnterPlanMode", "ExitPlanMode", "AskUserQuestion", "TodoWrite" -> "ASK";
            case "Skill", "ToolSearch", "ListMcpResourcesTool", "ReadMcpResourceTool" -> "SKILL";
            default -> null;
        };
    }

    /**
     * Build a short, sanitized human-readable summary string. Never includes raw arguments,
     * command output, file diffs, or prompts.
     */
    private static String sanitizedSummary(String type, String tool, String category, JsonNode node) {
        String displayTool = tool != null ? tool : type;
        // We deliberately do not include node.get("args"), prompt, output, or diff fields.
        // A coarse "target" field name hint is allowed - e.g., the bare file extension or domain.
        StringBuilder sb = new StringBuilder();
        sb.append(category).append(" · ").append(displayTool != null ? displayTool : "event");
        JsonNode target = firstString(node, "target", "path", "file");
        if (target != null) {
            String hint = pathHint(target.asString());
            if (hint != null) {
                sb.append(" · ").append(hint);
            }
        } else {
            JsonNode url = firstString(node, "url", "host");
            if (url != null) {
                String hint = urlHint(url.asString());
                if (hint != null) {
                    sb.append(" · ").append(hint);
                }
            }
        }
        return sb.toString();
    }

    private static JsonNode firstString(JsonNode node, String... names) {
        if (node == null) {
            return null;
        }
        for (String n : names) {
            JsonNode v = node.get(n);
            if (v != null && v.isString()) {
                return v;
            }
        }
        return null;
    }

    static String pathHint(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String base = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = base.lastIndexOf('.');
        if (dot > 0 && dot < base.length() - 1) {
            return "*." + base.substring(dot + 1);
        }
        return null;
    }

    static String urlHint(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            java.net.URI uri = java.net.URI.create(url);
            String host = uri.getHost();
            return host != null ? host : null;
        } catch (Exception ex) {
            return null;
        }
    }

    static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    public boolean isRawRevealAllowed() {
        return properties.isAllowRawReveal();
    }

    /** Visible for testing - returns an unmodifiable snapshot of cached session ids. */
    java.util.Set<String> cachedSessionIds() {
        return Collections.unmodifiableSet(sessions.keySet());
    }

    // ── internal types ────────────────────────────────────────────────────────

    static final class ParsedSession {
        final CopilotSessionSummary summary;
        final CopilotInsightCounts counts;
        final List<CopilotTurn> turns;
        final List<CopilotActivityEvent> events;
        final List<CopilotActivityEvent> failureEvents;
        final Map<String, RawEventReference> rawById;
        final Map<String, Integer> toolCounts;
        final Map<Long, BucketCounts> activityByHour;
        final long fileSize;
        final long fileMtime;

        ParsedSession(
                CopilotSessionSummary summary,
                CopilotInsightCounts counts,
                List<CopilotTurn> turns,
                List<CopilotActivityEvent> events,
                List<CopilotActivityEvent> failureEvents,
                Map<String, RawEventReference> rawById,
                Map<String, Integer> toolCounts,
                Map<Long, BucketCounts> activityByHour,
                long fileSize,
                long fileMtime) {
            this.summary = Objects.requireNonNull(summary);
            this.counts = Objects.requireNonNull(counts);
            this.turns = List.copyOf(turns);
            this.events = List.copyOf(events);
            this.failureEvents = List.copyOf(failureEvents);
            this.rawById = Map.copyOf(rawById);
            this.toolCounts = Map.copyOf(toolCounts);
            this.activityByHour = Map.copyOf(activityByHour);
            this.fileSize = fileSize;
            this.fileMtime = fileMtime;
        }
    }

    private static final class EventsExtraction {
        final List<CopilotActivityEvent> events;
        final Map<String, RawEventReference> rawById;
        final List<CopilotTurn> turns;
        final Map<Long, BucketCounts> tokenActivityByHour;
        final TokenUsage tokenUsage;
        final boolean schemaDrift;

        EventsExtraction(
                List<CopilotActivityEvent> events,
                Map<String, RawEventReference> rawById,
                List<CopilotTurn> turns,
                Map<Long, BucketCounts> tokenActivityByHour,
                TokenUsage tokenUsage,
                boolean schemaDrift) {
            this.events = events;
            this.rawById = rawById;
            this.turns = turns;
            this.tokenActivityByHour = tokenActivityByHour;
            this.tokenUsage = tokenUsage;
            this.schemaDrift = schemaDrift;
        }
    }

    private interface RawEventReference {}

    private record JsonNodeRawEventReference(JsonNode node) implements RawEventReference {}

    private record JsonlLineRawEventReference(Path file, int lineNumber) implements RawEventReference {}

    private enum SessionFileFormat {
        LEGACY_JSON,
        JSONL
    }

    private record SessionFileCandidate(String id, Path file, BasicFileAttributes attrs, SessionFileFormat format) {

        long lastModifiedTimeMillis() {
            return attrs.lastModifiedTime().toMillis();
        }
    }

    private record SessionAggregates(Map<String, Integer> toolCounts, Map<Long, BucketCounts> activityByHour) {}

    private record BucketCounts(int eventCount, int errorCount, Long inputTokens, Long outputTokens) {

        BucketCounts plus(BucketCounts other) {
            return new BucketCounts(
                    eventCount + other.eventCount,
                    errorCount + other.errorCount,
                    addNullable(inputTokens, other.inputTokens),
                    addNullable(outputTokens, other.outputTokens));
        }
    }

    private record TokenUsage(Long inputTokens, Long outputTokens, boolean sessionTotal) {

        static TokenUsage empty() {
            return new TokenUsage(null, null, false);
        }

        boolean hasAny() {
            return inputTokens != null || outputTokens != null;
        }

        TokenUsage plus(TokenUsage other) {
            if (other == null || !other.hasAny()) {
                return this;
            }
            return new TokenUsage(
                    addNullable(inputTokens, other.inputTokens),
                    addNullable(outputTokens, other.outputTokens),
                    sessionTotal);
        }

        TokenUsage asSessionTotal() {
            return new TokenUsage(inputTokens, outputTokens, true);
        }

        TokenUsage withoutSessionTotal() {
            return sessionTotal ? new TokenUsage(inputTokens, outputTokens, false) : this;
        }
    }

    private static final class BucketAccumulator {
        int eventCount;
        int errorCount;
        Long inputTokens;
        Long outputTokens;

        void add(BucketCounts counts) {
            eventCount += counts.eventCount();
            errorCount += counts.errorCount();
            inputTokens = addNullable(inputTokens, counts.inputTokens());
            outputTokens = addNullable(outputTokens, counts.outputTokens());
        }
    }

    private static final class TurnAccumulator {
        final int index;
        Long startedAt;
        Long lastSeenAt;
        String summary;
        int eventCount;
        Long inputTokens;
        Long outputTokens;

        TurnAccumulator(int index, Long startedAt) {
            this.index = index;
            this.startedAt = startedAt;
            this.lastSeenAt = startedAt;
        }

        void addTokenUsage(TokenUsage tokenUsage) {
            inputTokens = addNullable(inputTokens, tokenUsage.inputTokens());
            outputTokens = addNullable(outputTokens, tokenUsage.outputTokens());
        }

        CopilotTurn toTurn() {
            Long duration =
                    startedAt != null && lastSeenAt != null && lastSeenAt >= startedAt ? lastSeenAt - startedAt : null;
            return new CopilotTurn(index, startedAt, duration, summary, eventCount, inputTokens, outputTokens);
        }
    }

    public record RefreshEvent(long sequence, int sessionCount) {}
}
