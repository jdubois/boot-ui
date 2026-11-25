package io.github.jdubois.bootui.sample.config;

import io.github.jdubois.bootui.sample.catalog.ProductSummary;
import io.github.jdubois.bootui.sample.catalog.SampleCatalog;
import java.io.Serializable;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.hibernate.cache.internal.BasicCacheKeyImplementation;
import org.hibernate.cache.spi.entry.StandardCacheEntryImpl;
import org.hibernate.cache.spi.support.AbstractReadWriteAccess;
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
 *       SampleCatalog#activeProducts()} invokes a method reflectively. SpEL resolves {@code
 *       isEmpty()} against the {@link List} interface while the receiver is the concrete
 *       (JDK-internal) immutable list returned by {@link Stream#toList()} / {@link List#of};
 *       the public methods of both are registered.
 *   <li><b>JDK serialization</b> — the default Redis cache serializer serializes cached values with
 *       {@link java.io.ObjectOutputStream}. The cached {@code List<ProductSummary>} graph (the
 *       record, its element wrappers and the immutable list type) is registered for serialization.
 *       Caffeine's store-by-value JCache also serializes the {@code Product} READ_WRITE cache's
 *       simple identifier key, disassembled entry, cached item and soft lock graph.
 *   <li><b>Hibernate persister reflection</b> — the {@code SampleBillingDocument}/{@code
 *       SampleReceipt} {@code @Inheritance(TABLE_PER_CLASS)} hierarchy is loaded through Hibernate's
 *       {@code UnionSubclassEntityPersister}, which Hibernate instantiates reflectively via its
 *       public 4-arg constructor. Neither Hibernate 7.2 nor Spring Boot's AOT hints register that
 *       constructor, so its public constructors are registered here.
 *   <li><b>Hibernate JCache region factory reflection</b> — Hibernate resolves the configured {@code
 *       jcache} region factory and instantiates {@code JCacheRegionFactory} reflectively through its
 *       public no-arg constructor, which is registered here.
 *   <li><b>Caffeine JCache native support</b> — Typesafe Config loads the sample's {@code
 *       application.conf} and Caffeine's default {@code reference.conf} at runtime. Caffeine loads
 *       the generated {@code PSMS} node implementation and its configured
 *       {@code JavaSerializationCopier} by name. The resources and both types' constructors are
 *       included in the native image.
 *   <li><b>Hibernate identifier-array reflection</b> — Hibernate's multi-id entity loader
 *       reflectively instantiates a {@code UUID[]} array for the {@code SampleAuditEntry} {@code
 *       UUID} identifier. GraalVM requires the array type to be registered for reflective
 *       instantiation, so {@code UUID[]} is registered here.
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
            serializable.add(ProductSummary.class);
            serializable.add(Long.class);
            serializable.add(Integer.class);
            serializable.add(Boolean.class);
            serializable.add(Number.class);
            // Serial proxy used by List.of()/Stream#toList() immutable collections.
            serializable.add(serializationProxyClass());
            serializable.addAll(immutableCollections);
            // Caffeine's store-by-value copier serializes Product's simple Long identifier key and
            // Hibernate READ_WRITE entry/lock graph. These are sample-only cache implementation
            // details, not a reason to register every Hibernate type in the shared starter.
            serializable.add(BasicCacheKeyImplementation.class);
            serializable.add(StandardCacheEntryImpl.class);
            serializable.add(AbstractReadWriteAccess.Item.class);
            serializable.add(AbstractReadWriteAccess.SoftLockImpl.class);
            serializable.add(Serializable[].class);
            serializable.add(UUID.class);
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

            // Hibernate resolves the configured "jcache" region factory by name and reflectively
            // invokes its no-arg constructor. Spring Boot's AOT processing does not infer this
            // constructor, so the native executable otherwise fails while building the
            // SessionFactory.
            hints.reflection()
                    .registerTypeIfPresent(
                            classLoader,
                            "org.hibernate.cache.jcache.internal.JCacheRegionFactory",
                            MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);

            // Typesafe Config discovers these HOCON resources by name at runtime. Spring AOT does
            // not infer that lookup, so the native image otherwise starts without the "caffeine"
            // configuration root and fails while Hibernate creates its JCache regions.
            hints.resources().registerPattern("application.conf").registerPattern("reference.conf");

            // The sample's maximum-size JCache configuration selects Caffeine's generated PSMS
            // node implementation. NodeFactory loads it by name and invokes its package-private
            // constructor, which native-image cannot infer from the application configuration.
            hints.reflection()
                    .registerTypeIfPresent(
                            classLoader,
                            "com.github.benmanes.caffeine.cache.PSMS",
                            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);

            // Caffeine's default JCache store-by-value configuration names
            // JavaSerializationCopier in reference.conf. FactoryBuilder$ClassFactory creates it
            // through Class.newInstance(), so native-image needs its public no-arg constructor even
            // when the application did not name the type directly.
            hints.reflection()
                    .registerTypeIfPresent(
                            classLoader,
                            "com.github.benmanes.caffeine.jcache.copy.JavaSerializationCopier",
                            MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);

            // Hibernate builds a multi-id entity loader for every entity and, for the
            // SampleAuditEntry UUID identifier, reflectively instantiates a UUID[] array through
            // AbstractEntityPersister#buildMultiIdLoader. GraalVM requires the array type itself to
            // be registered for reflective instantiation; Spring Boot's AOT processing registers the
            // entity but not its identifier array type, so without this hint the native image fails
            // to build the SessionFactory with a MissingReflectionRegistrationError.
            hints.reflection().registerType(UUID[].class);
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
