package io.github.jdubois.bootui.cli;

import io.github.jdubois.bootui.client.BootUiCatalog;
import io.github.jdubois.bootui.client.BootUiClient;
import io.github.jdubois.bootui.client.BootUiClientException;
import io.github.jdubois.bootui.client.BootUiClientOptions;
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

/**
 * Everything a command needs to do its work: where the application is, where to write, and how to turn an
 * answer into an exit code.
 *
 * <p>The client is created per invocation rather than held, because the global options are only fully known
 * once parsing has finished — {@code bootui beans --url …} sets them after the command has been selected.
 */
final class CliContext {

    private final GlobalOptions options = new GlobalOptions();
    private final ToolManifest manifest;
    private final Map<String, String> environment;
    private final boolean terminal;
    private final PrintWriter out;
    private final PrintWriter err;
    private final Function<BootUiClientOptions, BootUiClient> clientFactory;

    CliContext(
            ToolManifest manifest,
            Map<String, String> environment,
            boolean terminal,
            PrintWriter out,
            PrintWriter err,
            Function<BootUiClientOptions, BootUiClient> clientFactory) {
        this.manifest = manifest;
        this.environment = Map.copyOf(environment);
        this.terminal = terminal;
        this.out = out;
        this.err = err;
        this.clientFactory = clientFactory;
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

    private BootUiClient newClient() {
        return clientFactory.apply(options.toClientOptions(environment));
    }

    /** Calls one tool and reports it. */
    int invokeTool(ToolManifest.Tool tool, String query, Integer limit, String id, String scanId, Integer offset) {
        try (BootUiClient client = newClient()) {
            Map<String, JsonValue> arguments = new LinkedHashMap<>();
            if (query != null) {
                arguments.put("query", JsonValue.of(query));
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
                return ExitCodes.SUCCESS;
            }
            confirmBootUiAnswered(client, result.outcome());
            err.println(describe(client, tool, result.outcome(), result.errorMessage()));
            err.flush();
            return exitCodeFor(result.outcome());
        } catch (BootUiClientException failure) {
            return fail(failure);
        }
    }

    /**
     * Checks that BootUI really is what refused, before we report a refusal.
     *
     * <p>{@code 403} and {@code 404} are the two statuses whose meaning here presupposes BootUI answered:
     * one becomes "the panel is disabled" and the other "no such command". But anything can return them —
     * Spring Security answers {@code 403} to a POST at an unknown path, so a typo in {@code --api-path},
     * or a {@code --url} pointing at some other application, would otherwise be reported as BootUI
     * declining by policy, and exit {@code 2}. A CI gate reads that as "skip", so a misconfigured target
     * would quietly pass instead of failing.
     *
     * <p>Asking for the catalog settles it: if there is no command-line endpoint there, that throws, and
     * its message already names the likely causes. This costs a request only on a path that has failed.
     */
    private void confirmBootUiAnswered(BootUiClient client, ToolOutcome outcome) {
        if (outcome != ToolOutcome.REFUSED_BY_POLICY && outcome != ToolOutcome.UNKNOWN_TOOL) {
            return;
        }
        try {
            client.catalog();
        } catch (BootUiClientException noEndpoint) {
            throw new BootUiClientException(
                    "This is not a BootUI policy refusal. " + noEndpoint.getMessage(), noEndpoint);
        }
    }

    /** Prints what the target instance advertises, which is the authority over the bundled manifest. */
    int listTools() {
        try (BootUiClient client = newClient()) {
            JsonValue document = client.get(CliPaths.CLI);
            if (options.json(terminal)) {
                out.println(document.toJson());
                out.flush();
                return ExitCodes.SUCCESS;
            }
            BootUiCatalog catalog = BootUiCatalog.from(document);
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
        } catch (BootUiClientException failure) {
            return fail(failure);
        }
    }

    /** Reads the MCP Server panel. */
    int mcpStatus() {
        try (BootUiClient client = newClient()) {
            JsonValue status = client.get(CliPaths.MCP_SERVER);
            emit(status, status.toJson());
            return ExitCodes.SUCCESS;
        } catch (BootUiClientException failure) {
            return fail(failure);
        }
    }

    /**
     * Flips the live MCP server state.
     *
     * <p>This is a panel action, so it is refused when the {@code mcp-server} panel is disabled or read-only —
     * the CLI does not get a privileged path to it.
     */
    int mcpToggle(boolean enabled) {
        try (BootUiClient client = newClient()) {
            JsonValue status = client.post(CliPaths.MCP_TOGGLE, "{\"enabled\":" + enabled + "}");
            emit(status, status.toJson());
            return ExitCodes.SUCCESS;
        } catch (BootUiClientException failure) {
            return fail(failure);
        }
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
            // Verbatim when we have it: the server's bytes are the contract-stable form, and re-emitting a
            // parsed tree would quietly normalize number formats and key order.
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

    /**
     * The message for a call BootUI would not run.
     *
     * <p>A tool missing from one stack is the failure most likely to look like a bug, so it says which stacks
     * do advertise it rather than leaving the reader to guess.
     */
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
        // A refusal is a statement about how the target is configured, not a failed request, so a script can
        // tell "BootUI said no" apart from "the call did not work".
        return switch (outcome) {
            case REFUSED_BY_POLICY, ENDPOINT_DISABLED -> ExitCodes.REFUSED;
            default -> ExitCodes.ERROR;
        };
    }

    /**
     * Why the target does not advertise a tool the CLI knows about.
     *
     * <p>The honest answer is usually the panel's own: BootUI does not advertise a tool whose panel is
     * unavailable, and the panel already says why — "No KafkaTemplate bean is available" is the answer, and
     * blaming the BootUI version instead would send the reader to check something that is not wrong. So the
     * running application is asked first, and the two guesses are the fallback rather than the answer.
     *
     * <p>A hint is a courtesy on a path that has already failed, so a panel lookup that does not work is
     * simply skipped: it must never replace the real failure with one about fetching the hint.
     */
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
            // Fall through to the static hints below.
        }
        return tool.onEveryStack()
                ? " The application may be running an older BootUI version."
                : " Only "
                        + String.join(", ", tool.stacks())
                                .toLowerCase(Locale.ROOT)
                                .replace('_', ' ')
                        + " advertise it.";
    }

    /** The BootUI API paths the CLI talks to that are not tool calls. */
    static final class CliPaths {

        static final String CLI = "/cli";
        static final String MCP_SERVER = "/mcp-server";
        static final String MCP_TOGGLE = "/mcp-server/toggle";
        static final String PANELS = "/panels";

        private CliPaths() {}
    }
}
