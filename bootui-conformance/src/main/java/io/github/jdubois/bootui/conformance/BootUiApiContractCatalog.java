package io.github.jdubois.bootui.conformance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Framework-neutral catalog of BootUI's black-box read and action contracts.
 *
 * <p>Read contracts intentionally describe stable DTO families and semantic fields rather than complete
 * payload snapshots. Action contracts list every state-changing adapter route, including infrastructure
 * writes that are deliberately outside panel read-only gating.
 */
public final class BootUiApiContractCatalog {

    private static final Set<Runtime> SPRING = Set.of(Runtime.SPRING_MVC, Runtime.SPRING_WEBFLUX);
    private static final Set<Runtime> ALL = Set.of(Runtime.SPRING_MVC, Runtime.SPRING_WEBFLUX, Runtime.QUARKUS);

    private static final List<ReadContract> READS = List.of(
            read(
                    "overview",
                    "/overview",
                    fields(
                            "applicationName", JsonType.STRING,
                            "frameworkName", JsonType.STRING,
                            "frameworkVersion", JsonType.NULLABLE_STRING,
                            "javaVersion", JsonType.STRING,
                            "activeProfiles", JsonType.ARRAY,
                            "activation", JsonType.OBJECT)),
            read(
                    "health",
                    "/health",
                    fields(
                            "status", JsonType.STRING,
                            "components", JsonType.ARRAY,
                            "available", JsonType.BOOLEAN,
                            "unavailableReason", JsonType.NULLABLE_STRING,
                            "setup", JsonType.ARRAY)),
            capabilityList(
                    "http-sessions",
                    "/http-sessions",
                    "sessions",
                    "totalSessions",
                    fields("actionEnabled", JsonType.BOOLEAN, "valueExposure", JsonType.STRING)),
            inventory("metrics", "/metrics", "metricsAvailable", "meters"),
            memory("live-memory", "/live-memory"),
            memory("jvm-tuning", "/jvm-tuning"),
            read(
                    "heap-dump",
                    "/heap-dump",
                    fields(
                            "hotspotAvailable", JsonType.BOOLEAN,
                            "captureEnabled", JsonType.BOOLEAN,
                            "dumpCount", JsonType.INTEGER,
                            "dumps", JsonType.ARRAY,
                            "topClasses", JsonType.ARRAY)),
            pagedCapabilityList("threads", "/threads", "available", "threads", "totalThreads"),
            advisor("memory", "/memory", "results"),
            read("startup", "/startup", fields("steps", JsonType.ARRAY)),
            advisor("graalvm", "/graalvm", "findings"),
            inventory("scheduled", "/scheduled", "schedulingPresent", "tasks"),
            pagedList(
                    "config",
                    "/config",
                    "properties",
                    fields(
                            "activeProfiles", JsonType.ARRAY,
                            "sources", JsonType.ARRAY,
                            "propertySuggestions", JsonType.ARRAY,
                            "overrideCount", JsonType.INTEGER)),
            read(
                    "profile-diff",
                    "/profile-diff",
                    fields("activeProfiles", JsonType.ARRAY, "profileSources", JsonType.ARRAY)),
            pagedList("loggers", "/loggers", "loggers", fields("availableLevels", JsonType.ARRAY)),
            pagedList("beans", "/beans", "beans", fields("total", JsonType.INTEGER)),
            pagedList(
                    "conditions",
                    "/conditions",
                    "positiveMatches",
                    fields(
                            "negativeMatches", JsonType.ARRAY,
                            "unconditionalClasses", JsonType.ARRAY,
                            "exclusions", JsonType.ARRAY,
                            "counts", JsonType.OBJECT)),
            pagedList("mappings", "/mappings/flat", "mappings", fields("total", JsonType.INTEGER)),
            inventory("data", "/data/repositories", "springDataPresent", "repositories"),
            inventory("flyway", "/flyway/migrations", "flywayPresent", "databases"),
            inventory("liquibase", "/liquibase/changesets", "liquibasePresent", "databases"),
            inventory("database-connection-pools", "/database-connection-pools/pools", "hikariPresent", "pools"),
            advisor("hibernate", "/hibernate", "results"),
            advisor("database-advisor", "/database-advisor", "results"),
            read(
                    "cache",
                    "/cache",
                    fields(
                            "cacheAvailable", JsonType.BOOLEAN,
                            "clearEnabled", JsonType.BOOLEAN,
                            "managerCount", JsonType.INTEGER,
                            "cacheCount", JsonType.INTEGER,
                            "managers", JsonType.ARRAY,
                            "operations", JsonType.ARRAY,
                            "warnings", JsonType.ARRAY)),
            read(
                    "spring-security",
                    "/spring-security",
                    fields(
                            "springSecurityPresent", JsonType.BOOLEAN,
                            "chains", JsonType.ARRAY,
                            "auth", JsonType.OBJECT)),
            advisor("security", "/security", "results"),
            read(
                    "security-logs",
                    "/security-logs",
                    fields(
                            "auditEventsPresent", JsonType.BOOLEAN,
                            "unavailableReason", JsonType.NULLABLE_STRING,
                            "typeSummaries", JsonType.ARRAY,
                            "events", JsonType.ARRAY,
                            "page", JsonType.OBJECT)),
            advisor("pentesting", "/pentesting", "findings"),
            read(
                    "ai",
                    "/ai/overview",
                    fields(
                            "enabled", JsonType.BOOLEAN,
                            "totalChats", JsonType.INTEGER,
                            "tokensByModel", JsonType.OBJECT,
                            "callsByModel", JsonType.OBJECT,
                            "recent", JsonType.ARRAY)),
            read(
                    "traces",
                    "/traces",
                    fields(
                            "enabled", JsonType.BOOLEAN,
                            "retained", JsonType.INTEGER,
                            "capacity", JsonType.INTEGER,
                            "traces", JsonType.ARRAY)),
            array("log-tail", "/log-tail/recent"),
            capabilityList("exceptions", "/exceptions", "groups", "totalExceptions", Map.of()),
            pagedList(
                    "http-exchanges",
                    "/http-exchanges",
                    "exchanges",
                    fields(
                            "total", JsonType.INTEGER,
                            "recorded", JsonType.INTEGER,
                            "hiddenSelf", JsonType.INTEGER,
                            "unavailableReason", JsonType.NULLABLE_STRING)),
            advisor("architecture", "/architecture", "results"),
            advisor("rest-api", "/rest-api", "results"),
            read(
                    "vulnerabilities",
                    "/vulnerabilities",
                    fields(
                            "scanningEnabled", JsonType.BOOLEAN,
                            "total", JsonType.INTEGER,
                            "vulnerable", JsonType.INTEGER,
                            "severityCounts", JsonType.ARRAY,
                            "scan", JsonType.OBJECT,
                            "dependencies", JsonType.ARRAY)),
            read(
                    "dev-services",
                    "/dev-services",
                    fields(
                            "dockerComposePresent", JsonType.BOOLEAN,
                            "testcontainersPresent", JsonType.BOOLEAN,
                            "snapshotTimestamp", JsonType.NUMBER,
                            "total", JsonType.INTEGER,
                            "services", JsonType.ARRAY,
                            "warnings", JsonType.ARRAY)),
            read(
                    "devtools",
                    "/devtools",
                    fields(
                            "restartAvailable", JsonType.BOOLEAN,
                            "restartUnavailableReason", JsonType.NULLABLE_STRING,
                            "restartPending", JsonType.BOOLEAN,
                            "liveReloadAvailable", JsonType.BOOLEAN,
                            "liveReloadUnavailableReason", JsonType.NULLABLE_STRING)),
            agent("copilot", "/copilot/dashboard"),
            agent("claude-code", "/claude-code/dashboard"),
            read(
                    "github",
                    "/github",
                    fields(
                            "available", JsonType.BOOLEAN,
                            "unavailableReason", JsonType.NULLABLE_STRING,
                            "connected", JsonType.BOOLEAN,
                            "status", JsonType.STRING,
                            "metrics", JsonType.ARRAY,
                            "warnings", JsonType.ARRAY)),
            advisor("spring", "/spring", "results"),
            advisor("crac", "/crac", "findings"),
            capture("sql-trace", "/sql-trace", "entries"),
            capture("transactions", "/transactions", "entries"),
            capture("rest-client-trace", "/rest-client-trace", "entries"),
            read(
                    "mcp-server",
                    "/mcp-server",
                    fields(
                            "enabled", JsonType.BOOLEAN,
                            "configuredMode", JsonType.STRING,
                            "overridden", JsonType.BOOLEAN,
                            "endpoint", JsonType.STRING,
                            "toolCount", JsonType.INTEGER,
                            "tools", JsonType.ARRAY)),
            read(
                    "activity",
                    "/activity",
                    fields(
                            "available", JsonType.BOOLEAN,
                            "entries", JsonType.ARRAY,
                            "typeCounts", JsonType.OBJECT,
                            "sources", JsonType.ARRAY,
                            "warnings", JsonType.ARRAY,
                            "pageInfo", JsonType.NULLABLE_OBJECT,
                            "persistenceOption", JsonType.NULLABLE_OBJECT)),
            capabilityList("email", "/email", "messages", "total", fields("devTrapEnabled", JsonType.BOOLEAN)),
            capture("kafka", "/kafka", "messages"),
            capture("rabbitmq", "/rabbitmq", "messages"),
            capture("jms", "/jms", "messages"));

