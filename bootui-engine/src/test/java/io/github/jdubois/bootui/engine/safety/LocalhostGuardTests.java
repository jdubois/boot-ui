package io.github.jdubois.bootui.engine.safety;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.safety.LocalhostGuardDecision.Allow;
import io.github.jdubois.bootui.engine.safety.LocalhostGuardDecision.Reason;
import io.github.jdubois.bootui.engine.safety.LocalhostGuardDecision.Reject;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pure-function contract tests for {@link LocalhostGuard}. These pin BootUI's local-only access
 * policy at the engine level so that <em>both</em> the Spring and Quarkus bindings share one source
 * of truth and cannot diverge. Every scenario covered by the Spring {@code LocalhostOnlyFilterTests}
 * is mirrored here as a direct {@code decide(...)} assertion (the filter's filesystem/DNS gateway
 * detection and request scoping stay binding concerns and are tested there), plus three pins for
 * nuances a clean rewrite could silently flip:
 *
 * <ul>
 *   <li>host-only (port-ignoring) cross-site Origin comparison — the supported Vite
 *       {@code :5173}&#8594;{@code :8080} proxy must pass a state-changing request;</li>
 *   <li>a present Origin with a missing/blank Host on a write is cross-site (rejected);</li>
 *   <li>a present but <em>malformed</em> Host is rejected rather than treated as absent, while the
 *       missing/blank-Host allowance and the shared authority parser are pinned case by case;</li>
 *   <li>the exact canonical 403 message strings.</li>
 * </ul>
 */
class LocalhostGuardTests {

    private final LocalhostGuard guard = new LocalhostGuard();

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static LocalhostGuardRequest get(String remoteAddr, String host) {
        return new LocalhostGuardRequest("GET", remoteAddr, host, null, null);
    }

    private static LocalhostGuardRequest post(String remoteAddr, String host, String origin, String secFetchSite) {
        return new LocalhostGuardRequest("POST", remoteAddr, host, origin, secFetchSite);
    }

    private static LocalhostGuardConfig defaults() {
        return new LocalhostGuardConfig(false, List.of(), List.of(), GatewayTrust.OFF, false, Set.of());
    }

    private static LocalhostGuardConfig allowNonLocalhost() {
        return new LocalhostGuardConfig(true, List.of(), List.of(), GatewayTrust.OFF, false, Set.of());
    }

    private static LocalhostGuardConfig withAllowedHosts(String... hosts) {
        return new LocalhostGuardConfig(false, List.of(hosts), List.of(), GatewayTrust.OFF, false, Set.of());
    }

    private static LocalhostGuardConfig withTrustedRange(String cidr) {
        return new LocalhostGuardConfig(
                false, List.of(), List.of(CidrRange.parse(cidr)), GatewayTrust.OFF, false, Set.of());
    }

    private static LocalhostGuardConfig withGateways(GatewayTrust mode, boolean inContainer, String... gateways)
            throws UnknownHostException {
        Set<InetAddress> detected = new LinkedHashSet<>();
        for (String gateway : gateways) {
            detected.add(InetAddress.getByName(gateway));
        }
        return new LocalhostGuardConfig(false, List.of(), List.of(), mode, inContainer, detected);
    }

    private void assertAllowed(LocalhostGuardDecision decision) {
        assertThat(decision).isInstanceOf(Allow.class);
    }

    private void assertRejected(LocalhostGuardDecision decision, Reason reason) {
        assertThat(decision)
                .isInstanceOfSatisfying(
                        Reject.class, reject -> assertThat(reject.reason()).isEqualTo(reason));
    }

    // -------------------------------------------------------------------------
    // Trusted source
    // -------------------------------------------------------------------------

    @Test
    void allowsLoopbackIpv4() {
        assertAllowed(guard.decide(get("127.0.0.1", null), defaults()));
    }

    @Test
    void allowsLoopbackIpv6() {
        assertAllowed(guard.decide(get("::1", null), defaults()));
    }

    @Test
    void rejectsNonLoopbackSource() {
        assertRejected(guard.decide(get("192.168.65.1", "localhost:8080"), defaults()), Reason.NON_LOOPBACK_SOURCE);
    }

