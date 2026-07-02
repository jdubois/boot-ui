package io.github.jdubois.bootui.autoconfigure.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.spring.SpringModel.BeanRef;
import io.github.jdubois.bootui.autoconfigure.spring.SpringModel.CacheManagerRef;
import io.github.jdubois.bootui.core.dto.SpringRuleResultDto;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Focused coverage for the rules added or corrected during the Spring Advisor audit. */
class SpringRulesTests {

    private static MockEnvironment env() {
        return new MockEnvironment();
    }

    private static SpringContext.Builder context(MockEnvironment environment) {
        return SpringContext.builder(environment).beanDefinitionCount(50);
    }

    // ── SPRING-WEB-002: only flag explicit server.shutdown=immediate ──────────────

    @Test
    void gracefulShutdownFlagsOnlyImmediate() {
        GracefulShutdownDisabledRule rule = new GracefulShutdownDisabledRule();

        assertThat(rule.evaluate(context(env().withProperty("server.shutdown", "immediate"))
                                .build())
                        .status())
                .isEqualTo("VIOLATION");
        assertThat(rule.evaluate(context(env().withProperty("server.shutdown", "graceful"))
                                .build())
                        .status())
                .isEqualTo("PASS");
        assertThat(rule.evaluate(context(env()).build()).status()).isEqualTo("PASS");
    }

    @Test
    void gracefulShutdownFlagsZeroedGracePeriod() {
        GracefulShutdownDisabledRule rule = new GracefulShutdownDisabledRule();

        // Zero grace period defeats graceful shutdown just like server.shutdown=immediate.
        assertThat(rule.evaluate(context(env().withProperty("spring.lifecycle.timeout-per-shutdown-phase", "0"))
                                .build())
                        .status())
                .isEqualTo("VIOLATION");
        assertThat(rule.evaluate(context(env().withProperty("spring.lifecycle.timeout-per-shutdown-phase", "0s"))
                                .build())
                        .status())
                .isEqualTo("VIOLATION");
        // A positive grace period (the 30s default or an explicit override) is fine.
        assertThat(rule.evaluate(context(env().withProperty("spring.lifecycle.timeout-per-shutdown-phase", "20s"))
                                .build())
                        .status())
                .isEqualTo("PASS");
        assertThat(rule.evaluate(context(env()).build()).status()).isEqualTo("PASS");
    }

    // ── SPRING-WIRING-003: union of Jackson 2 + Jackson 3 mapper beans ────────────

    @Test
    void duplicateJsonMappersFlaggedWithoutPrimary() {
        DuplicateObjectMapperRule rule = new DuplicateObjectMapperRule();

        SpringRuleResultDto twoNoPrimary = rule.evaluate(context(env())
                .objectMappers(List.of(new BeanRef("objectMapper", false), new BeanRef("jsonMapper", false)))
                .build());
        assertThat(twoNoPrimary.status()).isEqualTo("VIOLATION");

        SpringRuleResultDto onePrimary = rule.evaluate(context(env())
                .objectMappers(List.of(new BeanRef("objectMapper", true), new BeanRef("jsonMapper", false)))
                .build());
        assertThat(onePrimary.status()).isEqualTo("PASS");
    }

    // ── SPRING-WIRING-004: conventional names / AsyncConfigurer suppress ──────────

    @Test
    void taskExecutorAmbiguitySuppressedByConvention() {
        AmbiguousTaskExecutorRule rule = new AmbiguousTaskExecutorRule();
        List<BeanRef> two = List.of(new BeanRef("a", false), new BeanRef("b", false));

        assertThat(rule.evaluate(context(env()).taskExecutors(two).build()).status())
                .isEqualTo("VIOLATION");
        assertThat(rule.evaluate(context(env())
                                .taskExecutors(List.of(new BeanRef("taskExecutor", false), new BeanRef("b", false)))
                                .build())
                        .status())
                .isEqualTo("PASS");
        assertThat(rule.evaluate(context(env())
                                .taskExecutors(two)
                                .asyncConfigurerPresent(true)
                                .build())
                        .status())
                .isEqualTo("PASS");
    }

