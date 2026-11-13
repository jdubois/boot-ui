package io.github.jdubois.bootui.autoconfigure.spring;

import static io.github.jdubois.bootui.autoconfigure.spring.SpringObservations.Fact.*;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.config.BootUiActuatorDefaultsEnvironmentPostProcessor;
import io.github.jdubois.bootui.autoconfigure.spring.SpringModel.BeanRef;
import io.github.jdubois.bootui.autoconfigure.spring.SpringModel.CacheManagerRef;
import io.github.jdubois.bootui.autoconfigure.spring.SpringModel.PooledExecutorRef;
import io.github.jdubois.bootui.autoconfigure.spring.SpringObservations.AsyncSelection;
import io.github.jdubois.bootui.autoconfigure.spring.SpringObservations.Fact;
import io.github.jdubois.bootui.core.dto.SpringRuleResultDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

/** Requirement boundaries; real discovery/provenance is covered separately in SpringInventoryTests. */
class SpringRulesTests {
    static SpringObservations facts(Map<Fact, Object> facts) {
        return new SpringObservations(facts, List.of());
    }

    static SpringContext.Builder context(MockEnvironment env, Map<Fact, Object> evidence) {
        return SpringContext.builder(env).observations(facts(evidence));
    }

    static SpringRuleResultDto evaluate(SpringRule rule, MockEnvironment env, Map<Fact, Object> evidence) {
        return rule.evaluate(context(env, evidence).build());
    }

    static MockEnvironment env(String key, String value) {
        return new MockEnvironment().withProperty(key, value);
    }

    static final Map<Fact, Object> ENDPOINT_EVIDENCE =
            Map.of(ENDPOINTS, Set.of("health", "env", "configprops", "beans", "heapdump", "shutdown"));
    static final String TPTE = "org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor";
    static final List<BeanRef> TWO = List.of(new BeanRef("first", false), new BeanRef("second", false));

    @Test
    void registryPinsAllFortyFourRulesAndSevenRetirements() {
        var ids = SpringRuleRegistry.activeRules().stream()
                .map(rule -> rule.definition().id())
                .toList();
        assertThat(SpringRules.RETIRED_RULE_IDS)
                .isEqualTo(SpringRuleRegistry.RETIRED_RULE_IDS)
                .isEqualTo(SpringScanner.RETIRED_RULE_IDS)
                .containsExactlyInAnyOrder(
                        "SPRING-PROFILE-001",
                        "SPRING-PERF-004",
                        "SPRING-WEB-003",
                        "SPRING-WEB-006",
                        "SPRING-REACTIVE-001",
                        "SPRING-REACTIVE-002",
                        "SPRING-CONFIG-001");
        assertThat(ids)
                .hasSize(44)
                .doesNotHaveDuplicates()
                .contains(
                        "SPRING-WIRING-010",
                        "SPRING-WIRING-011",
                        "SPRING-REACTIVE-003",
                        "SPRING-CONFIG-007",
                        "SPRING-CONFIG-008",
                        "SPRING-PERF-007",
                        "SPRING-PERF-008",
                        "SPRING-PERF-009",
                        "SPRING-PERF-011",
                        "SPRING-WEB-008")
                .doesNotContainAnyElementsOf(SpringRuleRegistry.RETIRED_RULE_IDS);
        assertThat(SpringRuleRegistry.activeRules().stream()
                        .filter(r -> Set.of(
                                        "SPRING-WIRING-003",
                                        "SPRING-WIRING-004",
                                        "SPRING-WIRING-005",
                                        "SPRING-WIRING-006",
                                        "SPRING-CONFIG-006",
                                        "SPRING-CONFIG-008",
                                        "SPRING-PROFILE-002",
                                        "SPRING-PROFILE-003",
                                        "SPRING-PERF-001",
                                        "SPRING-PERF-002",
                                        "SPRING-PERF-005",
                                        "SPRING-PERF-007",
                                        "SPRING-CACHE-001",
                                        "SPRING-WEB-001",
                                        "SPRING-WEB-005")
                                .contains(r.definition().id())))
                .allSatisfy(rule -> assertThat(rule.definition().severity()).isEqualTo("INFO"));
    }

    @Test
    void factoryStateWinsOverPropertiesWithoutInferringActualOverrideOrCycle() {
        MockEnvironment env = env("spring.main.allow-bean-definition-overriding", "false")
                .withProperty("spring.main.allow-circular-references", "false");
        for (var pair : Map.of(
                        new BeanDefinitionOverridingRule(), OVERRIDING, new CircularReferencesAllowedRule(), CIRCULAR)
                .entrySet()) {
            assertThat(evaluate(pair.getKey(), env, Map.of(pair.getValue(), true))
                            .status())
                    .isEqualTo("VIOLATION");
            assertThat(evaluate(pair.getKey(), env, Map.of(pair.getValue(), false))
                            .status())
                    .isEqualTo("PASS");
            assertThat(evaluate(pair.getKey(), env, Map.of()).status()).isEqualTo("SKIPPED");
        }
    }

    @Test
    void candidateFlagsAliasesAndJacksonGenerationsHaveDifferentBoundaries() {
        var mapper = new DuplicateObjectMapperRule();
        BeanRef jackson2 = new BeanRef("objectMapper", false, true, false, true, true, List.of("mapper"), "jackson2");
        BeanRef jackson3 = new BeanRef("jsonMapper", false, true, false, true, true, List.of(), "jackson3");
        assertThat(mapper.evaluate(SpringContext.builder(new MockEnvironment())
                                .objectMappers(List.of(jackson2, jackson3))
                                .build())
                        .status())
                .isEqualTo("PASS");
        assertThat(mapper.evaluate(SpringContext.builder(new MockEnvironment())
                                .objectMappers(TWO)
                                .build())
                        .status())
                .isEqualTo("VIOLATION");
        assertThat(SpringModel.hasName(List.of(jackson2), "mapper")).isTrue();
        for (BeanRef secondary : List.of(
                new BeanRef("secondary", false, false, false, true),
                new BeanRef("secondary", false, true, false, false),
                new BeanRef("secondary", false, true, true, true))) {
            assertThat(SpringModel.hasResolvedCandidateMetadata(List.of(new BeanRef("main", false), secondary)))
                    .isTrue();
        }
        assertThat(SpringModel.hasResolvedCandidateMetadata(List.of(new BeanRef("a", true), new BeanRef("b", true))))
                .isFalse();
        BeanRef unknown = new BeanRef("manual", false, false, false, false, false, List.of(), "");
        assertThat(new AmbiguousDataSourceRule()
                        .evaluate(SpringContext.builder(new MockEnvironment())
                                .dataSources(List.of(unknown))
                                .build())
                        .status())
                .isEqualTo("SKIPPED");
        assertThat(new AmbiguousDataSourceRule()
                        .evaluate(SpringContext.builder(new MockEnvironment())
                                .dataSources(TWO)
                                .build())
                        .severity())
                .isEqualTo("INFO");
        var tx = new AmbiguousTransactionManagerRule();
        assertThat(tx.evaluate(SpringContext.builder(new MockEnvironment())
                                .transactionManagers(
                                        List.of(new BeanRef("transactionManager", false), new BeanRef("other", false)))
                                .build())
                        .status())
                .isEqualTo("VIOLATION");
        assertThat(tx.evaluate(SpringContext.builder(new MockEnvironment())
                                .transactionManagers(TWO)
                                .transactionManagementConfigurerPresent(true)
                                .build())
                        .status())
                .isEqualTo("SKIPPED");
    }