    @Test
    void allowNonLocalhostBypassesOnlySourceCheck() {
        assertAllowed(guard.decide(get("203.0.113.5", "localhost:8080"), allowNonLocalhost()));
        assertRejected(
                guard.decide(get("203.0.113.5", "attacker.example.com"), allowNonLocalhost()), Reason.DISALLOWED_HOST);
        assertRejected(
                guard.decide(
                        post("203.0.113.5", "localhost:8080", "https://attacker.example.com", "cross-site"),
                        allowNonLocalhost()),
                Reason.CROSS_SITE_WRITE);
    }

    @Test
    void rejectsUnknownRemoteAddress() {
        assertRejected(guard.decide(get("not-an-address", null), defaults()), Reason.NON_LOOPBACK_SOURCE);
    }

    @Test
    void rejectsBlankRemoteAddress() {
        assertRejected(guard.decide(get("", null), defaults()), Reason.NON_LOOPBACK_SOURCE);
    }

    @Test
    void rejectsNullRemoteAddress() {
        assertRejected(guard.decide(get(null, null), defaults()), Reason.NON_LOOPBACK_SOURCE);
    }

    @Test
    void rejectsWildcardIpv4Source() {
        assertRejected(guard.decide(get("0.0.0.0", null), defaults()), Reason.NON_LOOPBACK_SOURCE);
    }

    @Test
    void rejectsWildcardIpv6Source() {
        assertRejected(guard.decide(get("::", null), defaults()), Reason.NON_LOOPBACK_SOURCE);
    }

    // -------------------------------------------------------------------------
    // Host allow-list (DNS-rebinding defense)
    // -------------------------------------------------------------------------

    @Test
    void rejectsRebindingHostHeader() {
        assertRejected(guard.decide(get("127.0.0.1", "attacker.example.com"), defaults()), Reason.DISALLOWED_HOST);
    }

    @Test
    void allowsLoopbackHostHeaderWithPort() {
        assertAllowed(guard.decide(get("127.0.0.1", "localhost:8080"), defaults()));
    }

    @Test
    void allowsBracketedIpv6HostHeader() {
        assertAllowed(guard.decide(get("::1", "[::1]:8080"), defaults()));
    }

    @Test
    void allowsConfiguredAllowedHost() {
        assertAllowed(guard.decide(get("127.0.0.1", "app.local:8080"), withAllowedHosts("app.local")));
    }

    @Test
    void allowsBlankHostHeader() {
        assertAllowed(guard.decide(get("127.0.0.1", "   "), defaults()));
    }

    @Test
    void allowsMissingHostHeader() {
        assertAllowed(guard.decide(get("127.0.0.1", null), defaults()));
    }

    @Test
    void allowsHostHeaderCaseInsensitively() {
        assertAllowed(guard.decide(get("127.0.0.1", "LocalHost:8080"), defaults()));
    }

    @Test
    void allowsLoopbackIpv4HostHeaderWithoutPort() {
        assertAllowed(guard.decide(get("127.0.0.1", "127.0.0.1"), defaults()));
    }

    @Test
    void allowsExpandedIpv6LoopbackHostHeader() {
        assertAllowed(guard.decide(get("::1", "[0:0:0:0:0:0:0:1]:8080"), defaults()));
    }

    @Test
    void allowsAllowedHostWithEmptyPort() {
        assertAllowed(guard.decide(get("127.0.0.1", "localhost:"), defaults()));
    }

    @Test
    void allowsConfiguredIpv6ZoneHostHeader() {
        assertAllowed(guard.decide(get("127.0.0.1", "[fe80::1%25eth0]:8080"), withAllowedHosts("fe80::1%25eth0")));
    }

    // -------------------------------------------------------------------------
    // Malformed Host headers (present but unparsable -> rejected, not treated as absent)
    // -------------------------------------------------------------------------

    @Test
    void rejectsHostHeaderThatIsOnlyAColon() {
        assertRejected(guard.decide(get("127.0.0.1", ":"), defaults()), Reason.DISALLOWED_HOST);
    }

    @Test
    void rejectsHostHeaderWithTrailingJunkAfterIpv6Brackets() {
        assertRejected(guard.decide(get("127.0.0.1", "[::1]junk"), defaults()), Reason.DISALLOWED_HOST);
    }

    @Test
    void rejectsHostHeaderWithUnclosedIpv6Bracket() {
        assertRejected(guard.decide(get("127.0.0.1", "[::1"), defaults()), Reason.DISALLOWED_HOST);
    }

    @Test
    void rejectsHostHeaderWithEmptyIpv6Brackets() {
        assertRejected(guard.decide(get("127.0.0.1", "[]"), defaults()), Reason.DISALLOWED_HOST);
    }

