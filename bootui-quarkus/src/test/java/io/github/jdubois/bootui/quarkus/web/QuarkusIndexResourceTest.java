package io.github.jdubois.bootui.quarkus.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link QuarkusIndexResource#injectBaseHref}, mirroring the Spring adapter's
 * {@code BootUiIndexControllerTests} base-href-injection coverage.
 */
class QuarkusIndexResourceTest {

    @Test
    void injectsBaseTagAfterHeadAndBeforeRelativeUrls() {
        String html = "<html><head>\n<link href=\"./favicon.svg\" /></head><body></body></html>";

        String result = QuarkusIndexResource.injectBaseHref(html, "/bootui/");

        assertThat(result).contains("<base href=\"/bootui/\" />");
        assertThat(result.indexOf("<base")).isGreaterThan(result.indexOf("<head"));
        assertThat(result.indexOf("<base")).isLessThan(result.indexOf("./favicon.svg"));
    }

    @Test
    void leavesMarkupUnchangedWhenBaseTagAlreadyPresent() {
        String html = "<html><head><base href=\"/existing/\" /></head><body></body></html>";

        assertThat(QuarkusIndexResource.injectBaseHref(html, "/bootui/")).isEqualTo(html);
    }

    @Test
    void leavesMarkupUnchangedWhenNoHeadElement() {
        String html = "<html><body>no head here</body></html>";

        assertThat(QuarkusIndexResource.injectBaseHref(html, "/bootui/")).isEqualTo(html);
    }

    @Test
    void escapesBaseHrefAttributeValue() {
        String result = QuarkusIndexResource.injectBaseHref("<head></head>", "/a\"b/");

        assertThat(result).contains("/a&quot;b/");
    }

    @Test
    void injectsApplicationRootAwareApiPathWithoutDuplication() {
        String html = "<html><head></head><body></body></html>";

        String once = QuarkusIndexResource.injectRuntimePaths(html, "/host/console/", "/host/internal/api");
        String twice = QuarkusIndexResource.injectRuntimePaths(once, "/ignored/", "/ignored/api");

        assertThat(twice)
                .contains("<base href=\"/host/console/\" />")
                .contains("content=\"/host/internal/api\" name=\"bootui-api-path\"")
                .doesNotContain("/ignored/api");
    }
}
