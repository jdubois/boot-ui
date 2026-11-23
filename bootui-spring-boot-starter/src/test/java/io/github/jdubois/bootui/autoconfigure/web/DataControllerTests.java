package io.github.jdubois.bootui.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.io.Serializable;
import java.lang.reflect.Method;
import org.aopalliance.intercept.MethodInterceptor;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.target.LazyInitTargetSource;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartFactoryBean;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.config.BootstrapMode;
import org.springframework.data.repository.core.RepositoryInformation;
import org.springframework.data.repository.core.support.RepositoryFactoryInformation;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP-level tests for {@link DataController}.
 *
 * <p>Covers the Spring Data repository list and detail endpoints, including the
 * empty-context cases (no bean factory, no repository beans) and the three
 * supported detail lookup keys (bean name, fully qualified interface name,
 * simple interface name).</p>
 */
class DataControllerTests {

    private static DefaultListableBeanFactory beanFactoryWithRepository(String beanName, Class<?> repositoryInterface) {
        var factory = new DefaultListableBeanFactory();
        RepositoryFactoryInformation<?, ?> info = mock(RepositoryFactoryInformation.class);
        RepositoryInformation information = mock(RepositoryInformation.class);

        factory.registerSingleton(beanName, info);
        when(info.getRepositoryInformation()).thenReturn(information);

        // Mockito cannot return Class<?> from raw-typed getters without an unchecked
        // cast, so use doReturn-style stubbing via thenAnswer.
        when(information.getRepositoryInterface()).thenAnswer(invocation -> repositoryInterface);
        when(information.getDomainType()).thenAnswer(invocation -> Widget.class);
        when(information.getIdType()).thenAnswer(invocation -> Long.class);
        when(information.getRepositoryBaseClass()).thenAnswer(invocation -> null);

        // Classify methods: findByName is a derived query, everything else (Object methods
        // are filtered upstream) is treated as a base CRUD method.
        when(information.isCustomMethod(any(Method.class))).thenReturn(false);
        when(information.isBaseClassMethod(any(Method.class))).thenAnswer(invocation -> {
            Method m = invocation.getArgument(0);
            return !"findByName".equals(m.getName());
        });
        when(information.isQueryMethod(any(Method.class))).thenAnswer(invocation -> {
            Method m = invocation.getArgument(0);
            return "findByName".equals(m.getName());
        });

        return factory;
    }

    private static DataController controllerFor(ListableBeanFactory factory) {
        DataController controller = new DataController();
        controller.setBeanFactory(factory);
        return controller;
    }

    @Test
    void repositoriesReturnsEmptyReportWhenNoBeanFactory() throws Exception {
        MockMvc mvc = standaloneSetup(new DataController()).build();

        mvc.perform(get("/bootui/api/data/repositories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.springDataPresent").value(true))
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.repositories").isArray())
                .andExpect(jsonPath("$.repositories").isEmpty());
    }

    @Test
    void repositoriesReturnsEmptyReportWhenNoRepositoryBeans() throws Exception {
        MockMvc mvc =
                standaloneSetup(controllerFor(new DefaultListableBeanFactory())).build();

        mvc.perform(get("/bootui/api/data/repositories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.springDataPresent").value(true))
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.repositories").isEmpty());
    }

    @Test
    void repositoriesReturnsSummaryForDiscoveredRepository() throws Exception {
        ListableBeanFactory factory = beanFactoryWithRepository("widgetRepository", WidgetRepository.class);

        MockMvc mvc = standaloneSetup(controllerFor(factory)).build();

        mvc.perform(get("/bootui/api/data/repositories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.repositories[0].beanName").value("widgetRepository"))
                .andExpect(jsonPath("$.repositories[0].repositoryInterface").value(WidgetRepository.class.getName()))
                .andExpect(jsonPath("$.repositories[0].domainType").value(Widget.class.getName()))
                .andExpect(jsonPath("$.repositories[0].idType").value(Long.class.getName()))
                // WidgetRepository lives outside the spring-data package tree, so it
                // resolves to the generic store module marker.
                .andExpect(jsonPath("$.repositories[0].storeModule").value("GENERIC"))
                .andExpect(jsonPath("$.repositories[0].queryMethodCount").value(1))
                .andExpect(jsonPath("$.repositories[0].fragmentCount").value(0));
    }

