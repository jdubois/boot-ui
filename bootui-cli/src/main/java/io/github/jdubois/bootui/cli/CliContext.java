package io.github.jdubois.bootui.cli;

import io.github.jdubois.bootui.client.BootUiCatalog;
import io.github.jdubois.bootui.client.BootUiClient;
import io.github.jdubois.bootui.client.BootUiClientException;
import io.github.jdubois.bootui.client.BootUiClientOptions;
import io.github.jdubois.bootui.client.JsonParseException;
import io.github.jdubois.bootui.client.JsonValue;
import io.github.jdubois.bootui.client.JsonWriter;
import io.github.jdubois.bootui.client.ToolOutcome;
import io.github.jdubois.bootui.client.ToolResult;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

final class CliContext {

    private static final String UNKNOWN_MCP_OUTCOME =
            "The MCP action outcome is unknown: no valid BootUI MCP server status acknowledgement was received. The action was not retried.";

    private final GlobalOptions options = new GlobalOptions();
    private final ToolManifest manifest;
    private final Map<String, String> environment;
    private final boolean terminal;
    private final PrintWriter out;
    private final PrintWriter err;
    private final Function<BootUiClientOptions, BootUiClient> clientFactory;
    private final Function<BootUiClientOptions, BootUiClient> directClientFactory;

    CliContext(
            ToolManifest manifest,
            Map<String, String> environment,
            boolean terminal,
            PrintWriter out,
            PrintWriter err,
            Function<BootUiClientOptions, BootUiClient> clientFactory) {
        this(manifest, environment, terminal, out, err, clientFactory, clientFactory);
    }

    CliContext(
            ToolManifest manifest,
            Map<String, String> environment,
            boolean terminal,
            PrintWriter out,
            PrintWriter err,
            Function<BootUiClientOptions, BootUiClient> clientFactory,
            Function<BootUiClientOptions, BootUiClient> directClientFactory) {
        this.manifest = manifest;
        this.environment = Map.copyOf(environment);
        this.terminal = terminal;
        this.out = out;
        this.err = err;
        this.clientFactory = clientFactory;
        this.directClientFactory = directClientFactory;
    }

    GlobalOptions options() {
        return options;
    }

    ToolManifest manifest() {
        return manifest;
    }

    PrintWriter out() {
        return out;
    }

    PrintWriter err() {
        return err;
    }

    Map<String, String> environment() {
        return environment;
    }

    boolean terminal() {
        return terminal;
    }

    private int withClient(Function<BootUiClient, Integer> action) {
        try (BootUiClient client = newClient()) {
            return action.apply(client);
        } catch (BootUiClientException failure) {
            return fail(failure);
        }
    }

    private BootUiClient newClient() {
        if (options.app() != null) {
            if (options.hasExplicitUrl() || options.hasExplicitApiPath()) {
                throw new BootUiClientException("Choose --app or --url/--api-path, not both.");
            }
            BootUiInstances.Instance instance = resolveTargetInstance();
            return directClientFactory.apply(new BootUiClientOptions(
                    instance.baseUrl(), instance.apiPath(), options.resolvedToken(environment), options.timeout()));
        }
        return clientFactory.apply(options.toClientOptions(environment));
    }

    BootUiInstances.Instance resolveTargetInstance() {
        return BootUiInstances.resolve(
                BootUiInstances.resolveInstancesDir(environment),
                options.app(),
                options.timeout(),
                directClientFactory);
    }

    int listInstances() {
        List<BootUiInstances.Instance> instances =
                BootUiInstances.list(BootUiInstances.resolveInstancesDir(environment));
        Map<String, JsonValue> payload = new LinkedHashMap<>();
        payload.put(
                "instances",
                JsonValue.array(instances.stream()
                        .map(BootUiInstances.Instance::toJsonValue)
                        .toList()));
        JsonValue document = JsonValue.object(payload);
        if (options.json(terminal)) {
            out.println(document.toJson());
            out.flush();
            return ExitCodes.SUCCESS;
        }
        if (instances.isEmpty()) {
            out.println("No running BootUI instances found.");
            out.flush();
            return ExitCodes.SUCCESS;
        }
        emit(document, null);
        return ExitCodes.SUCCESS;
    }