    private static final List<ActionContract> ACTIONS = buildActions();

    private BootUiApiContractCatalog() {}

    public static List<ReadContract> reads() {
        return READS;
    }

    public static Map<String, ReadContract> readsByPanel() {
        Map<String, ReadContract> contracts = new LinkedHashMap<>();
        READS.forEach(contract -> contracts.put(contract.panelId(), contract));
        return Map.copyOf(contracts);
    }

    public static List<ActionContract> actions() {
        return ACTIONS;
    }

    public static List<ActionContract> actions(Runtime runtime) {
        return ACTIONS.stream()
                .filter(action -> action.runtimes().contains(runtime))
                .toList();
    }

    private static List<ActionContract> buildActions() {
        List<ActionContract> actions = new ArrayList<>();

        springMvc(actions, "http-sessions.clear", "http-sessions", "POST", "/http-sessions/session-key/clear");
        springMvc(
                actions, "http-sessions.invalidate", "http-sessions", "POST", "/http-sessions/session-key/invalidate");
        all(actions, "heap-dump.capture", "heap-dump", "POST", "/heap-dump/capture");
        all(actions, "heap-dump.analyze", "heap-dump", "POST", "/heap-dump/analyze");
        all(actions, "heap-dump.delete", "heap-dump", "POST", "/heap-dump/delete");
        all(actions, "threads.download", "threads", "POST", "/threads/download");
        all(actions, "memory.scan", "memory", "POST", "/memory/scan");
        spring(actions, "graalvm.scan.cancel", "graalvm", "POST", "/graalvm/scan/cancel");
        spring(actions, "graalvm.scan", "graalvm", "POST", "/graalvm/scan");
        spring(actions, "graalvm.install", "graalvm", "POST", "/graalvm/install");
        spring(actions, "graalvm.install-all", "graalvm", "POST", "/graalvm/install/all");
        spring(actions, "graalvm.dockerfile.install", "graalvm", "POST", "/graalvm/dockerfile/install");
        spring(actions, "config.override.put", "config", "POST", "/config/overrides");
        spring(actions, "config.override.delete", "config", "DELETE", "/config/overrides/conformance.key");
        all(actions, "loggers.level", "loggers", "POST", "/loggers/io.github.jdubois.bootui.conformance");
        all(actions, "security.scan", "security", "POST", "/security/scan");
        all(actions, "pentesting.scan", "pentesting", "POST", "/pentesting/scan");
        all(actions, "hibernate.scan", "hibernate", "POST", "/hibernate/scan");
        all(actions, "database-advisor.scan", "database-advisor", "POST", "/database-advisor/scan");
        all(actions, "cache.clear", "cache", "POST", "/cache/clear");
        all(actions, "traces.clear", "traces", "DELETE", "/traces");
        all(actions, "exceptions.clear", "exceptions", "DELETE", "/exceptions");
        all(actions, "exceptions.status", "exceptions", "POST", "/exceptions/conformance-unknown-fingerprint/status");
        all(actions, "http-probe.execute", "http-probe", "POST", "/http-probe");
        all(actions, "architecture.scan", "architecture", "POST", "/architecture/scan");
        all(actions, "vulnerabilities.scan", "vulnerabilities", "POST", "/vulnerabilities/scan");
        spring(actions, "devtools.livereload", "devtools", "POST", "/devtools/livereload");
        spring(actions, "devtools.restart", "devtools", "POST", "/devtools/restart");
        spring(actions, "dev-services.restart", "dev-services", "POST", "/dev-services/services/conformance/restart");
        quarkus(actions, "dev-services.restart", "dev-services", "POST", "/dev-services/conformance/restart");
        all(actions, "flyway.migrate", "flyway", "POST", "/flyway/migrate");
        all(actions, "flyway.clean", "flyway", "POST", "/flyway/clean");
        all(actions, "liquibase.update", "liquibase", "POST", "/liquibase/update");
        all(actions, "github.refresh", "github", "POST", "/github/refresh");
        all(actions, "rest-api.scan", "rest-api", "POST", "/rest-api/scan");
        all(actions, "spring.scan", "spring", "POST", "/spring/scan");
        spring(actions, "crac.scan", "crac", "POST", "/crac/scan");
        spring(actions, "crac.dockerfile.install", "crac", "POST", "/crac/dockerfile/install");
        spring(actions, "crac.entrypoint.install", "crac", "POST", "/crac/entrypoint/install");
        spring(actions, "crac.install-all", "crac", "POST", "/crac/install/all");
        all(actions, "sql-trace.clear", "sql-trace", "POST", "/sql-trace/clear");
        all(actions, "sql-trace.recording", "sql-trace", "POST", "/sql-trace/recording");
        spring(actions, "transactions.clear", "transactions", "POST", "/transactions/clear");
        spring(actions, "transactions.recording", "transactions", "POST", "/transactions/recording");
        all(actions, "rest-client-trace.clear", "rest-client-trace", "POST", "/rest-client-trace/clear");
        all(actions, "rest-client-trace.recording", "rest-client-trace", "POST", "/rest-client-trace/recording");
        all(actions, "mcp-server.toggle", "mcp-server", "POST", "/mcp-server/toggle");
        all(actions, "activity.use-existing-datasource", "activity", "POST", "/activity/use-existing-datasource");
        all(actions, "email.clear", "email", "DELETE", "/email");
        all(actions, "kafka.clear", "kafka", "DELETE", "/kafka");
        all(actions, "rabbitmq.clear", "rabbitmq", "DELETE", "/rabbitmq");
        spring(actions, "jms.clear", "jms", "DELETE", "/jms");

        infrastructure(actions, "dismissed-rules.dismiss", "POST", "/dismissed-rules/conformance-rule", ALL, true);
        infrastructure(actions, "dismissed-rules.restore", "DELETE", "/dismissed-rules/conformance-rule", ALL, true);
        infrastructure(actions, "mcp.bridge", "POST", "/mcp", ALL, false);
        infrastructure(actions, "otlp.ingest", "POST", "/otlp/v1/traces", SPRING, false);
        return List.copyOf(actions);
    }

