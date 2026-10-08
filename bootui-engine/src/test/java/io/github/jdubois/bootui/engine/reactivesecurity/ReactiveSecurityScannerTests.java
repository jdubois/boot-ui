package io.github.jdubois.bootui.engine.reactivesecurity;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.SecurityReport;
import io.github.jdubois.bootui.core.dto.SecurityRuleResultDto;
import io.github.jdubois.bootui.engine.advisor.AdvisorRuleRefusals;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for the framework-neutral reactive Spring Security advisor: {@link
 * ReactiveSecurityScanner}, {@link ReactiveSecurityRuleRegistry}, and the 25 {@code SEC-RXF-*} rules.
 * Everything here builds a plain {@link ReactiveSecurityObservation} — no Spring, no reflection, no
 * {@code MockEnvironment} — mirroring how {@code SpringReactiveSecurityObservationCollector} feeds the
 * scanner in production.
 */
class ReactiveSecurityScannerTests {

    private static final int RULE_COUNT = ReactiveSecurityRuleRegistry.RULE_COUNT;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-04T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void allCorsViolationsRemainAvailableWithoutChangingSamplesOrRefreshingObservations() {
        List<CorsConfigObservation> cors = java.util.stream.IntStream.range(0, 16)
                .mapToObj(index -> new CorsConfigObservation(
                        "/resource-" + index + "/**", List.of("*"), List.of(), List.of(), List.of(), false))
                .toList();
        var baseline = minimalObservation();
        var observation = new ReactiveSecurityObservation(
                baseline.chains(), cors, true, List.of(), List.of(), List.of(), baseline.environment(), List.of());
        var context = ReactiveSecurityContext.from(observation);
        List<String> expected = cors.stream()
                .map(config -> "CORS config for pattern '" + config.pattern() + "' uses wildcard origins. "
                        + "Confirm these resources are intended for public noncredentialed sharing.")
                .map(ReactiveSecuritySupport::detail)
                .toList();
        AtomicInteger collections = new AtomicInteger();
        ReactiveSecurityScanner scanner = ReactiveSecurityScanner.using(
                () -> {
                    collections.incrementAndGet();
                    return observation;
                },
                CLOCK);
        SecurityReport report = scanner.scan();
        String id = "SEC-RXF-CORS-001";
        String scanId = report.violationDetails().scanId();
        AdvisorRuleRefusals.assertEveryResultIsAKnownRule(
                report.results().stream().map(evaluated -> evaluated.id()).toList(),
                report.violationDetails().scanId(),
                (asked, scan) -> scanner.ruleViolations(asked, scan, 0, 1));
        var finding = new ReactiveCorsWildcardOriginRule().evaluate(context);
        assertThat(report.results()).contains(finding);
        assertThat(finding.violationCount()).isEqualTo(16);
        assertThat(finding.sampleViolations()).containsExactlyElementsOf(expected.subList(0, 10));
        assertThat(scanner.ruleViolations(id, scanId, 0, 11).violations())
                .containsExactlyElementsOf(expected.subList(0, 11));
        var last = scanner.ruleViolations(id, scanId, 11, 11);
        assertThat(last.violations()).containsExactlyElementsOf(expected.subList(11, 16));
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
        assertThat(bounded.violationDetails().total())
                .isEqualTo(report.violationDetails().total());
        assertThat(bounded.violationDetails().retained()).isEqualTo(7);
        assertThat(scanner.ruleViolations(id, bounded.violationDetails().scanId(), 0, null)
                        .truncated())
                .isTrue();
    }

