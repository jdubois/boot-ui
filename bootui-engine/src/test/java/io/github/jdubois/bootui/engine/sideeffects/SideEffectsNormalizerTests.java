package io.github.jdubois.bootui.engine.sideeffects;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SideEffectsNormalizerTests {

    private final SideEffectsNormalizer normalizer = new SideEffectsNormalizer("/Users/alice");

    @Test
    void theHomeDirectoryUuidsHexRunsAndDigitsAreCollapsed() {
        assertThat(normalizer.target("/Users/alice/reports/2026/report-17.pdf"))
                .isEqualTo("~/reports/{n}/report-{n}.pdf");
        assertThat(normalizer.target("/tmp/3f2504e0-4f89-11d3-9a0c-0305e82c3301.json"))
                .isEqualTo("/tmp/{uuid}.json");
        assertThat(normalizer.target("blob-deadbeef12ab")).isEqualTo("blob-{hex}");
        assertThat(normalizer.target("blob-deadbeefcafe"))
                .as("hex with no digit is a word")
                .isEqualTo("blob-deadbeefcafe");
        assertThat(normalizer.target("/Users/alicia/x"))
                .as("only the home directory itself")
                .isEqualTo("/Users/alicia/x");
        assertThat(normalizer.target("git")).isEqualTo("git");
        assertThat(normalizer.target(null)).isNull();
    }

    @Test
    void aThreadFamilyCollapsesDigitsOnly() {
        assertThat(normalizer.threadFamily("pool-3-thread-17")).isEqualTo("pool-{n}-thread-{n}");
        assertThat(normalizer.threadFamily("")).isNull();
    }
}