    // ── SPRING-WIRING-006: multiple transaction managers ─────────────────────────

    @Test
    void transactionManagerAmbiguityFlaggedUnlessResolved() {
        AmbiguousTransactionManagerRule rule = new AmbiguousTransactionManagerRule();

        assertThat(rule.evaluate(context(env())
                                .transactionManagers(List.of(new BeanRef("a", false), new BeanRef("b", false)))
                                .build())
                        .status())
                .isEqualTo("VIOLATION");
        assertThat(rule.evaluate(context(env())
                                .transactionManagers(
                                        List.of(new BeanRef("transactionManager", false), new BeanRef("b", false)))
                                .build())
                        .status())
                .isEqualTo("PASS");
        assertThat(rule.evaluate(context(env())
                                .transactionManagers(List.of(new BeanRef("a", false), new BeanRef("b", false)))
                                .transactionManagementConfigurerPresent(true)
                                .build())
                        .status())
                .isEqualTo("PASS");
    }

    // ── SPRING-WIRING-007: RestTemplate present ──────────────────────────────────

    @Test
    void restTemplatePresenceFlagged() {
        RestTemplateInUseRule rule = new RestTemplateInUseRule();

        assertThat(rule.evaluate(context(env())
                                .restTemplates(List.of(new BeanRef("restTemplate", false)))
                                .build())
                        .status())
                .isEqualTo("VIOLATION");
        assertThat(rule.evaluate(context(env()).build()).status()).isEqualTo("PASS");
    }

    // ── SPRING-WIRING-008: default-package components ────────────────────────────

    @Test
    void defaultPackageComponentsFlagged() {
        DefaultPackageComponentsRule rule = new DefaultPackageComponentsRule();

        assertThat(rule.evaluate(context(env())
                                .defaultPackageBeans(List.of("rootBean"))
                                .build())
                        .status())
                .isEqualTo("VIOLATION");
        assertThat(rule.evaluate(context(env()).build()).status()).isEqualTo("PASS");
    }

    // ── SPRING-WIRING-009: public mutable fields on singleton beans ──────────────

    @Test
    void mutableSingletonFieldsFlagged() {
        MutableSingletonFieldRule rule = new MutableSingletonFieldRule();

        assertThat(rule.evaluate(context(env())
                                .mutableSingletonFields(List.of("com.example.Counter#hits"))
                                .build())
                        .status())
                .isEqualTo("VIOLATION");
        assertThat(rule.evaluate(context(env()).build()).status()).isEqualTo("PASS");
    }

    // ── SPRING-CONFIG-003: renamed/removed Boot 4 properties ─────────────────────

    @Test
    void renamedOrRemovedPropertiesFlagged() {
        RemovedOrRenamedPropertyRule rule = new RemovedOrRenamedPropertyRule();

        assertThat(rule.evaluate(context(env().withProperty("spring.dao.exceptiontranslation.enabled", "true"))
                                .build())
                        .status())
                .isEqualTo("VIOLATION");
        assertThat(rule.evaluate(context(env().withProperty("server.undertow.threads.io", "4"))
                                .build())
                        .status())
                .isEqualTo("VIOLATION");
        // management.tracing.enabled is a valid Boot 4 key again, so it must no longer be flagged.
        assertThat(rule.evaluate(context(env().withProperty("management.tracing.enabled", "true"))
                                .build())
                        .status())
                .isEqualTo("PASS");
        assertThat(rule.evaluate(context(env()).build()).status()).isEqualTo("PASS");
    }

    // ── SPRING-CONFIG-004 / 005 ──────────────────────────────────────────────────

    @Test
    void applicationNameAndConfigOnNotFound() {
        MissingApplicationNameRule nameRule = new MissingApplicationNameRule();
        assertThat(nameRule.evaluate(context(env()).build()).status()).isEqualTo("VIOLATION");
        assertThat(nameRule.evaluate(context(env().withProperty("spring.application.name", "svc"))
                                .build())
                        .status())
                .isEqualTo("PASS");

        ConfigOnNotFoundIgnoreRule notFoundRule = new ConfigOnNotFoundIgnoreRule();
        assertThat(notFoundRule
                        .evaluate(context(env().withProperty("spring.config.on-not-found", "ignore"))
                                .build())
                        .status())
                .isEqualTo("VIOLATION");
        assertThat(notFoundRule.evaluate(context(env()).build()).status()).isEqualTo("PASS");
    }