    @Test
    void packagingAndMutableFieldsRemainLowReviewPrompts() {
        var c = SpringContext.builder(new MockEnvironment())
                .defaultPackageBeans(List.of("product"))
                .mutableSingletonFields(List.of("app.Counter#count"))
                .restTemplates(TWO)
                .build();
        for (SpringRule rule : List.of(
                new DefaultPackageComponentsRule(), new MutableSingletonFieldRule(), new RestTemplateInUseRule())) {
            assertThat(rule.evaluate(c).status()).isEqualTo("VIOLATION");
            assertThat(rule.definition().severity()).isEqualTo("LOW");
        }
        assertThat(new RestTemplateInUseRule().definition().recommendation())
                .contains("Boot's RestClient.Builder")
                .doesNotContain("RestClient.create()");
        assertThat(new DefaultPackageComponentsRule().definition().description())
                .contains("does not itself");
        assertThat(new MutableSingletonFieldRule().definition().description()).contains("not proof");
    }

    @Test
    void deprecatedJackson2AutoConfigurationIsLowAndUnknownWithoutCompleteInventory() {
        var rule = new Jackson2AutoConfigurationActiveRule();
        assertThat(rule.definition().severity()).isEqualTo("LOW");
        assertThat(rule.definition().description()).contains("4.3", "not which mapper");
        var active = evaluate(rule, new MockEnvironment(), Map.of(JACKSON2_AUTOCONFIGURATION, true));
        assertThat(active.status()).isEqualTo("VIOLATION");
        assertThat(active.violationCount()).isEqualTo(1);
        assertThat(evaluate(rule, new MockEnvironment(), Map.of(JACKSON2_AUTOCONFIGURATION, false))
                        .status())
                .isEqualTo("PASS");
        assertThat(evaluate(rule, new MockEnvironment(), Map.of()).status()).isEqualTo("SKIPPED");
    }

    @Test
    void propertiesMigratorIsAnInformationalMigrationReminder() {
        var rule = new PropertiesMigratorPresentRule();
        assertThat(rule.definition().severity()).isEqualTo("INFO");
        assertThat(evaluate(rule, new MockEnvironment(), Map.of(PROPERTIES_MIGRATOR, true))
                        .status())
                .isEqualTo("VIOLATION");
        assertThat(evaluate(rule, new MockEnvironment(), Map.of(PROPERTIES_MIGRATOR, false))
                        .status())
                .isEqualTo("PASS");
        assertThat(evaluate(rule, new MockEnvironment(), Map.of()).status()).isEqualTo("PASS");
    }

    @Test
    void virtualThreadsBeforeJep491NeedExplicitEnablementApplicableExecutionAndAnOldJdk() {
        var rule = new VirtualThreadsBeforeJep491Rule();
        assertThat(rule.definition().severity()).isEqualTo("INFO");
        MockEnvironment enabled = env("spring.threads.virtual.enabled", "true");
        for (int version : List.of(21, 23))
            assertThat(rule.evaluate(context(enabled, Map.of(JAVA_FEATURE_VERSION, version))
                                    .virtualThreadsSupported(true)
                                    .dispatcherServletPresent(true)
                                    .build())
                            .status())
                    .as("JDK " + version)
                    .isEqualTo("VIOLATION");
        for (int version : List.of(24, 25))
            assertThat(rule.evaluate(context(enabled, Map.of(JAVA_FEATURE_VERSION, version))
                                    .virtualThreadsSupported(true)
                                    .bootApplicationTaskExecutorPresent(true)
                                    .build())
                            .status())
                    .isEqualTo("PASS");
        assertThat(rule.evaluate(context(
                                        env("spring.threads.virtual.enabled", "false"),
                                        Map.of(JAVA_FEATURE_VERSION, 21))
                                .virtualThreadsSupported(true)
                                .dispatcherServletPresent(true)
                                .build())
                        .status())
                .isEqualTo("PASS");
        assertThat(rule.evaluate(context(enabled, Map.of(JAVA_FEATURE_VERSION, 21))
                                .virtualThreadsSupported(true)
                                .reactive(true)
                                .build())
                        .status())
                .as("reactive HTTP alone is not routed to Boot's virtual threads")
                .isEqualTo("SKIPPED");
        assertThat(rule.evaluate(context(enabled, Map.of(JAVA_FEATURE_VERSION, 17))
                                .dispatcherServletPresent(true)
                                .build())
                        .status())
                .isEqualTo("SKIPPED");
        assertThat(rule.evaluate(context(enabled, Map.of())
                                .virtualThreadsSupported(true)
                                .dispatcherServletPresent(true)
                                .build())
                        .status())
                .isEqualTo("SKIPPED");
    }

    @Test
    void unlimitedMultipartRequiresAnUnboundedTotalRequestSizeOnServletApplications() {
        var rule = new UnlimitedMultipartRequestRule();
        assertThat(rule.definition().severity()).isEqualTo("LOW");
        var both = evaluate(
                rule, new MockEnvironment(), Map.of(MULTIPART_MAX_REQUEST_SIZE, -1L, MULTIPART_MAX_FILE_SIZE, -1L));
        assertThat(both.status()).isEqualTo("VIOLATION");
        assertThat(both.violationCount()).isEqualTo(1);
        assertThat(both.sampleViolations().get(0)).contains("no per-file limit");
        assertThat(evaluate(
                                rule,
                                new MockEnvironment(),
                                Map.of(MULTIPART_MAX_REQUEST_SIZE, -1L, MULTIPART_MAX_FILE_SIZE, 1_048_576L))
                        .status())
                .isEqualTo("VIOLATION");
        assertThat(evaluate(
                                rule,
                                new MockEnvironment(),
                                Map.of(MULTIPART_MAX_REQUEST_SIZE, 10_485_760L, MULTIPART_MAX_FILE_SIZE, -1L))
                        .status())
                .as("a finite total request size still bounds every file")
                .isEqualTo("PASS");
        assertThat(evaluate(rule, new MockEnvironment(), Map.of(MULTIPART_DISABLED, true))
                        .status())
                .isEqualTo("PASS");
        assertThat(evaluate(rule, new MockEnvironment(), Map.of()).status()).isEqualTo("SKIPPED");
        assertThat(rule.evaluate(context(new MockEnvironment(), Map.of(MULTIPART_MAX_REQUEST_SIZE, -1L))
                                .reactive(true)
                                .build())
                        .status())
                .isEqualTo("SKIPPED");
    }

