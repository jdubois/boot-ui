package io.github.jdubois.bootui.engine.reactivesecurity;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.SecurityReport;
import io.github.jdubois.bootui.core.dto.SecurityRuleResultDto;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for the framework-neutral reactive Spring Security advisor: {@link
 * ReactiveSecurityScanner}, {@link ReactiveSecurityRuleRegistry}, and the 26 {@code SEC-RXF-*} rules.
 * Everything here builds a plain {@link ReactiveSecurityObservation} — no Spring, no reflection, no
 * {@code MockEnvironment} — mirroring how {@code SpringReactiveSecurityObservationCollector} feeds the
 * scanner in production.
 */
class ReactiveSecurityScannerTests {

    private static final int RULE_COUNT = ReactiveSecurityRuleRegistry.RULE_COUNT;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-04T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void initialReportIsNotScanned() {
        ReactiveSecurityScanner scanner = ReactiveSecurityScanner.using(this::minimalObservation, CLOCK);

        SecurityReport report = scanner.initialReport();

        assertThat(report.scan().status()).isEqualTo("NOT_SCANNED");
        assertThat(report.results()).isEmpty();
        assertThat(report.localOnly()).isTrue();
    }

    @Test
    void scanWithNoChainsReturnsDisabledWithStableEmptyResponse() {
        ReactiveSecurityScanner scanner = ReactiveSecurityScanner.using(ReactiveSecurityObservation::empty, CLOCK);

        SecurityReport report = scanner.scan();

        assertThat(report.scan().status()).isEqualTo("DISABLED");
        assertThat(report.filterChainsAnalyzed()).isZero();
        assertThat(report.rulesEvaluated()).isZero();
        assertThat(report.violationsFound()).isZero();
        assertThat(report.results()).isEmpty();
        assertThat(report.filterChains()).isEmpty();
    }

    @Test
    void scanWithNullSupplierResultReturnsDisabled() {
        ReactiveSecurityScanner scanner = ReactiveSecurityScanner.using(() -> null, CLOCK);

        SecurityReport report = scanner.scan();

        assertThat(report.scan().status()).isEqualTo("DISABLED");
        assertThat(report.results()).isEmpty();
    }

    @Test
    void scanSwallowsSupplierExceptionAndReturnsDisabled() {
        ReactiveSecurityScanner scanner = ReactiveSecurityScanner.using(
                () -> {
                    throw new IllegalStateException("boom");
                },
                CLOCK);

        SecurityReport report = scanner.scan();

        assertThat(report.scan().status()).isEqualTo("DISABLED");
        assertThat(report.scan().message()).contains("IllegalStateException");
    }

    @Test
    void scanReportsRuleFindingsAcrossCategories() {
        WebFilterChainObservation chain = new WebFilterChainObservation(
                0,
                "any request",
                List.of(
                        "SecurityContextServerWebExchangeWebFilter",
                        "HttpHeaderWriterWebFilter",
                        "AuthorizationWebFilter"),
                Boolean.FALSE,
                List.of(
                        "StrictTransportSecurityServerHttpHeadersWriter",
                        "XFrameOptionsServerHttpHeadersWriter",
                        "XXssProtectionServerHttpHeadersWriter",
                        "ContentTypeOptionsServerHttpHeadersWriter"),
                31536000L,
                Boolean.TRUE,
                null,
                null);
        ReactiveSecurityEnvironmentSnapshot environment = new ReactiveSecurityEnvironmentSnapshot(
                false, "*", null, false, List.of(), false, false, false, false, null, Set.of());
        ReactiveSecurityObservation observation = new ReactiveSecurityObservation(
                List.of(chain),
                List.of(new CorsConfigObservation("/**", List.of(), List.of("*"), List.of(), List.of(), Boolean.TRUE)),
                true,
                List.of(),
                List.of(),
                List.of(),
                environment,
                List.of());
        ReactiveSecurityScanner scanner = ReactiveSecurityScanner.using(() -> observation, CLOCK);

        SecurityReport report = scanner.scan();

        assertThat(report.scan().status()).isEqualTo("SCANNED");
        assertThat(report.filterChainsAnalyzed()).isEqualTo(1);
        assertThat(report.rulesEvaluated()).isEqualTo(RULE_COUNT);
        assertThat(report.violationsFound()).isPositive();
        assertThat(report.results())
                .extracting(SecurityRuleResultDto::id)
                .contains("SEC-RXF-CORS-001", "SEC-RXF-CORS-002", "SEC-RXF-ACT-001");
        // Severity histogram always lists all five severities
        assertThat(report.severityCounts())
                .extracting("severity")
                .containsExactly("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO");
    }

