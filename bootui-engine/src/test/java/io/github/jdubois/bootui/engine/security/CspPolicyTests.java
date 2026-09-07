package io.github.jdubois.bootui.engine.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CspPolicyTests {

    @Test
    void separatesScriptExecutionFromStylePolicy() {
        var policy = CspPolicy.analyze("default-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-eval'");
        assertThat(policy.complete()).isTrue();
        assertThat(policy.unsafeInlineScript()).isFalse();
        assertThat(policy.unsafeEvalScript()).isTrue();
        assertThat(policy.unrestrictedScript()).isFalse();
    }

    @Test
    void usesSpecificDirectiveBeforeFallbackAndKeepsFirstDuplicate() {
        var policy = CspPolicy.analyze("default-src *; script-src 'self'; script-src 'unsafe-inline' https:");
        assertThat(policy.unsafeInlineScript()).isFalse();
        assertThat(policy.unrestrictedScript()).isFalse();
        assertThat(CspPolicy.analyze("script-src *; script-src 'none'").unrestrictedScript())
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "script-src 'unsafe-inline' 'nonce-YWJjZA=='",
                "script-src 'unsafe-inline' 'sha256-YWJjZA=='",
                "script-src 'unsafe-inline' 'sha384-YWJjZA=='",
                "script-src 'unsafe-inline' 'sha512-YWJjZA=='",
                "script-src 'unsafe-inline' 'strict-dynamic'",
                "script-src 'UNSAFE-INLINE' 'NONCE-YWJjZA=='"
            })
    void nonceHashAndStrictDynamicOverrideUnsafeInline(String policy) {
        assertThat(CspPolicy.analyze(policy).unsafeInlineScript()).isFalse();
    }

    @Test
    void malformedNonceDoesNotSuppressUnsafeInline() {
        assertThat(CspPolicy.analyze("script-src 'unsafe-inline' 'nonce-'").unsafeInlineScript())
                .isTrue();
        assertThat(CspPolicy.analyze("script-src 'unsafe-inline' 'sha999-YWJjZA=='")
                        .unsafeInlineScript())
                .isTrue();
    }

    @Test
    void elementAndAttributePoliciesHaveIndependentFallbacks() {
        assertThat(CspPolicy.analyze("script-src 'unsafe-inline'; script-src-elem 'none'")
                        .unsafeInlineScript())
                .isTrue();
        assertThat(CspPolicy.analyze("script-src 'unsafe-inline'; script-src-elem 'none'; script-src-attr 'none'")
                        .unsafeInlineScript())
                .isFalse();
        var policy = CspPolicy.analyze("script-src 'unsafe-eval'; script-src-elem 'self'");
        assertThat(policy.unsafeEvalScript()).isTrue();
        assertThat(CspPolicy.analyze("script-src *; script-src-elem 'self'").unrestrictedScript())
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "https:", "data:", "https://*", "https://*:443", "*:8080"})
    void recognizesExplicitArbitrarySources(String source) {
        assertThat(CspPolicy.analyze("script-src " + source).unrestrictedScript())
                .isTrue();
    }

    @Test
    void strictDynamicDisregardsBroadScriptHosts() {
        var policy = CspPolicy.analyze("script-src 'nonce-YWJjZA==' 'strict-dynamic' https: 'unsafe-inline'");
        assertThat(policy.unrestrictedScript()).isFalse();
        assertThat(policy.unsafeInlineScript()).isFalse();
        assertThat(CspPolicy.analyze("script-src https://*.example.com").unrestrictedScript())
                .isFalse();
    }

    @Test
    void sandboxWithoutAllowScriptsBlocksScriptExecution() {
        var blocked = CspPolicy.analyze("script-src * 'unsafe-inline' 'unsafe-eval'; sandbox allow-forms");
        assertThat(blocked.complete()).isTrue();
        assertThat(blocked.unsafeInlineScript()).isFalse();
        assertThat(blocked.unsafeEvalScript()).isFalse();
        assertThat(blocked.unrestrictedScript()).isFalse();
        var allowed = CspPolicy.analyze("script-src * 'unsafe-inline' 'unsafe-eval'; sandbox allow-scripts");
        assertThat(allowed.unsafeInlineScript()).isTrue();
        assertThat(allowed.unsafeEvalScript()).isTrue();
        assertThat(allowed.unrestrictedScript()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "frame-ancestors 'none'",
                "frame-ancestors",
                "FRAME-ANCESTORS 'SELF'",
                "frame-ancestors 'self' https://app.example",
                "frame-ancestors https://*.example.com",
                "frame-ancestors 'none' 'self'",
                "frame-ancestors 'none'; frame-ancestors *"
            })
    void recognizesRestrictiveFramingSources(String policy) {
        assertThat(CspPolicy.analyze(policy).restrictiveFrameAncestors()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "default-src 'none'",
                "frame-ancestors *",
                "frame-ancestors 'none' *",
                "frame-ancestors https:",
                "frame-ancestors https://*",
                "frame-ancestors 'nonce-YWJjZA=='",
                "report-uri https://example.test/frame-ancestors"
            })
    void doesNotInventFramingProtection(String policy) {
        assertThat(CspPolicy.analyze(policy).restrictiveFrameAncestors()).isFalse();
    }

    @Test
    void distinguishesAbsentFramingPolicyFromAnExplicitPermissivePolicy() {
        var absent = CspPolicy.analyze("default-src 'none'");
        assertThat(absent.frameAncestorsPresent()).isFalse();
        assertThat(absent.restrictiveFrameAncestors()).isFalse();
        var permissive = CspPolicy.analyze("frame-ancestors *");
        assertThat(permissive.frameAncestorsPresent()).isTrue();
        assertThat(permissive.restrictiveFrameAncestors()).isFalse();
        var empty = CspPolicy.analyze("frame-ancestors;");
        assertThat(empty.frameAncestorsPresent()).isTrue();
        assertThat(empty.restrictiveFrameAncestors()).isTrue();
    }

    @Test
    void unsupportedCompositionAndLimitsAreExplicitlyIncomplete() {
        assertThat(CspPolicy.analyze("script-src *, script-src 'none'").complete())
                .isFalse();
        assertThat(CspPolicy.analyze(null).complete()).isFalse();
        assertThat(CspPolicy.analyze("x".repeat(8193)).complete()).isFalse();
        assertThat(CspPolicy.analyze("a;".repeat(65)).complete()).isFalse();
        assertThat(CspPolicy.analyze("script-src " + "'self' ".repeat(256)).complete())
                .isFalse();
        assertThat(CspPolicy.analyze("script-src 'self'\u0000").complete()).isFalse();
        assertThat(CspPolicy.analyze("script-src 'self'\u007f").complete()).isFalse();
        assertThat(CspPolicy.analyze("bad_directive *").complete()).isFalse();
        assertThat(CspPolicy.analyze("").complete()).isTrue();
    }

    @Test
    void analysisDoesNotRetainConfigurationValues() {
        var analysis = CspPolicy.analyze("script-src 'nonce-c2VjcmV0'; report-uri https://private.example/report");
        assertThat(analysis.toString()).doesNotContain("c2VjcmV0", "private.example");
    }
}
