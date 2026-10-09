package io.github.jdubois.bootui.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.mcp.McpToolCatalog;
import io.github.jdubois.bootui.engine.mcp.McpToolSchema;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A 1.x {@code bootui} CLI keeps its commands against a 2.x application ({@code docs/PLAN-v2.md} §5.6): it calls each
 * tool by the name its own bundled manifest recorded, sending only the arguments that manifest's schema names. So every
 * tool the last 1.x CLI knows must still be served under that name, with its action flag, on at least the stacks it
 * was, accepting every argument the 1.x schema named and requiring none it did not: a schema may widen, as {@code NONE}
 * to {@code QUERY_LIMIT}, but an {@code ID} or {@code RULE_VIOLATIONS} tool keeps its schema exactly. New tools need a
 * 2.x CLI, which this test does not constrain.
 */
class OneXCliCompatibilityTests {

    private static final String ONE_X_TOOLS = "/cli-1.x/bootui-tools-1.20.0.tsv";

    @Test
    void everyToolTheLastOneXCliKnowsIsStillServedWithACompatibleSchemaItsActionAndItsStacks() throws IOException {
        List<String[]> tools = oneXTools();
        assertThat(tools).as("the 1.20.0 manifest's tools").hasSize(91);

        for (String[] tool : tools) {
            String name = tool[0];
            McpToolCatalog.Entry entry = McpToolCatalog.byName(name).orElse(null);
            assertThat(entry)
                    .as("%s, which a 1.x CLI calls, is still a tool", name)
                    .isNotNull();
            McpToolSchema before = McpToolSchema.valueOf(tool[1]);
            McpToolSchema now = entry.schema();
            if (before == McpToolSchema.ID || before == McpToolSchema.RULE_VIOLATIONS) {
                assertThat(now).as("%s's argument schema", name).isEqualTo(before);
            }
            assertThat(now.argumentNames())
                    .as("%s accepts every argument a 1.x CLI sends", name)
                    .containsAll(before.argumentNames());
            assertThat(required(before))
                    .as("%s requires no argument a 1.x CLI does not send", name)
                    .containsAll(required(now));
            assertThat(entry.action()).as("%s's action flag", name).isEqualTo(Boolean.parseBoolean(tool[2]));
            assertThat(entry.stacks().stream().map(Enum::name).toList())
                    .as("%s's stacks", name)
                    .containsAll(Arrays.asList(tool[3].split(",")));
        }
    }

    /** The arguments {@code schema} requires: an {@code ID}'s id, a {@code RULE_VIOLATIONS}'s id and scanId. */
    private static Set<String> required(McpToolSchema schema) {
        return switch (schema) {
            case ID -> Set.of("id");
            case RULE_VIOLATIONS -> Set.of("id", "scanId");
            default -> Set.of();
        };
    }

    private static List<String[]> oneXTools() throws IOException {
        try (InputStream in = OneXCliCompatibilityTests.class.getResourceAsStream(ONE_X_TOOLS)) {
            assertThat(in).as(ONE_X_TOOLS).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .map(line -> line.split("\t"))
                    .toList();
        }
    }
}
