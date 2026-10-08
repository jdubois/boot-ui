package io.github.jdubois.bootui.engine.quarkussecurity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.core.dto.SecurityReport;
import io.github.jdubois.bootui.core.dto.SecurityRuleResultDto;
import io.github.jdubois.bootui.engine.advisor.AdvisorRuleRefusals;
import io.github.jdubois.bootui.spi.QuarkusSecurityEndpoint;
import io.github.jdubois.bootui.spi.QuarkusSecurityEvidence;
import io.github.jdubois.bootui.spi.QuarkusSecurityPermission;
import io.github.jdubois.bootui.spi.QuarkusSecuritySnapshot;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class QuarkusSecurityScannerTest {

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 4, 10000})
    void baseIntegrationPostureExcludesUnknownDocumentChecksBeforeCountingAndRetaining(int retentionLimit) {
        Snap snap = new Snap();
        snap.basic = false;
        snap.authenticated = 0;
        snap.denyUnannotated = false;
        snap.endpoints = 2;
        snap.secured = 0;
        snap.insecure = "enabled";
        snap.ssl = false;
        snap.hsts = false;
        snap.csp = false;
        snap.xFrame = false;
        snap.xContentType = false;
        snap.evidence = new QuarkusSecurityEvidence(
                Set.of("QS-HDR-003", "QS-HDR-004", "QS-HDR-005"),
                List.of("document applicability is not established"),
                List.of(),
                List.of(
                        new QuarkusSecurityEndpoint(
                                "/api/items", "GET", QuarkusSecurityEndpoint.Access.UNANNOTATED, false),
                        new QuarkusSecurityEndpoint(
                                "/api/status", "GET", QuarkusSecurityEndpoint.Access.UNANNOTATED, false)),
                true);
        QuarkusSecurityScanner scanner = QuarkusSecurityScanner.usingSnapshot(snap::build, CLOCK);
        scanner.setViolationRetentionLimit(() -> retentionLimit);
        SecurityReport report = scanner.scan();
        assertThat(report.results())
                .extracting(SecurityRuleResultDto::id)
                .containsExactlyInAnyOrder("QS-AUTH-001", "QS-TLS-001", "QS-TLS-002", "QS-HDR-006");
        assertCountedMetadata(report);
        assertThat(report.violationDetails().total()).isEqualTo(4);
        assertThat(report.violationDetails().retained()).isEqualTo(Math.min(4, retentionLimit));
        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        String scanId = report.violationDetails().scanId();
        AdvisorRuleRefusals.assertKnownAndUnknownRulesAreToldApart(
                QuarkusSecurityChecks.ruleIds(),
                report.results().stream().map(evaluated -> evaluated.id()).toList(),
                report.violationDetails().scanId(),
                (asked, scan) -> scanner.ruleViolations(asked, scan, 0, 1));
        for (String unknown : snap.evidence.unknownRules()) {
            assertThatThrownBy(() -> scanner.ruleViolations(unknown, scanId, 0, null))
                    .isInstanceOfSatisfying(
                            io.github.jdubois.bootui.engine.advisor.AdvisorViolationException.class,
                            failure -> assertThat(failure.status()).isEqualTo(404));
        }
        var headers = scanner.ruleViolations("QS-HDR-006", scanId, 0, null);
        assertThat(headers.violationCount()).isOne();
        if (retentionLimit >= 4) {
            assertThat(headers.violations())
                    .containsExactly("quarkus.http.header.\"X-Content-Type-Options\".value absent");
            assertThat(headers.truncated()).isFalse();
        }
    }

    @Test
    void retainsAllCountedSecurityDetailsBeforeTwentySampleLimitWithoutCollectingAgain() {
        Snap snap = new Snap();
        snap.secrets = java.util.stream.IntStream.range(0, 35)
                .mapToObj(index -> "application.credential-" + index)
                .toList();
        java.util.concurrent.atomic.AtomicInteger collections = new java.util.concurrent.atomic.AtomicInteger();
        QuarkusSecurityScanner scanner = QuarkusSecurityScanner.usingSnapshot(
                () -> {
                    collections.incrementAndGet();
                    return snap.build();
                },
                CLOCK);
        SecurityReport report = scanner.scan();
        String scanId = report.violationDetails().scanId();
        String id = "QS-CFG-001";
        assertThat(report.results())
                .filteredOn(result -> result.id().equals(id))
                .singleElement()
                .satisfies(result -> {
                    assertThat(result.violationCount()).isEqualTo(35);
                    assertThat(result.sampleViolations()).containsExactlyElementsOf(snap.secrets.subList(0, 20));
                });
        var first = scanner.ruleViolations(id, scanId, 0, 21);
        assertThat(first.violations()).containsExactlyElementsOf(snap.secrets.subList(0, 21));
        assertThat(first.page().hasMore()).isTrue();
        var last = scanner.ruleViolations(id, scanId, 21, 21);
        assertThat(last.violations()).containsExactlyElementsOf(snap.secrets.subList(21, 35));
        assertThat(last.page().hasMore()).isFalse();
        assertThat(last.truncated()).isFalse();
        assertThat(scanner.applyDismissals(report, Set.of(id)).violationDetails())
                .isEqualTo(report.violationDetails());
        assertThat(scanner.lastReport()).isSameAs(report);
        assertThat(collections).hasValue(1);

        scanner.setViolationRetentionLimit(() -> 7);
        SecurityReport bounded = scanner.scan();
        assertThat(bounded.results()).isEqualTo(report.results());
        assertThat(bounded.evidence()).isEqualTo(report.evidence());
        var truncated = scanner.ruleViolations(id, bounded.violationDetails().scanId(), 0, null);
        assertThat(truncated.truncated()).isTrue();
        assertThat(truncated.violationCount()).isEqualTo(35);
        assertThat(bounded.violationDetails().retained()).isEqualTo(7);
    }

    @Test
    void endpointCountKeepsAggregatePreviewButRetainsOnlyKnownEndpointIdentities() {
        Snap snap = new Snap();
        snap.denyUnannotated = false;
        snap.endpoints = 25;
        snap.secured = 0;
        List<QuarkusSecurityEndpoint> endpoints = java.util.stream.IntStream.range(0, 25)
                .mapToObj(index -> new QuarkusSecurityEndpoint(
                        "/resource-" + index, "GET", QuarkusSecurityEndpoint.Access.UNANNOTATED, false))
                .toList();
        snap.evidence = new QuarkusSecurityEvidence(Set.of(), List.of(), List.of(), endpoints, true);
        QuarkusSecurityScanner scanner = QuarkusSecurityScanner.usingSnapshot(snap::build, CLOCK);
        SecurityReport report = scanner.scan();
        String id = "QS-AUTHZ-004";
        assertThat(report.results())
                .filteredOn(result -> result.id().equals(id))
                .singleElement()
                .satisfies(result -> {
                    assertThat(result.violationCount()).isEqualTo(25);
                    assertThat(result.sampleViolations())
                            .containsExactly("25 declared endpoint(s) without a supported restriction");
                });
        var page = scanner.ruleViolations(id, report.violationDetails().scanId(), 0, null);
        assertThat(page.violations())
                .containsExactlyElementsOf(endpoints.stream()
                        .map(endpoint ->
                                "GET " + endpoint.path() + " — declared endpoint without a supported restriction")
                        .toList());
        assertThat(page.truncated()).isFalse();

        snap.evidence = QuarkusSecurityEvidence.LEGACY;
        SecurityReport legacy = scanner.scan();
        var unknown = scanner.ruleViolations(id, legacy.violationDetails().scanId(), 0, null);
        assertThat(unknown.violationCount()).isEqualTo(25);
        assertThat(unknown.violations()).isEmpty();
        assertThat(unknown.truncated()).isTrue();
        assertThat(unknown.page().hasMore()).isFalse();
    }

    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC);

    /**
     * Mutable builder whose defaults describe a hardened Quarkus app that fires zero rules, so each test can
     * flip exactly the fields under test. Mirrors the {@link QuarkusSecuritySnapshot} positional record.
     */
    private static final class Snap {
        // Auth mechanisms — basic auth on, over redirected HTTP, is a clean baseline.
        boolean oidc = false;
        boolean jwt = false;
        boolean basic = true;
        boolean form = false;
        boolean mtls = false;
        // Transport
        String insecure = "redirect";
        boolean ssl = true;
        boolean behindProxy = false;
        boolean tlsTrustAll = false;
        // CORS
        boolean cors = false;
        String corsOrigins = "https://app.example";
        boolean corsCreds = false;
        String corsMethods = "GET,POST";
        String corsHeaders = "Content-Type";
        // Headers
        boolean hsts = true;
        boolean csp = true;
        String hstsValue = "max-age=31536000; includeSubDomains";
        String cspValue = "default-src 'self'";
        boolean xFrame = true;
        boolean xContentType = true;
        // Dev exposure
        boolean oidcTlsNone = false;
        boolean swagger = false;
        boolean openApi = false;
        // CSRF / authz
        boolean csrf = true;
        List<QuarkusSecurityPermission> permissions = List.of();
        int rolesAllowed = 0;
        int permitAll = 0;
        int denyAll = 0;
        int authenticated = 1;
        int endpoints = 4;
        int secured = 4;
        boolean denyUnannotated = true;
        // OIDC details
        boolean jwtIssuer = true;
        boolean proactiveDisabled = false;
        boolean oidcAudience = true;
        String oidcAppType = "service";
        boolean oidcCookieSecure = true;
        // Management
        boolean mgmtEnabled = false;
        boolean mgmtNonLoopback = false;
        boolean mgmtHostUnpinnedForProd = false;
        // Config hygiene
        List<String> secrets = List.of();
        // Auth hardening
        boolean jwtAlgUnpinnedForRemoteJwks = false;
        boolean jdbcClearPasswordMapper = false;
        boolean embeddedUsers = false;
        boolean jwtAudiences = true;
        boolean jwtInlineKey = false;
        // Headers (nice-to-have)
        boolean referrerPolicy = true;
        boolean permissionsPolicy = true;
        // Quarkus-specific
        String nonAppRootPath = "q";
        boolean grpcReflectionProd = false;
        boolean graphqlPresent = false;
        boolean graphqlIntrospection = true;
        boolean graphqlUi = false;
        List<String> insecureMessagingChannels = List.of();
        // Session (form-auth cookies)
        boolean formHttpOnly = true;
        boolean formSameSiteNone = false;
        boolean formTimeoutExcessive = false;
        // OIDC PKCE / Health UI
        boolean oidcHasClientSecret = true;
        boolean oidcPkceRequired = true;
        boolean healthUiAlwaysInclude = false;
        boolean insecureIdentityProviderUrl = false;
        boolean oidcIssuerAny = false;
        boolean oidcServiceTokenConsumer = true;
        boolean embeddedUsersPlainText = false;
        List<String> tlsHostnameVerificationDisabled = List.of();
        boolean nonAppRootPathMerged = false;
        int quarkusAuthorizationAnnotations = 0;
        boolean defaultRolesAllowed = false;
        List<String> legacyTlsProtocols = List.of();
        boolean oidcTokenEncryptionDisabled = false;
        boolean forwardedHeadersTrustAnyProxy = false;
        QuarkusSecurityEvidence evidence = QuarkusSecurityEvidence.LEGACY;

        QuarkusSecuritySnapshot build() {
            return new QuarkusSecuritySnapshot(
                    oidc,
                    jwt,
                    basic,
                    form,
                    mtls,
                    insecure,
                    ssl,
                    cors,
                    corsOrigins,
                    corsCreds,
                    hsts,
                    csp,
                    oidcTlsNone,
                    swagger,
                    openApi,
                    csrf,
                    permissions,
                    rolesAllowed,
                    permitAll,
                    denyAll,
                    authenticated,
                    endpoints,
                    secured,
                    secrets,
                    behindProxy,
                    jwtIssuer,
                    proactiveDisabled,
                    oidcAudience,
                    oidcAppType,
                    oidcCookieSecure,
                    tlsTrustAll,
                    corsMethods,
                    corsHeaders,
                    hstsValue,
                    cspValue,
                    xFrame,
                    xContentType,
                    denyUnannotated,
                    mgmtEnabled,
                    mgmtNonLoopback,
                    mgmtHostUnpinnedForProd,
                    jwtAlgUnpinnedForRemoteJwks,
                    jdbcClearPasswordMapper,
                    embeddedUsers,
                    jwtAudiences,
                    jwtInlineKey,
                    referrerPolicy,
                    permissionsPolicy,
                    nonAppRootPath,
                    grpcReflectionProd,
                    graphqlPresent,
                    graphqlIntrospection,
                    graphqlUi,
                    insecureMessagingChannels,
                    formHttpOnly,
                    formSameSiteNone,
                    formTimeoutExcessive,
                    oidcHasClientSecret,
                    oidcPkceRequired,
                    healthUiAlwaysInclude,
                    insecureIdentityProviderUrl,
                    oidcIssuerAny,
                    oidcServiceTokenConsumer,
                    embeddedUsersPlainText,
                    tlsHostnameVerificationDisabled,
                    nonAppRootPathMerged,
                    quarkusAuthorizationAnnotations,
                    defaultRolesAllowed,
                    legacyTlsProtocols,
                    oidcTokenEncryptionDisabled,
                    forwardedHeadersTrustAnyProxy,
                    evidence);
        }
    }

    private static SecurityReport scan(Snap s) {
        SecurityReport report =
                QuarkusSecurityScanner.usingSnapshot(s::build, CLOCK).scan();
        assertCountedMetadata(report);
        return report;
    }

    private static void assertCountedMetadata(SecurityReport report) {
        assertThat(report.violationDetails().total())
                .isEqualTo(report.results().stream()
                        .mapToInt(SecurityRuleResultDto::violationCount)
                        .sum());
    }

    private static SecurityRuleResultDto find(SecurityReport r, String id) {
        return r.results().stream().filter(x -> x.id().equals(id)).findFirst().orElse(null);
    }

    @Test
    void hardenedBaselineHasNoFindings() {
        Snap s = new Snap();
        s.permissions = List.of(new QuarkusSecurityPermission("api", "/api/*", "authenticated", null));
        SecurityReport r = scan(s);
        assertThat(r.violationsFound()).isZero();
        assertThat(r.filterChainsAnalyzed()).isEqualTo(1);
        assertThat(r.scan().status()).isEqualTo("PARTIAL");
        assertThat(r.evidence().usable()).isTrue();
        assertThat(r.evidence().coverageComplete()).isFalse();
        assertThat(r.evidence().limitations()).anyMatch(value -> value.contains("QS-AUTHZ-004"));
    }

    @Test
    void completedCleanChecksSurvivePartialConfigurationAndDismissals() {
        Snap snap = new Snap();
        snap.insecure = "enabled";
        snap.evidence = new QuarkusSecurityEvidence(
                Set.of("QS-CFG-001"), List.of("Configuration inventory was bounded."), List.of(), List.of(), true);
        QuarkusSecurityScanner scanner = QuarkusSecurityScanner.usingSnapshot(snap::build, CLOCK);
        SecurityReport report = scanner.scan();
        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.evidence().usable()).isTrue();
        assertThat(report.evidence().coverageComplete()).isFalse();
        assertThat(scanner.applyDismissals(report, Set.of("QS-AUTH-002")).evidence())
                .isEqualTo(report.evidence());
    }

    @Test
    void allUnknownObservationsAndFailedCollectionHaveNoCompletedChecks() {
        Snap snap = new Snap();
        java.util.Set<String> unknown = new java.util.HashSet<>();
        for (String category : List.of(
                "AUTH", "AUTHZ", "TLS", "CORS", "HDR", "DEV", "OIDC", "MGMT", "CFG", "SESSION", "GRPC", "GRAPHQL",
                "MSG")) {
            for (int number = 1; number <= 13; number++) {
                unknown.add("QS-" + category + "-" + String.format("%03d", number));
            }
        }
        snap.evidence = new QuarkusSecurityEvidence(unknown, List.of(), List.of(), List.of(), false);
        assertThat(scan(snap).evidence().usable()).isFalse();
        SecurityReport failed = QuarkusSecurityScanner.usingSnapshot(
                        () -> {
                            throw new IllegalStateException("private-value");
                        },
                        CLOCK)
                .scan();
        assertThat(failed.scan().status()).isEqualTo("ERROR");
        assertThat(failed.evidence().usable()).isFalse();
        assertThat(failed.evidence().coverageComplete()).isFalse();
        assertThat(failed.evidence().limitations()).noneMatch(value -> value.contains("private-value"));
    }

    @Test
    void authenticationEvaluationOwnsApplicabilityCompletionAndGenuinePartialFindings() {
        Set<String> unknown = new java.util.HashSet<>();
        for (String category : List.of(
                "AUTH", "AUTHZ", "TLS", "CORS", "HDR", "DEV", "OIDC", "MGMT", "CFG", "SESSION", "GRPC", "GRAPHQL",
                "MSG")) {
            for (int number = 1; number <= 13; number++) {
                unknown.add("QS-" + category + "-" + String.format("%03d", number));
            }
        }
        unknown.remove("QS-AUTH-002");
        Snap snap = new Snap();
        snap.evidence = new QuarkusSecurityEvidence(unknown, List.of(), List.of(), List.of(), true);
        snap.basic = false;
        var skipped = QuarkusSecurityChecks.evaluateObserved(snap.build());
        assertThat(skipped.evidence().usable()).isFalse();

        snap.basic = true;
        var clean = QuarkusSecurityChecks.evaluateObserved(snap.build());
        assertThat(clean.evidence().usable()).isTrue();

        snap.insecure = "enabled";
        var finding = QuarkusSecurityChecks.evaluateObserved(snap.build());
        assertThat(finding.evidence().usable()).isTrue();
        assertThat(finding.evidence().coverageComplete()).isFalse();
        assertThat(finding.findings())
                .singleElement()
                .satisfies(result -> assertThat(result.id()).isEqualTo("QS-AUTH-002"));
    }

    @Test
    void noAuthWithEndpointsFlagsAuth001() {
        Snap s = new Snap();
        s.basic = false;
        s.authenticated = 0;
        s.denyUnannotated = false;
        s.endpoints = 2;
        s.secured = 0;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-AUTH-001")).isNotNull();
        assertThat(find(r, "QS-AUTH-001").severity()).isEqualTo("HIGH");
        assertThat(r.violationsFound()).isGreaterThan(0);
    }

    @Test
    void noAuthButNoEndpointsDoesNotFlagAuth001() {
        Snap s = new Snap();
        s.basic = false;
        s.authenticated = 0;
        s.endpoints = 0;
        s.secured = 0;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-AUTH-001")).isNull();
    }

    @Test
    void basicAuthOverPlainHttpFlagsAuth002() {
        Snap s = new Snap();
        s.basic = true;
        s.insecure = "enabled";
        s.ssl = false;
        s.behindProxy = true; // suppress TLS rules to isolate
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-AUTH-002").severity()).isEqualTo("HIGH");
    }

    @Test
    void formAuthWithoutCsrfFlagsAuth003() {
        Snap s = new Snap();
        s.form = true;
        s.csrf = false;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-AUTH-003").severity()).isEqualTo("LOW");
    }

    @Test
    void formAuthWithCsrfDoesNotFlagAuth003() {
        Snap s = new Snap();
        s.form = true;
        s.csrf = true;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-AUTH-003")).isNull();
    }

    @Test
    void jwtWithoutIssuerFlagsAuth004() {
        Snap s = new Snap();
        s.jwt = true;
        s.jwtIssuer = false;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-AUTH-004").severity()).isEqualTo("MEDIUM");
    }

    @Test
    void jwtWithIssuerDoesNotFlagAuth004() {
        Snap s = new Snap();
        s.jwt = true;
        s.jwtIssuer = true;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-AUTH-004")).isNull();
    }

    @Test
    void proactiveTimingDoesNotReviveRetiredAuth005() {
        Snap s = new Snap();
        s.proactiveDisabled = true;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-AUTH-005")).isNull();
    }

    @Test
    void permitAllOnRootPathFlagsAuthz002() {
        Snap s = new Snap();
        s.permissions = List.of(new QuarkusSecurityPermission("open", "/*", "permit", null));
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-AUTHZ-002").severity()).isEqualTo("INFO");
    }

    @Test
    void permitPolicyDoesNotMasqueradeAsProtectiveAuthorization() {
        Snap s = new Snap();
        s.basic = false;
        s.authenticated = 0;
        s.denyUnannotated = false;
        s.permissions = List.of(new QuarkusSecurityPermission("open", "/*", "permit", null));

        assertThat(find(scan(s), "QS-AUTH-001")).isNotNull();

        s.basic = true;
        assertThat(find(scan(s), "QS-AUTHZ-001")).isNotNull();
    }

    @Test
    void permitAllOnScopedPathDoesNotFlagAuthz002() {
        // Regression: the old substring check matched "/public/*" against "/*".
        Snap s = new Snap();
        s.permissions = List.of(new QuarkusSecurityPermission("public", "/public/*", "permit", null));
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-AUTHZ-002")).isNull();
    }

    @Test
    void annotationRatioDoesNotReviveRetiredAuthz003() {
        Snap s = new Snap();
        s.endpoints = 6;
        s.secured = 1;
        s.authenticated = 1;
        s.denyUnannotated = true; // isolate from QS-AUTHZ-004
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-AUTHZ-003")).isNull();
    }

    @Test
    void unannotatedEndpointsWithoutDenyByDefaultFlagsAuthz004() {
        Snap s = new Snap();
        s.endpoints = 4;
        s.secured = 2;
        s.denyUnannotated = false;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-AUTHZ-004").severity()).isEqualTo("MEDIUM");
    }

    @Test
    void denyByDefaultSuppressesAuthz004() {
        Snap s = new Snap();
        s.endpoints = 4;
        s.secured = 2;
        s.denyUnannotated = true;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-AUTHZ-004")).isNull();
    }

    @Test
    void broadProtectivePolicySuppressesAuthz004() {
        Snap s = new Snap();
        s.endpoints = 4;
        s.secured = 2;
        s.denyUnannotated = false;
        s.permissions = List.of(new QuarkusSecurityPermission("all", "/*", "authenticated", null));
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-AUTHZ-004")).isNull();
    }

    @Test
    void insecureRequestsFlagsTls001EvenWhenForwardedHeadersAreTrusted() {
        Snap fires = new Snap();
        fires.insecure = "enabled";
        assertThat(find(scan(fires), "QS-TLS-001").severity()).isEqualTo("LOW");

        Snap proxied = new Snap();
        proxied.insecure = "enabled";
        proxied.behindProxy = true;
        assertThat(find(scan(proxied), "QS-TLS-001")).isNotNull();
    }

    @Test
    void noTlsFlagsTls002EvenWhenForwardedHeadersAreTrusted() {
        Snap fires = new Snap();
        fires.ssl = false;
        assertThat(find(scan(fires), "QS-TLS-002").severity()).isEqualTo("INFO");

        Snap proxied = new Snap();
        proxied.ssl = false;
        proxied.behindProxy = true;
        assertThat(find(scan(proxied), "QS-TLS-002")).isNotNull();
    }

    @Test
    void trustAllFlagsTls003() {
        Snap s = new Snap();
        s.tlsTrustAll = true;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-TLS-003").severity()).isEqualTo("HIGH");
    }

    @Test
    void wildcardCorsWithCredentialsIsHighReview() {
        Snap s = new Snap();
        s.cors = true;
        s.corsOrigins = "*";
        s.corsCreds = true;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-CORS-002").severity()).isEqualTo("HIGH");
        assertThat(find(r, "QS-CORS-001")).isNull();
    }

    @Test
    void wildcardCorsWithoutCredentialsFlagsCors001() {
        Snap s = new Snap();
        s.cors = true;
        s.corsOrigins = "*";
        s.corsCreds = false;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-CORS-001").severity()).isEqualTo("LOW");
    }

    @Test
    void trustedOriginReflectionDoesNotReviveRetiredCors003() {
        Snap s = new Snap();
        s.cors = true;
        s.corsOrigins = "https://app.example";
        s.corsCreds = true;
        s.corsMethods = "*";
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-CORS-003")).isNull();
        assertThat(find(r, "QS-CORS-002")).isNull();
    }

    @Test
    void pinnedCorsWithExplicitMethodsDoesNotFlagCors003() {
        Snap s = new Snap();
        s.cors = true;
        s.corsOrigins = "https://app.example";
        s.corsCreds = true;
        s.corsMethods = "GET,POST";
        s.corsHeaders = "Content-Type";
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-CORS-003")).isNull();
    }

    @Test
    void trustedOriginDefaultMethodsDoNotReviveRetiredCors003() {
        Snap s = new Snap();
        s.cors = true;
        s.corsOrigins = "https://app.example";
        s.corsCreds = true;
        s.corsMethods = null;
        s.corsHeaders = "Content-Type";
        assertThat(find(scan(s), "QS-CORS-003")).isNull();
    }

    @Test
    void multiEntryCorsMethodsAreNotTreatedAsWildcard() {
        Snap s = new Snap();
        s.cors = true;
        s.corsOrigins = "https://app.example";
        s.corsCreds = true;
        s.corsMethods = "*,GET";
        s.corsHeaders = "Content-Type";
        assertThat(find(scan(s), "QS-CORS-003")).isNull();
    }

    @Test
    void noSecurityHeadersFlagsHdr003AndHdr004() {
        Snap s = new Snap();
        s.hsts = false;
        s.csp = false;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-HDR-003").severity()).isEqualTo("LOW");
        assertThat(find(r, "QS-HDR-004").severity()).isEqualTo("LOW");
    }

    @Test
    void weakHstsFlagsHdr001() {
        Snap s = new Snap();
        s.hsts = true;
        s.hstsValue = "max-age=3600";
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-HDR-001").severity()).isEqualTo("LOW");
    }

    @Test
    void strongHstsDoesNotFlagHdr001() {
        Snap s = new Snap();
        s.hsts = true;
        s.hstsValue = "max-age=31536000; includeSubDomains";
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-HDR-001")).isNull();
    }

    @Test
    void hstsDoesNotRequireIncludeSubDomains() {
        Snap s = new Snap();
        s.hsts = true;
        s.hstsValue = "max-age=31536000";
        assertThat(find(scan(s), "QS-HDR-001")).isNull();
    }

    @Test
    void missingFramingHeadersFlagsHdr005() {
        Snap s = new Snap();
        s.xFrame = false;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-HDR-005").severity()).isEqualTo("LOW");
    }

    @Test
    void cspFrameAncestorsSatisfiesHdr005() {
        Snap s = new Snap();
        s.xFrame = false;
        s.cspValue = "default-src 'self'; frame-ancestors 'none'";
        s.xContentType = true;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-HDR-005")).isNull();
    }

    @Test
    void weakCspFlagsHdr002() {
        Snap s = new Snap();
        s.csp = true;
        s.cspValue = "default-src 'self'; script-src 'unsafe-inline'";
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-HDR-002").severity()).isEqualTo("MEDIUM");
    }

    @Test
    void wildcardScriptCspFlagsHdr002() {
        Snap s = new Snap();
        s.csp = true;
        s.cspValue = "default-src *";
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-HDR-002")).isNotNull();
    }

    @Test
    void oidcTlsVerificationNoneFlagsDev001() {
        Snap s = new Snap();
        s.oidcTlsNone = true;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-DEV-001").severity()).isEqualTo("HIGH");
    }

    @Test
    void swaggerAlwaysIncludeFlagsDev002() {
        Snap s = new Snap();
        s.swagger = true;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-DEV-002").severity()).isEqualTo("MEDIUM");
    }

    @Test
    void nonexistentOpenApiAlwaysIncludeFactDoesNotFlagDev002() {
        Snap s = new Snap();
        s.openApi = true;

        assertThat(find(scan(s), "QS-DEV-002")).isNull();
    }

    @Test
    void oidcWithoutAudienceFlagsOidc001() {
        Snap s = new Snap();
        s.oidc = true;
        s.oidcAudience = false;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-OIDC-001").severity()).isEqualTo("HIGH");
    }

    @Test
    void oidcWebAppWithoutAudienceDoesNotFlagOidc001() {
        Snap s = new Snap();
        s.oidc = true;
        s.oidcAudience = false;
        s.oidcAppType = "web-app";
        s.oidcServiceTokenConsumer = false;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-OIDC-001")).isNull();
    }

    @Test
    void oidcWebAppInsecureCookieFlagsOidc002() {
        Snap s = new Snap();
        s.oidc = true;
        s.oidcAudience = true;
        s.oidcAppType = "web-app";
        s.oidcCookieSecure = false;
        s.ssl = false;
        s.insecure = "enabled";
        s.behindProxy = true;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-OIDC-002").severity()).isEqualTo("MEDIUM");
    }

    @Test
    void oidcWebAppWithTlsDoesNotFlagOidc002() {
        Snap s = new Snap();
        s.oidc = true;
        s.oidcAudience = true;
        s.oidcAppType = "web-app";
        s.oidcCookieSecure = false;
        s.ssl = true;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-OIDC-002")).isNull();
    }

    @Test
    void managementOnNonLoopbackFlagsMgmt001() {
        Snap s = new Snap();
        s.mgmtEnabled = true;
        s.mgmtNonLoopback = true;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-MGMT-001").severity()).isEqualTo("LOW");
    }

    @Test
    void suspectedSecretFlagsCfg001() {
        Snap s = new Snap();
        s.secrets = List.of("app.api.password");
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-CFG-001").severity()).isEqualTo("MEDIUM");
    }

    @Test
    void jwtAlgorithmDefaultDoesNotFlagRetiredAuth006() {
        Snap s = new Snap();
        s.jwtAlgUnpinnedForRemoteJwks = true;
        assertThat(find(scan(s), "QS-AUTH-006")).isNull();
    }

    @Test
    void embeddedUsersEnabledFlagsAuth007() {
        Snap s = new Snap();
        s.embeddedUsers = true;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-AUTH-007").severity()).isEqualTo("MEDIUM");
    }

    @Test
    void embeddedUsersWithPlainTextPasswordsFlagsAuth013() {
        Snap s = new Snap();
        s.embeddedUsers = true;
        s.embeddedUsersPlainText = true;
        assertThat(find(scan(s), "QS-AUTH-013").severity()).isEqualTo("HIGH");
    }

    @Test
    void plainTextSettingWithoutEmbeddedUsersDoesNotFlagAuth013() {
        Snap s = new Snap();
        s.embeddedUsers = false;
        s.embeddedUsersPlainText = true;
        assertThat(find(scan(s), "QS-AUTH-013")).isNull();
    }

    @Test
    void jwtWithoutAudienceFlagsAuth008() {
        Snap s = new Snap();
        s.jwt = true;
        s.jwtAudiences = false;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-AUTH-008").severity()).isEqualTo("MEDIUM");
    }

    @Test
    void jwtWithAudienceDoesNotFlagAuth008() {
        Snap s = new Snap();
        s.jwt = true;
        s.jwtAudiences = true;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-AUTH-008")).isNull();
    }

    @Test
    void jwtInlinePublicKeyFlagsAuth009() {
        Snap s = new Snap();
        s.jwt = true;
        s.jwtInlineKey = true;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-AUTH-009").severity()).isEqualTo("INFO");
    }

    @Test
    void jwtJwksLocationDoesNotFlagAuth009() {
        Snap s = new Snap();
        s.jwt = true;
        s.jwtInlineKey = false;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-AUTH-009")).isNull();
    }

    @Test
    void jdbcClearPasswordMapperFlagsAuth010() {
        Snap s = new Snap();
        s.jdbcClearPasswordMapper = true;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-AUTH-010").severity()).isEqualTo("HIGH");
    }

    @Test
    void formAuthOverPlainHttpFlagsAuth012() {
        Snap s = new Snap();
        s.form = true;
        s.csrf = true;
        s.insecure = "enabled";
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-AUTH-012").severity()).isEqualTo("HIGH");
    }

    @Test
    void formAuthBehindProxyStillFlagsAcceptedPlainHttpListener() {
        Snap s = new Snap();
        s.form = true;
        s.csrf = true;
        s.insecure = "enabled";
        s.behindProxy = true;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-AUTH-012")).isNotNull();
    }

    @Test
    void insecureIdentityProviderUrlFlagsTls004() {
        Snap s = new Snap();
        s.insecureIdentityProviderUrl = true;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-TLS-004").severity()).isEqualTo("HIGH");
    }

    @Test
    void disabledTlsHostnameVerificationFlagsTls005() {
        Snap s = new Snap();
        s.tlsHostnameVerificationDisabled = List.of("default TLS registry bucket", "OIDC tenant partner");
        SecurityRuleResultDto finding = find(scan(s), "QS-TLS-005");
        assertThat(finding.severity()).isEqualTo("HIGH");
        assertThat(finding.violationCount()).isEqualTo(2);
        assertThat(finding.sampleViolations()).containsExactly("default TLS registry bucket", "OIDC tenant partner");
    }

    @Test
    void oidcIssuerAnyFlagsOidc004() {
        Snap s = new Snap();
        s.oidc = true;
        s.oidcIssuerAny = true;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-OIDC-004").severity()).isEqualTo("HIGH");
    }

    @Test
    void missingReferrerPolicyDoesNotReviveRetiredHdr007() {
        Snap s = new Snap();
        s.referrerPolicy = false;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-HDR-007")).isNull();
    }

    @Test
    void missingPermissionsPolicyDoesNotReviveRetiredHdr008() {
        Snap s = new Snap();
        s.permissionsPolicy = false;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-HDR-008")).isNull();
    }

    @Test
    void graphqlUiAlwaysIncludeFlagsDev002() {
        Snap s = new Snap();
        s.graphqlUi = true;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-DEV-002").severity()).isEqualTo("MEDIUM");
    }

    @Test
    void mergedNamespaceDoesNotReviveRetiredMgmt002() {
        Snap s = new Snap();
        s.nonAppRootPath = "/";
        s.nonAppRootPathMerged = true;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-MGMT-002")).isNull();
    }

    @Test
    void defaultNonApplicationRootPathDoesNotFlagMgmt002() {
        Snap s = new Snap();
        s.nonAppRootPath = "q";
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-MGMT-002")).isNull();
    }

    @Test
    void customMergedNamespaceDoesNotReviveRetiredMgmt002() {
        Snap s = new Snap();
        s.nonAppRootPath = "/api";
        s.nonAppRootPathMerged = true;
        assertThat(find(scan(s), "QS-MGMT-002")).isNull();
    }

    @Test
    void formAuthCookieNotHttpOnlyFlagsSession001() {
        Snap s = new Snap();
        s.form = true;
        s.csrf = true; // isolate from QS-AUTH-003
        s.formHttpOnly = false;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-SESSION-001").severity()).isEqualTo("MEDIUM");
    }

    @Test
    void formAuthCookieHttpOnlyDoesNotFlagSession001() {
        Snap s = new Snap();
        s.form = true;
        s.csrf = true;
        s.formHttpOnly = true;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-SESSION-001")).isNull();
    }

    @Test
    void formAuthCookieSameSiteNoneFlagsSession002() {
        Snap s = new Snap();
        s.form = true;
        s.csrf = true;
        s.formSameSiteNone = true;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-SESSION-002").severity()).isEqualTo("LOW");
    }

    @Test
    void formAuthExcessiveSessionTimeoutFlagsSession003() {
        Snap s = new Snap();
        s.form = true;
        s.csrf = true;
        s.formTimeoutExcessive = true;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-SESSION-003").severity()).isEqualTo("LOW");
    }

    @Test
    void noFormAuthDoesNotFlagSessionRules() {
        Snap s = new Snap();
        s.form = false;
        s.formHttpOnly = false;
        s.formSameSiteNone = true;
        s.formTimeoutExcessive = true;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-SESSION-001")).isNull();
        assertThat(find(r, "QS-SESSION-002")).isNull();
        assertThat(find(r, "QS-SESSION-003")).isNull();
    }

    @Test
    void grpcReflectionEnabledInProdFlagsGrpc001() {
        Snap s = new Snap();
        s.grpcReflectionProd = true;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-GRPC-001").severity()).isEqualTo("MEDIUM");
    }

    @Test
    void graphqlIntrospectionEnabledFlagsGraphql001() {
        Snap s = new Snap();
        s.graphqlPresent = true;
        s.graphqlIntrospection = true;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-GRAPHQL-001").severity()).isEqualTo("LOW");
    }

    @Test
    void graphqlAbsentDoesNotFlagGraphql001() {
        Snap s = new Snap();
        s.graphqlPresent = false;
        s.graphqlIntrospection = true;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-GRAPHQL-001")).isNull();
    }

    @Test
    void messagingCredentialsWithoutTlsFlagsMsg001() {
        Snap s = new Snap();
        s.insecureMessagingChannels = List.of("kafka (global default)");
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-MSG-001").severity()).isEqualTo("HIGH");
        assertThat(find(r, "QS-MSG-001").sampleViolations()).containsExactly("kafka (global default)");
    }

    @Test
    void noInsecureMessagingChannelsDoesNotFlagMsg001() {
        Snap s = new Snap();
        s.insecureMessagingChannels = List.of();
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-MSG-001")).isNull();
    }

    @Test
    void singleInsecureChannelNameAppearsAsMsg001Sample() {
        // The engine trusts insecureMessagingChannels() as already-resolved per-channel names; the provider
        // (QuarkusSecuritySnapshotProviderImpl) owns evaluating each channel prefix independently so one
        // channel's secure protocol can't mask another's insecure one.
        Snap s = new Snap();
        s.insecureMessagingChannels = List.of("orders-out");
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-MSG-001").violationCount()).isEqualTo(1);
        assertThat(find(r, "QS-MSG-001").sampleViolations()).containsExactly("orders-out");
    }

    @Test
    void publicOidcClientWithoutPkceFlagsOidc003() {
        Snap s = new Snap();
        s.oidc = true;
        s.oidcAppType = "web-app";
        s.oidcHasClientSecret = false;
        s.oidcPkceRequired = false;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-OIDC-003").severity()).isEqualTo("MEDIUM");
    }

    @Test
    void confidentialOidcClientDoesNotFlagOidc003() {
        Snap s = new Snap();
        s.oidc = true;
        s.oidcAppType = "web-app";
        s.oidcHasClientSecret = true;
        s.oidcPkceRequired = false;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-OIDC-003")).isNull();
    }

    @Test
    void publicOidcClientWithPkceDoesNotFlagOidc003() {
        Snap s = new Snap();
        s.oidc = true;
        s.oidcAppType = "web-app";
        s.oidcHasClientSecret = false;
        s.oidcPkceRequired = true;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-OIDC-003")).isNull();
    }

    @Test
    void serviceApplicationTypeDoesNotFlagOidc003() {
        Snap s = new Snap();
        s.oidc = true;
        s.oidcAppType = "service";
        s.oidcHasClientSecret = false;
        s.oidcPkceRequired = false;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-OIDC-003")).isNull();
    }

    @Test
    void healthUiAlwaysIncludeFlagsDev003() {
        Snap s = new Snap();
        s.healthUiAlwaysInclude = true;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-DEV-003").severity()).isEqualTo("LOW");
    }

    @Test
    void healthUiNotAlwaysIncludeDoesNotFlagDev003() {
        Snap s = new Snap();
        s.healthUiAlwaysInclude = false;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-DEV-003")).isNull();
    }

    @Test
    void managementHostUnpinnedForProdFlagsMgmt003() {
        Snap s = new Snap();
        s.mgmtEnabled = true;
        s.mgmtHostUnpinnedForProd = true;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-MGMT-003").severity()).isEqualTo("INFO");
    }

    @Test
    void managementHostPinnedForProdDoesNotFlagMgmt003() {
        Snap s = new Snap();
        s.mgmtEnabled = true;
        s.mgmtHostUnpinnedForProd = false;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-MGMT-003")).isNull();
    }

    @Test
    void managementDisabledDoesNotFlagMgmt003() {
        Snap s = new Snap();
        s.mgmtEnabled = false;
        s.mgmtHostUnpinnedForProd = false;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-MGMT-003")).isNull();
    }

    @Test
    void unsetCorsOriginsRemainRestrictiveWithoutRetiredCors005() {
        Snap s = new Snap();
        s.cors = true;
        s.corsOrigins = null;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-CORS-005")).isNull();
        assertThat(find(r, "QS-CORS-001")).isNull();
        assertThat(find(r, "QS-CORS-002")).isNull();
    }

    @Test
    void blankCorsOriginsDoNotReviveRetiredCors005() {
        Snap s = new Snap();
        s.cors = true;
        s.corsOrigins = "  ";
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-CORS-005")).isNull();
    }

    @Test
    void pinnedCorsOriginsDoesNotFlagCors005() {
        Snap s = new Snap();
        s.cors = true;
        s.corsOrigins = "https://app.example";
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-CORS-005")).isNull();
    }

    @Test
    void multiEntryOriginsWithWildcardIsNotExplicitWildcard() {
        // Regression: real Quarkus's isOriginConfiguredWithWildcard only treats a SINGLE "*" entry as a
        // wildcard; a multi-entry list containing "*" alongside another origin is not a wildcard match.
        Snap s = new Snap();
        s.cors = true;
        s.corsOrigins = "*,https://app.example";
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-CORS-001")).isNull();
        assertThat(find(r, "QS-CORS-002")).isNull();
        assertThat(find(r, "QS-CORS-005")).isNull();
    }

    @Test
    void methodScopedPermitAllDoesNotFlagAuthz002() {
        // Regression: a permit policy scoped to a single HTTP method on a root path is not equivalent to an
        // unrestricted permit — it should not be over-flagged as "permits all paths".
        Snap s = new Snap();
        s.permissions = List.of(new QuarkusSecurityPermission("cors-preflight", "/*", "permit", "OPTIONS"));
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-AUTHZ-002")).isNull();
    }

    @Test
    void unrestrictedPermitAllOnRootPathStillFlagsAuthz002() {
        Snap s = new Snap();
        s.permissions = List.of(new QuarkusSecurityPermission("open", "/*", "permit", null));
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-AUTHZ-002").severity()).isEqualTo("INFO");
    }

    @Test
    void methodScopedProtectivePolicyDeniesOtherMethods() {
        Snap s = new Snap();
        s.endpoints = 4;
        s.secured = 2;
        s.denyUnannotated = false;
        s.permissions = List.of(new QuarkusSecurityPermission("get-only", "/*", "authenticated", "GET"));
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-AUTHZ-004")).isNull();
    }

    @Test
    void unrestrictedProtectivePolicyStillSuppressesAuthz004() {
        Snap s = new Snap();
        s.endpoints = 4;
        s.secured = 2;
        s.denyUnannotated = false;
        s.permissions = List.of(new QuarkusSecurityPermission("all", "/*", "authenticated", null));
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-AUTHZ-004")).isNull();
    }

    @Test
    void defaultRolesSuppressMissingAuthorizationFindings() {
        Snap s = new Snap();
        s.authenticated = 0;
        s.secured = 0;
        s.denyUnannotated = false;
        s.defaultRolesAllowed = true;
        SecurityReport report = scan(s);
        assertThat(find(report, "QS-AUTHZ-001")).isNull();
        assertThat(find(report, "QS-AUTHZ-004")).isNull();
    }

    @Test
    void quarkusAuthorizationAnnotationsCountAsProtection() {
        Snap s = new Snap();
        s.basic = false;
        s.authenticated = 0;
        s.secured = 1;
        s.quarkusAuthorizationAnnotations = 1;
        assertThat(find(scan(s), "QS-AUTH-001")).isNull();
    }

    @Test
    void quarkusAuthorizationAnnotationsDoNotReviveRetiredCoverageRatio() {
        Snap s = new Snap();
        s.authenticated = 0;
        s.endpoints = 4;
        s.secured = 1;
        s.quarkusAuthorizationAnnotations = 1;
        s.denyUnannotated = true;

        assertThat(find(scan(s), "QS-AUTHZ-003")).isNull();
    }

    @Test
    void dismissalsHideMatchingFindings() {
        Snap s = new Snap();
        s.basic = false;
        s.authenticated = 0;
        s.denyUnannotated = false;
        s.endpoints = 2;
        s.secured = 0;
        QuarkusSecurityScanner scanner = QuarkusSecurityScanner.usingSnapshot(s::build, CLOCK);
        SecurityReport scanned = scanner.scan();
        int before = scanned.violationsFound();
        SecurityReport after = scanner.applyDismissals(scanned, Set.of("QS-AUTH-001"));
        assertThat(after.violationsFound()).isEqualTo(before - 1);
    }

    @Test
    void initialReportIsNotScanned() {
        Snap s = new Snap();
        SecurityReport r = QuarkusSecurityScanner.usingSnapshot(s::build, CLOCK).initialReport();
        assertThat(r.scan().status()).isEqualTo("NOT_SCANNED");
        assertThat(r.violationsFound()).isZero();
    }

    @Test
    void allRuleIdsUseQsPrefix() {
        Snap s = new Snap();
        s.basic = false;
        s.authenticated = 0;
        s.denyUnannotated = false;
        s.endpoints = 2;
        s.secured = 0;
        SecurityReport r = scan(s);
        assertThat(r.results()).allSatisfy(x -> assertThat(x.id()).startsWith("QS-"));
        assertThat(r.scan().rulesEvaluated()).isEqualTo(45);
    }

    @Test
    void legacyTlsProtocolDeclarationsFlagTls006WithValueFreeLabels() {
        Snap s = new Snap();
        s.legacyTlsProtocols = List.of("default TLS registry bucket (TLSv1.1)", "HTTP server SSL declaration (TLSv1)");
        SecurityRuleResultDto result = find(scan(s), "QS-TLS-006");
        assertThat(result.severity()).isEqualTo("LOW");
        assertThat(result.category()).isEqualTo("Transport");
        assertThat(result.violationCount()).isEqualTo(2);
        assertThat(result.sampleViolations()).containsExactlyElementsOf(s.legacyTlsProtocols);
        assertThat(result.learnMoreUrl())
                .isEqualTo("https://quarkus.io/version/3.33/guides/tls-registry-reference#tls-protocol-versions");
        assertThat(find(scan(new Snap()), "QS-TLS-006")).isNull();
    }

    @Test
    void forwardedHeadersTrustedFromAnyProxyFlagsProxy001() {
        Snap s = new Snap();
        s.forwardedHeadersTrustAnyProxy = true;
        SecurityRuleResultDto result = find(scan(s), "QS-PROXY-001");
        assertThat(result.severity()).isEqualTo("LOW");
        assertThat(result.category()).isEqualTo("Proxy");
        assertThat(result.learnMoreUrl())
                .isEqualTo("https://quarkus.io/version/3.33/guides/http-reference#reverse-proxy");
        s.forwardedHeadersTrustAnyProxy = false;
        s.behindProxy = true;
        assertThat(find(scan(s), "QS-PROXY-001")).isNull();
    }

    @Test
    void disabledOidcTokenEncryptionFlagsOidc005OnlyForWebTenants() {
        Snap s = new Snap();
        s.oidc = true;
        s.oidcAppType = "web-app";
        s.oidcServiceTokenConsumer = false;
        s.oidcTokenEncryptionDisabled = true;
        SecurityRuleResultDto result = find(scan(s), "QS-OIDC-005");
        assertThat(result.severity()).isEqualTo("MEDIUM");
        assertThat(result.learnMoreUrl())
                .isEqualTo(
                        "https://quarkus.io/version/3.33/guides/security-oidc-code-flow-authentication#token-state-manager");

        s.oidcAppType = "service";
        s.oidcServiceTokenConsumer = true;
        assertThat(find(scan(s), "QS-OIDC-005")).isNull();

        s.oidcAppType = "hybrid";
        assertThat(find(scan(s), "QS-OIDC-005")).isNotNull();
        s.oidcTokenEncryptionDisabled = false;
        assertThat(find(scan(s), "QS-OIDC-005")).isNull();
    }

    @Test
    void unknownNewRuleEvidenceSuppressesTheFindingAndReportsPartialCoverage() {
        Snap s = new Snap();
        s.forwardedHeadersTrustAnyProxy = true;
        s.legacyTlsProtocols = List.of("named TLS registry declaration (TLSv1)");
        s.evidence = new QuarkusSecurityEvidence(
                Set.of("QS-PROXY-001", "QS-TLS-006"),
                List.of("unresolved production declaration"),
                List.of(),
                List.of(),
                false);
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-PROXY-001")).isNull();
        assertThat(find(r, "QS-TLS-006")).isNull();
        assertThat(r.scan().status()).isEqualTo("PARTIAL");
    }

    @Test
    void everyRuleLinksToARuleSpecificQuarkusGuide() {
        List<String> ids = List.of(
                "QS-AUTH-001",
                "QS-AUTH-002",
                "QS-AUTH-003",
                "QS-AUTH-004",
                "QS-AUTH-007",
                "QS-AUTH-008",
                "QS-AUTH-009",
                "QS-AUTH-010",
                "QS-AUTH-012",
                "QS-AUTH-013",
                "QS-AUTHZ-001",
                "QS-AUTHZ-002",
                "QS-AUTHZ-004",
                "QS-TLS-001",
                "QS-TLS-002",
                "QS-TLS-003",
                "QS-TLS-004",
                "QS-TLS-005",
                "QS-TLS-006",
                "QS-CORS-001",
                "QS-CORS-002",
                "QS-HDR-001",
                "QS-HDR-002",
                "QS-HDR-003",
                "QS-HDR-004",
                "QS-HDR-005",
                "QS-HDR-006",
                "QS-DEV-001",
                "QS-DEV-002",
                "QS-DEV-003",
                "QS-OIDC-001",
                "QS-OIDC-002",
                "QS-OIDC-003",
                "QS-OIDC-004",
                "QS-OIDC-005",
                "QS-MGMT-001",
                "QS-MGMT-003",
                "QS-CFG-001",
                "QS-SESSION-001",
                "QS-SESSION-002",
                "QS-SESSION-003",
                "QS-GRPC-001",
                "QS-GRAPHQL-001",
                "QS-MSG-001",
                "QS-PROXY-001");
        assertThat(ids).hasSize(QuarkusSecurityChecks.ruleCount()).doesNotHaveDuplicates();
        // Verified against the pinned Quarkus 3.33 guides and anchors; only the authentication-absence rule uses the
        // overview.
        assertThat(ids)
                .allSatisfy(id -> assertThat(QuarkusSecurityChecks.learnMore(id))
                        .startsWith("https://quarkus.io/version/3.33/guides/")
                        .satisfies(url ->
                                assertThat(url.endsWith("/security-overview")).isEqualTo(id.equals("QS-AUTH-001"))));
        assertThat(QuarkusSecurityChecks.learnMore("QS-SESSION-002"))
                .isEqualTo("https://quarkus.io/version/3.33/guides/security-authentication-mechanisms#form-auth");
        assertThat(QuarkusSecurityChecks.learnMore("QS-HDR-004"))
                .isEqualTo("https://quarkus.io/version/3.33/guides/http-reference#additional-http-headers");
    }

    @Test
    void incompleteEvidenceDoesNotEraseUnrelatedKnownViolationsOrInventPasses() {
        Snap s = new Snap();
        s.insecure = "enabled";
        s.jwt = true;
        s.jwtIssuer = false;
        s.evidence = new QuarkusSecurityEvidence(
                Set.of("QS-AUTH-004"),
                List.of("custom verifier"),
                List.of("JWT configuration could not be read"),
                List.of(),
                true);
        SecurityReport report = scan(s);
        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(find(report, "QS-AUTH-002")).isNotNull();
        assertThat(find(report, "QS-AUTH-004")).isNull();
        assertThat(report.analysisErrors())
                .singleElement()
                .satisfies(error -> assertThat(error.status()).isEqualTo("ERROR"));
        assertThat(report.results()).allMatch(result -> result.status().equals("VIOLATION"));
    }

    @Test
    void nullOrFailedSnapshotHasValueFreeAnalysisError() {
        var report = QuarkusSecurityScanner.usingSnapshot(
                        () -> {
                            throw new IllegalArgumentException("secret-value");
                        },
                        CLOCK)
                .scan();
        assertThat(report.scan().status()).isEqualTo("ERROR");
        assertThat(report.analysisErrors()).hasSize(1);
        assertThat(report.toString()).doesNotContain("secret-value");
        assertThat(QuarkusSecurityScanner.usingSnapshot(() -> null, CLOCK)
                        .scan()
                        .scan()
                        .status())
                .isEqualTo("ERROR");
    }

    @Test
    void nativeEndpointEvidenceUsesWinningPathRatherThanBroadPolicyShortcut() {
        Snap s = new Snap();
        s.denyUnannotated = false;
        s.secured = 0;
        s.permissions = List.of(
                new QuarkusSecurityPermission("root", "/*", "authenticated", null),
                new QuarkusSecurityPermission("open", "/open", "permit", null));
        s.evidence = new QuarkusSecurityEvidence(
                Set.of(),
                List.of(),
                List.of(),
                List.of(
                        new QuarkusSecurityEndpoint("/open", "GET", QuarkusSecurityEndpoint.Access.UNANNOTATED, false),
                        new QuarkusSecurityEndpoint(
                                "/closed", "GET", QuarkusSecurityEndpoint.Access.UNANNOTATED, false),
                        new QuarkusSecurityEndpoint(
                                "/intentional", "GET", QuarkusSecurityEndpoint.Access.PERMIT, false)),
                true);
        assertThat(find(scan(s), "QS-AUTHZ-004").violationCount()).isEqualTo(1);
    }

    @Test
    void exactRootPermissionIsNotAnApplicationWidePublicDefault() {
        Snap s = new Snap();
        s.permissions = List.of(new QuarkusSecurityPermission("root", "/", "permit", null));
        assertThat(find(scan(s), "QS-AUTHZ-002")).isNull();
    }

    @Test
    void httpsMaterialDoesNotSecureOidcCookiesOnAcceptedHttp() {
        Snap s = new Snap();
        s.oidc = true;
        s.oidcAppType = "web-app";
        s.oidcCookieSecure = false;
        s.ssl = true;
        s.insecure = "enabled";
        assertThat(find(scan(s), "QS-OIDC-002")).isNotNull();
        s.oidcCookieSecure = true;
        assertThat(find(scan(s), "QS-OIDC-002")).isNull();
    }

    @Test
    void scriptPolicyIgnoresStyleOnlyInlineAndHonorsRestrictiveOverrides() {
        for (String policy : List.of(
                "script-src 'self'; style-src 'unsafe-inline'",
                "default-src *; script-src 'none'",
                "script-src 'nonce-YWJjZA==' 'strict-dynamic' 'unsafe-inline' https:")) {
            Snap s = new Snap();
            s.cspValue = policy;
            assertThat(find(scan(s), "QS-HDR-002")).as(policy).isNull();
        }
    }

    @Test
    void universalFramingDirectiveIsNotClickjackingProtection() {
        Snap s = new Snap();
        s.xFrame = false;
        s.cspValue = "script-src 'self'; frame-ancestors *";
        assertThat(find(scan(s), "QS-HDR-005")).isNotNull();
    }

    @Test
    void enforcingFrameAncestorsOverridesOtherwiseValidXFrameOptions() {
        Snap s = new Snap();
        s.xFrame = true;
        s.cspValue = "script-src 'self'; frame-ancestors *";
        assertThat(find(scan(s), "QS-HDR-005")).isNotNull();
        s.cspValue = "script-src 'self'";
        assertThat(find(scan(s), "QS-HDR-005")).isNull();
        s.xFrame = false;
        assertThat(find(scan(s), "QS-HDR-005")).isNotNull();
        s.cspValue = "script-src 'self'; frame-ancestors";
        assertThat(find(scan(s), "QS-HDR-005")).isNull();
    }

    @Test
    void incompleteCspNeverEstablishesMissingFramingOrWeakScriptPolicy() {
        for (String policy : java.util.Arrays.asList(null, "script-src *, frame-ancestors 'none'", "x".repeat(8193))) {
            for (boolean xFrame : new boolean[] {false, true}) {
                Snap s = new Snap();
                s.csp = true;
                s.xFrame = xFrame;
                s.cspValue = policy;
                var report = scan(s);
                assertThat(report.scan().status()).isEqualTo("PARTIAL");
                assertThat(find(report, "QS-HDR-002")).isNull();
                assertThat(find(report, "QS-HDR-005")).isNull();
                assertThat(report.analysisErrors()).isEmpty();
            }
        }
    }

    @Test
    void regexUniversalOriginInsideListIsCredentialedReview() {
        Snap s = new Snap();
        s.cors = true;
        s.corsCreds = true;
        s.corsOrigins = "/.*/,https://app.example";
        assertThat(find(scan(s), "QS-CORS-002")).isNotNull();
        assertThat(find(scan(s), "QS-CORS-001")).isNull();
    }
}
