package io.github.jdubois.bootui.autoconfigure.hibernate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import io.github.jdubois.bootui.engine.hibernate.HibernateAdvisorObservation;
import io.github.jdubois.bootui.engine.hibernate.HibernateFactorySettingsReader;
import io.github.jdubois.bootui.engine.hibernate.HibernatePersistenceUnitObservation;
import io.github.jdubois.bootui.engine.hibernate.HibernateRepositoryMethodModel;
import io.github.jdubois.bootui.engine.hibernate.HibernateRepositoryModel;
import io.github.jdubois.bootui.engine.hibernate.HibernateScanner;
import io.github.jdubois.bootui.engine.hibernate.JpaMetamodelReader;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.QueryHint;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.jpa.repository.support.JpaMetamodelEntityInformation;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.core.EntityInformation;
import org.springframework.data.repository.core.RepositoryInformation;
import org.springframework.data.repository.core.support.RepositoryFactoryInformation;
import org.springframework.data.repository.query.Param;

class SpringHibernateRepositoryDiscoveryTests {
    @Test
    void limitDoesNotShiftCollectionBindingsOrMatchAnUnrelatedPredicate() throws Exception {
        for (String name : List.of("limited", "unrelated")) {
            Method method = name.equals("limited")
                    ? ParameterRepository.class.getMethod(name, Limit.class, List.class)
                    : ParameterRepository.class.getMethod(name, Limit.class, Long.class, List.class);
            HibernateRepositoryMethodModel observed =
                    SpringHibernateRepositoryDiscovery.readMethod(ParameterRepository.class, Order.class, method);
            assertThat(observed.evidence().collectionParameterBindings())
                    .containsExactly(name.equals("limited") ? "?1" : "?2", ":ids");
            var factory = SpringHibernateAdvisorObservationSourceTests.factory("orders", 1);
            when(factory.getProperties()).thenReturn(Map.of("hibernate.query.in_clause_parameter_padding", "false"));
            var unit = new HibernatePersistenceUnitObservation(
                    "orders",
                    "orders",
                    JpaMetamodelReader.readEntities(List.of(factory)).entities(),
                    List.of(new HibernateRepositoryModel(
                            ParameterRepository.class.getName(), Order.class, List.of(observed), true)),
                    "7.4.5.Final",
                    HibernateFactorySettingsReader.read(factory, "orders", new ArrayList<>()),
                    null);
            var report = HibernateScanner.observing(
                            () -> new HibernateAdvisorObservation(List.of(unit), null, List.of()), Clock.systemUTC())
                    .scan();
            assertThat(report.results().stream().anyMatch(result -> result.id().equals("HIB-CONFIG-009")))
                    .isEqualTo(name.equals("limited"));
        }
    }

    @Test
    void dynamicProjectionParameterIsExcludedByNativeBindableMetadata() throws Exception {
        HibernateRepositoryMethodModel method = SpringHibernateRepositoryDiscovery.readMethod(
                ParameterRepository.class,
                Order.class,
                ParameterRepository.class.getMethod("projected", Class.class, List.class));
        assertThat(method.evidence().collectionParameterBindings()).containsExactly("?1", ":ids");
        assertThat(method.evidence().queryRewriter()).isTrue();
    }

    @Test
    void mergedNativeQueryAndComposedModifyingKeepTheirMetadata() throws Exception {
        HibernateRepositoryMethodModel nativeMethod = read("nativePage", Pageable.class);
        assertThat(nativeMethod.nativeQuery()).isTrue();
        assertThat(nativeMethod.query()).isEqualTo("select * from orders");
        assertThat(nativeMethod.countQuery()).isEqualTo("select count(*) from orders");
        assertThat(nativeMethod.returnType()).isEqualTo(Page.class);
        assertThat(nativeMethod.evidence().returnElementType()).isEqualTo(Order.class);
        assertThat(nativeMethod.evidence().queryRewriter()).isFalse();
        HibernateRepositoryMethodModel modifying = read("bulk");
        assertThat(modifying.modifying()).isTrue();
        assertThat(modifying.modifyingClearsAutomatically()).isTrue();
        assertThat(modifying.modifyingFlushesAutomatically()).isTrue();
        assertThat(modifying.query()).startsWith("delete ");
    }

