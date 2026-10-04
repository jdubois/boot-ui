package io.github.jdubois.bootui.engine.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.cli.CliCommandPaths;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** M4-21: every tool that takes arguments says how to call it, and where its id comes from. */
class McpToolGuideTests {

    @Test
    void everyToolThatTakesArgumentsHasAnExampleWithinItsSchema() {
        for (McpToolCatalog.Entry entry : McpToolCatalog.entries()) {
            Map<String, Object> example = McpToolGuide.example(entry.name());
            if (entry.schema() == McpToolSchema.NONE) {
                assertThat(example).as(entry.name()).isEmpty();
                continue;
            }
            assertThat(example).as(entry.name()).isNotEmpty();
            assertThat(entry.schema().argumentNames()).as(entry.name()).containsAll(example.keySet());
            if (entry.schema() == McpToolSchema.ID || entry.schema() == McpToolSchema.RULE_VIOLATIONS) {
                assertThat(example).as(entry.name()).containsKey("id");
            }
            if (entry.schema() == McpToolSchema.RULE_VIOLATIONS) {
                assertThat(example).as(entry.name()).containsKey("scanId");
            }
        }
        assertThat(McpToolCatalog.names()).containsAll(McpToolGuide.toolsWithExamples());
    }

    @Test
    void everyIdToolNamesWhereItsIdComesFromAndItsDescriptionsSayTheSame() {
        for (McpToolCatalog.Entry entry : McpToolCatalog.entries()) {
            McpToolGuide.IdSource source = McpToolGuide.idSource(entry.name());
            if (!entry.schema().argumentNames().contains("id")) {
                assertThat(source).as(entry.name()).isNull();
                continue;
            }
            assertThat(source).as(entry.name()).isNotNull();
            for (String from : source.fromTools()) {
                McpToolCatalog.Entry origin = McpToolCatalog.byName(from).orElseThrow();
                assertThat(origin.stacks())
                        .as(
                                "%s takes its id from %s, which every stack advertising it must advertise",
                                entry.name(), from)
                        .containsAll(entry.stacks());
                if (entry.stacks().contains(McpToolCatalog.Stack.SPRING_MVC)) {
                    assertThat(McpToolDescriptions.spring(entry.name()))
                            .as(entry.name())
                            .contains(from);
                }
                if (entry.stacks().contains(McpToolCatalog.Stack.QUARKUS)) {
                    assertThat(McpToolDescriptions.quarkus(entry.name()))
                            .as(entry.name())
                            .contains(from);
                }
            }
        }
    }

    @Test
    void queryWordsAreOnlyForToolsThatTakeAQuery() {
        assertThat(McpToolGuide.toolsWithQueryWords())
                .allSatisfy(tool -> assertThat(
                                McpToolCatalog.byName(tool).orElseThrow().schema())
                        .isEqualTo(McpToolSchema.QUERY_LIMIT));
    }

    @Test
    void aMissingIdNamesTheCallThatReturnsOne() {
        assertThat(McpProtocol.missingArgumentMessage(McpProtocol.MISSING_ID_ARGUMENT_MESSAGE, "get_runtime_insight"))
                .isEqualTo("Missing required argument: id (an observation id from get_runtime_insights)");
        assertThat(McpProtocol.missingArgumentMessage(McpProtocol.MISSING_ID_ARGUMENT_MESSAGE, "get_beans"))
                .isEqualTo(McpProtocol.MISSING_ID_ARGUMENT_MESSAGE);
    }

    @Test
    void examplesRenderAsCommandLinesAShellPassesThroughUnchanged() {
        assertThat(CliCommandPaths.command("get_beans", McpToolGuide.example("get_beans")))
                .isEqualTo("bootui beans --query dataSource");
        assertThat(CliCommandPaths.command("get_runtime_impact", McpToolGuide.example("get_runtime_impact")))
                .as("zsh's extended glob reads #")
                .isEqualTo("bootui insights impact 'OrderService#total'");
        assertThat(CliCommandPaths.command(
                        "get_architecture_rule_violations", McpToolGuide.example("get_architecture_rule_violations")))
                .isEqualTo("bootui architecture violations <ruleId> --scan-id <scanId> --limit 100");
        assertThat(CliCommandPaths.command("get_runtime_impact", Map.of("id", "GET /it's")))
                .isEqualTo("bootui insights impact 'GET /it'\\''s'");
        assertThat(CliCommandPaths.command("get_runtime_insight", Map.of("id", "-1")))
                .as("a value that looks like an option goes after --")
                .isEqualTo("bootui insights show -- -1");
        assertThat(CliCommandPaths.command("get_config", Map.of("query", ""))).isEqualTo("bootui config --query ''");
    }
}
