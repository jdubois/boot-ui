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
import java.io.IOException;
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
 * Copilot CLI. Only allowlisted fields are surfaced to callers; the raw {@link JsonNode}
 * is retained in memory for the opt-in raw-reveal endpoint.
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
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(sessionStateDir, "*.json")) {
            for (Path file : stream) {
                String id = sessionIdFor(file);
                seen.put(id, Boolean.TRUE);
                try {
                    BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                    if (attrs.size() > MAX_FILE_BYTES) {
                        log.warn(
                                "BootUI Copilot: skipping {} ({} bytes exceeds {} byte limit)",
                                file.getFileName(),
                                attrs.size(),
                                MAX_FILE_BYTES);
                        continue;
                    }
                    long mtime = attrs.lastModifiedTime().toMillis();
                    ParsedSession existing = sessions.get(id);
                    if (existing != null && existing.fileSize == attrs.size() && existing.fileMtime == mtime) {
                        continue;
                    }
                    JsonNode root = objectMapper.readTree(file.toFile());
                    ParsedSession parsed = parse(id, file, root, attrs);
                    sessions.put(id, parsed);
                } catch (IOException ex) {
                    log.debug("BootUI Copilot: failed to parse {}: {}", file, ex.toString());
                }
            }
        } catch (IOException ex) {
            log.warn("BootUI Copilot: failed to list {}: {}", sessionStateDir, ex.toString());
        }
        // remove sessions whose files have disappeared
        sessions.keySet().removeIf(id -> !seen.containsKey(id));
        return sessions.size();
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
        JsonNode node = ps.rawById.get(eventId);
        if (node == null) {
            return null;
        }
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
        Map<String, JsonNode> rawById = extracted.rawById;
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
        Map<String, JsonNode> rawById = new LinkedHashMap<>();
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
            Map<String, JsonNode> rawById,
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
        rawById.put(id, node);
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
        final Map<String, JsonNode> rawById;
        final long fileSize;
        final long fileMtime;

        ParsedSession(
                CopilotSessionSummary summary,
                CopilotInsightCounts counts,
                List<CopilotTurn> turns,
                List<CopilotActivityEvent> events,
                Map<String, JsonNode> rawById,
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
        final Map<String, JsonNode> rawById;
        final List<CopilotTurn> turns;
        final boolean schemaDrift;

        EventsExtraction(
                List<CopilotActivityEvent> events,
                Map<String, JsonNode> rawById,
                List<CopilotTurn> turns,
                boolean schemaDrift) {
            this.events = events;
            this.rawById = rawById;
            this.turns = turns;
            this.schemaDrift = schemaDrift;
        }
    }

    public record RefreshEvent(long sequence, int sessionCount) {}
}
