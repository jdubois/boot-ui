package io.github.jdubois.bootui.engine.quarkussecurity;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.SecurityReport;
import io.github.jdubois.bootui.core.dto.SecurityRuleResultDto;
import io.github.jdubois.bootui.spi.QuarkusSecurityPermission;
import io.github.jdubois.bootui.spi.QuarkusSecuritySnapshot;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class QuarkusSecurityScannerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC);

    /**
     * Mutable builder whose defaults describe a hardened Quarkus app that fires zero rules, so each test can
     * flip exactly the fields under test. Mirrors the 60-field {@link QuarkusSecuritySnapshot} positional record.
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
        String nonAppRootPath = "/q";
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
                    healthUiAlwaysInclude);
        }
    }

    private static SecurityReport scan(Snap s) {
        return QuarkusSecurityScanner.usingSnapshot(s::build, CLOCK).scan();
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
        assertThat(r.scan().status()).isEqualTo("SCANNED");
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
        assertThat(find(r, "QS-AUTH-003").severity()).isEqualTo("HIGH");
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
    void proactiveAuthDisabledFlagsAuth005() {
        Snap s = new Snap();
        s.proactiveDisabled = true;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-AUTH-005").severity()).isEqualTo("INFO");
    }

    @Test
    void permitAllOnRootPathFlagsAuthz002() {
        Snap s = new Snap();
        s.permissions = List.of(new QuarkusSecurityPermission("open", "/*", "permit", null));
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-AUTHZ-002").severity()).isEqualTo("HIGH");
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
    void mostlyUnsecuredEndpointsFlagsAuthz003() {
        Snap s = new Snap();
        s.endpoints = 6;
        s.secured = 1;
        s.authenticated = 1;
        s.denyUnannotated = true; // isolate from QS-AUTHZ-004
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-AUTHZ-003").severity()).isEqualTo("LOW");
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
    void insecureRequestsFlagsTls001ButProxySuppresses() {
        Snap fires = new Snap();
        fires.insecure = "enabled";
        assertThat(find(scan(fires), "QS-TLS-001").severity()).isEqualTo("LOW");

        Snap proxied = new Snap();
        proxied.insecure = "enabled";
        proxied.behindProxy = true;
        assertThat(find(scan(proxied), "QS-TLS-001")).isNull();
    }

    @Test
    void noTlsFlagsTls002ButProxySuppresses() {
        Snap fires = new Snap();
        fires.ssl = false;
        assertThat(find(scan(fires), "QS-TLS-002").severity()).isEqualTo("INFO");

        Snap proxied = new Snap();
        proxied.ssl = false;
        proxied.behindProxy = true;
        assertThat(find(scan(proxied), "QS-TLS-002")).isNull();
    }

    @Test
    void trustAllFlagsTls003() {
        Snap s = new Snap();
        s.tlsTrustAll = true;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-TLS-003").severity()).isEqualTo("HIGH");
    }

    @Test
    void wildcardCorsWithCredentialsIsCritical() {
        Snap s = new Snap();
        s.cors = true;
        s.corsOrigins = "*";
        s.corsCreds = true;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-CORS-002").severity()).isEqualTo("CRITICAL");
        assertThat(find(r, "QS-CORS-001")).isNull();
    }

    @Test
    void wildcardCorsWithoutCredentialsFlagsCors001() {
        Snap s = new Snap();
        s.cors = true;
        s.corsOrigins = "*";
        s.corsCreds = false;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-CORS-001").severity()).isEqualTo("MEDIUM");
    }

    @Test
    void pinnedCorsWithCredentialsAndWildcardMethodsFlagsCors003() {
        Snap s = new Snap();
        s.cors = true;
        s.corsOrigins = "https://app.example";
        s.corsCreds = true;
        s.corsMethods = "*";
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-CORS-003").severity()).isEqualTo("MEDIUM");
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
        assertThat(find(r, "QS-DEV-001").severity()).isEqualTo("MEDIUM");
    }

    @Test
    void swaggerAlwaysIncludeFlagsDev002() {
        Snap s = new Snap();
        s.swagger = true;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-DEV-002").severity()).isEqualTo("MEDIUM");
    }

    @Test
    void oidcWithoutAudienceFlagsOidc001() {
        Snap s = new Snap();
        s.oidc = true;
        s.oidcAudience = false;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-OIDC-001").severity()).isEqualTo("MEDIUM");
    }

    @Test
    void oidcWebAppInsecureCookieFlagsOidc002() {
        Snap s = new Snap();
        s.oidc = true;
        s.oidcAudience = true;
        s.oidcAppType = "web-app";
        s.oidcCookieSecure = false;
        s.ssl = false;
        s.behindProxy = true; // suppress TLS rules
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
        assertThat(find(r, "QS-CFG-001").severity()).isEqualTo("CRITICAL");
    }

    @Test
    void jwtAlgorithmUnpinnedForRemoteJwksFlagsAuth006() {
        Snap s = new Snap();
        s.jwtAlgUnpinnedForRemoteJwks = true;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-AUTH-006").severity()).isEqualTo("MEDIUM");
    }

    @Test
    void embeddedUsersEnabledFlagsAuth007() {
        Snap s = new Snap();
        s.embeddedUsers = true;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-AUTH-007").severity()).isEqualTo("MEDIUM");
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
        assertThat(find(r, "QS-AUTH-009").severity()).isEqualTo("LOW");
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
    void missingReferrerPolicyFlagsHdr007() {
        Snap s = new Snap();
        s.referrerPolicy = false;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-HDR-007").severity()).isEqualTo("INFO");
    }

    @Test
    void missingPermissionsPolicyFlagsHdr008() {
        Snap s = new Snap();
        s.permissionsPolicy = false;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-HDR-008").severity()).isEqualTo("INFO");
    }

    @Test
    void graphqlUiAlwaysIncludeFlagsDev002() {
        Snap s = new Snap();
        s.graphqlUi = true;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-DEV-002").severity()).isEqualTo("MEDIUM");
    }

    @Test
    void nonApplicationRootPathCollapsedFlagsMgmt002() {
        Snap s = new Snap();
        s.nonAppRootPath = "/";
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-MGMT-002").severity()).isEqualTo("MEDIUM");
    }

    @Test
    void defaultNonApplicationRootPathDoesNotFlagMgmt002() {
        Snap s = new Snap();
        s.nonAppRootPath = "/q";
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-MGMT-002")).isNull();
    }

    @Test
    void formAuthCookieNotHttpOnlyFlagsSession001() {
        Snap s = new Snap();
        s.form = true;
        s.csrf = true; // isolate from QS-AUTH-003
        s.formHttpOnly = false;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-SESSION-001").severity()).isEqualTo("HIGH");
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
        assertThat(find(r, "QS-SESSION-002").severity()).isEqualTo("MEDIUM");
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
        s.mgmtHostUnpinnedForProd = true;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-MGMT-003")).isNull();
    }

    @Test
    void unsetCorsOriginsFlagsCors005AsInfoNotWildcard() {
        Snap s = new Snap();
        s.cors = true;
        s.corsOrigins = null;
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-CORS-005").severity()).isEqualTo("INFO");
        assertThat(find(r, "QS-CORS-001")).isNull();
        assertThat(find(r, "QS-CORS-002")).isNull();
    }

    @Test
    void blankCorsOriginsFlagsCors005() {
        Snap s = new Snap();
        s.cors = true;
        s.corsOrigins = "  ";
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-CORS-005").severity()).isEqualTo("INFO");
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
        assertThat(find(r, "QS-AUTHZ-002").severity()).isEqualTo("HIGH");
    }

    @Test
    void methodScopedProtectivePolicyDoesNotSuppressAuthz004() {
        // Regression: a protective policy scoped to only one HTTP method (e.g. GET) does not actually cover
        // every unannotated endpoint, so it must not suppress the "no deny-by-default" finding.
        Snap s = new Snap();
        s.endpoints = 4;
        s.secured = 2;
        s.denyUnannotated = false;
        s.permissions = List.of(new QuarkusSecurityPermission("get-only", "/*", "authenticated", "GET"));
        SecurityReport r = scan(s);
        assertThat(find(r, "QS-AUTHZ-004").severity()).isEqualTo("MEDIUM");
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
}
