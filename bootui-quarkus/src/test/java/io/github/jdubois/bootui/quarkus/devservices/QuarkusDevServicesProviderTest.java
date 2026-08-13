package io.github.jdubois.bootui.quarkus.devservices;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.DevServiceDto;
import io.github.jdubois.bootui.quarkus.QuarkusExposurePolicy;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.UnsatisfiedResolutionException;
import jakarta.enterprise.util.TypeLiteral;
import java.lang.annotation.Annotation;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pure unit test of {@link QuarkusDevServicesProvider}'s mapping from build-time-captured
 * {@link RawDevService} entries to the neutral {@link DevServiceDto}. In particular, it pins that the
 * service {@code type} is classified via the shared
 * {@code io.github.jdubois.bootui.engine.devservices.DevServiceTypeInference} engine helper, so a Postgres
 * (or other well-known) Quarkus Dev Service is typed the same way the Spring adapter types an identical
 * name/description/config, instead of the previous generic {@code "Dev Service"} for every entry.
 */
class QuarkusDevServicesProviderTest {

    @Test
    void classifiesWellKnownDevServicesByType() {
        SmallRyeConfig config = new SmallRyeConfigBuilder().build();
        QuarkusDevServices captured = new QuarkusDevServices(List.of(
                new RawDevService(
                        "default",
                        "postgres:16",
                        "abc123",
                        Map.of("quarkus.datasource.jdbc.url", "jdbc:postgresql://localhost:5432/app")),
                new RawDevService("cache", "redis:7", "def456", Map.of()),
                new RawDevService("unknown", "custom-image:1", "", Map.of())));
        QuarkusDevServicesProvider provider =
                new QuarkusDevServicesProvider(new SatisfiedInstance<>(captured), new QuarkusExposurePolicy(config));

        List<String> types =
                provider.services().stream().map(DevServiceDto::type).toList();

        assertThat(types).containsExactly("PostgreSQL", "Redis", "Service");
    }

    @Test
    void returnsEmptyListWhenNoDevServicesWereCaptured() {
        SmallRyeConfig config = new SmallRyeConfigBuilder().build();
        QuarkusDevServicesProvider provider =
                new QuarkusDevServicesProvider(new UnsatisfiedInstance<>(), new QuarkusExposurePolicy(config));

        assertThat(provider.services()).isEmpty();
        assertThat(provider.dockerComposePresent()).isFalse();
        assertThat(provider.testcontainersPresent()).isFalse();
    }

    /**
     * A minimal always-unsatisfied {@link Instance}, standing in for an absent {@code QuarkusDevServices}
     * bean (no dev services started). No Mockito dependency exists in this module for CDI {@link Instance}
     * fakes; see {@code LiveActivityResourceTests} for the established practice this mirrors.
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
            throw new UnsatisfiedResolutionException("no QuarkusDevServices bean produced in this test");
        }
    }

    /**
     * A minimal always-satisfied {@link Instance} wrapping a fixed value, standing in for a present
     * {@code QuarkusDevServices} bean. See {@link UnsatisfiedInstance} for why this module hand-rolls
     * {@link Instance} fakes rather than using Mockito.
     */
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
