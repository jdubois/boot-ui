package io.github.bootui.autoconfigure.safety;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bootui.autoconfigure.BootUiProperties;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class LocalhostOnlyFilterTests {

    private BootUiProperties properties;

    private LocalhostOnlyFilter filter;

    @BeforeEach
    void setUp() {
        properties = new BootUiProperties();
        filter = new LocalhostOnlyFilter(properties);
    }

    @Test
    void allowsLoopbackIpv4Request() throws Exception {
        MockHttpServletRequest request = bootUiRequest("/bootui/api/overview", "127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void allowsLoopbackIpv6Request() throws Exception {
        MockHttpServletRequest request = bootUiRequest("/bootui/", "::1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsNonLoopbackRequestWithJsonBody() throws Exception {
        MockHttpServletRequest request = bootUiRequest("/bootui/api/config", "10.0.0.5");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getContentAsString()).contains("loopback");
    }

    @Test
    void allowsNonLoopbackWhenExplicitlyOptedIn() throws Exception {
        properties.setAllowNonLocalhost(true);
        MockHttpServletRequest request = bootUiRequest("/bootui/api/health", "10.0.0.5");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void allowsNonLoopbackWhenLocalhostOnlyDisabled() throws Exception {
        properties.setLocalhostOnly(false);
        MockHttpServletRequest request = bootUiRequest("/bootui/", "10.0.0.5");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void skipsFilterForNonBootUiPaths() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/sample/hello");
        request.setRequestURI("/api/sample/hello");
        request.setRemoteAddr("10.0.0.5");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsRequestWithUnknownRemoteAddress() throws Exception {
        MockHttpServletRequest request = bootUiRequest("/bootui/", "not-an-address");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void rejectsRequestWithBlankRemoteAddress() throws Exception {
        MockHttpServletRequest request = bootUiRequest("/bootui/", "");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    private MockHttpServletRequest bootUiRequest(String uri, String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        request.setRemoteAddr(remoteAddr);
        return request;
    }
}
