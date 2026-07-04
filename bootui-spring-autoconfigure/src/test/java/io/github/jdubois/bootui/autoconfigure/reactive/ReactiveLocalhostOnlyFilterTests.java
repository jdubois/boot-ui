package io.github.jdubois.bootui.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties.Mode;
import io.github.jdubois.bootui.engine.safety.ContainerGatewayDetector;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Reactive (WebFlux) sibling of {@code LocalhostOnlyFilterTests}: proves the {@code ServerWebExchange}
 * translation layer builds the same {@code LocalhostGuardRequest}/{@code LocalhostGuardConfig} and
 * renders the same canonical 403 JSON as the servlet filter. The underlying policy itself is
 * exhaustively pinned by {@code LocalhostGuardTests} in {@code bootui-engine}, so this suite focuses on
 * the adapter-specific wiring (exchange to guard request, decision to response) rather than re-proving
 * every policy branch.
 */
class ReactiveLocalhostOnlyFilterTests {

    private static final WebFilterChain OK_CHAIN = exchange -> {
        exchange.getResponse().setStatusCode(HttpStatus.OK);
        return Mono.empty();
    };

    private static final String GATEWAY = "192.168.65.1";

    private BootUiProperties properties;

    private ReactiveLocalhostOnlyFilter filter;

    @BeforeEach
    void setUp() {
        properties = new BootUiProperties();
        filter = new ReactiveLocalhostOnlyFilter(properties);
    }

