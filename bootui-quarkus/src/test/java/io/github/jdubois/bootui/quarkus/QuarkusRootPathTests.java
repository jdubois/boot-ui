package io.github.jdubois.bootui.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class QuarkusRootPathTests {

    @Test
    void matrixParametersNeverHideAPathFromTheGuards() {
        assertThat(QuarkusRootPath.stripPrefix("/bootui/api/dismissed-findings;x=1", ""))
                .isEqualTo("/bootui/api/dismissed-findings");
        assertThat(QuarkusRootPath.stripPrefix("/bootui;x=1/api;y=2/dismissed-rules;z=3/RULE", ""))
                .isEqualTo("/bootui/api/dismissed-rules/RULE");
        assertThat(QuarkusRootPath.stripPrefix("/app/bootui/api/hibernate;a=b/scan", "/app"))
                .isEqualTo("/bootui/api/hibernate/scan");
        assertThat(QuarkusRootPath.stripPrefix("/bootui/api/dismissed-findings/", ""))
                .isEqualTo("/bootui/api/dismissed-findings/");
        assertThat(QuarkusRootPath.stripPrefix(null, "")).isNull();
    }
}