    int invokeTool(
            ToolManifest.Tool tool,
            String query,
            Integer limit,
            String id,
            String scanId,
            Integer offset,
            FailOn.Criterion failOn) {
        return withClient(client -> {
            Map<String, JsonValue> arguments = new LinkedHashMap<>();
            String effectiveQuery = query;
            if (failOn == FailOn.Criterion.INSUFFICIENT
                    && "get_runtime_insights".equals(tool.name())
                    && effectiveQuery == null) {
                effectiveQuery = "all";
            }
            if (effectiveQuery != null) {
                arguments.put("query", JsonValue.of(effectiveQuery));
            }
            if (limit != null) {
                arguments.put("limit", JsonValue.of(limit.longValue()));
            }
            if (id != null) {
                arguments.put("id", JsonValue.of(id));
            }
            if (scanId != null) {
                arguments.put("scanId", JsonValue.of(scanId));
            }
            if (offset != null) {
                arguments.put("offset", JsonValue.of(offset.longValue()));
            }
            ToolResult result = client.invoke(tool.name(), arguments);
            if (result.successful()) {
                emit(result.payload(), result.rawBody());
                if (failOn == null) {
                    return ExitCodes.SUCCESS;
                }
                FailOn.Verdict verdict = FailOn.evaluate(result.payload(), failOn, tool.name());
                if (!verdict.message().isBlank()) {
                    err.println(verdict.message());
                    err.flush();
                }
                return verdict.exitCode();
            }
            ToolOutcome outcome = confirmBootUiAnswered(client, result);
            err.println(describe(client, tool, outcome, result.errorMessage()));
            err.flush();
            return exitCodeFor(outcome);
        });
    }

    private ToolOutcome confirmBootUiAnswered(BootUiClient client, ToolResult result) {
        ToolOutcome outcome = result.outcome();
        if (outcome != ToolOutcome.REFUSED_BY_POLICY && outcome != ToolOutcome.UNKNOWN_TOOL && result.status() != 503) {
            return outcome;
        }
        try {
            BootUiCatalog catalog = client.catalog();
            return result.status() == 503 && !catalog.enabled() ? ToolOutcome.ENDPOINT_DISABLED : outcome;
        } catch (BootUiClientException noEndpoint) {
            String context = result.status() == 503
                    ? "Cannot confirm BootUI command-line endpoint state after HTTP 503. "
                    : "This is not a BootUI policy refusal. ";
            throw new BootUiClientException(context + noEndpoint.getMessage(), noEndpoint);
        }
    }

    int listTools() {
        return withClient(client -> {
            JsonValue document = client.get(CliPaths.CLI);
            BootUiCatalog catalog = BootUiCatalog.from(document);
            if (options.json(terminal)) {
                out.println(document.toJson());
                out.flush();
                return ExitCodes.SUCCESS;
            }
            List<JsonValue> rows = new ArrayList<>();
            for (BootUiCatalog.CatalogTool tool : catalog.tools()) {
                ToolManifest.Tool known = manifest.byName(tool.name());
                Map<String, JsonValue> row = new LinkedHashMap<>();
                row.put("command", JsonValue.of(known == null ? "-" : known.command()));
                row.put("tool", JsonValue.of(tool.name()));
                row.put("panel", JsonValue.of(tool.panel()));
                row.put(
                        "arguments",
                        JsonValue.of(tool.arguments().isEmpty() ? "-" : String.join(", ", tool.arguments())));
                row.put("status", JsonValue.of(status(tool)));
                rows.add(JsonValue.object(row));
            }
            Map<String, JsonValue> summary = new LinkedHashMap<>();
            summary.put("endpoint", JsonValue.of(catalog.endpoint()));
            summary.put("serverVersion", JsonValue.of(catalog.serverVersion()));
            summary.put("enabled", JsonValue.of(catalog.enabled()));
            summary.put("maxResults", JsonValue.of(catalog.maxResults()));
            summary.put("tools", JsonValue.array(rows));
            emit(JsonValue.object(summary), null);
            return ExitCodes.SUCCESS;
        });
    }

    int mcpStatus() {
        return withClient(client -> emitMcpStatus(client.get(CliPaths.MCP_SERVER), false));
    }

