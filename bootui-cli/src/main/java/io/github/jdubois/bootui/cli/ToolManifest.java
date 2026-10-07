package io.github.jdubois.bootui.cli;

import io.github.jdubois.bootui.client.JsonValue;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The command tree the CLI was built with, read from the generated {@code bootui-tools.json}.
 *
 * <p>It exists so {@code bootui --help} works without a running application. It is <em>not</em> the authority
 * on what a given instance exposes: the three stacks advertise different tool sets and panels can be disabled,
 * so what a specific application will actually answer comes from {@code GET /bootui/api/cli} at runtime.
 *
 * <p>The file is generated from {@code McpToolCatalog} and checked in, with a test that fails when the two
 * disagree. That is what makes a new MCP tool impossible to forget: it cannot reach the registry without also
 * reaching this manifest and, through it, the command tree.
 */
public final class ToolManifest {

    private static final String RESOURCE = "/bootui-tools.json";

    private final List<Tool> tools;

    ToolManifest(List<Tool> tools) {
        this.tools = List.copyOf(tools);
    }

    /** Reads the manifest bundled with this CLI. */
    public static ToolManifest bundled() {
        try (InputStream resource = ToolManifest.class.getResourceAsStream(RESOURCE)) {
            if (resource == null) {
                throw new IllegalStateException("Missing " + RESOURCE + " on the classpath");
            }
            return parse(new String(resource.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read " + RESOURCE, failure);
        }
    }

    /** Parses a manifest document. */
    public static ToolManifest parse(String json) {
        List<Tool> tools = new ArrayList<>();
        for (JsonValue tool : JsonValue.parse(json).get("tools").values()) {
            tools.add(new Tool(
                    tool.get("name").asString(""),
                    tool.get("command").asString(""),
                    tool.get("schema").asString("NONE"),
                    tool.get("panel").asString(""),
                    tool.get("action").asBoolean(false),
                    tool.get("stacks").values().stream()
                            .map(stack -> stack.asString(""))
                            .toList(),
                    tool.get("summary").asString(""),
                    tool.get("description").asString(""),
                    tool.get("example").asString(""),
                    tool.get("idHelp").asString(""),
                    tool.get("queryHelp").asString("")));
        }
        return new ToolManifest(tools);
    }

    /** Every tool, ordered by command path. */
    public List<Tool> tools() {
        return tools;
    }

    /** The entry for one MCP tool name, or {@code null}. */
    public Tool byName(String name) {
        return tools.stream()
                .filter(tool -> tool.name().equals(name))
                .findFirst()
                .orElse(null);
    }

    /**
     * One command.
     *
     * @param name the MCP tool name it invokes
     * @param command the space-separated command path, e.g. {@code memory heap analyze}
     * @param schema the argument schema: {@code NONE}, {@code LIMIT}, {@code QUERY_LIMIT}, {@code ID},
     *     or {@code RULE_VIOLATIONS}
     * @param panel the panel backing it
     * @param action whether it changes state, and is therefore refused on a read-only panel
     * @param stacks the stacks that advertise it, so help can say when a command is stack-specific
     * @param summary a one-line description for the command listing
     * @param description the whole description for the command's own help, or the summary when the manifest has none
     * @param example one complete command line, with {@code <placeholders>} for values another command returns
     * @param idHelp where the {@code <id>} comes from, or empty when the command takes none
     * @param queryHelp the words {@code --query} understands beyond a plain filter, or empty
     */
    public record Tool(
            String name,
            String command,
            String schema,
            String panel,
            boolean action,
            List<String> stacks,
            String summary,
            String description,
            String example,
            String idHelp,
            String queryHelp) {

        /** The tag the help prints before an action's description: it changes state, so ask the user first. */
        public static final String ACTION_TAG = "[action - needs approval]";

        public Tool {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(command, "command");
            stacks = stacks == null ? List.of() : List.copyOf(stacks);
            summary = summary == null ? "" : summary;
            description = description == null || description.isBlank() ? summary : description;
            example = example == null || example.isBlank() ? "bootui " + command : example;
            idHelp = idHelp == null ? "" : idHelp;
            queryHelp = queryHelp == null ? "" : queryHelp;
        }

        public Tool(
                String name,
                String command,
                String schema,
                String panel,
                boolean action,
                List<String> stacks,
                String summary) {
            this(name, command, schema, panel, action, stacks, summary, null, null, null, null);
        }

        /** The whole description for a command's own {@code --help}, tagged when the tool is an action. */
        public String helpDescription() {
            return tag() + description;
        }

        /** {@link #ACTION_TAG} and a space for an action, otherwise empty. */
        public String tag() {
            return action ? ACTION_TAG + " " : "";
        }

        /**
         * The command with its arguments, such as {@code bootui insights show <id>} or
         * {@code bootui beans [--query <text>] [--limit <count>]}, from the same schema flags the command tree binds.
         */
        public String synopsis() {
            StringBuilder line = new StringBuilder("bootui ").append(command);
            if (takesId()) {
                line.append(optionalId() ? " [<id>]" : " <id>");
            }
            if (takesScanId()) {
                line.append(" --scan-id <scanId>");
            }
            if (takesQuery()) {
                line.append(" [--query <text>]");
            }
            if (takesOffset()) {
                line.append(" [--offset <offset>]");
            }
            if (takesLimit()) {
                line.append(" [--limit <count>]");
            }
            return line.toString();
        }

        /** The command path split into its words. */
        public List<String> path() {
            return List.of(command.split(" "));
        }

        /** Whether this tool takes a {@code query} filter. */
        public boolean takesQuery() {
            return "QUERY_LIMIT".equals(schema);
        }

        /** Whether this tool takes a {@code limit}. */
        public boolean takesLimit() {
            return "LIMIT".equals(schema) || "QUERY_LIMIT".equals(schema) || takesScanId();
        }

        /** Whether this tool requires an {@code id} positional. */
        public boolean takesId() {
            return "ID".equals(schema) || optionalId() || takesScanId();
        }

        public boolean optionalId() {
            return "OPTIONAL_ID".equals(schema);
        }

        /** Whether this tool requires a completed advisor snapshot identifier. */
        public boolean takesScanId() {
            return "RULE_VIOLATIONS".equals(schema);
        }

        /** Whether this tool accepts an offset into retained advisor details. */
        public boolean takesOffset() {
            return "RULE_VIOLATIONS".equals(schema);
        }

        /** Whether every stack advertises this tool. */
        public boolean onEveryStack() {
            return stacks.size() == 3;
        }
    }
}
