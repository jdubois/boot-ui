package io.github.jdubois.bootui.engine.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Verifies the shared "detail" sanitizer every advisor's rule-support class (Architecture, Hibernate,
 * Memory, REST API, CRaC, GraalVM) relies on to flatten and bound a free-form finding detail before it
 * reaches the browser panel.
 */
class DetailTextTests {

    @Test
    void returnsPlaceholderForNull() {
        assertThat(DetailText.sanitize(null)).isEqualTo("No additional detail.");
    }

    @Test
    void returnsPlaceholderForBlank() {
        assertThat(DetailText.sanitize("   ")).isEqualTo("No additional detail.");
        assertThat(DetailText.sanitize("")).isEqualTo("No additional detail.");
    }

    @Test
    void flattensNewlinesTabsAndTrims() {
        assertThat(DetailText.sanitize("  line one\nline two\tcol  ")).isEqualTo("line one line two col");
    }

    @Test
    void leavesShortValuesUnchanged() {
        assertThat(DetailText.sanitize("a short detail")).isEqualTo("a short detail");
    }

    @Test
    void truncatesValuesLongerThanTheDefaultLimitWithEllipsis() {
        String longValue = "x".repeat(DetailText.DEFAULT_MAX_CHARS + 50);

        String result = DetailText.sanitize(longValue);

        assertThat(result).hasSize(DetailText.DEFAULT_MAX_CHARS);
        assertThat(result).endsWith("...");
    }

    @Test
    void honorsACustomMaxCharsLimit() {
        String result = DetailText.sanitize("abcdefghij", 5);

        assertThat(result).isEqualTo("ab...");
    }
}