    @Test
    void rejectsBracketedNonIpv6HostHeader() {
        assertRejected(guard.decide(get("127.0.0.1", "[localhost]:8080"), defaults()), Reason.DISALLOWED_HOST);
    }

    @Test
    void rejectsUnbracketedIpv6HostHeader() {
        // "::1" is only a legal authority in bracketed form; unbracketed it parses as an empty host.
        assertRejected(guard.decide(get("::1", "::1"), defaults()), Reason.DISALLOWED_HOST);
    }

    @Test
    void rejectsHostHeaderWithNonNumericPort() {
        assertRejected(guard.decide(get("127.0.0.1", "localhost:80x"), defaults()), Reason.DISALLOWED_HOST);
    }

    @Test
    void rejectsHostHeaderWithEmbeddedWhitespace() {
        assertRejected(guard.decide(get("127.0.0.1", "local host"), defaults()), Reason.DISALLOWED_HOST);
    }

    @Test
    void rejectsHostHeaderWithUserInfo() {
        assertRejected(
                guard.decide(get("127.0.0.1", "localhost@evil.example.com"), defaults()), Reason.DISALLOWED_HOST);
    }

    @Test
    void rejectsHostHeaderCarryingAScheme() {
        assertRejected(guard.decide(get("127.0.0.1", "http://localhost:8080"), defaults()), Reason.DISALLOWED_HOST);
    }

    @Test
    void rejectsHostHeaderCarryingAPath() {
        assertRejected(
                guard.decide(get("127.0.0.1", "localhost:8080/@evil.example.com"), defaults()), Reason.DISALLOWED_HOST);
    }

    @Test
    void rejectsMalformedHostHeaderEvenWhenAllowListIsConfigured() {
        assertRejected(
                guard.decide(get("127.0.0.1", ":"), withAllowedHosts("localhost", "app.local")),
                Reason.DISALLOWED_HOST);
    }

    @Test
    void rejectsMalformedHostHeaderWithTheCanonicalHostMessage() {
        assertThat(((Reject) guard.decide(get("127.0.0.1", "[::1]junk"), defaults())).message())
                .isEqualTo(LocalhostGuard.MESSAGE_DISALLOWED_HOST);
    }

    @Test
    void rejectsMalformedHostHeaderBeforeTheCrossSiteWriteCheck() {
        assertRejected(
                guard.decide(post("127.0.0.1", ":", "http://evil.example.com", "cross-site"), defaults()),
                Reason.DISALLOWED_HOST);
    }

    @Test
    void stillReportsTheSourceRejectionWhenTheHostIsAlsoMalformed() {
        // Defense order is behavior: an untrusted peer is reported as such even with a malformed Host.
        assertRejected(guard.decide(get("192.168.65.1", ":"), defaults()), Reason.NON_LOOPBACK_SOURCE);
    }

    @Test
    void rejectsMalformedHostHeaderUnderAllowNonLocalhost() {
        assertRejected(guard.decide(get("203.0.113.5", "[::1]junk"), allowNonLocalhost()), Reason.DISALLOWED_HOST);
    }

    @Test
    void rejectsMalformedOriginOnStateChangingRequest() {
        // An unparsable Origin yields no comparable host: fail closed rather than treating it as same-site.
        assertRejected(
                guard.decide(post("127.0.0.1", "localhost:8080", "http://local host:8080", null), defaults()),
                Reason.CROSS_SITE_WRITE);
    }

    // -------------------------------------------------------------------------
    // Authority parsing (shared by the Host allow-list and the Origin comparison)
    // -------------------------------------------------------------------------

