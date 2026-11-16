package io.github.jdubois.bootui.autoconfigure.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.cache.CacheActivityAware;
import io.github.jdubois.bootui.autoconfigure.cache.CacheActivityCacheManagerBeanPostProcessor;
import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.engine.cache.CacheActivityRecorder;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.insights.CacheCapture;
import io.github.jdubois.bootui.engine.insights.ProxyBypass;
import io.github.jdubois.bootui.engine.journal.ApplicationFrames;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalAggregates;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.spi.BeanProvider;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.github.jdubois.bootui.spi.MappingProvider;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.web.context.reactive.GenericReactiveWebApplicationContext;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.interceptor.CacheResolver;
import org.springframework.cache.interceptor.SimpleKey;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.ReactiveAdapterRegistry;
import org.springframework.core.ResolvableType;
import reactor.core.publisher.Mono;

class SpringCacheCaptureTests {

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void transientFailedOrNullCacheOwnershipDoesNotProveProxyBypassAfterSqlOwnershipRecovers(
            boolean reactive, boolean nullResult) {
        try (App app = new App(reactive, true, context -> {})) {
            CacheActivityRecorder recorder = app.context.getBean(CacheActivityRecorder.class);
            AtomicBoolean failOnce = new AtomicBoolean(true);
            recorder.setCorrelationContextProvider(() -> {
                if (failOnce.getAndSet(false)) {
                    if (nullResult) return null;
                    throw new IllegalStateException("private provider failure");
                }
                return app.active.get();
            });
            long epoch = recorder.captureState().epoch();
            Operations operations = app.context.getBean(Operations.class);
            app.request(
                    "ownership-miss", () -> assertThat(operations.supported()).isEqualTo(1));
            app.request(
                    "ownership-hit", () -> assertThat(operations.supported()).isEqualTo(1));
            assertThat(app.statements.get())
                    .as("real caching continues to serve the second invocation")
                    .isEqualTo(1);
            assertThat(recorder.recentEvents()).hasSize(3).anyMatch(event -> event.requestId() == null);
            assertThat(recorder.recentEvents()).anyMatch(event -> "ownership-miss".equals(event.requestId()));
            var report = app.controller.report();
            assertThat(report.observations()).noneMatch(row -> row.kind().equals(ProxyBypass.KIND));
            assertThat(recorder.captureState().epoch()).isGreaterThan(epoch);
            assertThat(report.checks())
                    .filteredOn(check -> check.kind().equals(ProxyBypass.KIND))
                    .singleElement()
                    .satisfies(check -> assertThat(check.reason()).contains(CacheCapture.OBSERVATION_FAILED));
            assertThat(report.toString()).doesNotContain("private provider failure");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legitimateBackgroundNoneDoesNotInvalidateSupportedCacheAbsenceProof(boolean reactive) {
        try (App app = new App(reactive, true, context -> {})) {
            CacheActivityRecorder recorder = app.context.getBean(CacheActivityRecorder.class);
            long epoch = recorder.captureState().epoch();
            recorder.recordHit("cacheManager", "prices", "background");
            assertThat(recorder.captureState().epoch()).isEqualTo(epoch);
            assertThat(recorder.recentEvents())
                    .singleElement()
                    .satisfies(event -> assertThat(event.requestId()).isNull());
            app.request("supported-self", app.context.getBean(Operations.class)::bypassSupported);
            assertThat(app.controller.report().observations())
                    .filteredOn(row -> row.kind().equals(ProxyBypass.KIND))
                    .singleElement()
                    .satisfies(row -> assertThat(row.exemplarRequestIds()).containsExactly("supported-self"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void oneWrappedManagerDoesNotProveAnExplicitlyUnwrappedManager(boolean reactive) {
        try (App app = new App(
                reactive,
                true,
                context -> context.registerBean("unwrapped", UnwrappedManager.class, UnwrappedManager::new))) {
            Operations operations = app.context.getBean(Operations.class);
            app.request("unwrapped-proxy", operations::unwrapped);
            app.request("unwrapped-self", operations::bypassUnwrapped);
            app.request("supported-self", operations::bypassSupported);
            var report = app.controller.report();
            assertThat(report.observations())
                    .filteredOn(row -> row.kind().equals(ProxyBypass.KIND))
                    .singleElement()
                    .satisfies(row -> {
                        assertThat(row.sentence()).contains("Operations.supported");
                        assertThat(row.exemplarRequestIds()).containsExactly("supported-self");
                    });
            assertThat(report.checks())
                    .filteredOn(check -> check.kind().equals(ProxyBypass.KIND))
                    .singleElement()
                    .satisfies(check -> assertThat(check.reason()).contains(CacheCapture.UNPROVEN_MANAGER));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void customResolversAreNeverInvokedToEstablishCapture(boolean reactive) {
        AtomicInteger resolverCalls = new AtomicInteger();
        Cache nativeCache = new ConcurrentMapCacheManager("prices").getCache("prices");
        try (App app = new App(
                reactive,
                true,
                context -> context.registerBean("customResolver", CacheResolver.class, () -> operation -> {
                    resolverCalls.incrementAndGet();
                    return List.of(nativeCache);
                }))) {
            Operations operations = app.context.getBean(Operations.class);
            app.request("custom-proxy", operations::custom);
            assertThat(resolverCalls.get()).isPositive();
            int before = resolverCalls.get();
            assertThat(app.controller.report().observations())
                    .noneMatch(row -> row.kind().equals(ProxyBypass.KIND));
            assertThat(app.controller.report().checks())
                    .filteredOn(check -> check.kind().equals(ProxyBypass.KIND))
                    .singleElement()
                    .satisfies(
                            check -> assertThat(check.reason()).contains(SpringProxyBoundaries.CUSTOM_CACHE_RESOLVER));
            assertThat(resolverCalls.get()).isEqualTo(before);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aCustomResolverWithNoStaticCacheNamesStillExplainsItsUnassessedBoundary(boolean reactive) {
        AtomicInteger calls = new AtomicInteger();
        Cache nativeCache = new ConcurrentMapCacheManager("prices").getCache("prices");
        try (App app = new App(
                reactive,
                true,
                context -> context.registerBean("customResolver", CacheResolver.class, () -> operation -> {
                    calls.incrementAndGet();
                    return List.of(nativeCache);
                }))) {
            Operations operations = app.context.getBean(Operations.class);
            app.request("unnamed-proxy", operations::customUnnamed);
            app.request("unnamed-self", operations::bypassCustomUnnamed);
            int before = calls.get();
            assertThat(before).isPositive();
            var report = app.controller.report();
            assertThat(report.observations()).noneMatch(row -> row.kind().equals(ProxyBypass.KIND));
            assertThat(report.checks())
                    .filteredOn(check -> check.kind().equals(ProxyBypass.KIND))
                    .singleElement()
                    .satisfies(
                            check -> assertThat(check.reason()).contains(SpringProxyBoundaries.CUSTOM_CACHE_RESOLVER));
            assertThat(calls.get()).isEqualTo(before);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aKeyRepresentationFailureDoesNotDisruptCachingOrEstablishProxyBypass(boolean reactive) {
        try (App app = new App(reactive, true, context -> {})) {
            Operations operations = app.context.getBean(Operations.class);
            Object key = new Object() {
                @Override
                public String toString() {
                    throw new IllegalStateException("private key representation");
                }
            };
            CacheActivityRecorder recorder = app.context.getBean(CacheActivityRecorder.class);
            long epoch = recorder.captureState().epoch();
            app.request("key-miss", () -> assertThat(operations.keyed(key)).isEqualTo(1));
            app.request("key-hit", () -> assertThat(operations.keyed(key)).isEqualTo(1));
            assertThat(app.statements.get())
                    .as("the real cache still serves the second invocation")
                    .isEqualTo(1);
            assertThat(recorder.captureState().epoch()).isGreaterThan(epoch);
            var report = app.controller.report();
            assertThat(report.observations()).noneMatch(row -> row.kind().equals(ProxyBypass.KIND));
            assertThat(report.checks())
                    .filteredOn(check -> check.kind().equals(ProxyBypass.KIND))
                    .singleElement()
                    .satisfies(check -> assertThat(check.reason()).contains(CacheCapture.OBSERVATION_FAILED));
            assertThat(report.toString()).doesNotContain("private key representation");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void customConfigurerDefaultRoutingStaysUnknownWithoutInvokingItsCallbacksOnRead(boolean reactive) {
        AtomicInteger callbacks = new AtomicInteger();
        CacheManager manager = new ConcurrentMapCacheManager("prices");
        try (App app = new App(
                reactive,
                true,
                context -> context.registerBean(CachingConfigurer.class, () -> new CachingConfigurer() {
                    @Override
                    public CacheManager cacheManager() {
                        callbacks.incrementAndGet();
                        return manager;
                    }
                }))) {
            Operations operations = app.context.getBean(Operations.class);
            app.request("configured-proxy", operations::defaultRoute);
            int before = callbacks.get();
            var report = app.controller.report();
            assertThat(report.observations()).noneMatch(row -> row.kind().equals(ProxyBypass.KIND));
            assertThat(report.checks())
                    .filteredOn(check -> check.kind().equals(ProxyBypass.KIND))
                    .singleElement()
                    .satisfies(check -> assertThat(check.reason()).contains(SpringCacheCapture.CUSTOM_ROUTING));
            assertThat(callbacks.get()).isEqualTo(before);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void inheritedConfigurerRoutingIsAnExplicitPassiveUnknown(boolean reactive) {
        AtomicInteger callbacks = new AtomicInteger();
        CacheManager manager = new ConcurrentMapCacheManager("prices");
        try (GenericApplicationContext parent = new GenericApplicationContext()) {
            parent.registerBean(CachingConfigurer.class, () -> new CachingConfigurer() {
                @Override
                public CacheManager cacheManager() {
                    callbacks.incrementAndGet();
                    return manager;
                }
            });
            parent.refresh();
            try (App app = new App(reactive, true, context -> context.setParent(parent))) {
                Operations operations = app.context.getBean(Operations.class);
                app.request("inherited-proxy", operations::defaultRoute);
                assertThat(manager.getCache("prices").get(SimpleKey.EMPTY))
                        .as("Spring actually used the ancestor's manager")
                        .isNotNull();
                assertThat(callbacks.get()).isPositive();
                int before = callbacks.get();
                var report = app.controller.report();
                assertThat(report.observations()).noneMatch(row -> row.kind().equals(ProxyBypass.KIND));
                assertThat(report.checks())
                        .filteredOn(check -> check.kind().equals(ProxyBypass.KIND))
                        .singleElement()
                        .satisfies(check -> assertThat(check.reason()).contains(SpringCacheCapture.HIERARCHY));
                assertThat(callbacks.get()).isEqualTo(before);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void asynchronousAndReactiveRetrievalStayUnknownWithoutChangingTheWrappersBehavior(boolean reactive) {
        try (App app = new App(reactive, true, context -> {})) {
            Operations operations = app.context.getBean(Operations.class);
            assertThat(ReactiveAdapterRegistry.getSharedInstance().getAdapter(Flow.Publisher.class))
                    .isNotNull();
            assertThatThrownBy(operations::future).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(operations::mono).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(operations::flow).isInstanceOf(UnsupportedOperationException.class);
            app.request("future-self", operations::bypassFuture);
            app.request("mono-self", operations::bypassMono);
            app.request("flow-self", operations::bypassFlow);
            app.request("supported-self", operations::bypassSupported);
            var report = app.controller.report();
            assertThat(report.observations())
                    .filteredOn(row -> row.kind().equals(ProxyBypass.KIND))
                    .singleElement()
                    .satisfies(row -> assertThat(row.exemplarRequestIds()).containsExactly("supported-self"));
            assertThat(report.checks())
                    .filteredOn(check -> check.kind().equals(ProxyBypass.KIND))
                    .singleElement()
                    .satisfies(check -> assertThat(check.reason()).contains(SpringProxyBoundaries.ASYNC_CACHE));
            assertThat(app.subscriptions.get()).isZero();
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void lazyStringClassResolverDefinitionsAreReadWithoutCreatingThem(boolean reactive, boolean rootDefinition) {
        try (App app = new App(reactive, true, context -> {})) {
            AtomicInteger creations = new AtomicInteger();
            AbstractBeanDefinition declaration =
                    rootDefinition ? new RootBeanDefinition() : new GenericBeanDefinition();
            declaration.setBeanClassName(LazyResolver.class.getName());
            declaration.setLazyInit(true);
            declaration.getConstructorArgumentValues().addGenericArgumentValue(creations);
            app.context.registerBeanDefinition("lazyStringResolver", declaration);
            CacheCapture proof = CacheCapture.read(new SpringCacheCapture(app.context));
            assertThat(proof.reason()).isEqualTo(SpringCacheCapture.CUSTOM_ROUTING);
            assertThat(creations.get()).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unresolvedDeclarationsStillWithholdProofWithoutCreatingAnything(boolean reactive) {
        try (App app = new App(reactive, true, context -> {})) {
            GenericBeanDefinition declaration = new GenericBeanDefinition();
            declaration.setBeanClassName("example.missing.UnresolvableCacheRouting");
            declaration.setLazyInit(true);
            app.context.registerBeanDefinition("unresolved", declaration);
            CacheCapture proof = CacheCapture.read(new SpringCacheCapture(app.context));
            assertThat(proof.reason()).contains("IllegalArgumentException");
            assertThat(proof.unavailable("cacheManager", app.clock.get() + 1)).isNotNull();
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void initializedGenericFactoriesKeepProvenDefinitionProductsWhileUnknownProductsStillWithholdProof(
            boolean reactive, boolean typedProduct) {
        try (App app = new App(reactive, true, context -> {})) {
            AtomicInteger callbacks = new AtomicInteger();
            RootBeanDefinition declaration = new RootBeanDefinition(GenericFactory.class);
            if (typedProduct) {
                declaration.setTargetType(ResolvableType.forClassWithGenerics(GenericFactory.class, String.class));
            }
            app.context.registerBeanDefinition("unrelatedFactory", declaration);
            app.context.getBeanFactory().registerSingleton("unrelatedFactory", new GenericFactory<String>(callbacks));
            CacheCapture proof = CacheCapture.read(new SpringCacheCapture(app.context));
            if (typedProduct) {
                assertThat(proof.reason()).isNull();
                assertThat(proof.unavailable("cacheManager", app.clock.get() + 1))
                        .isNull();
            } else {
                assertThat(proof.reason()).isEqualTo(SpringCacheCapture.AMBIGUOUS);
            }
            assertThat(callbacks.get()).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingProxyInfrastructureIsNotProvenByOneWrappedManager(boolean reactive) {
        try (App app = new App(reactive, false, context -> {})) {
            app.request("no-proxy", app.context.getBean(Operations.class)::supported);
            var report = app.controller.report();
            assertThat(report.observations()).noneMatch(row -> row.kind().equals(ProxyBypass.KIND));
            assertThat(report.checks())
                    .filteredOn(check -> check.kind().equals(ProxyBypass.KIND))
                    .singleElement()
                    .satisfies(check -> assertThat(check.reason()).contains(SpringCacheCapture.NO_PROXY));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void readingInsightsDoesNotCreateALazyRecorderOrRunItsFactory(boolean reactive) {
        AtomicInteger creations = new AtomicInteger();
        try (RuntimeJournal journal = new RuntimeJournal(
                        new RuntimeJournalSettings(true, 100, 1000000, 100, 10, 10, JournalSource.all()),
                        RunIdentity.start());
                GenericApplicationContext context = context(reactive)) {
            context.registerBean(RuntimeJournal.class, () -> journal);
            context.registerBean(
                    CacheActivityRecorder.class,
                    () -> {
                        creations.incrementAndGet();
                        return new CacheActivityRecorder(true, 10);
                    },
                    definition -> definition.setLazyInit(true));
            context.refresh();
            RuntimeInsightsController controller = controller(context);
            controller.report();
            controller.report();
            assertThat(creations.get()).isZero();
            assertThat(CacheCapture.read(new SpringCacheCapture(context)).reason())
                    .isEqualTo(SpringCacheCapture.AMBIGUOUS);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void existingFactoryBeansAndAmbiguousRecordersArePassiveUnknowns(boolean reactive) {
        AtomicInteger callbacks = new AtomicInteger();
        try (GenericApplicationContext context = context(reactive)) {
            context.refresh();
            context.getBeanFactory().registerSingleton("factory", new RecorderFactory(callbacks));
            assertThat(CacheCapture.read(new SpringCacheCapture(context)).reason())
                    .isEqualTo(SpringCacheCapture.AMBIGUOUS);
            assertThat(callbacks.get()).isZero();
        }
        try (GenericApplicationContext context = context(reactive)) {
            context.refresh();
            context.getBeanFactory().registerSingleton("first", new CacheActivityRecorder(true, 10));
            context.getBeanFactory().registerSingleton("second", new CacheActivityRecorder(true, 10));
            assertThat(CacheCapture.read(new SpringCacheCapture(context)).reason())
                    .isEqualTo(SpringCacheCapture.AMBIGUOUS);
        }
    }

    private static GenericApplicationContext context(boolean reactive) {
        return reactive ? new GenericReactiveWebApplicationContext() : new GenericApplicationContext();
    }

    private static RuntimeInsightsController controller(GenericApplicationContext context) {
        return new RuntimeInsightsController(
                context,
                new BootUiProperties(),
                context.getBeanProvider(RuntimeJournal.class),
                context.getBeanProvider(JournalAggregates.class),
                context.getBeanProvider(MappingProvider.class),
                context.getBeanProvider(BeanProvider.class));
    }

    static final class RecorderFactory implements FactoryBean<CacheActivityRecorder> {
        private final AtomicInteger callbacks;

        RecorderFactory(AtomicInteger callbacks) {
            this.callbacks = callbacks;
        }

        @Override
        public CacheActivityRecorder getObject() {
            callbacks.incrementAndGet();
            return new CacheActivityRecorder(true, 10);
        }

        @Override
        public Class<?> getObjectType() {
            callbacks.incrementAndGet();
            return CacheActivityRecorder.class;
        }
    }

    static final class LazyResolver implements CacheResolver {
        LazyResolver(AtomicInteger creations) {
            creations.incrementAndGet();
        }

        @Override
        public java.util.Collection<? extends Cache> resolveCaches(
                org.springframework.cache.interceptor.CacheOperationInvocationContext<?> context) {
            throw new AssertionError("A lazy resolver must not run while proving capture");
        }
    }

    static final class GenericFactory<T> implements FactoryBean<T> {
        private final AtomicInteger callbacks;

        GenericFactory(AtomicInteger callbacks) {
            this.callbacks = callbacks;
        }

        @Override
        public T getObject() {
            callbacks.incrementAndGet();
            throw new AssertionError("An unrelated factory product must not be created");
        }

        @Override
        public Class<?> getObjectType() {
            callbacks.incrementAndGet();
            return Object.class;
        }
    }

    static final class UnwrappedManager implements CacheManager, CacheActivityAware {
        private final CacheManager delegate = new ConcurrentMapCacheManager("prices");

        @Override
        public Cache getCache(String name) {
            return delegate.getCache(name);
        }

        @Override
        public java.util.Collection<String> getCacheNames() {
            return delegate.getCacheNames();
        }

        @Override
        public CacheManager getTargetCacheManager() {
            throw new AssertionError("A custom unwrapping callback must not be invoked");
        }
    }

    static class Operations {
        private final App app;

        Operations(App app) {
            this.app = app;
        }

        @Cacheable(cacheNames = "prices", cacheManager = "cacheManager")
        public int supported() {
            app.statement("supported");
            return 1;
        }

        @Cacheable(cacheNames = "prices", cacheManager = "unwrapped")
        public int unwrapped() {
            app.statement("unwrapped");
            return 1;
        }

        @Cacheable(cacheNames = "prices", cacheResolver = "customResolver")
        public int custom() {
            app.statement("custom");
            return 1;
        }

        @Cacheable(cacheResolver = "customResolver")
        public int customUnnamed() {
            app.statement("customUnnamed");
            return 1;
        }

        @Cacheable(cacheNames = "prices", cacheManager = "cacheManager")
        public int keyed(Object key) {
            app.statement("keyed");
            return 1;
        }

        @Cacheable("prices")
        public int defaultRoute() {
            app.statement("defaultRoute");
            return 1;
        }

        @Cacheable(cacheNames = "prices", cacheManager = "cacheManager")
        public CompletableFuture<Integer> future() {
            app.statement("future");
            return CompletableFuture.completedFuture(1);
        }

        @Cacheable(cacheNames = "prices", cacheManager = "cacheManager")
        public Mono<Integer> mono() {
            app.statement("mono");
            return Mono.just(1);
        }

        @Cacheable(cacheNames = "prices", cacheManager = "cacheManager")
        public Flow.Publisher<Integer> flow() {
            app.statement("flow");
            return subscriber -> app.subscriptions.incrementAndGet();
        }

        public int bypassCustomUnnamed() {
            return customUnnamed();
        }

        public Flow.Publisher<Integer> bypassFlow() {
            return flow();
        }

        public int bypassSupported() {
            return supported();
        }

        public int bypassUnwrapped() {
            return unwrapped();
        }

        public CompletableFuture<Integer> bypassFuture() {
            return future();
        }

        public Mono<Integer> bypassMono() {
            return mono();
        }
    }

    private static final class App implements AutoCloseable {
        private final AtomicLong clock = new AtomicLong(1_000);
        private final AtomicReference<CorrelationContext> active = new AtomicReference<>(CorrelationContext.NONE);
        private final AtomicInteger statements = new AtomicInteger();
        private final AtomicInteger subscriptions = new AtomicInteger();
        private final RuntimeJournal journal = new RuntimeJournal(
                new RuntimeJournalSettings(true, 1000, 10000000, 1000, 10, 10, JournalSource.all()),
                RunIdentity.start());
        private final GenericApplicationContext context;
        private final RuntimeInsightsController controller;

        App(boolean reactive, boolean proxyCaching, Consumer<GenericApplicationContext> customize) {
            context = context(reactive);
            CacheActivityRecorder recorder = new CacheActivityRecorder(true, 100, clock::get);
            recorder.setRuntimeEventSink(journal);
            recorder.setCorrelationContextProvider(active::get);
            context.registerBean(RuntimeJournal.class, () -> journal);
            context.registerBean(CacheActivityRecorder.class, () -> recorder);
            context.registerBean(SqlTraceRecorder.class, () -> {
                SqlTraceRecorder sql = new SqlTraceRecorder(true, true, false, false, 8, 100, 2000, 200, 5);
                sql.registerDataSource("db");
                return sql;
            });
            context.registerBean(
                    CacheActivityCacheManagerBeanPostProcessor.class,
                    () -> new CacheActivityCacheManagerBeanPostProcessor(
                            context.getBeanProvider(CacheActivityRecorder.class),
                            context.getBeanProvider(BootUiSelfDataFilter.class)));
            context.registerBean(
                    "cacheManager",
                    ConcurrentMapCacheManager.class,
                    () -> new ConcurrentMapCacheManager("prices"),
                    definition -> definition.setPrimary(true));
            context.registerBean(Operations.class, () -> new Operations(this));
            if (proxyCaching)
                new AnnotatedBeanDefinitionReader(context)
                        .register(RuntimeInsightsControllerTests.CacheProxyConfiguration.class);
            customize.accept(context);
            context.refresh();
            controller = controller(context);
        }

        void request(String id, Runnable work) {
            long start = clock.incrementAndGet();
            active.set(CorrelationContext.forRequest(id));
            try {
                work.run();
                journal.offer(RuntimeEvent.of(
                        JournalSource.HTTP,
                        start,
                        5000000,
                        active.get(),
                        "worker",
                        null,
                        false,
                        new HttpPayload("GET", "/" + id, "/" + id, null, 200)));
            } finally {
                active.set(CorrelationContext.NONE);
            }
            try {
                assertThat(journal.awaitDrained(Duration.ofSeconds(5))).isTrue();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError(ex);
            }
        }

        void statement(String method) {
            statements.incrementAndGet();
            journal.offer(RuntimeEvent.of(
                    JournalSource.SQL,
                    clock.get(),
                    100,
                    active.get(),
                    "worker",
                    null,
                    false,
                    new SqlPayload(
                            "select * from prices",
                            null,
                            "db",
                            false,
                            ApplicationFrames.of(
                                    List.of(Operations.class.getName() + "." + method + "(Prices.java:1)")),
                            null,
                            1000)));
        }

        @Override
        public void close() {
            context.close();
            journal.close();
        }
    }
}
