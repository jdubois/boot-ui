package io.github.jdubois.bootui.sample;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * Registers the GraalVM reachability metadata that the sample app needs but that Spring Boot's AOT
 * processing cannot infer automatically.
 *
 * <p>The following reachability metadata is registered:
 *
 * <ul>
 *   <li><b>SpEL reflection</b> — {@code unless = "#result.isEmpty()"} on {@link
 *       BootUiSampleApplication.SampleCatalog#activeProducts()} invokes a method reflectively. SpEL
 *       resolves {@code isEmpty()} against the {@link List} interface while the receiver is the
 *       concrete (JDK-internal) immutable list returned by {@link Stream#toList()} / {@link
 *       List#of}; the public methods of both are registered.
 *   <li><b>JDK serialization</b> — the default Redis cache serializer serializes cached values with
 *       {@link java.io.ObjectOutputStream}. The cached {@code List<ProductSummary>} graph (the
 *       record, its element wrappers and the immutable list type) is registered for serialization.
 *   <li><b>Hibernate persister reflection</b> — the {@code SampleBillingDocument}/{@code
 *       SampleReceipt} {@code @Inheritance(TABLE_PER_CLASS)} hierarchy is loaded through Hibernate's
 *       {@code UnionSubclassEntityPersister}, which Hibernate instantiates reflectively via its
 *       public 4-arg constructor. Neither Hibernate 7.2 nor Spring Boot's AOT hints register that
 *       constructor, so its public constructors are registered here.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints(NativeHintsConfiguration.SampleRuntimeHints.class)
class NativeHintsConfiguration {

    static class SampleRuntimeHints implements RuntimeHintsRegistrar {

        @Override
        public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
            Set<Class<?>> immutableCollections = new LinkedHashSet<>();
            immutableCollections.add(List.of().getClass());
            immutableCollections.add(List.of("a").getClass());
            immutableCollections.add(List.of("a", "b", "c").getClass());
            immutableCollections.add(Stream.of().toList().getClass());
            immutableCollections.add(Stream.of("a").toList().getClass());

            // SpEL resolves the invoked method against the collection interfaces, but calls it on
            // the concrete immutable type; register the public methods of both.
            Set<Class<?>> reflectiveInvoke = new LinkedHashSet<>();
            reflectiveInvoke.add(Collection.class);
            reflectiveInvoke.add(List.class);
            reflectiveInvoke.add(Set.class);
            reflectiveInvoke.add(Map.class);
            reflectiveInvoke.addAll(immutableCollections);
            for (Class<?> type : reflectiveInvoke) {
                hints.reflection().registerType(type, MemberCategory.INVOKE_PUBLIC_METHODS);
            }

            // JDK serialization graph for the cached List<ProductSummary> value.
            Set<Class<?>> serializable = new LinkedHashSet<>();
            serializable.add(BootUiSampleApplication.ProductSummary.class);
            serializable.add(Long.class);
            serializable.add(Integer.class);
            serializable.add(Boolean.class);
            serializable.add(Number.class);
            // Serial proxy used by List.of()/Stream#toList() immutable collections.
            serializable.add(serializationProxyClass());
            serializable.addAll(immutableCollections);
            for (Class<?> type : serializable) {
                hints.reflection().registerJavaSerialization(type);
            }

            // Hibernate resolves the entity persister for the TABLE_PER_CLASS demo hierarchy
            // (SampleBillingDocument / SampleReceipt) reflectively through the public
            // UnionSubclassEntityPersister(PersistentClass, EntityDataAccess, NaturalIdDataAccess,
            // RuntimeModelCreationContext) constructor. Spring Boot's AOT processing does not infer
            // this, so without the hint the native image fails to build the SessionFactory with a
            // NoSuchMethodException.
            hints.reflection()
                    .registerTypeIfPresent(
                            classLoader,
                            "org.hibernate.persister.entity.UnionSubclassEntityPersister",
                            MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
        }

        private static Class<?> serializationProxyClass() {
            try {
                return Class.forName("java.util.CollSer");
            } catch (ClassNotFoundException ex) {
                throw new IllegalStateException("Failed to load java.util.CollSer", ex);
            }
        }
    }
}
