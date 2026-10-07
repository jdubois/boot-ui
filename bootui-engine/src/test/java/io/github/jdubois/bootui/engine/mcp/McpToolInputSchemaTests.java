package io.github.jdubois.bootui.engine.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.RuntimeInsightsAgentReportDto;
import java.util.List;
import org.junit.jupiter.api.Test;

class McpToolInputSchemaTests {

    @Test
    void everyCatalogToolAdvertisesExactlyItsSchemaArguments() {
        for (McpToolCatalog.Entry entry : McpToolCatalog.entries()) {
            McpToolInputSchema schema = McpToolInputSchema.of(entry.name(), entry.schema(), 200);
            assertThat(schema.properties())
                    .as(entry.name())
                    .extracting(McpToolInputSchema.Property::name)
                    .containsExactlyElementsOf(entry.schema().argumentNames());
            assertThat(schema.properties())
                    .as(entry.name())
                    .allSatisfy(property -> assertThat(property.description()).isNotBlank());
        }
    }

    @Test
    void anIdSaysWhereItComesFromForThatTool() {
        McpToolInputSchema.Property probe = only(McpToolInputSchema.of("start_method_probe", McpToolSchema.ID, 200));
        assertThat(probe.required()).isTrue();
        assertThat(probe.description()).isEqualTo("An application method (binary.Class#name) from get_code_paths.");
        assertThat(probe.examples()).containsExactly("com.example.OrderService#total");

        McpToolInputSchema.Property impact = only(McpToolInputSchema.of("get_runtime_impact", McpToolSchema.ID, 200));
        assertThat(impact.description()).startsWith("A route, bean, class, Class#method");

        McpToolInputSchema.Property insight = only(McpToolInputSchema.of("get_runtime_insight", McpToolSchema.ID, 200));
        assertThat(insight.description()).isEqualTo("An observation id from get_runtime_insights.");
        assertThat(insight.examples()).as("a placeholder is not an example").isEmpty();

        McpToolInputSchema.Property run =
                only(McpToolInputSchema.of("get_runtime_run_comparison", McpToolSchema.OPTIONAL_ID, 200));
        assertThat(run.required()).isFalse();
        assertThat(run.description()).startsWith("Optional: previous (the default)");
    }

    @Test
    void aQueryListsTheWordsItUnderstands() {
        List<McpToolInputSchema.Property> insights = McpToolInputSchema.of(
                        "get_runtime_insights", McpToolSchema.QUERY_LIMIT, 200)
                .properties();
        assertThat(insights.get(0).description()).contains("all (every observation)", "repeated-selects");
        assertThat(insights.get(0).examples()).containsExactly("security");
        assertThat(McpToolInputSchema.of("get_side_effects", McpToolSchema.QUERY_LIMIT, 200)
                        .properties()
                        .get(0)
                        .description())
                .contains("processes", "not captured");
        assertThat(McpToolInputSchema.of("get_beans", McpToolSchema.QUERY_LIMIT, 200)
                        .properties()
                        .get(0)
                        .description())
                .isEqualTo("Optional case-insensitive filter applied to the results.");
    }

    @Test
    void aLimitAdvertisesTheDefaultACallGetsWhenItOmitsOne() {
        assertThat(McpToolInputSchema.of("get_runtime_insights", McpToolSchema.QUERY_LIMIT, 200)
                        .properties()
                        .get(1)
                        .defaultValue())
                .isEqualTo(RuntimeInsightsAgentReportDto.DEFAULT_LIMIT);
        assertThat(McpToolInputSchema.of("get_runtime_insights", McpToolSchema.QUERY_LIMIT, 3)
                        .properties()
                        .get(1)
                        .defaultValue())
                .isEqualTo(3);
        assertThat(McpToolInputSchema.of("get_beans", McpToolSchema.QUERY_LIMIT, 200)
                        .properties()
                        .get(1)
                        .defaultValue())
                .isEqualTo(200);
        McpToolInputSchema violations =
                McpToolInputSchema.of("get_architecture_rule_violations", McpToolSchema.RULE_VIOLATIONS, 50);
        assertThat(violations.required()).containsExactly("id", "scanId");
        assertThat(violations.properties().get(0).description())
                .isEqualTo("The rule id, from get_architecture_report.");
        assertThat(violations.properties().get(2).defaultValue()).isZero();
        assertThat(violations.properties().get(3).defaultValue()).isEqualTo(50);
    }

    private static McpToolInputSchema.Property only(McpToolInputSchema schema) {
        assertThat(schema.properties()).hasSize(1);
        return schema.properties().get(0);
    }
}
