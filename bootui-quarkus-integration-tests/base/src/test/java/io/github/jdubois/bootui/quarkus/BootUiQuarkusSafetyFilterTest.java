package io.github.jdubois.bootui.quarkus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.engine.safety.ContainerGatewayDetector;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.net.HostAndPort;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.RoutingContext;
import java.net.InetAddress;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * White-box binding tests for {@link BootUiQuarkusSafetyFilter} (hence the same package as the runtime
 * class, even though it lives in the integration-tests module). These exercise the parts of the Vert.x
 * binding that the shared, loopback-only {@code AbstractBootUiApiConformanceTest} cannot reach: source
 * trust on a <em>non-loopback</em> peer (impossible to produce over real loopback HTTP), Host-header
 * rebinding (the JDK HttpClient forbids setting a {@code Host} header), the spoof guarantee that the
 * source is the raw TCP peer and never a forwarded header, and the {@code :authority} (HTTP/2) host
 * fallback. The access policy itself is pinned by the engine's {@code LocalhostGuardTests}; these tests
 * only assert the binding feeds the guard correctly and renders the decision as the canonical JSON 403.
 */
class BootUiQuarkusSafetyFilterTest {

    private static final String CROSS_SITE_MESSAGE =
            "BootUI rejected a cross-site request to a state-changing endpoint.";
    private static final String DISALLOWED_HOST_MESSAGE =
            "BootUI rejected an unrecognized Host header. Add it to bootui.allowed-hosts to allow this hostname.";
    private static final String NON_LOOPBACK_MESSAGE_PREFIX = "BootUI is restricted to loopback requests.";

    // --- scope -------------------------------------------------------------------------------------

    @Test
    void scopeCoversTheWholeBootuiSurfaceButNotLookalikePaths() {
        assertThat(BootUiQuarkusSafetyFilter.isBootUiRequest("/bootui")).isTrue();
        assertThat(BootUiQuarkusSafetyFilter.isBootUiRequest("/bootui/")).isTrue();
        assertThat(BootUiQuarkusSafetyFilter.isBootUiRequest("/bootui/index.html"))
                .isTrue();
        assertThat(BootUiQuarkusSafetyFilter.isBootUiRequest("/bootui/api")).isTrue();
        assertThat(BootUiQuarkusSafetyFilter.isBootUiRequest("/bootui/api/threads"))
                .isTrue();
        assertThat(BootUiQuarkusSafetyFilter.isBootUiRequest("/bootui-other")).isFalse();
        assertThat(BootUiQuarkusSafetyFilter.isBootUiRequest("/bootuixyz")).isFalse();
        assertThat(BootUiQuarkusSafetyFilter.isBootUiRequest("/other")).isFalse();
        assertThat(BootUiQuarkusSafetyFilter.isBootUiRequest("/")).isFalse();
        assertThat(BootUiQuarkusSafetyFilter.isBootUiRequest(null)).isFalse();
    }

    // --- host sourcing -----------------------------------------------------------------------------

    @Test
    void hostAuthorityPrefersTheRawHostHeader() {
        assertThat(BootUiQuarkusSafetyFilter.hostAuthority("localhost:8080", HostAndPort.create("ignored", 1)))
                .isEqualTo("localhost:8080");
    }

    @Test
    void hostAuthorityFallsBackToTheVertxAuthorityWhenNoHostHeader() {
        // HTTP/2 carries the authority in the :authority pseudo-header, exposed via authority().
        assertThat(BootUiQuarkusSafetyFilter.hostAuthority(null, HostAndPort.create("evil.example.com", 8080)))
                .isEqualTo("evil.example.com:8080");
        assertThat(BootUiQuarkusSafetyFilter.hostAuthority("  ", HostAndPort.create("evil.example.com", -1)))
                .isEqualTo("evil.example.com");
    }

    @Test
    void hostAuthorityBracketsIpv6AuthorityFallback() {
        assertThat(BootUiQuarkusSafetyFilter.hostAuthority(null, HostAndPort.create("::1", 8080)))
                .isEqualTo("[::1]:8080");
    }

