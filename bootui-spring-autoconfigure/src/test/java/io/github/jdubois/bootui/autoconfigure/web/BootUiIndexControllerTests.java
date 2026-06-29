package io.github.jdubois.bootui.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Tests for {@link BootUiIndexController}.
 *
 * <p>The SPA shell is served (HTTP 200) at <em>both</em> {@code /bootui} and {@code /bootui/} with a
 * runtime-injected {@code <base href>} so its relative assets/API resolve regardless of a trailing
 * slash. There is deliberately <em>no</em> {@code /bootui -> /bootui/} redirect: removing it is what
 * makes BootUI immune to host-side trailing-slash stripping (#456).</p>
 */
class BootUiIndexControllerTests {

    private static final String STUB_INDEX = "<!doctype html>\n<html lang=\"en\">\n  <head>\n"
            + "    <link href=\"./favicon.svg\" rel=\"icon\" />\n"
            + "    <script type=\"module\" src=\"./assets/index-test.js\"></script>\n"
            + "  </head>\n  <body><div id=\"app\"></div></body>\n</html>\n";

    private static MockMvc buildMvc(BootUiProperties properties) {
        BootUiIndexController controller = new BootUiIndexController(
                properties, new ByteArrayResource(STUB_INDEX.getBytes(StandardCharsets.UTF_8)));
        return standaloneSetup(controller).build();
    }

    // ── /bootui and /bootui/ → SPA shell with injected <base href> ─────────────

    @Test
    void rootPathServesSpaWithInjectedBaseHref() throws Exception {
        MockMvc mvc = buildMvc(new BootUiProperties());

        mvc.perform(get("/bootui"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("<base href=\"/bootui/\" />")));
    }

    @Test
    void trailingSlashPathServesSpaWithInjectedBaseHref() throws Exception {
        MockMvc mvc = buildMvc(new BootUiProperties());

        mvc.perform(get("/bootui/"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("<base href=\"/bootui/\" />")));
    }

    @Test
    void baseHrefIncludesServletContextPath() throws Exception {
        MockMvc mvc = buildMvc(new BootUiProperties());

        // The base href must include the context path so relative assets/API resolve under a host
        // server.servlet.context-path (e.g. /api/bootui/...). See #332.
        mvc.perform(get("/api/bootui").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<base href=\"/api/bootui/\" />")));
    }

    @Test
    void baseHrefHonorsCustomPathProperty() throws Exception {
        BootUiProperties properties = new BootUiProperties();
        properties.setPath("/devtools");
        MockMvc mvc = buildMvc(properties);

        mvc.perform(get("/bootui"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<base href=\"/devtools/\" />")));
    }

    // ── injectBaseHref ─────────────────────────────────────────────────────────

    @Test
    void injectsBaseTagAfterHeadAndBeforeRelativeUrls() {
        String html = "<html><head>\n<link href=\"./favicon.svg\" /></head><body></body></html>";

        String result = BootUiIndexController.injectBaseHref(html, "/bootui/");

        assertThat(result).contains("<base href=\"/bootui/\" />");
        assertThat(result.indexOf("<base")).isGreaterThan(result.indexOf("<head"));
        assertThat(result.indexOf("<base")).isLessThan(result.indexOf("./favicon.svg"));
    }

    @Test
    void leavesMarkupUnchangedWhenBaseTagAlreadyPresent() {
        String html = "<html><head><base href=\"/existing/\" /></head><body></body></html>";

        assertThat(BootUiIndexController.injectBaseHref(html, "/bootui/")).isEqualTo(html);
    }

    @Test
    void leavesMarkupUnchangedWhenNoHeadElement() {
        String html = "<html><body>no head here</body></html>";

        assertThat(BootUiIndexController.injectBaseHref(html, "/bootui/")).isEqualTo(html);
    }

    @Test
    void escapesBaseHrefAttributeValue() {
        String result = BootUiIndexController.injectBaseHref("<head></head>", "/a\"b/");

        assertThat(result).contains("/a&quot;b/");
    }
}