    private static ReadContract read(String panelId, String path, Map<String, JsonType> fields) {
        return new ReadContract(panelId, path, JsonType.OBJECT, fields);
    }

    private static ReadContract array(String panelId, String path) {
        return new ReadContract(panelId, path, JsonType.ARRAY, Map.of());
    }

    private static ReadContract advisor(String panelId, String path, String resultField) {
        return read(
                panelId,
                path,
                fields(
                        "localOnly",
                        JsonType.BOOLEAN,
                        "disclaimer",
                        JsonType.STRING,
                        "severityCounts",
                        JsonType.ARRAY,
                        "scan",
                        JsonType.OBJECT,
                        "scan.status",
                        JsonType.STRING,
                        resultField,
                        JsonType.ARRAY));
    }

    private static ReadContract memory(String panelId, String path) {
        return read(
                panelId,
                path,
                fields(
                        "heap", JsonType.OBJECT,
                        "nonHeap", JsonType.OBJECT,
                        "pools", JsonType.ARRAY,
                        "jvmInputArguments", JsonType.ARRAY,
                        "suggestedJvmOptions", JsonType.STRING,
                        "calculation", JsonType.OBJECT));
    }

    private static ReadContract inventory(String panelId, String path, String availabilityField, String itemsField) {
        return read(
                panelId,
                path,
                fields(availabilityField, JsonType.BOOLEAN, "total", JsonType.INTEGER, itemsField, JsonType.ARRAY));
    }

