package io.github.jdubois.bootui.engine.mcp;

import io.github.jdubois.bootui.core.dto.LowFootprintStatus;
import io.github.jdubois.bootui.core.dto.SuspendedComponentDto;
import io.github.jdubois.bootui.engine.memory.LowFootprintService;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Low-footprint controls and capture provenance shared by MCP and CLI on every request stack. */
public final class McpLowFootprint {

    public static final String RESUME_HINT = "Recording is held by low-footprint mode, not a quiet application. "
            + "Call disable_low_footprint (bootui low-footprint disable) to resume; cleared data is gone. "
            + "Native statistics and Java-agent instrumentation may still run.";

    private static final Set<String> BLOCKED = Set.of(
            "resume_sql_trace_recording",
            "resume_transaction_recording",
            "resume_rest_client_recording",
            "start_method_probe");

    private static final Map<String, Set<String>> PANEL_COMPONENTS = Map.ofEntries(
            Map.entry(BootUiPanels.ACTIVITY, Set.of("runtime-journal", "live-activity")),
            Map.entry(BootUiPanels.RUNTIME_INSIGHTS, Set.of("runtime-journal", "resource-sampler", "gc-events")),
            Map.entry(BootUiPanels.EXCEPTIONS, Set.of("exceptions")),
            Map.entry(BootUiPanels.SECURITY_LOGS, Set.of("security-events")),
            Map.entry(BootUiPanels.SQL_TRACE, Set.of("sql-trace")),
            Map.entry(BootUiPanels.TRANSACTIONS, Set.of("transactions")),
            Map.entry(BootUiPanels.REST_CLIENT_TRACE, Set.of("rest-client-trace")),
            Map.entry(BootUiPanels.HTTP_EXCHANGES, Set.of("http-exchanges")),
            Map.entry(BootUiPanels.TRACES, Set.of("telemetry")),
            Map.entry(BootUiPanels.AI, Set.of("telemetry")),
            Map.entry(BootUiPanels.LOG_TAIL, Set.of("log-tail")),
            Map.entry(BootUiPanels.EMAIL, Set.of("email")),
            Map.entry(BootUiPanels.KAFKA, Set.of("kafka")),
            Map.entry(BootUiPanels.RABBITMQ, Set.of("rabbit")),
            Map.entry(BootUiPanels.JMS, Set.of("jms")),
            Map.entry(BootUiPanels.WEBSOCKETS, Set.of("websockets")),
            Map.entry(BootUiPanels.CACHE, Set.of("cache")),
            Map.entry(BootUiPanels.SCHEDULED, Set.of("scheduled")),
            Map.entry(BootUiPanels.FAULT_TOLERANCE, Set.of("fault-tolerance")),
            Map.entry(BootUiPanels.CODE_PATHS, Set.of("runtime-journal")),
            Map.entry(BootUiPanels.CODE_INVENTORY, Set.of("runtime-journal")),
            Map.entry(BootUiPanels.SIDE_EFFECTS, Set.of("runtime-journal")));

    private McpLowFootprint() {}

    /** A transport-only carrier; codecs merge metadata alongside the original object's fields. */
    public record Result(Object payload, Map<String, Object> lowFootprint, boolean capturePaused) {}

    /** Whether a tool reads buffered evidence, including the global context carried by Overview. */
    public static boolean affected(String tool) {
        if (tool.equals("get_method_probe") || tool.equals("start_method_probe")) {
            return false;
        }
        return McpToolCatalog.byName(tool)
                .map(entry ->
                        entry.panelId().equals(BootUiPanels.OVERVIEW) || PANEL_COMPONENTS.containsKey(entry.panelId()))
                .orElse(false);
    }

    /** Component IDs with a deliberate mapping, including the Spring-only internal correlation bridge. */
    public static Set<String> classifiedComponents() {
        Set<String> ids = new java.util.HashSet<>(
                Set.of("idle-buffers", "RequestCorrelationRegistry", "SecurityEventCorrelationRegistry"));
        PANEL_COMPONENTS.values().forEach(ids::addAll);
        return Set.copyOf(ids);
    }

    public static List<McpTool> tools(LowFootprintService service, McpToolCatalog.Stack stack) {
        return List.of(
                control("get_low_footprint", stack, args -> service.status()),
                control(
                        "enable_low_footprint",
                        stack,
                        args -> McpControlAcks.lowFootprint("enabled", service.enable())),
                control(
                        "disable_low_footprint",
                        stack,
                        args -> McpControlAcks.lowFootprint("disabled", service.disable())));
    }

    private static McpTool control(String name, McpToolCatalog.Stack stack, Function<McpArguments, Object> handler) {
        McpToolCatalog.Entry entry = McpToolCatalog.require(name, stack);
        return new McpTool(
                name,
                stack == McpToolCatalog.Stack.QUARKUS
                        ? McpToolDescriptions.quarkus(name)
                        : McpToolDescriptions.spring(name),
                entry.schema(),
                entry.panelId(),
                entry.action(),
                handler);
    }

    /** Decorates the handler, so every dispatch path applies the same hold and metadata policy. */
    public static McpTool decorate(McpTool tool, LowFootprintService service) {
        return new McpTool(tool.name(), tool.description(), tool.schema(), tool.panelId(), tool.action(), args -> {
            if (BLOCKED.contains(tool.name())) {
                synchronized (service) {
                    if (service.active()) {
                        throw new McpToolClientException(409, "Cannot run " + tool.name() + ". " + RESUME_HINT);
                    }
                    return tool.invoke(args);
                }
            }
            if (!affected(tool.name())) {
                return tool.invoke(args);
            }
            LowFootprintStatus before = activeStatus(service);
            Object payload = tool.invoke(args);
            LowFootprintStatus status = before != null ? before : activeStatus(service);
            return status == null ? payload : annotate(tool, payload, status);
        });
    }

    private static LowFootprintStatus activeStatus(LowFootprintService service) {
        if (!service.active()) {
            return null;
        }
        LowFootprintStatus status = service.status();
        return status.active() ? status : null;
    }

    private static Object annotate(McpTool tool, Object payload, LowFootprintStatus status) {
        Set<String> ids = PANEL_COMPONENTS.getOrDefault(tool.panelId(), Set.of());
        List<SuspendedComponentDto> components = status.components().stream()
                .filter(component -> tool.panelId().equals(BootUiPanels.OVERVIEW) || ids.contains(component.id()))
                .toList();
        if (components.isEmpty() && !tool.panelId().equals(BootUiPanels.OVERVIEW)) {
            return payload;
        }
        boolean paused = components.stream().anyMatch(SuspendedComponentDto::suspended);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("active", true);
        metadata.put("paused", paused);
        metadata.put("sinceEpochMillis", status.sinceEpochMillis());
        metadata.put(
                "components", components.stream().map(SuspendedComponentDto::id).toList());
        List<String> warnings = new ArrayList<>();
        components.stream()
                .filter(component -> component.failure() != null)
                .forEach(component -> warnings.add(component.id() + ": " + component.failure()));
        if (status.agentAttached()) {
            warnings.add(LowFootprintService.AGENT_WARNING);
        }
        metadata.put("warnings", List.copyOf(warnings));
        metadata.put(
                "hint",
                paused
                        ? RESUME_HINT
                        : "Low-footprint mode is active, but relevant capture is not known to be paused. Inspect warnings "
                                + "and get_low_footprint before relying on capture.");
        return new Result(payload, java.util.Collections.unmodifiableMap(metadata), paused);
    }
}
