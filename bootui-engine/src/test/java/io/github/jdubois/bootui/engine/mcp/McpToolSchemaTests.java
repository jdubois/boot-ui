package io.github.jdubois.bootui.engine.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class McpToolSchemaTests {

    @Test
    void argumentNamesAreIteratedInDeclarationOrder() {
        // Set.of() salts its iteration order per JVM. The command-line facade publishes this order as the
        // argument order a generated CLI binds flags and positionals from, so it has to be stable.
        assertThat(McpToolSchema.QUERY_LIMIT.argumentNames()).containsExactly("query", "limit");
        assertThat(McpToolSchema.QUERY.argumentNames()).containsExactly("query");
        assertThat(McpToolSchema.LIMIT.argumentNames()).containsExactly("limit");
        assertThat(McpToolSchema.ID.argumentNames()).containsExactly("id");
        assertThat(McpToolSchema.OPTIONAL_ID.argumentNames()).containsExactly("id");
        assertThat(McpToolSchema.RULE_VIOLATIONS.argumentNames()).containsExactly("id", "scanId", "offset", "limit");
        assertThat(McpToolSchema.NONE.argumentNames()).isEmpty();
    }

    @Test
    void onlyTheQuerySchemaToleratesAnArgumentItNeitherAdvertisesNorUses() {
        // CLI binaries built while get_agent_status took QUERY_LIMIT still send --limit for it.
        assertThat(McpToolSchema.QUERY.ignoredArgumentNames()).containsExactly("limit");
        for (McpToolSchema schema : McpToolSchema.values()) {
            if (schema != McpToolSchema.QUERY) {
                assertThat(schema.ignoredArgumentNames()).as(schema.name()).isEmpty();
            }
        }
    }

    @Test
    void argumentNamesAreUnmodifiable() {
        assertThat(McpToolSchema.LIMIT.argumentNames()).isUnmodifiable();
    }
}
