package io.github.jdubois.bootui.sample.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.TypeReference;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

class NativeHintsConfigurationTests {

    private final RuntimeHints hints = new RuntimeHints();

    NativeHintsConfigurationTests() {
        new NativeHintsConfiguration.SampleRuntimeHints()
                .registerHints(hints, getClass().getClassLoader());
    }

    @Test
    void registersJCacheRegionFactoryPublicConstructor() {
        assertThat(RuntimeHintsPredicates.reflection()
                        .onType(TypeReference.of("org.hibernate.cache.jcache.internal.JCacheRegionFactory"))
                        .withMemberCategory(MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS))
                .accepts(hints);
    }

    @Test
    void registersCaffeineJCacheConfigurationResources() {
        assertThat(RuntimeHintsPredicates.resource().forResource("application.conf"))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.resource().forResource("reference.conf"))
                .accepts(hints);
    }

    @Test
    void registersCaffeineGeneratedNodeConstructors() {
        assertThat(RuntimeHintsPredicates.reflection()
                        .onType(TypeReference.of("com.github.benmanes.caffeine.cache.PSMS"))
                        .withMemberCategory(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS))
                .accepts(hints);
    }

    @Test
    void registersCaffeineJCacheCopierConstructors() {
        assertThat(RuntimeHintsPredicates.reflection()
                        .onType(TypeReference.of("com.github.benmanes.caffeine.jcache.copy.JavaSerializationCopier"))
                        .withMemberCategory(MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS))
                .accepts(hints);
    }
}
