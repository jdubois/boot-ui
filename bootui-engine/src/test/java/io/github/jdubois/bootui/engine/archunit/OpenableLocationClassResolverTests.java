package io.github.jdubois.bootui.engine.archunit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.tngtech.archunit.core.importer.resolvers.ClassResolver;
import java.net.URI;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OpenableLocationClassResolverTests {

    @Test
    void quarkusSchemeIsNotOpenable() throws Exception {
        // The exact resource location the Quarkus runtime classloader exposes, which triggers the
        // MalformedURLException: unknown protocol: quarkus WARN under ArchUnit's default resolver.
        URI quarkusResource = new URI("quarkus:io/quarkus/hibernate/orm/panache/PanacheEntityBase.class");

        assertThat(OpenableLocationClassResolver.isOpenable(quarkusResource)).isFalse();
    }

    @Test
    void standardClasspathSchemesAreOpenable() throws Exception {
        // file:/jar: cover a typical Spring Boot classpath; jrt: covers the JDK ancestry that is resolved
        // from the bootstrap loader even on Quarkus. All must stay openable so resolution is unchanged.
        assertThat(OpenableLocationClassResolver.isOpenable(new URI("file:/app/classes/Foo.class")))
                .isTrue();
        assertThat(OpenableLocationClassResolver.isOpenable(new URI("jar:file:/app/lib/x.jar!/Foo.class")))
                .isTrue();
        assertThat(OpenableLocationClassResolver.isOpenable(new URI("jrt:/java.base/java/lang/Object.class")))
                .isTrue();
    }

    @Test
    void nonAbsoluteLocationIsNotOpenable() throws Exception {
        // URI.toURL() throws IllegalArgumentException (not MalformedURLException) for a relative URI; the
        // resolver must treat that as unopenable too rather than letting it escape.
        assertThat(OpenableLocationClassResolver.isOpenable(new URI("relative/path/Foo.class")))
                .isFalse();
    }

    @Test
    void behavesAsAClassResolverWithoutThrowing() {
        ClassResolver resolver = new OpenableLocationClassResolver();
        resolver.setClassUriImporter(uri -> Optional.empty());

        assertThatCode(() -> {
                    Optional<?> resolved = resolver.tryResolve("does.not.Exist");
                    assertThat(resolved).isEmpty();
                })
                .doesNotThrowAnyException();
    }
}
