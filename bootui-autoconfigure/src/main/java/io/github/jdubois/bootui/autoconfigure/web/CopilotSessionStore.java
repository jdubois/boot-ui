package io.github.jdubois.bootui.autoconfigure.web;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.BootUiDtos.CopilotActivityEvent;
import io.github.jdubois.bootui.core.BootUiDtos.CopilotInsightCounts;
import io.github.jdubois.bootui.core.BootUiDtos.CopilotSessionDetail;
import io.github.jdubois.bootui.core.BootUiDtos.CopilotSessionListDto;
import io.github.jdubois.bootui.core.BootUiDtos.CopilotSessionSummary;
import io.github.jdubois.bootui.core.BootUiDtos.CopilotTurn;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Scans, parses, and caches sanitized session-state files written by the local
 * Copilot CLI. Only allowlisted fields are surfaced to callers; JSONL raw events
 * are read back from disk only for the opt-in raw-reveal endpoint.
 *
 * <p>This store is intentionally tolerant of unknown JSON shapes: when an expected
 * field is missing, the parser still produces a session summary and flags
 * {@code schemaDrift=true} so the panel degrades to an explanatory empty state
 * instead of failing outright. The Copilot CLI's session-state format is internal
 * and subject to change without notice.</p>
 *
 * <p>A single daemon thread watches the directory via {@link WatchService} with a
 * configurable debounce, then notifies subscribers. When the directory does not
 * exist on startup the thread polls every five seconds until it appears.</p>
 */
public class CopilotSessionStore {

    private static final Logger log = LoggerFactory.getLogger(CopilotSessionStore.class);

    private static final String EVENTS_JSONL = "events.jsonl";

    /** Largest individual session-state file we will parse, in bytes. */
    private static final long MAX_FILE_BYTES = 32L * 1024 * 1024;

    private final BootUiProperties.Copilot properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, ParsedSession> sessions = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<RefreshEvent>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong refreshCounter = new AtomicLong();
    private final Path sessionStateDir;

    private volatile Thread watcherThread;
    private volatile boolean stopped;

    public CopilotSessionStore(BootUiProperties.Copilot properties) {
        this.properties = properties;
        this.sessionStateDir = resolveDir(properties);
    }