    @Test
    void corsEvaluatorOwnsZeroTargetCompletedAndMissingEvidenceOutcomes() {
        ReactiveSecurityContext empty =
                new ReactiveSecurityContext(List.of(), List.of(), true, ReactiveSecurityEnvironmentSnapshot.empty());
        assertThat(new ReactiveCorsWildcardOriginRule().evaluate(empty).status())
                .isEqualTo("PASS");
        assertThat(empty.evaluation().evidence(List.of()).usable()).isFalse();

        CorsConfigObservation safe = new CorsConfigObservation(
                "/**", List.of("https://app.example"), List.of(), List.of(), List.of(), false);
        ReactiveSecurityContext known = new ReactiveSecurityContext(
                List.of(), List.of(safe), true, ReactiveSecurityEnvironmentSnapshot.empty());
        assertThat(new ReactiveCorsWildcardOriginRule().evaluate(known).status())
                .isEqualTo("PASS");
        assertThat(known.evaluation().evidence(List.of()).usable()).isTrue();

        ReactiveSecurityContext missing = new ReactiveSecurityContext(
                List.of(), List.of(safe), false, ReactiveSecurityEnvironmentSnapshot.empty());
        assertThat(new ReactiveCorsWildcardOriginRule().evaluate(missing).status())
                .isEqualTo("SKIPPED");
        assertThat(missing.evaluation().evidence(List.of()).usable()).isFalse();
        assertThat(missing.evaluation().evidence(List.of()).coverageComplete()).isFalse();

        CorsConfigObservation unsafe =
                new CorsConfigObservation("/**", List.of("*"), List.of(), List.of(), List.of(), false);
        ReactiveSecurityContext partial = new ReactiveSecurityContext(
                List.of(), List.of(unsafe), false, ReactiveSecurityEnvironmentSnapshot.empty());
        assertThat(new ReactiveCorsWildcardOriginRule().evaluate(partial).status())
                .isEqualTo("VIOLATION");
        assertThat(partial.evaluation().evidence(List.of()).usable()).isTrue();
        assertThat(partial.evaluation().evidence(List.of()).coverageComplete()).isFalse();
    }

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
        assertThat(report.evidence().usable()).isFalse();
        assertThat(report.evidence().coverageComplete()).isFalse();
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
        assertThat(report.evidence().usable()).isFalse();
    }

    @Test
    void failedEmptyAndNullSubsequentScansNeverRetainOldChainDescriptions() {
        for (int outcome = 0; outcome < 3; outcome++) {
            int next = outcome;
            AtomicInteger calls = new AtomicInteger();
            ReactiveSecurityScanner scanner = ReactiveSecurityScanner.using(
                    () -> {
                        if (calls.getAndIncrement() == 0) {
                            return minimalObservation();
                        }
                        if (next == 0) {
                            throw new IllegalStateException("do-not-leak");
                        }
                        return next == 1 ? ReactiveSecurityObservation.empty() : null;
                    },
                    CLOCK);
            assertThat(scanner.scan().filterChains()).isNotEmpty();
            SecurityReport after = scanner.scan();
            assertThat(after.scan().status()).isEqualTo("DISABLED");
            assertThat(after.filterChains()).isEmpty();
            assertThat(after.toString()).doesNotContain("do-not-leak");
        }
    }

    @Test
    void skippedEvidenceMakesPartialWithoutAnalysisErrorsAndKnownFindingSurvives() {
        WebFilterChainObservation unknown =
                new WebFilterChainObservation(0, "unknown", List.of(), null, List.of(), null, null, null, null);
        WebFilterChainObservation missing =
                new WebFilterChainObservation(1, "known", List.of(), true, List.of(), null, null, null, null);
        ReactiveSecurityObservation observation = new ReactiveSecurityObservation(
                List.of(unknown, missing),
                List.of(),
                false,
                List.of(),
                List.of(),
                List.of(),
                ReactiveSecurityEnvironmentSnapshot.empty(),
                List.of());
        SecurityReport report =
                ReactiveSecurityScanner.using(() -> observation, CLOCK).scan();
        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.scan().message()).contains("incomplete");
        assertThat(report.analysisErrors()).isEmpty();
        assertThat(report.results()).extracting(SecurityRuleResultDto::id).contains("SEC-RXF-AUTHZ-001");
    }

    @Test
    void actualConfigurationFailureIsErrorAndNeverLeaksApplicationExceptionDetails() {
        ReactiveSecurityEnvironmentSnapshot failed = new ReactiveSecurityEnvironmentSnapshot(
                false,
                null,
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
                false,
                false,
                false,
                Set.of(),
                true,
                Map.of("SEC-RXF-CONFIG-004", "java.lang.IllegalArgumentException"),
                Set.of());
        SecurityReport report = scan(minimalObservation().chains().get(0), failed);
        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.evidence().usable()).isTrue();
        assertThat(report.evidence().coverageComplete()).isFalse();
        assertThat(report.analysisErrors()).singleElement().satisfies(result -> {
            assertThat(result.id()).isEqualTo("SEC-RXF-CONFIG-004");
            assertThat(result.status()).isEqualTo("ERROR");
        });
    }

    @Test
    void findingDoesNotHideIncompleteCorsCoverage() {
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
        List<CorsConfigObservation> cors = List.of(
                new CorsConfigObservation("/first", List.of("*"), List.of(), List.of(), List.of(), false),
                new CorsConfigObservation("/second", List.of(), List.of("*"), List.of(), List.of(), true),
                new CorsConfigObservation(
                        "/third", List.of(), List.of("https://*.example.com"), List.of(), List.of(), true));
        ReactiveSecurityObservation observation = new ReactiveSecurityObservation(
                List.of(chain),
                cors,
                true,
                List.of(),
                List.of(),
                List.of(),
                ReactiveSecurityEnvironmentSnapshot.empty(),
                List.of(),
                false);
        ReactiveSecurityScanner scanner = ReactiveSecurityScanner.using(() -> observation, CLOCK);
        SecurityReport report = scanner.scan();
        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.results())
                .extracting(SecurityRuleResultDto::id)
                .contains("SEC-RXF-CORS-001", "SEC-RXF-CORS-002");
        assertThat(report.evidence().usable()).isTrue();
        assertThat(report.evidence().coverageComplete()).isFalse();
        assertThat(report.evidence().limitations()).anyMatch(value -> value.contains("SEC-RXF-CORS-001"));
        assertThat(scanner.applyDismissals(report, Set.of("SEC-RXF-CORS-001")).evidence())
                .isEqualTo(report.evidence());
    }

    @Test
    void failedApplicableEvaluationsNeverCountAsCompletedChecks() {
        Map<String, String> failures = ReactiveSecurityRuleRegistry.activeRules().stream()
                .collect(java.util.stream.Collectors.toMap(
                        rule -> rule.definition().id(), rule -> "Unavailable"));
        ReactiveSecurityEnvironmentSnapshot environment = new ReactiveSecurityEnvironmentSnapshot(
                false, null, null, false, List.of(), false, false, false, false, null, Set.of(), false, false, false,
                false, false, Set.of(), true, failures, Set.of());
        SecurityReport report = scan(minimalObservation().chains().get(0), environment);
        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.analysisErrors()).hasSize(RULE_COUNT);
        assertThat(report.evidence().usable()).isFalse();
        assertThat(report.evidence().coverageComplete()).isFalse();
    }

    @Test
    void oauthClientGrantsAreNotBrowserLoginOrSessionPersistence() {
        WebFilterChainObservation client = new WebFilterChainObservation(
                0,
                "any request",
                List.of("AuthenticationWebFilter", "OAuth2AuthorizationCodeGrantWebFilter"),
                true,
                true,
                List.of(),
                null,
                null,
                null,
                null,
                true,
                false);
        assertThat(scan(client, List.of(), false).results())
                .extracting(SecurityRuleResultDto::id)
                .doesNotContain("SEC-RXF-CSRF-001", "SEC-RXF-CSRF-002", "SEC-RXF-SESSION-001");
    }

    @Test
    void wildcardSchemeWithExactTrustedHostIsNotArbitraryHostTrust() {
        SecurityReport report = scan(
                minimalObservation().chains().get(0),
                List.of(new CorsConfigObservation(
                        "/**", List.of(), List.of("*://app.example.com"), List.of(), List.of(), true)),
                true);
        assertThat(report.results()).extracting(SecurityRuleResultDto::id).doesNotContain("SEC-RXF-CORS-003");
    }

    @Test
    void rejectedLiteralWildcardPrecedesPatternsAndWildcardPatternsDoNotDuplicateBroadReview() {
        SecurityReport invalid = scan(
                minimalObservation().chains().get(0),
                List.of(new CorsConfigObservation(
                        "/**", List.of("*"), List.of("*", "https://*"), List.of(), List.of(), true)),
                true);
        assertThat(invalid.results())
                .extracting(SecurityRuleResultDto::id)
                .contains("SEC-RXF-CORS-001")
                .doesNotContain("SEC-RXF-CORS-002", "SEC-RXF-CORS-003");
        SecurityReport broad = scan(
                minimalObservation().chains().get(0),
                List.of(new CorsConfigObservation(
                        "/**", List.of(), List.of("*", "https://*"), List.of(), List.of(), true)),
                true);
        assertThat(broad.results())
                .extracting(SecurityRuleResultDto::id)
                .contains("SEC-RXF-CORS-002")
                .doesNotContain("SEC-RXF-CORS-001", "SEC-RXF-CORS-003");
    }

    @Test
    void anonymousOnlyDoesNotCountAsCredentialAuthenticationForDisabledHeaders() {
        WebFilterChainObservation anonymous = new WebFilterChainObservation(
                0, "any request", List.of("AnonymousAuthenticationWebFilter"), true, List.of(), null, null, null, null);
        assertThat(scan(anonymous, List.of(), false).results())
                .extracting(SecurityRuleResultDto::id)
                .contains("SEC-RXF-AUTHZ-001")
                .doesNotContain("SEC-RXF-HEAD-005");
    }

    @Test
    void unknownCspDispositionCannotEstablishEnforcementOrMissingFraming() {
        WebFilterChainObservation unknown = new WebFilterChainObservation(
                0,
                "any request",
                List.of("HttpHeaderWriterWebFilter"),
                true,
                false,
                List.of(),
                null,
                null,
                "frame-ancestors 'none'",
                null,
                true,
                false);
        SecurityReport report = scan(unknown, List.of(), false);
        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.results())
                .extracting(SecurityRuleResultDto::id)
                .doesNotContain("SEC-RXF-HEAD-002", "SEC-RXF-HEAD-004");
    }

    @Test
    void enforcingFrameAncestorsOverridesFrameOptionsButReportOnlyDoesNot() {
        for (String policy :
                List.of("frame-ancestors *", "frame-ancestors 'none'", "frame-ancestors", "default-src 'self'")) {
            WebFilterChainObservation chain = new WebFilterChainObservation(
                    0,
                    "any request",
                    List.of("OAuth2LoginAuthenticationWebFilter", "HttpHeaderWriterWebFilter"),
                    true,
                    List.of("XFrameOptionsServerHttpHeadersWriter"),
                    null,
                    null,
                    policy,
                    false);
            assertThat(scan(chain, List.of(), false).results().stream()
                            .anyMatch(result -> result.id().equals("SEC-RXF-HEAD-002")))
                    .as(policy)
                    .isEqualTo(policy.equals("frame-ancestors *"));
        }
        WebFilterChainObservation reporting = new WebFilterChainObservation(
                0,
                "any request",
                List.of("OAuth2LoginAuthenticationWebFilter", "HttpHeaderWriterWebFilter"),
                true,
                List.of("XFrameOptionsServerHttpHeadersWriter"),
                null,
                null,
                "frame-ancestors *",
                true);
        assertThat(scan(reporting, List.of(), false).results())
                .extracting(SecurityRuleResultDto::id)
                .doesNotContain("SEC-RXF-HEAD-002");
        WebFilterChainObservation unknown = new WebFilterChainObservation(
                0,
                "any request",
                List.of("OAuth2LoginAuthenticationWebFilter", "HttpHeaderWriterWebFilter"),
                true,
                List.of("XFrameOptionsServerHttpHeadersWriter"),
                null,
                null,
                "frame-ancestors 'none'",
                null);
        ReactiveSecurityObservation observation = new ReactiveSecurityObservation(
                List.of(unknown),
                List.of(),
                false,
                List.of(),
                List.of(),
                List.of(),
                ReactiveSecurityEnvironmentSnapshot.empty(),
                List.of());
        assertThat(new ReactiveFrameOptionsRule()
                        .evaluate(ReactiveSecurityContext.from(observation))
                        .status())
                .isEqualTo("SKIPPED");
        WebFilterChainObservation unsupported = new WebFilterChainObservation(
                0,
                "any request",
                List.of("HttpHeaderWriterWebFilter"),
                true,
                List.of("XFrameOptionsServerHttpHeadersWriter"),
                null,
                null,
                "default-src 'self', frame-ancestors *",
                false);
        ReactiveSecurityObservation unsupportedObservation = new ReactiveSecurityObservation(
                List.of(unsupported),
                List.of(),
                false,
                List.of(),
                List.of(),
                List.of(),
                ReactiveSecurityEnvironmentSnapshot.empty(),
                List.of());
        assertThat(new ReactiveFrameOptionsRule()
                        .evaluate(ReactiveSecurityContext.from(unsupportedObservation))
                        .status())
                .isEqualTo("SKIPPED");
    }

    @Test
    void zeroAgeHstsIsDeletionAndUnreadableAgeIsUnknown() {
        WebFilterChainObservation zero = new WebFilterChainObservation(
                0,
                "any request",
                List.of("HttpHeaderWriterWebFilter"),
                true,
                List.of("StrictTransportSecurityServerHttpHeadersWriter"),
                0L,
                true,
                null,
                null);
        assertThat(scan(zero, List.of(), false).results())
                .filteredOn(result -> result.id().equals("SEC-RXF-HEAD-006"))
                .singleElement()
                .satisfies(result -> assertThat(result.sampleViolations())
                        .singleElement()
                        .asString()
                        .contains("removes"));
        WebFilterChainObservation unknown = new WebFilterChainObservation(
                0,
                "any request",
                List.of("HttpHeaderWriterWebFilter"),
                true,
                List.of("StrictTransportSecurityServerHttpHeadersWriter"),
                null,
                true,
                null,
                null);
        assertThat(scan(unknown, List.of(), false).scan().status()).isEqualTo("PARTIAL");
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
                .contains("SEC-RXF-CORS-002", "SEC-RXF-ACT-001")
                .doesNotContain("SEC-RXF-CORS-001");
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
        assertThat(new ReactiveBasicCsrfRule().evaluate(context).status()).isEqualTo(ReactiveSecuritySupport.SKIPPED);
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
    void missingAuthorizationDoesNotDuplicateRetiredRules() {
        WebFilterChainObservation chain = new WebFilterChainObservation(
                0, "any request", List.of("AuthenticationWebFilter"), Boolean.TRUE, List.of(), null, null, null, null);

        SecurityReport report = scan(chain, List.of(), false);

        assertThat(report.results())
                .extracting(SecurityRuleResultDto::id)
                .contains("SEC-RXF-AUTHZ-001")
                .doesNotContain("SEC-RXF-AUTHZ-002", "SEC-RXF-AUTHZ-003");
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
    void scanDetectsCsrfWebFilterAbsenceForOauthLogin() {
        WebFilterChainObservation chain = new WebFilterChainObservation(
                0,
                "any request",
                List.of(
                        "SecurityContextServerWebExchangeWebFilter",
                        "AuthorizationWebFilter",
                        "OAuth2LoginAuthenticationWebFilter"),
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
    void broadHostOriginPatternTriggersLowSeverityReview() {
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
                .isEqualTo("LOW");
    }

    @Test
    void broadTopLevelDomainSuffixOriginPatternTriggersLowSeverityReview() {
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
                .isEqualTo("LOW");
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
                List.of("AuthorizationWebFilter", "OAuth2LoginAuthenticationWebFilter", "HttpHeaderWriterWebFilter"),
                Boolean.FALSE,
                List.of("ContentTypeOptionsServerHttpHeadersWriter"),
                null,
                null,
                "default-src 'self'; frame-ancestors 'none'",
                Boolean.FALSE);
        WebFilterChainObservation reportOnlyPolicy = new WebFilterChainObservation(
                0,
                "any request",
                List.of("AuthorizationWebFilter", "OAuth2LoginAuthenticationWebFilter", "HttpHeaderWriterWebFilter"),
                Boolean.FALSE,
                List.of("ContentTypeOptionsServerHttpHeadersWriter"),
                null,
                null,
                "default-src 'self'; frame-ancestors 'none'",
                Boolean.TRUE);
        WebFilterChainObservation directiveNameOnlyInUrl = new WebFilterChainObservation(
                0,
                "any request",
                List.of("AuthorizationWebFilter", "OAuth2LoginAuthenticationWebFilter", "HttpHeaderWriterWebFilter"),
                Boolean.FALSE,
                List.of("ContentTypeOptionsServerHttpHeadersWriter"),
                null,
                null,
                "default-src 'self'; report-uri https://frame-ancestors.example",
                Boolean.FALSE);
        WebFilterChainObservation unrestrictedPolicy = new WebFilterChainObservation(
                0,
                "any request",
                List.of("AuthorizationWebFilter", "OAuth2LoginAuthenticationWebFilter", "HttpHeaderWriterWebFilter"),
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
                List.of("AuthorizationWebFilter", "OAuth2LoginAuthenticationWebFilter", "HttpHeaderWriterWebFilter"),
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
                List.of("AuthorizationWebFilter", "OAuth2LoginAuthenticationWebFilter", "HttpHeaderWriterWebFilter"),
                Boolean.FALSE,
                List.of("ContentSecurityPolicyServerHttpHeadersWriter"),
                null,
                null,
                "default-src 'self'",
                Boolean.TRUE);
        WebFilterChainObservation enforcingChain = new WebFilterChainObservation(
                0,
                "any request",
                List.of("AuthorizationWebFilter", "OAuth2LoginAuthenticationWebFilter", "HttpHeaderWriterWebFilter"),
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
        assertThat(result.severity()).isEqualTo("INFO");
        assertThat(result.sampleViolations())
                .singleElement()
                .asString()
                .contains("supported static verification key", "out-of-band rotation");
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
    void plainHttpOAuth2ClientProviderEndpointIsHighOnlyInProduction() {
        WebFilterChainObservation chain = new WebFilterChainObservation(
                0, "any request", List.of("AuthorizationWebFilter"), Boolean.FALSE, List.of(), null, null, null, null);
        String key = "spring.security.oauth2.client.provider.corp.token-uri";

        SecurityReport production = scan(chain, clientEndpoints(List.of("prod"), Set.of(key)));
        SecurityReport development = scan(chain, clientEndpoints(List.of("dev"), Set.of(key)));
        SecurityReport secure = scan(chain, clientEndpoints(List.of("prod"), Set.of()));

        assertThat(production.results())
                .filteredOn(result -> result.id().equals("SEC-RXF-OAUTH2-005"))
                .singleElement()
                .satisfies(result -> {
                    assertThat(result.severity()).isEqualTo("HIGH");
                    assertThat(result.sampleViolations()).containsExactly(key + " uses plain HTTP.");
                });
        assertThat(development.results()).extracting(SecurityRuleResultDto::id).doesNotContain("SEC-RXF-OAUTH2-005");
        assertThat(secure.results()).extracting(SecurityRuleResultDto::id).doesNotContain("SEC-RXF-OAUTH2-005");
    }

    @Test
    void documentHeaderRulesIgnoreBearerOnlyApiChainsButReviewBrowserLoginChains() {
        WebFilterChainObservation bearerApi = new WebFilterChainObservation(
                0,
                "any request",
                List.of("AuthenticationWebFilter", "AuthorizationWebFilter", "HttpHeaderWriterWebFilter"),
                Boolean.FALSE,
                true,
                List.of("ContentTypeOptionsServerHttpHeadersWriter"),
                null,
                null,
                null,
                null,
                true,
                false,
                false,
                true,
                null,
                List.of(),
                false);
        WebFilterChainObservation oneTimeTokenLogin = new WebFilterChainObservation(
                0,
                "any request",
                List.of("AuthenticationWebFilter", "AuthorizationWebFilter", "HttpHeaderWriterWebFilter"),
                Boolean.FALSE,
                false,
                List.of("ContentTypeOptionsServerHttpHeadersWriter"),
                null,
                null,
                null,
                null,
                true,
                true,
                false,
                true,
                null,
                List.of(),
                false);

        SecurityReport api = scan(bearerApi, List.of(), false);
        SecurityReport browser = scan(oneTimeTokenLogin, List.of(), false);

        assertThat(api.results())
                .extracting(SecurityRuleResultDto::id)
                .doesNotContain("SEC-RXF-HEAD-002", "SEC-RXF-HEAD-004");
        assertThat(browser.results())
                .extracting(SecurityRuleResultDto::id)
                .contains("SEC-RXF-HEAD-002", "SEC-RXF-HEAD-004", "SEC-RXF-CSRF-001");
    }

    private static ReactiveSecurityEnvironmentSnapshot clientEndpoints(List<String> profiles, Set<String> keys) {
        return new ReactiveSecurityEnvironmentSnapshot(
                false, null, null, false, profiles, false, false, false, false, null, Set.of(), false, false, false,
                false, false, Set.of(), true, Map.of(), Set.of(), true, keys);
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
                .contains(
                        "SEC-RXF-ACT-005",
                        "SEC-RXF-OAUTH2-004",
                        "SEC-RXF-OAUTH2-005",
                        "SEC-RXF-CORS-003",
                        "SEC-RXF-AUTHZ-004")
                .doesNotContain("SEC-RXF-CONFIG-001", "SEC-RXF-OAUTH2-001", "SEC-RXF-AUTHZ-002", "SEC-RXF-AUTHZ-003");
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