    // ── SPRING-PROFILE-003 ───────────────────────────────────────────────────────

    @Test
    void profileValidationDisabledFlagged() {
        ProfileValidationDisabledRule rule = new ProfileValidationDisabledRule();
        assertThat(rule.evaluate(context(env().withProperty("spring.profiles.validate", "false"))
                                .build())
                        .status())
                .isEqualTo("VIOLATION");
        assertThat(rule.evaluate(context(env()).build()).status()).isEqualTo("PASS");
    }

    // ── SPRING-PERF-005 / 006 ────────────────────────────────────────────────────

    @Test
    void schedulerPoolAndAsyncQueue() {
        SchedulerPoolTooSmallRule scheduler = new SchedulerPoolTooSmallRule();
        assertThat(scheduler
                        .evaluate(context(env()).schedulingEnabled(true).build())
                        .status())
                .isEqualTo("VIOLATION");
        assertThat(scheduler
                        .evaluate(context(env().withProperty("spring.task.scheduling.pool.size", "4"))
                                .schedulingEnabled(true)
                                .build())
                        .status())
                .isEqualTo("PASS");
        assertThat(scheduler
                        .evaluate(context(env().withProperty("spring.threads.virtual.enabled", "true"))
                                .schedulingEnabled(true)
                                .build())
                        .status())
                .isEqualTo("PASS");

        UnboundedAsyncQueueRule async = new UnboundedAsyncQueueRule();
        assertThat(async.evaluate(context(env()).asyncEnabled(true).build()).status())
                .isEqualTo("VIOLATION");
        assertThat(async.evaluate(context(env().withProperty("spring.task.execution.pool.queue-capacity", "100"))
                                .asyncEnabled(true)
                                .build())
                        .status())
                .isEqualTo("PASS");
    }

    // ── SPRING-CACHE-001 ─────────────────────────────────────────────────────────

    @Test
    void inMemoryCacheManagerFlaggedOnlyWhenAllInMemory() {
        InMemoryCacheManagerRule rule = new InMemoryCacheManagerRule();

        assertThat(rule.evaluate(context(env())
                                .cachingEnabled(true)
                                .cacheManagers(List.of(new CacheManagerRef(
                                        "cacheManager",
                                        "org.springframework.cache.concurrent.ConcurrentMapCacheManager")))
                                .build())
                        .status())
                .isEqualTo("VIOLATION");

        assertThat(rule.evaluate(context(env())
                                .cachingEnabled(true)
                                .cacheManagers(List.of(
                                        new CacheManagerRef(
                                                "dev",
                                                "org.springframework.cache.concurrent.ConcurrentMapCacheManager"),
                                        new CacheManagerRef(
                                                "real", "com.github.benmanes.caffeine.CaffeineCacheManager")))
                                .build())
                        .status())
                .isEqualTo("PASS");

        assertThat(rule.evaluate(context(env()).cachingEnabled(true).build()).status())
                .isEqualTo("PASS");
    }

    // ── SPRING-WEB-004 ───────────────────────────────────────────────────────────

    @Test
    void errorDetailsExposedFlagsBothPrefixes() {
        ErrorDetailsExposedRule rule = new ErrorDetailsExposedRule();

        assertThat(rule.evaluate(context(env().withProperty("spring.web.error.include-stacktrace", "always"))
                                .build())
                        .status())
                .isEqualTo("VIOLATION");
        assertThat(rule.evaluate(context(env().withProperty("server.error.include-message", "always"))
                                .build())
                        .status())
                .isEqualTo("VIOLATION");
        assertThat(rule.evaluate(context(env().withProperty("server.error.include-exception", "true"))
                                .build())
                        .status())
                .isEqualTo("VIOLATION");
        assertThat(rule.evaluate(context(env().withProperty("spring.web.error.include-stacktrace", "never"))
                                .build())
                        .status())
                .isEqualTo("PASS");
        assertThat(rule.evaluate(context(env().withProperty("server.error.include-exception", "false"))
                                .build())
                        .status())
                .isEqualTo("PASS");
    }