    private static ReadContract capabilityList(
            String panelId, String path, String itemsField, String totalField, Map<String, JsonType> additionalFields) {
        Map<String, JsonType> required = new LinkedHashMap<>(fields(
                "available",
                JsonType.BOOLEAN,
                "unavailableReason",
                JsonType.NULLABLE_STRING,
                totalField,
                JsonType.NUMBER,
                itemsField,
                JsonType.ARRAY));
        required.putAll(additionalFields);
        return read(panelId, path, required);
    }

    private static ReadContract pagedCapabilityList(
            String panelId, String path, String availabilityField, String itemsField, String totalField) {
        return read(
                panelId,
                path,
                fields(
                        availabilityField,
                        JsonType.BOOLEAN,
                        "unavailableReason",
                        JsonType.NULLABLE_STRING,
                        totalField,
                        JsonType.INTEGER,
                        itemsField,
                        JsonType.ARRAY,
                        "page",
                        JsonType.OBJECT));
    }

    private static ReadContract pagedList(
            String panelId, String path, String itemsField, Map<String, JsonType> additionalFields) {
        Map<String, JsonType> required =
                new LinkedHashMap<>(fields(itemsField, JsonType.ARRAY, "page", JsonType.OBJECT));
        required.putAll(additionalFields);
        return read(panelId, path, required);
    }

