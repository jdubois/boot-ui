package io.github.jdubois.bootui.sample.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.hibernate.cache.internal.BasicCacheKeyImplementation;
import org.hibernate.cache.spi.entry.StandardCacheEntryImpl;
import org.hibernate.cache.spi.support.AbstractReadWriteAccess;
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

    @Test
    void registersTheProductReadWriteCacheSerializationGraph() throws Exception {
        var entryConstructor =
                StandardCacheEntryImpl.class.getDeclaredConstructor(Serializable[].class, String.class, Object.class);
        entryConstructor.setAccessible(true);
        var entry = entryConstructor.newInstance(
                new Serializable[] {true, "tools", "Console"}, "io.github.jdubois.bootui.sample.catalog.Product", null);
        var itemConstructor =
                AbstractReadWriteAccess.Item.class.getDeclaredConstructor(Object.class, Object.class, long.class);
        itemConstructor.setAccessible(true);
        var item = itemConstructor.newInstance(entry, null, 1L);
        var lock = item.lock(2L, new UUID(0L, 1L), 3L);
        var key = new BasicCacheKeyImplementation(1L, "io.github.jdubois.bootui.sample.catalog.Product", 1);
        Set<Class<?>> serializedTypes = new LinkedHashSet<>();
        for (Object value : new Object[] {key, item, lock}) {
            var bytes = new ByteArrayOutputStream();
            try (var stream = new ObjectOutputStream(bytes) {
                @Override
                protected void annotateClass(Class<?> type) {
                    serializedTypes.add(type);
                }
            }) {
                stream.writeObject(value);
            }
            try (var stream = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                assertThat(stream.readObject()).isInstanceOf(value.getClass());
            }
        }
        assertThat(serializedTypes)
                .contains(
                        BasicCacheKeyImplementation.class,
                        StandardCacheEntryImpl.class,
                        AbstractReadWriteAccess.Item.class,
                        AbstractReadWriteAccess.SoftLockImpl.class);
        assertThat(serializedTypes).allSatisfy(type -> {
            var hint = hints.reflection().getTypeHint(type);
            assertThat(hint)
                    .as("Caffeine store-by-value serialization of %s", type.getName())
                    .isNotNull();
            assertThat(hint.hasJavaSerialization())
                    .as("Java serialization enabled for %s", type.getName())
                    .isTrue();
        });
    }
}