    @Test
    void parsesWellFormedHostHeaders() {
        assertThat(LocalhostGuard.extractRequestHost("localhost")).isEqualTo("localhost");
        assertThat(LocalhostGuard.extractRequestHost("localhost:8080")).isEqualTo("localhost");
        assertThat(LocalhostGuard.extractRequestHost("LOCALHOST:8080")).isEqualTo("localhost");
        assertThat(LocalhostGuard.extractRequestHost("  localhost:8080  ")).isEqualTo("localhost");
        assertThat(LocalhostGuard.extractRequestHost("127.0.0.1:8080")).isEqualTo("127.0.0.1");
        assertThat(LocalhostGuard.extractRequestHost("[::1]")).isEqualTo("::1");
        assertThat(LocalhostGuard.extractRequestHost("[::1]:8080")).isEqualTo("::1");
        assertThat(LocalhostGuard.extractRequestHost("[0:0:0:0:0:0:0:1]:8080")).isEqualTo("0:0:0:0:0:0:0:1");
        assertThat(LocalhostGuard.extractRequestHost("[::ffff:127.0.0.1]:8080")).isEqualTo("::ffff:127.0.0.1");
        assertThat(LocalhostGuard.extractRequestHost("[fe80::1%25eth0]:8080")).isEqualTo("fe80::1%25eth0");
        assertThat(LocalhostGuard.extractRequestHost("[fe80::1%eth0]")).isEqualTo("fe80::1%eth0");
        assertThat(LocalhostGuard.extractRequestHost("my_app-1.local:8080")).isEqualTo("my_app-1.local");
        assertThat(LocalhostGuard.extractRequestHost("xn--mnchen-3ya.local")).isEqualTo("xn--mnchen-3ya.local");
        assertThat(LocalhostGuard.extractRequestHost("localhost:")).isEqualTo("localhost");
    }

    @Test
    void reportsMalformedHostHeadersAsUnusable() {
        assertThat(LocalhostGuard.extractRequestHost(null)).isNull();
        assertThat(LocalhostGuard.extractRequestHost("")).isNull();
        assertThat(LocalhostGuard.extractRequestHost("   ")).isNull();
        assertThat(LocalhostGuard.extractRequestHost(":")).isNull();
        assertThat(LocalhostGuard.extractRequestHost(":8080")).isNull();
        assertThat(LocalhostGuard.extractRequestHost("/path")).isNull();
        assertThat(LocalhostGuard.extractRequestHost("[::1]junk")).isNull();
        assertThat(LocalhostGuard.extractRequestHost("[::1]:8080junk")).isNull();
        assertThat(LocalhostGuard.extractRequestHost("[::1")).isNull();
        assertThat(LocalhostGuard.extractRequestHost("[]")).isNull();
        assertThat(LocalhostGuard.extractRequestHost("[]:8080")).isNull();
        assertThat(LocalhostGuard.extractRequestHost("[localhost]")).isNull();
        assertThat(LocalhostGuard.extractRequestHost("[::1%]")).isNull();
        assertThat(LocalhostGuard.extractRequestHost("::1")).isNull();
        assertThat(LocalhostGuard.extractRequestHost("localhost:80x")).isNull();
        assertThat(LocalhostGuard.extractRequestHost("localhost:8080:9090")).isNull();
        assertThat(LocalhostGuard.extractRequestHost("local host")).isNull();
        assertThat(LocalhostGuard.extractRequestHost("localhost@evil.example.com"))
                .isNull();
        assertThat(LocalhostGuard.extractRequestHost("local\u0000host")).isNull();
        // A Host header is authority-form only: a scheme or a path makes it ambiguous, so it never
        // parses back down to a plausible-looking hostname.
        assertThat(LocalhostGuard.extractRequestHost("http://localhost:8080")).isNull();
        assertThat(LocalhostGuard.extractRequestHost("localhost:8080/path")).isNull();
        assertThat(LocalhostGuard.extractRequestHost("localhost/@evil.example.com"))
                .isNull();
    }

    @Test
    void parsesOriginHeadersThroughTheSameAuthorityParser() {
        assertThat(LocalhostGuard.extractOriginHost("http://localhost:8080")).isEqualTo("localhost");
        assertThat(LocalhostGuard.extractOriginHost("https://localhost:8080/")).isEqualTo("localhost");
        assertThat(LocalhostGuard.extractOriginHost("http://[::1]:8080/api")).isEqualTo("::1");
        assertThat(LocalhostGuard.extractOriginHost("http://localhost:5173")).isEqualTo("localhost");
        assertThat(LocalhostGuard.extractOriginHost(null)).isNull();
        assertThat(LocalhostGuard.extractOriginHost("   ")).isNull();
        assertThat(LocalhostGuard.extractOriginHost("http://")).isNull();
        assertThat(LocalhostGuard.extractOriginHost("http://local host:8080")).isNull();
        assertThat(LocalhostGuard.extractOriginHost("http://[::1]junk")).isNull();
    }

    // -------------------------------------------------------------------------
    // Cross-site write protection (CSRF defense)
    // -------------------------------------------------------------------------

