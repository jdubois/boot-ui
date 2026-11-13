package io.github.jdubois.bootui.autoconfigure;

import io.github.jdubois.bootui.autoconfigure.activity.AuthenticationPhaseObservationHandler;
import io.github.jdubois.bootui.autoconfigure.activity.AuthorizationDecisionObservationHandler;
import io.github.jdubois.bootui.autoconfigure.architecture.SpringBasePackageProvider;
import io.github.jdubois.bootui.autoconfigure.beans.SpringBeanProvider;
import io.github.jdubois.bootui.autoconfigure.cache.CacheActivityCacheManagerBeanPostProcessor;
import io.github.jdubois.bootui.autoconfigure.cache.SpringCacheProvider;
import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.autoconfigure.config.SpringConfigProvider;
import io.github.jdubois.bootui.autoconfigure.config.SpringMemoryRuntimeConfig;
import io.github.jdubois.bootui.autoconfigure.crac.CracRuntimeInventoryCollector;
import io.github.jdubois.bootui.autoconfigure.databaseadvisor.SpringDatabaseAdvisorDataSourceProvider;
import io.github.jdubois.bootui.autoconfigure.databaseadvisor.SpringDatabaseAdvisorEvidenceProvider;
import io.github.jdubois.bootui.autoconfigure.datasource.SpringConnectionPoolProvider;
import io.github.jdubois.bootui.autoconfigure.errorcontract.SpringErrorContractProvider;
import io.github.jdubois.bootui.autoconfigure.faulttolerance.BootUiRetryListener;
import io.github.jdubois.bootui.autoconfigure.faulttolerance.Resilience4jPolicyProvider;
import io.github.jdubois.bootui.autoconfigure.faulttolerance.SpringRetryPolicyProvider;
import io.github.jdubois.bootui.autoconfigure.flyway.SpringFlywayProvider;
import io.github.jdubois.bootui.autoconfigure.graalvm.HttpReachabilityMetadataRepository;
import io.github.jdubois.bootui.autoconfigure.health.SpringHealthGuidance;
import io.github.jdubois.bootui.autoconfigure.health.SpringHealthProvider;
import io.github.jdubois.bootui.autoconfigure.hibernate.SpringHibernateDiscovery;
import io.github.jdubois.bootui.autoconfigure.hibernate.SpringHibernateStatisticsProvider;
import io.github.jdubois.bootui.autoconfigure.idle.IdleReclaimable;
import io.github.jdubois.bootui.autoconfigure.javaagent.AgentClaimOwner;
import io.github.jdubois.bootui.autoconfigure.javaagent.AgentProfileGuard;
import io.github.jdubois.bootui.autoconfigure.javaagent.BootUiAgentClaimEnvironmentPostProcessor;
import io.github.jdubois.bootui.autoconfigure.jms.JmsListenerCaptureBeanPostProcessor;
import io.github.jdubois.bootui.autoconfigure.jms.JmsProducerCaptureBeanPostProcessor;
import io.github.jdubois.bootui.autoconfigure.journal.ControlMarkerPublisher;
import io.github.jdubois.bootui.autoconfigure.journal.LogbackLogCoverage;
import io.github.jdubois.bootui.autoconfigure.journal.LogbackThreadLocalHolders;
import io.github.jdubois.bootui.autoconfigure.journal.RunApplicationKey;
import io.github.jdubois.bootui.autoconfigure.journal.RunStartPublisher;
import io.github.jdubois.bootui.autoconfigure.journal.RuntimeEventPublisherInstaller;
import io.github.jdubois.bootui.autoconfigure.journal.RuntimeJournalLogAppender;
import io.github.jdubois.bootui.autoconfigure.kafka.KafkaConsumerCaptureBeanPostProcessor;
import io.github.jdubois.bootui.autoconfigure.kafka.KafkaProducerCaptureBeanPostProcessor;
import io.github.jdubois.bootui.autoconfigure.liquibase.SpringLiquibaseProvider;
import io.github.jdubois.bootui.autoconfigure.logging.SpringLoggerProvider;
import io.github.jdubois.bootui.autoconfigure.mappings.SpringMappingProvider;
import io.github.jdubois.bootui.autoconfigure.mcp.SpringMcpPanelPolicy;
import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.autoconfigure.pentesting.SpringPentestingObservationCollector;
import io.github.jdubois.bootui.autoconfigure.rabbit.RabbitConsumerCaptureBeanPostProcessor;
import io.github.jdubois.bootui.autoconfigure.rabbit.RabbitProducerCaptureBeanPostProcessor;
import io.github.jdubois.bootui.autoconfigure.restclienttrace.RestClientTraceExchangeFilter;
import io.github.jdubois.bootui.autoconfigure.restclienttrace.RestClientTraceInterceptor;
import io.github.jdubois.bootui.autoconfigure.scheduled.BootUiSchedulingConfigurer;
import io.github.jdubois.bootui.autoconfigure.scheduled.ScheduledTaskRunObservationHandler;
import io.github.jdubois.bootui.autoconfigure.scheduled.SpringScheduledTaskProvider;
import io.github.jdubois.bootui.autoconfigure.web.ActuatorMappingsController;
import io.github.jdubois.bootui.autoconfigure.web.ConfigMetadataCatalog;
import io.github.jdubois.bootui.autoconfigure.web.DeclaredRouteTemplates;
import io.github.jdubois.bootui.autoconfigure.web.DependencyCatalog;
import io.github.jdubois.bootui.engine.activity.ActivityInstanceIds;
import io.github.jdubois.bootui.engine.activity.ActivityPersistenceSettings;
import io.github.jdubois.bootui.engine.activity.ActivityStoreFactory;
import io.github.jdubois.bootui.engine.activity.SwitchableActivityStore;
import io.github.jdubois.bootui.engine.architecture.ArchitecturePlatform;
import io.github.jdubois.bootui.engine.architecture.ArchitectureScanner;
import io.github.jdubois.bootui.engine.beans.BeansService;
import io.github.jdubois.bootui.engine.cache.CacheActivityRecorder;
import io.github.jdubois.bootui.engine.cache.CacheService;
import io.github.jdubois.bootui.engine.cli.CliService;
import io.github.jdubois.bootui.engine.codepaths.CodePathsService;
import io.github.jdubois.bootui.engine.codepaths.JournalRequestOutcomes;
import io.github.jdubois.bootui.engine.config.ConfigService;
import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.correlation.RequestPhases;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.crac.CracReadinessScanner;
import io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorScanner;
import io.github.jdubois.bootui.engine.datasource.ConnectionPoolService;
import io.github.jdubois.bootui.engine.email.EmailCaptureService;
import io.github.jdubois.bootui.engine.email.EmailStore;
import io.github.jdubois.bootui.engine.errorcontract.ErrorContractService;
import io.github.jdubois.bootui.engine.exceptions.CaughtExceptionsReader;
import io.github.jdubois.bootui.engine.exceptions.LogCoverage;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceEventRecorder;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceService;
import io.github.jdubois.bootui.engine.flyway.FlywayService;
import io.github.jdubois.bootui.engine.graalvm.GraalVmDependencySettings;
import io.github.jdubois.bootui.engine.graalvm.GraalVmReadinessScanner;
import io.github.jdubois.bootui.engine.health.HealthService;
import io.github.jdubois.bootui.engine.heapdump.HeapDumpService;
import io.github.jdubois.bootui.engine.heapdump.HeapDumpSettings;
import io.github.jdubois.bootui.engine.hibernate.EntityDiscovery;
import io.github.jdubois.bootui.engine.hibernate.EntityDiscoverySource;
import io.github.jdubois.bootui.engine.hibernate.HibernateScanner;
import io.github.jdubois.bootui.engine.hibernate.HibernateStatisticsService;
import io.github.jdubois.bootui.engine.inventory.CodeInventoryHistory;
import io.github.jdubois.bootui.engine.inventory.CodeInventoryService;
import io.github.jdubois.bootui.engine.inventory.JournalRequestRoutes;
import io.github.jdubois.bootui.engine.javaagent.AgentBridgeAccess;
import io.github.jdubois.bootui.engine.javaagent.AgentCaughtExceptions;
import io.github.jdubois.bootui.engine.javaagent.AgentHandoffs;
import io.github.jdubois.bootui.engine.javaagent.AgentSetupSnippets;
import io.github.jdubois.bootui.engine.javaagent.JavaAgentService;
import io.github.jdubois.bootui.engine.javaagent.JavaAgentSettings;
import io.github.jdubois.bootui.engine.jms.JmsActivityRecorder;
import io.github.jdubois.bootui.engine.journal.AgentEvidence;
import io.github.jdubois.bootui.engine.journal.JournalAggregates;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RunBaselineFile;
import io.github.jdubois.bootui.engine.journal.RunHistory;
import io.github.jdubois.bootui.engine.journal.RunningHandoffs;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder;
import io.github.jdubois.bootui.engine.liquibase.LiquibaseService;
import io.github.jdubois.bootui.engine.loggers.LoggersService;
import io.github.jdubois.bootui.engine.mappings.MappingsService;
import io.github.jdubois.bootui.engine.memory.LowFootprintService;
import io.github.jdubois.bootui.engine.memory.MemoryOffloadService;
import io.github.jdubois.bootui.engine.memory.MemoryReportProvider;
import io.github.jdubois.bootui.engine.memory.MemoryScanner;
import io.github.jdubois.bootui.engine.metrics.MetricsReportProvider;
import io.github.jdubois.bootui.engine.model.StructureSnapshots;
import io.github.jdubois.bootui.engine.mysql.MySqlInsightService;
import io.github.jdubois.bootui.engine.mysql.MySqlRowLimits;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.pentesting.PentestingScanner;
import io.github.jdubois.bootui.engine.postgres.PostgresInsightService;
import io.github.jdubois.bootui.engine.postgres.PostgresRowLimits;
import io.github.jdubois.bootui.engine.rabbit.RabbitActivityRecorder;
import io.github.jdubois.bootui.engine.restapi.RestApiScanner;
import io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder;
import io.github.jdubois.bootui.engine.scheduled.ScheduledTaskRunStore;
import io.github.jdubois.bootui.engine.scheduled.ScheduledTasksService;
import io.github.jdubois.bootui.engine.sideeffects.JournalExecutions;
import io.github.jdubois.bootui.engine.sideeffects.JournalNetworkCapture;
import io.github.jdubois.bootui.engine.sideeffects.SideEffectsService;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.threads.ThreadAnalysisService;
import io.github.jdubois.bootui.engine.threads.ThreadCaptureSettings;
import io.github.jdubois.bootui.engine.threads.ThreadDumpService;
import io.github.jdubois.bootui.engine.vulnerabilities.DependencyProvider;
import io.github.jdubois.bootui.engine.web.HttpProbeService;
import io.github.jdubois.bootui.engine.websocket.WebSocketActivityRecorder;
import io.github.jdubois.bootui.engine.websocket.WebSocketSettings;
import io.github.jdubois.bootui.spi.BasePackageProvider;
import io.github.jdubois.bootui.spi.BeanProvider;
import io.github.jdubois.bootui.spi.ConnectionPoolProvider;
import io.github.jdubois.bootui.spi.DatabaseAdvisorEvidenceProvider;
import io.github.jdubois.bootui.spi.ErrorContractProvider;
import io.github.jdubois.bootui.spi.FaultTolerancePolicyProvider;
import io.github.jdubois.bootui.spi.HealthProvider;
import io.github.jdubois.bootui.spi.LoggerProvider;
import io.github.jdubois.bootui.spi.LowFootprintSuspendable;
import io.github.jdubois.bootui.spi.MappingProvider;
import io.github.jdubois.bootui.spi.MemoryOffloadable;
import io.github.jdubois.bootui.spi.ScheduledTaskProvider;
import io.github.jdubois.bootui.spi.ThreadKindClassifier;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManagerFactory;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.aop.scope.ScopedObject;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.actuate.beans.BeansEndpoint;
import org.springframework.boot.actuate.logging.LoggersEndpoint;
import org.springframework.boot.actuate.web.mappings.MappingsEndpoint;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.restclient.RestTemplateCustomizer;
import org.springframework.boot.web.context.reactive.ReactiveWebApplicationContext;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.boot.webclient.WebClientCustomizer;
import org.springframework.cache.interceptor.CacheOperationSource;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.util.ClassUtils;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