    @Test
    void partialObservationErrorsSurfaceAsPartialStatus() {
        WebFilterChainObservation chain = new WebFilterChainObservation(
                0, "any request", List.of("AuthorizationWebFilter"), Boolean.FALSE, List.of(), null, null, null, null);
        ReactiveSecurityObservation observation = new ReactiveSecurityObservation(
                List.of(chain),
                List.of(),
                false,
                List.of(),
                List.of(),
                List.of(),
                ReactiveSecurityEnvironmentSnapshot.empty(),
                List.of("Could not read CORS beans."));
        ReactiveSecurityScanner scanner = ReactiveSecurityScanner.using(() -> observation, CLOCK);

        SecurityReport report = scanner.scan();

        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.scan().message()).contains("Could not read CORS beans.");
    }

    @Test
    void applyDismissalsMarksAndFiltersResults() {
        WebFilterChainObservation chain = new WebFilterChainObservation(
                0, "any request", List.of(), Boolean.TRUE, List.of(), null, null, null, null);
        ReactiveSecurityObservation observation = new ReactiveSecurityObservation(
                List.of(chain),
                List.of(),
                false,
                List.of(),
                List.of(),
                List.of(),
                ReactiveSecurityEnvironmentSnapshot.empty(),
                List.of());
        ReactiveSecurityScanner scanner = ReactiveSecurityScanner.using(() -> observation, CLOCK);

        SecurityReport report = scanner.scan();
        assertThat(report.violationsFound()).isPositive();

        String firstViolationId = report.results().get(0).id();
        SecurityReport withDismissal = scanner.applyDismissals(report, Set.of(firstViolationId));

        assertThat(withDismissal.violationsFound()).isEqualTo(report.violationsFound() - 1);
        assertThat(withDismissal.results().stream()
                        .filter(r -> r.id().equals(firstViolationId))
                        .findFirst())
                .isPresent()
                .get()
                .extracting(SecurityRuleResultDto::dismissed)
                .isEqualTo(Boolean.TRUE);
    }

    @Test
    void scanChainWithAuthorizationWebFilterPassesAuthzRule() {
        WebFilterChainObservation chain = new WebFilterChainObservation(
                0,
                "any request",
                List.of("SecurityContextServerWebExchangeWebFilter", "AuthorizationWebFilter"),
                Boolean.FALSE,
                List.of(
                        "StrictTransportSecurityServerHttpHeadersWriter",
                        "XFrameOptionsServerHttpHeadersWriter",
                        "XXssProtectionServerHttpHeadersWriter",
                        "ContentTypeOptionsServerHttpHeadersWriter"),
                31536000L,
                Boolean.TRUE,
                null,
                null);
        SecurityReport report = scan(chain, List.of(), false);

        // SEC-RXF-AUTHZ-001 should not fire: AuthorizationWebFilter is present
        assertThat(report.results()).extracting(SecurityRuleResultDto::id).doesNotContain("SEC-RXF-AUTHZ-001");
    }

    @Test
    void unknownWebFiltersDoNotCreateMissingAuthorizationOrCsrfFindings() {
        WebFilterChainObservation chain =
                new WebFilterChainObservation(0, "any request", List.of(), null, List.of(), null, null, null, null);

        SecurityReport report = scan(chain, List.of(), false);

        assertThat(report.results())
                .extracting(SecurityRuleResultDto::id)
                .doesNotContain("SEC-RXF-AUTHZ-001", "SEC-RXF-AUTHZ-002", "SEC-RXF-AUTHZ-003", "SEC-RXF-CSRF-002");
    }

    @Test
    void unknownWebFiltersProduceExplicitSkippedRuleResults() {
        WebFilterChainObservation chain =
                new WebFilterChainObservation(0, "any request", List.of(), null, List.of(), null, null, null, null);
        ReactiveSecurityObservation observation = new ReactiveSecurityObservation(
                List.of(chain),
                List.of(),
                false,
                List.of(),
                List.of(),
                List.of(),
                ReactiveSecurityEnvironmentSnapshot.empty(),
                List.of());
        ReactiveSecurityContext context = ReactiveSecurityContext.from(observation);

        assertThat(new ReactiveAuthorizationFilterRule().evaluate(context).status())
                .isEqualTo(ReactiveSecuritySupport.SKIPPED);
        assertThat(new ReactiveCsrfGloballyDisabledRule().evaluate(context).status())
                .isEqualTo(ReactiveSecuritySupport.SKIPPED);
    }

    @Test
    void unknownHeaderWritersProduceExplicitSkippedRuleResults() {
        WebFilterChainObservation chain = new WebFilterChainObservation(
                0,
                "any request",
                List.of("AuthorizationWebFilter", "HttpHeaderWriterWebFilter"),
                Boolean.FALSE,
                false,
                List.of(),
                null,
                null,
                null,
                null,
                false);
        ReactiveSecurityObservation observation = new ReactiveSecurityObservation(
                List.of(chain),
                List.of(),
                false,
                List.of(),
                List.of(),
                List.of(),
                ReactiveSecurityEnvironmentSnapshot.empty(),
                List.of("Chain 0: header writers could not be collected"));
        ReactiveSecurityContext context = ReactiveSecurityContext.from(observation);

        assertThat(new ReactiveFrameOptionsRule().evaluate(context).status())
                .isEqualTo(ReactiveSecuritySupport.SKIPPED);
        assertThat(new ReactiveContentSecurityPolicyRule().evaluate(context).status())
                .isEqualTo(ReactiveSecuritySupport.SKIPPED);
    }

    @Test
    void catchAllAuthenticationWithoutAuthorizationTriggersDedicatedReview() {
        WebFilterChainObservation chain = new WebFilterChainObservation(
                0, "any request", List.of("AuthenticationWebFilter"), Boolean.TRUE, List.of(), null, null, null, null);

        SecurityReport report = scan(chain, List.of(), false);

        assertThat(report.results()).extracting(SecurityRuleResultDto::id).contains("SEC-RXF-AUTHZ-002");
    }

    @Test
    void authorizationFilterDoesNotClaimPermitAllEvenWhenAuthenticationIsPresent() {
        WebFilterChainObservation chain = new WebFilterChainObservation(
                0,
                "any request",
                List.of("AuthenticationWebFilter", "AuthorizationWebFilter"),
                Boolean.FALSE,
                List.of(),
                null,
                null,
                null,
                null);

        SecurityReport report = scan(chain, List.of(), false);

        assertThat(report.results()).extracting(SecurityRuleResultDto::id).doesNotContain("SEC-RXF-AUTHZ-002");
    }

    @Test
    void scanDetectsCsrfWebFilterAbsenceForOidcSessionRegistryLogin() {
        WebFilterChainObservation chain = new WebFilterChainObservation(
                0,
                "any request",
                List.of(
                        "SecurityContextServerWebExchangeWebFilter",
                        "AuthorizationWebFilter",
                        "OidcSessionRegistryAuthenticationWebFilter"),
                Boolean.FALSE,
                List.of("StrictTransportSecurityServerHttpHeadersWriter"),
                31536000L,
                Boolean.TRUE,
                null,
                null);
        SecurityReport report = scan(chain, List.of(), false);

        // Observed OIDC login without CsrfWebFilter should trigger SEC-RXF-CSRF-001.
        assertThat(report.results()).extracting(SecurityRuleResultDto::id).contains("SEC-RXF-CSRF-001");
    }

    @Test
    void scanDetectsCsrfWebFilterAbsenceForFormLoginChain() {
        WebFilterChainObservation chain = new WebFilterChainObservation(
                0,
                "any request",
                List.of(
                        "SecurityContextServerWebExchangeWebFilter",
                        "AuthorizationWebFilter",
                        "AuthenticationWebFilter"),
                Boolean.FALSE,
                false,
                List.of("StrictTransportSecurityServerHttpHeadersWriter"),
                31536000L,
                Boolean.TRUE,
                null,
                null,
                true,
                true);
        SecurityReport report = scan(chain, List.of(), false);

        // Observed formLogin() converter without CsrfWebFilter should trigger SEC-RXF-CSRF-001, same
        // as an OAuth2/OIDC login filter.
        assertThat(report.results()).extracting(SecurityRuleResultDto::id).contains("SEC-RXF-CSRF-001");
    }

    @Test
    void formLoginChainWithCsrfWebFilterDoesNotTriggerCsrfRule() {
        WebFilterChainObservation chain = new WebFilterChainObservation(
                0,
                "any request",
                List.of(
                        "SecurityContextServerWebExchangeWebFilter",
                        "AuthorizationWebFilter",
                        "AuthenticationWebFilter",
                        "CsrfWebFilter"),
                Boolean.FALSE,
                false,
                List.of("StrictTransportSecurityServerHttpHeadersWriter"),
                31536000L,
                Boolean.TRUE,
                null,
                null,
                true,
                true);
        SecurityReport report = scan(chain, List.of(), false);

        assertThat(report.results()).extracting(SecurityRuleResultDto::id).doesNotContain("SEC-RXF-CSRF-001");
    }

    @Test
    void plainAuthenticationWebFilterWithoutFormLoginConverterDoesNotTriggerLoginRules() {
        // A generic AuthenticationWebFilter (e.g. HTTP Basic) whose converter is not
        // ServerFormLoginAuthenticationConverter must not be mistaken for a formLogin() chain.
        WebFilterChainObservation chain = new WebFilterChainObservation(
                0,
                "any request",
                List.of(
                        "SecurityContextServerWebExchangeWebFilter",
                        "AuthorizationWebFilter",
                        "AuthenticationWebFilter"),
                Boolean.FALSE,
                false,
                List.of("StrictTransportSecurityServerHttpHeadersWriter"),
                31536000L,
                Boolean.TRUE,
                null,
                null,
                true,
                false);
        SecurityReport report = scan(chain, List.of(), false);

        assertThat(report.results())
                .extracting(SecurityRuleResultDto::id)
                .doesNotContain("SEC-RXF-CSRF-001", "SEC-RXF-SESSION-001");
    }

    @Test
    void scanDetectsWildcardCorsOrigin() {
        WebFilterChainObservation chain = new WebFilterChainObservation(
                0,
                "any request",
                List.of("AuthorizationWebFilter", "CorsWebFilter"),
                Boolean.FALSE,
                List.of("StrictTransportSecurityServerHttpHeadersWriter"),
                31536000L,
                Boolean.TRUE,
                null,
                null);
        SecurityReport report = scan(
                chain,
                List.of(new CorsConfigObservation("/**", List.of("*"), List.of(), List.of(), List.of(), null)),
                true);

        assertThat(report.results()).extracting(SecurityRuleResultDto::id).contains("SEC-RXF-CORS-001");
    }

    @Test
    void literalWildcardOriginWithCredentialsDoesNotTriggerPatternRule() {
        WebFilterChainObservation chain = new WebFilterChainObservation(
                0,
                "any request",
                List.of("AuthorizationWebFilter", "CorsWebFilter"),
                Boolean.FALSE,
                List.of("StrictTransportSecurityServerHttpHeadersWriter"),
                31536000L,
                Boolean.TRUE,
                null,
                null);
        SecurityReport report = scan(
                chain,
                List.of(new CorsConfigObservation("/**", List.of("*"), List.of(), List.of(), List.of(), Boolean.TRUE)),
                true);

        assertThat(report.results())
                .extracting(SecurityRuleResultDto::id)
                .contains("SEC-RXF-CORS-001")
                .doesNotContain("SEC-RXF-CORS-002");
    }

    @Test
    void credentialedWildcardOriginPatternTriggersHighSeverityRule() {
        WebFilterChainObservation chain = new WebFilterChainObservation(
                0,
                "any request",
                List.of("AuthorizationWebFilter", "CorsWebFilter"),
                Boolean.FALSE,
                List.of("StrictTransportSecurityServerHttpHeadersWriter"),
                31536000L,
                Boolean.TRUE,
                null,
                null);
        SecurityReport report = scan(
                chain,
                List.of(new CorsConfigObservation("/**", List.of(), List.of("*"), List.of(), List.of(), Boolean.TRUE)),
                true);

        assertThat(report.results())
                .filteredOn(result -> result.id().equals("SEC-RXF-CORS-002"))
                .singleElement()
                .extracting(SecurityRuleResultDto::severity)
                .isEqualTo("HIGH");
    }

    @Test
    void broadWildcardSchemeOriginPatternTriggersMediumSeverityRule() {
        WebFilterChainObservation chain = new WebFilterChainObservation(
                0,
                "any request",
                List.of("AuthorizationWebFilter", "CorsWebFilter"),
                Boolean.FALSE,
                List.of("StrictTransportSecurityServerHttpHeadersWriter"),
                31536000L,
                Boolean.TRUE,
                null,
                null);
        SecurityReport report = scan(
                chain,
                List.of(new CorsConfigObservation(
                        "/**", List.of(), List.of("https://*"), List.of(), List.of(), Boolean.FALSE)),
                true);

        assertThat(report.results())
                .filteredOn(result -> result.id().equals("SEC-RXF-CORS-003"))
                .singleElement()
                .extracting(SecurityRuleResultDto::severity)
                .isEqualTo("MEDIUM");
    }

    @Test
    void broadTopLevelDomainSuffixOriginPatternTriggersMediumSeverityRule() {
        WebFilterChainObservation chain = new WebFilterChainObservation(
                0,
                "any request",
                List.of("AuthorizationWebFilter", "CorsWebFilter"),
                Boolean.FALSE,
                List.of("StrictTransportSecurityServerHttpHeadersWriter"),
                31536000L,
                Boolean.TRUE,
                null,
                null);
        SecurityReport report = scan(
                chain,
                List.of(new CorsConfigObservation(
                        "/**", List.of(), List.of("https://*.com"), List.of(), List.of(), Boolean.FALSE)),
                true);

        assertThat(report.results())
                .filteredOn(result -> result.id().equals("SEC-RXF-CORS-003"))
                .singleElement()
                .extracting(SecurityRuleResultDto::severity)
                .isEqualTo("MEDIUM");
    }

    @Test
    void credentialedBroadOriginPatternTriggersHighSeverityBroadPatternRule() {
        WebFilterChainObservation chain = new WebFilterChainObservation(
                0,
                "any request",
                List.of("AuthorizationWebFilter", "CorsWebFilter"),
                Boolean.FALSE,
                List.of("StrictTransportSecurityServerHttpHeadersWriter"),
                31536000L,
                Boolean.TRUE,
                null,
                null);
        SecurityReport report = scan(
                chain,
                List.of(new CorsConfigObservation(
                        "/**", List.of(), List.of("*://*"), List.of(), List.of(), Boolean.TRUE)),
                true);

        assertThat(report.results())
                .filteredOn(result -> result.id().equals("SEC-RXF-CORS-003"))
                .singleElement()
                .extracting(SecurityRuleResultDto::severity)
                .isEqualTo("HIGH");
    }

    @Test
    void scopedSubdomainWildcardOriginPatternDoesNotTriggerBroadPatternRule() {
        WebFilterChainObservation chain = new WebFilterChainObservation(
                0,
                "any request",
                List.of("AuthorizationWebFilter", "CorsWebFilter"),
                Boolean.FALSE,
                List.of("StrictTransportSecurityServerHttpHeadersWriter"),
                31536000L,
                Boolean.TRUE,
                null,
                null);
        SecurityReport report = scan(
                chain,
                List.of(new CorsConfigObservation(
                        "/**", List.of(), List.of("https://*.example.com"), List.of(), List.of(), Boolean.TRUE)),
                true);

        assertThat(report.results()).extracting(SecurityRuleResultDto::id).doesNotContain("SEC-RXF-CORS-003");
    }

    @Test
    void exactWildcardOriginPatternDoesNotDuplicateBroadPatternRule() {
        WebFilterChainObservation chain = new WebFilterChainObservation(
                0,
                "any request",
                List.of("AuthorizationWebFilter", "CorsWebFilter"),
                Boolean.FALSE,
                List.of("StrictTransportSecurityServerHttpHeadersWriter"),
                31536000L,
                Boolean.TRUE,
                null,
                null);
        SecurityReport report = scan(
                chain,
                List.of(new CorsConfigObservation("/**", List.of(), List.of("*"), List.of(), List.of(), Boolean.TRUE)),
                true);

        // The exact "*" pattern is already covered by SEC-RXF-CORS-001/002; the broad-pattern rule
        // must not fire a duplicate finding for it.
        assertThat(report.results()).extracting(SecurityRuleResultDto::id).doesNotContain("SEC-RXF-CORS-003");
    }

    @Test
    void safeExplicitOriginDoesNotTriggerBroadPatternRule() {
        WebFilterChainObservation chain = new WebFilterChainObservation(
                0,
                "any request",
                List.of("AuthorizationWebFilter", "CorsWebFilter"),
                Boolean.FALSE,
                List.of("StrictTransportSecurityServerHttpHeadersWriter"),
                31536000L,
                Boolean.TRUE,
                null,
                null);
        SecurityReport report = scan(
                chain,
                List.of(new CorsConfigObservation(
                        "/**", List.of(), List.of("https://app.example.com"), List.of(), List.of(), Boolean.TRUE)),
                true);

        assertThat(report.results()).extracting(SecurityRuleResultDto::id).doesNotContain("SEC-RXF-CORS-003");
    }

    @Test
    void partialCorsInspectionSkipsSafeKnownEntriesButKeepsKnownViolations() {
        WebFilterChainObservation chain = new WebFilterChainObservation(
                0, "any request", List.of("AuthorizationWebFilter"), Boolean.FALSE, List.of(), null, null, null, null);
        ReactiveSecurityObservation incompleteSafeObservation = new ReactiveSecurityObservation(
                List.of(chain),
                List.of(new CorsConfigObservation(
                        "/**", List.of("https://app.example"), List.of(), List.of(), List.of(), Boolean.TRUE)),
                true,
                List.of(),
                List.of(),
                List.of(),
                ReactiveSecurityEnvironmentSnapshot.empty(),
                List.of("CORS source unavailable"),
                false);
        ReactiveSecurityObservation incompleteViolationObservation = new ReactiveSecurityObservation(
                List.of(chain),
                List.of(new CorsConfigObservation("/**", List.of("*"), List.of(), List.of(), List.of(), null)),
                true,
                List.of(),
                List.of(),
                List.of(),
                ReactiveSecurityEnvironmentSnapshot.empty(),
                List.of("CORS source unavailable"),
                false);

        assertThat(new ReactiveCorsWildcardOriginRule()
                        .evaluate(ReactiveSecurityContext.from(incompleteSafeObservation))
                        .status())
                .isEqualTo(ReactiveSecuritySupport.SKIPPED);
        assertThat(new ReactiveCorsWildcardOriginRule()
                        .evaluate(ReactiveSecurityContext.from(incompleteViolationObservation))
                        .status())
                .isEqualTo(ReactiveSecuritySupport.VIOLATION);
    }

    @Test
    void scanDetectsMissingHstsHeader() {
        WebFilterChainObservation chain = new WebFilterChainObservation(
                0,
                "any request",
                List.of("AuthorizationWebFilter", "HttpHeaderWriterWebFilter"),
                Boolean.FALSE,
                List.of("XFrameOptionsServerHttpHeadersWriter"),
                null,
                null,
                null,
                null);
        // SEC-RXF-HEAD-001 only fires while TLS is configured (globally, or via an
        // HttpsRedirectWebFilter in a chain) - mirrors the rule's own isTlsConfigured() gate.
        ReactiveSecurityEnvironmentSnapshot environment = new ReactiveSecurityEnvironmentSnapshot(
                true, null, null, false, List.of(), false, false, false, false, null, Set.of());
        ReactiveSecurityObservation observation = new ReactiveSecurityObservation(
                List.of(chain), List.of(), false, List.of(), List.of(), List.of(), environment, List.of());
        SecurityReport report =
                ReactiveSecurityScanner.using(() -> observation, CLOCK).scan();

        assertThat(report.results()).extracting(SecurityRuleResultDto::id).contains("SEC-RXF-HEAD-001");
    }

    @Test
    void hstsOneYearBoundaryMatchesSpringSecurityDefault() {
        WebFilterChainObservation belowDefault = new WebFilterChainObservation(
                0,
                "any request",
                List.of("AuthorizationWebFilter", "HttpHeaderWriterWebFilter"),
                Boolean.FALSE,
                List.of("StrictTransportSecurityServerHttpHeadersWriter"),
                31535999L,
                Boolean.TRUE,
                null,
                null);
        WebFilterChainObservation atDefault = new WebFilterChainObservation(
                0,
                "any request",
                List.of("AuthorizationWebFilter", "HttpHeaderWriterWebFilter"),
                Boolean.FALSE,
                List.of("StrictTransportSecurityServerHttpHeadersWriter"),
                31536000L,
                Boolean.TRUE,
                null,
                null);

        assertThat(scan(belowDefault, List.of(), false).results())
                .extracting(SecurityRuleResultDto::id)
                .contains("SEC-RXF-HEAD-006");
        assertThat(scan(atDefault, List.of(), false).results())
                .extracting(SecurityRuleResultDto::id)
                .doesNotContain("SEC-RXF-HEAD-006");
    }

    @Test
    void enforcingCspFrameAncestorsReplacesFrameOptionsButReportOnlyDoesNot() {
        WebFilterChainObservation enforcingPolicy = new WebFilterChainObservation(
                0,
                "any request",
                List.of("AuthorizationWebFilter", "HttpHeaderWriterWebFilter"),
                Boolean.FALSE,
                List.of("ContentTypeOptionsServerHttpHeadersWriter"),
                null,
                null,
                "default-src 'self'; frame-ancestors 'none'",
                Boolean.FALSE);
        WebFilterChainObservation reportOnlyPolicy = new WebFilterChainObservation(
                0,
                "any request",
                List.of("AuthorizationWebFilter", "HttpHeaderWriterWebFilter"),
                Boolean.FALSE,
                List.of("ContentTypeOptionsServerHttpHeadersWriter"),
                null,
                null,
                "default-src 'self'; frame-ancestors 'none'",
                Boolean.TRUE);
        WebFilterChainObservation directiveNameOnlyInUrl = new WebFilterChainObservation(
                0,
                "any request",
                List.of("AuthorizationWebFilter", "HttpHeaderWriterWebFilter"),
                Boolean.FALSE,
                List.of("ContentTypeOptionsServerHttpHeadersWriter"),
                null,
                null,
                "default-src 'self'; report-uri https://frame-ancestors.example",
                Boolean.FALSE);
        WebFilterChainObservation unrestrictedPolicy = new WebFilterChainObservation(
                0,
                "any request",
                List.of("AuthorizationWebFilter", "HttpHeaderWriterWebFilter"),
                Boolean.FALSE,
                List.of("ContentTypeOptionsServerHttpHeadersWriter"),
                null,
                null,
                "default-src 'self'; frame-ancestors *",
                Boolean.FALSE);

        assertThat(scan(enforcingPolicy, List.of(), false).results())
                .extracting(SecurityRuleResultDto::id)
                .doesNotContain("SEC-RXF-HEAD-002");
        assertThat(scan(reportOnlyPolicy, List.of(), false).results())
                .extracting(SecurityRuleResultDto::id)
                .contains("SEC-RXF-HEAD-002");
        assertThat(scan(directiveNameOnlyInUrl, List.of(), false).results())
                .extracting(SecurityRuleResultDto::id)
                .contains("SEC-RXF-HEAD-002");
        assertThat(scan(unrestrictedPolicy, List.of(), false).results())
                .extracting(SecurityRuleResultDto::id)
                .contains("SEC-RXF-HEAD-002");
    }

    @Test
    void scanDetectsUnconfiguredCspWriter() {
        WebFilterChainObservation chain = new WebFilterChainObservation(
                0,
                "any request",
                List.of("AuthorizationWebFilter", "HttpHeaderWriterWebFilter"),
                Boolean.FALSE,
                List.of("ContentSecurityPolicyServerHttpHeadersWriter"),
                null,
                null,
                null,
                Boolean.FALSE);

        SecurityReport report = scan(chain, List.of(), false);

        assertThat(report.results())
                .filteredOn(result -> result.id().equals("SEC-RXF-HEAD-004"))
                .singleElement()
                .extracting(SecurityRuleResultDto::severity)
                .isEqualTo("LOW");
    }

    @Test
    void scanDetectsReportOnlyCspButAcceptsEnforcingCsp() {
        WebFilterChainObservation reportOnlyChain = new WebFilterChainObservation(
                0,
                "any request",
                List.of("AuthorizationWebFilter", "HttpHeaderWriterWebFilter"),
                Boolean.FALSE,
                List.of("ContentSecurityPolicyServerHttpHeadersWriter"),
                null,
                null,
                "default-src 'self'",
                Boolean.TRUE);
        WebFilterChainObservation enforcingChain = new WebFilterChainObservation(
                0,
                "any request",
                List.of("AuthorizationWebFilter", "HttpHeaderWriterWebFilter"),
                Boolean.FALSE,
                List.of("ContentSecurityPolicyServerHttpHeadersWriter"),
                null,
                null,
                "default-src 'self'",
                Boolean.FALSE);

        assertThat(scan(reportOnlyChain, List.of(), false).results())
                .extracting(SecurityRuleResultDto::id)
                .contains("SEC-RXF-HEAD-004");
        assertThat(scan(enforcingChain, List.of(), false).results())
                .extracting(SecurityRuleResultDto::id)
                .doesNotContain("SEC-RXF-HEAD-004");
    }

    @Test
    void scanDetectsActuatorWildcardExposure() {
        WebFilterChainObservation chain = new WebFilterChainObservation(
                0,
                "any request",
                List.of("AuthorizationWebFilter"),
                Boolean.FALSE,
                List.of("StrictTransportSecurityServerHttpHeadersWriter"),
                31536000L,
                Boolean.TRUE,
                null,
                null);
        ReactiveSecurityEnvironmentSnapshot environment = new ReactiveSecurityEnvironmentSnapshot(
                false, "*", null, false, List.of(), false, false, false, false, null, Set.of());
        ReactiveSecurityObservation observation = new ReactiveSecurityObservation(
                List.of(chain), List.of(), false, List.of(), List.of(), List.of(), environment, List.of());
        ReactiveSecurityScanner scanner = ReactiveSecurityScanner.using(() -> observation, CLOCK);

        SecurityReport report = scanner.scan();

        assertThat(report.results()).extracting(SecurityRuleResultDto::id).contains("SEC-RXF-ACT-001");
    }

    @Test
    void actuatorShowValuesAlwaysRequiresObservedWebExposure() {
        WebFilterChainObservation chain = new WebFilterChainObservation(
                0, "any request", List.of("AuthorizationWebFilter"), Boolean.FALSE, List.of(), null, null, null, null);
        ReactiveSecurityEnvironmentSnapshot exposed = new ReactiveSecurityEnvironmentSnapshot(
                false,
                "configprops",
                null,
                false,
                List.of(),
                false,
                false,
                false,
                false,
                null,
                Set.of(),
                false,
                false,
                true,
                false,
                true);
        ReactiveSecurityEnvironmentSnapshot notExposed = new ReactiveSecurityEnvironmentSnapshot(
                false, null, null, false, List.of(), false, false, false, false, null, Set.of(), false, false, true);

        SecurityReport report = scan(chain, exposed);

        SecurityRuleResultDto result = report.results().stream()
                .filter(candidate -> candidate.id().equals("SEC-RXF-ACT-005"))
                .findFirst()
                .orElseThrow();
        assertThat(result.severity()).isEqualTo("HIGH");
        assertThat(result.sampleViolations()).singleElement().asString().contains("configprops.show-values");
        assertThat(scan(chain, notExposed).results())
                .extracting(SecurityRuleResultDto::id)
                .doesNotContain("SEC-RXF-ACT-005");
    }

    @Test
    void hardcodedSecretRuleReportsKeysOnlyNeverValues() {
        WebFilterChainObservation chain = new WebFilterChainObservation(
                0, "any request", List.of("AuthorizationWebFilter"), Boolean.FALSE, List.of(), null, null, null, null);
        ReactiveSecurityEnvironmentSnapshot environment = new ReactiveSecurityEnvironmentSnapshot(
                false, null, null, false, List.of(), false, false, false, false, null, Set.of("my.api.secret-key"));
        ReactiveSecurityObservation observation = new ReactiveSecurityObservation(
                List.of(chain), List.of(), false, List.of(), List.of(), List.of(), environment, List.of());
        ReactiveSecurityScanner scanner = ReactiveSecurityScanner.using(() -> observation, CLOCK);

        SecurityReport report = scanner.scan();

        SecurityRuleResultDto result = report.results().stream()
                .filter(r -> r.id().equals("SEC-RXF-CONFIG-003"))
                .findFirst()
                .orElseThrow();
        assertThat(result.status()).isEqualTo(ReactiveSecuritySupport.VIOLATION);
        assertThat(result.sampleViolations())
                .anySatisfy(detail -> assertThat(detail).contains("my.api.secret-key"));
        // The observation carries suspected-secret property keys only (Set<String>) - there is no
        // value field anywhere in ReactiveSecurityEnvironmentSnapshot for this rule to echo.
        assertThat(result.sampleViolations())
                .allSatisfy(detail -> assertThat(detail).contains("value not shown"));
    }

    @Test
    void jwtStaticPublicKeyRuleReportsOnlyThePropertyName() {
        WebFilterChainObservation chain = new WebFilterChainObservation(
                0, "any request", List.of("AuthorizationWebFilter"), Boolean.FALSE, List.of(), null, null, null, null);
        ReactiveSecurityEnvironmentSnapshot environment = new ReactiveSecurityEnvironmentSnapshot(
                false, null, null, false, List.of(), false, true, false, false, null, Set.of());
        ReactiveSecurityObservation observation = new ReactiveSecurityObservation(
                List.of(chain), List.of(), false, List.of(), List.of(), List.of(), environment, List.of());
        ReactiveSecurityScanner scanner = ReactiveSecurityScanner.using(() -> observation, CLOCK);

        SecurityReport report = scanner.scan();

        SecurityRuleResultDto result = report.results().stream()
                .filter(r -> r.id().equals("SEC-RXF-OAUTH2-002"))
                .findFirst()
                .orElseThrow();
        assertThat(result.status()).isEqualTo(ReactiveSecuritySupport.VIOLATION);
        assertThat(result.severity()).isEqualTo("LOW");
        assertThat(result.sampleViolations())
                .containsExactly(
                        "spring.security.oauth2.resourceserver.jwt.public-key-location configures a static verification key; prefer issuer-uri or jwk-set-uri for key rotation.");
    }

    @Test
    void scanDetectsMixedBearerTokenAndOauth2LoginFilters() {
        WebFilterChainObservation chain = new WebFilterChainObservation(
                0,
                "any request",
                List.of("AuthorizationWebFilter", "AuthenticationWebFilter", "OAuth2LoginAuthenticationWebFilter"),
                Boolean.FALSE,
                true,
                List.of(),
                null,
                null,
                null,
                null);

        SecurityReport report = scan(chain, List.of(), false);

        assertThat(report.results()).extracting(SecurityRuleResultDto::id).contains("SEC-RXF-SESSION-001");
    }

    @Test
    void scanDetectsMixedBearerTokenAndFormLoginFilters() {
        WebFilterChainObservation chain = new WebFilterChainObservation(
                0,
                "any request",
                List.of("AuthorizationWebFilter", "AuthenticationWebFilter"),
                Boolean.FALSE,
                true,
                List.of(),
                null,
                null,
                null,
                null,
                true,
                true);

        SecurityReport report = scan(chain, List.of(), false);

        // A chain that mixes a bearer-token converter with a formLogin() converter should trigger
        // SEC-RXF-SESSION-001, the same as mixing bearer-token with an OAuth2/OIDC login filter.
        assertThat(report.results()).extracting(SecurityRuleResultDto::id).contains("SEC-RXF-SESSION-001");
    }

    @Test
    void bearerTokenAloneWithoutFormLoginDoesNotTriggerMixedSessionRule() {
        WebFilterChainObservation chain = new WebFilterChainObservation(
                0,
                "any request",
                List.of("AuthorizationWebFilter", "AuthenticationWebFilter"),
                Boolean.FALSE,
                true,
                List.of(),
                null,
                null,
                null,
                null,
                true,
                false);

        SecurityReport report = scan(chain, List.of(), false);

        assertThat(report.results()).extracting(SecurityRuleResultDto::id).doesNotContain("SEC-RXF-SESSION-001");
    }

    @Test
    void plainHttpOpaqueTokenIntrospectionIsHighSeverityOnlyInProduction() {
        WebFilterChainObservation chain = new WebFilterChainObservation(
                0, "any request", List.of("AuthorizationWebFilter"), Boolean.FALSE, List.of(), null, null, null, null);
        ReactiveSecurityEnvironmentSnapshot production = new ReactiveSecurityEnvironmentSnapshot(
                false,
                null,
                null,
                false,
                List.of("prod"),
                false,
                false,
                false,
                false,
                null,
                Set.of(),
                true,
                false,
                false);
        ReactiveSecurityEnvironmentSnapshot development = new ReactiveSecurityEnvironmentSnapshot(
                false,
                null,
                null,
                false,
                List.of("dev"),
                false,
                false,
                false,
                false,
                null,
                Set.of(),
                true,
                false,
                false);

        SecurityReport productionReport = scan(chain, production);
        SecurityReport developmentReport = scan(chain, development);

        assertThat(productionReport.results())
                .filteredOn(result -> result.id().equals("SEC-RXF-OAUTH2-004"))
                .singleElement()
                .extracting(SecurityRuleResultDto::severity)
                .isEqualTo("HIGH");
        assertThat(developmentReport.results())
                .extracting(SecurityRuleResultDto::id)
                .doesNotContain("SEC-RXF-OAUTH2-004");
    }

    @Test
    void traceSecurityLoggingTriggersProductionRuleAndRemovedRulesStayAbsent() {
        WebFilterChainObservation chain = new WebFilterChainObservation(
                0, "any request", List.of("AuthorizationWebFilter"), Boolean.FALSE, List.of(), null, null, null, null);
        ReactiveSecurityEnvironmentSnapshot environment = new ReactiveSecurityEnvironmentSnapshot(
                false, null, null, false, List.of("production"), true, false, false, false, "TRACE", Set.of());

        SecurityReport report = scan(chain, environment);

        assertThat(report.results())
                .extracting(SecurityRuleResultDto::id)
                .contains("SEC-RXF-CONFIG-004")
                .doesNotContain("SEC-RXF-CONFIG-001", "SEC-RXF-OAUTH2-001");
    }

    @Test
    void ruleCountMatchesRegistry() {
        assertThat(ReactiveSecurityRuleRegistry.activeRules()).hasSize(RULE_COUNT);
        assertThat(RULE_COUNT).isEqualTo(26);
    }

    @Test
    void allRuleIdsStartWithSecRxf() {
        assertThat(ReactiveSecurityRuleRegistry.activeRules())
                .extracting(r -> r.definition().id())
                .allMatch(id -> id.startsWith("SEC-RXF-"));
    }

    @Test
    void allRuleIdsAreUniqueAndOrdered() {
        List<String> ids = ReactiveSecurityRuleRegistry.activeRules().stream()
                .map(r -> r.definition().id())
                .toList();
        assertThat(ids).doesNotHaveDuplicates();
        assertThat(ids).hasSize(26);
        assertThat(ids)
                .contains("SEC-RXF-ACT-005", "SEC-RXF-OAUTH2-004", "SEC-RXF-CORS-003")
                .doesNotContain("SEC-RXF-CONFIG-001", "SEC-RXF-OAUTH2-001");
        List<String> sorted = ids.stream().sorted().toList();
        // Registry order need not be alphabetical, but must be stable/deterministic across calls.
        List<String> idsAgain = ReactiveSecurityRuleRegistry.activeRules().stream()
                .map(r -> r.definition().id())
                .toList();
        assertThat(ids).isEqualTo(idsAgain);
        assertThat(sorted).doesNotHaveDuplicates();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private SecurityReport scan(
            WebFilterChainObservation chain, List<CorsConfigObservation> corsConfigs, boolean corsSourcePresent) {
        ReactiveSecurityObservation observation = new ReactiveSecurityObservation(
                List.of(chain),
                corsConfigs,
                corsSourcePresent,
                List.of(),
                List.of(),
                List.of(),
                ReactiveSecurityEnvironmentSnapshot.empty(),
                List.of());
        return ReactiveSecurityScanner.using(() -> observation, CLOCK).scan();
    }

    private ReactiveSecurityObservation minimalObservation() {
        WebFilterChainObservation chain = new WebFilterChainObservation(
                0,
                "any request",
                List.of("AuthorizationWebFilter"),
                Boolean.FALSE,
                List.of("StrictTransportSecurityServerHttpHeadersWriter"),
                31536000L,
                Boolean.TRUE,
                null,
                null);
        return new ReactiveSecurityObservation(
                List.of(chain),
                List.of(),
                false,
                List.of(),
                List.of(),
                List.of(),
                ReactiveSecurityEnvironmentSnapshot.empty(),
                List.of());
    }

    private SecurityReport scan(WebFilterChainObservation chain, ReactiveSecurityEnvironmentSnapshot environment) {
        ReactiveSecurityObservation observation = new ReactiveSecurityObservation(
                List.of(chain), List.of(), false, List.of(), List.of(), List.of(), environment, List.of());
        return ReactiveSecurityScanner.using(() -> observation, CLOCK).scan();
    }
}
