package io.github.jdubois.bootui.autoconfigure.safety;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.engine.safety.ApiTokenAuthenticator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiAuthenticationFilterTests {

    private static final String TOKEN = "test-token";

    private ApiAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ApiAuthenticationFilter(new BootUiProperties(), new ApiTokenAuthenticator(TOKEN));
    }

    @Test
    void loopbackApiRequestsDoNotRequireAuthentication() throws Exception {
        MockHttpServletResponse response = filter(request("GET", "/bootui/api/overview", "127.0.0.1"));

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void nonLoopbackApiRequestsRequireAuthentication() throws Exception {
        MockHttpServletResponse response = filter(request("GET", "/bootui/api/overview", "10.0.0.5"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bear" + "er realm=\"BootUI\"");
        assertThat(response.getContentAsString()).contains(ApiTokenAuthenticator.AUTHENTICATION_REQUIRED_MESSAGE);
    }

    @Test
    void validBearerTokenCreatesAnHttpOnlyBrowserSession() throws Exception {
        MockHttpServletRequest request = request("POST", "/bootui/api/auth/session", "10.0.0.5");
        request.addHeader("Authorization", "Bearer " + TOKEN);

        MockHttpServletResponse response = filter(request);

        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(response.getHeader("Set-Cookie"))
                .isEqualTo("BOOTUI_SESSION=test-token; Path=/bootui/api; HttpOnly; SameSite=Strict");
    }

    @Test
    void sessionCookieAuthenticatesSubsequentRequests() throws Exception {
        MockHttpServletRequest request = request("GET", "/bootui/api/health", "10.0.0.5");
        request.addHeader("Cookie", "BOOTUI_SESSION=" + TOKEN);

        assertThat(filter(request).getStatus()).isEqualTo(200);
    }

    @Test
    void staticUiAndApplicationRequestsAreNotAuthenticated() throws Exception {
        assertThat(filter(request("GET", "/bootui/index.html", "10.0.0.5")).getStatus())
                .isEqualTo(200);
        assertThat(filter(request("GET", "/api/users", "10.0.0.5")).getStatus()).isEqualTo(200);
    }

    private MockHttpServletResponse filter(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private static MockHttpServletRequest request(String method, String path, String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        request.setRemoteAddr(remoteAddress);
        return request;
    }
}
