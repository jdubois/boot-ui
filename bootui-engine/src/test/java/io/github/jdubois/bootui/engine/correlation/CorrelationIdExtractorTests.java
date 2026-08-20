package io.github.jdubois.bootui.engine.correlation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.CorrelationIdDto;
import io.github.jdubois.bootui.engine.correlation.CorrelationIdExtractor.CapturedCorrelationId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The canonical correlation-identifier policy: which names are read, how values are bounded and exposed. */
class CorrelationIdExtractorTests {

    @Test
    void readsTheThreeBuiltInHeadersRegardlessOfCasing() {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("X-Correlation-ID", List.of("corr-1"));
        headers.put("x-request-id", List.of("req-1"));
        headers.put("X-FLOW-ID", List.of("flow-1"));

        List<CapturedCorrelationId> captured = CorrelationIdExtractor.extract(headers, null);

        assertThat(captured)
                .extracting(CapturedCorrelationId::name, CapturedCorrelationId::value)
                .containsExactly(
                        tuple("x-correlation-id", "corr-1"),
                        tuple("x-request-id", "req-1"),
                        tuple("x-flow-id", "flow-1"));
    }

    @Test
    void ordersIdentifiersByPolicyNotByArrivalOrder() {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("X-Flow-Id", List.of("flow"));
        headers.put("X-Correlation-Id", List.of("corr"));

        assertThat(CorrelationIdExtractor.extract(headers, null))
                .extracting(CapturedCorrelationId::name)
                .containsExactly("x-correlation-id", "x-flow-id");
    }

    @Test
    void readsConfiguredAdditionalHeaders() {
        CorrelationIdSettings settings = CorrelationIdSettings.of("X-Tenant-Trace");

        List<CapturedCorrelationId> captured =
                CorrelationIdExtractor.extract(Map.of("x-tenant-trace", List.of("t-9")), settings);

        assertThat(captured).singleElement().satisfies(id -> {
            assertThat(id.name()).isEqualTo("x-tenant-trace");
            assertThat(id.value()).isEqualTo("t-9");
        });
    }

    @Test
    void ignoresUnconfiguredHeaders() {
        assertThat(CorrelationIdExtractor.extract(Map.of("x-tenant-trace", List.of("t-9")), null))
                .isEmpty();
    }

    @Test
    void keepsOnlyTheFirstNonBlankValueOfADuplicatedHeader() {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("X-Request-Id", List.of("  ", "first"));
        headers.put("x-request-id", List.of("second"));

        assertThat(CorrelationIdExtractor.extract(headers, null))
                .extracting(CapturedCorrelationId::value)
                .containsExactly("first");
    }

    @Test
    void trimsValuesAndRefusesBlankOnes() {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("X-Correlation-Id", List.of("  padded  "));
        headers.put("X-Request-Id", List.of("   "));

        assertThat(CorrelationIdExtractor.extract(headers, null))
                .extracting(CapturedCorrelationId::name, CapturedCorrelationId::value)
                .containsExactly(tuple("x-correlation-id", "padded"));
    }

    @Test
    void refusesValuesCarryingControlCharacters() {
        assertThat(CorrelationIdExtractor.extract(Map.of("x-request-id", List.of("bad\nvalue")), null))
                .isEmpty();
    }

    @Test
    void truncatesOverlongValuesAndFlagsThem() {
        String overlong = "z".repeat(CorrelationIdPolicy.MAX_VALUE_LENGTH + 40);

        CapturedCorrelationId captured = CorrelationIdExtractor.extract(Map.of("x-request-id", List.of(overlong)), null)
                .get(0);

        assertThat(captured.truncated()).isTrue();
        assertThat(captured.value()).hasSize(CorrelationIdPolicy.MAX_VALUE_LENGTH);
        assertThat(captured.lookupId())
                .isEqualTo(CorrelationIdPolicy.lookupId("z".repeat(CorrelationIdPolicy.MAX_VALUE_LENGTH)));
    }