    // ── SPRING-CONFIG-002: debug/trace flags and verbose framework logging ───────

    @Test
    void verboseLoggingFlaggedForFlagsAndLevels() {
        DebugOrTraceLoggingRule rule = new DebugOrTraceLoggingRule();

        assertThat(rule.evaluate(context(env().withProperty("debug", "true")).build())
                        .status())
                .isEqualTo("VIOLATION");
        assertThat(rule.evaluate(context(env().withProperty("logging.level.org.springframework", "DEBUG"))
                                .build())
                        .status())
                .isEqualTo("VIOLATION");
        assertThat(rule.evaluate(context(env().withProperty("logging.level.root", "trace"))
                                .build())
                        .status())
                .isEqualTo("VIOLATION");
        assertThat(rule.evaluate(context(env().withProperty("logging.level.org.springframework", "INFO"))
                                .build())
                        .status())
                .isEqualTo("PASS");
        assertThat(rule.evaluate(context(env()).build()).status()).isEqualTo("PASS");
    }

    @Test
    void verboseLoggingIsMediumInProduction() {
        DebugOrTraceLoggingRule rule = new DebugOrTraceLoggingRule();

        MockEnvironment prod = env();
        prod.setActiveProfiles("prod");
        prod.setProperty("debug", "true");
        SpringRuleResultDto result = rule.evaluate(context(prod).build());

        assertThat(result.status()).isEqualTo("VIOLATION");
        assertThat(result.severity()).isEqualTo("MEDIUM");

        // Outside a production-like profile the declared LOW severity is unchanged.
        assertThat(rule.evaluate(context(env().withProperty("debug", "true")).build())
                        .severity())
                .isEqualTo("LOW");
    }

    // ── SPRING-WEB-005 ───────────────────────────────────────────────────────────

    @Test
    void httpClientTimeoutsRequireAClientBean() {
        HttpClientTimeoutsUnsetRule rule = new HttpClientTimeoutsUnsetRule();

        assertThat(rule.evaluate(context(env()).build()).status()).isEqualTo("PASS");
        assertThat(rule.evaluate(context(env()).restClientBeanPresent(true).build())
                        .status())
                .isEqualTo("VIOLATION");
        // Only one of the two timeouts is still a violation: outbound calls can still hang.
        assertThat(rule.evaluate(context(env().withProperty("spring.http.clients.connect-timeout", "2s"))
                                .restClientBeanPresent(true)
                                .build())
                        .status())
                .isEqualTo("VIOLATION");
        assertThat(rule.evaluate(context(env().withProperty("spring.http.clients.read-timeout", "5s"))
                                .restClientBeanPresent(true)
                                .build())
                        .status())
                .isEqualTo("VIOLATION");
        assertThat(rule.evaluate(context(env().withProperty("spring.http.clients.connect-timeout", "2s")
                                        .withProperty("spring.http.clients.read-timeout", "5s"))
                                .restClientBeanPresent(true)
                                .build())
                        .status())
                .isEqualTo("PASS");
    }

    // ── SPRING-WEB-006 ───────────────────────────────────────────────────────────

    @Test
    void forwardHeadersStrategyRequiredUnderProdProfile() {
        ForwardHeadersStrategyUnsetRule rule = new ForwardHeadersStrategyUnsetRule();

        MockEnvironment prod = env();
        prod.setActiveProfiles("prod");
        assertThat(rule.evaluate(context(prod).build()).status()).isEqualTo("VIOLATION");

        MockEnvironment prodConfigured = env();
        prodConfigured.setActiveProfiles("prod");
        prodConfigured.withProperty("server.forward-headers-strategy", "framework");
        assertThat(rule.evaluate(context(prodConfigured).build()).status()).isEqualTo("PASS");

        assertThat(rule.evaluate(context(env()).build()).status()).isEqualTo("PASS");
    }

    // ── SPRING-WEB-007 ───────────────────────────────────────────────────────────