    @Test
    void configurationAdviceIsQualifiedAndMigrationCatalogueKeepsLiveKeys() {
        var legacy = new RemovedOrRenamedPropertyRule();
        for (String key : SpringMigrationProperties.ENTRIES.keySet())
            assertThat(evaluate(legacy, env(key, "secret-value"), Map.of()).status())
                    .as(key)
                    .isEqualTo("VIOLATION");
        for (String key : List.of(
                "spring.dao.exceptiontranslation.enabled",
                "spring.jackson.read.accept-any-property-name",
                "spring.jackson.write.write-nan-as-strings",
                "server.servlet.encoding.mapping.fr",
                "spring.data.mongodb.gridfs.database",
                "spring.data.mongodb.auto-index-creation",
                "spring.data.mongodb.field-naming-strategy",
                "spring.data.mongodb.representation.big-decimal",
                "management.otlp.metrics.export.url",
                "management.opentelemetry.tracing.export.otlp.endpoint",
                "management.tracing.export.enabled",
                "spring.rabbitmq.listener.simple.retry.max-retries",
                "spring.kafka.retry.topic.backoff.jitter",
                "spring.jackson.json.read.allow-java-comments",
                "spring.thymeleaf.cache",
                "spring.data.redis.lettuce.cluster.refresh.period"))
            assertThat(evaluate(legacy, env(key, "true"), Map.of()).status())
                    .as(key)
                    .isEqualTo("PASS");
        assertThat(evaluate(legacy, env("spring.data.mongodb.additional-hosts[0]", "secret-host"), Map.of())
                        .status())
                .isEqualTo("VIOLATION");
        for (String child : List.of(
                "management.wavefront.api-token",
                "spring.jackson.parser.allow-comments",
                "management.otlp.tracing.headers.authorization")) {
            var result = evaluate(legacy, env(child, "secret-value"), Map.of());
            assertThat(result.status()).as(child).isEqualTo("VIOLATION");
            assertThat(result.toString()).doesNotContain("secret-value");
        }
        MockEnvironment relaxed = new MockEnvironment();
        relaxed.getPropertySources()
                .addFirst(new SystemEnvironmentPropertySource(
                        "systemEnvironment", Map.of("SPRING_CODEC_MAXINMEMORYSIZE", "-1")));
        assertThat(evaluate(legacy, relaxed, Map.of()).status()).isEqualTo("VIOLATION");
        assertThat(evaluate(legacy, relaxed, Map.of()).toString()).doesNotContain("secret-host");
        assertThat(evaluate(new MissingApplicationNameRule(), new MockEnvironment(), Map.of())
                        .status())
                .isEqualTo("VIOLATION");
        assertThat(evaluate(new MissingApplicationNameRule(), env("spring.application.name", "orders"), Map.of())
                        .status())
                .isEqualTo("PASS");
        assertThat(evaluate(new ConfigOnNotFoundIgnoreRule(), env("spring.config.on-not-found", "ignore"), Map.of())
                        .status())
                .isEqualTo("VIOLATION");
        assertThat(evaluate(
                                new Jackson2DefaultsCompatibilityRule(),
                                env("spring.jackson.use-jackson2-defaults", "true"),
                                Map.of())
                        .status())
                .isEqualTo("SKIPPED");
        assertThat(evaluate(
                                new Jackson2DefaultsCompatibilityRule(),
                                env("spring.jackson.use-jackson2-defaults", "true"),
                                Map.of(JACKSON3_CONFIGURATION, true))
                        .status())
                .isEqualTo("VIOLATION");
    }

    @Test
    void loggingAndProfilesDoNotInventRuntimeOrDeploymentState() {
        MockEnvironment prod = env("debug", "true");
        prod.setDefaultProfiles("production");
        assertThat(evaluate(new DebugOrTraceLoggingRule(), prod, Map.of()).severity())
                .isEqualTo("LOW");
        for (String set : List.of("", "true", "off", "no", "FALSE", "pw:api"))
            assertThat(evaluate(new DebugOrTraceLoggingRule(), env("debug", set), Map.of())
                            .status())
                    .as("debug=" + set)
                    .isEqualTo("VIOLATION");
        assertThat(evaluate(new DebugOrTraceLoggingRule(), env("trace", ""), Map.of())
                        .status())
                .isEqualTo("VIOLATION");
        assertThat(evaluate(new DebugOrTraceLoggingRule(), env("debug", "false"), Map.of())
                        .status())
                .isEqualTo("PASS");
        assertThat(new DevToolsOnClasspathRule()
                        .evaluate(SpringContext.builder(prod)
                                .devToolsPresent(true)
                                .build())
                        .status())
                .isEqualTo("VIOLATION");
        assertThat(new DevToolsOnClasspathRule()
                        .evaluate(SpringContext.builder(new MockEnvironment())
                                .devToolsPresent(true)
                                .build())
                        .status())
                .isEqualTo("PASS");
        assertThat(evaluate(new ProfileValidationDisabledRule(), env("spring.profiles.validate", "false"), Map.of())
                        .severity())
                .isEqualTo("INFO");
        for (String level : List.of("DEBUG", "TRACE", "ALL"))
            assertThat(evaluate(new DebugOrTraceLoggingRule(), env("logging.level.root", level), Map.of())
                            .status())
                    .isEqualTo("VIOLATION");
        assertThat(evaluate(new DebugOrTraceLoggingRule(), env("logging.level.root", "INFO"), Map.of())
                        .status())
                .isEqualTo("PASS");
    }

    @Test
    void virtualThreadsRemainAnOptionalApplicableOpportunity() {
        var rule = new VirtualThreadsAvailableRule();
        for (int jdk : List.of(21, 23))
            assertThat(rule.evaluate(context(new MockEnvironment(), Map.of(JAVA_FEATURE_VERSION, jdk))
                                    .virtualThreadsSupported(true)
                                    .dispatcherServletPresent(true)
                                    .build())
                            .status())
                    .as("JDK " + jdk + " is skipped to avoid Catch-22 with SPRING-PERF-007")
                    .isEqualTo("SKIPPED");
        for (int jdk : List.of(24, 25))
            assertThat(rule.evaluate(context(new MockEnvironment(), Map.of(JAVA_FEATURE_VERSION, jdk))
                                    .virtualThreadsSupported(true)
                                    .dispatcherServletPresent(true)
                                    .build())
                            .status())
                    .as("JDK " + jdk)
                    .isEqualTo("VIOLATION");
        assertThat(rule.evaluate(SpringContext.builder(new MockEnvironment())
                                .virtualThreadsSupported(true)
                                .dispatcherServletPresent(true)
                                .build())
                        .status())
                .isEqualTo("VIOLATION");
        assertThat(rule.evaluate(SpringContext.builder(new MockEnvironment())
                                .virtualThreadsSupported(true)
                                .reactive(true)
                                .build())
                        .status())
                .isEqualTo("SKIPPED");
        assertThat(rule.evaluate(SpringContext.builder(new MockEnvironment())
                                .virtualThreadsSupported(true)
                                .reactive(true)
                                .bootApplicationTaskExecutorPresent(true)
                                .build())
                        .status())
                .isEqualTo("VIOLATION");
        for (String value : List.of("true", "false"))
            assertThat(rule.evaluate(SpringContext.builder(env("spring.threads.virtual.enabled", value))
                                    .virtualThreadsSupported(true)
                                    .dispatcherServletPresent(true)
                                    .build())
                            .status())
                    .isEqualTo("PASS");
        assertThat(rule.evaluate(SpringContext.builder(new MockEnvironment())
                                .virtualThreadsSupported(false)
                                .dispatcherServletPresent(true)
                                .build())
                        .status())
                .isEqualTo("SKIPPED");
        assertThat(new VirtualThreadsOverriddenByPoolRule()
                        .evaluate(SpringContext.builder(env("spring.threads.virtual.enabled", "true"))
                                .virtualThreadsSupported(true)
                                .pooledTaskExecutors(
                                        List.of(new PooledExecutorRef("pool", "com.example.Config", TPTE, false)))
                                .build())
                        .severity())
                .isEqualTo("INFO");
    }