    @Test
    void capsTheNumberOfIdentifiersCapturedPerRequest() {
        CorrelationIdSettings settings = CorrelationIdSettings.of("x-a", "x-b", "x-c");
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("x-correlation-id", List.of("1"));
        headers.put("x-request-id", List.of("2"));
        headers.put("x-flow-id", List.of("3"));
        headers.put("x-a", List.of("4"));
        headers.put("x-b", List.of("5"));

        assertThat(CorrelationIdExtractor.extract(headers, settings))
                .hasSize(CorrelationIdPolicy.MAX_IDS_PER_REQUEST)
                .extracting(CapturedCorrelationId::value)
                .containsExactly("1", "2", "3", "4");
    }

    @Test
    void returnsNothingWithoutHeaders() {
        assertThat(CorrelationIdExtractor.extract(null, null)).isEmpty();
        assertThat(CorrelationIdExtractor.extract(Map.of(), null)).isEmpty();
    }

    @Test
    void masksValuesByDefault() {
        List<CorrelationIdDto> dtos = CorrelationIdExtractor.toDtos(
                CorrelationIdExtractor.extract(Map.of("x-request-id", List.of("req-1")), null), ValueExposure.MASKED);

        assertThat(dtos).singleElement().satisfies(dto -> {
            assertThat(dto.name()).isEqualTo("x-request-id");
            assertThat(dto.value()).isEqualTo(SecretMasker.MASKED_VALUE);
            assertThat(dto.masked()).isTrue();
            assertThat(dto.lookupId()).isEqualTo(CorrelationIdPolicy.lookupId("req-1"));
        });
    }

    @Test
    void withholdsBothTheValueAndTheLookupIdentityUnderMetadataOnly() {
        List<CorrelationIdDto> dtos = CorrelationIdExtractor.toDtos(
                CorrelationIdExtractor.extract(Map.of("x-request-id", List.of("req-1")), null),
                ValueExposure.METADATA_ONLY);

        assertThat(dtos).singleElement().satisfies(dto -> {
            assertThat(dto.value()).isNull();
            assertThat(dto.masked()).isTrue();
            // The identity is a reproducible digest of the value, so serving it in the one mode that
            // promises no value-derived data would be a leak; filtering is unavailable there instead.
            assertThat(dto.lookupId()).isNull();
        });
        assertThat(CorrelationIdExtractor.lookupIds(dtos)).isEmpty();
    }

    @Test
    void revealsValuesOnlyUnderFullExposure() {
        List<CorrelationIdDto> dtos = CorrelationIdExtractor.toDtos(
                CorrelationIdExtractor.extract(Map.of("x-request-id", List.of("req-1")), null), ValueExposure.FULL);

        assertThat(dtos).singleElement().satisfies(dto -> {
            assertThat(dto.value()).isEqualTo("req-1");
            assertThat(dto.masked()).isFalse();
        });
    }

    @Test
    void exposesLookupIdentitiesInOrder() {
        List<CorrelationIdDto> dtos = CorrelationIdExtractor.toDtos(
                CorrelationIdExtractor.extract(
                        Map.of("x-correlation-id", List.of("a"), "x-request-id", List.of("b")), null),
                ValueExposure.MASKED);

        assertThat(CorrelationIdExtractor.lookupIds(dtos))
                .containsExactly(CorrelationIdPolicy.lookupId("a"), CorrelationIdPolicy.lookupId("b"));
    }

    @Test
    void foldsDuplicateCasingsDeterministicallyRegardlessOfHeaderMapOrder() {
        Map<String, List<String>> ascending = new LinkedHashMap<>();
        ascending.put("X-Request-ID", List.of("upper-first"));
        ascending.put("x-request-id", List.of("lower-second"));
        Map<String, List<String>> descending = new LinkedHashMap<>();
        descending.put("x-request-id", List.of("lower-second"));
        descending.put("X-Request-ID", List.of("upper-first"));

        assertThat(CorrelationIdExtractor.extract(ascending, null))
                .singleElement()
                .satisfies(id -> assertThat(id.value()).isEqualTo("upper-first"));
        assertThat(CorrelationIdExtractor.extract(descending, null))
                .singleElement()
                .satisfies(id -> assertThat(id.value()).isEqualTo("upper-first"));
    }

    @Test
    void fallsThroughToTheNextCasingWhenTheFirstCarriesNoUsableValue() {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("x-request-id", List.of("usable"));
        headers.put("X-Request-ID", List.of("   "));

        assertThat(CorrelationIdExtractor.extract(headers, null))
                .singleElement()
                .satisfies(id -> assertThat(id.value()).isEqualTo("usable"));
    }
}
