package io.github.jdubois.bootui.cli;

import io.github.jdubois.bootui.client.JsonValue;
import io.github.jdubois.bootui.client.JsonWriter;
import io.github.jdubois.bootui.engine.cli.CliCommandPaths;
import io.github.jdubois.bootui.engine.mcp.McpToolCatalog;
import io.github.jdubois.bootui.engine.mcp.McpToolDescriptions;
import io.github.jdubois.bootui.engine.mcp.McpToolGuide;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders {@code bootui-tools.json} from {@link McpToolCatalog} plus {@link CliCommandPaths}.
 *
 * <p>Test-scoped by design: the CLI must not carry the engine, or it could only ever talk to applications
 * running the exact BootUI version it was compiled against. Generation happens at build time, the result is
 * checked in, and {@link ToolManifestGeneratorTests} fails when the two drift.
 */
final class ToolManifestGenerator {

    private ToolManifestGenerator() {}

    /** The manifest document, formatted the way it is checked in. */
    static String generate() {
        List<McpToolCatalog.Entry> entries = new ArrayList<>(McpToolCatalog.entries());
        entries.sort(Comparator.comparing(entry -> CliCommandPaths.BY_TOOL.get(entry.name())));

        List<JsonValue> tools = new ArrayList<>();
        for (McpToolCatalog.Entry entry : entries) {
            Map<String, JsonValue> tool = new LinkedHashMap<>();
            tool.put("name", JsonValue.of(entry.name()));
            tool.put("command", JsonValue.of(CliCommandPaths.BY_TOOL.get(entry.name())));
            tool.put("schema", JsonValue.of(entry.schema().name()));
            tool.put("panel", JsonValue.of(entry.panelId()));
            tool.put("action", JsonValue.of(entry.action()));
            tool.put(
                    "stacks",
                    JsonValue.array(entry.stacks().stream()
                            .map(Enum::name)
                            .sorted()
                            .map(JsonValue::of)
                            .toList()));
            tool.put("summary", JsonValue.of(summary(entry.name())));
            tool.put(
                    "example", JsonValue.of(CliCommandPaths.command(entry.name(), McpToolGuide.example(entry.name()))));
            McpToolGuide.IdSource source = McpToolGuide.idSource(entry.name());
            if (source != null) {
                tool.put("idHelp", JsonValue.of(idHelp(source)));
            }
            String queryWords = McpToolGuide.queryWords(entry.name());
            if (queryWords != null) {
                tool.put("queryHelp", JsonValue.of(queryWords));
            }
            tools.add(JsonValue.object(tool));
        }

        Map<String, JsonValue> manifest = new LinkedHashMap<>();
        manifest.put("tools", JsonValue.array(tools));
        return JsonWriter.pretty(JsonValue.object(manifest)) + "\n";
    }

    /** Where an id comes from, phrased with the commands rather than the MCP tools that return it. */
    private static String idHelp(McpToolGuide.IdSource source) {
        String noun =
                Character.toUpperCase(source.noun().charAt(0)) + source.noun().substring(1);
        if (source.fromTools().isEmpty()) {
            return noun + ".";
        }
        List<String> commands = source.fromTools().stream()
                .map(tool -> "'bootui " + CliCommandPaths.commandFor(tool) + "'")
                .toList();
        return noun + " from " + String.join(" or ", commands) + ".";
    }

    /**
     * The first sentence of the MCP description.
     *
     * <p>MCP descriptions are written for a model deciding whether to call a tool, so they carry guidance a
     * help listing does not want ("prefer a narrow query", "not proof that a bean is exercised"). The opening
     * sentence is the part that says what the tool returns, which is exactly what a help line needs.
     */
    private static String summary(String toolName) {
        String description = McpToolDescriptions.spring(toolName);
        int end = description.indexOf(". ");
        String sentence = end < 0 ? description : description.substring(0, end + 1);
        return sentence.trim();
    }
}
