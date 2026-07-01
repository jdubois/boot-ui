package io.github.jdubois.bootui.autoconfigure.safety;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties.Mode;
import io.github.jdubois.bootui.engine.safety.ContainerGatewayDetector;
import jakarta.servlet.FilterChain;
import java.net.InetAddress;
import java.util.Optional;
import java.util.Set;
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

    @Test
    void rejectsWildcardIpv4Address() throws Exception {
        MockHttpServletRequest request = bootUiRequest("/bootui/", "0.0.0.0");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void rejectsWildcardIpv6Address() throws Exception {
        MockHttpServletRequest request = bootUiRequest("/bootui/", "::");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void rejectsRebindingHostHeader() throws Exception {
        MockHttpServletRequest request = bootUiRequest("/bootui/api/overview", "127.0.0.1");
        request.addHeader("Host", "attacker.example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("Host");
    }

    @Test
    void allowsLoopbackHostHeaderWithPort() throws Exception {
        MockHttpServletRequest request = bootUiRequest("/bootui/api/overview", "127.0.0.1");
        request.addHeader("Host", "localhost:8080");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void allowsBracketedIpv6HostHeader() throws Exception {
        MockHttpServletRequest request = bootUiRequest("/bootui/api/overview", "::1");
        request.addHeader("Host", "[::1]:8080");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void allowsConfiguredAllowedHost() throws Exception {
        properties.setAllowedHosts(new String[] {"app.local"});
        MockHttpServletRequest request = bootUiRequest("/bootui/api/overview", "127.0.0.1");
        request.addHeader("Host", "app.local:8080");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsCrossSiteOriginOnStateChangingRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/bootui/api/config");
        request.setRequestURI("/bootui/api/config");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Host", "localhost:8080");
        request.addHeader("Origin", "http://evil.example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("cross-site");
    }

    @Test
    void allowsSameOriginStateChangingRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/bootui/api/config");
        request.setRequestURI("/bootui/api/config");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Host", "localhost:8080");
        request.addHeader("Origin", "http://localhost:8080");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void allowsStateChangingRequestWithoutOrigin() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/bootui/api/config");
        request.setRequestURI("/bootui/api/config");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Host", "localhost:8080");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsSecFetchSiteCrossSiteOnStateChangingRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/bootui/api/config");
        request.setRequestURI("/bootui/api/config");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Host", "localhost:8080");
        request.addHeader("Sec-Fetch-Site", "cross-site");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void allowsCrossSiteOriginOnSafeMethod() throws Exception {
        MockHttpServletRequest request = bootUiRequest("/bootui/api/overview", "127.0.0.1");
        request.addHeader("Host", "localhost:8080");
        request.addHeader("Origin", "http://evil.example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void allowsBlankHostHeader() throws Exception {
        MockHttpServletRequest request = bootUiRequest("/bootui/api/overview", "127.0.0.1");
        request.addHeader("Host", "   ");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void allowsHostHeaderCaseInsensitively() throws Exception {
        MockHttpServletRequest request = bootUiRequest("/bootui/api/overview", "127.0.0.1");
        request.addHeader("Host", "LocalHost:8080");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void allowsTrustedSourceRangeWithAcceptableHost() throws Exception {
        properties.setTrustedProxies(new String[] {"172.16.0.0/12"});
        MockHttpServletRequest request = bootUiRequest("/bootui/api/overview", "172.17.0.1");
        request.addHeader("Host", "localhost:8080");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsTrustedSourceRangeWithDisallowedHost() throws Exception {
        properties.setTrustedProxies(new String[] {"172.16.0.0/12"});
        MockHttpServletRequest request = bootUiRequest("/bootui/api/overview", "172.17.0.1");
        request.addHeader("Host", "attacker.example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("Host");
    }

    @Test
    void rejectsCrossSiteWriteFromTrustedSourceRange() throws Exception {
        properties.setTrustedProxies(new String[] {"172.16.0.0/12"});
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/bootui/api/config");
        request.setRequestURI("/bootui/api/config");
        request.setRemoteAddr("172.17.0.1");
        request.addHeader("Host", "localhost:8080");
        request.addHeader("Origin", "http://evil.example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("cross-site");
    }

    @Test
    void rejectsSourceOutsideTrustedRange() throws Exception {
        properties.setTrustedProxies(new String[] {"172.16.0.0/12"});
        MockHttpServletRequest request = bootUiRequest("/bootui/api/overview", "10.0.0.5");
        request.addHeader("Host", "localhost:8080");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void allowsTrustedIpv6SourceRange() throws Exception {
        properties.setTrustedProxies(new String[] {"fd00::/8"});
        MockHttpServletRequest request = bootUiRequest("/bootui/api/overview", "fd00::1234");
        request.addHeader("Host", "localhost:8080");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void ignoresMalformedTrustedProxyEntries() throws Exception {
        properties.setTrustedProxies(new String[] {"not-a-cidr", "172.16.0.0/12", "  "});
        MockHttpServletRequest request = bootUiRequest("/bootui/api/overview", "172.17.0.1");
        request.addHeader("Host", "localhost:8080");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    private MockHttpServletRequest bootUiRequest(String uri, String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    // -------------------------------------------------------------------------
    // bootui.trust-container-gateway
    // -------------------------------------------------------------------------

    private static final String GATEWAY = "192.168.65.1";

    private LocalhostOnlyFilter filterWithGateway(boolean inContainer, String gateway) {
        return new LocalhostOnlyFilter(properties, new FakeGatewayDetector(inContainer, gateway));
    }

    @Test
    void autoTrustsContainerGatewayWhenInContainer() throws Exception {
        properties.setTrustContainerGateway(Mode.AUTO);
        LocalhostOnlyFilter gatewayFilter = filterWithGateway(true, GATEWAY);
        MockHttpServletRequest request = bootUiRequest("/bootui/api/overview", GATEWAY);
        request.addHeader("Host", "localhost:8080");
        MockHttpServletResponse response = new MockHttpServletResponse();

        gatewayFilter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void autoDoesNotTrustContainerGatewayWhenNotInContainer() throws Exception {
        properties.setTrustContainerGateway(Mode.AUTO);
        LocalhostOnlyFilter gatewayFilter = filterWithGateway(false, GATEWAY);
        MockHttpServletRequest request = bootUiRequest("/bootui/api/overview", GATEWAY);
        request.addHeader("Host", "localhost:8080");
        MockHttpServletResponse response = new MockHttpServletResponse();

        gatewayFilter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void onTrustsDetectedGatewayEvenWhenNotInContainer() throws Exception {
        properties.setTrustContainerGateway(Mode.ON);
        LocalhostOnlyFilter gatewayFilter = filterWithGateway(false, GATEWAY);
        MockHttpServletRequest request = bootUiRequest("/bootui/api/overview", GATEWAY);
        request.addHeader("Host", "localhost:8080");
        MockHttpServletResponse response = new MockHttpServletResponse();

        gatewayFilter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void offNeverTrustsContainerGateway() throws Exception {
        properties.setTrustContainerGateway(Mode.OFF);
        LocalhostOnlyFilter gatewayFilter = filterWithGateway(true, GATEWAY);
        MockHttpServletRequest request = bootUiRequest("/bootui/api/overview", GATEWAY);
        request.addHeader("Host", "localhost:8080");
        MockHttpServletResponse response = new MockHttpServletResponse();

        gatewayFilter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void doesNotTrustNonGatewaySourceWhenInContainer() throws Exception {
        properties.setTrustContainerGateway(Mode.AUTO);
        LocalhostOnlyFilter gatewayFilter = filterWithGateway(true, GATEWAY);
        MockHttpServletRequest request = bootUiRequest("/bootui/api/overview", "192.168.65.99");
        request.addHeader("Host", "localhost:8080");
        MockHttpServletResponse response = new MockHttpServletResponse();

        gatewayFilter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void stillRejectsDisallowedHostFromTrustedGateway() throws Exception {
        properties.setTrustContainerGateway(Mode.AUTO);
        LocalhostOnlyFilter gatewayFilter = filterWithGateway(true, GATEWAY);
        MockHttpServletRequest request = bootUiRequest("/bootui/api/overview", GATEWAY);
        request.addHeader("Host", "attacker.example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();

        gatewayFilter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("Host");
    }

    @Test
    void stillRejectsCrossSiteWriteFromTrustedGateway() throws Exception {
        properties.setTrustContainerGateway(Mode.AUTO);
        LocalhostOnlyFilter gatewayFilter = filterWithGateway(true, GATEWAY);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/bootui/api/config");
        request.setRequestURI("/bootui/api/config");
        request.setRemoteAddr(GATEWAY);
        request.addHeader("Host", "localhost:8080");
        request.addHeader("Origin", "http://evil.example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();

        gatewayFilter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("cross-site");
    }

    @Test
    void autoTrustsDockerDesktopGatewayResolvedViaDnsNotInRouteTable() throws Exception {
        // Docker Desktop: the route-table default gateway is the docker0 bridge (172.17.0.1), but
        // published-port traffic is SNAT'd from gateway.docker.internal (192.168.65.1), which is
        // discovered via DNS rather than /proc/net/route. AUTO must trust that DNS-resolved address.
        properties.setTrustContainerGateway(Mode.AUTO);
        LocalhostOnlyFilter gatewayFilter =
                new LocalhostOnlyFilter(properties, new FakeGatewayDetector(true, "172.17.0.1", GATEWAY));
        MockHttpServletRequest request = bootUiRequest("/bootui/api/overview", GATEWAY);
        request.addHeader("Host", "localhost:8080");
        MockHttpServletResponse response = new MockHttpServletResponse();

        gatewayFilter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void alsoTrustsRouteTableGatewayAlongsideDockerDesktopGateway() throws Exception {
        // Both detected gateways are trusted: the bridge gateway (Linux Docker Engine SNAT source)
        // and the Docker Desktop gateway. A request from the route-table gateway is still accepted.
        properties.setTrustContainerGateway(Mode.AUTO);
        LocalhostOnlyFilter gatewayFilter =
                new LocalhostOnlyFilter(properties, new FakeGatewayDetector(true, "172.17.0.1", GATEWAY));
        MockHttpServletRequest request = bootUiRequest("/bootui/api/overview", "172.17.0.1");
        request.addHeader("Host", "localhost:8080");
        MockHttpServletResponse response = new MockHttpServletResponse();

        gatewayFilter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    /** Fake detector that returns a fixed container status and gateways without touching the filesystem or DNS. */
    private static final class FakeGatewayDetector extends ContainerGatewayDetector {

        private final boolean inContainer;
        private final InetAddress routeGateway;
        private final Set<InetAddress> dockerDesktopGateways;

        private FakeGatewayDetector(boolean inContainer, String gateway) {
            this(inContainer, gateway, (String) null);
        }

        private FakeGatewayDetector(boolean inContainer, String routeGateway, String dockerDesktopGateway) {
            try {
                this.inContainer = inContainer;
                this.routeGateway = routeGateway == null ? null : InetAddress.getByName(routeGateway);
                this.dockerDesktopGateways =
                        dockerDesktopGateway == null ? Set.of() : Set.of(InetAddress.getByName(dockerDesktopGateway));
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
            return dockerDesktopGateways;
        }
    }
}