    int mcpToggle(boolean enabled) {
        return withClient(client -> {
            JsonValue status;
            try {
                status = client.post(CliPaths.MCP_TOGGLE, "{\"enabled\":" + enabled + "}");
            } catch (BootUiClientException failure) {
                if (failure.getCause() instanceof JsonParseException) {
                    throw new BootUiClientException(UNKNOWN_MCP_OUTCOME, failure);
                }
                throw failure;
            }
            return emitMcpStatus(status, true);
        });
    }

    private int emitMcpStatus(JsonValue status, boolean action) {
        JsonValue tools = status.get("tools");
        boolean valid = status.isObject()
                && "bootui".equals(status.get("serverName").asString(null))
                && "http".equals(status.get("transport").asString(null))
                && status.get("enabled").isBoolean()
                && tools.isArray()
                && tools.values().stream()
                        .allMatch(tool -> tool.isObject()
                                && !tool.get("name").asString("").isBlank()
                                && tool.get("action").isBoolean());
        if (!valid) {
            throw new BootUiClientException(
                    action
                            ? UNKNOWN_MCP_OUTCOME
                            : "The target did not return a valid BootUI MCP server status report.");
        }
        emit(status, status.toJson());
        return ExitCodes.SUCCESS;
    }

    private static String status(BootUiCatalog.CatalogTool tool) {
        if (!tool.panelEnabled()) {
            return "panel disabled";
        }
        if (tool.action() && tool.panelReadOnly()) {
            return "read-only";
        }
        return tool.action() ? "action" : "ready";
    }

    private void emit(JsonValue payload, String rawBody) {
        if (options.json(terminal)) {
            out.println(rawBody == null || rawBody.isBlank() ? JsonWriter.pretty(payload) : rawBody);
        } else {
            out.println(new TextRenderer(options.color(terminal, environment)).render(payload));
        }
        out.flush();
    }

    private int fail(BootUiClientException failure) {
        err.println(failure.getMessage());
        if (options.verbose() && failure.getCause() != null) {
            failure.printStackTrace(err);
        }
        err.flush();
        return ExitCodes.ERROR;
    }

    private String describe(BootUiClient client, ToolManifest.Tool tool, ToolOutcome outcome, String message) {
        return switch (outcome) {
            case UNKNOWN_TOOL ->
                "This application does not expose '" + tool.name() + "'." + unknownToolHint(client, tool);
            case REFUSED_BY_POLICY -> message + " (panel '" + tool.panel() + "')";
            case UNAUTHENTICATED ->
                message
                        + ". A non-loopback --url needs --token, and BootUI only answers requests it "
                        + "considers local.";
            case ENDPOINT_DISABLED ->
                "The BootUI command-line endpoint is disabled on this application. "
                        + "Set bootui.cli.enabled=true to allow it.";
            default -> message;
        };
    }

    private static int exitCodeFor(ToolOutcome outcome) {
        return switch (outcome) {
            case REFUSED_BY_POLICY, ENDPOINT_DISABLED -> ExitCodes.REFUSED;
            default -> ExitCodes.ERROR;
        };
    }

    private String unknownToolHint(BootUiClient client, ToolManifest.Tool tool) {
        try {
            JsonValue document = client.get(CliPaths.PANELS);
            JsonValue panels = document.get("panels");
            for (JsonValue panel : panels.size() > 0 ? panels.values() : document.values()) {
                if (!tool.panel().equals(panel.get("id").asString(null))) {
                    continue;
                }
                if (panel.get("available").asBoolean(true)) {
                    break;
                }
                String reason = panel.get("unavailableReason").asString("");
                return reason.isBlank()
                        ? " Its '" + tool.panel() + "' panel is not available in this application."
                        : " Its '" + tool.panel() + "' panel is not available in this application: " + reason;
            }
        } catch (RuntimeException ignored) {
        }
        return tool.onEveryStack()
                ? " The application may be running an older BootUI version."
                : " Only "
                        + String.join(", ", tool.stacks())
                                .toLowerCase(Locale.ROOT)
                                .replace('_', ' ')
                        + " advertise it.";
    }

    static final class CliPaths {

        static final String CLI = "/cli";
        static final String MCP_SERVER = "/mcp-server";
        static final String MCP_TOGGLE = "/mcp-server/toggle";
        static final String PANELS = "/panels";

        private CliPaths() {}
    }
}
