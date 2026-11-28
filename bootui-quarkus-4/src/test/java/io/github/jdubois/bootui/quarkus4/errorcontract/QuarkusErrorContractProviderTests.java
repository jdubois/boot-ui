package io.github.jdubois.bootui.quarkus4.errorcontract;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.spi.ErrorHandlerDescriptor;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.UnsatisfiedResolutionException;
import jakarta.enterprise.util.TypeLiteral;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link QuarkusErrorContractProvider}, the runtime half of the Quarkus error-contract adapter.
 *
 * <p>All discovery happens at build time, so the provider's job is narrow but load-bearing: report the
 * backend honestly when the build-time capture is absent (production, or a launch mode where the BootUI API
 * is not wired), and otherwise map the captured rows one-to-one onto the neutral SPI contract without
 * reclassifying anything — classification belongs to the engine so all three stacks agree.</p>
 */
class QuarkusErrorContractProviderTests {

    @Test
    void reportsUnavailableWithAReasonWhenTheBuildTimeCaptureIsAbsent() {
        QuarkusErrorContractProvider provider = new QuarkusErrorContractProvider(new UnsatisfiedInstance<>());

        assertThat(provider.available()).isFalse();
        assertThat(provider.unavailableReason()).isEqualTo(QuarkusErrorContractProvider.UNAVAILABLE_REASON);
        assertThat(provider.handlers()).isEmpty();
    }

    @Test
    void reportsAvailableWithoutAReasonWhenTheCaptureIsPresentButEmpty() {
        QuarkusErrorContractProvider provider = providerFor();

        assertThat(provider.available()).isTrue();
        assertThat(provider.unavailableReason()).isNull();
        assertThat(provider.handlers()).isEmpty();
    }

    @Test
    void mapsCapturedRowsOntoTheNeutralContractWithoutReclassifyingThem() {
        RawErrorHandler raw = new RawErrorHandler(
                ErrorHandlerDescriptor.JAKARTA_REST_EXCEPTION_MAPPER,
                "com.example.OrderNotFoundMapper",
                "toResponse",
                List.of("com.example.OrderNotFoundException"),
                ErrorHandlerDescriptor.SCOPE_GLOBAL,
                null,
                5000,
                "404",
                true,
                "jakarta.ws.rs.core.Response",
                "com.example.ErrorBody",
                List.of("application/problem+json"));

        List<ErrorHandlerDescriptor> handlers = providerFor(raw).handlers();

        assertThat(handlers).singleElement().satisfies(handler -> {
            assertThat(handler.source()).isEqualTo(ErrorHandlerDescriptor.JAKARTA_REST_EXCEPTION_MAPPER);
            assertThat(handler.componentClassName()).isEqualTo("com.example.OrderNotFoundMapper");
            assertThat(handler.methodName()).isEqualTo("toResponse");
            assertThat(handler.exceptionTypeNames()).containsExactly("com.example.OrderNotFoundException");
            assertThat(handler.scope()).isEqualTo(ErrorHandlerDescriptor.SCOPE_GLOBAL);
            assertThat(handler.scopeTarget()).isNull();
            assertThat(handler.declaredOrder()).isEqualTo(5000);
            assertThat(handler.declaredStatus()).isEqualTo("404");
            assertThat(handler.dynamicStatus()).isTrue();
            assertThat(handler.returnTypeName()).isEqualTo("jakarta.ws.rs.core.Response");
            assertThat(handler.bodyTypeName()).isEqualTo("com.example.ErrorBody");
            assertThat(handler.produces()).containsExactly("application/problem+json");
        });
    }

    @Test
    void preservesCaptureOrderSoTheEngineOwnsTheStableSort() {
        RawErrorHandler second = serverExceptionMapper("com.example.ZebraResource", "mapZebra");
        RawErrorHandler first = serverExceptionMapper("com.example.AlphaResource", "mapAlpha");

        assertThat(providerFor(second, first).handlers())
                .extracting(ErrorHandlerDescriptor::componentClassName)
                .containsExactly("com.example.ZebraResource", "com.example.AlphaResource");
    }

    @Test
    void memoizesTheMappingBehindAStableListInstance() {
        QuarkusErrorContractProvider provider = providerFor(serverExceptionMapper("com.example.Resource", "map"));

        assertThat(provider.handlers()).isSameAs(provider.handlers());
    }

    @Test
    void toleratesNullListsFromTheBuildTimeCapture() {
        RawErrorHandler raw = new RawErrorHandler(
                ErrorHandlerDescriptor.JAKARTA_REST_EXCEPTION_MAPPER,
                "com.example.Mapper",
                "toResponse",
                null,
                ErrorHandlerDescriptor.SCOPE_GLOBAL,
                null,
                null,
                null,
                false,
                null,
                null,
                null);

        assertThat(new QuarkusErrorContract(null).handlers()).isEmpty();
        assertThat(providerFor(raw).handlers()).singleElement().satisfies(handler -> {
            assertThat(handler.exceptionTypeNames()).isEmpty();
            assertThat(handler.produces()).isEmpty();
        });
    }

    private static RawErrorHandler serverExceptionMapper(String component, String method) {
        return new RawErrorHandler(
                ErrorHandlerDescriptor.QUARKUS_SERVER_EXCEPTION_MAPPER,
                component,
                method,
                List.of("java.lang.IllegalStateException"),
                ErrorHandlerDescriptor.SCOPE_CONTROLLER,
                component,
                null,
                null,
                false,
                "jakarta.ws.rs.core.Response",
                null,
                List.of());
    }

    private static QuarkusErrorContractProvider providerFor(RawErrorHandler... rows) {
        return new QuarkusErrorContractProvider(
                new SatisfiedInstance<>(new QuarkusErrorContract(new ArrayList<>(List.of(rows)))));
    }

    /**
     * A minimal always-unsatisfied {@link Instance}, standing in for an absent {@code QuarkusErrorContract}
     * bean. This module hand-rolls CDI {@link Instance} fakes rather than depending on Mockito; see
     * {@code QuarkusDevServicesProviderTest} for the established practice this mirrors.
     */
    private static final class UnsatisfiedInstance<T> implements Instance<T> {

        @Override
        public Instance<T> select(Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isUnsatisfied() {
            return true;
        }

        @Override
        public boolean isAmbiguous() {
            return false;
        }

        @Override
        public void destroy(T instance) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instance.Handle<T> getHandle() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterable<? extends Instance.Handle<T>> handles() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterator<T> iterator() {
            throw new UnsupportedOperationException();
        }

        @Override
        public T get() {
            throw new UnsatisfiedResolutionException("no QuarkusErrorContract bean produced in this test");
        }
    }

    /** A minimal always-satisfied {@link Instance} wrapping a fixed value. */
    private static final class SatisfiedInstance<T> implements Instance<T> {

        private final T value;

        SatisfiedInstance(T value) {
            this.value = value;
        }

        @Override
        public Instance<T> select(Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isUnsatisfied() {
            return false;
        }

        @Override
        public boolean isAmbiguous() {
            return false;
        }

        @Override
        public void destroy(T instance) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instance.Handle<T> getHandle() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterable<? extends Instance.Handle<T>> handles() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterator<T> iterator() {
            throw new UnsupportedOperationException();
        }

        @Override
        public T get() {
            return value;
        }
    }
}
