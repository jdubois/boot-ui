package io.github.jdubois.bootui.autoconfigure.safety;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.engine.safety.ApiTokenAuthenticator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Proves the servlet safety filters decide whether they apply on the same decoded path Spring's handler
 * mapping resolves against.
 *
 * <p>Servlet containers match a {@code FilterRegistrationBean} URL pattern against the decoded, normalized
 * path, so these filters are invoked for {@code /%62ootui/api/**} and {@code /bootui;x=1/api/**} and then
 * used to disarm themselves in {@code shouldNotFilter} by comparing the raw {@code getRequestURI()} —
 * while {@code PathPattern} still routed the request to the BootUI controller. That turned every
 * percent-encoded or matrix-parameter spelling of a BootUI URL into a full bypass of the loopback, Host
 * allow-list, cross-site-write, bearer-token and per-panel policy guards.</p>
 */
class BootUiGuardPathMatchingTests {

    @ParameterizedTest
    @ValueSource(strings = {"/%62ootui/api/config", "/bootui;x=1/api/config"})
    void localhostGuardStillRejectsNonLoopbackSources(String uri) throws Exception {
        LocalhostOnlyFilter filter = new LocalhostOnlyFilter(new BootUiProperties());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        request.setRemoteAddr("10.0.0.5");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/%62ootui/api/config", "/bootui;x=1/api/config"})
    void bearerTokenIsStillRequiredForNonLoopbackSources(String uri) throws Exception {
        BootUiProperties properties = new BootUiProperties();
        ApiAuthenticationFilter filter = new ApiAuthenticationFilter(
                properties, new ApiTokenAuthenticator("test-token"), new LocalhostOnlyFilter(properties));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        request.setRemoteAddr("10.0.0.5");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/%62ootui/api/config", "/bootui;x=1/api/config"})
    void panelPolicyStillAppliesToDisabledPanels(String uri) throws Exception {
        BootUiProperties properties = new BootUiProperties();
        properties.panel("config").setEnabled(false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new PanelAccessFilter(properties).doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("\"panel\":\"config\"");
    }

    @Test
    void canonicalPathsAreStillMatched() throws Exception {
        BootUiProperties properties = new BootUiProperties();
        properties.panel("config").setEnabled(false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/bootui/api/config");
        request.setRequestURI("/bootui/api/config");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new PanelAccessFilter(properties).doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void nonBootUiRequestsAreStillLeftAlone() throws Exception {
        LocalhostOnlyFilter filter = new LocalhostOnlyFilter(new BootUiProperties());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders");
        request.setRequestURI("/api/orders");
        request.setRemoteAddr("10.0.0.5");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }
}
