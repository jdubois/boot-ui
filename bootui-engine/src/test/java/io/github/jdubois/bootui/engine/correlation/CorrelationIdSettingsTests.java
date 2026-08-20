package io.github.jdubois.bootui.engine.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Validation and bounds for configured correlation-identifier header names. */
class CorrelationIdSettingsTests {

    @Test
    void defaultsToTheBuiltInNamesOnly() {
        assertThat(CorrelationIdSettings.defaults().headerNames())
                .containsExactlyElementsOf(CorrelationIdPolicy.BUILT_IN_HEADER_NAMES);
        assertThat(CorrelationIdSettings.defaults().rejectedHeaderNames()).isEmpty();
    }

    @Test
    void normalizesAndAppendsConfiguredNames() {
        CorrelationIdSettings settings = CorrelationIdSettings.of(" X-Tenant-Trace ", "X-Gateway-Id");

        assertThat(settings.headerNames())
                .containsExactly("x-correlation-id", "x-request-id", "x-flow-id", "x-tenant-trace", "x-gateway-id");
        assertThat(settings.rejectedHeaderNames()).isEmpty();
    }

    @Test
    void foldsDuplicatesAndBuiltInRepetitionsWithoutReportingThem() {
        CorrelationIdSettings settings =
                CorrelationIdSettings.of("X-Correlation-ID", "x-tenant-trace", "X-Tenant-Trace");

        assertThat(settings.headerNames())
                .containsExactly("x-correlation-id", "x-request-id", "x-flow-id", "x-tenant-trace");
        assertThat(settings.rejectedHeaderNames()).isEmpty();
    }

    @Test
    void rejectsCredentialBearingNamesEvenWhenConfigured() {
        CorrelationIdSettings settings =
                CorrelationIdSettings.of("Authorization", "Cookie", "Set-Cookie", "Proxy-Authorization", "X-Api-Key");

        assertThat(settings.headerNames()).containsExactlyElementsOf(CorrelationIdPolicy.BUILT_IN_HEADER_NAMES);
        assertThat(settings.rejectedHeaderNames())
                .containsExactly("Authorization", "Cookie", "Set-Cookie", "Proxy-Authorization", "X-Api-Key");
    }

    @Test
    void rejectsSecretLookingAndTokenBearingNames() {
        CorrelationIdSettings settings =
                CorrelationIdSettings.of("X-Auth-Token", "X-Session-Id", "X-Client-Secret", "X-Private-Trace");

        assertThat(settings.headerNames()).containsExactlyElementsOf(CorrelationIdPolicy.BUILT_IN_HEADER_NAMES);
        assertThat(settings.rejectedHeaderNames()).hasSize(4);
    }

    @Test
    void rejectsInvalidAndOverlongNames() {
        String overlong = "x-" + "a".repeat(CorrelationIdPolicy.MAX_HEADER_NAME_LENGTH);
        CorrelationIdSettings settings = CorrelationIdSettings.of("bad name", "bad:name", "", overlong);

        assertThat(settings.headerNames()).containsExactlyElementsOf(CorrelationIdPolicy.BUILT_IN_HEADER_NAMES);
        assertThat(settings.rejectedHeaderNames()).containsExactly("bad name", "bad:name", "", overlong);
    }

    @Test
    void rejectsNamesBeyondTheAdditionalBound() {
        List<String> configured = List.of("x-a-1", "x-a-2", "x-a-3", "x-a-4", "x-a-5", "x-a-6", "x-a-7");

        CorrelationIdSettings settings = CorrelationIdSettings.of(configured);

        assertThat(settings.headerNames())
                .hasSize(CorrelationIdPolicy.BUILT_IN_HEADER_NAMES.size()
                        + CorrelationIdPolicy.MAX_ADDITIONAL_HEADER_NAMES);
        assertThat(settings.rejectedHeaderNames()).containsExactly("x-a-6", "x-a-7");
    }

    @Test
    void lookupIdentitiesMatchTheValuesTheBrowserDerives() {
        // Pinned so the server and the browser (utils/correlationId.js) can never drift apart: the UI
        // derives the same identity locally so a typed identifier never has to reach BootUI.
        assertThat(CorrelationIdPolicy.lookupId("corr-1")).isEqualTo("88b87faa5f574f9b");
        assertThat(CorrelationIdPolicy.lookupId("req-1")).isEqualTo("74a2f8fde4aec9c7");
        assertThat(CorrelationIdPolicy.lookupId("flow-1")).isEqualTo("4fdac0bf3032d5c6");
    }

    @Test
    void lookupIdentitiesAreOpaqueStableAndValueSpecific() {
        String id = CorrelationIdPolicy.lookupId("corr-1");

        assertThat(id).hasSize(CorrelationIdPolicy.LOOKUP_ID_LENGTH).doesNotContain("corr-1");
        assertThat(id).isEqualTo(CorrelationIdPolicy.lookupId("corr-1"));
        assertThat(id).isNotEqualTo(CorrelationIdPolicy.lookupId("corr-2"));
        assertThat(id).isNotEqualTo(CorrelationIdPolicy.lookupId("CORR-1"));
        assertThat(CorrelationIdPolicy.lookupId(null)).isNull();
    }

    @Test
    void refusesTracePropagationHeaderNames() {
        CorrelationIdSettings settings = CorrelationIdSettings.of("traceparent", "X-B3-TraceId", "x-amzn-trace-id");

        assertThat(settings.headerNames()).containsExactlyElementsOf(CorrelationIdPolicy.BUILT_IN_HEADER_NAMES);
        assertThat(settings.rejectedHeaderNames()).containsExactly("traceparent", "X-B3-TraceId", "x-amzn-trace-id");
    }

    @Test
    void refusesAnyHeaderNameCarryingCookies() {
        assertThat(CorrelationIdSettings.of("X-Session-Cookie", "Cookie2").headerNames())
                .containsExactlyElementsOf(CorrelationIdPolicy.BUILT_IN_HEADER_NAMES);
    }

    @Test
    void boundsAnOverLongValueWithoutSplittingASurrogatePair() {
        String rocket = "\uD83D\uDE80";
        String value = "a".repeat(CorrelationIdPolicy.MAX_VALUE_LENGTH - 1) + rocket + "tail";

        String bounded = CorrelationIdPolicy.truncate(value);

        assertThat(bounded).hasSize(CorrelationIdPolicy.MAX_VALUE_LENGTH - 1);
        assertThat(Character.isHighSurrogate(bounded.charAt(bounded.length() - 1)))
                .isFalse();
        // The browser bounds the same value the same way, so both sides derive the same identity.
        assertThat(CorrelationIdPolicy.lookupId(bounded))
                .isEqualTo(CorrelationIdPolicy.lookupId("a".repeat(CorrelationIdPolicy.MAX_VALUE_LENGTH - 1)));
    }

    @Test
    void keepsASurrogatePairThatFitsInsideTheBound() {
        String rocket = "\uD83D\uDE80";
        String value = "a".repeat(CorrelationIdPolicy.MAX_VALUE_LENGTH - 2) + rocket + "tail";

        assertThat(CorrelationIdPolicy.truncate(value))
                .hasSize(CorrelationIdPolicy.MAX_VALUE_LENGTH)
                .endsWith(rocket);
    }
}
