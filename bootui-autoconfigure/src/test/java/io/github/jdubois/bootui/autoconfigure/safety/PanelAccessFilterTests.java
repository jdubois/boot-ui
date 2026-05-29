package io.github.jdubois.bootui.autoconfigure.safety;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PanelAccessFilterTests {

    private BootUiProperties properties;

    private PanelAccessFilter filter;

    @BeforeEach
    void setUp() {
        properties = new BootUiProperties();
        filter = new PanelAccessFilter(properties);
    }

    @Test
    void allowsEnabledPanelReadRequest() throws Exception {
        MockHttpServletRequest request = request("GET", "/bootui/api/config");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void blocksDisabledPanelReadRequest() throws Exception {
        properties.panel("config").setEnabled(false);
        MockHttpServletRequest request = request("GET", "/bootui/api/config");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getContentAsString())
                .contains("\"panel\":\"config\"")
                .contains("bootui.panels.config.enabled=false");
    }

    @Test
    void blocksDisabledPanelActionRequest() throws Exception {
        properties.panel("loggers").setEnabled(false);
        MockHttpServletRequest request = request("POST", "/bootui/api/loggers/io.github.jdubois.bootui");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("\"panel\":\"loggers\"");
    }

    @Test
    void allowsReadOnlyPanelReadRequest() throws Exception {
        properties.panel("config").setReadOnly(true);
        MockHttpServletRequest request = request("GET", "/bootui/api/config");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void blocksReadOnlyPanelActionRequest() throws Exception {
        properties.panel("config").setReadOnly(true);
        MockHttpServletRequest request = request("POST", "/bootui/api/config/overrides");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString())
                .contains("\"panel\":\"config\"")
                .contains("bootui.panels.config.read-only=true");
    }

    @Test
    void globalReadOnlyBlocksActionCapablePanelActions() throws Exception {
        properties.setReadOnly(true);
        MockHttpServletRequest request = request("DELETE", "/bootui/api/traces");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("bootui.read-only=true");
    }

    @Test
    void globalReadOnlyDoesNotBlockReadOnlyPanelWithoutActions() throws Exception {
        properties.setReadOnly(true);
        MockHttpServletRequest request = request("GET", "/bootui/api/overview");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void skipsCorePanelMetadataEndpoint() throws Exception {
        properties.setReadOnly(true);
        MockHttpServletRequest request = request("GET", "/bootui/api/panels");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void skipsOtlpIngestionEndpoint() throws Exception {
        properties.setReadOnly(true);
        MockHttpServletRequest request = request("POST", "/bootui/api/otlp/v1/traces");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void honorsCustomApiPathAndContextPath() throws Exception {
        properties.setApiPath("/admin/api");
        properties.panel("cache").setEnabled(false);
        MockHttpServletRequest request = request("POST", "/my-app/admin/api/cache/clear");
        request.setContextPath("/my-app");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("\"panel\":\"cache\"");
    }

    private MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        return request;
    }
}