    @Test
    void queryHintsGraphsBindingsAndProjectionAreAllowlisted() throws Exception {
        HibernateRepositoryMethodModel method = read("page", List.class, Pageable.class);
        assertThat(method.evidence().limitInMemory()).isTrue();
        assertThat(method.evidence().entityGraph()).isTrue();
        assertThat(method.evidence().entityGraphType()).isEqualTo("LOAD");
        assertThat(method.evidence().collectionParameterBindings()).containsExactly("?1", ":ids");
        assertThat(method.evidence().toString()).doesNotContain("sensitive-arbitrary-hint", "credential-value");
        assertThat(read("projection", Pageable.class).evidence().returnElementType())
                .isEqualTo(View.class);
        assertThat(read("dynamic", Class.class).evidence().queryRewriter()).isTrue();
        assertThat(read("nativeSlice", Pageable.class).returnType()).isEqualTo(Slice.class);
        assertThat(read("named", Pageable.class).evidence().namedQuery()).isTrue();
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void jpaEntityInformationProvesStoreEvenForCustomBaseInterfaceAndNoProductIsCreated() throws Exception {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        RepositoryFactoryInformation jpa = factory(true);
        RepositoryFactoryInformation nonJpa = factory(false);
        beans.registerSingleton("jpa", jpa);
        beans.registerSingleton("nonJpa", nonJpa);
        AtomicInteger lazyProducts = new AtomicInteger();
        RootBeanDefinition lazy = new RootBeanDefinition(UnrelatedFactory.class, () -> {
            lazyProducts.incrementAndGet();
            return new UnrelatedFactory();
        });
        lazy.setLazyInit(true);
        beans.registerBeanDefinition("unrelated", lazy);
        List<String> errors = new ArrayList<>();
        List<HibernateRepositoryModel> repositories = SpringHibernateRepositoryDiscovery.discover(beans, errors);
        assertThat(repositories).singleElement().satisfies(repository -> {
            assertThat(repository.repositoryInterface()).isEqualTo(CustomRepository.class.getName());
            assertThat(repository.standardJpaNewness()).isTrue();
            assertThat(repository.methods()).hasSize(8);
        });
        assertThat(errors).isEmpty();
        assertThat(lazyProducts).hasValue(0);
        verify((FactoryBean<?>) jpa, never()).getObject();
        verify((FactoryBean<?>) nonJpa, never()).getObject();
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void customSaveAndRepositoryWithoutSaveDoNotProveStandardNewness() {
        for (Class<?> repository : List.of(CustomRepository.class, ReadOnlyRepository.class)) {
            DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
            RepositoryFactoryInformation factory = factory(true);
            RepositoryInformation information = factory.getRepositoryInformation();
            when(information.getRepositoryInterface()).thenReturn((Class) repository);
            when(information.isCustomMethod(org.mockito.ArgumentMatchers.any(Method.class)))
                    .thenAnswer(invocation ->
                            invocation.<Method>getArgument(0).getName().equals("save"));
            beans.registerSingleton("repository", factory);
            assertThat(SpringHibernateRepositoryDiscovery.discover(beans, new ArrayList<>()))
                    .singleElement()
                    .satisfies(value -> assertThat(value.standardJpaNewness()).isFalse());
        }
    }

    @Test
    void unresolvedLazyFactoryRemainsUnknownWithoutInstantiation() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        AtomicInteger creations = new AtomicInteger();
        RootBeanDefinition lazy = new RootBeanDefinition(RepositoryFactoryInformation.class, () -> {
            creations.incrementAndGet();
            return factory(true);
        });
        lazy.setLazyInit(true);
        beans.registerBeanDefinition("lazyJpa", lazy);
        List<String> errors = new ArrayList<>();
        assertThat(SpringHibernateRepositoryDiscovery.discover(beans, errors)).isEmpty();
        assertThat(creations).hasValue(0);
        assertThat(errors).containsExactly("REPOSITORY_METADATA_UNAVAILABLE");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static RepositoryFactoryInformation factory(boolean jpa) {
        RepositoryFactoryInformation factory =
                mock(RepositoryFactoryInformation.class, withSettings().extraInterfaces(FactoryBean.class));
        RepositoryInformation information = mock(RepositoryInformation.class);
        when(factory.getRepositoryInformation()).thenReturn(information);
        when(factory.getEntityInformation())
                .thenReturn(jpa ? mock(JpaMetamodelEntityInformation.class) : mock(EntityInformation.class));
        when(information.getRepositoryInterface()).thenReturn((Class) CustomRepository.class);
        when(information.getDomainType()).thenReturn((Class) Order.class);
        doReturn(org.springframework.data.core.TypeInformation.of(Order.class))
                .when(information)
                .getDomainTypeInformation();
        when(information.getRepositoryBaseClass()).thenReturn((Class) SimpleJpaRepository.class);
        when(information.isQueryMethod(org.mockito.ArgumentMatchers.any(Method.class)))
                .thenAnswer(invocation ->
                        !invocation.<Method>getArgument(0).getName().equals("save"));
        when(information.isBaseClassMethod(org.mockito.ArgumentMatchers.any(Method.class)))
                .thenAnswer(invocation ->
                        invocation.<Method>getArgument(0).getName().equals("save"));
        when(((FactoryBean) factory).getObjectType()).thenReturn(CustomRepository.class);
        return factory;
    }

    private static HibernateRepositoryMethodModel read(String name, Class<?>... parameters) throws Exception {
        return SpringHibernateRepositoryDiscovery.readMethod(
                CustomRepository.class, Order.class, CustomRepository.class.getMethod(name, parameters));
    }

    @Entity
    static class Order {
        @Id
        Long id;
    }

    interface View {
        Long getId();
    }

    interface ParameterRepository extends Repository<Order, Long> {
        @Query("select o from Order o where o.id in ?1")
        List<Order> limited(Limit limit, List<Long> ids);

        @Query("select o from Order o where o.id in ?1")
        List<Order> unrelated(Limit limit, Long scalar, List<Long> ids);

        @Query("select o from Order o where o.id in ?1")
        <T> List<T> projected(Class<T> projection, List<Long> ids);
    }

    interface CustomRepository extends Repository<Order, Long> {
        Order save(Order entity);

        @NativeQuery(value = "select * from orders", countQuery = "select count(*) from orders")
        Page<Order> nativePage(Pageable pageable);

        @NativeQuery("select * from orders")
        Slice<Order> nativeSlice(Pageable pageable);

        @DeleteOrders
        int bulk();

        @Query("select o from Order o where o.id in :ids")
        @EntityGraph(type = EntityGraph.EntityGraphType.LOAD, attributePaths = "items")
        @QueryHints({
            @QueryHint(name = "org.hibernate.limitInMemory", value = "true"),
            @QueryHint(name = "sensitive-arbitrary-hint", value = "credential-value")
        })
        Page<Order> page(@Param("ids") List<Long> ids, Pageable pageable);

        @Query("select o from Order o")
        Page<View> projection(Pageable pageable);

        @Query("select o from Order o")
        <T> List<T> dynamic(Class<T> projection);

        @Query(name = "Order.findNamed", countName = "Order.countNamed")
        Page<Order> named(Pageable pageable);

        List<Order> findByIdIn(List<Long> ids);

        default String customMethod() {
            return "not a query";
        }
    }

    interface ReadOnlyRepository extends Repository<Order, Long> {}

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @Query("delete from Order o")
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @interface DeleteOrders {}

    static class UnrelatedFactory implements FactoryBean<Object> {
        public Object getObject() {
            throw new AssertionError("Must not create products");
        }

        public Class<?> getObjectType() {
            return Object.class;
        }
    }
}
