package io.github.jdubois.bootui.engine.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Verifies the relaxed-binding-aware name matching used by the Configuration panel and the {@code get_config}
 * MCP tool. Property sources enumerate environment variables under their raw {@code UPPER_SNAKE_CASE} names,
 * so a dotted query has to reach them through canonicalization rather than a literal substring test.
 */
class RelaxedNamesTests {

    @Test
    void canonicalizeLowercasesAndNormalizesSeparators() {
        assertThat(RelaxedNames.canonicalize("BOOTUI_MCP_ENABLED")).isEqualTo("bootui.mcp.enabled");
        assertThat(RelaxedNames.canonicalize("bootui.conformance.api-token")).isEqualTo("bootui.conformance.api.token");
        assertThat(RelaxedNames.canonicalize("bootui.mcp.enabled")).isEqualTo("bootui.mcp.enabled");
    }

    @Test
    void canonicalizePreservesNullAndEmpty() {
        assertThat(RelaxedNames.canonicalize(null)).isNull();
        assertThat(RelaxedNames.canonicalize("")).isEmpty();
    }

    @Test
    void canonicalizeIsLengthPreservingSoLiteralMatchesAreNeverLost() {
        String name = "Spring_Datasource-URL";
        assertThat(RelaxedNames.canonicalize(name)).hasSameSizeAs(name);
        assertThat(RelaxedNames.contains(name, RelaxedNames.canonicalize("datasource")))
                .isTrue();
    }

    @Test
    void containsMatchesAcrossSeparatorAndCaseSpellings() {
        assertThat(RelaxedNames.contains("BOOTUI_MCP_ENABLED", "bootui.mcp.enabled"))
                .isTrue();
        assertThat(RelaxedNames.contains("bootui.mcp.enabled", RelaxedNames.canonicalize("BOOTUI_MCP_ENABLED")))
                .isTrue();
        assertThat(RelaxedNames.contains("spring.datasource.url", RelaxedNames.canonicalize("spring-datasource")))
                .isTrue();
    }

    @Test
    void containsRejectsUnrelatedNamesAndToleratesNulls() {
        assertThat(RelaxedNames.contains("bootui.mcp.enabled", "bootui.cli.enabled"))
                .isFalse();
        assertThat(RelaxedNames.contains(null, "bootui")).isFalse();
    }

    @Test
    void anEmptyOrNullQueryMatchesEverything() {
        assertThat(RelaxedNames.contains("bootui.mcp.enabled", "")).isTrue();
        assertThat(RelaxedNames.contains(null, "")).isTrue();
        assertThat(RelaxedNames.contains("bootui.mcp.enabled", null)).isTrue();
    }
}
