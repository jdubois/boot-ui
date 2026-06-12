package io.github.jdubois.bootui.autoconfigure.safety;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.panel.BootUiPanels;
import java.util.LinkedHashMap;
import java.util.Map;
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
        Map<String, ActionRequest> actionRequestsByPanel = actionRequestsByPanel();

        assertThat(actionRequestsByPanel.keySet())
                .containsExactlyElementsOf(BootUiPanels.all().stream()
                        .filter(BootUiPanels.Panel::actionCapable)
                        .map(BootUiPanels.Panel::id)
                        .toList());
        for (Map.Entry<String, ActionRequest> entry : actionRequestsByPanel.entrySet()) {
            MockHttpServletRequest request =
                    request(entry.getValue().method(), entry.getValue().uri());
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, new MockFilterChain());

            assertThat(response.getStatus()).as(entry.getKey()).isEqualTo(403);
            assertThat(response.getContentAsString())
                    .as(entry.getKey())
                    .contains("\"panel\":\"" + entry.getKey() + "\"")
                    .contains("bootui.read-only=true");
        }
    }

    @Test
    void perPanelReadOnlyBlocksPentestingScanAction() throws Exception {
        properties.panel("pentesting").setReadOnly(true);
        MockHttpServletRequest request = request("POST", "/bootui/api/pentesting/scan");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString())
                .contains("\"panel\":\"pentesting\"")
                .contains("bootui.panels.pentesting.read-only=true");
    }

    @Test
    void perPanelReadOnlyAllowsPentestingReportRead() throws Exception {
        properties.panel("pentesting").setReadOnly(true);
        MockHttpServletRequest request = request("GET", "/bootui/api/pentesting");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
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
        properties.panel("spring-cache").setEnabled(false);
        MockHttpServletRequest request = request("POST", "/my-app/admin/api/spring-cache/clear");
        request.setContextPath("/my-app");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("\"panel\":\"spring-cache\"");
    }

    private MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        return request;
    }

    private Map<String, ActionRequest> actionRequestsByPanel() {
        Map<String, ActionRequest> requests = new LinkedHashMap<>();
        requests.put("http-sessions", new ActionRequest("POST", "/bootui/api/http-sessions/session-key/invalidate"));
        requests.put("heap-dump", new ActionRequest("POST", "/bootui/api/heap-dump/capture"));
        requests.put("threads", new ActionRequest("POST", "/bootui/api/threads/download"));
        requests.put("memory", new ActionRequest("POST", "/bootui/api/memory/scan"));
        requests.put("graalvm", new ActionRequest("POST", "/bootui/api/graalvm/scan"));
        requests.put("config", new ActionRequest("POST", "/bootui/api/config/overrides"));
        requests.put("loggers", new ActionRequest("POST", "/bootui/api/loggers/io.github.jdubois.bootui"));
        requests.put("security", new ActionRequest("POST", "/bootui/api/security/scan"));
        requests.put("pentesting", new ActionRequest("POST", "/bootui/api/pentesting/scan"));
        requests.put("hibernate", new ActionRequest("POST", "/bootui/api/hibernate/scan"));
        requests.put("spring-cache", new ActionRequest("POST", "/bootui/api/spring-cache/clear"));
        requests.put("traces", new ActionRequest("DELETE", "/bootui/api/traces"));
        requests.put("exceptions", new ActionRequest("DELETE", "/bootui/api/exceptions"));
        requests.put("http-probe", new ActionRequest("POST", "/bootui/api/http-probe"));
        requests.put("architecture", new ActionRequest("POST", "/bootui/api/architecture/scan"));
        requests.put("vulnerabilities", new ActionRequest("POST", "/bootui/api/vulnerabilities/scan"));
        requests.put("devtools", new ActionRequest("POST", "/bootui/api/devtools/restart"));
        requests.put("dev-services", new ActionRequest("POST", "/bootui/api/dev-services/services/demo/restart"));
        requests.put("flyway", new ActionRequest("POST", "/bootui/api/flyway/migrate"));
        requests.put("liquibase", new ActionRequest("POST", "/bootui/api/liquibase/update"));
        requests.put("github", new ActionRequest("POST", "/bootui/api/github/refresh"));
        requests.put("rest-api", new ActionRequest("POST", "/bootui/api/rest-api/scan"));
        requests.put("spring", new ActionRequest("POST", "/bootui/api/spring/scan"));
        requests.put("crac", new ActionRequest("POST", "/bootui/api/crac/scan"));
        requests.put("sql-trace", new ActionRequest("POST", "/bootui/api/sql-trace/clear"));
        return requests;
    }

    private record ActionRequest(String method, String uri) {}
}
