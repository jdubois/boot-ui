package io.github.jdubois.bootui.autoconfigure.hibernate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.engine.hibernate.HibernateAdvisorObservation;
import io.github.jdubois.bootui.engine.hibernate.HibernateApplicationFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateFactorySettings;
import io.github.jdubois.bootui.engine.hibernate.HibernateFactorySettingsReader;
import io.github.jdubois.bootui.engine.hibernate.HibernateObservationDiagnostic;
import io.github.jdubois.bootui.engine.hibernate.HibernateScanner;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.boot.spi.SessionFactoryOptions;
import org.hibernate.cache.spi.CacheImplementor;
import org.hibernate.cache.spi.RegionFactory;
import org.hibernate.engine.jdbc.connections.spi.ConnectionProvider;
import org.hibernate.engine.jdbc.spi.JdbcServices;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.service.spi.ServiceRegistryImplementor;
import org.hibernate.stat.spi.StatisticsImplementor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockServletContext;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewFilter;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.servlet.handler.WebRequestHandlerInterceptorAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

class SpringHibernateAdvisorObservationSourceTests {
    @Test
    void failingLazyMiddleFactoryDoesNotHideLaterUnitsInEitherOrder() {
        for (List<String> order : List.of(List.of("first", "failed", "last"), List.of("last", "failed", "first"))) {
            DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
            AtomicInteger resolutions = new AtomicInteger();
            for (String name : order) {
                RootBeanDefinition definition = new RootBeanDefinition(EntityManagerFactory.class, () -> {
                    resolutions.incrementAndGet();
                    if ("failed".equals(name)) throw new IllegalStateException("credential-value");
                    return factory(name, 1);
                });
                definition.setLazyInit(true);
                beans.registerBeanDefinition(name, definition);
            }
            SpringHibernateAdvisorObservationSource source = source(beans, new MockEnvironment());
            assertThat(resolutions).hasValue(0);
            HibernateAdvisorObservation observation = source.observe();
            assertThat(resolutions).hasValue(3);
            assertThat(observation.units())
                    .extracting(unit -> unit.label())
                    .containsExactly(order.get(0), order.get(2));
            assertThat(observation.diagnostics())
                    .containsExactly(new HibernateObservationDiagnostic(
                            "unit-2", HibernateObservationDiagnostic.Reason.FACTORY_UNAVAILABLE));
            var report = HibernateScanner.observing(() -> observation, Clock.systemUTC())
                    .scan();
            assertThat(report.scan().status()).isEqualTo("PARTIAL");
            assertThat(report.scan().message()).doesNotContain("credential-value");
            assertThat(report.results())
                    .filteredOn(result -> result.id().equals("HIB-CONFIG-004"))
                    .singleElement()
                    .satisfies(result -> {
                        assertThat(result.violationCount()).isEqualTo(2);
                        assertThat(result.sampleViolations()).anyMatch(sample -> sample.startsWith("[first]"));
                        assertThat(result.sampleViolations()).anyMatch(sample -> sample.startsWith("[last]"));
                    });
        }
    }