    /**
     * Resolve the configured session-state directory. Defaults to
     * {@code ${user.home}/.copilot/session-state}. Tilde-expansion is supported.
     */
    static Path resolveDir(BootUiProperties.Copilot properties) {
        String configured = properties.getSessionStateDir();
        if (configured == null || configured.isBlank()) {
            String home = System.getProperty("user.home", "");
            return Paths.get(home, ".copilot", "session-state");
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
            return 0;
        }
        Map<String, Boolean> seen = new HashMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(sessionStateDir)) {
            for (Path entry : stream) {
                scanEntry(entry, seen);
            }
        } catch (IOException ex) {
            log.warn("BootUI Copilot: failed to list {}: {}", sessionStateDir, ex.toString());
        }
        // remove sessions whose files have disappeared
        sessions.keySet().removeIf(id -> !seen.containsKey(id));
        return sessions.size();
    }

    private void scanEntry(Path entry, Map<String, Boolean> seen) {
        if (Files.isSymbolicLink(entry)) {
            return;
        }
        if (Files.isDirectory(entry)) {
            Path eventsFile = entry.resolve(EVENTS_JSONL);
            if (Files.isRegularFile(eventsFile)) {
                scanJsonlSession(entry.getFileName().toString(), eventsFile, seen);
            }
            return;
        }
        String name = entry.getFileName().toString();
        if (Files.isRegularFile(entry) && name.toLowerCase(Locale.ROOT).endsWith(".json")) {
            scanLegacyJsonSession(sessionIdFor(entry), entry, seen);
        }
    }

    private void scanLegacyJsonSession(String id, Path file, Map<String, Boolean> seen) {
        seen.put(id, Boolean.TRUE);
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            if (attrs.size() > MAX_FILE_BYTES) {
                log.warn(
                        "BootUI Copilot: skipping {} ({} bytes exceeds {} byte limit)",
                        file.getFileName(),
                        attrs.size(),
                        MAX_FILE_BYTES);
                return;
            }
            long mtime = attrs.lastModifiedTime().toMillis();
            ParsedSession existing = sessions.get(id);
            if (existing != null && existing.fileSize == attrs.size() && existing.fileMtime == mtime) {
                return;
            }
            JsonNode root = objectMapper.readTree(file.toFile());
            ParsedSession parsed = parse(id, file, root, attrs);
            sessions.put(id, parsed);
        } catch (IOException ex) {
            log.debug("BootUI Copilot: failed to parse {}: {}", file, ex.toString());
        }
    }

    private void scanJsonlSession(String id, Path file, Map<String, Boolean> seen) {
        seen.put(id, Boolean.TRUE);
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            long mtime = attrs.lastModifiedTime().toMillis();
            ParsedSession existing = sessions.get(id);
            if (existing != null && existing.fileSize == attrs.size() && existing.fileMtime == mtime) {
                return;
            }
            sessions.put(id, parseJsonl(id, file, attrs));
        } catch (IOException ex) {
            log.debug("BootUI Copilot: failed to parse {}: {}", file, ex.toString());
        }
    }

    /**
     * Start the directory watcher in a daemon thread. Safe to call once at bean init.
     */
    public synchronized void start() {
        if (watcherThread != null) {
            return;
        }
        stopped = false;
        refresh();
        Thread t = new Thread(this::runWatcher, "bootui-copilot-watcher");
        t.setDaemon(true);
        t.start();
        watcherThread = t;
    }

    @PreDestroy
    public synchronized void stop() {
        stopped = true;
        Thread t = watcherThread;
        if (t != null) {
            t.interrupt();
        }
        watcherThread = null;
    }

    private void runWatcher() {
        long debounceMs = Math.max(50L, properties.getStreamDebounce().toMillis());
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
                log.debug("BootUI Copilot watcher error: {}", ex.toString());
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void notifyListeners() {
        RefreshEvent event = new RefreshEvent(refreshCounter.incrementAndGet(), sessions.size());
        for (Consumer<RefreshEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException ex) {
                log.debug("BootUI Copilot listener error: {}", ex.toString());
            }
        }
    }

    public Runnable subscribe(Consumer<RefreshEvent> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** Build the list payload returned by {@code GET /bootui/api/copilot/sessions}. */
    public CopilotSessionListDto listSessions() {
        if (!isDirectoryAvailable()) {
            return new CopilotSessionListDto(
                    false,
                    "Copilot CLI session-state directory not found at " + sessionStateDir,
                    sessionStateDir.toString(),
                    0,
                    List.of(),
                    List.of());
        }
        List<CopilotSessionSummary> sorted = sessions.values().stream()
                .map(ps -> ps.summary)
                .sorted(Comparator.comparing(
                                CopilotSessionSummary::updatedAtEpochMillis,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(CopilotSessionSummary::id))
                .toList();
        List<String> warnings = sorted.stream().anyMatch(CopilotSessionSummary::schemaDrift)
                ? List.of(
                        "One or more sessions did not match the expected Copilot CLI schema; some details may be missing.")
                : List.<String>of();
        return new CopilotSessionListDto(true, null, sessionStateDir.toString(), sorted.size(), sorted, warnings);
    }

    public CopilotSessionDetail getSession(String id) {
        ParsedSession ps = sessions.get(id);
        if (ps == null) {
            return null;
        }
        int returned = Math.min(ps.events.size(), 200);
        List<CopilotActivityEvent> recent =
                ps.events.subList(Math.max(0, ps.events.size() - returned), ps.events.size());
        List<String> warnings = ps.summary.schemaDrift()
                ? List.of("Session did not match the expected Copilot CLI schema; some details may be missing.")
                : List.<String>of();
        return new CopilotSessionDetail(ps.summary, ps.counts, ps.turns, recent, warnings);
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
                errors,
                lastSummary,
                extracted.schemaDrift);

        return new ParsedSession(summary, counts, turns, events, rawById, attrs.size(), mtime);
    }

    private ParsedSession parseJsonl(String id, Path file, BasicFileAttributes attrs) throws IOException {
        int maxEvents = Math.max(1, properties.getMaxEventsPerSession());
        long mtime = attrs.lastModifiedTime().toMillis();
        List<CopilotActivityEvent> events = new ArrayList<>();
        Map<String, RawEventReference> rawById = new LinkedHashMap<>();
        Map<String, TurnAccumulator> turnAccumulators = new LinkedHashMap<>();
        Map<String, Integer> byCategory = new LinkedHashMap<>();

        String model = null;
        String cwd = null;
        String status = null;
        Long startedAt = null;
        Long updatedAt = null;
        Long lastActivity = null;
        String lastSummary = null;
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
                    log.debug("BootUI Copilot: failed to parse {} line {}: {}", file, lineNumber, ex.toString());
                    continue;
                }
                parsedAnyLine = true;
                JsonNode data = child(node, "data");
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
                model = firstNonBlank(model, stringField(data, "model"), stringField(node, "model"));
                cwd = firstNonBlank(cwd, stringField(context, "cwd"));
                status = firstNonBlank(status, stringField(data, "status"), stringField(node, "status", "state"));

                int turnIndex = turnIndexFor(turnAccumulators, data, type, timestamp);
                updateTurn(turnAccumulators, data, type, timestamp);

                CopilotActivityEvent event = jsonlActivityEvent(node, turnIndex, lineNumber);
                if (event == null) {
                    continue;
                }
                lastSummary = event.summary();
                if (event.timestampEpochMillis() != null) {
                    lastActivity = event.timestampEpochMillis();
                }
                if (Boolean.FALSE.equals(event.success())) {
                    errors++;
                }
                byCategory.merge(event.category(), 1, Integer::sum);
                retainEvent(events, rawById, event, new JsonlLineRawEventReference(file, lineNumber), maxEvents);
            }
        }

        if (updatedAt == null) {
            updatedAt = mtime;
        }
        byCategory.clear();
        errors = 0;
        lastActivity = null;
        lastSummary = null;
        for (CopilotActivityEvent event : events) {
            byCategory.merge(event.category(), 1, Integer::sum);
            if (Boolean.FALSE.equals(event.success())) {
                errors++;
            }
            if (event.timestampEpochMillis() != null
                    && (lastActivity == null || event.timestampEpochMillis() >= lastActivity)) {
                lastActivity = event.timestampEpochMillis();
                lastSummary = event.summary();
            }
        }
        if (lastSummary == null && !events.isEmpty()) {
            lastSummary = events.get(events.size() - 1).summary();
        }

        List<CopilotTurn> turns =
                turnAccumulators.values().stream().map(TurnAccumulator::toTurn).toList();
        CopilotInsightCounts counts = new CopilotInsightCounts(events.size(), byCategory, errors, lastActivity);
        CopilotSessionSummary summary = new CopilotSessionSummary(
                id,
                file.getParent().getFileName() + "/" + file.getFileName(),
                startedAt,
                updatedAt,
                model,
                cwd,
                status,
                events.size(),
                turns.size(),
                errors,
                lastSummary,
                schemaDrift || !parsedAnyLine);
        return new ParsedSession(summary, counts, turns, events, rawById, attrs.size(), mtime);
    }

    private static int turnIndexFor(
            Map<String, TurnAccumulator> turnAccumulators, JsonNode data, String type, Long timestamp) {
        String key = turnKey(data, type);
        if (key == null) {
            key = "__session";
        }
        TurnAccumulator accumulator = turnAccumulators.computeIfAbsent(
                key, ignored -> new TurnAccumulator(turnAccumulators.size(), timestamp));
        return accumulator.index;
    }

    private static void updateTurn(
            Map<String, TurnAccumulator> turnAccumulators, JsonNode data, String type, Long timestamp) {
        String key = turnKey(data, type);
        if (key == null) {
            key = "__session";
        }
        TurnAccumulator accumulator = turnAccumulators.computeIfAbsent(
                key, ignored -> new TurnAccumulator(turnAccumulators.size(), timestamp));
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

    private static String turnKey(JsonNode data, String type) {
        String turnId = stringField(data, "turnId", "turn_id");
        if (turnId != null) {
            return turnId;
        }
        if (type != null && type.startsWith("assistant.turn")) {
            return stringField(data, "id", "messageId");
        }
        return null;
    }

    private CopilotActivityEvent jsonlActivityEvent(JsonNode node, int turnIndex, int lineNumber) {
        String type = stringField(node, "type", "kind", "event", "role");
        if (isSensitiveMessageEvent(type)) {
            return null;
        }
        JsonNode data = child(node, "data");
        String tool = jsonlToolName(data);
        if (type == null && tool == null) {
            return null;
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
        String summary = sanitizedJsonlSummary(type, tool, category, data);
        String eventId = eventIdFor(node, lineNumber, turnIndex, tool);
        return new CopilotActivityEvent(
                eventId,
                turnIndex,
                ts,
                truncate(type, 64),
                truncate(tool, 96),
                category,
                truncate(summary, 240),
                success);
    }

    private static boolean isSensitiveMessageEvent(String type) {
        if (type == null) {
            return false;
        }
        String normalized = type.toLowerCase(Locale.ROOT);
        return normalized.equals("user.message")
                || normalized.equals("assistant.message")
                || normalized.equals("system.message");
    }

    private static String jsonlToolName(JsonNode data) {
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
        return null;
    }

    private static String sanitizedJsonlSummary(String type, String tool, String category, JsonNode data) {
        StringBuilder sb = new StringBuilder();
        sb.append(category).append(" · ").append(tool != null ? tool : type != null ? type : "event");
        String extension = firstNonBlank(
                stringField(child(child(data, "toolTelemetry"), "properties"), "fileExtension"),
                stringField(
                        child(child(child(child(data, "input"), "toolResult"), "toolTelemetry"), "properties"),
                        "fileExtension"));
        if (extension != null) {
            sb.append(" · *.").append(extension.replaceFirst("^\\.", ""));
        }
        return sb.toString();
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

    private static String sessionIdFor(Path file) {
        String name = file.getFileName().toString();
        if (name.toLowerCase(Locale.ROOT).endsWith(".json")) {
            return name.substring(0, name.length() - ".json".length());
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
                String s = v.asText();
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
            if (v.isTextual()) {
                Long parsed = parseTimestamp(v.asText());
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
        boolean schemaDrift = false;

        if (root == null || root.isMissingNode()) {
            return new EventsExtraction(out, rawById, turns, true);
        }

        // Shape 1: top-level "events" or "activity" array
        JsonNode events = firstArray(root, "events", "activity", "tool_calls", "toolCalls");
        if (events != null) {
            for (int i = 0; i < events.size() && out.size() < maxEvents; i++) {
                addEvent(out, rawById, events.get(i), 0, i);
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
                if (turnEvents != null) {
                    for (int i = 0; i < turnEvents.size() && out.size() < maxEvents; i++) {
                        addEvent(out, rawById, turnEvents.get(i), t, i);
                    }
                }
                int eventCount = out.size() - beforeCount;
                String turnSummary = stringField(turn, "summary", "title", "intent");
                Long durationMs = longField(turn, "duration_ms", "durationMs", "duration");
                turns.add(new CopilotTurn(t, turnStart, durationMs, turnSummary, eventCount));
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
                    addEvent(out, rawById, msg, 0, i);
                }
            }
        }

        if (out.isEmpty() && events == null && turnsNode == null) {
            schemaDrift = true;
        }

        return new EventsExtraction(out, rawById, turns, schemaDrift);
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

    /**
     * Map an event type / tool name to one of the curated categories used by the UI filters.
     */
    static String categorize(String type, String tool) {
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
            String hint = pathHint(target.asText());
            if (hint != null) {
                sb.append(" · ").append(hint);
            }
        } else {
            JsonNode url = firstString(node, "url", "host");
            if (url != null) {
                String hint = urlHint(url.asText());
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
            if (v != null && v.isTextual()) {
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
        final Map<String, RawEventReference> rawById;
        final long fileSize;
        final long fileMtime;

        ParsedSession(
                CopilotSessionSummary summary,
                CopilotInsightCounts counts,
                List<CopilotTurn> turns,
                List<CopilotActivityEvent> events,
                Map<String, RawEventReference> rawById,
                long fileSize,
                long fileMtime) {
            this.summary = Objects.requireNonNull(summary);
            this.counts = Objects.requireNonNull(counts);
            this.turns = List.copyOf(turns);
            this.events = List.copyOf(events);
            this.rawById = Map.copyOf(rawById);
            this.fileSize = fileSize;
            this.fileMtime = fileMtime;
        }
    }

    private static final class EventsExtraction {
        final List<CopilotActivityEvent> events;
        final Map<String, RawEventReference> rawById;
        final List<CopilotTurn> turns;
        final boolean schemaDrift;

        EventsExtraction(
                List<CopilotActivityEvent> events,
                Map<String, RawEventReference> rawById,
                List<CopilotTurn> turns,
                boolean schemaDrift) {
            this.events = events;
            this.rawById = rawById;
            this.turns = turns;
            this.schemaDrift = schemaDrift;
        }
    }

    private interface RawEventReference {}

    private record JsonNodeRawEventReference(JsonNode node) implements RawEventReference {}

    private record JsonlLineRawEventReference(Path file, int lineNumber) implements RawEventReference {}

    private static final class TurnAccumulator {
        final int index;
        Long startedAt;
        Long lastSeenAt;
        String summary;
        int eventCount;

        TurnAccumulator(int index, Long startedAt) {
            this.index = index;
            this.startedAt = startedAt;
            this.lastSeenAt = startedAt;
        }

        CopilotTurn toTurn() {
            Long duration =
                    startedAt != null && lastSeenAt != null && lastSeenAt >= startedAt ? lastSeenAt - startedAt : null;
            return new CopilotTurn(index, startedAt, duration, summary, eventCount);
        }
    }

    public record RefreshEvent(long sequence, int sessionCount) {}
}
