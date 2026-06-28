package io.github.jdubois.bootui.engine.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class InternalPackageMatcherTest {

    private final InternalPackageMatcher matcher = new InternalPackageMatcher(
            List.of("io.github.jdubois.bootui.autoconfigure", "io.github.jdubois.bootui.core"));

    @Test
    void matchesAConfiguredPackageExactly() {
        assertThat(matcher.matchesName("io.github.jdubois.bootui.autoconfigure"))
                .isTrue();
        assertThat(matcher.matchesName("io.github.jdubois.bootui.core")).isTrue();
    }

    @Test
    void matchesTypesAndLoggersNestedUnderAConfiguredPackage() {
        assertThat(matcher.matchesName("io.github.jdubois.bootui.autoconfigure.web.LoggersController"))
                .isTrue();
        assertThat(matcher.matchesName("io.github.jdubois.bootui.core.dto.LoggerDto"))
                .isTrue();
    }

    @Test
    void normalizesNestedClassSeparatorToDot() {
        assertThat(matcher.matchesName("io.github.jdubois.bootui.autoconfigure.web.Outer$Inner"))
                .isTrue();
    }

    @Test
    void usesDottedPrefixBoundaryToAvoidSiblingPackages() {
        // A sibling package that merely shares a prefix string must NOT match.
        assertThat(matcher.matchesName("io.github.jdubois.bootui.coreextra.Thing"))
                .isFalse();
    }

    @Test
    void doesNotMatchTheSharedEngineSpiOrSampleAppPackages() {
        // The shared modules and the host's own sample/application code stay visible in the panels: a blanket
        // "io.github.jdubois.bootui." prefix would wrongly hide the sample app's loggers (a behavior a
        // mutation test depends on), so the matcher is deliberately scoped to the adapter + core only.
        assertThat(matcher.matchesName("io.github.jdubois.bootui.engine.loggers.LoggersService"))
                .isFalse();
        assertThat(matcher.matchesName("io.github.jdubois.bootui.spi.LoggerProvider"))
                .isFalse();
        assertThat(matcher.matchesName("io.github.jdubois.bootui.sample.SampleApplication"))
                .isFalse();
    }

    @Test
    void doesNotMatchUnrelatedNames() {
        assertThat(matcher.matchesName("com.example.OrderService")).isFalse();
        assertThat(matcher.matchesName("org.springframework.web.SomeClass")).isFalse();
    }

    @Test
    void treatsNullAndBlankAsNonMatching() {
        assertThat(matcher.matchesName(null)).isFalse();
        assertThat(matcher.matchesName("")).isFalse();
        assertThat(matcher.matchesName("   ")).isFalse();
    }

    @Test
    void honorsTheAdapterSuppliedPackageList() {
        // The Quarkus adapter feeds its own package set; the matcher is purely driven by that input.
        InternalPackageMatcher quarkus = new InternalPackageMatcher(
                List.of("io.github.jdubois.bootui.quarkus", "io.github.jdubois.bootui.core"));

        assertThat(quarkus.matchesName("io.github.jdubois.bootui.quarkus.web.LoggersResource"))
                .isTrue();
        // The Spring adapter's package is not hidden by the Quarkus matcher.
        assertThat(quarkus.matchesName("io.github.jdubois.bootui.autoconfigure.web.LoggersController"))
                .isFalse();
    }
}