    @Test
    void pooledExecutorFindingNamesApplicationPoolsAndExcludesFrameworkOwnedOnes() {
        var rule = new VirtualThreadsOverriddenByPoolRule();
        var stomp = List.of(
                new PooledExecutorRef(
                        "clientInboundChannelExecutor",
                        "org.springframework.messaging.simp.config.AbstractMessageBrokerConfiguration",
                        TPTE,
                        true),
                new PooledExecutorRef(
                        "brokerChannelExecutor",
                        "org.springframework.messaging.simp.config.AbstractMessageBrokerConfiguration",
                        TPTE,
                        true));
        var app = new PooledExecutorRef("reportPool", "com.example.ReportConfig", TPTE, false);
        var unresolved = new PooledExecutorRef("registered", null, "com.example.CustomPool", false);
        MockEnvironment enabled = env("spring.threads.virtual.enabled", "true");
        assertThat(rule.evaluate(SpringContext.builder(enabled)
                                .virtualThreadsSupported(true)
                                .pooledTaskExecutors(stomp)
                                .build())
                        .status())
                .isEqualTo("PASS");
        var mixed = new ArrayList<>(stomp);
        mixed.add(app);
        mixed.add(unresolved);
        var result = rule.evaluate(SpringContext.builder(enabled)
                .virtualThreadsSupported(true)
                .pooledTaskExecutors(mixed)
                .build());
        assertThat(result.status()).isEqualTo("VIOLATION");
        assertThat(result.sampleViolations())
                .hasSize(2)
                .anySatisfy(detail -> assertThat(detail)
                        .startsWith("ThreadPoolTaskExecutor bean 'reportPool' declared by com.example.ReportConfig"))
                .anySatisfy(detail -> assertThat(detail)
                        .contains("'registered' of type com.example.CustomPool (declaring configuration not resolved)"))
                .noneSatisfy(detail -> assertThat(detail).contains("ChannelExecutor"));
        var many = java.util.stream.IntStream.range(0, 12)
                .mapToObj(i -> new PooledExecutorRef("pool" + i, "com.example.Config", TPTE, false))
                .toList();
        var counted = rule.evaluate(SpringContext.builder(enabled)
                .virtualThreadsSupported(true)
                .pooledTaskExecutors(many)
                .build());
        assertThat(counted.violationCount()).isEqualTo(12);
        assertThat(counted.sampleViolations()).hasSize(10);
        assertThat(rule.evaluate(SpringContext.builder(env("spring.threads.virtual.enabled", "false"))
                                .virtualThreadsSupported(true)
                                .pooledTaskExecutors(List.of(app))
                                .build())
                        .status())
                .isEqualTo("PASS");
    }

    @Test
    void asyncAdviceUsesProvenSelectionNotPoolPropertyAbsence() {
        var fallback = new AsyncWithoutCustomExecutorRule();
        var queue = new UnboundedAsyncQueueRule();
        var ambiguous = new AmbiguousTaskExecutorRule();
        for (SpringRule rule : List.of(fallback, queue, ambiguous))
            assertThat(rule.evaluate(SpringContext.builder(new MockEnvironment())
                                    .asyncEnabled(true)
                                    .build())
                            .status())
                    .isEqualTo("SKIPPED");
        assertThat(fallback.evaluate(context(
                                        env("spring.threads.virtual.enabled", "true"),
                                        Map.of(ASYNC_SELECTION, AsyncSelection.FRAMEWORK_FALLBACK))
                                .asyncEnabled(true)
                                .build())
                        .status())
                .isEqualTo("VIOLATION");
        assertThat(fallback.evaluate(context(new MockEnvironment(), Map.of(ASYNC_SELECTION, AsyncSelection.SELECTED))
                                .asyncEnabled(true)
                                .build())
                        .status())
                .isEqualTo("PASS");
        assertThat(ambiguous
                        .evaluate(context(new MockEnvironment(), Map.of(ASYNC_SELECTION, AsyncSelection.AMBIGUOUS))
                                .asyncEnabled(true)
                                .build())
                        .severity())
                .isEqualTo("INFO");
        assertThat(queue.evaluate(context(new MockEnvironment(), Map.of(ASYNC_QUEUE_CAPACITY, Integer.MAX_VALUE))
                                .asyncEnabled(true)
                                .build())
                        .status())
                .isEqualTo("VIOLATION");
        assertThat(queue.evaluate(context(new MockEnvironment(), Map.of(ASYNC_QUEUE_CAPACITY, 10))
                                .asyncEnabled(true)
                                .build())
                        .status())
                .isEqualTo("PASS");
    }

    @Test
    void schedulerRequiresMultipleRegisteredTasksAndObservedSelectedSize() {
        var rule = new SchedulerPoolTooSmallRule();
        assertThat(rule.evaluate(context(new MockEnvironment(), Map.of(SCHEDULER_POOL_SIZE, 1, SCHEDULED_TASK_COUNT, 2))
                                .schedulingEnabled(true)
                                .build())
                        .status())
                .isEqualTo("VIOLATION");
        for (Map<Fact, Object> facts : List.of(
                Map.<Fact, Object>of(SCHEDULER_POOL_SIZE, 1, SCHEDULED_TASK_COUNT, 1),
                Map.<Fact, Object>of(SCHEDULER_POOL_SIZE, 2, SCHEDULED_TASK_COUNT, 2)))
            assertThat(rule.evaluate(context(new MockEnvironment(), facts)
                                    .schedulingEnabled(true)
                                    .build())
                            .status())
                    .isEqualTo("PASS");
        assertThat(rule.evaluate(context(env("spring.task.scheduling.pool.size", "1"), Map.of())
                                .schedulingEnabled(true)
                                .build())
                        .status())
                .isEqualTo("SKIPPED");
    }

    @Test
    void cacheRuleDoesNotCallNoOpUnboundedOrRequireDistributedCaching() {
        var rule = new InMemoryCacheManagerRule();
        for (String type : List.of(
                "org.springframework.cache.support.NoOpCacheManager",
                "org.springframework.cache.caffeine.CaffeineCacheManager"))
            assertThat(rule.evaluate(SpringContext.builder(new MockEnvironment())
                                    .cachingEnabled(true)
                                    .cacheManagers(List.of(new CacheManagerRef("cache", type)))
                                    .build())
                            .status())
                    .isEqualTo("PASS");
        assertThat(rule.evaluate(SpringContext.builder(new MockEnvironment())
                                .cachingEnabled(true)
                                .cacheManagers(List.of(new CacheManagerRef("custom", "app.CustomCacheManager")))
                                .build())
                        .status())
                .isEqualTo("SKIPPED");
        assertThat(rule.evaluate(SpringContext.builder(new MockEnvironment())
                                .cachingEnabled(true)
                                .cacheManagers(List.of(new CacheManagerRef(
                                        "cache", "org.springframework.cache.concurrent.ConcurrentMapCacheManager")))
                                .build())
                        .status())
                .isEqualTo("VIOLATION");
    }