    @Test
    void rejectsCrossSiteOriginOnStateChangingRequest() {
        assertRejected(
                guard.decide(post("127.0.0.1", "localhost:8080", "http://evil.example.com", null), defaults()),
                Reason.CROSS_SITE_WRITE);
    }

    @Test
    void allowsSameOriginStateChangingRequest() {
        assertAllowed(guard.decide(post("127.0.0.1", "localhost:8080", "http://localhost:8080", null), defaults()));
    }

    @Test
    void allowsSameOriginStateChangingRequestWithTrailingSlashOrigin() {
        assertAllowed(guard.decide(post("127.0.0.1", "localhost:8080", "http://localhost:8080/", null), defaults()));
    }

    @Test
    void allowsStateChangingRequestWithoutOrigin() {
        assertAllowed(guard.decide(post("127.0.0.1", "localhost:8080", null, null), defaults()));
    }

    @Test
    void rejectsSecFetchSiteCrossSiteOnStateChangingRequest() {
        assertRejected(
                guard.decide(post("127.0.0.1", "localhost:8080", null, "cross-site"), defaults()),
                Reason.CROSS_SITE_WRITE);
    }

    @Test
    void allowsCrossSiteOriginOnSafeMethod() {
        LocalhostGuardRequest request =
                new LocalhostGuardRequest("GET", "127.0.0.1", "localhost:8080", "http://evil.example.com", null);
        assertAllowed(guard.decide(request, defaults()));
    }

    // -------------------------------------------------------------------------
    // Trusted source ranges (bootui.trusted-proxies)
    // -------------------------------------------------------------------------

    @Test
    void allowsTrustedSourceRangeWithAcceptableHost() {
        assertAllowed(guard.decide(get("172.17.0.1", "localhost:8080"), withTrustedRange("172.16.0.0/12")));
    }

    @Test
    void rejectsTrustedSourceRangeWithDisallowedHost() {
        assertRejected(
                guard.decide(get("172.17.0.1", "attacker.example.com"), withTrustedRange("172.16.0.0/12")),
                Reason.DISALLOWED_HOST);
    }

    @Test
    void rejectsCrossSiteWriteFromTrustedSourceRange() {
        assertRejected(
                guard.decide(
                        post("172.17.0.1", "localhost:8080", "http://evil.example.com", null),
                        withTrustedRange("172.16.0.0/12")),
                Reason.CROSS_SITE_WRITE);
    }

    @Test
    void rejectsSourceOutsideTrustedRange() {
        assertRejected(
                guard.decide(get("10.0.0.5", "localhost:8080"), withTrustedRange("172.16.0.0/12")),
                Reason.NON_LOOPBACK_SOURCE);
    }

    @Test
    void allowsTrustedIpv6SourceRange() {
        assertAllowed(guard.decide(get("fd00::1234", "localhost:8080"), withTrustedRange("fd00::/8")));
    }

    // -------------------------------------------------------------------------
    // Container gateway trust (bootui.trust-container-gateway)
    // -------------------------------------------------------------------------

    @Test
    void autoTrustsContainerGatewayWhenInContainer() throws Exception {
        LocalhostGuardDecision decision = guard.decide(
                get("192.168.65.1", "localhost:8080"), withGateways(GatewayTrust.AUTO, true, "192.168.65.1"));
        assertThat(decision).isInstanceOfSatisfying(Allow.class, allow -> {
            assertThat(allow.trustedViaGateway()).isTrue();
            assertThat(allow.trustedGateway()).isEqualTo(loopbackEquivalent("192.168.65.1"));
        });
    }

    @Test
    void autoDoesNotTrustContainerGatewayWhenNotInContainer() throws Exception {
        assertRejected(
                guard.decide(
                        get("192.168.65.1", "localhost:8080"), withGateways(GatewayTrust.AUTO, false, "192.168.65.1")),
                Reason.NON_LOOPBACK_SOURCE);
    }

    @Test
    void onTrustsDetectedGatewayEvenWhenNotInContainer() throws Exception {
        LocalhostGuardDecision decision = guard.decide(
                get("192.168.65.1", "localhost:8080"), withGateways(GatewayTrust.ON, false, "192.168.65.1"));
        assertThat(decision)
                .isInstanceOfSatisfying(
                        Allow.class,
                        allow -> assertThat(allow.trustedViaGateway()).isTrue());
    }

    @Test
    void offNeverTrustsContainerGateway() throws Exception {
        assertRejected(
                guard.decide(
                        get("192.168.65.1", "localhost:8080"), withGateways(GatewayTrust.OFF, true, "192.168.65.1")),
                Reason.NON_LOOPBACK_SOURCE);
    }