    private static ReadContract capture(String panelId, String path, String itemsField) {
        return read(
                panelId,
                path,
                fields(
                        "available",
                        JsonType.BOOLEAN,
                        "unavailableReason",
                        JsonType.NULLABLE_STRING,
                        "capturing",
                        JsonType.BOOLEAN,
                        "totalCaptured",
                        JsonType.NUMBER,
                        itemsField,
                        JsonType.ARRAY));
    }

    private static ReadContract agent(String panelId, String path) {
        return read(
                panelId,
                path,
                fields(
                        "available", JsonType.BOOLEAN,
                        "unavailableReason", JsonType.NULLABLE_STRING,
                        "sessionCount", JsonType.INTEGER,
                        "eventCount", JsonType.INTEGER,
                        "recentSessions", JsonType.ARRAY,
                        "warnings", JsonType.ARRAY));
    }

    private static Map<String, JsonType> fields(Object... pairs) {
        Map<String, JsonType> fields = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            fields.put((String) pairs[index], (JsonType) pairs[index + 1]);
        }
        return Map.copyOf(fields);
    }

    private static void all(List<ActionContract> actions, String id, String panelId, String method, String path) {
        actions.add(new ActionContract(id, panelId, method, path, ALL, true));
    }

    private static void spring(List<ActionContract> actions, String id, String panelId, String method, String path) {
        actions.add(new ActionContract(id, panelId, method, path, SPRING, true));
    }

    private static void springMvc(List<ActionContract> actions, String id, String panelId, String method, String path) {
        actions.add(new ActionContract(id, panelId, method, path, Set.of(Runtime.SPRING_MVC), true));
    }

    private static void quarkus(List<ActionContract> actions, String id, String panelId, String method, String path) {
        actions.add(new ActionContract(id, panelId, method, path, Set.of(Runtime.QUARKUS), true));
    }

    private static void infrastructure(
            List<ActionContract> actions,
            String id,
            String method,
            String path,
            Set<Runtime> runtimes,
            boolean blockedByGlobalReadOnly) {
        actions.add(new ActionContract(id, null, method, path, runtimes, blockedByGlobalReadOnly));
    }

    public enum Runtime {
        SPRING_MVC,
        SPRING_WEBFLUX,
        QUARKUS
    }

    public enum JsonType {
        STRING,
        BOOLEAN,
        INTEGER,
        NUMBER,
        ARRAY,
        OBJECT,
        NULLABLE_STRING,
        NULLABLE_OBJECT
    }

    public record ReadContract(
            String panelId, String relativePath, JsonType rootType, Map<String, JsonType> requiredFields) {

        public ReadContract {
            requiredFields = Map.copyOf(requiredFields);
        }
    }

    public record ActionContract(
            String id,
            String panelId,
            String method,
            String relativePath,
            Set<Runtime> runtimes,
            boolean blockedByGlobalReadOnly) {

        public ActionContract {
            runtimes = Set.copyOf(runtimes);
        }
    }
}
