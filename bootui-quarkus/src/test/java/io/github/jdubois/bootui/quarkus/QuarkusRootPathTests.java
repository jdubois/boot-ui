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

    @Test
    void anEncodedSlashNextToAMatrixParameterCannotHideAPathFromTheGuards() {
        // RESTEasy Reactive decodes each segment once a path has a matrix parameter, so %2F separates segments for it.
        assertThat(QuarkusRootPath.stripPrefix("/bootui/api%2Fdismissed-findings;x=1", ""))
                .isEqualTo("/bootui/api/dismissed-findings");
        assertThat(QuarkusRootPath.stripPrefix("/bootui%2fapi/dismissed-findings;x=1", ""))
                .isEqualTo("/bootui/api/dismissed-findings");
        assertThat(QuarkusRootPath.stripPrefix("/bootui/api/dismissed%2Dfindings;x=1", ""))
                .isEqualTo("/bootui/api/dismissed-findings");
        assertThat(QuarkusRootPath.stripPrefix("/bootui/api/dismissed-findings;x=%zz", ""))
                .isEqualTo("/bootui/api/dismissed-findings");
        assertThat(QuarkusRootPath.stripPrefix("/bootui/api/a%2;x=1", "")).isEqualTo("/bootui/api/a%2");
        // Without a matrix parameter Vert.x's normalized path is used as it is.
        assertThat(QuarkusRootPath.stripPrefix("/bootui/api%2Fdismissed-findings", ""))
                .isEqualTo("/bootui/api%2Fdismissed-findings");
    }
}