    @Test
    void observesDivergentNativeOptionsRatherThanEnvironmentAndDeduplicatesUnwrappedIdentity() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        SessionFactoryImplementor first = factory("first", 1);
        SessionFactoryImplementor second = factory("second", 25);
        EntityManagerFactory alias = mock(EntityManagerFactory.class);
        when(alias.unwrap(SessionFactoryImplementor.class)).thenReturn(first);
        beans.registerSingleton("first", first);
        beans.registerSingleton("second", second);
        beans.registerSingleton("alias", alias);
        MockEnvironment environment = new MockEnvironment().withProperty("hibernate.jdbc.batch_size", "999");
        HibernateAdvisorObservation observation = source(beans, environment).observe();
        assertThat(observation.units()).hasSize(2);
        assertThat(observation.units())
                .extracting(unit -> unit.settings().jdbcBatchSize())
                .containsExactly(1, 25);
        assertThat(observation.units())
                .extracting(unit -> unit.settings().defaultBatchFetchSize())
                .containsOnly(16);
        assertThat(observation.units())
                .extracting(unit -> unit.settings().queryCache())
                .containsOnly(true);
        assertThat(observation.units())
                .extracting(unit -> unit.settings().regionFactory())
                .containsOnly(HibernateFactorySettings.RegionFactory.AVAILABLE);
        assertThat(observation.diagnostics()).isEmpty();
        verify(first, times(1)).getSessionFactoryOptions();
    }

    @Test
    void osivNeedsServletActivationEvidenceNotJustPropertyDefaults() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        MockEnvironment environment = new MockEnvironment();
        try (GenericWebApplicationContext servlet = new GenericWebApplicationContext()) {
            servlet.setServletContext(filterContext(Map.of()));
            SpringHibernateAdvisorObservationSource source =
                    new SpringHibernateAdvisorObservationSource(beans, environment, servlet);
            assertThat(source.observe().application().openInView())
                    .isEqualTo(HibernateApplicationFacts.OpenInView.UNKNOWN);
            environment.setProperty("spring.jpa.open-in-view", "true");
            assertThat(source.observe().application().openInView())
                    .isEqualTo(HibernateApplicationFacts.OpenInView.UNKNOWN);
            environment.setProperty("spring.jpa.open-in-view", "false");
            assertThat(source.observe().application().openInView())
                    .isEqualTo(HibernateApplicationFacts.OpenInView.DISABLED);
            beans.registerSingleton("osiv", new OpenEntityManagerInViewInterceptor());
            assertThat(source.observe().application().openInView())
                    .isEqualTo(HibernateApplicationFacts.OpenInView.UNKNOWN);
            assertThat(source(beans, environment).observe().application().openInView())
                    .isEqualTo(HibernateApplicationFacts.OpenInView.NOT_APPLICABLE);
        }
    }

    @Test
    void actualAdaptedInterceptorsIncludingMappedWrappersProveActivationAndKeepFixedSeverity() {
        for (boolean mapped : List.of(false, true)) {
            DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
            beans.registerSingleton("factory", factory("orders", 25));
            MockEnvironment environment = new MockEnvironment().withProperty("spring.jpa.open-in-view", "false");
            environment.setActiveProfiles("prod");
            try (GenericWebApplicationContext servlet = new GenericWebApplicationContext(beans)) {
                servlet.refresh();
                OpenEntityManagerInViewInterceptor osiv = new OpenEntityManagerInViewInterceptor();
                RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
                mapping.setInterceptors(mapped ? new MappedInterceptor(new String[] {"/orders/**"}, osiv) : osiv);
                mapping.setApplicationContext(servlet);
                beans.registerSingleton("mapping", mapping);
                HibernateAdvisorObservation observation =
                        new SpringHibernateAdvisorObservationSource(beans, environment, servlet).observe();
                assertThat(observation.application().openInView())
                        .isEqualTo(HibernateApplicationFacts.OpenInView.ENABLED);
                assertThat(HibernateScanner.observing(() -> observation, Clock.systemUTC())
                                .scan()
                                .results())
                        .filteredOn(result -> result.id().equals("HIB-CONFIG-001"))
                        .singleElement()
                        .satisfies(result -> assertThat(result.severity()).isEqualTo("MEDIUM"));
            }
        }
    }

    @Test
    void plainFiltersAndDisabledOrUnregisteredFilterRegistrationBeansRemainUnknown() {
        for (String kind : List.of("plain", "disabled", "unregistered")) {
            DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
            OpenEntityManagerInViewFilter filter = new OpenEntityManagerInViewFilter();
            if ("plain".equals(kind)) {
                beans.registerSingleton("osiv", filter);
            } else {
                FilterRegistrationBean<OpenEntityManagerInViewFilter> registration =
                        new FilterRegistrationBean<>(filter);
                registration.setEnabled(!"disabled".equals(kind));
                beans.registerSingleton("osivRegistration", registration);
            }
            try (GenericWebApplicationContext servlet = new GenericWebApplicationContext()) {
                servlet.setServletContext(filterContext(Map.of()));
                var source = new SpringHibernateAdvisorObservationSource(
                        beans, new MockEnvironment().withProperty("spring.jpa.open-in-view", "false"), servlet);
                assertThat(source.observe().application().openInView())
                        .as(kind)
                        .isEqualTo(HibernateApplicationFacts.OpenInView.UNKNOWN);
            }
        }
    }

    @Test
    void filtersNeedActualServletContainerRegistrationAndANonEmptyMapping() {
        for (String mapping : List.of("url", "servlet", "unmapped", "unrelated")) {
            FilterRegistration registration = mock(FilterRegistration.class);
            when(registration.getClassName())
                    .thenReturn(
                            "unrelated".equals(mapping)
                                    ? org.springframework.web.filter.CharacterEncodingFilter.class.getName()
                                    : OpenEntityManagerInViewFilter.class.getName());
            when(registration.getUrlPatternMappings())
                    .thenReturn("url".equals(mapping) || "unrelated".equals(mapping) ? List.of("/*") : List.of());
            when(registration.getServletNameMappings())
                    .thenReturn("servlet".equals(mapping) ? List.of("dispatcherServlet") : List.of());
            try (GenericWebApplicationContext servlet = new GenericWebApplicationContext()) {
                servlet.setServletContext(filterContext(Map.of("registeredOsiv", registration)));
                var source = new SpringHibernateAdvisorObservationSource(
                        new DefaultListableBeanFactory(), new MockEnvironment(), servlet);
                assertThat(source.observe().application().openInView())
                        .as(mapping)
                        .isEqualTo(
                                "url".equals(mapping) || "servlet".equals(mapping)
                                        ? HibernateApplicationFacts.OpenInView.ENABLED
                                        : HibernateApplicationFacts.OpenInView.UNKNOWN);
            }
        }
    }

    @Test
    void lazyMappingsFiltersAndRegistrationBeansAreNotInitialized() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        AtomicInteger initializations = new AtomicInteger();
        for (Class<?> type : List.of(
                RequestMappingHandlerMapping.class,
                OpenEntityManagerInViewFilter.class,
                FilterRegistrationBean.class)) {
            RootBeanDefinition definition = new RootBeanDefinition(type, () -> {
                initializations.incrementAndGet();
                throw new IllegalStateException("must not initialize");
            });
            definition.setLazyInit(true);
            beans.registerBeanDefinition(type.getSimpleName(), definition);
        }
        try (GenericWebApplicationContext servlet = new GenericWebApplicationContext(beans)) {
            servlet.setServletContext(filterContext(Map.of()));
            servlet.refresh();
            var source = new SpringHibernateAdvisorObservationSource(beans, new MockEnvironment(), servlet);
            assertThat(source.observe().application().openInView())
                    .isEqualTo(HibernateApplicationFacts.OpenInView.UNKNOWN);
            assertThat(initializations).hasValue(0);
        }
    }

    @Test
    void unreadableMappingsDoNotHideLaterRegistrationsOrExposeTheirFailure() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        RequestMappingHandlerMapping unreadable = mock(RequestMappingHandlerMapping.class);
        when(unreadable.getAdaptedInterceptors()).thenThrow(new IllegalStateException("credential-value"));
        beans.registerSingleton("unreadable", unreadable);
        try (GenericWebApplicationContext servlet = new GenericWebApplicationContext(beans)) {
            servlet.refresh();
            var source = new SpringHibernateAdvisorObservationSource(beans, new MockEnvironment(), servlet);
            assertThat(source.observe().application().openInView())
                    .isEqualTo(HibernateApplicationFacts.OpenInView.UNKNOWN);
            RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
            mapping.setInterceptors(new OpenEntityManagerInViewInterceptor());
            mapping.setApplicationContext(servlet);
            beans.registerSingleton("readable", mapping);
            assertThat(source.observe().application().openInView())
                    .isEqualTo(HibernateApplicationFacts.OpenInView.ENABLED);
            assertThat(source.observe().toString()).doesNotContain("credential-value");
        }
    }

    @Test
    void interceptorInspectionIsBoundedAndDoesNotGuessAboutEntriesBeyondTheLimit() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        RequestMappingHandlerMapping mapping = mock(RequestMappingHandlerMapping.class);
        HandlerInterceptor[] interceptors = new HandlerInterceptor[129];
        interceptors[128] = new WebRequestHandlerInterceptorAdapter(new OpenEntityManagerInViewInterceptor());
        when(mapping.getAdaptedInterceptors()).thenReturn(interceptors);
        beans.registerSingleton("mapping", mapping);
        try (GenericWebApplicationContext servlet = new GenericWebApplicationContext()) {
            var source = new SpringHibernateAdvisorObservationSource(beans, new MockEnvironment(), servlet);
            assertThat(source.observe().application().openInView())
                    .isEqualTo(HibernateApplicationFacts.OpenInView.UNKNOWN);
            interceptors[0] = interceptors[128];
            assertThat(source.observe().application().openInView())
                    .isEqualTo(HibernateApplicationFacts.OpenInView.ENABLED);
        }
    }

    @Test
    void customAdaptersDoNotProveTheirStoredOsivDelegateIsInvoked() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        AtomicInteger callbacks = new AtomicInteger();
        var customAdapter = new WebRequestHandlerInterceptorAdapter(new OpenEntityManagerInViewInterceptor()) {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                callbacks.incrementAndGet();
                return true;
            }
        };
        RequestMappingHandlerMapping mapping = mock(RequestMappingHandlerMapping.class);
        when(mapping.getAdaptedInterceptors()).thenReturn(new HandlerInterceptor[] {customAdapter});
        beans.registerSingleton("mapping", mapping);
        try (GenericWebApplicationContext servlet = new GenericWebApplicationContext()) {
            servlet.setServletContext(filterContext(Map.of()));
            var source = new SpringHibernateAdvisorObservationSource(
                    beans, new MockEnvironment().withProperty("spring.jpa.open-in-view", "false"), servlet);
            assertThat(source.observe().application().openInView())
                    .isEqualTo(HibernateApplicationFacts.OpenInView.UNKNOWN);
            assertThat(callbacks).hasValue(0);
        }
    }

    @Test
    void oversizedBeanInventoriesCannotBeUsedToClaimOsivIsDisabled() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        for (int index = 0; index < 4097; index++) beans.registerSingleton("unrelated-" + index, new Object());
        try (GenericWebApplicationContext servlet = new GenericWebApplicationContext()) {
            servlet.setServletContext(filterContext(Map.of()));
            var source = new SpringHibernateAdvisorObservationSource(
                    beans, new MockEnvironment().withProperty("spring.jpa.open-in-view", "false"), servlet);
            assertThat(source.observe().application().openInView())
                    .isEqualTo(HibernateApplicationFacts.OpenInView.UNKNOWN);
        }
    }

    @Test
    void unreadableFilterRegistrationsRemainUnknownButDoNotHideLaterReadableRegistrations() {
        FilterRegistration unreadable = mock(FilterRegistration.class);
        when(unreadable.getClassName()).thenThrow(new IllegalStateException("credential-value"));
        FilterRegistration readable = mock(FilterRegistration.class);
        when(readable.getClassName()).thenReturn(OpenEntityManagerInViewFilter.class.getName());
        when(readable.getUrlPatternMappings()).thenReturn(List.of("/*"));
        Map<String, FilterRegistration> registrations = new LinkedHashMap<>();
        registrations.put("unreadable", unreadable);
        try (GenericWebApplicationContext servlet = new GenericWebApplicationContext()) {
            servlet.setServletContext(filterContext(registrations));
            var source = new SpringHibernateAdvisorObservationSource(
                    new DefaultListableBeanFactory(), new MockEnvironment(), servlet);
            assertThat(source.observe().application().openInView())
                    .isEqualTo(HibernateApplicationFacts.OpenInView.UNKNOWN);
            registrations.put("readable", readable);
            assertThat(source.observe().application().openInView())
                    .isEqualTo(HibernateApplicationFacts.OpenInView.ENABLED);
            assertThat(source.observe().toString()).doesNotContain("credential-value");
        }
    }

    @Test
    void servletAndMvcApisAreOptionalAndMissingRegistrationApisRemainUnknown() throws Exception {
        for (String missing : List.of("org.springframework.web.servlet", "jakarta.servlet")) {
            try (FilteredClassLoader loader = new FilteredClassLoader(missing);
                    GenericWebApplicationContext servlet = new GenericWebApplicationContext()) {
                servlet.setClassLoader(loader);
                ServletContext container = filterContext(Map.of());
                servlet.setServletContext(container);
                var source = new SpringHibernateAdvisorObservationSource(
                        new DefaultListableBeanFactory(), new MockEnvironment(), servlet);
                assertThat(source.observe().application().openInView())
                        .isEqualTo(HibernateApplicationFacts.OpenInView.UNKNOWN);
                if ("jakarta.servlet".equals(missing))
                    verify(container, never()).getFilterRegistrations();
            }
        }
        try (FilteredClassLoader loader = new FilteredClassLoader("org.springframework.web", "jakarta.servlet");
                GenericApplicationContext nonServlet = new GenericApplicationContext()) {
            nonServlet.setClassLoader(loader);
            var source = new SpringHibernateAdvisorObservationSource(
                    new DefaultListableBeanFactory(), new MockEnvironment(), nonServlet);
            assertThat(source.observe().application().openInView())
                    .isEqualTo(HibernateApplicationFacts.OpenInView.NOT_APPLICABLE);
        }
    }

    private static ServletContext filterContext(Map<String, ? extends FilterRegistration> registrations) {
        ServletContext context = spy(new MockServletContext());
        org.mockito.Mockito.doReturn(registrations).when(context).getFilterRegistrations();
        return context;
    }

    @Test
    void observedDialectOverridesStaleBootstrapDialectWithoutOpeningConnections() {
        SessionFactoryImplementor factory = factory("orders", 25);
        JdbcServices jdbc = mock(JdbcServices.class);
        when(factory.getJdbcServices()).thenReturn(jdbc);
        when(jdbc.getDialect()).thenReturn(mock(org.hibernate.dialect.PostgreSQLDialect.class));
        when(factory.getProperties()).thenReturn(Map.of("hibernate.dialect", "org.hibernate.dialect.OracleDialect"));
        assertThat(HibernateFactorySettingsReader.read(factory, "orders", new ArrayList<>())
                        .oracle())
                .isFalse();
        when(jdbc.getDialect()).thenReturn(mock(org.hibernate.dialect.OracleDialect.class));
        assertThat(HibernateFactorySettingsReader.read(factory, "orders", new ArrayList<>())
                        .oracle())
                .isTrue();
    }

    @Test
    void ambiguousDomainIsNeverAssignedToTheFirstFactory() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("first", factory("first", 1));
        beans.registerSingleton("second", factory("second", 25));
        beans.registerSingleton("repository", SpringHibernateRepositoryDiscoveryTests.factory(true));
        HibernateAdvisorObservation observation =
                source(beans, new MockEnvironment()).observe();
        assertThat(observation.units())
                .allSatisfy(unit -> assertThat(unit.repositories()).isEmpty());
        assertThat(observation.diagnostics())
                .extracting(HibernateObservationDiagnostic::reason)
                .contains(HibernateObservationDiagnostic.Reason.AMBIGUOUS_REPOSITORY_UNIT);
    }

    @Test
    void failedIndividualGetterKeepsMappingsAndOtherSettingsWithControlledDiagnostic() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        SessionFactoryImplementor factory = factory("orders", 25);
        when(factory.getSessionFactoryOptions().isOrderInsertsEnabled())
                .thenThrow(new NoClassDefFoundError("credential"));
        beans.registerSingleton("factory", factory);
        HibernateAdvisorObservation observation =
                source(beans, new MockEnvironment()).observe();
        assertThat(observation.units()).singleElement().satisfies(unit -> {
            assertThat(unit.entities()).hasSize(1);
            assertThat(unit.settings().jdbcBatchSize()).isEqualTo(25);
            assertThat(unit.settings().orderInserts()).isNull();
            assertThat(unit.settings().orderUpdates()).isTrue();
        });
        assertThat(observation.diagnostics())
                .extracting(HibernateObservationDiagnostic::reason)
                .contains(HibernateObservationDiagnostic.Reason.FACTORY_SETTING_UNAVAILABLE);
        assertThat(observation.diagnostics().toString()).doesNotContain("credential");
    }

    @Test
    void nativeSchemaVocabularyAndScalarAllowlistNeverRetainArbitraryProperties() {
        SessionFactoryImplementor factory = factory("orders", 25);
        when(factory.getProperties())
                .thenReturn(Map.of(
                        "jakarta.persistence.schema-generation.database.action", "create",
                        "hibernate.hbm2ddl.auto", "create",
                        "hibernate.connection.password", "credential",
                        "hibernate.connection.url", "jdbc:secret"));
        HibernateFactorySettings settings = HibernateFactorySettingsReader.read(factory, "orders", new ArrayList<>());
        assertThat(settings.schemaAction()).isEqualTo(HibernateFactorySettings.SchemaAction.CREATE_ONLY);
        assertThat(settings.toString()).doesNotContain("credential", "jdbc:secret", "password");
        when(factory.getProperties()).thenReturn(Map.of("hibernate.hbm2ddl.auto", "create"));
        assertThat(HibernateFactorySettingsReader.read(factory, "orders", new ArrayList<>())
                        .schemaAction())
                .isEqualTo(HibernateFactorySettings.SchemaAction.DROP_AND_CREATE);
    }

    static SpringHibernateAdvisorObservationSource source(
            DefaultListableBeanFactory beans, MockEnvironment environment) {
        return new SpringHibernateAdvisorObservationSource(beans, environment, new GenericApplicationContext());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static SessionFactoryImplementor factory(String name, int batch) {
        SessionFactoryImplementor factory = mock(SessionFactoryImplementor.class);
        when(factory.unwrap(SessionFactoryImplementor.class)).thenReturn(factory);
        when(factory.getName()).thenReturn(name);
        SessionFactoryOptions options = mock(SessionFactoryOptions.class);
        when(factory.getSessionFactoryOptions()).thenReturn(options);
        when(options.getJdbcBatchSize()).thenReturn(batch);
        when(options.getDefaultBatchFetchSize()).thenReturn(16);
        when(options.isOrderUpdatesEnabled()).thenReturn(true);
        when(options.isSecondLevelCacheEnabled()).thenReturn(true);
        when(options.isQueryCacheEnabled()).thenReturn(true);
        StatisticsImplementor statistics = mock(StatisticsImplementor.class);
        when(factory.getStatistics()).thenReturn(statistics);
        when(statistics.isStatisticsEnabled()).thenReturn(true);
        CacheImplementor cache = mock(CacheImplementor.class);
        when(factory.getCache()).thenReturn(cache);
        when(cache.getRegionFactory()).thenReturn(mock(RegionFactory.class));
        ServiceRegistryImplementor registry = mock(ServiceRegistryImplementor.class);
        when(factory.getServiceRegistry()).thenReturn(registry);
        when(registry.getService(ConnectionProvider.class)).thenReturn(mock(ConnectionProvider.class));
        when(factory.getProperties()).thenReturn(Map.of("hibernate.hbm2ddl.auto", "none"));
        Metamodel metamodel = mock(Metamodel.class);
        when(factory.getMetamodel()).thenReturn(metamodel);
        EntityType entity = mock(EntityType.class);
        when(entity.getJavaType()).thenReturn(SpringHibernateRepositoryDiscoveryTests.Order.class);
        when(entity.getName()).thenReturn("Order");
        when(entity.getAttributes()).thenReturn(Set.of());
        when(metamodel.getEntities()).thenReturn(Set.of(entity));
        return factory;
    }
}
