package io.github.jdubois.bootui.autoconfigure.restadvisor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RestApiHandlerModelBuilderTests {

    @Test
    void normalizePathMaintainsTrailingSlash() {
        assertThat(RestApiHandlerModelBuilder.normalizePath("/api/")).isEqualTo("/api/");
        assertThat(RestApiHandlerModelBuilder.normalizePath("api/")).isEqualTo("/api/");
        assertThat(RestApiHandlerModelBuilder.normalizePath("/api//")).isEqualTo("/api/");
        assertThat(RestApiHandlerModelBuilder.normalizePath("/api")).isEqualTo("/api");
        assertThat(RestApiHandlerModelBuilder.normalizePath("/")).isEqualTo("/");
    }
}