    @Test
    void allowsLoopbackIpv4Request() {
        MockServerWebExchange exchange = bootUiExchange("GET", "/bootui/api/overview", "127.0.0.1");

        filter.filter(exchange, OK_CHAIN).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void allowsLoopbackIpv6Request() {
        MockServerWebExchange exchange = bootUiExchange("GET", "/bootui/", "::1");

        filter.filter(exchange, OK_CHAIN).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void rejectsNonLoopbackRequestWithJsonBody() {
        MockServerWebExchange exchange = bootUiExchange("GET", "/bootui/api/config", "10.0.0.5");

        filter.filter(exchange, OK_CHAIN).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange.getResponse().getHeaders().getContentType()).hasToString("application/json");
        assertThat(bodyAsString(exchange)).contains("loopback");
    }

    @Test
    void allowsNonLoopbackWhenExplicitlyOptedIn() {
        properties.setAllowNonLocalhost(true);
        MockServerWebExchange exchange = bootUiExchange("GET", "/bootui/api/health", "10.0.0.5");

        filter.filter(exchange, OK_CHAIN).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void skipsFilterForNonBootUiPaths() {
        MockServerWebExchange exchange = bootUiExchange("GET", "/api/sample/hello", "10.0.0.5");

        filter.filter(exchange, OK_CHAIN).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void rejectsRequestWithUnresolvedRemoteAddress() {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get("/bootui/");
        builder.remoteAddress(InetSocketAddress.createUnresolved("not-an-address", 0));
        MockServerWebExchange exchange = MockServerWebExchange.from(builder);

        filter.filter(exchange, OK_CHAIN).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void rejectsRebindingHostHeader() {
        MockServerWebExchange exchange =
                bootUiExchange("GET", "/bootui/api/overview", "127.0.0.1", "Host", "attacker.example.com");

        filter.filter(exchange, OK_CHAIN).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(bodyAsString(exchange)).contains("Host");
    }

    @Test
    void allowsLoopbackHostHeaderWithPort() {
        MockServerWebExchange exchange =
                bootUiExchange("GET", "/bootui/api/overview", "127.0.0.1", "Host", "localhost:8080");

        filter.filter(exchange, OK_CHAIN).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void allowsConfiguredAllowedHost() {
        properties.setAllowedHosts(new String[] {"app.local"});
        MockServerWebExchange exchange =
                bootUiExchange("GET", "/bootui/api/overview", "127.0.0.1", "Host", "app.local:8080");

        filter.filter(exchange, OK_CHAIN).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void rejectsCrossSiteOriginOnStateChangingRequest() {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.post("/bootui/api/config")
                .header("Host", "localhost:8080")
                .header("Origin", "http://evil.example.com");
        builder.remoteAddress(new InetSocketAddress("127.0.0.1", 54321));
        MockServerWebExchange exchange = MockServerWebExchange.from(builder);

        filter.filter(exchange, OK_CHAIN).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(bodyAsString(exchange)).contains("cross-site");
    }

    @Test
    void allowsSameOriginStateChangingRequest() {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.post("/bootui/api/config")
                .header("Host", "localhost:8080")
                .header("Origin", "http://localhost:8080");
        builder.remoteAddress(new InetSocketAddress("127.0.0.1", 54321));
        MockServerWebExchange exchange = MockServerWebExchange.from(builder);

        filter.filter(exchange, OK_CHAIN).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void allowsCrossSiteOriginOnSafeMethod() {
        // Safe (GET) methods are never subject to the cross-site-write check, only the Host allow-list.
        MockServerWebExchange exchange =
                bootUiExchange("GET", "/bootui/api/overview", "127.0.0.1", "Host", "localhost:8080");

        filter.filter(exchange, OK_CHAIN).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void allowsTrustedSourceRangeWithAcceptableHost() {
        properties.setTrustedProxies(new String[] {"172.16.0.0/12"});
        MockServerWebExchange exchange =
                bootUiExchange("GET", "/bootui/api/overview", "172.17.0.1", "Host", "localhost:8080");

        filter.filter(exchange, OK_CHAIN).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void ignoresMalformedTrustedProxyEntries() {
        properties.setTrustedProxies(new String[] {"not-a-cidr", "172.16.0.0/12", "  "});
        MockServerWebExchange exchange =
                bootUiExchange("GET", "/bootui/api/overview", "172.17.0.1", "Host", "localhost:8080");

        filter.filter(exchange, OK_CHAIN).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void autoTrustsContainerGateway() {
        properties.setTrustContainerGateway(Mode.AUTO);
        ReactiveLocalhostOnlyFilter gatewayFilter =
                new ReactiveLocalhostOnlyFilter(properties, new FakeGatewayDetector(true, GATEWAY));
        MockServerWebExchange exchange =
                bootUiExchange("GET", "/bootui/api/overview", GATEWAY, "Host", "localhost:8080");

        gatewayFilter.filter(exchange, OK_CHAIN).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void stillRejectsDisallowedHostFromTrustedGateway() {
        properties.setTrustContainerGateway(Mode.AUTO);
        ReactiveLocalhostOnlyFilter gatewayFilter =
                new ReactiveLocalhostOnlyFilter(properties, new FakeGatewayDetector(true, GATEWAY));
        MockServerWebExchange exchange =
                bootUiExchange("GET", "/bootui/api/overview", GATEWAY, "Host", "attacker.example.com");

        gatewayFilter.filter(exchange, OK_CHAIN).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(bodyAsString(exchange)).contains("Host");
    }

    private static MockServerWebExchange bootUiExchange(String method, String uri, String remoteAddr) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.method(HttpMethod.valueOf(method), uri);
        builder.remoteAddress(new InetSocketAddress(remoteAddr, 54321));
        return MockServerWebExchange.from(builder);
    }

    private static MockServerWebExchange bootUiExchange(
            String method, String uri, String remoteAddr, String headerName, String headerValue) {
        MockServerHttpRequest.BaseBuilder<?> builder =
                MockServerHttpRequest.method(HttpMethod.valueOf(method), uri).header(headerName, headerValue);
        builder.remoteAddress(new InetSocketAddress(remoteAddr, 54321));
        return MockServerWebExchange.from(builder);
    }

    private static String bodyAsString(MockServerWebExchange exchange) {
        return exchange.getResponse().getBodyAsString().block(Duration.ofSeconds(5));
    }

    /** Fake detector that returns a fixed container status and gateway without touching the filesystem or DNS. */
    private static final class FakeGatewayDetector extends ContainerGatewayDetector {

        private final boolean inContainer;
        private final InetAddress routeGateway;

        private FakeGatewayDetector(boolean inContainer, String routeGateway) {
            try {
                this.inContainer = inContainer;
                this.routeGateway = routeGateway == null ? null : InetAddress.getByName(routeGateway);
            } catch (Exception e) {
                throw new IllegalArgumentException(e);
            }
        }

        @Override
        public boolean isInContainer() {
            return inContainer;
        }

        @Override
        public Optional<InetAddress> defaultGateway() {
            return Optional.ofNullable(routeGateway);
        }

        @Override
        public Set<InetAddress> dockerDesktopGateways() {
            return Set.of();
        }
    }
}