    @Test
    void hostAuthorityIsNullWhenNeitherIsPresent() {
        assertThat(BootUiQuarkusSafetyFilter.hostAuthority(null, null)).isNull();
        assertThat(BootUiQuarkusSafetyFilter.hostAuthority("", null)).isEmpty();
    }

    // --- remote sourcing ---------------------------------------------------------------------------

    @Test
    void remoteAddrReadsTheRawSocketPeer() {
        assertThat(BootUiQuarkusSafetyFilter.remoteAddr(SocketAddress.inetSocketAddress(12345, "203.0.113.5")))
                .isEqualTo("203.0.113.5");
        assertThat(BootUiQuarkusSafetyFilter.remoteAddr(null)).isNull();
    }

    // --- end-to-end binding (mocked Vert.x) --------------------------------------------------------

    @Test
    void rejectsNonLoopbackPeerAndIgnoresForwardedHeaders() {
        // A non-loopback peer must be rejected even when a forwarded header claims loopback: the binding
        // sources the raw TCP peer, never X-Forwarded-For/Forwarded. This is the spoof guarantee.
        RoutingContext rc = mockRequest("GET", "/bootui/api/threads", "203.0.113.5", "localhost:8080", null, null);
        HttpServerResponse resp = rc.response();
        BootUiQuarkusSafetyFilter filter = newFilter(Map.of());

        filter.handle(rc);

        assertRejected(resp, NON_LOOPBACK_MESSAGE_PREFIX);
        verify(rc, never()).next();
        verify(rc.request(), never()).getHeader("X-Forwarded-For");
        verify(rc.request(), never()).getHeader("Forwarded");
    }

    @Test
    void rejectsRebindingHostHeaderFromLoopback() {
        // DNS-rebinding defense: a loopback request with a foreign Host header is rejected. Not testable
        // over real loopback HTTP because the JDK HttpClient forbids setting the Host header.
        RoutingContext rc = mockRequest("GET", "/bootui/api/threads", "127.0.0.1", "evil.example.com", null, null);
        HttpServerResponse resp = rc.response();
        BootUiQuarkusSafetyFilter filter = newFilter(Map.of());

        filter.handle(rc);

        assertRejected(resp, DISALLOWED_HOST_MESSAGE);
        verify(rc, never()).next();
    }

    @Test
    void appliesHostAllowListToTheHttp2AuthorityWhenNoHostHeader() {
        // No Host header (HTTP/2): the allow-list must still apply, using the :authority host.
        RoutingContext rc = mockRequest("GET", "/bootui/api/threads", "127.0.0.1", null, null, null);
        when(rc.request().authority()).thenReturn(HostAndPort.create("evil.example.com", 8080));
        HttpServerResponse resp = rc.response();
        BootUiQuarkusSafetyFilter filter = newFilter(Map.of());

        filter.handle(rc);

        assertRejected(resp, DISALLOWED_HOST_MESSAGE);
        verify(rc, never()).next();
    }

    @Test
    void rejectsCrossSiteWriteFromLoopback() {
        RoutingContext rc = mockRequest(
                "POST", "/bootui/api/loggers", "127.0.0.1", "localhost:8080", "http://evil.example.com", null);
        HttpServerResponse resp = rc.response();
        BootUiQuarkusSafetyFilter filter = newFilter(Map.of());

        filter.handle(rc);

        assertRejected(resp, CROSS_SITE_MESSAGE);
        verify(rc, never()).next();
    }

    @Test
    void allowsSameOriginWriteFromLoopback() {
        RoutingContext rc = mockRequest(
                "POST", "/bootui/api/loggers", "127.0.0.1", "localhost:8080", "http://localhost:8080", null);
        BootUiQuarkusSafetyFilter filter = newFilter(Map.of());

        filter.handle(rc);

        verify(rc).next();
        verify(rc.response(), never()).setStatusCode(anyInt());
    }

