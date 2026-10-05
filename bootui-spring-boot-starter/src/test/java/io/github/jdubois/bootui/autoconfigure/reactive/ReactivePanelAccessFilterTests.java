package io.github.jdubois.bootui.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.conformance.BootUiApiContractCatalog;
import io.github.jdubois.bootui.conformance.BootUiApiContractCatalog.ActionContract;
import io.github.jdubois.bootui.conformance.BootUiApiContractCatalog.Runtime;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import java.time.Duration;
import java.util.List;
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
        List<ActionContract> actions = BootUiApiContractCatalog.actions(Runtime.SPRING_WEBFLUX).stream()
                .filter(action -> action.panelId() != null)
                .toList();

        assertThat(actions.stream().map(ActionContract::panelId).collect(java.util.stream.Collectors.toSet()))
                .containsExactlyInAnyOrderElementsOf(BootUiPanels.all().stream()
                        .filter(BootUiPanels.Panel::actionCapable)
                        .map(BootUiPanels.Panel::id)
                        .filter(id -> !BootUiPanels.HTTP_SESSIONS.equals(id))
                        .toList());
        for (ActionContract action : actions) {
            MockServerWebExchange exchange = exchange(action.method(), "/bootui/api" + action.relativePath());

            filter.filter(exchange, OK_CHAIN).block(Duration.ofSeconds(5));

            assertThat(exchange.getResponse().getStatusCode()).as(action.id()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(bodyAsString(exchange))
                    .as(action.id())
                    .contains("\"panel\":\"" + action.panelId() + "\"")
                    .contains("bootui.read-only=true");
        }
    }

    @Test
    void globalReadOnlyBlocksCatalogedNonPanelBrowserWrites() {
        properties.setReadOnly(true);
        List<ActionContract> actions = BootUiApiContractCatalog.actions(Runtime.SPRING_WEBFLUX).stream()
                .filter(action -> action.panelId() == null && action.blockedByGlobalReadOnly())
                .toList();

        assertThat(actions).isNotEmpty();
        for (ActionContract action : actions) {
            MockServerWebExchange exchange = exchange(action.method(), "/bootui/api" + action.relativePath());

            filter.filter(exchange, OK_CHAIN).block(Duration.ofSeconds(5));

            assertThat(exchange.getResponse().getStatusCode()).as(action.id()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(bodyAsString(exchange))
                    .as(action.id())
                    .contains("\"panel\":\"dismissed-rules\"")
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
    void perPanelReadOnlyBlocksRestClientActionsButAllowsReportRead() {
        properties.panel(BootUiPanels.REST_CLIENT_TRACE).setReadOnly(true);
        MockServerWebExchange action = exchange("POST", "/bootui/api/rest-client-trace/recording");
        MockServerWebExchange report = exchange("GET", "/bootui/api/rest-client-trace");

        filter.filter(action, OK_CHAIN).block(Duration.ofSeconds(5));
        filter.filter(report, OK_CHAIN).block(Duration.ofSeconds(5));

        assertThat(action.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(bodyAsString(action))
                .contains("\"panel\":\"rest-client-trace\"")
                .contains("bootui.panels.rest-client-trace.read-only=true");
        assertThat(report.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
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
}
