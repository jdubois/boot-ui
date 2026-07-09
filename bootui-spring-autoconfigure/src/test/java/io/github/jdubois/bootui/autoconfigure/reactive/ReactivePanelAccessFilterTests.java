package io.github.jdubois.bootui.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Reactive (WebFlux) sibling of {@code PanelAccessFilterTests}: proves {@link ReactivePanelAccessFilter}
 * enforces the same shared {@code BootUiPanels} per-panel enabled/read-only gating as the servlet filter,
 * over a {@code ServerWebExchange} instead of an {@code HttpServletRequest}/{@code HttpServletResponse}
 * pair.
 */
class ReactivePanelAccessFilterTests {

    private static final WebFilterChain OK_CHAIN = exchange -> {
        exchange.getResponse().setStatusCode(HttpStatus.OK);
        return Mono.empty();
    };

    private BootUiProperties properties;

    private ReactivePanelAccessFilter filter;

    @BeforeEach
    void setUp() {
        properties = new BootUiProperties();
        filter = new ReactivePanelAccessFilter(properties);
    }

    @Test
    void allowsEnabledPanelReadRequest() {
        MockServerWebExchange exchange = exchange("GET", "/bootui/api/config");

        filter.filter(exchange, OK_CHAIN).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void blocksDisabledPanelReadRequest() {
        properties.panel("config").setEnabled(false);
        MockServerWebExchange exchange = exchange("GET", "/bootui/api/config");

        filter.filter(exchange, OK_CHAIN).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange.getResponse().getHeaders().getContentType()).hasToString("application/json");
        assertThat(bodyAsString(exchange))
                .contains("\"panel\":\"config\"")
                .contains("bootui.panels.config.enabled=false");
    }

    @Test
    void blocksDisabledPanelActionRequest() {
        properties.panel("loggers").setEnabled(false);
        MockServerWebExchange exchange = exchange("POST", "/bootui/api/loggers/io.github.jdubois.bootui");

        filter.filter(exchange, OK_CHAIN).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(bodyAsString(exchange)).contains("\"panel\":\"loggers\"");
    }

    @Test
    void allowsReadOnlyPanelReadRequest() {
        properties.panel("config").setReadOnly(true);
        MockServerWebExchange exchange = exchange("GET", "/bootui/api/config");

        filter.filter(exchange, OK_CHAIN).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void blocksReadOnlyPanelActionRequest() {
        properties.panel("config").setReadOnly(true);
        MockServerWebExchange exchange = exchange("POST", "/bootui/api/config/overrides");

        filter.filter(exchange, OK_CHAIN).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(bodyAsString(exchange))
                .contains("\"panel\":\"config\"")
                .contains("bootui.panels.config.read-only=true");
    }

    @Test
    void globalReadOnlyBlocksActionCapablePanelActions() {
        properties.setReadOnly(true);
        Map<String, ActionRequest> actionRequestsByPanel = actionRequestsByPanel();

        assertThat(actionRequestsByPanel.keySet())
                .containsExactlyElementsOf(BootUiPanels.all().stream()
                        .filter(BootUiPanels.Panel::actionCapable)
                        .map(BootUiPanels.Panel::id)
                        .toList());
        for (Map.Entry<String, ActionRequest> entry : actionRequestsByPanel.entrySet()) {
            MockServerWebExchange exchange =
                    exchange(entry.getValue().method(), entry.getValue().uri());

            filter.filter(exchange, OK_CHAIN).block(Duration.ofSeconds(5));

            assertThat(exchange.getResponse().getStatusCode())
                    .as(entry.getKey())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(bodyAsString(exchange))
                    .as(entry.getKey())
                    .contains("\"panel\":\"" + entry.getKey() + "\"")
                    .contains("bootui.read-only=true");
        }
    }

    @Test
    void perPanelReadOnlyBlocksPentestingScanAction() {
        properties.panel("pentesting").setReadOnly(true);
        MockServerWebExchange exchange = exchange("POST", "/bootui/api/pentesting/scan");

        filter.filter(exchange, OK_CHAIN).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(bodyAsString(exchange))
                .contains("\"panel\":\"pentesting\"")
                .contains("bootui.panels.pentesting.read-only=true");
    }

    @Test
    void perPanelReadOnlyAllowsPentestingReportRead() {
        properties.panel("pentesting").setReadOnly(true);
        MockServerWebExchange exchange = exchange("GET", "/bootui/api/pentesting");

        filter.filter(exchange, OK_CHAIN).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void globalReadOnlyDoesNotBlockReadOnlyPanelWithoutActions() {
        properties.setReadOnly(true);
        MockServerWebExchange exchange = exchange("GET", "/bootui/api/metrics");

        filter.filter(exchange, OK_CHAIN).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void overviewShellEndpointIsNeverGatedByPanelToggle() {
        // GET /bootui/api/overview is the shell's framework-neutral chrome data source, so disabling
        // the Overview dashboard panel must not 403 it - same contract as the servlet filter.
        properties.panel(BootUiPanels.OVERVIEW).setEnabled(false);
        MockServerWebExchange exchange = exchange("GET", "/bootui/api/overview");

        filter.filter(exchange, OK_CHAIN).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void skipsCorePanelMetadataEndpoint() {
        properties.setReadOnly(true);
        MockServerWebExchange exchange = exchange("GET", "/bootui/api/panels");

        filter.filter(exchange, OK_CHAIN).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void skipsOtlpIngestionEndpoint() {
        properties.setReadOnly(true);
        MockServerWebExchange exchange = exchange("POST", "/bootui/api/otlp/v1/traces");

        filter.filter(exchange, OK_CHAIN).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void honorsCustomApiPath() {
        properties.setApiPath("/admin/api");
        properties.panel("cache").setEnabled(false);
        MockServerWebExchange exchange = exchange("POST", "/admin/api/cache/clear");

        filter.filter(exchange, OK_CHAIN).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(bodyAsString(exchange)).contains("\"panel\":\"cache\"");
    }

    private static MockServerWebExchange exchange(String method, String uri) {
        return MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.valueOf(method), uri));
    }

    private static String bodyAsString(MockServerWebExchange exchange) {
        return exchange.getResponse().getBodyAsString().block(Duration.ofSeconds(5));
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
        requests.put("cache", new ActionRequest("POST", "/bootui/api/cache/clear"));
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
        requests.put("mcp-server", new ActionRequest("POST", "/bootui/api/mcp-server/toggle"));
        requests.put("activity", new ActionRequest("POST", "/bootui/api/activity/use-existing-datasource"));
        requests.put("email", new ActionRequest("DELETE", "/bootui/api/email"));
        return requests;
    }

    private record ActionRequest(String method, String uri) {}
}
