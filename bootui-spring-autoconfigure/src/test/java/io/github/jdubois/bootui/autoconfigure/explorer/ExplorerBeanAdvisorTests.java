package io.github.jdubois.bootui.autoconfigure.explorer;

import static org.assertj.core.api.Assertions.assertThat;

import example.bootuiexplorer.InvocationFixtures;
import example.bootuiexplorer.InvocationFixtures.AuditRepository;
import example.bootuiexplorer.InvocationFixtures.AuditRepositoryTarget;
import example.bootuiexplorer.InvocationFixtures.DefaultInventory;
import example.bootuiexplorer.InvocationFixtures.DefaultOrderService;
import example.bootuiexplorer.InvocationFixtures.InventoryHolder;
import example.bootuiexplorer.InvocationFixtures.OrderService;
import example.bootuiexplorer.InvocationFixtures.OrdersController;
import example.bootuiexplorer.InvocationFixtures.OrdersRepository;
import example.bootuiexplorer.InvocationFixtures.OrdersRepositoryTarget;
import example.bootuiexplorer.InvocationFixtures.PlainService;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.cache.CacheActivityCacheManagerBeanPostProcessor;
import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.autoconfigure.otlp.SpringTelemetrySettings;
import io.github.jdubois.bootui.autoconfigure.sqltrace.SqlTraceDataSourceBeanPostProcessor;
import io.github.jdubois.bootui.engine.cache.CacheActivityEvent;
import io.github.jdubois.bootui.engine.cache.CacheActivityOperation;
import io.github.jdubois.bootui.engine.cache.CacheActivityRecorder;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.telemetry.BootUiSpanExporter;
import io.github.jdubois.bootui.engine.telemetry.NormalizedSpan;
import io.github.jdubois.bootui.engine.telemetry.TelemetryStore;
import io.github.jdubois.bootui.spi.InvocationContextProvider;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.sql.DataSource;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.config.AopConfigUtils;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.framework.autoproxy.AutoProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.data.repository.core.RepositoryInformation;
import org.springframework.data.repository.core.RepositoryMetadata;
import org.springframework.data.repository.core.support.RepositoryFactorySupport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class ExplorerBeanAdvisorTests {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BootUiExplorerAutoConfiguration.class))
            .withUserConfiguration(Foundation.class)
            .withPropertyValues("bootui.enabled=ON")
            .withInitializer(context -> AutoConfigurationPackages.register(
                    (BeanDefinitionRegistry) context.getBeanFactory(), InvocationFixtures.class.getPackageName()));

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void defaultOnInstallsAdviceAndCapturesEligibleInvocations() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ExplorerBeanAdvisor.class);
            assertThat(AopUtils.isAopProxy(context.getBean(PlainService.class))).isTrue();
            assertThat(capture(
                            context, () -> context.getBean(PlainService.class).call()))
                    .containsExactly("plainService");
        });
    }

    @Test
    void explicitOptOutDoesNotInstallAnAdvisorAutoProxyCreatorOrExtraProxies() {
        runner.withPropertyValues("bootui.explorer.enabled=false").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(ExplorerBeanAdvisor.class);
            assertThat(context.containsBean(AopConfigUtils.AUTO_PROXY_CREATOR_BEAN_NAME))
                    .isFalse();
            assertThat(AopUtils.isAopProxy(context.getBean(PlainService.class))).isFalse();
            assertThat(context.getBean(TelemetryStore.class).allSpansSnapshot()).isEmpty();
        });
    }

    @Test
    void introducingAdviceNeverMovesFinalAccessorsOntoAnUninitializedClassProxy() {
        runner.withPropertyValues("bootui.explorer.enabled=true").run(context -> {
            var bean = context.getBean(InvocationFixtures.FinalAccessorService.class);
            assertThat(AopUtils.isAopProxy(bean)).isFalse();
            assertThat(bean.value()).isEqualTo("initialized");
            assertThat(bean.call()).isEqualTo("initialized");
        });
    }

    @Test
    void factoryOnlyBeansStartWithoutNewProxiesWhileDefaultCaptureRemainsActive() {
        runner.withUserConfiguration(FactoryOnlyBeans.class).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ExplorerBeanAdvisor.class);
            var bean = context.getBean(InvocationFixtures.FactoryOnlyService.class);
            assertThat(AopUtils.isAopProxy(bean)).isFalse();
            assertThat(capture(context, () -> {
                        assertThat(bean.count()).isEqualTo("initialized");
                        assertThat(context.getBean(PlainService.class).call()).isEqualTo("value");
                    }))
                    .containsExactly("plainService");
        });
    }

    @Test
    void existingProxyOfFactoryOnlyTargetRemainsAdvisedWithoutMakingOtherBeansOfItsTypeProxyable() {
        runner.withUserConfiguration(FactoryOnlyProxyBeans.class).run(context -> {
            assertThat(context).hasNotFailed();
            var proxy = context.getBean("proxiedFactoryService", InvocationFixtures.Inventory.class);
            var plain = context.getBean("factoryOnlyService", InvocationFixtures.FactoryOnlyService.class);
            assertThat(AopUtils.isJdkDynamicProxy(proxy)).isTrue();
            assertThat(AopUtils.isAopProxy(plain)).isFalse();
            assertThat(List.of(((Advised) proxy).getAdvisors()))
                    .filteredOn(ExplorerBeanAdvisor.class::isInstance)
                    .hasSize(1);
            assertThat(capture(context, () -> {
                        assertThat(proxy.count()).isEqualTo("initialized");
                        assertThat(plain.count()).isEqualTo("initialized");
                    }))
                    .containsExactly("proxiedFactoryService");
        });
    }

    @Test
    void protectedAndPackageVisibleConstructorsStillAllowDefaultCapture() {
        runner.withUserConfiguration(VisibleConstructorBeans.class).run(context -> {
            assertThat(context).hasNotFailed();
            var protectedBean = context.getBean(InvocationFixtures.ProtectedConstructorService.class);
            var packageBean = context.getBean(InvocationFixtures.PackageConstructorService.class);
            assertThat(AopUtils.isCglibProxy(protectedBean)).isTrue();
            assertThat(AopUtils.isCglibProxy(packageBean)).isTrue();
            assertThat(capture(context, () -> {
                        assertThat(protectedBean.call()).isEqualTo("value");
                        assertThat(packageBean.call()).isEqualTo("value");
                    }))
                    .containsExactly("protectedConstructorService", "packageConstructorService");
        });
    }

    @Test
    void allActivationAndPanelGatesLeaveTheApplicationUntouched() {
        for (String property : List.of(
                "bootui.enabled=OFF",
                "bootui.panels.activity.enabled=false",
                "bootui.panels.explorer.enabled=false",
                "bootui.panels.beans.enabled=false",
                "bootui.panels.traces.enabled=false",
                "bootui.telemetry.enabled=false",
                "org.graalvm.nativeimage.imagecode=runtime")) {
            runner.withPropertyValues(property).run(context -> {
                assertThat(context).hasNotFailed().doesNotHaveBean(ExplorerBeanAdvisor.class);
                assertThat(AopUtils.isAopProxy(context.getBean(PlainService.class)))
                        .isFalse();
            });
        }
    }

    @Test
    void reactiveAndMissingOpenTelemetryOrAopRemainUninstrumented() {
        new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BootUiExplorerAutoConfiguration.class))
                .withPropertyValues("bootui.enabled=ON")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ExplorerBeanAdvisor.class));
        for (String absent : List.of("io.opentelemetry", "org.springframework.aop")) {
            new WebApplicationContextRunner()
                    .withClassLoader(new FilteredClassLoader(absent))
                    .withConfiguration(AutoConfigurations.of(BootUiExplorerAutoConfiguration.class))
                    .withPropertyValues("bootui.enabled=ON")
                    .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ExplorerBeanAdvisor.class));
        }
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BootUiExplorerAutoConfiguration.class))
                .withPropertyValues("bootui.enabled=ON")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ExplorerBeanAdvisor.class));
    }

    @Test
    void existingJdkSpringDataCacheAndTransactionAdvisorsKeepExactContextWithoutExportingLocalSpans() {
        verifyApplication(false);
    }

    @Test
    void existingCglibAdvisorsKeepInjectionAndCaptureBehavior() {
        verifyApplication(true);
    }

    @Test
    void introducedAdviceNeverNarrowsAnInterfaceBackedBeanToItsInterfaces() {
        runner.withUserConfiguration(Application.class, ConcreteInjection.class)
                .withPropertyValues("bootui.explorer.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(AopUtils.isAopProxy(context.getBean("inventory")))
                            .isFalse();
                    assertThat(context.getBean(DefaultInventory.class)).isNotNull();
                    assertThat(context.getBean(InventoryHolder.class).count()).isEqualTo("1");
                    assertThat(AopUtils.isCglibProxy(context.getBean(InventoryHolder.class)))
                            .isTrue();

                    OrderService service = context.getBean(OrderService.class);
                    assertThat(AopUtils.isJdkDynamicProxy(service)).isTrue();
                    assertThat(((Advised) service).getAdvisors()[0])
                            .isSameAs(context.getBean(ExplorerBeanAdvisor.class));
                    assertThat(capture(context, () -> {
                                context.getBean(InventoryHolder.class).count();
                                assertThat(service.load(1L)).isEqualTo("order");
                            }))
                            .contains("inventoryHolder", "orderService")
                            .doesNotContain("inventory");
                });
    }

    @Test
    void classProxyingHostsKeepObservingPreviouslyUnproxiedInterfaceBackedBeans() {
        runner.withUserConfiguration(Application.class, ClassProxies.class, ConcreteInjection.class)
                .withPropertyValues("bootui.explorer.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(AopUtils.isCglibProxy(context.getBean("inventory")))
                            .isTrue();
                    assertThat(context.getBean(DefaultInventory.class)).isNotNull();
                    assertThat(context.getBean(InventoryHolder.class).count()).isEqualTo("1");
                    assertThat(capture(
                                    context,
                                    () -> context.getBean(InventoryHolder.class).count()))
                            .containsExactly("inventory", "inventoryHolder");
                });
    }

    @Test
    void perBeanTargetClassPreservationStillObservesAnInterfaceBackedBean() {
        runner.withUserConfiguration(Application.class, ConcreteInjection.class, PreservedTargetClass.class)
                .withPropertyValues("bootui.explorer.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(AopUtils.isCglibProxy(context.getBean("inventory")))
                            .isTrue();
                    assertThat(context.getBean(DefaultInventory.class)).isNotNull();
                    assertThat(capture(
                                    context,
                                    () -> context.getBean(InventoryHolder.class).count()))
                            .containsExactly("inventory", "inventoryHolder");
                });
    }

    @Test
    void nestedProxiesAroundOneTargetObserveAnInvocationOnlyOnce() {
        runner.withUserConfiguration(Application.class, NestedProxies.class)
                .withPropertyValues("bootui.explorer.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AuditRepository repository = context.getBean(AuditRepository.class);
                    Object inner = ((Advised) repository).getTargetSource().getTarget();
                    assertThat(inner).isInstanceOf(Advised.class);
                    assertThat(List.of(((Advised) repository).getAdvisors()))
                            .filteredOn(ExplorerBeanAdvisor.class::isInstance)
                            .hasSize(1);
                    assertThat(List.of(((Advised) inner).getAdvisors()))
                            .filteredOn(ExplorerBeanAdvisor.class::isInstance)
                            .isEmpty();
                    assertThat(capture(
                                    context,
                                    () -> assertThat(repository.record(1L)).isEqualTo("audited")))
                            .containsExactly("auditRepository");
                });
    }

    @Test
    void aProxyAddedAfterTheAdvisorDoesNotObserveTheInvocationTwice() {
        runner.withUserConfiguration(Application.class, ClassProxies.class, LateProxies.class)
                .withPropertyValues("bootui.explorer.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    OrderService service = context.getBean(OrderService.class);
                    Object inner = ((Advised) service).getTargetSource().getTarget();
                    assertThat(List.of(((Advised) service).getAdvisors()))
                            .filteredOn(ExplorerBeanAdvisor.class::isInstance)
                            .isEmpty();
                    assertThat(List.of(((Advised) inner).getAdvisors()))
                            .filteredOn(ExplorerBeanAdvisor.class::isInstance)
                            .hasSize(1);
                    assertThat(capture(context, () -> service.load(1L)))
                            .containsExactly("ordersRepository", "orderService");
                });
    }

    private List<String> capture(org.springframework.context.ApplicationContext context, Runnable work) {
        TelemetryStore store = context.getBean(TelemetryStore.class);
        int before = store.allSpansSnapshot().size();
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest("GET", "/orders")));
        try (SdkTracerProvider sdk = SdkTracerProvider.builder().build()) {
            Span server = sdk.get("host")
                    .spanBuilder("GET /orders")
                    .setSpanKind(SpanKind.SERVER)
                    .startSpan();
            try (Scope ignored = server.makeCurrent()) {
                work.run();
            } finally {
                server.end();
                RequestContextHolder.resetRequestAttributes();
            }
        }
        return store.allSpansSnapshot().stream()
                .skip(before)
                .map(span -> attribute(span, "bean"))
                .toList();
    }

    @Test
    void mvcDispatcherProvidesRealRequestScopeForControllerServiceRepositoryAndSql() {
        runner.withUserConfiguration(Application.class)
                .withPropertyValues("bootui.explorer.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                                    context.getBean(OrdersController.class))
                            .build();
                    try (SdkTracerProvider sdk = SdkTracerProvider.builder().build()) {
                        Span server = sdk.get("host")
                                .spanBuilder("GET /orders")
                                .setSpanKind(SpanKind.SERVER)
                                .startSpan();
                        try (Scope ignored = server.makeCurrent()) {
                            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                            "/orders"))
                                    .andExpect(
                                            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                                    .isOk())
                                    .andExpect(
                                            org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                                                    .string("order"));
                            assertThat(Span.current()).isSameAs(server);
                        } finally {
                            server.end();
                        }
                    }
                    assertThat(context.getBean(TelemetryStore.class).allSpansSnapshot())
                            .extracting(span -> attribute(span, "role"))
                            .containsExactly("REPOSITORY", "SERVICE", "CONTROLLER");
                    assertThat(context.getBean(SqlTraceRecorder.class).recent()).hasSize(2);
                    assertThat(context.getBean(SpringInvocationContext.class)
                                    .capture()
                                    .current())
                            .isEqualTo(InvocationContextProvider.EMPTY);
                });
    }

    @Test
    void sameTypeSingletonBeansKeepDistinctBeanIdentities() {
        runner.withPropertyValues("bootui.explorer.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            RequestContextHolder.setRequestAttributes(
                    new ServletRequestAttributes(new MockHttpServletRequest("GET", "/orders")));
            try (SdkTracerProvider sdk = SdkTracerProvider.builder().build()) {
                Span server = sdk.get("host")
                        .spanBuilder("GET /orders")
                        .setSpanKind(SpanKind.SERVER)
                        .startSpan();
                try (Scope ignored = server.makeCurrent()) {
                    context.getBean(PlainService.class).call();
                    context.getBean("secondPlainService", PlainService.class).call();
                } finally {
                    server.end();
                }
            }
            assertThat(context.getBean(TelemetryStore.class).allSpansSnapshot())
                    .extracting(span -> attribute(span, "bean"))
                    .containsExactly("plainService", "secondPlainService");
        });
    }

    private void verifyApplication(boolean cglib) {
        var application =
                runner.withUserConfiguration(Application.class).withPropertyValues("bootui.explorer.enabled=true");
        if (cglib) {
            application = application.withUserConfiguration(ClassProxies.class);
        }
        application.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ExplorerBeanAdvisor.class);
            assertThat(context.getBeanFactory().containsSingleton("lazyService"))
                    .isFalse();
            OrderService service = context.getBean(OrderService.class);
            assertThat(AopUtils.isCglibProxy(service)).isEqualTo(cglib);
            assertThat(AopUtils.isJdkDynamicProxy(service)).isEqualTo(!cglib);
            assertThat(((Advised) service).getAdvisors()[0]).isSameAs(context.getBean(ExplorerBeanAdvisor.class));
            OrdersRepository repository = context.getBean(OrdersRepository.class);
            assertThat(AopUtils.isJdkDynamicProxy(repository)).isTrue();
            assertThat(((Advised) repository).getTargetSource().getTarget()).isInstanceOf(OrdersRepositoryTarget.class);
            assertThat(List.of(((Advised) repository).getAdvisors()))
                    .filteredOn(ExplorerBeanAdvisor.class::isInstance)
                    .hasSize(1);

            TelemetryStore store = context.getBean(TelemetryStore.class);
            HostExporter host = new HostExporter();
            try (SdkTracerProvider sdk = SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(host))
                    .addSpanProcessor(SimpleSpanProcessor.create(new BootUiSpanExporter(
                            store,
                            context.getBean(BootUiSelfDataFilter.class).telemetryClassifier(),
                            new SpringTelemetrySettings(context.getBean(BootUiProperties.class)))))
                    .build()) {
                Span server = sdk.get("host")
                        .spanBuilder("GET /orders")
                        .setSpanKind(SpanKind.SERVER)
                        .startSpan();
                var request = new MockHttpServletRequest("GET", "/orders");
                RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
                try (Scope ignored = server.makeCurrent()) {
                    var controller = context.getBean(OrdersController.class);
                    assertThat(controller.get()).isEqualTo("order");
                    assertThat(controller.get()).isEqualTo("order");
                    assertThat(controller.handledFailure()).isEqualTo("handled");
                    assertThat(Span.current()).isSameAs(server);
                    assertThat(host.spans).isEmpty();
                    assertThat(context.getBean(SpringInvocationContext.class)
                                    .capture()
                                    .current())
                            .isEqualTo(InvocationContextProvider.EMPTY);
                } finally {
                    server.end();
                }
                assertThat(host.spans).extracting(SpanData::getName).containsExactly("GET /orders");
            }
            var local = store.allSpansSnapshot().stream()
                    .filter(span -> "bootui.explorer".equals(span.scope()))
                    .toList();
            assertThat(local)
                    .filteredOn(span -> "REPOSITORY".equals(attribute(span, "role")))
                    .singleElement()
                    .satisfies(span -> assertThat(attribute(span, "type")).isEqualTo(OrdersRepository.class.getName()));
            var queries = context.getBean(SqlTraceRecorder.class).recent();
            assertThat(queries).hasSize(2);
            for (var query : queries) {
                NormalizedSpan parent = local.stream()
                        .filter(span -> span.spanId().equals(query.invocationId()))
                        .findFirst()
                        .orElseThrow();
                assertThat(attribute(parent, "role"))
                        .isEqualTo(query.sql().equals("select 2") ? "SERVICE" : "REPOSITORY");
                assertThat(query.dataSource()).isEqualTo("dataSource");
            }
            var cacheEvents = context.getBean(CacheActivityRecorder.class).recentEvents();
            assertThat(cacheEvents)
                    .extracting(CacheActivityEvent::operation)
                    .containsExactly(
                            CacheActivityOperation.MISS, CacheActivityOperation.PUT, CacheActivityOperation.HIT);
            assertThat(cacheEvents)
                    .allSatisfy(event -> assertThat(local.stream()
                                    .filter(span -> span.spanId().equals(event.invocationId()))
                                    .map(span -> attribute(span, "role")))
                            .containsExactly("SERVICE"));
            assertThat(local).filteredOn(NormalizedSpan::isError).hasSize(1);
            assertThat(local.toString()).doesNotContain("private payload");
        });
    }

    @Test
    void activeBackgroundAndUnsampledContextsDoNotCreateBeanSpansOrInstantiateLazyBeans() {
        runner.withPropertyValues("bootui.explorer.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            PlainService service = context.getBean(PlainService.class);
            assertThat(AopUtils.isAopProxy(service)).isTrue();
            assertThat(service.call()).isEqualTo("value");
            try (Scope ignored = Span.wrap(io.opentelemetry.api.trace.SpanContext.create(
                            "1234567890abcdef1234567890abcdef",
                            "1234567890123456",
                            io.opentelemetry.api.trace.TraceFlags.getDefault(),
                            io.opentelemetry.api.trace.TraceState.getDefault()))
                    .makeCurrent()) {
                RequestContextHolder.setRequestAttributes(
                        new ServletRequestAttributes(new MockHttpServletRequest("GET", "/orders")));
                assertThat(service.call()).isEqualTo("value");
            }
            assertThat(context.getBean(TelemetryStore.class).allSpansSnapshot()).isEmpty();
            assertThat(context.getBeanFactory().containsSingleton("lazyService"))
                    .isFalse();
        });
    }

    private static String attribute(NormalizedSpan span, String name) {
        return span.attributes().get("bootui.explorer." + name).asString();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(BootUiProperties.class)
    static class Foundation {
        @Bean
        InvocationFixtures.FinalAccessorService finalAccessorService() {
            return new InvocationFixtures.FinalAccessorService("initialized");
        }

        @Bean
        OpenTelemetry openTelemetry() {
            return OpenTelemetry.noop();
        }

        @Bean
        TelemetryStore telemetryStore(BootUiProperties properties) {
            return new TelemetryStore(new SpringTelemetrySettings(properties));
        }

        @Bean
        BootUiSelfDataFilter selfDataFilter(BootUiProperties properties) {
            return new BootUiSelfDataFilter(properties);
        }

        @Bean
        @Primary
        PlainService plainService() {
            return new PlainService();
        }

        @Bean
        PlainService secondPlainService() {
            return new PlainService();
        }

        @Bean
        @Lazy
        PlainService lazyService() {
            throw new AssertionError("Lazy bean was instantiated");
        }

        @Bean
        @org.springframework.context.annotation.Scope("prototype")
        PlainService prototypeService() {
            throw new AssertionError("Prototype bean was instantiated");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCaching
    @EnableTransactionManagement
    static class Application {
        @Bean
        static SqlTraceDataSourceBeanPostProcessor sqlCapture(ObjectProvider<SqlTraceRecorder> recorders) {
            return new SqlTraceDataSourceBeanPostProcessor(recorders);
        }

        @Bean
        static CacheActivityCacheManagerBeanPostProcessor cacheCapture(
                ObjectProvider<CacheActivityRecorder> recorders, ObjectProvider<BootUiSelfDataFilter> filters) {
            return new CacheActivityCacheManagerBeanPostProcessor(recorders, filters);
        }

        @Bean
        SqlTraceRecorder sqlRecorder() {
            return new SqlTraceRecorder(true, true, false, false, 100, 1000, 2000, 100, 5);
        }

        @Bean
        CacheActivityRecorder cacheRecorder() {
            return new CacheActivityRecorder(true, 100);
        }

        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("orders");
        }

        @Bean
        DataSource dataSource() {
            var dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:explorer");
            return dataSource;
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        FactoryBean<OrdersRepository> ordersRepository(JdbcTemplate jdbc) {
            var factory = new RepositoryFactorySupport() {
                @Override
                protected Object getTargetRepository(RepositoryInformation information) {
                    return new OrdersRepositoryTarget(jdbc);
                }

                @Override
                protected Class<?> getRepositoryBaseClass(RepositoryMetadata metadata) {
                    return OrdersRepositoryTarget.class;
                }
            };
            return new FactoryBean<>() {
                private final OrdersRepository repository = factory.getRepository(OrdersRepository.class);

                @Override
                public OrdersRepository getObject() {
                    return repository;
                }

                @Override
                public Class<?> getObjectType() {
                    return OrdersRepository.class;
                }
            };
        }

        @Bean
        OrderService orderService(OrdersRepository repository, JdbcTemplate jdbc) {
            return new DefaultOrderService(repository, jdbc);
        }

        @Bean
        OrdersController ordersController(OrderService service) {
            return new OrdersController(service);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FactoryOnlyBeans {
        @Bean
        InvocationFixtures.FactoryOnlyService factoryOnlyService() {
            return InvocationFixtures.FactoryOnlyService.create();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FactoryOnlyProxyBeans {
        @Bean
        InvocationFixtures.Inventory proxiedFactoryService() {
            ProxyFactory factory = new ProxyFactory(InvocationFixtures.FactoryOnlyService.create());
            return (InvocationFixtures.Inventory) factory.getProxy();
        }

        @Bean
        @DependsOn("proxiedFactoryService")
        InvocationFixtures.FactoryOnlyService factoryOnlyService() {
            return InvocationFixtures.FactoryOnlyService.create();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class VisibleConstructorBeans {
        @Bean
        InvocationFixtures.ProtectedConstructorService protectedConstructorService() {
            return InvocationFixtures.ProtectedConstructorService.create();
        }

        @Bean
        InvocationFixtures.PackageConstructorService packageConstructorService() {
            return InvocationFixtures.PackageConstructorService.create();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class ClassProxies {}

    @Configuration(proxyBeanMethods = false)
    static class NestedProxies {
        @Bean
        FactoryBean<AuditRepository> auditRepository() {
            var factory = new RepositoryFactorySupport() {
                @Override
                protected Object getTargetRepository(RepositoryInformation information) {
                    return new AuditRepositoryTarget();
                }

                @Override
                protected Class<?> getRepositoryBaseClass(RepositoryMetadata metadata) {
                    return AuditRepositoryTarget.class;
                }
            };
            return new FactoryBean<>() {
                private final AuditRepository repository = factory.getRepository(AuditRepository.class);

                @Override
                public AuditRepository getObject() {
                    return repository;
                }

                @Override
                public Class<?> getObjectType() {
                    return AuditRepository.class;
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class LateProxies {
        @Bean
        static BeanPostProcessor lateOrderServiceProxy() {
            class LateProxy implements BeanPostProcessor, Ordered {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!"orderService".equals(beanName)) {
                        return bean;
                    }
                    ProxyFactory factory = new ProxyFactory(bean);
                    factory.addAdvice((MethodInterceptor) MethodInvocation::proceed);
                    return factory.getProxy();
                }

                @Override
                public int getOrder() {
                    return Ordered.LOWEST_PRECEDENCE;
                }
            }
            return new LateProxy();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ConcreteInjection {
        @Bean
        DefaultInventory inventory() {
            return new DefaultInventory();
        }

        @Bean
        InventoryHolder inventoryHolder(DefaultInventory inventory) {
            return new InventoryHolder(inventory);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PreservedTargetClass {
        @Bean
        static org.springframework.beans.factory.config.BeanFactoryPostProcessor preserveInventoryTargetClass() {
            return factory -> factory.getBeanDefinition("inventory")
                    .setAttribute(AutoProxyUtils.PRESERVE_TARGET_CLASS_ATTRIBUTE, Boolean.TRUE);
        }
    }

    private static class HostExporter implements SpanExporter {
        private final List<SpanData> spans = new ArrayList<>();

        @Override
        public CompletableResultCode export(Collection<SpanData> exported) {
            spans.addAll(exported);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