    @Test
    void originOpportunitiesRequireProvenanceAndRespectOptOutsOnBothStacks() {
        var rule = new ResponseCompressionDisabledRule();
        for (boolean reactive : List.of(false, true)) {
            assertThat(rule.evaluate(context(new MockEnvironment(), Map.of(BOOT_WEB_SERVER, true))
                                    .reactive(reactive)
                                    .build())
                            .status())
                    .isEqualTo("VIOLATION");
            assertThat(rule.evaluate(context(new MockEnvironment(), Map.of())
                                    .reactive(reactive)
                                    .build())
                            .status())
                    .isEqualTo("SKIPPED");
            for (String value : List.of("true", "false"))
                assertThat(rule.evaluate(
                                        context(env("server.compression.enabled", value), Map.of(BOOT_WEB_SERVER, true))
                                                .reactive(reactive)
                                                .build())
                                .status())
                        .isEqualTo("PASS");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0s", "PT0S"})
    void exactlyZeroGraceIsFlagged(String duration) {
        assertThat(evaluate(
                                new GracefulShutdownDisabledRule(),
                                env("spring.lifecycle.timeout-per-shutdown-phase", duration),
                                Map.of(BOOT_WEB_SERVER, true))
                        .status())
                .isEqualTo("VIOLATION");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1ns", "PT0.000000001S", "30s", "1"})
    void positiveGraceIsNotRoundedToZero(String duration) {
        assertThat(evaluate(
                                new GracefulShutdownDisabledRule(),
                                env("spring.lifecycle.timeout-per-shutdown-phase", duration),
                                Map.of(BOOT_WEB_SERVER, true))
                        .status())
                .isEqualTo("PASS");
    }

    @Test
    void errorDetailsUseBoot4NamespaceAndOnParamIsNotConfidentiality() {
        var rule = new ErrorDetailsExposedRule();
        for (String key : List.of("include-stacktrace", "include-message", "include-binding-errors"))
            for (String value : List.of("always", "on-param", "ON_PARAM", "onparam", "Always"))
                assertThat(evaluate(rule, env("spring.web.error." + key, value), Map.of(BOOT_ERROR_HANDLING, true))
                                .status())
                        .as(key + "=" + value)
                        .isEqualTo("VIOLATION");
        assertThat(evaluate(rule, env("spring.web.error.include-message", "NEVER"), Map.of(BOOT_ERROR_HANDLING, true))
                        .status())
                .isEqualTo("PASS");
        assertThat(evaluate(
                                rule,
                                env("spring.web.error.include-message", "sometimes"),
                                Map.of(BOOT_ERROR_HANDLING, true))
                        .status())
                .isEqualTo("ERROR");
        assertThat(evaluate(rule, env("spring.web.error.include-exception", "true"), Map.of(BOOT_ERROR_HANDLING, true))
                        .status())
                .isEqualTo("VIOLATION");
        assertThat(evaluate(rule, env("server.error.include-stacktrace", "always"), Map.of(BOOT_ERROR_HANDLING, true))
                        .status())
                .isEqualTo("PASS");
        assertThat(evaluate(rule, env("spring.web.error.include-stacktrace", "always"), Map.of())
                        .status())
                .isEqualTo("SKIPPED");
        assertThat(rule.definition().recommendation()).contains("never/false").contains("do not use on-param");
    }

    @Test
    void errorDetailsIgnoreDevToolsDevelopmentDefaultsButNotApplicationConfiguration() {
        var rule = new ErrorDetailsExposedRule();
        MockEnvironment devtools = new MockEnvironment();
        devtools.getPropertySources()
                .addLast(new MapPropertySource(
                        "devtools",
                        Map.of(
                                "spring.web.error.include-binding-errors", "always",
                                "spring.web.error.include-message", "always",
                                "spring.web.error.include-stacktrace", "always")));
        ConfigurationPropertySources.attach(devtools);
        assertThat(evaluate(rule, devtools, Map.of(BOOT_ERROR_HANDLING, true)).status())
                .isEqualTo("PASS");
        devtools.setProperty("spring.web.error.include-stacktrace", "on-param");
        var result = evaluate(rule, devtools, Map.of(BOOT_ERROR_HANDLING, true));
        assertThat(result.status()).isEqualTo("VIOLATION");
        assertThat(result.violationCount()).isEqualTo(1);
        MockEnvironment appNamedDevtools = new MockEnvironment();
        java.util.Properties properties = new java.util.Properties();
        properties.setProperty("spring.web.error.include-stacktrace", "always");
        appNamedDevtools
                .getPropertySources()
                .addFirst(new org.springframework.core.env.PropertiesPropertySource("devtools", properties));
        ConfigurationPropertySources.attach(appNamedDevtools);
        assertThat(evaluate(rule, appNamedDevtools, Map.of(BOOT_ERROR_HANDLING, true))
                        .status())
                .as("only DevTools' own map source is ignored")
                .isEqualTo("VIOLATION");
    }

    @Test
    void clientGroupsAreIndependentAndDoNotClaimArbitraryClientsHaveNoTimeouts() {
        var rule = new HttpClientTimeoutsUnsetRule();
        assertThat(rule.evaluate(SpringContext.builder(new MockEnvironment())
                                .restClientBeanPresent(true)
                                .webClientBeanPresent(true)
                                .build())
                        .status())
                .isEqualTo("SKIPPED");
        var groups = env("spring.http.serviceclient.[complete].connect-timeout", "1s")
                .withProperty("spring.http.serviceclient.[complete].read-timeout", "2s")
                .withProperty("spring.http.serviceclient.[incomplete].connect-timeout", "1s");
        assertThat(evaluate(rule, groups, Map.of(HTTP_SERVICE_GROUPS, true)).violationCount())
                .isEqualTo(1);
        groups.withProperty("spring.http.serviceclient.[incomplete].read-timeout", "2s");
        assertThat(evaluate(rule, groups, Map.of(HTTP_SERVICE_GROUPS, true)).status())
                .isEqualTo("PASS");
        assertThat(evaluate(rule, groups, Map.of(HTTP_SERVICE_GROUPS, true, HTTP_CLIENT_DEFAULTS, true))
                        .status())
                .isEqualTo("VIOLATION");
        groups.withProperty("spring.http.clients.connect-timeout", "1s")
                .withProperty("spring.http.clients.read-timeout", "2s");
        assertThat(evaluate(rule, groups, Map.of(HTTP_CLIENT_DEFAULTS, true, HTTP_SERVICE_GROUPS, true))
                        .status())
                .isEqualTo("PASS");
    }

    @Test
    void osivRequiresRegistrationNotEmfOrPropertyAndStaysMedium() {
        var rule = new OpenSessionInViewEnabledRule();
        var prod = env("spring.jpa.open-in-view", "true");
        prod.setDefaultProfiles("prod");
        assertThat(rule.evaluate(SpringContext.builder(prod)
                                .entityManagerFactoryPresent(true)
                                .dispatcherServletPresent(true)
                                .build())
                        .status())
                .isEqualTo("SKIPPED");
        assertThat(evaluate(
                                rule,
                                prod,
                                Map.of(
                                        OSIV,
                                        new SpringObservations.OsivObservation(
                                                "Boot servlet interceptor/configurer registration", true)))
                        .severity())
                .isEqualTo("MEDIUM");
        assertThat(rule.evaluate(context(
                                        prod,
                                        Map.of(OSIV, new SpringObservations.OsivObservation("registration", true)))
                                .reactive(true)
                                .build())
                        .status())
                .isEqualTo("SKIPPED");
        assertThat(rule.definition().description()).contains("does not prove a held JDBC connection");
    }

    @Test
    void absentJpaDoesNotCreateAnOsivEvidenceGapButUnreadableJpaStillDoes() {
        var rule = new OpenSessionInViewEnabledRule();
        var noJpa = context(new MockEnvironment(), Map.of()).build();
        assertThat(rule.evaluate(noJpa).status()).isEqualTo("SKIPPED");
        var noJpaEvidence = noJpa.observations().evaluation().evidence(List.of());
        assertThat(noJpaEvidence.usable()).isFalse();
        assertThat(noJpaEvidence.coverageComplete()).isTrue();

        var jpa = context(new MockEnvironment(), Map.of())
                .entityManagerFactoryPresent(true)
                .build();
        assertThat(rule.evaluate(jpa).status()).isEqualTo("SKIPPED");
        assertThat(jpa.observations().evaluation().evidence(List.of()).coverageComplete())
                .isFalse();

        var unknownInventory = SpringContext.builder(new MockEnvironment()).build();
        rule.evaluate(unknownInventory);
        assertThat(unknownInventory
                        .observations()
                        .evaluation()
                        .evidence(List.of())
                        .coverageComplete())
                .isFalse();
    }

    @Test
    void databaseRulesUseSanitizedSupportedProvenanceAndDefaultProfiles() {
        var prod = env("spring.datasource.url", "jdbc:h2:mem:secret;PASSWORD=do-not-display");
        prod.setDefaultProfiles("production");
        assertThat(evaluate(new InMemoryDatasourceInProductionRule(), prod, Map.of())
                        .status())
                .isEqualTo("SKIPPED");
        var result = evaluate(new InMemoryDatasourceInProductionRule(), prod, Map.of(JDBC_KIND, "H2 memory"));
        assertThat(result.status()).isEqualTo("VIOLATION");
        assertThat(result.toString()).doesNotContain("do-not-display", "jdbc:h2", "secret;");
        assertThat(evaluate(new InMemoryDatasourceInProductionRule(), prod, Map.of(JDBC_KIND, "other"))
                        .status())
                .isEqualTo("PASS");
        assertThat(SpringInventory.jdbcKind("jdbc:h2:mem")).isEqualTo("H2 memory");
        assertThat(SpringInventory.jdbcKind("jdbc:h2:mem;DB_CLOSE_DELAY=-1")).isEqualTo("H2 memory");
        assertThat(SpringInventory.jdbcKind("jdbc:postgresql://db/test?hint=jdbc:h2:mem:foo"))
                .isEqualTo("other");
        assertThat(SpringInventory.r2dbcKind("r2dbc:h2:mem:///orders?options=DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"))
                .isEqualTo("H2 memory");
        assertThat(SpringInventory.r2dbcKind("r2dbc:pool:h2:mem:///orders?options=DB_CLOSE_DELAY=-1"))
                .isEqualTo("H2 memory");
        assertThat(SpringInventory.r2dbcKind("r2dbc:postgresql://db/orders?sslMode=verify-full&hint=r2dbc:h2:mem:foo"))
                .isEqualTo("other");
        assertThat(evaluate(new InMemoryR2dbcInProductionRule(), prod, Map.of(R2DBC_KIND, "H2 memory"))
                        .status())
                .isEqualTo("VIOLATION");
        assertThat(evaluate(new InMemoryR2dbcInProductionRule(), prod, Map.of()).status())
                .isEqualTo("SKIPPED");
    }

    @Test
    void oversizedUnrelatedSystemEnvironmentVariableDoesNotFailPropertyRules() {
        MockEnvironment environment = new MockEnvironment().withProperty("spring.application.name", "orders");
        environment
                .getPropertySources()
                .addLast(new SystemEnvironmentPropertySource(
                        "systemEnvironment",
                        Map.of(
                                "UNRELATED_HUGE_VAR", "secret".repeat(1000),
                                "OVERSIZED_BOUND_NAME", "secret".repeat(1000))));
        ConfigurationPropertySources.attach(environment);
        assertThat(evaluate(new MissingApplicationNameRule(), environment, Map.of())
                        .status())
                .isEqualTo("PASS");
        assertThat(evaluate(new RemovedOrRenamedPropertyRule(), environment, Map.of())
                        .status())
                .isEqualTo("PASS");
        MockEnvironment oversizedAppName = new MockEnvironment();
        oversizedAppName
                .getPropertySources()
                .addFirst(new SystemEnvironmentPropertySource(
                        "systemEnvironment", Map.of("SPRING_APPLICATION_NAME", "secret".repeat(1000))));
        ConfigurationPropertySources.attach(oversizedAppName);
        var result = evaluate(new MissingApplicationNameRule(), oversizedAppName, Map.of());
        assertThat(result.status()).isEqualTo("ERROR");
        assertThat(result.toString()).doesNotContain("secret");
    }

    @Test
    void codecRuleOnlyFlagsObservedUnlimitedCurrentConfiguration() {
        var rule = new UnlimitedCodecAggregationRule();
        for (Map<Fact, Object> evidence : List.of(
                Map.<Fact, Object>of(BOOT_CODEC_CONFIGURATION, true),
                Map.<Fact, Object>of(BOOT_CODEC_CONFIGURATION, true, CODEC_LIMIT, 262144L)))
            assertThat(rule.evaluate(context(new MockEnvironment(), evidence)
                                    .reactive(true)
                                    .build())
                            .status())
                    .isEqualTo("PASS");
        assertThat(rule.evaluate(
                                context(new MockEnvironment(), Map.of(BOOT_CODEC_CONFIGURATION, true, CODEC_LIMIT, -1L))
                                        .reactive(true)
                                        .build())
                        .status())
                .isEqualTo("VIOLATION");
        assertThat(rule.evaluate(context(env("spring.http.codecs.max-in-memory-size", "-1"), Map.of())
                                .reactive(true)
                                .build())
                        .status())
                .isEqualTo("SKIPPED");
        assertThat(rule.evaluate(
                                context(new MockEnvironment(), Map.of(BOOT_CODEC_CONFIGURATION, true, CODEC_LIMIT, -1L))
                                        .build())
                        .status())
                .isEqualTo("SKIPPED");
    }

    @Test
    void actuatorBoot411DefaultsRequireExplicitAccessEvenWithInclude() {
        var rule = new DangerousActuatorEndpointsAccessibleRule();
        for (String include : List.of("*", "heapdump,shutdown")) {
            var config = env("management.endpoints.web.exposure.include", include);
            assertThat(evaluate(rule, config, ENDPOINT_EVIDENCE).status()).isEqualTo("PASS");
            config.withProperty("management.endpoint.heapdump.access", "read-only");
            assertThat(evaluate(rule, config, ENDPOINT_EVIDENCE).violationCount())
                    .isEqualTo(1);
            config.withProperty("management.endpoint.shutdown.access", "read-only");
            assertThat(evaluate(rule, config, ENDPOINT_EVIDENCE).violationCount())
                    .isEqualTo(1);
            config.withProperty("management.endpoint.shutdown.access", "unrestricted");
            assertThat(evaluate(rule, config, ENDPOINT_EVIDENCE).violationCount())
                    .isEqualTo(2);
            config.withProperty("management.endpoints.access.max-permitted", "read-only");
            assertThat(evaluate(rule, config, ENDPOINT_EVIDENCE).violationCount())
                    .isEqualTo(1);
            config.withProperty("management.endpoints.access.max-permitted", "none");
            assertThat(evaluate(rule, config, ENDPOINT_EVIDENCE).status()).isEqualTo("PASS");
        }
    }

    @Test
    void actuatorEndpointOverridesGlobalLegacyAndSameScopeConflictsAreErrors() {
        var rule = new DangerousActuatorEndpointsAccessibleRule();
        var config = env("management.endpoints.web.exposure.include", "shutdown")
                .withProperty("management.endpoints.access.default", "none")
                .withProperty("management.endpoint.shutdown.enabled", "true");
        config.setActiveProfiles("prod");
        assertThat(evaluate(rule, config, ENDPOINT_EVIDENCE).severity()).isEqualTo("HIGH");
        config.withProperty("management.endpoint.shutdown.access", "unrestricted");
        assertThat(evaluate(rule, config, ENDPOINT_EVIDENCE).status()).isEqualTo("ERROR");
        config.withProperty("management.endpoints.web.exposure.exclude", "*");
        assertThat(evaluate(rule, config, ENDPOINT_EVIDENCE).status()).isEqualTo("ERROR");
        var globalConflict = env("management.endpoints.web.exposure.include", "env")
                .withProperty("management.endpoints.access.default", "none")
                .withProperty("management.endpoints.enabled-by-default", "true");
        assertThat(evaluate(new SensitiveActuatorEndpointsExposedRule(), globalConflict, ENDPOINT_EVIDENCE)
                        .status())
                .isEqualTo("ERROR");
    }

    @Test
    void actuatorEmptyIncludesExcludesMissingEndpointsAndHostOnlyDefaults() {
        var show = new ActuatorShowValuesAlwaysRule();
        var config = env("management.endpoints.web.exposure.include", "")
                .withProperty("management.endpoint.health.show-details", "always");
        assertThat(evaluate(show, config, ENDPOINT_EVIDENCE).status()).isEqualTo("VIOLATION");
        config.withProperty("management.endpoints.web.exposure.exclude", "*");
        assertThat(evaluate(show, config, ENDPOINT_EVIDENCE).status()).isEqualTo("PASS");
        assertThat(evaluate(show, env("management.endpoint.health.show-details", "always"), Map.of(ENDPOINTS, Set.of()))
                        .status())
                .isEqualTo("PASS");
        MockEnvironment defaults = new MockEnvironment();
        defaults.getPropertySources()
                .addLast(new MapPropertySource(
                        "defaultProperties",
                        Map.of(
                                "management.endpoint.health.show-details",
                                "always",
                                "management.endpoints.web.exposure.include",
                                BootUiActuatorDefaultsEnvironmentPostProcessor.REQUIRED_ENDPOINTS)));
        ConfigurationPropertySources.attach(defaults);
        for (SpringRule rule : List.of(show, new ActuatorExposeAllRule(), new SensitiveActuatorEndpointsExposedRule()))
            assertThat(evaluate(rule, defaults, ENDPOINT_EVIDENCE).status()).isEqualTo("PASS");
        MockEnvironment host = new MockEnvironment();
        host.getPropertySources()
                .addFirst(new SystemEnvironmentPropertySource(
                        "systemEnvironment", Map.of("MANAGEMENT_ENDPOINT_HEALTH_SHOWDETAILS", "always")));
        ConfigurationPropertySources.attach(host);
        assertThat(evaluate(show, host, ENDPOINT_EVIDENCE).status()).isEqualTo("VIOLATION");
        for (String spelling : List.of("WHEN_AUTHORIZED", "when-authorized", "whenauthorized"))
            assertThat(evaluate(show, env("management.endpoint.health.show-details", spelling), ENDPOINT_EVIDENCE)
                            .status())
                    .as(spelling)
                    .isEqualTo("PASS");
        assertThat(evaluate(
                                new SensitiveActuatorEndpointsExposedRule(),
                                env("management.endpoints.web.exposure.include[0]", "env"),
                                ENDPOINT_EVIDENCE)
                        .status())
                .isEqualTo("VIOLATION");
    }

    @Test
    void actuatorWildcardIsFixedMediumAndRespectsAccessDisabledWebAndDeduplication() {
        var config = env("management.endpoints.web.exposure.include", "*");
        config.setActiveProfiles("prod");
        assertThat(evaluate(new ActuatorExposeAllRule(), config, ENDPOINT_EVIDENCE)
                        .severity())
                .isEqualTo("MEDIUM");
        assertThat(evaluate(new SensitiveActuatorEndpointsExposedRule(), config, ENDPOINT_EVIDENCE)
                        .status())
                .isEqualTo("PASS");
        config.withProperty("management.server.port", "-1");
        assertThat(evaluate(new ActuatorExposeAllRule(), config, ENDPOINT_EVIDENCE)
                        .status())
                .isEqualTo("PASS");
        config.withProperty("management.server.port", "0").withProperty("management.endpoints.access.default", "none");
        assertThat(evaluate(new ActuatorExposeAllRule(), config, ENDPOINT_EVIDENCE)
                        .status())
                .isEqualTo("PASS");
    }

    @Test
    void actuatorExposureRulesDeferWhenApplicationSecurityChainIsPresentWhileShowValuesAndErrorDetailsStayActive() {
        var wildcardEnv = env("management.endpoints.web.exposure.include", "*")
                .withProperty("management.endpoint.heapdump.access", "read-only")
                .withProperty("management.endpoint.shutdown.access", "unrestricted")
                .withProperty("management.endpoint.health.show-details", "always")
                .withProperty("spring.web.error.include-stacktrace", "always");
        Map<Fact, Object> evidence = new java.util.HashMap<>(ENDPOINT_EVIDENCE);
        evidence.put(BOOT_ERROR_HANDLING, true);

        var secured = context(wildcardEnv, evidence)
                .applicationSecurityChainPresent(true)
                .build();
        for (SpringRule deferred : List.of(
                new ActuatorExposeAllRule(),
                new SensitiveActuatorEndpointsExposedRule(),
                new DangerousActuatorEndpointsAccessibleRule())) {
            var result = deferred.evaluate(secured);
            assertThat(result.status()).as(result.id()).isEqualTo("SKIPPED");
            assertThat(result.sampleViolations()).containsExactly(ActuatorExposure.DEFERRED_TO_SECURITY_ADVISOR);
        }
        assertThat(new ActuatorShowValuesAlwaysRule().evaluate(secured).status())
                .isEqualTo("VIOLATION");
        assertThat(new ErrorDetailsExposedRule().evaluate(secured).status()).isEqualTo("VIOLATION");
        assertThat(secured.observations().evaluation().evidence(List.of()).coverageComplete())
                .isTrue();

        var explicitSensitiveEnv = env("management.endpoints.web.exposure.include", "env,configprops");
        Map<Fact, Object> factSecuredEvidence = new java.util.HashMap<>(ENDPOINT_EVIDENCE);
        factSecuredEvidence.put(APPLICATION_SECURITY_CHAIN, true);
        var factSecured = context(explicitSensitiveEnv, factSecuredEvidence).build();
        assertThat(new SensitiveActuatorEndpointsExposedRule()
                        .evaluate(factSecured)
                        .status())
                .isEqualTo("SKIPPED");
        assertThat(factSecured.observations().evaluation().evidence(List.of()).coverageComplete())
                .isTrue();
    }

    @Test
    void malformedAndUnresolvedPropertiesAreSanitizedErrorsNotDefaults() {
        Map<SpringRule, String> cases = Map.of(
                new VirtualThreadsAvailableRule(), "spring.threads.virtual.enabled",
                new DebugOrTraceLoggingRule(), "debug",
                new GracefulShutdownDisabledRule(), "spring.lifecycle.timeout-per-shutdown-phase",
                new ActuatorShowValuesAlwaysRule(), "management.endpoint.health.show-details",
                new DangerousActuatorEndpointsAccessibleRule(), "management.endpoint.heapdump.access");
        for (var test : cases.entrySet()) {
            var environment = env(test.getValue(), "${secret-missing}")
                    .withProperty("management.endpoints.web.exposure.include", "*");
            Map<Fact, Object> evidence = new java.util.HashMap<>(ENDPOINT_EVIDENCE);
            evidence.put(BOOT_WEB_SERVER, true);
            var result = test.getKey()
                    .evaluate(context(environment, evidence)
                            .virtualThreadsSupported(true)
                            .dispatcherServletPresent(true)
                            .build());
            assertThat(result.status()).as(test.getValue()).isEqualTo("ERROR");
            assertThat(result.toString()).doesNotContain("secret-missing", "${");
        }
        assertThat(evaluate(
                                new ActuatorExposeAllRule(),
                                env("management.endpoints.web.exposure.include", "${secret}"),
                                ENDPOINT_EVIDENCE)
                        .status())
                .isEqualTo("ERROR");
    }

    @Test
    void dormantResilienceAndInfrastructureRulesReportFindingsAndRespectIncompleteInventory() {
        var resilience = new DormantResilienceAnnotationsRule();
        var infra = new DormantInfrastructureAnnotationsRule();
        assertThat(resilience.definition().severity()).isEqualTo("HIGH");
        assertThat(infra.definition().severity()).isEqualTo("HIGH");

        var clean = SpringContext.builder(new MockEnvironment())
                .beanDefinitionCount(10)
                .observations(facts(Map.of()))
                .build();
        assertThat(resilience.evaluate(clean).status()).isEqualTo("PASS");
        assertThat(infra.evaluate(clean).status()).isEqualTo("PASS");

        var incomplete = SpringContext.builder(new MockEnvironment())
                .beanDefinitionCount(10)
                .observations(new SpringObservations(Map.of(), List.of("incomplete")))
                .build();
        assertThat(resilience.evaluate(incomplete).status()).isEqualTo("SKIPPED");
        assertThat(infra.evaluate(incomplete).status()).isEqualTo("SKIPPED");

        var violated = SpringContext.builder(new MockEnvironment())
                .dormantResilienceBeans(
                        List.of(
                                "orderService (app.OrderService#placeOrder): @Retryable (Spring Framework 7) is present without RetryAnnotationBeanPostProcessor; add @EnableResilientMethods."))
                .dormantInfrastructureBeans(
                        List.of(
                                "mailWorker (app.MailWorker#send): @Async is present without AsyncAnnotationBeanPostProcessor or AsyncExecutionAspectSupport; add @EnableAsync."))
                .observations(facts(Map.of()))
                .build();
        var resilienceResult = resilience.evaluate(violated);
        assertThat(resilienceResult.status()).isEqualTo("VIOLATION");
        assertThat(resilienceResult.violationCount()).isEqualTo(1);
        assertThat(resilienceResult.sampleViolations().get(0)).contains("@EnableResilientMethods");

        var infraResult = infra.evaluate(violated);
        assertThat(infraResult.status()).isEqualTo("VIOLATION");
        assertThat(infraResult.violationCount()).isEqualTo(1);
        assertThat(infraResult.sampleViolations().get(0)).contains("@EnableAsync");
    }

    @Test
    void virtualThreadsExecutorBackoffRuleDetectsBackedOffApplicationTaskExecutor() {
        var rule = new VirtualThreadsExecutorBackoffRule();
        assertThat(rule.definition().severity()).isEqualTo("MEDIUM");

        MockEnvironment vtEnabled = env("spring.threads.virtual.enabled", "true");
        assertThat(rule.evaluate(context(vtEnabled, Map.of())
                                .virtualThreadsSupported(false)
                                .taskExecutionPropertiesPresent(true)
                                .executors(List.of(new BeanRef("customExecutor", false)))
                                .build())
                        .status())
                .isEqualTo("SKIPPED");
        assertThat(rule.evaluate(context(vtEnabled, Map.of())
                                .virtualThreadsSupported(true)
                                .executors(List.of(new BeanRef("customExecutor", false)))
                                .build())
                        .status())
                .isEqualTo("SKIPPED");

        var backedOff = rule.evaluate(context(vtEnabled, Map.of(TASK_EXECUTION_PROPERTIES, true))
                .virtualThreadsSupported(true)
                .executors(List.of(new BeanRef("customExecutor", false)))
                .build());
        assertThat(backedOff.status()).isEqualTo("VIOLATION");
        assertThat(backedOff.sampleViolations().get(0)).contains("customExecutor", "spring.task.execution.mode=force");

        MockEnvironment forced =
                env("spring.threads.virtual.enabled", "true").withProperty("spring.task.execution.mode", "FORCE");
        assertThat(rule.evaluate(context(forced, Map.of(TASK_EXECUTION_PROPERTIES, true))
                                .virtualThreadsSupported(true)
                                .executors(List.of(new BeanRef("customExecutor", false)))
                                .build())
                        .status())
                .isEqualTo("PASS");

        assertThat(rule.evaluate(context(vtEnabled, Map.of(TASK_EXECUTION_PROPERTIES, true))
                                .virtualThreadsSupported(true)
                                .bootApplicationTaskExecutorPresent(true)
                                .executors(List.of(
                                        new BeanRef("applicationTaskExecutor", false),
                                        new BeanRef("customExecutor", false)))
                                .build())
                        .status())
                .isEqualTo("PASS");

        MockEnvironment invalidMode =
                env("spring.threads.virtual.enabled", "true").withProperty("spring.task.execution.mode", "invalid");
        assertThat(rule.evaluate(context(invalidMode, Map.of(TASK_EXECUTION_PROPERTIES, true))
                                .virtualThreadsSupported(true)
                                .build())
                        .status())
                .isEqualTo("ERROR");
    }

    @Test
    void ignoredVirtualThreadPoolPropertiesRuleFlagsPoolSettingsUnderSimpleAsyncExecutors() {
        var rule = new IgnoredVirtualThreadPoolPropertiesRule();
        assertThat(rule.definition().severity()).isEqualTo("LOW");

        MockEnvironment configured = env("spring.threads.virtual.enabled", "true")
                .withProperty("spring.task.execution.pool.max-size", "32")
                .withProperty("spring.task.execution.pool.queue-capacity", "100")
                .withProperty("spring.task.scheduling.pool.size", "4");

        assertThat(rule.evaluate(context(configured, Map.of())
                                .virtualThreadsSupported(true)
                                .build())
                        .status())
                .isEqualTo("SKIPPED");

        var result = rule.evaluate(
                context(configured, Map.of(BOOT_VIRTUAL_TASK_EXECUTOR, true, BOOT_VIRTUAL_TASK_SCHEDULER, true))
                        .virtualThreadsSupported(true)
                        .build());
        assertThat(result.status()).isEqualTo("VIOLATION");
        assertThat(result.violationCount()).isEqualTo(3);
        assertThat(result.sampleViolations())
                .anySatisfy(d -> assertThat(d).contains("spring.task.execution.pool.max-size"))
                .anySatisfy(d -> assertThat(d).contains("spring.task.scheduling.pool.size"));

        assertThat(rule.evaluate(context(
                                        env("spring.threads.virtual.enabled", "true"),
                                        Map.of(BOOT_VIRTUAL_TASK_EXECUTOR, true))
                                .virtualThreadsSupported(true)
                                .build())
                        .status())
                .isEqualTo("PASS");
    }

    @Test
    void incompleteTaskShutdownTerminationRuleDetectsMissingPeriodOrDisabledAwaitOnVirtualThreads() {
        var rule = new IncompleteTaskShutdownTerminationRule();
        assertThat(rule.definition().severity()).isEqualTo("MEDIUM");

        assertThat(evaluate(rule, new MockEnvironment(), Map.of()).status()).isEqualTo("PASS");

        for (String period : List.of("0", "0s", "PT0S", "-5s")) {
            MockEnvironment zeroPeriod = env("spring.task.execution.shutdown.await-termination", "true")
                    .withProperty("spring.task.execution.shutdown.await-termination-period", period);
            assertThat(evaluate(rule, zeroPeriod, Map.of()).status()).as(period).isEqualTo("VIOLATION");
        }

        MockEnvironment missingPeriod = env("spring.task.execution.shutdown.await-termination", "true")
                .withProperty("spring.task.scheduling.shutdown.await-termination", "true");
        var missingResult = evaluate(rule, missingPeriod, Map.of());
        assertThat(missingResult.status()).isEqualTo("VIOLATION");
        assertThat(missingResult.violationCount()).isEqualTo(2);

        MockEnvironment complete = env("spring.task.execution.shutdown.await-termination", "true")
                .withProperty("spring.task.execution.shutdown.await-termination-period", "30s")
                .withProperty("spring.task.scheduling.shutdown.await-termination", "true")
                .withProperty("spring.task.scheduling.shutdown.await-termination-period", "15s");
        assertThat(evaluate(rule, complete, Map.of()).status()).isEqualTo("PASS");

        MockEnvironment vtPeriodWithoutAwait = env("spring.threads.virtual.enabled", "true")
                .withProperty("spring.task.execution.shutdown.await-termination-period", "20s");
        assertThat(rule.evaluate(context(vtPeriodWithoutAwait, Map.of())
                                .virtualThreadsSupported(true)
                                .build())
                        .status())
                .isEqualTo("VIOLATION");

        MockEnvironment invalidDuration =
                env("spring.task.execution.shutdown.await-termination-period", "not-a-duration");
        assertThat(evaluate(rule, invalidDuration, Map.of()).status()).isEqualTo("ERROR");
    }
}