    @Test
    void doesNotTrustNonGatewaySourceWhenInContainer() throws Exception {
        assertRejected(
                guard.decide(
                        get("192.168.65.99", "localhost:8080"), withGateways(GatewayTrust.AUTO, true, "192.168.65.1")),
                Reason.NON_LOOPBACK_SOURCE);
    }

    @Test
    void stillRejectsDisallowedHostFromTrustedGateway() throws Exception {
        assertRejected(
                guard.decide(
                        get("192.168.65.1", "attacker.example.com"),
                        withGateways(GatewayTrust.AUTO, true, "192.168.65.1")),
                Reason.DISALLOWED_HOST);
    }

    @Test
    void stillRejectsCrossSiteWriteFromTrustedGateway() throws Exception {
        assertRejected(
                guard.decide(
                        post("192.168.65.1", "localhost:8080", "http://evil.example.com", null),
                        withGateways(GatewayTrust.AUTO, true, "192.168.65.1")),
                Reason.CROSS_SITE_WRITE);
    }

    @Test
    void autoTrustsDockerDesktopGatewayAlongsideRouteTableGateway() throws Exception {
        LocalhostGuardConfig config = withGateways(GatewayTrust.AUTO, true, "172.17.0.1", "192.168.65.1");
        assertAllowed(guard.decide(get("192.168.65.1", "localhost:8080"), config));
        assertAllowed(guard.decide(get("172.17.0.1", "localhost:8080"), config));
    }

    // -------------------------------------------------------------------------
    // New pins (nuances a clean rewrite could silently flip)
    // -------------------------------------------------------------------------

    @Test
    void allowsViteProxyStateChangingRequestAcrossPorts() {
        // Browser Origin localhost:5173 (Vite) proxied to a Host of localhost:8080: same host, different
        // port -> the host-only comparison must treat this as same-site and allow the write.
        assertAllowed(guard.decide(post("127.0.0.1", "localhost:8080", "http://localhost:5173", null), defaults()));
    }

    @Test
    void rejectsOriginWriteWhenHostHeaderIsBlank() {
        assertRejected(
                guard.decide(post("127.0.0.1", "   ", "http://localhost:8080", null), defaults()),
                Reason.CROSS_SITE_WRITE);
    }

    @Test
    void rejectsOriginWriteWhenHostHeaderIsMissing() {
        assertRejected(
                guard.decide(post("127.0.0.1", null, "http://localhost:8080", null), defaults()),
                Reason.CROSS_SITE_WRITE);
    }

    @Test
    void exposesExactCanonicalRejectionMessages() {
        assertThat(((Reject) guard.decide(get("192.168.65.1", "localhost:8080"), defaults())).message())
                .isEqualTo(LocalhostGuard.MESSAGE_NON_LOOPBACK_SOURCE)
                .isEqualTo(
                        "BootUI is restricted to loopback requests. Set bootui.allow-non-localhost=true to override, "
                                + "add a trusted source range to bootui.trusted-proxies, or (when running in a container) "
                                + "set bootui.trust-container-gateway=AUTO to trust the auto-detected container gateway.");
        assertThat(((Reject) guard.decide(get("127.0.0.1", "attacker.example.com"), defaults())).message())
                .isEqualTo(LocalhostGuard.MESSAGE_DISALLOWED_HOST)
                .isEqualTo("BootUI rejected an unrecognized Host header. "
                        + "Add it to bootui.allowed-hosts to allow this hostname.");
        assertThat(((Reject) guard.decide(
                                post("127.0.0.1", "localhost:8080", "http://evil.example.com", null), defaults()))
                        .message())
                .isEqualTo(LocalhostGuard.MESSAGE_CROSS_SITE_WRITE)
                .isEqualTo("BootUI rejected a cross-site request to a state-changing endpoint.");
    }

    @Test
    void directTrustReportsNoGatewayTrust() {
        assertThat(guard.decide(get("127.0.0.1", "localhost:8080"), defaults()))
                .isInstanceOfSatisfying(Allow.class, allow -> {
                    assertThat(allow.trustedViaGateway()).isFalse();
                    assertThat(allow.trustedGateway()).isNull();
                });
    }

    private static InetAddress loopbackEquivalent(String value) {
        try {
            return InetAddress.getByName(value);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }
}
