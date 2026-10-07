package io.github.jdubois.bootui.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.mcp.McpToolCatalog;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * A 1.x {@code bootui} CLI keeps its commands against a 2.x application ({@code docs/PLAN-v2.md} §5.6): it calls each
 * tool by the name and argument schema its own bundled manifest recorded, so every tool the last 1.x CLI knows must
 * still be served under that name, with that schema and action flag, on at least the stacks it was. New tools need a
 * 2.x CLI, which this test does not constrain.
 */
class OneXCliCompatibilityTests {

    private static final String ONE_X_TOOLS = "/cli-1.x/bootui-tools-1.20.0.tsv";

    @Test
    void everyToolTheLastOneXCliKnowsIsStillServedWithTheSameSchemaActionAndStacks() throws IOException {
        List<String[]> tools = oneXTools();
        assertThat(tools).as("the 1.20.0 manifest's tools").hasSize(91);

        for (String[] tool : tools) {
            String name = tool[0];
            McpToolCatalog.Entry entry = McpToolCatalog.byName(name).orElse(null);
            assertThat(entry).as("%s, which a 1.x CLI calls, is still a tool", name).isNotNull();
            assertThat(entry.schema().name()).as("%s's argument schema", name).isEqualTo(tool[1]);
            assertThat(entry.action()).as("%s's action flag", name).isEqualTo(Boolean.parseBoolean(tool[2]));
            assertThat(entry.stacks().stream().map(Enum::name).toList())
                    .as("%s's stacks", name)
                    .containsAll(Arrays.asList(tool[3].split(",")));
        }
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