    @Test
    void tomcatThreadCapRedundantWithVirtualThreads() {
        RedundantTomcatThreadsRule rule = new RedundantTomcatThreadsRule();

        assertThat(rule.evaluate(context(env().withProperty("spring.threads.virtual.enabled", "true")
                                        .withProperty("server.tomcat.threads.max", "200"))
                                .build())
                        .status())
                .isEqualTo("VIOLATION");
        assertThat(rule.evaluate(context(env().withProperty("spring.threads.virtual.enabled", "true"))
                                .build())
                        .status())
                .isEqualTo("PASS");
    }

    // ── SPRING-MGMT-001 ──────────────────────────────────────────────────────────

    @Test
    void actuatorExposeAllFlagged() {
        ActuatorExposeAllRule rule = new ActuatorExposeAllRule();

        SpringRuleResultDto exposeAll =
                rule.evaluate(context(env().withProperty("management.endpoints.web.exposure.include", "*"))
                        .build());
        assertThat(exposeAll.status()).isEqualTo("VIOLATION");
        assertThat(exposeAll.severity()).isEqualTo("MEDIUM");
        assertThat(rule.evaluate(context(env().withProperty("management.endpoints.web.exposure.include", "health,info"))
                                .build())
                        .status())
                .isEqualTo("PASS");
        // Disabled management web port: exposure no longer reachable, so not flagged.
        assertThat(rule.evaluate(context(env().withProperty("management.endpoints.web.exposure.include", "*")
                                        .withProperty("management.server.port", "-1"))
                                .build())
                        .status())
                .isEqualTo("PASS");
        // exclude=* cancels the wildcard include, so nothing is actually exposed.
        assertThat(rule.evaluate(context(env().withProperty("management.endpoints.web.exposure.include", "*")
                                        .withProperty("management.endpoints.web.exposure.exclude", "*"))
                                .build())
                        .status())
                .isEqualTo("PASS");
    }

    @Test
    void actuatorExposeAllIsHighInProductionOnApplicationPort() {
        ActuatorExposeAllRule rule = new ActuatorExposeAllRule();

        MockEnvironment prod = env();
        prod.setActiveProfiles("prod");
        prod.withProperty("management.endpoints.web.exposure.include", "*");
        SpringRuleResultDto result = rule.evaluate(context(prod).build());
        assertThat(result.status()).isEqualTo("VIOLATION");
        assertThat(result.severity()).isEqualTo("HIGH");

        // A separate management port keeps it at the base MEDIUM severity.
        MockEnvironment prodSeparate = env();
        prodSeparate.setActiveProfiles("prod");
        prodSeparate.withProperty("management.endpoints.web.exposure.include", "*");
        prodSeparate.withProperty("management.server.port", "9001");
        assertThat(rule.evaluate(context(prodSeparate).build()).severity()).isEqualTo("MEDIUM");
    }

    // ── SPRING-MGMT-002: explicitly-named sensitive endpoints ────────────────────

    @Test
    void sensitiveActuatorEndpointsFlaggedWhenExplicitlyExposed() {
        SensitiveActuatorEndpointsExposedRule rule = new SensitiveActuatorEndpointsExposedRule();

        assertThat(rule.evaluate(context(env().withProperty(
                                                "management.endpoints.web.exposure.include", "health,info,env,beans"))
                                .build())
                        .status())
                .isEqualTo("VIOLATION");
        // The wildcard case is owned by MGMT-001, so MGMT-002 stays silent for it.
        assertThat(rule.evaluate(context(env().withProperty("management.endpoints.web.exposure.include", "*"))
                                .build())
                        .status())
                .isEqualTo("PASS");
        assertThat(rule.evaluate(context(env().withProperty("management.endpoints.web.exposure.include", "health,info"))
                                .build())
                        .status())
                .isEqualTo("PASS");
        // Excluded again → not reachable.
        assertThat(rule.evaluate(context(env().withProperty("management.endpoints.web.exposure.include", "env")
                                        .withProperty("management.endpoints.web.exposure.exclude", "env"))
                                .build())
                        .status())
                .isEqualTo("PASS");
        // Access forced to none → not readable.
        assertThat(rule.evaluate(context(env().withProperty("management.endpoints.web.exposure.include", "env")
                                        .withProperty("management.endpoint.env.access", "none"))
                                .build())
                        .status())
                .isEqualTo("PASS");
    }