@Configuration(proxyBeanMethods = false)
public class BootUiEngineConfiguration {

    @Bean
    @ConditionalOnMissingBean
    RunIdentity bootUiRunIdentity() {
        return RunIdentity.start();
    }

    @Bean
    @ConditionalOnMissingBean
    RuntimeJournal bootUiRuntimeJournal(
            BootUiProperties properties, RunIdentity run, ObjectProvider<ThreadKindClassifier> threadKinds) {
        RuntimeJournal journal =
                new RuntimeJournal(properties.getRuntimeJournal().toSettings(), run);
        journal.setThreadKindClassifier(threadKinds.getIfUnique());
        return journal;
    }

    @Bean
    static RuntimeEventPublisherInstaller bootUiRuntimeEventPublisherInstaller(
            ObjectProvider<RuntimeJournal> journal,
            ObjectProvider<JournalAggregates> aggregates,
            ObjectProvider<BootUiProperties> properties) {
        return new RuntimeEventPublisherInstaller(journal, aggregates, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    JournalAggregates bootUiJournalAggregates(
            RunIdentity run,
            ObjectProvider<MappingProvider> mappingProvider,
            BootUiProperties properties,
            Environment environment,
            ApplicationContext context) {
        JournalAggregates aggregates = new JournalAggregates();
        aggregates.setDeclaredRoutes(DeclaredRouteTemplates.caching(mappingProvider));
        aggregates.recordRunIn(
                RunHistory.shared(),
                run,
                RunBaselineFile.of(
                        properties.getRuntimeJournal().getBaselineFile(),
                        environment.getProperty("spring.application.name", "application")),
                RunApplicationKey.of(environment, context));
        return aggregates;
    }

    @Bean
    @ConditionalOnMissingBean
    AgentEvidence bootUiAgentEvidence(BootUiProperties properties) {
        DataSize max = properties.getRuntimeJournal().getAgentEvidenceMaxBytes();
        return new AgentEvidence(properties::isPanelEnabled, max == null ? null : max.toBytes());
    }

    @Bean
    SmartInitializingSingleton bootUiAgentEvidenceJournalListener(
            ObjectProvider<AgentEvidence> evidence, ObjectProvider<RuntimeJournal> journal) {
        return () -> {
            AgentEvidence current = evidence.getIfUnique();
            if (current != null) {
                current.listenTo(journal.getIfUnique());
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean(org.springframework.core.task.TaskDecorator.class)
    org.springframework.core.task.TaskDecorator bootUiTaskDecorator() {
        return new io.github.jdubois.bootui.autoconfigure.activity.BootUiTaskDecorator();
    }

    @Bean
    static io.github.jdubois.bootui.autoconfigure.activity.BootUiExecutorDecoration bootUiExecutorDecoration() {
        return new io.github.jdubois.bootui.autoconfigure.activity.BootUiExecutorDecoration();
    }

    @Bean
    RunStartPublisher bootUiRunStartPublisher(
            RuntimeJournal journal, ApplicationContext context, ObjectProvider<BootUiSelfDataFilter> selfData) {
        return new RunStartPublisher(journal, context, selfData.getIfAvailable());
    }

    @Bean
    ControlMarkerPublisher bootUiControlMarkerPublisher(RuntimeJournal journal, ApplicationContext context) {
        return new ControlMarkerPublisher(journal, context);
    }

    @Bean
    @ConditionalOnMissingBean
    BootUiInstanceLeaseLifecycle bootUiInstanceLeaseLifecycle(
            RunIdentity run, BootUiProperties properties, ApplicationContext context) {
        return new BootUiInstanceLeaseLifecycle(run, properties, context);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "ch.qos.logback.classic.LoggerContext")
    static class RuntimeJournalLogConfiguration {

        @Bean
        LogbackThreadLocalHolders bootUiLogbackThreadLocalHolders() {
            return LogbackThreadLocalHolders.install();
        }

        @Bean
        RuntimeJournalLogAppender bootUiRuntimeJournalLogAppender(RuntimeJournal journal) {
            return journal.settings().records(JournalSource.LOG) ? RuntimeJournalLogAppender.install(journal) : null;
        }

        @Bean
        LogCoverage bootUiLogCoverage() {
            return LogbackLogCoverage.install();
        }
    }

    @Bean
    RequestPhases bootUiRequestPhases() {
        return new RequestPhases();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(
            name = {
                "org.springframework.graphql.observation.ExecutionRequestObservationContext",
                "org.springframework.boot.micrometer.observation.autoconfigure.ObservationRegistryCustomizer"
            })
    static class GraphQlOperationConfiguration {

        @Bean
        org.springframework.boot.micrometer.observation.autoconfigure.ObservationRegistryCustomizer<
                        io.micrometer.observation.ObservationRegistry>
                bootUiGraphQlOperationCustomizer(RequestPhases phases) {
            return registry -> registry.observationConfig()
                    .observationHandler(
                            new io.github.jdubois.bootui.autoconfigure.graphql.GraphQlOperationObservationHandler(
                                    phases));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(
            name = {
                "org.springframework.security.authentication.AuthenticationObservationContext",
                "org.springframework.boot.micrometer.observation.autoconfigure.ObservationRegistryCustomizer"
            })
    static class AuthenticationPhaseConfiguration {

        @Bean
        org.springframework.boot.micrometer.observation.autoconfigure.ObservationRegistryCustomizer<
                        io.micrometer.observation.ObservationRegistry>
                bootUiAuthenticationPhaseCustomizer(RequestPhases phases) {
            return registry ->
                    registry.observationConfig().observationHandler(new AuthenticationPhaseObservationHandler(phases));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(
            name = {
                "org.springframework.security.authorization.AuthorizationObservationContext",
                "org.springframework.boot.micrometer.observation.autoconfigure.ObservationRegistryCustomizer"
            })
    static class AuthorizationDecisionConfiguration {

        @Bean
        org.springframework.boot.micrometer.observation.autoconfigure.ObservationRegistryCustomizer<
                        io.micrometer.observation.ObservationRegistry>
                bootUiAuthorizationDecisionCustomizer(ObjectProvider<RuntimeJournal> journal) {
            return registry -> registry.observationConfig()
                    .observationHandler(new AuthorizationDecisionObservationHandler(journal::getIfAvailable));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(
            name = {
                "io.micrometer.observation.ObservationHandler",
                "org.springframework.boot.micrometer.observation.autoconfigure.ObservationRegistryCustomizer"
            })
    static class AiObservationConfiguration {

        @Bean
        org.springframework.boot.micrometer.observation.autoconfigure.ObservationRegistryCustomizer<
                        io.micrometer.observation.ObservationRegistry>
                bootUiAiObservationCustomizer(ObjectProvider<RuntimeJournal> journal) {
            return registry -> registry.observationConfig()
                    .observationHandler(new io.github.jdubois.bootui.autoconfigure.activity.AiObservationJournalHandler(
                            journal::getIfAvailable));
        }
    }

    @Bean
    @ConditionalOnMissingBean
    AgentHandoffs bootUiAgentHandoffs(BootUiProperties properties, RequestPhases phases) {
        return new AgentHandoffs(
                BootUiCorrelation::current,
                phases,
                properties.getAgent().getExecutors().getMaxHandoff());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    AgentCaughtExceptions bootUiAgentCaughtExceptions(ObjectProvider<AgentClaimOwner> owner) {
        return new AgentCaughtExceptions(AgentBridgeAccess.locate(), () -> {
            AgentClaimOwner current = owner.getIfUnique();
            return current == null ? null : current.claim();
        });
    }

    @Bean
    @ConditionalOnMissingBean
    CaughtExceptionsReader bootUiCaughtExceptionsReader(
            RuntimeJournal journal, ObjectProvider<AgentCaughtExceptions> sensor, ObjectProvider<LogCoverage> logs) {
        return new CaughtExceptionsReader(
                journal, sensor::getIfUnique, logs.getIfUnique(() -> LogCoverage.UNREADABLE), RunningHandoffs.shared());
    }

    @Bean(destroyMethod = "close")
    @Lazy
    @ConditionalOnMissingBean
    CodeInventoryService bootUiCodeInventoryService(
            BootUiProperties properties,
            ApplicationContext context,
            ObjectProvider<AgentClaimOwner> owner,
            ObjectProvider<JavaAgentService> javaAgent,
            ObjectProvider<BasePackageProvider> basePackages,
            ObjectProvider<JournalAggregates> aggregates,
            ObjectProvider<RuntimeJournal> journal,
            AgentEvidence evidence) {
        DependencyProvider declared = DependencyCatalog.forApplication(
                () -> {
                    BasePackageProvider provider = basePackages.getIfAvailable();
                    return provider == null ? List.of() : provider.basePackages();
                },
                context.getClassLoader());
        CodeInventoryService service = new CodeInventoryService(
                AgentBridgeAccess.locate(),
                () -> {
                    AgentClaimOwner current = owner.getIfUnique();
                    return current == null ? null : current.claim();
                },
                () -> {
                    JavaAgentService agent = javaAgent.getIfUnique();
                    return agent == null
                            ? JavaAgentService.INVENTORY_REQUIREMENT + "."
                            : agent.inventoryUnavailableReason();
                },
                context::getClassLoader,
                declared::inventory,
                () -> {
                    JournalAggregates current = aggregates.getIfAvailable();
                    return current == null ? null : current.runReadyAtEpochMillis();
                },
                properties.getCodeInventory().toSettings(),
                CodeInventoryHistory.shared(),
                evidence);
        JournalAggregates journalAggregates = aggregates.getIfAvailable();
        service.setRequestRoutes(JournalRequestRoutes.of(
                journal.getIfAvailable(), journalAggregates == null ? null : journalAggregates.declaredRoutes()));
        return service;
    }

    @Bean(destroyMethod = "close")
    @Lazy
    @ConditionalOnMissingBean
    CodePathsService bootUiCodePathsService(
            BootUiProperties properties,
            ObjectProvider<AgentClaimOwner> owner,
            ObjectProvider<JavaAgentService> javaAgent,
            ObjectProvider<JournalAggregates> aggregates,
            ObjectProvider<RuntimeJournal> journal,
            ObjectProvider<BeanProvider> beans,
            AgentEvidence evidence,
            BootUiExposure exposure) {
        CodePathsService service = new CodePathsService(
                AgentBridgeAccess.locate(),
                () -> {
                    AgentClaimOwner current = owner.getIfUnique();
                    return current == null ? null : current.claim();
                },
                () -> {
                    JavaAgentService agent = javaAgent.getIfUnique();
                    return agent == null
                            ? JavaAgentService.CODE_PATHS_REQUIREMENT + "."
                            : agent.codePathsUnavailableReason();
                },
                evidence);
        JournalAggregates journalAggregates = aggregates.getIfAvailable();
        service.setRequestOutcomes(JournalRequestOutcomes.of(
                journal.getIfAvailable(), journalAggregates == null ? null : journalAggregates.declaredRoutes()));
        service.setStructure(() -> StructureSnapshots.read(null, beans.getIfUnique(), null));
        service.setExposure(exposure);
        return service;
    }

    @Bean(destroyMethod = "close")
    @Lazy
    @ConditionalOnMissingBean
    SideEffectsService bootUiSideEffectsService(
            ObjectProvider<AgentClaimOwner> owner,
            ObjectProvider<JavaAgentService> javaAgent,
            ObjectProvider<JournalAggregates> aggregates,
            ObjectProvider<RuntimeJournal> journal,
            AgentEvidence evidence,
            org.springframework.core.env.Environment environment,
            org.springframework.context.ApplicationContext applicationContext,
            ObjectProvider<RequestPhases> requestPhases) {
        SideEffectsService service = new SideEffectsService(
                AgentBridgeAccess.locate(),
                () -> {
                    AgentClaimOwner current = owner.getIfUnique();
                    return current == null ? null : current.claim();
                },
                () -> {
                    JavaAgentService agent = javaAgent.getIfUnique();
                    return agent == null
                            ? JavaAgentService.SIDE_EFFECTS_REQUIREMENT + "."
                            : agent.sideEffectsUnavailableReason();
                },
                id -> {
                    JavaAgentService agent = javaAgent.getIfUnique();
                    return agent == null ? null : agent.sideEffectsCoverage(id);
                },
                evidence);
        JournalAggregates journalAggregates = aggregates.getIfAvailable();
        service.setRequestRoutes(JournalRequestRoutes.of(
                journal.getIfAvailable(), journalAggregates == null ? null : journalAggregates.declaredRoutes()));
        service.setExecutionLabels(JournalExecutions.of(journal.getIfAvailable()));
        service.setNetworkCapture(JournalNetworkCapture.of(journal.getIfAvailable()));
        service.setExporterEndpoints(environment::getProperty);
        service.setServerEventLoops(() -> nettyServer(applicationContext));
        service.listenToRequestEnds(requestPhases.getIfAvailable());
        if (journalAggregates != null) {
            journalAggregates.setRunSideEffects(service::runSideEffects);
        }
        JavaAgentService agent = javaAgent.getIfUnique();
        if (agent != null) {
            agent.onSensorSwitched(id -> {
                service.sensorSwitched(id);
                service.start();
            });
        }
        return service;
    }

    static boolean nettyServer(org.springframework.context.ApplicationContext applicationContext) {
        if (!(applicationContext instanceof ReactiveWebApplicationContext)) {
            return false;
        }
        if (applicationContext instanceof org.springframework.boot.web.server.context.WebServerApplicationContext web) {
            org.springframework.boot.web.server.WebServer server = web.getWebServer();
            return server == null || server.getClass().getName().contains("Netty");
        }
        return true;
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    JavaAgentService bootUiJavaAgentService(
            BootUiProperties properties,
            ObjectProvider<AgentClaimOwner> owner,
            ObjectProvider<BootUiActivation> activation,
            Environment environment) {
        return new JavaAgentService(
                AgentBridgeAccess.locate(),
                () -> {
                    AgentClaimOwner current = owner.getIfUnique();
                    return current == null ? null : current.claim();
                },
                JavaAgentSettings.of(
                        AgentSetupSnippets.SPRING,
                        properties.getAgent().isEnabled(),
                        AgentProfileGuard.refusal(activation.getIfAvailable(), environment)));
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    ThreadDumpService bootUiThreadDumpService(BootUiExposure exposure, ThreadCaptureSettings settings) {
        return new ThreadDumpService(exposure, settings);
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    ThreadCaptureSettings bootUiThreadCaptureSettings(BootUiProperties properties) {
        var limits = properties.getThreads();
        return new ThreadCaptureSettings(
                limits.getMaxThreads(),
                limits.getMaxStackDepth(),
                limits.getMaxOwnedLocks(),
                limits.getCaptureDeadlineMillis(),
                limits.isIncludeSynchronizers(),
                limits.getMaxCaptures(),
                limits.getMaxRetainedBytes(),
                limits.getMaxCaptureBytes(),
                limits.getTtlMillis(),
                limits.getObservationSamples(),
                limits.getObservationIntervalMillis());
    }

    @Bean(destroyMethod = "close")
    @Lazy
    @ConditionalOnMissingBean
    ThreadAnalysisService bootUiThreadAnalysisService(
            ThreadDumpService reader,
            ThreadCaptureSettings settings,
            RunIdentity run,
            BootUiExposure exposure,
            BootUiProperties properties,
            LowFootprintService lowFootprint) {
        return new ThreadAnalysisService(
                reader, settings, run, exposure, new SpringMcpPanelPolicy(properties), lowFootprint::active);
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    MemoryScanner bootUiMemoryScanner(ThreadDumpService threadDumpService, BootUiProperties properties) {
        MemoryScanner scanner = MemoryScanner.create(threadDumpService, Clock.systemUTC());
        scanner.setViolationRetentionLimit(() -> properties.getAdvisors().getMaxRetainedViolations());
        return scanner;
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    BasePackageProvider bootUiBasePackageProvider(ApplicationContext applicationContext) {
        return new SpringBasePackageProvider(applicationContext);
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    ArchitectureScanner bootUiArchitectureScanner(
            BasePackageProvider basePackageProvider, BootUiProperties properties) {
        ArchitectureScanner scanner = ArchitectureScanner.usingClasspath(
                basePackageProvider::basePackages, ArchitecturePlatform.SPRING, Clock.systemUTC());
        scanner.setViolationRetentionLimit(() -> properties.getAdvisors().getMaxRetainedViolations());
        return scanner;
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    DatabaseAdvisorEvidenceProvider bootUiDatabaseAdvisorEvidenceProvider(
            ObjectProvider<ListableBeanFactory> beanFactoryProvider,
            ObjectProvider<ConnectionPoolProvider> connectionPools,
            ObjectProvider<io.github.jdubois.bootui.engine.hibernate.HibernateAdvisorObservationSource>
                    hibernateObservations) {
        return new SpringDatabaseAdvisorEvidenceProvider(beanFactoryProvider, connectionPools, hibernateObservations);
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    DatabaseAdvisorScanner bootUiDatabaseAdvisorScanner(
            ObjectProvider<ListableBeanFactory> beanFactoryProvider,
            ObjectProvider<EntityDiscoverySource> entityDiscoverySource,
            ObjectProvider<SqlTraceRecorder> sqlTraceRecorder,
            ObjectProvider<DatabaseAdvisorEvidenceProvider> evidenceProvider,
            BootUiProperties properties) {
        SpringDatabaseAdvisorDataSourceProvider dataSourceProvider =
                new SpringDatabaseAdvisorDataSourceProvider(beanFactoryProvider);
        DatabaseAdvisorScanner scanner = DatabaseAdvisorScanner.usingDiscovery(
                dataSourceProvider::discover,
                () -> {
                    EntityDiscoverySource source = entityDiscoverySource.getIfAvailable();
                    return source == null
                            ? EntityDiscovery.absent("Hibernate/JPA metamodel not detected on the classpath.")
                            : source.discover();
                },
                () -> {
                    SqlTraceRecorder recorder = sqlTraceRecorder.getIfAvailable();
                    return recorder == null ? List.of() : recorder.entries(false);
                },
                evidenceProvider.getIfAvailable(),
                Clock.systemUTC());
        scanner.setViolationRetentionLimit(() -> properties.getAdvisors().getMaxRetainedViolations());
        return scanner;
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    PostgresInsightService bootUiPostgresInsightService(
            ObjectProvider<ListableBeanFactory> beanFactoryProvider,
            BootUiExposure exposure,
            BootUiProperties properties) {
        SpringDatabaseAdvisorDataSourceProvider dataSourceProvider =
                new SpringDatabaseAdvisorDataSourceProvider(beanFactoryProvider);
        BootUiProperties.Postgresql postgresql = properties.getPostgresql();
        return PostgresInsightService.using(
                dataSourceProvider::discover,
                exposure,
                Clock.systemUTC(),
                new PostgresRowLimits(
                        postgresql.getMaxSessions(),
                        postgresql.getMaxStatements(),
                        postgresql.getMaxIndexes(),
                        postgresql.getMaxTables(),
                        postgresql.getMaxVacuumTables(),
                        postgresql.getMaxReplicas(),
                        postgresql.getMaxSettings()));
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    MySqlInsightService bootUiMySqlInsightService(
            ObjectProvider<ListableBeanFactory> beanFactoryProvider,
            BootUiExposure exposure,
            BootUiProperties properties) {
        SpringDatabaseAdvisorDataSourceProvider provider =
                new SpringDatabaseAdvisorDataSourceProvider(beanFactoryProvider);
        BootUiProperties.Mysql mysql = properties.getMysql();
        return MySqlInsightService.using(
                provider::discover,
                exposure,
                Clock.systemUTC(),
                new MySqlRowLimits(
                        mysql.getMaxSessions(),
                        mysql.getMaxStatements(),
                        mysql.getMaxIndexes(),
                        mysql.getMaxTables(),
                        mysql.getMaxLockWaits(),
                        mysql.getMaxReplicationChannels(),
                        mysql.getMaxSettings()));
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    RestApiScanner bootUiRestApiScanner(
            BasePackageProvider basePackageProvider,
            Environment environment,
            ApplicationContext applicationContext,
            BootUiProperties properties) {
        RestApiScanner scanner = RestApiScanner.usingClasspath(
                basePackageProvider::basePackages,
                () -> ClassUtils.isPresent(
                        "io.swagger.v3.oas.annotations.Operation", BootUiEngineConfiguration.class.getClassLoader()),
                () -> isSpringApiVersioningConfigured(environment, applicationContext),
                Clock.systemUTC());
        scanner.setViolationRetentionLimit(() -> properties.getAdvisors().getMaxRetainedViolations());
        scanner.setSpringWebStack(() -> springWebStack(applicationContext));
        return scanner;
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    ErrorContractProvider bootUiErrorContractProvider(ListableBeanFactory beanFactory) {
        return new SpringErrorContractProvider(beanFactory);
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    ErrorContractService bootUiErrorContractService(ObjectProvider<ErrorContractProvider> errorContractProviders) {
        return new ErrorContractService(errorContractProviders.getIfAvailable());
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    GraalVmReadinessScanner bootUiGraalVmReadinessScanner(
            BasePackageProvider basePackageProvider, BootUiProperties properties) {
        BootUiProperties.Graalvm graalvm = properties.getGraalvm();
        return GraalVmReadinessScanner.usingClasspath(
                basePackageProvider::basePackages,
                new GraalVmDependencySettings(graalvm.isRepositoryLookupEnabled(), graalvm.getMaxRepositoryLookups()),
                new HttpReachabilityMetadataRepository(graalvm.getRepositoryLookupTimeout()),
                Clock.systemUTC());
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    CracReadinessScanner bootUiCracReadinessScanner(
            BasePackageProvider basePackageProvider, ApplicationContext applicationContext) {
        return CracReadinessScanner.usingClasspath(
                basePackageProvider::basePackages,
                () -> CracRuntimeInventoryCollector.collect(applicationContext),
                Clock.systemUTC());
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    PentestingScanner bootUiPentestingScanner(
            ApplicationContext applicationContext, Environment environment, BootUiProperties properties) {
        ObjectProvider<RequestMappingInfoHandlerMapping> handlerMappingProvider =
                SpringPentestingObservationCollector.isServletContext(applicationContext)
                                && ClassUtils.isPresent(
                                        "org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping",
                                        applicationContext.getClassLoader())
                        ? applicationContext.getBeanProvider(RequestMappingInfoHandlerMapping.class)
                        : null;
        SpringPentestingObservationCollector collector = new SpringPentestingObservationCollector(
                applicationContext, handlerMappingProvider, environment, properties);
        return PentestingScanner.usingObservation(collector::collect, Clock.systemUTC());
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    HeapDumpService bootUiHeapDumpService(BootUiProperties properties) {
        BootUiProperties.HeapDump heapDump = properties.getHeapDump();
        HeapDumpSettings settings = new HeapDumpSettings(
                heapDump.getOutputDir(),
                heapDump.isCaptureEnabled(),
                heapDump.isAllowRawDownload(),
                heapDump.getMaxDumps(),
                heapDump.getMaxClasses(),
                heapDump.getTopClasses());
        return new HeapDumpService(settings);
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    HttpProbeService bootUiHttpProbeService(Environment environment) {
        return new HttpProbeService(() -> environment.getProperty(
                "local.server.port", Integer.class, environment.getProperty("server.port", Integer.class, 8080)));
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    MemoryReportProvider bootUiMemoryReportProvider(Environment environment, BootUiExposure exposure) {
        return new MemoryReportProvider(new SpringMemoryRuntimeConfig(environment), exposure);
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    MemoryOffloadService bootUiMemoryOffloadService(ConfigurableListableBeanFactory beanFactory) {
        return new MemoryOffloadService(() -> createdOffloadables(beanFactory));
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    LowFootprintService bootUiLowFootprintService(
            ConfigurableListableBeanFactory beanFactory, MemoryOffloadService offloadService) {
        return new LowFootprintService(() -> createdLowFootprintCandidates(beanFactory), offloadService);
    }

    static List<Object> createdLowFootprintCandidates(ConfigurableListableBeanFactory beanFactory) {
        List<Object> candidates = new ArrayList<>();
        for (String name : beanFactory.getSingletonNames()) {
            Object singleton = beanFactory.getSingleton(name);
            if ((singleton instanceof LowFootprintSuspendable
                            || singleton instanceof io.github.jdubois.bootui.spi.IdleReclaimable)
                    && !(singleton instanceof ScopedObject)) {
                candidates.add(singleton);
            }
        }
        addCreatedLogTailBuffer(beanFactory, candidates);
        return candidates;
    }

    static List<Object> createdOffloadables(ConfigurableListableBeanFactory beanFactory) {
        List<Object> offloadables = new ArrayList<>();
        for (String name : beanFactory.getSingletonNames()) {
            Object singleton = beanFactory.getSingleton(name);
            if (singleton instanceof MemoryOffloadable && !(singleton instanceof ScopedObject)) {
                offloadables.add(singleton);
            }
        }
        addCreatedLogTailBuffer(beanFactory, offloadables);
        return offloadables;
    }

    private static void addCreatedLogTailBuffer(ConfigurableListableBeanFactory beanFactory, List<Object> candidates) {
        if (ClassUtils.isPresent("ch.qos.logback.classic.LoggerContext", beanFactory.getBeanClassLoader())) {
            var appender = io.github.jdubois.bootui.autoconfigure.web.BootUiLogAppender.find();
            if (appender != null) {
                candidates.add(appender.buffer());
            }
        }
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    MetricsReportProvider bootUiMetricsReportProvider(
            ObjectProvider<MeterRegistry> registries, BootUiSelfDataFilter selfDataFilter) {
        return new MetricsReportProvider(() -> resolveRegistry(registries), selfDataFilter::shouldIncludeMeter);
    }

    static MeterRegistry resolveRegistry(ObjectProvider<MeterRegistry> registries) {
        try {
            return registries.getIfAvailable();
        } catch (NoUniqueBeanDefinitionException ex) {
            return registries.orderedStream().findFirst().orElse(null);
        }
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    LoggersService bootUiLoggersService(
            ObjectProvider<LoggerProvider> loggerProviders, BootUiSelfDataFilter selfDataFilter) {
        return new LoggersService(
                loggerProviders.getIfAvailable(),
                selfDataFilter::shouldIncludeLogger,
                selfDataFilter::isBootUiLoggerName);
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    HealthService bootUiHealthService(ObjectProvider<HealthProvider> healthProviders) {
        return new HealthService(healthProviders.getIfAvailable(), SpringHealthGuidance.INSTANCE);
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    MappingsService bootUiMappingsService(ObjectProvider<MappingProvider> mappingProviders) {
        return new MappingsService(mappingProviders.getIfAvailable());
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    BeansService bootUiBeansService(ObjectProvider<BeanProvider> beanProviders) {
        return new BeansService(beanProviders.getIfAvailable());
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    ConfigService bootUiConfigService(ConfigurableEnvironment environment, BootUiExposure exposure) {
        SpringConfigProvider provider = new SpringConfigProvider(
                environment, new ConfigMetadataCatalog(BootUiEngineConfiguration.class.getClassLoader()));
        return new ConfigService(provider, exposure);
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    EmailCaptureService bootUiEmailCaptureService(BootUiProperties properties, BootUiExposure exposure) {
        BootUiProperties.Email emailProperties = properties.getEmail();
        return new EmailCaptureService(
                new EmailStore(emailProperties.getMaxEntries(), emailProperties.getMaxBodyLength()),
                exposure,
                emailProperties.isDevTrap(),
                emailProperties.isMaskContent());
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    ScheduledTasksService bootUiScheduledTasksService(ObjectProvider<ScheduledTaskProvider> scheduledTaskProviders) {
        return new ScheduledTasksService(scheduledTaskProviders.getIfAvailable());
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.hibernate.SessionEventListener")
    static class OrmJournalConfiguration {

        @Bean
        io.github.jdubois.bootui.engine.journal.OrmSessionEvents.Publisher bootUiOrmSessionEventsPublisher() {
            return new io.github.jdubois.bootui.engine.journal.OrmSessionEvents.Publisher();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = {"jakarta.persistence.EntityManagerFactory", "org.hibernate.SessionFactory"})
    static class HibernateAdvisorConfiguration {

        @Bean
        @Lazy
        @ConditionalOnMissingBean
        io.github.jdubois.bootui.engine.hibernate.HibernateAdvisorObservationSource
                bootUiHibernateAdvisorObservationSource(
                        ListableBeanFactory beanFactory,
                        Environment environment,
                        ObjectProvider<ConnectionPoolProvider> connectionPools) {
            return new io.github.jdubois.bootui.autoconfigure.hibernate.SpringHibernateAdvisorObservationSource(
                    beanFactory, environment, connectionPools);
        }

        @Bean
        @Lazy
        @ConditionalOnMissingBean
        HibernateScanner bootUiHibernateScanner(
                io.github.jdubois.bootui.engine.hibernate.HibernateAdvisorObservationSource observations,
                BootUiProperties properties) {
            HibernateScanner scanner = HibernateScanner.observing(observations, Clock.systemUTC());
            scanner.setViolationRetentionLimit(() -> properties.getAdvisors().getMaxRetainedViolations());
            return scanner;
        }

        @Bean
        @Lazy
        @ConditionalOnMissingBean
        HibernateStatisticsService bootUiHibernateStatisticsService(
                ObjectProvider<EntityManagerFactory> entityManagerFactories) {
            return new HibernateStatisticsService(new SpringHibernateStatisticsProvider(entityManagerFactories));
        }

        @Bean
        @Lazy
        @ConditionalOnMissingBean
        EntityDiscoverySource bootUiEntityDiscoverySource(
                ObjectProvider<EntityManagerFactory> entityManagerFactories,
                ObjectProvider<ListableBeanFactory> beanFactories) {
            return () -> SpringHibernateDiscovery.discover(entityManagerFactories, beanFactories);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.actuate.logging.LoggersEndpoint")
    static class LoggersBackendConfiguration {

        @Bean
        @Lazy
        @ConditionalOnMissingBean
        SpringLoggerProvider bootUiSpringLoggerProvider(ObjectProvider<LoggersEndpoint> endpoints) {
            return new SpringLoggerProvider(endpoints::getIfAvailable);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.health.actuate.endpoint.HealthEndpoint")
    static class HealthBackendConfiguration {

        @Bean
        @Lazy
        @ConditionalOnMissingBean
        SpringHealthProvider bootUiSpringHealthProvider(ObjectProvider<HealthEndpoint> endpoints) {
            return new SpringHealthProvider(endpoints::getIfAvailable);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.actuate.web.mappings.MappingsEndpoint")
    static class MappingsBackendConfiguration {

        @Bean
        @Lazy
        @ConditionalOnMissingBean
        SpringMappingProvider bootUiSpringMappingProvider(
                ObjectProvider<MappingsEndpoint> endpoints, BootUiSelfDataFilter selfDataFilter) {
            return new SpringMappingProvider(endpoints::getIfAvailable, selfDataFilter);
        }

        @Bean
        @Lazy
        @ConditionalOnMissingBean
        ActuatorMappingsController bootUiActuatorMappingsController(ObjectProvider<MappingsEndpoint> endpoints) {
            return new ActuatorMappingsController(endpoints);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.actuate.beans.BeansEndpoint")
    static class BeansBackendConfiguration {

        @Bean
        @Lazy
        @ConditionalOnMissingBean
        SpringBeanProvider bootUiSpringBeanProvider(
                ObjectProvider<BeansEndpoint> endpoints, BootUiSelfDataFilter selfDataFilter) {
            return new SpringBeanProvider(endpoints::getIfAvailable, selfDataFilter);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.scheduling.config.ScheduledTaskHolder")
    static class ScheduledTasksBackendConfiguration {

        @Bean
        ScheduledTaskRunStore bootUiScheduledTaskRunStore(BootUiProperties properties) {
            return new ScheduledTaskRunStore(properties.getActivity().getMaxScheduledTaskRuns());
        }

        @Bean
        ScheduledTaskRunObservationHandler bootUiScheduledTaskRunObservationHandler(
                ScheduledTaskRunStore store, BootUiSelfDataFilter selfDataFilter) {
            return new ScheduledTaskRunObservationHandler(store, selfDataFilter);
        }

        @Bean
        BootUiSchedulingConfigurer bootUiSchedulingConfigurer(ScheduledTaskRunObservationHandler handler) {
            return new BootUiSchedulingConfigurer(handler);
        }

        @Bean
        @Lazy
        @ConditionalOnMissingBean
        SpringScheduledTaskProvider bootUiSpringScheduledTaskProvider(
                ObjectProvider<ScheduledTaskHolder> scheduledTaskHolders, BootUiSelfDataFilter selfDataFilter) {
            return new SpringScheduledTaskProvider(
                    scheduledTaskHolders.orderedStream().toList(), selfDataFilter);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RestClientTraceBackendConfiguration {

        @Bean
        RestClientTraceRecorder bootUiRestClientTraceRecorder(
                BootUiProperties properties, ObjectProvider<ThreadKindClassifier> threadKinds) {
            BootUiProperties.RestClientTrace restClientTrace = properties.getRestClientTrace();
            boolean enabled = restClientTrace.isEnabled() && properties.isPanelEnabled(BootUiPanels.REST_CLIENT_TRACE);
            RestClientTraceRecorder recorder = new RestClientTraceRecorder(
                    enabled,
                    restClientTrace.isRecording(),
                    restClientTrace.isCaptureHeaders(),
                    restClientTrace.isCaptureCallSite(),
                    restClientTrace.getMaxEntries(),
                    restClientTrace.getSlowCallThresholdMillis(),
                    restClientTrace.getMaxUriLength(),
                    restClientTrace.getMaxHeaderValueLength(),
                    restClientTrace.getChattyCallThreshold(),
                    restClientTrace.getReservedSharePercent());
            threadKinds.ifAvailable(recorder::setThreadKindClassifier);
            return recorder;
        }

        @Bean
        IdleReclaimable bootUiRestClientTraceRecorderIdleReclaimable(RestClientTraceRecorder recorder) {
            return new IdleReclaimable() {
                @Override
                public void suspendForIdle() {
                    recorder.suspendForIdle();
                }

                @Override
                public void resumeFromIdle() {
                    recorder.resumeFromIdle();
                }
            };
        }

        @Configuration(proxyBeanMethods = false)
        @ConditionalOnClass(
                name = {
                    "org.springframework.boot.restclient.RestClientCustomizer",
                    "org.springframework.boot.restclient.RestTemplateCustomizer"
                })
        static class RestClientCustomizerConfiguration {

            @Bean
            RestClientCustomizer bootUiRestClientCustomizer(RestClientTraceRecorder recorder) {
                return (RestClient.Builder builder) -> {
                    if (recorder.isEnabled()) {
                        recorder.registerClientCustomization("RestClient");
                        builder.requestInterceptor(new RestClientTraceInterceptor(recorder, "RestClient"));
                    }
                };
            }

            @Bean
            RestTemplateCustomizer bootUiRestTemplateCustomizer(RestClientTraceRecorder recorder) {
                return (RestTemplate restTemplate) -> {
                    if (recorder.isEnabled()) {
                        recorder.registerClientCustomization("RestTemplate");
                        restTemplate.getInterceptors().add(new RestClientTraceInterceptor(recorder, "RestTemplate"));
                    }
                };
            }
        }

        @Configuration(proxyBeanMethods = false)
        @ConditionalOnClass(
                name = {
                    "org.springframework.web.reactive.function.client.WebClient",
                    "org.springframework.boot.webclient.WebClientCustomizer"
                })
        static class WebClientCustomizerConfiguration {

            @Bean
            WebClientCustomizer bootUiWebClientCustomizer(RestClientTraceRecorder recorder) {
                return (WebClient.Builder builder) -> {
                    if (recorder.isEnabled()) {
                        recorder.registerClientCustomization("WebClient");
                        builder.filter(new RestClientTraceExchangeFilter(recorder));
                    }
                };
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.cache.CacheManager")
    static class CacheBackendConfiguration {

        @Bean
        @Lazy
        @ConditionalOnMissingBean
        CacheService bootUiCacheService(
                ObjectProvider<ListableBeanFactory> beanFactoryProvider,
                ObjectProvider<CacheOperationSource> cacheOperationSources,
                ObjectProvider<MeterRegistry> meterRegistries,
                BootUiProperties properties,
                BootUiSelfDataFilter selfDataFilter) {
            SpringCacheProvider provider =
                    new SpringCacheProvider(beanFactoryProvider, cacheOperationSources, properties, selfDataFilter);
            return new CacheService(
                    provider,
                    () -> {
                        MeterRegistry unique = meterRegistries.getIfUnique();
                        return unique != null
                                ? unique
                                : meterRegistries.orderedStream().findFirst().orElse(null);
                    },
                    selfDataFilter::shouldIncludeMeter);
        }

        @Bean
        CacheActivityRecorder bootUiCacheActivityRecorder(BootUiProperties properties) {
            BootUiProperties.Cache cache = properties.getCache();
            return new CacheActivityRecorder(
                    cache.isActivityCaptureEnabled() && properties.isPanelEnabled(BootUiPanels.CACHE),
                    cache.getActivityMaxEvents());
        }

        @Bean
        static CacheActivityCacheManagerBeanPostProcessor bootUiCacheActivityCacheManagerBeanPostProcessor(
                ObjectProvider<CacheActivityRecorder> recorderProvider,
                ObjectProvider<BootUiSelfDataFilter> selfDataFilterProvider) {
            return new CacheActivityCacheManagerBeanPostProcessor(recorderProvider, selfDataFilterProvider);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class KafkaBackendConfiguration {

        @Bean
        @Lazy
        @ConditionalOnMissingBean
        @ConditionalOnClass(name = "org.springframework.kafka.core.KafkaTemplate")
        KafkaActivityRecorder bootUiKafkaActivityRecorder(BootUiProperties properties) {
            BootUiProperties.Kafka kafka = properties.getKafka();
            boolean enabled = kafka.isEnabled() && properties.isPanelEnabled(BootUiPanels.KAFKA);
            return new KafkaActivityRecorder(
                    enabled, kafka.isCaptureKey(), kafka.getMaxEntries(), kafka.getMaxKeyLength());
        }

        @Bean
        @ConditionalOnClass(name = "org.springframework.kafka.core.KafkaTemplate")
        static KafkaProducerCaptureBeanPostProcessor bootUiKafkaProducerCaptureBeanPostProcessor(
                ObjectProvider<KafkaActivityRecorder> recorderProvider) {
            return new KafkaProducerCaptureBeanPostProcessor(recorderProvider);
        }

        @Bean
        @ConditionalOnClass(name = "org.springframework.kafka.core.KafkaTemplate")
        static KafkaConsumerCaptureBeanPostProcessor bootUiKafkaConsumerCaptureBeanPostProcessor(
                ObjectProvider<KafkaActivityRecorder> recorderProvider) {
            return new KafkaConsumerCaptureBeanPostProcessor(recorderProvider);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FaultToleranceBackendConfiguration {

        @Bean
        @Lazy
        @ConditionalOnMissingBean
        FaultToleranceEventRecorder bootUiFaultToleranceEventRecorder(
                BootUiProperties properties, ConfigurableListableBeanFactory beanFactory) {
            BootUiProperties.FaultTolerance faultTolerance = properties.getFaultTolerance();
            boolean enabled = faultTolerance.isEnabled()
                    && properties.isPanelEnabled(BootUiPanels.FAULT_TOLERANCE)
                    && faultToleranceLibraryPresent(beanFactory.getBeanClassLoader());
            return new FaultToleranceEventRecorder(enabled, faultTolerance.getMaxEvents());
        }

        private static boolean faultToleranceLibraryPresent(ClassLoader classLoader) {
            return ClassUtils.isPresent("io.github.resilience4j.core.Registry", classLoader)
                    || ClassUtils.isPresent("org.springframework.retry.annotation.Retryable", classLoader);
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnClass(name = "io.github.resilience4j.core.Registry")
        Resilience4jPolicyProvider bootUiResilience4jPolicyProvider(
                ConfigurableListableBeanFactory beanFactory, FaultToleranceEventRecorder recorder) {
            return new Resilience4jPolicyProvider(beanFactory, recorder);
        }

        @Configuration(proxyBeanMethods = false)
        @ConditionalOnClass(
                name = {"org.springframework.retry.annotation.Retryable", "org.springframework.retry.RetryListener"})
        static class SpringRetryFaultToleranceConfiguration {

            @Bean
            @Lazy
            @ConditionalOnMissingBean
            SpringRetryPolicyProvider bootUiSpringRetryPolicyProvider(ConfigurableListableBeanFactory beanFactory) {
                return new SpringRetryPolicyProvider(beanFactory);
            }

            @Bean
            BootUiRetryListener bootUiRetryListener(FaultToleranceEventRecorder recorder) {
                return new BootUiRetryListener(recorder);
            }
        }

        @Bean
        @Lazy
        @ConditionalOnMissingBean
        FaultToleranceService bootUiFaultToleranceService(
                ObjectProvider<FaultTolerancePolicyProvider> providers,
                FaultToleranceEventRecorder recorder,
                BootUiProperties properties) {
            return new FaultToleranceService(
                    providers.orderedStream().toList(),
                    recorder,
                    properties.getFaultTolerance().getMaxEvents());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class JmsBackendConfiguration {

        @Bean
        @Lazy
        @ConditionalOnMissingBean
        @ConditionalOnClass(name = {"org.springframework.jms.core.JmsTemplate", "jakarta.jms.Message"})
        JmsActivityRecorder bootUiJmsActivityRecorder(BootUiProperties properties) {
            BootUiProperties.Jms jms = properties.getJms();
            boolean enabled = jms.isEnabled() && properties.isPanelEnabled(BootUiPanels.JMS);
            return new JmsActivityRecorder(
                    enabled, jms.isCaptureMessageId(), jms.getMaxEntries(), jms.getMaxMessageIdLength());
        }

        @Bean
        @ConditionalOnClass(name = {"org.springframework.jms.core.JmsTemplate", "jakarta.jms.Message"})
        static JmsProducerCaptureBeanPostProcessor bootUiJmsProducerCaptureBeanPostProcessor(
                ObjectProvider<JmsActivityRecorder> recorderProvider) {
            return new JmsProducerCaptureBeanPostProcessor(recorderProvider);
        }

        @Bean
        @ConditionalOnClass(name = {"org.springframework.jms.core.JmsTemplate", "jakarta.jms.Message"})
        static JmsListenerCaptureBeanPostProcessor bootUiJmsListenerCaptureBeanPostProcessor(
                ObjectProvider<JmsActivityRecorder> recorderProvider) {
            return new JmsListenerCaptureBeanPostProcessor(recorderProvider);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RabbitBackendConfiguration {

        @Bean
        @Lazy
        @ConditionalOnMissingBean
        @ConditionalOnClass(name = "org.springframework.amqp.rabbit.core.RabbitTemplate")
        RabbitActivityRecorder bootUiRabbitActivityRecorder(BootUiProperties properties) {
            BootUiProperties.Rabbitmq rabbit = properties.getRabbitmq();
            boolean enabled = rabbit.isEnabled() && properties.isPanelEnabled(BootUiPanels.RABBITMQ);
            return new RabbitActivityRecorder(
                    enabled,
                    rabbit.isCaptureCorrelationId(),
                    rabbit.getMaxEntries(),
                    rabbit.getMaxCorrelationIdLength());
        }

        @Bean
        @ConditionalOnClass(name = "org.springframework.amqp.rabbit.core.RabbitTemplate")
        static RabbitProducerCaptureBeanPostProcessor bootUiRabbitProducerCaptureBeanPostProcessor(
                ObjectProvider<RabbitActivityRecorder> recorderProvider) {
            return new RabbitProducerCaptureBeanPostProcessor(recorderProvider);
        }

        @Bean
        @ConditionalOnClass(name = "org.springframework.amqp.rabbit.core.RabbitTemplate")
        static RabbitConsumerCaptureBeanPostProcessor bootUiRabbitConsumerCaptureBeanPostProcessor(
                ObjectProvider<RabbitActivityRecorder> recorderProvider) {
            return new RabbitConsumerCaptureBeanPostProcessor(recorderProvider);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class WebSocketBackendConfiguration {

        @Bean
        @Lazy
        @ConditionalOnMissingBean
        WebSocketSettings bootUiWebSocketSettings(BootUiProperties properties) {
            BootUiProperties.WebSockets websockets = properties.getWebsockets();
            return new WebSocketSettings(
                    websockets.isEnabled() && properties.isPanelEnabled(BootUiPanels.WEBSOCKETS),
                    websockets.isCapturing(),
                    websockets.getMaxEndpoints(),
                    websockets.getMaxSessions(),
                    websockets.getMaxSubscriptions(),
                    websockets.getMaxActivityEntries(),
                    websockets.getMaxTrackedSessions());
        }

        @Bean
        @Lazy
        @ConditionalOnMissingBean
        WebSocketActivityRecorder bootUiWebSocketActivityRecorder(WebSocketSettings settings) {
            return new WebSocketActivityRecorder(settings);
        }

        @Bean
        @Lazy
        IdleReclaimable bootUiWebSocketRecorderIdleReclaimable(WebSocketActivityRecorder recorder) {
            return new IdleReclaimable() {
                @Override
                public void suspendForIdle() {
                    recorder.suspendForIdle();
                }

                @Override
                public void resumeFromIdle() {
                    recorder.resumeFromIdle();
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Flyway.class)
    static class FlywayBackendConfiguration {

        @Bean
        @Lazy
        @ConditionalOnMissingBean
        FlywayService bootUiFlywayService(ObjectProvider<ListableBeanFactory> beanFactoryProvider) {
            return new FlywayService(new SpringFlywayProvider(beanFactoryProvider));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "liquibase.integration.spring.SpringLiquibase")
    static class LiquibaseBackendConfiguration {

        @Bean
        @Lazy
        @ConditionalOnMissingBean
        LiquibaseService bootUiLiquibaseService(ObjectProvider<ListableBeanFactory> beanFactoryProvider) {
            return new LiquibaseService(new SpringLiquibaseProvider(beanFactoryProvider));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "com.zaxxer.hikari.HikariDataSource")
    static class ConnectionPoolsBackendConfiguration {

        @Bean
        @Lazy
        @ConditionalOnMissingBean(ConnectionPoolProvider.class)
        ConnectionPoolProvider bootUiConnectionPoolProvider(ObjectProvider<ListableBeanFactory> beanFactoryProvider) {
            return new SpringConnectionPoolProvider(beanFactoryProvider);
        }

        @Bean
        @Lazy
        @ConditionalOnMissingBean
        ConnectionPoolService bootUiConnectionPoolService(
                ConnectionPoolProvider connectionPoolProvider, BootUiExposure exposure) {
            return new ConnectionPoolService(connectionPoolProvider, exposure);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ActivityPersistenceBackendConfiguration {

        @Bean
        @Lazy
        @ConditionalOnMissingBean
        ActivityPersistenceSettings bootUiActivityPersistenceSettings(
                BootUiProperties properties, Environment environment) {
            BootUiProperties.ActivityPersistence persistence =
                    properties.getActivity().getPersistence();
            String instanceId = ActivityInstanceIds.resolveOrDefault(
                    persistence.getInstanceId(), environment.getProperty("spring.application.name"));
            ActivityPersistenceSettings.DataSourceMode dataSourceMode =
                    persistence.getDataSourceMode() == BootUiProperties.ActivityPersistence.DataSourceMode.DEDICATED
                            ? ActivityPersistenceSettings.DataSourceMode.DEDICATED
                            : ActivityPersistenceSettings.DataSourceMode.SHARED;
            return new ActivityPersistenceSettings(
                    persistence.isEnabled(),
                    dataSourceMode,
                    persistence.getDedicatedJdbcUrl(),
                    persistence.getDedicatedUsername(),
                    persistence.getDedicatedPassword(),
                    persistence.getDedicatedDriverClassName(),
                    persistence.getTableName(),
                    persistence.getFlushInterval(),
                    persistence.getBufferMaxEntries(),
                    persistence.getRetention(),
                    instanceId);
        }

        @Bean
        @Lazy
        @ConditionalOnMissingBean
        SwitchableActivityStore bootUiActivityStore(
                ActivityPersistenceSettings settings, ObjectProvider<DataSource> dataSourceProvider) {
            return ActivityStoreFactory.create(settings, () -> resolveActivityDataSource(dataSourceProvider));
        }
    }

    public static DataSource resolveActivityDataSource(ObjectProvider<DataSource> dataSourceProvider) {
        try {
            return dataSourceProvider.getIfAvailable();
        } catch (NoUniqueBeanDefinitionException ex) {
            return dataSourceProvider.orderedStream().findFirst().orElse(null);
        }
    }

    static RestApiScanner.SpringWebStack springWebStack(ApplicationContext applicationContext) {
        if (applicationContext instanceof ReactiveWebApplicationContext) {
            return RestApiScanner.SpringWebStack.REACTIVE;
        }
        if (applicationContext instanceof WebApplicationContext) {
            return RestApiScanner.SpringWebStack.SERVLET;
        }
        return RestApiScanner.SpringWebStack.UNKNOWN;
    }

    private static boolean isSpringApiVersioningConfigured(
            Environment environment, ApplicationContext applicationContext) {
        String prefix = applicationContext instanceof ReactiveWebApplicationContext
                ? "spring.webflux.apiversion"
                : "spring.mvc.apiversion";
        Binder binder = Binder.get(environment);
        return binder.bind(prefix + ".supported", Bindable.listOf(String.class)).orElse(List.of()).stream()
                        .anyMatch(value -> !value.isBlank())
                || hasTextProperty(environment, prefix + ".default")
                || hasTextProperty(environment, prefix + ".use.header")
                || (hasTextProperty(environment, prefix + ".use.path-segment")
                        && binder.bind(prefix + ".use.path-segment", Integer.class)
                                .isBound())
                || hasTextProperty(environment, prefix + ".use.query-parameter")
                || binder
                        .bind(prefix + ".use.media-type-parameter", Bindable.mapOf(String.class, String.class))
                        .orElseGet(java.util.Map::of)
                        .values()
                        .stream()
                        .anyMatch(value -> !value.isBlank());
    }

    private static boolean hasTextProperty(Environment environment, String name) {
        String value = environment.getProperty(name);
        return value != null && !value.isBlank();
    }
}

final class BootUiInstanceLeaseLifecycle implements ApplicationListener<ApplicationEvent>, DisposableBean {

    private final RunIdentity run;
    private final BootUiProperties properties;
    private final ApplicationContext context;
    private final boolean startedInTest;
    private volatile Path leaseFile;

    BootUiInstanceLeaseLifecycle(RunIdentity run, BootUiProperties properties, ApplicationContext context) {
        this(run, properties, context, BootUiAgentClaimEnvironmentPostProcessor.startedByTestFramework());
    }

    BootUiInstanceLeaseLifecycle(
            RunIdentity run, BootUiProperties properties, ApplicationContext context, boolean startedInTest) {
        this.run = Objects.requireNonNull(run, "run");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.context = Objects.requireNonNull(context, "context");
        this.startedInTest = startedInTest;
    }

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        if (event instanceof WebServerInitializedEvent webEvent) {
            if (webEvent.getApplicationContext() == context && webEvent.getWebServer() != null) {
                int port = webEvent.getWebServer().getPort();
                if (port > 0) {
                    registerIfEligible(port);
                }
            }
        } else if (event instanceof ApplicationReadyEvent readyEvent) {
            if (readyEvent.getApplicationContext() == context && leaseFile == null) {
                registerIfEligible(null);
            }
        } else if (event instanceof ContextClosedEvent closedEvent) {
            if (closedEvent.getApplicationContext() == context) {
                destroy();
            }
        }
    }

    synchronized Path registerIfEligible(Integer boundPort) {
        if (!properties.getCli().isEnabled()) {
            return null;
        }
        Environment environment = context.getEnvironment();
        if (!isDiscoveryEnabled(environment)) {
            return null;
        }
        int port = resolvePort(environment, boundPort);
        if (port <= 0 || port > 65535) {
            return null;
        }
        boolean sslEnabled = isSslEnabled(environment);
        String baseUrl = (sslEnabled ? "https" : "http") + "://localhost:" + port;
        String apiPath = resolveEffectiveApiPath(environment);
        String appName = environment.getProperty("spring.application.name", "application");
        CliService.deleteInstanceLease(this.leaseFile);
        this.leaseFile = CliService.writeInstanceLease(
                run, appName, "Spring Boot", baseUrl, apiPath, resolveConfiguredDir(environment));
        return this.leaseFile;
    }

    boolean isDiscoveryEnabled(Environment environment) {
        if (environment != null) {
            String raw = environment.getProperty("bootui.cli.instance-discovery.enabled");
            if (raw != null && !raw.isBlank()) {
                return Boolean.parseBoolean(raw.trim());
            }
        }
        return !startedInTest && !BootUiAgentClaimEnvironmentPostProcessor.startedByTestFramework();
    }

    String resolveEffectiveApiPath(Environment environment) {
        if (context instanceof ReactiveWebApplicationContext) {
            String basePath = environment == null ? "" : environment.getProperty("spring.webflux.base-path", "");
            return CliService.composeApiPath(properties.getApiPath(), basePath);
        }
        String contextPath = environment == null ? "" : environment.getProperty("server.servlet.context-path", "");
        String servletPath = environment == null ? "" : environment.getProperty("spring.mvc.servlet.path", "");
        return CliService.composeApiPath(properties.getApiPath(), contextPath, servletPath);
    }

    private String resolveConfiguredDir(Environment environment) {
        String configured = properties.getCli().getInstancesDir();
        if ((configured == null || configured.isBlank()) && environment != null) {
            configured = environment.getProperty(
                    "bootui.cli.instances-dir", environment.getProperty("bootui.cli.instance-discovery.instances-dir"));
        }
        return configured;
    }

    private static boolean isSslEnabled(Environment environment) {
        if (environment == null) {
            return false;
        }
        Boolean explicit = environment.getProperty("server.ssl.enabled", Boolean.class);
        if (explicit != null) {
            return explicit;
        }
        return isNonBlank(environment.getProperty("server.ssl.bundle"))
                || isNonBlank(environment.getProperty("server.ssl.key-store"))
                || isNonBlank(environment.getProperty("server.ssl.certificate"))
                || "PKCS11"
                        .equalsIgnoreCase(environment
                                .getProperty("server.ssl.key-store-type", "")
                                .trim());
    }

    private static boolean isNonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static int resolvePort(Environment environment, Integer boundPort) {
        if (boundPort != null && boundPort > 0) {
            return boundPort;
        }
        if (environment == null) {
            return -1;
        }
        String raw = environment.getProperty("local.server.port");
        if (raw == null || raw.isBlank()) {
            return -1;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException invalid) {
            return -1;
        }
    }

    Path leaseFile() {
        return leaseFile;
    }

    @Override
    public synchronized void destroy() {
        Path current = this.leaseFile;
        this.leaseFile = null;
        CliService.deleteInstanceLease(current);
    }
}
