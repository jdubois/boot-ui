package io.github.jdubois.bootui.autoconfigure.panel;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Shared registry for the panels exposed by the browser UI and protected by the
 * BootUI API access filters.
 */
public final class BootUiPanels {

    public static final String OVERVIEW = "overview";
    public static final String HEALTH = "health";
    public static final String HTTP_SESSIONS = "http-sessions";
    public static final String METRICS = "metrics";
    public static final String MEMORY = "memory";
    public static final String TUNING_ADVISOR = "tuning-advisor";
    public static final String HEAP_DUMP = "heap-dump";
    public static final String THREADS = "threads";
    public static final String STARTUP = "startup";
    public static final String GRAALVM = "graalvm";
    public static final String SCHEDULED = "scheduled";
    public static final String CONFIG = "config";
    public static final String PROFILES = "profiles";
    public static final String LOGGERS = "loggers";
    public static final String BEANS = "beans";
    public static final String CONDITIONS = "conditions";
    public static final String MAPPINGS = "mappings";
    public static final String DATA = "data";
    public static final String FLYWAY = "flyway";
    public static final String LIQUIBASE = "liquibase";
    public static final String DATABASE_CONNECTION_POOLS = "database-connection-pools";
    public static final String SPRING_CACHE = "spring-cache";
    public static final String SPRING_SECURITY = "spring-security";
    public static final String SECURITY_LOGS = "security-logs";
    public static final String AI = "ai";
    public static final String TRACES = "traces";
    public static final String LOG_TAIL = "log-tail";
    public static final String HTTP_EXCHANGES = "http-exchanges";
    public static final String HTTP_PROBE = "http-probe";
    public static final String ARCHITECTURE = "architecture";
    public static final String PENTEST = "pentest";
    public static final String VULNERABILITIES = "vulnerabilities";
    public static final String DEVTOOLS = "devtools";
    public static final String DEV_SERVICES = "dev-services";
    public static final String COPILOT = "copilot";
    public static final String CLAUDE_CODE = "claude-code";

    private static final List<Panel> PANELS = List.of(
            new Panel(OVERVIEW, "Overview", false, "/overview"),
            new Panel(HEALTH, "Health", false, "/health"),
            new Panel(HTTP_SESSIONS, "HTTP Sessions", true, "/http-sessions"),
            new Panel(METRICS, "Metrics", false, "/metrics"),
            new Panel(MEMORY, "Memory", false, "/memory"),
            new Panel(TUNING_ADVISOR, "Tuning Advisor", false, "/tuning-advisor"),
            new Panel(HEAP_DUMP, "Heap Dump", true, "/heap-dump"),
            new Panel(THREADS, "Threads", true, "/threads"),
            new Panel(STARTUP, "Startup Timeline", false, "/startup"),
            new Panel(GRAALVM, "GraalVM", true, "/graalvm"),
            new Panel(CONFIG, "Configuration", true, "/config"),
            new Panel(PROFILES, "Profile Diff", false, "/profiles"),
            new Panel(LOGGERS, "Loggers", true, "/loggers"),
            new Panel(BEANS, "Beans", false, "/beans"),
            new Panel(CONDITIONS, "Conditions", false, "/conditions"),
            new Panel(MAPPINGS, "Mappings", false, "/mappings"),
            new Panel(SPRING_SECURITY, "Spring Security", false, "/spring-security"),
            new Panel(SECURITY_LOGS, "Security Logs", false, "/security-logs"),
            new Panel(PENTEST, "Pentesting", true, "/pentest"),
            new Panel(SCHEDULED, "Scheduled Tasks", false, "/scheduled"),
            new Panel(DATABASE_CONNECTION_POOLS, "Database Connection Pools", false, "/database-connection-pools"),
            new Panel(DATA, "Spring Data", false, "/data"),
            new Panel(SPRING_CACHE, "Spring Cache", true, "/spring-cache"),
            new Panel(AI, "AI Usage", false, "/ai"),
            new Panel(TRACES, "Traces", true, "/traces"),
            new Panel(LOG_TAIL, "Log Tail", false, "/logs"),
            new Panel(HTTP_EXCHANGES, "HTTP Exchanges", false, "/http-exchanges"),
            new Panel(HTTP_PROBE, "HTTP Probe", true, "/probe"),
            new Panel(ARCHITECTURE, "Architecture", true, "/architecture"),
            new Panel(VULNERABILITIES, "Vulnerabilities", true, "/dependencies"),
            new Panel(DEVTOOLS, "DevTools", true, "/devtools"),
            new Panel(DEV_SERVICES, "Dev Services", true, "/dev-services"),
            new Panel(COPILOT, "Copilot", false, "/copilot"),
            new Panel(CLAUDE_CODE, "Claude Code", false, "/claude-code"),
            new Panel(FLYWAY, "Flyway", true, "/flyway"),
            new Panel(LIQUIBASE, "Liquibase", true, "/liquibase"));

    private static final Map<String, Panel> BY_ID =
            PANELS.stream().collect(Collectors.toUnmodifiableMap(Panel::id, Function.identity()));
    private static final Set<String> IDS = Set.copyOf(BY_ID.keySet());

    private BootUiPanels() {}

    public static List<Panel> all() {
        return PANELS;
    }

    public static Set<String> ids() {
        return IDS;
    }

    public static Optional<Panel> byId(String id) {
        return Optional.ofNullable(BY_ID.get(id));
    }

    public static Optional<Panel> byApiPath(String apiRelativePath) {
        return PANELS.stream()
                .filter(panel -> panel.matchesApiPath(apiRelativePath))
                .findFirst();
    }

    public record Panel(String id, String title, boolean actionCapable, List<String> apiPrefixes) {

        public Panel(String id, String title, boolean actionCapable, String apiPrefix) {
            this(id, title, actionCapable, List.of(apiPrefix));
        }

        public Panel {
            apiPrefixes = List.copyOf(apiPrefixes);
        }

        public boolean matchesApiPath(String apiRelativePath) {
            for (String apiPrefix : apiPrefixes) {
                if (apiRelativePath.equals(apiPrefix) || apiRelativePath.startsWith(apiPrefix + "/")) {
                    return true;
                }
            }
            return false;
        }
    }
}