    @Test
    void repositoryDetailLooksUpByBeanName() throws Exception {
        ListableBeanFactory factory = beanFactoryWithRepository("widgetRepository", WidgetRepository.class);
        MockMvc mvc = standaloneSetup(controllerFor(factory)).build();

        mvc.perform(get("/bootui/api/data/repositories/widgetRepository"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.beanName").value("widgetRepository"))
                .andExpect(jsonPath("$.repositoryInterface").value(WidgetRepository.class.getName()))
                .andExpect(jsonPath("$.methods").isArray())
                .andExpect(jsonPath("$.methods[?(@.name=='findByName')]").exists())
                .andExpect(jsonPath("$.methods[?(@.name=='findByName')].origin").value("QUERY"));
    }

    @Test
    void repositoryDetailLooksUpByFullyQualifiedInterfaceName() throws Exception {
        ListableBeanFactory factory = beanFactoryWithRepository("widgetRepository", WidgetRepository.class);
        MockMvc mvc = standaloneSetup(controllerFor(factory)).build();

        mvc.perform(get("/bootui/api/data/repositories/" + WidgetRepository.class.getName()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositoryInterface").value(WidgetRepository.class.getName()));
    }

    @Test
    void repositoryDetailLooksUpBySimpleInterfaceName() throws Exception {
        ListableBeanFactory factory = beanFactoryWithRepository("widgetRepository", WidgetRepository.class);
        MockMvc mvc = standaloneSetup(controllerFor(factory)).build();

        mvc.perform(get("/bootui/api/data/repositories/WidgetRepository"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositoryInterface").value(WidgetRepository.class.getName()));
    }

    @Test
    void repositoryDetailReturnsNotFoundForUnknownRepository() throws Exception {
        ListableBeanFactory factory = beanFactoryWithRepository("widgetRepository", WidgetRepository.class);
        MockMvc mvc = standaloneSetup(controllerFor(factory)).build();

        mvc.perform(get("/bootui/api/data/repositories/UnknownRepository")).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/widgetRepository"})
    void readsDoNotInitializeLazyJpaRepositories(String suffix) throws Exception {
        try (var context = new AnnotationConfigApplicationContext(LazyJpaRepositories.class)) {
            var factory = context.getBeanFactory();
            assertThat(factory.getBeanDefinition("widgetRepository").isLazyInit())
                    .isTrue();
            assertThat(factory.containsSingleton("widgetRepository")).isFalse();
            MockMvc mvc = standaloneSetup(controllerFor(factory)).build();

            var response = mvc.perform(get("/bootui/api/data/repositories" + suffix));
            assertThat(factory.containsSingleton("widgetRepository"))
                    .as("BootUI must not initialize the JPA repository factory")
                    .isFalse();
            if (suffix.isEmpty()) {
                response.andExpect(status().isOk())
                        .andExpect(jsonPath("$.repositories").isEmpty());
            } else {
                response.andExpect(status().isNotFound());
            }

            // The application uses the repository independently; BootUI can now read real Spring Data metadata.
            context.getBean("widgetRepository", WidgetRepository.class);
            mvc.perform(get("/bootui/api/data/repositories"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1))
                    .andExpect(jsonPath("$.repositories[0].beanName").value("widgetRepository"));
            mvc.perform(get("/bootui/api/data/repositories/widgetRepository"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.methods[?(@.name=='findByName')].origin")
                            .value("QUERY"));
        }
    }

    @Test
    void readsDoNotQueryAnUnrelatedInitializedSmartFactory() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(DataController.class)) {
            var controller = context.getBean(DataController.class);
            var unrelated = mock(SmartFactoryBean.class);
            context.getBeanFactory().registerSingleton("unrelatedFactory", unrelated);
            MockMvc mvc = standaloneSetup(controller).build();

            mvc.perform(get("/bootui/api/data/repositories")).andExpect(status().isOk());
            mvc.perform(get("/bootui/api/data/repositories/missing")).andExpect(status().isNotFound());

            verifyNoInteractions(unrelated);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void readsDoNotResolveALazyRepositoryFactoryTarget(boolean opaque) throws Exception {
        try (var context = new AnnotationConfigApplicationContext(LazyJpaRepositories.class)) {
            var factory = context.getBeanFactory();
            var source = new LazyInitTargetSource();
            source.setTargetBeanName("&widgetRepository");
            source.setTargetClass(RepositoryFactoryInformation.class);
            source.setBeanFactory(factory);
            var proxy = new ProxyFactory();
            proxy.setInterfaces(RepositoryFactoryInformation.class, Advised.class);
            proxy.setOpaque(opaque);
            proxy.setTargetSource(source);
            factory.registerSingleton("lazyMetadataProxy", proxy.getProxy());
            assertThat(factory.containsSingleton("widgetRepository")).isFalse();
            MockMvc mvc = standaloneSetup(controllerFor(factory)).build();

            var response = mvc.perform(get("/bootui/api/data/repositories"));
            assertThat(factory.containsSingleton("widgetRepository"))
                    .as("BootUI must not resolve the proxy's lazy repository factory target")
                    .isFalse();
            response.andExpect(status().isOk())
                    .andExpect(jsonPath("$.repositories").isEmpty());
            mvc.perform(get("/bootui/api/data/repositories/lazyMetadataProxy")).andExpect(status().isNotFound());
            assertThat(factory.containsSingleton("widgetRepository")).isFalse();
        }
    }

    @Test
    void omitsMetadataOnlyReachableThroughAnInitializedProxy() throws Exception {
        var source = beanFactoryWithRepository("widgetRepository", WidgetRepository.class);
        var proxy = new ProxyFactory(source.getSingleton("widgetRepository"));
        proxy.addAdvice((MethodInterceptor) invocation -> {
            throw new AssertionError("An inventory read must not invoke repository factory advice");
        });
        var factory = new DefaultListableBeanFactory();
        factory.registerSingleton("widgetRepository", proxy.getProxy());
        MockMvc mvc = standaloneSetup(controllerFor(factory)).build();

        mvc.perform(get("/bootui/api/data/repositories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositories").isEmpty());
        mvc.perform(get("/bootui/api/data/repositories/WidgetRepository")).andExpect(status().isNotFound());
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableJpaRepositories(
            basePackageClasses = DataControllerTests.class,
            considerNestedRepositories = true,
            bootstrapMode = BootstrapMode.LAZY)
    static class LazyJpaRepositories {
        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory() {
            var dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:bootui-passive-data");
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setManagedTypes(PersistenceManagedTypes.of(Widget.class.getName()));
            return factory;
        }
    }

    /**
     * Test-only repository fixture used to exercise method classification.
     */
    @Component("widgetRepository")
    public interface WidgetRepository extends Repository<Widget, Long> {
        Widget findByName(String name);
    }

    /**
     * Test-only domain class for the synthetic repository fixture.
     */
    @Entity
    public static class Widget implements Serializable {
        @Id
        private Long id;

        private String name;

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }
    }
}
