package io.github.jdubois.bootui.client;

import java.util.List;

/**
 * What a running instance says it exposes, read from {@code GET /bootui/api/cli}.
 *
 * <p>This is the authority at runtime, not the manifest a client was built with: it reflects the target
 * application's stack and its live panel toggles, so a client from one BootUI version reports the truth
 * about an application running another.
 *
 * @param enabled whether the endpoint accepts tool calls
 * @param serverName the server identifier BootUI advertises
 * @param serverVersion the BootUI version of the target application
 * @param endpoint the absolute path the endpoint is mounted at
 * @param maxResults the result cap the instance applies
 * @param tools the advertised tools
 */
public record BootUiCatalog(
        boolean enabled,
        String serverName,
        String serverVersion,
        String endpoint,
        int maxResults,
        List<CatalogTool> tools) {

    public BootUiCatalog {
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    /** The named tool, or {@code null} when this instance does not advertise it. */
    public CatalogTool tool(String name) {
        return tools.stream()
                .filter(tool -> tool.name().equals(name))
                .findFirst()
                .orElse(null);
    }

    /** Parses the endpoint's status document. */
    public static BootUiCatalog from(JsonValue json) {
        List<CatalogTool> tools =
                json.get("tools").values().stream().map(CatalogTool::from).toList();
        return new BootUiCatalog(
                json.get("enabled").asBoolean(false),
                json.get("serverName").asString(""),
                json.get("serverVersion").asString(""),
                json.get("endpoint").asString(""),
                json.get("maxResults").asInt(0),
                tools);
    }

    /**
     * One advertised tool.
     *
     * @param name the MCP tool name
     * @param description the human-readable description
     * @param panel the panel backing it
     * @param action whether it changes state
     * @param schema the argument schema name: {@code NONE}, {@code LIMIT}, {@code QUERY_LIMIT}, or {@code ID}
     * @param arguments the accepted argument names, in the order the schema declares them
     * @param panelEnabled whether the backing panel is currently enabled
     * @param panelReadOnly whether the backing panel is currently read-only
     */
    public record CatalogTool(
            String name,
            String description,
            String panel,
            boolean action,
            String schema,
            List<String> arguments,
            boolean panelEnabled,
            boolean panelReadOnly) {

        public CatalogTool {
            arguments = arguments == null ? List.of() : List.copyOf(arguments);
        }

        /** Whether this instance would currently refuse the tool: its panel is off, or it is an action on a read-only panel. */
        public boolean refused() {
            return !panelEnabled || (action && panelReadOnly);
        }

        static CatalogTool from(JsonValue json) {
            List<String> arguments = json.get("arguments").values().stream()
                    .map(argument -> argument.asString(""))
                    .filter(argument -> !argument.isEmpty())
                    .toList();
            return new CatalogTool(
                    json.get("name").asString(""),
                    json.get("description").asString(""),
                    json.get("panel").asString(""),
                    json.get("action").asBoolean(false),
                    json.get("schema").asString("NONE"),
                    arguments,
                    json.get("panelEnabled").asBoolean(true),
                    json.get("panelReadOnly").asBoolean(false));
        }
    }
}
