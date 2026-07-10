package io.github.jdubois.bootui.autoconfigure.safety;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.engine.safety.BootUiSecurityHeaders;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SecurityHeadersFilterTests {

    private BootUiProperties properties;

    private SecurityHeadersFilter filter;

    @BeforeEach
    void setUp() {
        properties = new BootUiProperties();
        filter = new SecurityHeadersFilter(properties);
    }

    // --- scope ---------------------------------------------------------------------------------

    @Test
    void appliesHeadersToApiRequests() throws Exception {
        MockHttpServletRequest request = request("GET", "/bootui/api/overview");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertCommonSecurityHeaders(response);
    }

    @Test
    void appliesHeadersToShellRequest() throws Exception {
        MockHttpServletRequest request = request("GET", "/bootui/");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertCommonSecurityHeaders(response);
    }

    @Test
    void skipsNonBootUiPaths() throws Exception {
        MockHttpServletRequest request = request("GET", "/api/hello");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(BootUiSecurityHeaders.X_CONTENT_TYPE_OPTIONS))
                .isNull();
    }

    // --- CSP ---------------------------------------------------------------------------------

    @Test
    void contentSecurityPolicyIsSet() throws Exception {
        MockHttpServletResponse response = applyFilter("/bootui/api/config");

        assertThat(response.getHeader(BootUiSecurityHeaders.CONTENT_SECURITY_POLICY))
                .isEqualTo(BootUiSecurityHeaders.CSP_VALUE);
    }

    @Test
    void cspContainsFrameAncestorsNone() throws Exception {
        MockHttpServletResponse response = applyFilter("/bootui/api/overview");

        assertThat(response.getHeader(BootUiSecurityHeaders.CONTENT_SECURITY_POLICY))
                .contains("frame-ancestors 'none'");
    }

    @Test
    void cspDoesNotContainUnsafeEval() throws Exception {
        MockHttpServletResponse response = applyFilter("/bootui/");

        assertThat(response.getHeader(BootUiSecurityHeaders.CONTENT_SECURITY_POLICY))
                .doesNotContain("unsafe-eval");
    }

    // --- X-Frame-Options and X-Content-Type-Options ----------------------------------------

    @Test
    void xFrameOptionsIsDeny() throws Exception {
        MockHttpServletResponse response = applyFilter("/bootui/api/config");

        assertThat(response.getHeader(BootUiSecurityHeaders.X_FRAME_OPTIONS)).isEqualTo(BootUiSecurityHeaders.DENY);
    }

    @Test
    void xContentTypeOptionsIsNosniff() throws Exception {
        MockHttpServletResponse response = applyFilter("/bootui/api/loggers");

        assertThat(response.getHeader(BootUiSecurityHeaders.X_CONTENT_TYPE_OPTIONS))
                .isEqualTo(BootUiSecurityHeaders.NOSNIFF);
    }

    // --- Referrer-Policy -------------------------------------------------------------------

    @Test
    void referrerPolicyIsSet() throws Exception {
        MockHttpServletResponse response = applyFilter("/bootui/api/health");

        assertThat(response.getHeader(BootUiSecurityHeaders.REFERRER_POLICY))
                .isEqualTo(BootUiSecurityHeaders.STRICT_ORIGIN_WHEN_CROSS_ORIGIN);
    }

    // --- Cache-Control by path type --------------------------------------------------------

    @Test
    void apiPathGetsNoStoreCacheControl() throws Exception {
        MockHttpServletResponse response = applyFilter("/bootui/api/config");

        assertThat(response.getHeader(BootUiSecurityHeaders.CACHE_CONTROL)).isEqualTo(BootUiSecurityHeaders.NO_STORE);
        assertThat(response.getHeader(BootUiSecurityHeaders.PRAGMA)).isEqualTo(BootUiSecurityHeaders.PRAGMA_NO_CACHE);
    }

    @Test
    void unhashedAssetIsRevalidated() throws Exception {
        MockHttpServletResponse response = applyFilter("/bootui/assets/index-abc123.js");

        assertThat(response.getHeader(BootUiSecurityHeaders.CACHE_CONTROL)).isEqualTo(BootUiSecurityHeaders.NO_CACHE);
    }

    @Test
    void contentHashedAssetGetsImmutableCacheControl() throws Exception {
        MockHttpServletResponse response = applyFilter("/bootui/assets/index-C2x2BcDS.js");

        assertThat(response.getHeader(BootUiSecurityHeaders.CACHE_CONTROL)).isEqualTo(BootUiSecurityHeaders.IMMUTABLE);
        assertThat(response.getHeader(BootUiSecurityHeaders.PRAGMA)).isNull();
    }

    @Test
    void missingHashedAssetIsNotCachedAsImmutable() throws Exception {
        MockHttpServletRequest request = request("GET", "/bootui/assets/missing-C2x2BcDS.js");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (downstreamRequest, downstreamResponse) -> {
            ((HttpServletResponse) downstreamResponse).setStatus(404);
        });

        assertThat(response.getHeader(BootUiSecurityHeaders.CACHE_CONTROL)).isEqualTo(BootUiSecurityHeaders.NO_CACHE);
        assertThat(response.getHeader(BootUiSecurityHeaders.PRAGMA)).isEqualTo(BootUiSecurityHeaders.PRAGMA_NO_CACHE);
    }

    @Test
    void shellGetsNoCacheCacheControl() throws Exception {
        MockHttpServletResponse response = applyFilter("/bootui/");

        assertThat(response.getHeader(BootUiSecurityHeaders.CACHE_CONTROL)).isEqualTo(BootUiSecurityHeaders.NO_CACHE);
        assertThat(response.getHeader(BootUiSecurityHeaders.PRAGMA)).isEqualTo(BootUiSecurityHeaders.PRAGMA_NO_CACHE);
    }

    @Test
    void indexHtmlGetsNoCacheCacheControl() throws Exception {
        MockHttpServletResponse response = applyFilter("/bootui/index.html");

        assertThat(response.getHeader(BootUiSecurityHeaders.CACHE_CONTROL)).isEqualTo(BootUiSecurityHeaders.NO_CACHE);
    }

    // --- context-path handling -------------------------------------------------------------

    @Test
    void contextPathIsStrippedForCacheClassification() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app/bootui/api/overview");
        request.setContextPath("/app");
        request.setRequestURI("/app/bootui/api/overview");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(BootUiSecurityHeaders.CACHE_CONTROL)).isEqualTo(BootUiSecurityHeaders.NO_STORE);
    }

    @Test
    void preservesExistingHostSecurityPolicyButOwnsCacheSemantics() throws Exception {
        MockHttpServletRequest request = request("GET", "/bootui/api/overview");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (downstreamRequest, downstreamResponse) -> {
            HttpServletResponse httpResponse = (HttpServletResponse) downstreamResponse;
            httpResponse.setStatus(201);
            assertThat(httpResponse.containsHeader(BootUiSecurityHeaders.CONTENT_SECURITY_POLICY))
                    .as("BootUI's baseline must not make a downstream host writer skip its policy")
                    .isFalse();
            httpResponse.addHeader(BootUiSecurityHeaders.CONTENT_SECURITY_POLICY, "default-src 'none'");
            httpResponse.setHeader(BootUiSecurityHeaders.CACHE_CONTROL, "public");
        });

        assertThat(response.getHeaders(BootUiSecurityHeaders.CONTENT_SECURITY_POLICY))
                .containsExactly("default-src 'none'");
        assertThat(response.getHeaders(BootUiSecurityHeaders.CACHE_CONTROL))
                .containsExactly(BootUiSecurityHeaders.NO_STORE);
    }

    @Test
    void thrownHashedAssetResponseIsNotCachedAsImmutable() {
        MockHttpServletRequest request = request("GET", "/bootui/assets/index-C2x2BcDS.js");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilter(request, response, (downstreamRequest, downstreamResponse) -> {
                    throw new ServletException("render failed");
                }))
                .isInstanceOf(ServletException.class);

        assertThat(response.getHeader(BootUiSecurityHeaders.CACHE_CONTROL)).isEqualTo(BootUiSecurityHeaders.NO_CACHE);
        assertThat(response.getHeader(BootUiSecurityHeaders.PRAGMA)).isEqualTo(BootUiSecurityHeaders.PRAGMA_NO_CACHE);
    }

    // --- helpers ---------------------------------------------------------------------------

    private MockHttpServletResponse applyFilter(String path) throws Exception {
        MockHttpServletRequest request = request("GET", path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private void assertCommonSecurityHeaders(MockHttpServletResponse response) {
        assertThat(response.getHeader(BootUiSecurityHeaders.CONTENT_SECURITY_POLICY))
                .isNotNull();
        assertThat(response.getHeader(BootUiSecurityHeaders.X_CONTENT_TYPE_OPTIONS))
                .isEqualTo(BootUiSecurityHeaders.NOSNIFF);
        assertThat(response.getHeader(BootUiSecurityHeaders.X_FRAME_OPTIONS)).isEqualTo(BootUiSecurityHeaders.DENY);
        assertThat(response.getHeader(BootUiSecurityHeaders.REFERRER_POLICY))
                .isEqualTo(BootUiSecurityHeaders.STRICT_ORIGIN_WHEN_CROSS_ORIGIN);
        assertThat(response.getHeader(BootUiSecurityHeaders.PERMISSIONS_POLICY))
                .isEqualTo(BootUiSecurityHeaders.PERMISSIONS_POLICY_VALUE);
        assertThat(response.getHeader(BootUiSecurityHeaders.CACHE_CONTROL)).isNotNull();
    }

    private MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        request.setRemoteAddr("127.0.0.1");
        return request;
    }
}
