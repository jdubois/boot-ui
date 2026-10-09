package io.github.jdubois.bootui.engine.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The JSON Schema of one tool's arguments, as advertised in {@code tools/list}, without depending on a JSON library:
 * each adapter codec renders it verbatim, so the Spring and Quarkus servers advertise the same bytes.
 *
 * <p>The argument names come from {@link McpToolSchema}; what each argument means for this tool comes from
 * {@link McpToolGuide} (where an {@code id} comes from, the words a {@code query} understands, one example) and its
 * default page size from {@link McpToolCatalog#defaultLimit(String)}, the same sources the CLI help and the
 * missing-argument errors use.
 *
 * @param properties the arguments in the order a command line passes them
 */
public record McpToolInputSchema(List<Property> properties) {

    /** The page size advisor detail reads use when the call asks for none. */
    static final int RULE_VIOLATIONS_DEFAULT_LIMIT = 100;

    /** The largest page an advisor detail read returns, before the transport's {@code max-results} cap. */
    static final int RULE_VIOLATIONS_MAX_LIMIT = 1000;

    public McpToolInputSchema {
        properties = List.copyOf(properties);
    }

    /**
     * One argument.
     *
     * @param name the argument name
     * @param type {@code string} or {@code integer}
     * @param required whether a call must pass it
     * @param minimum the smallest accepted integer, or {@code null}
     * @param minLength the shortest accepted string, or {@code null}
     * @param defaultValue the value a call that omits it gets, or {@code null} when there is none
     * @param description what the argument means for this tool
     * @param examples values a caller would plausibly pass, never a placeholder for another tool's output
     */
    public record Property(
            String name,
            String type,
            boolean required,
            Integer minimum,
            Integer minLength,
            Integer defaultValue,
            String description,
            List<String> examples) {

        public Property {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(description, "description");
            examples = examples == null ? List.of() : List.copyOf(examples);
        }
    }

    /** The names of the required arguments, in order. */
    public List<String> required() {
        return properties.stream()
                .filter(Property::required)
                .map(Property::name)
                .toList();
    }

    /**
     * Builds the schema of {@code tool}.
     *
     * @param tool the tool name
     * @param schema its argument shape
     * @param maxResults the transport's {@code max-results} cap, which bounds every default page size
     */
    public static McpToolInputSchema of(String tool, McpToolSchema schema, int maxResults) {
        int cap = Math.max(1, maxResults);
        List<Property> properties = new ArrayList<>();
        switch (schema) {
            case NONE -> {}
            case LIMIT -> properties.add(limit(tool, cap));
            case QUERY_LIMIT -> {
                properties.add(query(tool));
                properties.add(limit(tool, cap));
            }
            case QUERY -> properties.add(query(tool));
            case ID -> properties.add(id(tool, true));
            case OPTIONAL_ID -> properties.add(id(tool, false));
            case RULE_VIOLATIONS -> {
                String report = tool.replace("_rule_violations", "_report");
                properties.add(new Property(
                        "id", "string", true, null, 1, null, "The rule id, from " + report + ".", List.of()));
                properties.add(new Property(
                        "scanId",
                        "string",
                        true,
                        null,
                        1,
                        null,
                        "The cached report's violationDetails.scanId. Never starts a scan.",
                        List.of()));
                properties.add(new Property(
                        "offset",
                        "integer",
                        false,
                        0,
                        null,
                        0,
                        "Zero-based offset into the retained violations; advance it by page.returned while "
                                + "page.hasMore.",
                        List.of()));
                properties.add(new Property(
                        "limit",
                        "integer",
                        false,
                        1,
                        null,
                        Math.min(RULE_VIOLATIONS_DEFAULT_LIMIT, Math.min(RULE_VIOLATIONS_MAX_LIMIT, cap)),
                        "Page size, default " + RULE_VIOLATIONS_DEFAULT_LIMIT + ", capped at min("
                                + RULE_VIOLATIONS_MAX_LIMIT + ", bootui.mcp.max-results).",
                        List.of()));
            }
        }
        return new McpToolInputSchema(properties);
    }

    private static Property query(String tool) {
        String words = McpToolGuide.queryWords(tool);
        String description =
                words == null ? "Optional case-insensitive filter applied to the results." : "Optional: " + words + ".";
        return new Property("query", "string", false, null, null, null, description, example(tool, "query"));
    }

    private static Property limit(String tool, int cap) {
        Integer compacted = McpToolCatalog.defaultLimit(tool);
        int defaultLimit = compacted == null ? cap : Math.min(Math.max(1, compacted), cap);
        String meaning = "get_http_routes".equals(tool)
                ? "Optional number of routes each ranking criterion contributes"
                : "Optional maximum number of items to return";
        String description = meaning + "; default " + defaultLimit
                + (compacted == null ? " (the server's limit)" : "")
                + ", capped by the bootui.mcp.max-results server limit.";
        return new Property("limit", "integer", false, 1, null, defaultLimit, description, List.of());
    }

    private static Property id(String tool, boolean required) {
        McpToolGuide.IdSource source = McpToolGuide.idSource(tool);
        String description;
        if (source != null) {
            String text = source.describe();
            description =
                    (required ? Character.toUpperCase(text.charAt(0)) + text.substring(1) : "Optional: " + text) + ".";
        } else {
            description = required
                    ? "Exact identifier of the resource to fetch."
                    : "Optional identifier; omitted selects the tool's documented default.";
        }
        return new Property("id", "string", required, null, null, null, description, example(tool, "id"));
    }

    /** The guide's example value for {@code argument}, unless it is a placeholder for another tool's output. */
    private static List<String> example(String tool, String argument) {
        Object value = McpToolGuide.example(tool).get(argument);
        if (value == null) {
            return List.of();
        }
        String text = String.valueOf(value);
        return text.startsWith("<") ? List.of() : List.of(text);
    }
}
