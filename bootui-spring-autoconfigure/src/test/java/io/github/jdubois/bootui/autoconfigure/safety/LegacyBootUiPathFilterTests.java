package io.github.jdubois.bootui.autoconfigure.safety;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class LegacyBootUiPathFilterTests {

    @Test
    void defaultPathRemainsAvailable() throws Exception {
        BootUiProperties properties = new BootUiProperties();
        MockHttpServletResponse response = filter(properties, "/bootui/assets/app.js", "");

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void customPathBlocksInternalMountWithContextPath() throws Exception {
        BootUiProperties properties = new BootUiProperties();
        properties.setPath("/console");
        MockHttpServletResponse response = filter(properties, "/host/bootui/assets/app.js", "/host");

        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    void explicitApiPathUnderInternalMountRemainsAvailable() throws Exception {
        BootUiProperties properties = new BootUiProperties();
        properties.setPath("/console");
        properties.setApiPath("/bootui/api");
        MockHttpServletResponse response = filter(properties, "/bootui/api/overview", "");

        assertThat(response.getStatus()).isEqualTo(200);
    }

    private static MockHttpServletResponse filter(BootUiProperties properties, String uri, String contextPath)
            throws Exception {
        LegacyBootUiPathFilter filter = new LegacyBootUiPathFilter(properties);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        request.setContextPath(contextPath);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