    @Test
    void allowsCrossPortViteProxyWriteFromLoopback() {
        // The supported Vite dev-server proxy: browser Origin :5173 proxied to a Host of :8080. Host-only
        // compare must let the state-changing request through.
        RoutingContext rc = mockRequest(
                "POST", "/bootui/api/loggers", "127.0.0.1", "localhost:8080", "http://localhost:5173", null);
        BootUiQuarkusSafetyFilter filter = newFilter(Map.of());

        filter.handle(rc);

        verify(rc).next();
        verify(rc.response(), never()).setStatusCode(anyInt());
    }

    @Test
    void ignoresRequestsOutsideTheBootuiSurface() {
        RoutingContext rc = mockRequest(
                "POST", "/other", "203.0.113.5", "evil.example.com", "http://evil.example.com", "cross-site");
        BootUiQuarkusSafetyFilter filter = newFilter(Map.of());

        filter.handle(rc);

        verify(rc).next();
        verify(rc.response(), never()).setStatusCode(anyInt());
    }

    @Test
    void allowNonLocalhostBypassesEveryCheck() {
        RoutingContext rc = mockRequest(
                "POST",
                "/bootui/api/loggers",
                "203.0.113.5",
                "evil.example.com",
                "http://evil.example.com",
                "cross-site");
        BootUiQuarkusSafetyFilter filter = newFilter(Map.of("bootui.allow-non-localhost", "true"));

        filter.handle(rc);

        verify(rc).next();
        verify(rc.response(), never()).setStatusCode(anyInt());
    }

    @Test
    void honoursConfiguredAllowedHost() {
        RoutingContext rc = mockRequest("GET", "/bootui/api/threads", "127.0.0.1", "devbox.internal", null, null);
        BootUiQuarkusSafetyFilter filter = newFilter(Map.of("bootui.allowed-hosts", "devbox.internal"));

        filter.handle(rc);

        verify(rc).next();
        verify(rc.response(), never()).setStatusCode(anyInt());
    }

    // --- root-path handling ------------------------------------------------------------------------

    @Test
    void guardsTheBootuiSurfaceUnderANonDefaultRootPath() {
        // Under quarkus.http.root-path=/app the console is served at /app/bootui/**; the guard must strip
        // the prefix and still apply (this is the fail-open regression): a DNS-rebinding Host is rejected.
        RoutingContext rc = mockRequest("GET", "/app/bootui/api/threads", "127.0.0.1", "evil.example.com", null, null);
        HttpServerResponse resp = rc.response();
        BootUiQuarkusSafetyFilter filter = newFilter(Map.of("quarkus.http.root-path", "/app"));

        filter.handle(rc);

        assertRejected(resp, DISALLOWED_HOST_MESSAGE);
        verify(rc, never()).next();
    }

    @Test
    void rejectsCrossSiteWriteUnderANonDefaultRootPath() {
        RoutingContext rc = mockRequest(
                "POST", "/app/bootui/api/loggers", "127.0.0.1", "localhost:8080", "http://evil.example.com", null);
        HttpServerResponse resp = rc.response();
        BootUiQuarkusSafetyFilter filter = newFilter(Map.of("quarkus.http.root-path", "/app"));

        filter.handle(rc);

        assertRejected(resp, CROSS_SITE_MESSAGE);
        verify(rc, never()).next();
    }

    @Test
    void ignoresNonBootuiPathsUnderANonDefaultRootPath() {
        // Stripping the prefix must not over-match: /app/other is not the console and is left alone.
        RoutingContext rc = mockRequest(
                "POST", "/app/other", "203.0.113.5", "evil.example.com", "http://evil.example.com", "cross-site");
        BootUiQuarkusSafetyFilter filter = newFilter(Map.of("quarkus.http.root-path", "/app"));

        filter.handle(rc);

        verify(rc).next();
        verify(rc.response(), never()).setStatusCode(anyInt());
    }