    // ── SPRING-MGMT-003: show-values / show-details = always ─────────────────────

    @Test
    void actuatorShowValuesAlwaysFlaggedWhenReadable() {
        ActuatorShowValuesAlwaysRule rule = new ActuatorShowValuesAlwaysRule();

        assertThat(rule.evaluate(context(env().withProperty("management.endpoints.web.exposure.include", "env")
                                        .withProperty("management.endpoint.env.show-values", "ALWAYS"))
                                .build())
                        .status())
                .isEqualTo("VIOLATION");
        assertThat(rule.evaluate(context(env().withProperty("management.endpoints.web.exposure.include", "health")
                                        .withProperty("management.endpoint.health.show-details", "always"))
                                .build())
                        .status())
                .isEqualTo("VIOLATION");
        // health is web-exposed by default even without an explicit include list.
        assertThat(rule.evaluate(context(env().withProperty("management.endpoint.health.show-details", "always"))
                                .build())
                        .status())
                .isEqualTo("VIOLATION");
        // show-values=ALWAYS but the endpoint is not exposed → not reachable, so no finding.
        assertThat(rule.evaluate(context(env().withProperty("management.endpoint.env.show-values", "ALWAYS"))
                                .build())
                        .status())
                .isEqualTo("PASS");
        assertThat(rule.evaluate(context(env().withProperty("management.endpoints.web.exposure.include", "env")
                                        .withProperty("management.endpoint.env.show-values", "WHEN_AUTHORIZED"))
                                .build())
                        .status())
                .isEqualTo("PASS");
    }

    // ── SPRING-MGMT-004: shutdown / heapdump reachable ───────────────────────────

    @Test
    void dangerousActuatorEndpointsFlagged() {
        DangerousActuatorEndpointsAccessibleRule rule = new DangerousActuatorEndpointsAccessibleRule();

        // Legacy enabled flag + exposed → write reachable.
        assertThat(rule.evaluate(context(env().withProperty("management.endpoints.web.exposure.include", "shutdown")
                                        .withProperty("management.endpoint.shutdown.enabled", "true"))
                                .build())
                        .status())
                .isEqualTo("VIOLATION");
        // Boot 4 access model.
        assertThat(rule.evaluate(context(env().withProperty("management.endpoints.web.exposure.include", "shutdown")
                                        .withProperty("management.endpoint.shutdown.access", "unrestricted"))
                                .build())
                        .status())
                .isEqualTo("VIOLATION");
        // Heapdump defaults to read-only access, so being exposed is enough.
        assertThat(rule.evaluate(context(env().withProperty("management.endpoints.web.exposure.include", "heapdump"))
                                .build())
                        .status())
                .isEqualTo("VIOLATION");
        // Shutdown exposed but left at default access (none) → not reachable, no finding.
        assertThat(rule.evaluate(context(env().withProperty("management.endpoints.web.exposure.include", "*")
                                        .withProperty("management.endpoints.web.exposure.exclude", "heapdump"))
                                .build())
                        .status())
                .isEqualTo("PASS");
    }

    @Test
    void shutdownOnApplicationPortInProductionIsCritical() {
        DangerousActuatorEndpointsAccessibleRule rule = new DangerousActuatorEndpointsAccessibleRule();

        MockEnvironment prod = env();
        prod.setActiveProfiles("prod");
        prod.withProperty("management.endpoints.web.exposure.include", "shutdown");
        prod.withProperty("management.endpoint.shutdown.access", "unrestricted");
        SpringRuleResultDto result = rule.evaluate(context(prod).build());
        assertThat(result.status()).isEqualTo("VIOLATION");
        assertThat(result.severity()).isEqualTo("CRITICAL");
    }

    // ── SPRING-JPA-001: Open Session in View ─────────────────────────────────────

