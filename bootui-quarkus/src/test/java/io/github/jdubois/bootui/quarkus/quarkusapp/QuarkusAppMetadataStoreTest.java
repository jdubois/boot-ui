package io.github.jdubois.bootui.quarkus.quarkusapp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.spi.QuarkusAppEvidenceProblem;
import io.github.jdubois.bootui.spi.QuarkusAppMetadata;
import io.github.jdubois.bootui.spi.QuarkusAppMetadata.RestClient;
import io.github.jdubois.bootui.spi.QuarkusAppMetadata.SharedField;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuarkusAppMetadataStoreTest {

    @Test
    void roundTripsAllDeclarationsAndCapabilitiesInDeterministicOrder() {
        QuarkusAppMetadata first = metadata(
                List.of(
                        new SharedField("sample.Z", "state", "SINGLETON", true),
                        new SharedField("sample.A", "state", "APPLICATION", false)),
                List.of("sample.Z#call()", "sample.A#call()"),
                List.of(new RestClient("sample.Z", "z"), new RestClient("sample.A", "a")),
                List.of(new QuarkusAppEvidenceProblem("QA-PERF-002", QuarkusAppMetadataStore.UNRESOLVED)));
        QuarkusAppMetadata second = metadata(
                reversed(first.sharedFields()), reversed(first.synchronizedVirtualThreadMethods()),
                reversed(first.restClients()), first.problems());

        byte[] encoded = QuarkusAppMetadataStore.encode(first);
        QuarkusAppMetadata result = QuarkusAppMetadataStore.decode(encoded);

        assertThat(encoded).isEqualTo(QuarkusAppMetadataStore.encode(second));
        assertThat(result.available()).isTrue();
        assertThat(result.beanCount()).isEqualTo(2);
        assertThat(result.endpointCount()).isEqualTo(3);
        assertThat(result.configPropertyCount()).isEqualTo(4);
        assertThat(result.configMappingCount()).isEqualTo(5);
        assertThat(result.scheduledDeclarationCount()).isEqualTo(6);
        assertThat(result.hibernateOrmSupported()).isTrue();
        assertThat(result.jdbcDatasourceSupported()).isTrue();
        assertThat(result.restClientSupported()).isTrue();
        assertThat(result.sharedFields()).containsExactlyElementsOf(second.sharedFields());
        assertThat(result.synchronizedVirtualThreadMethods())
                .containsExactlyElementsOf(second.synchronizedVirtualThreadMethods());
        assertThat(result.restClients()).containsExactlyElementsOf(second.restClients());
        assertThat(result.problems()).isEqualTo(first.problems());
    }

    @Test
    void missingResourceAndNullContextClassLoaderAreUnavailableNotKnownEmpty() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(new ClassLoader(null) {});
            assertThat(QuarkusAppMetadataStore.load()).isEqualTo(QuarkusAppMetadata.unavailable());
            Thread.currentThread().setContextClassLoader(null);
            assertThat(QuarkusAppMetadataStore.load()).isEqualTo(QuarkusAppMetadata.unavailable());
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void unreadableResourcesUseOnlyFixedErrorsAndCloseTheStream() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        boolean[] closed = {false};
        try {
            Thread.currentThread().setContextClassLoader(new ClassLoader(null) {
                @Override
                public InputStream getResourceAsStream(String name) {
                    return new InputStream() {
                        @Override
                        public int read() throws IOException {
                            throw new IOException("secret-source-value");
                        }

                        @Override
                        public void close() {
                            closed[0] = true;
                        }
                    };
                }
            });
            assertInvalid(QuarkusAppMetadataStore.load());
            assertThat(closed[0]).isTrue();
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void rejectsInvalidHeaderVersionTruncatedTrailingAndExcessiveBytes() {
        byte[] valid = QuarkusAppMetadataStore.encode(metadata(List.of(), List.of(), List.of(), List.of()));
        byte[] badMagic = valid.clone();
        badMagic[0] = 0;
        byte[] badVersion = valid.clone();
        badVersion[7] = 99;
        byte[] negativeCount = valid.clone();
        ByteBuffer.wrap(negativeCount).putInt(9, -1);
        byte[] oversizedCount = valid.clone();
        ByteBuffer.wrap(oversizedCount).putInt(9, QuarkusAppMetadataStore.MAX_CLASSES + 1);
        byte[] oversizedFieldCount = valid.clone();
        ByteBuffer.wrap(oversizedFieldCount).putInt(32, Integer.MAX_VALUE);

        for (byte[] bytes : List.of(
                badMagic,
                badVersion,
                negativeCount,
                oversizedCount,
                oversizedFieldCount,
                Arrays.copyOf(valid, valid.length - 1),
                Arrays.copyOf(valid, valid.length + 1),
                new byte[QuarkusAppMetadataStore.MAX_RESOURCE_BYTES + 1])) {
            assertInvalid(QuarkusAppMetadataStore.decode(bytes));
        }
        assertInvalid(QuarkusAppMetadataStore.decode(null));
    }

    @Test
    void invalidStringLengthAndUtf8AreUnavailable() {
        byte[] valid = QuarkusAppMetadataStore.encode(metadata(
                List.of(new SharedField("sample.A", "field", "APPLICATION", false)), List.of(), List.of(), List.of()));
        byte[] excessiveString = valid.clone();
        ByteBuffer.wrap(excessiveString).putInt(36, Integer.MAX_VALUE);
        assertInvalid(QuarkusAppMetadataStore.decode(excessiveString));

        byte[] invalidUtf8 = valid.clone();
        invalidUtf8[40] = (byte) 0xff;
        assertInvalid(QuarkusAppMetadataStore.decode(invalidUtf8));
    }

    @Test
    void entryLimitsAreSanitizedWithoutPreventingResourceGeneration() {
        SharedField field = new SharedField("sample.A", "field", "APPLICATION", false);
        QuarkusAppMetadata result = QuarkusAppMetadataStore.decode(QuarkusAppMetadataStore.encode(metadata(
                Collections.nCopies(QuarkusAppMetadataStore.MAX_MEMBERS + 1, field),
                Collections.nCopies(QuarkusAppMetadataStore.MAX_MEMBERS + 1, "sample.A#call()"),
                Collections.nCopies(QuarkusAppMetadataStore.MAX_CLIENTS + 1, new RestClient("sample.Client", "")),
                List.of())));

        assertThat(result.available()).isTrue();
        assertThat(result.problems())
                .extracting(QuarkusAppEvidenceProblem::ruleId)
                .containsExactly("QA-CDI-001", "QA-CDI-002", "QA-CDI-003", "QA-PERF-002", "QA-WEB-003");
        assertThat(result.problems()).allMatch(problem -> problem.message().equals(QuarkusAppMetadataStore.INCOMPLETE));
    }

    @Test
    void oversizedNamesPreserveOtherEvidenceWithRuleSpecificErrors() {
        String oversized = "x".repeat(QuarkusAppMetadataStore.MAX_STRING_BYTES + 1);
        SharedField retained = new SharedField("sample.A", "field", "APPLICATION", false);
        QuarkusAppMetadata result = QuarkusAppMetadataStore.decode(QuarkusAppMetadataStore.encode(metadata(
                List.of(retained, new SharedField("sample.B", oversized, "SINGLETON", true)),
                List.of("sample.A#call()", oversized),
                List.of(new RestClient("sample.Client", oversized)),
                List.of())));

        assertThat(result.available()).isTrue();
        assertThat(result.sharedFields()).containsExactly(retained);
        assertThat(result.synchronizedVirtualThreadMethods()).containsExactly("sample.A#call()");
        assertThat(result.restClients()).isEmpty();
        assertThat(result.problems())
                .extracting(QuarkusAppEvidenceProblem::ruleId)
                .containsExactly("QA-CDI-002", "QA-PERF-002", "QA-WEB-003");
    }

    @Test
    void aggregateResourceBudgetRetainsBoundedDeterministicEvidence() {
        String longName = "x".repeat(4_000);
        List<SharedField> fields = new ArrayList<>();
        for (int i = 0; i < 3_000; i++) {
            fields.add(new SharedField("sample.C" + i, longName, "APPLICATION", false));
        }
        byte[] bytes = QuarkusAppMetadataStore.encode(metadata(fields, List.of(), List.of(), List.of()));
        QuarkusAppMetadata result = QuarkusAppMetadataStore.decode(bytes);

        assertThat(bytes.length).isLessThanOrEqualTo(QuarkusAppMetadataStore.MAX_RESOURCE_BYTES);
        assertThat(result.available()).isTrue();
        assertThat(result.sharedFields()).isNotEmpty().hasSizeLessThan(fields.size());
        assertThat(result.problems())
                .extracting(QuarkusAppEvidenceProblem::ruleId)
                .containsExactly("QA-CDI-001");
        assertThat(bytes)
                .isEqualTo(QuarkusAppMetadataStore.encode(metadata(reversed(fields), List.of(), List.of(), List.of())));
    }

    @Test
    void arbitraryExceptionMessagesNeverSurviveTheCodec() {
        QuarkusAppMetadata result = QuarkusAppMetadataStore.decode(QuarkusAppMetadataStore.encode(metadata(
                List.of(),
                List.of(),
                List.of(),
                List.of(new QuarkusAppEvidenceProblem("QA-WEB-003", "password=secret")))));

        assertThat(result.problems())
                .containsExactly(new QuarkusAppEvidenceProblem("QA-WEB-003", QuarkusAppMetadataStore.UNRESOLVED));
    }

    @Test
    void loadReadsTheBoundedGeneratedResource() {
        byte[] bytes = QuarkusAppMetadataStore.encode(metadata(List.of(), List.of(), List.of(), List.of()));
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(new ClassLoader(null) {
                @Override
                public InputStream getResourceAsStream(String name) {
                    assertThat(name).isEqualTo(QuarkusAppMetadataStore.RESOURCE_NAME);
                    return new ByteArrayInputStream(bytes);
                }
            });
            assertThat(QuarkusAppMetadataStore.load().available()).isTrue();
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    private static QuarkusAppMetadata metadata(
            List<SharedField> fields,
            List<String> methods,
            List<RestClient> clients,
            List<QuarkusAppEvidenceProblem> problems) {
        return new QuarkusAppMetadata(true, 2, 3, 4, 5, 6, true, true, true, fields, methods, clients, problems);
    }

    private static <T> List<T> reversed(List<T> values) {
        List<T> reversed = new ArrayList<>(values);
        Collections.reverse(reversed);
        return reversed;
    }

    private static void assertInvalid(QuarkusAppMetadata result) {
        assertThat(result.available()).isFalse();
        assertThat(result.sharedFields()).isEmpty();
        assertThat(result.problems())
                .hasSize(5)
                .allMatch(problem -> problem.message().equals(QuarkusAppMetadataStore.INVALID_RESOURCE));
    }
}
