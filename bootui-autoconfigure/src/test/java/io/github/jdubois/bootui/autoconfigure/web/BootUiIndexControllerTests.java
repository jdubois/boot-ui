package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * Tests for {@link BootUiIndexController}.
 *
 * <p>Verifies the two URL mappings that serve the Vue SPA:</p>
 * <ul>
 *   <li>{@code GET /bootui} → redirect to {@code /bootui/} (trailing-slash canonical form).</li>
 *   <li>{@code GET /bootui/} → forward to {@code /bootui/index.html} (SPA entry point).</li>
 * </ul>
 */
class BootUiIndexControllerTests {

    // ── /bootui → redirect ────────────────────────────────────────────────────

    private static MockMvc buildMvc(BootUiProperties properties) {
        return standaloneSetup(new BootUiIndexController(properties))
            .setViewResolvers(new InternalResourceViewResolver())
            .build();
    }

    @Test
    void rootPathRedirectsToTrailingSlash() throws Exception {
        MockMvc mvc = buildMvc(new BootUiProperties());

        mvc.perform(get("/bootui"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/bootui/"));
    }

    // ── /bootui/ → forward ────────────────────────────────────────────────────

    @Test
    void customPathPropertyUsedInRedirect() throws Exception {
        BootUiProperties properties = new BootUiProperties();
        properties.setPath("/devtools");
        MockMvc mvc = buildMvc(properties);

        mvc.perform(get("/bootui"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/devtools/"));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    @Test
    void indexPathForwardsToIndexHtml() throws Exception {
        MockMvc mvc = buildMvc(new BootUiProperties());

        mvc.perform(get("/bootui/"))
            .andExpect(status().isOk())
            .andExpect(forwardedUrl("/bootui/index.html"));
    }
}