    @Test
    void normalizesRootPathValuesToAStripPrefix() {
        assertThat(BootUiQuarkusSafetyFilter.normalizeRootPath("/")).isEmpty();
        assertThat(BootUiQuarkusSafetyFilter.normalizeRootPath("")).isEmpty();
        assertThat(BootUiQuarkusSafetyFilter.normalizeRootPath(null)).isEmpty();
        assertThat(BootUiQuarkusSafetyFilter.normalizeRootPath("/app")).isEqualTo("/app");
        assertThat(BootUiQuarkusSafetyFilter.normalizeRootPath("/app/")).isEqualTo("/app");
        assertThat(BootUiQuarkusSafetyFilter.normalizeRootPath("app")).isEqualTo("/app");
        assertThat(BootUiQuarkusSafetyFilter.normalizeRootPath("/api/v1/")).isEqualTo("/api/v1");
    }

    // --- gateway snapshot resolution ---------------------------------------------------------------

    @Test
    void gatewaySnapshotIsNotResolvedWhenTrustIsOff() {
        RecordingGatewayDetector detector = new RecordingGatewayDetector();
        BootUiQuarkusSafetyFilter filter = new BootUiQuarkusSafetyFilter(configOf(Map.of()), detector);

        filter.resolveGatewaySnapshot();

        assertThat(detector.touched)
                .as("OFF (default) must never touch /proc or DNS")
                .isFalse();
    }

    @Test
    void gatewaySnapshotIsResolvedEagerlyWhenTrustIsEnabled() {
        RecordingGatewayDetector detector = new RecordingGatewayDetector();
        BootUiQuarkusSafetyFilter filter =
                new BootUiQuarkusSafetyFilter(configOf(Map.of("bootui.trust-container-gateway", "AUTO")), detector);

        filter.resolveGatewaySnapshot();

        assertThat(detector.touched)
                .as("a non-OFF mode must resolve the gateway snapshot eagerly at startup")
                .isTrue();
    }

    // --- helpers -----------------------------------------------------------------------------------

    private static BootUiQuarkusSafetyFilter newFilter(Map<String, String> properties) {
        // Default to a detector that never runs (gateway trust is OFF unless a test sets it).
        return new BootUiQuarkusSafetyFilter(configOf(properties), new RecordingGatewayDetector());
    }

    private static Config configOf(Map<String, String> properties) {
        return new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(properties, "test", 100))
                .build();
    }

    private static RoutingContext mockRequest(
            String method, String path, String peer, String hostHeader, String origin, String secFetchSite) {
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.method()).thenReturn(HttpMethod.valueOf(method));
        when(request.remoteAddress()).thenReturn(SocketAddress.inetSocketAddress(54321, peer));
        when(request.getHeader("Host")).thenReturn(hostHeader);
        when(request.getHeader("Origin")).thenReturn(origin);
        when(request.getHeader("Sec-Fetch-Site")).thenReturn(secFetchSite);

        HttpServerResponse response = mock(HttpServerResponse.class, RETURNS_SELF);

        RoutingContext rc = mock(RoutingContext.class);
        when(rc.normalizedPath()).thenReturn(path);
        when(rc.request()).thenReturn(request);
        when(rc.response()).thenReturn(response);
        return rc;
    }

    private static void assertRejected(HttpServerResponse response, String expectedMessageFragment) {
        verify(response).setStatusCode(403);
        verify(response).putHeader("Content-Type", "application/json");
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(response).end(body.capture());
        assertThat(body.getValue()).startsWith("{\"error\":\"").endsWith("\"}").contains(expectedMessageFragment);
    }

    /** A detector that records whether any probe method was invoked, returning a benign empty snapshot. */
    private static final class RecordingGatewayDetector extends ContainerGatewayDetector {
        private boolean touched;

        @Override
        public boolean isInContainer() {
            touched = true;
            return false;
        }

        @Override
        public Optional<InetAddress> defaultGateway() {
            touched = true;
            return Optional.empty();
        }

        @Override
        public Set<InetAddress> dockerDesktopGateways() {
            touched = true;
            return Set.of();
        }
    }
}