    @Test
    void openSessionInViewFlaggedOnlyWithJpaAndWeb() {
        OpenSessionInViewEnabledRule rule = new OpenSessionInViewEnabledRule();

        // No EntityManagerFactory / DispatcherServlet → inapplicable.
        assertThat(rule.evaluate(context(env()).build()).status()).isEqualTo("SKIPPED");

        // JPA + web present, property absent → defaults to enabled → violation.
        assertThat(rule.evaluate(context(env())
                                .entityManagerFactoryPresent(true)
                                .dispatcherServletPresent(true)
                                .build())
                        .status())
                .isEqualTo("VIOLATION");
        // Explicit true → violation.
        assertThat(rule.evaluate(context(env().withProperty("spring.jpa.open-in-view", "true"))
                                .entityManagerFactoryPresent(true)
                                .dispatcherServletPresent(true)
                                .build())
                        .status())
                .isEqualTo("VIOLATION");
        // Explicit false → pass.
        assertThat(rule.evaluate(context(env().withProperty("spring.jpa.open-in-view", "false"))
                                .entityManagerFactoryPresent(true)
                                .dispatcherServletPresent(true)
                                .build())
                        .status())
                .isEqualTo("PASS");
    }

    @Test
    void openSessionInViewIsHighInProduction() {
        OpenSessionInViewEnabledRule rule = new OpenSessionInViewEnabledRule();

        MockEnvironment prod = env();
        prod.setActiveProfiles("prod");
        SpringRuleResultDto result = rule.evaluate(context(prod)
                .entityManagerFactoryPresent(true)
                .dispatcherServletPresent(true)
                .build());
        assertThat(result.status()).isEqualTo("VIOLATION");
        assertThat(result.severity()).isEqualTo("HIGH");
    }

    // ── SPRING-DATA-001: in-memory datasource in production ──────────────────────

    @Test
    void inMemoryDatasourceSkippedOutsideProduction() {
        InMemoryDatasourceInProductionRule rule = new InMemoryDatasourceInProductionRule();

        assertThat(rule.evaluate(context(env().withProperty("spring.datasource.url", "jdbc:h2:mem:testdb"))
                                .build())
                        .status())
                .isEqualTo("SKIPPED");
    }

    @Test
    void inMemoryDatasourceFlaggedInProductionForEachEngine() {
        InMemoryDatasourceInProductionRule rule = new InMemoryDatasourceInProductionRule();

        MockEnvironment h2 = env();
        h2.setActiveProfiles("prod");
        h2.setProperty("spring.datasource.url", "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1");
        assertThat(rule.evaluate(context(h2).build()).status()).isEqualTo("VIOLATION");

        MockEnvironment hsqldb = env();
        hsqldb.setActiveProfiles("production");
        hsqldb.setProperty("spring.datasource.url", "jdbc:hsqldb:mem:testdb");
        assertThat(rule.evaluate(context(hsqldb).build()).status()).isEqualTo("VIOLATION");

        MockEnvironment derby = env();
        derby.setActiveProfiles("prod");
        derby.setProperty("spring.datasource.url", "jdbc:derby:memory:testdb;create=true");
        assertThat(rule.evaluate(context(derby).build()).status()).isEqualTo("VIOLATION");
    }

    @Test
    void inMemoryDatasourcePassesInProductionForARealDatabase() {
        InMemoryDatasourceInProductionRule rule = new InMemoryDatasourceInProductionRule();

        MockEnvironment prod = env();
        prod.setActiveProfiles("prod");
        prod.setProperty("spring.datasource.url", "jdbc:postgresql://db.internal:5432/app");
        assertThat(rule.evaluate(context(prod).build()).status()).isEqualTo("PASS");

        // File-backed H2 (not jdbc:h2:mem:) is a legitimate durable embedded deployment.
        MockEnvironment fileH2 = env();
        fileH2.setActiveProfiles("prod");
        fileH2.setProperty("spring.datasource.url", "jdbc:h2:file:/var/data/app");
        assertThat(rule.evaluate(context(fileH2).build()).status()).isEqualTo("PASS");

        MockEnvironment noUrl = env();
        noUrl.setActiveProfiles("prod");
        assertThat(rule.evaluate(context(noUrl).build()).status()).isEqualTo("PASS");
    }
}
