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

    // ── SPRING-CONFIG-003: renamed/removed Boot 4 properties ─────────────────────

    @Test
    void renamedOrRemovedPropertiesFlagged() {
        RemovedOrRenamedPropertyRule rule = new RemovedOrRenamedPropertyRule();

        assertThat(rule.evaluate(context(env().withProperty("management.tracing.enabled", "true"))
                                .build())
                        .status())
                .isEqualTo("VIOLATION");
        assertThat(rule.evaluate(context(env().withProperty("server.undertow.threads.io", "4"))
                                .build())
                        .status())
                .isEqualTo("VIOLATION");
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
        assertThat(rule.evaluate(context(env().withProperty("spring.web.error.include-stacktrace", "never"))
                                .build())
                        .status())
                .isEqualTo("PASS");
    }

    // ── SPRING-WEB-005 ───────────────────────────────────────────────────────────

    @Test
    void httpClientTimeoutsRequireAClientBean() {
        HttpClientTimeoutsUnsetRule rule = new HttpClientTimeoutsUnsetRule();

        assertThat(rule.evaluate(context(env()).build()).status()).isEqualTo("PASS");
        assertThat(rule.evaluate(context(env()).restClientBeanPresent(true).build())
                        .status())
                .isEqualTo("VIOLATION");
        assertThat(rule.evaluate(context(env().withProperty("spring.http.clients.connect-timeout", "2s"))
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

        assertThat(rule.evaluate(context(env().withProperty("management.endpoints.web.exposure.include", "*"))
                                .build())
                        .status())
                .isEqualTo("VIOLATION");
        assertThat(rule.evaluate(context(env().withProperty("management.endpoints.web.exposure.include", "health,info"))
                                .build())
                        .status())
                .isEqualTo("PASS");
    }
}
