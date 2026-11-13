package io.github.jdubois.bootui.quarkus.deployment;

import io.github.jdubois.bootui.engine.javaagent.AgentClaim;
import io.github.jdubois.bootui.engine.javaagent.AgentPackages;
import io.github.jdubois.bootui.engine.javaagent.AgentSensorSettings;
import io.github.jdubois.bootui.quarkus.BootUiEngineProducer;
import io.github.jdubois.bootui.quarkus.BootUiPathRewriteFilter;
import io.github.jdubois.bootui.quarkus.BootUiProdShellGuardFilter;
import io.github.jdubois.bootui.quarkus.BootUiQuarkusAuthenticationFilter;
import io.github.jdubois.bootui.quarkus.BootUiQuarkusSafetyFilter;
import io.github.jdubois.bootui.quarkus.BootUiQuarkusStartupBanner;
import io.github.jdubois.bootui.quarkus.BootUiTelemetryProducer;
import io.github.jdubois.bootui.quarkus.QuarkusApplicationInfo;
import io.github.jdubois.bootui.quarkus.QuarkusBasePackageProvider;
import io.github.jdubois.bootui.quarkus.QuarkusDependencyProvider;
import io.github.jdubois.bootui.quarkus.QuarkusExposurePolicy;
import io.github.jdubois.bootui.quarkus.QuarkusMemoryRuntimeConfig;
import io.github.jdubois.bootui.quarkus.QuarkusPanelAccessFilter;
import io.github.jdubois.bootui.quarkus.QuarkusPanelAvailability;
import io.github.jdubois.bootui.quarkus.QuarkusRunStart;
import io.github.jdubois.bootui.quarkus.QuarkusServerPortSupplier;
import io.github.jdubois.bootui.quarkus.QuarkusTelemetrySettings;
import io.github.jdubois.bootui.quarkus.activity.QuarkusActivityCapture;
import io.github.jdubois.bootui.quarkus.agent.AgentSessionProducer;
import io.github.jdubois.bootui.quarkus.beans.QuarkusBeanDependencies;
import io.github.jdubois.bootui.quarkus.cli.BootUiCliProducer;
import io.github.jdubois.bootui.quarkus.config.QuarkusConfigProvider;
import io.github.jdubois.bootui.quarkus.devservices.DevServicesRecorder;
import io.github.jdubois.bootui.quarkus.devservices.QuarkusDevServicesProvider;
import io.github.jdubois.bootui.quarkus.devservices.RawDevService;
import io.github.jdubois.bootui.quarkus.errorcontract.ErrorContractRecorder;
import io.github.jdubois.bootui.quarkus.errorcontract.QuarkusErrorContract;
import io.github.jdubois.bootui.quarkus.errorcontract.QuarkusErrorContractProvider;
import io.github.jdubois.bootui.quarkus.errorcontract.RawErrorHandler;
import io.github.jdubois.bootui.quarkus.exceptions.QuarkusExceptionCapture;
import io.github.jdubois.bootui.quarkus.exceptions.QuarkusPreMappingExceptionCaptureHandler;
import io.github.jdubois.bootui.quarkus.faulttolerance.FaultTolerancePoliciesRecorder;
import io.github.jdubois.bootui.quarkus.faulttolerance.QuarkusFaultToleranceCapture;
import io.github.jdubois.bootui.quarkus.faulttolerance.QuarkusFaultTolerancePolicies;
import io.github.jdubois.bootui.quarkus.faulttolerance.QuarkusFaultTolerancePolicyProvider;
import io.github.jdubois.bootui.quarkus.faulttolerance.RawFaultTolerancePolicy;
import io.github.jdubois.bootui.quarkus.faulttolerance.RawFaultToleranceSetting;
import io.github.jdubois.bootui.quarkus.javaagent.BootUiAgentRecorder;
import io.github.jdubois.bootui.quarkus.javaagent.QuarkusAgentClaim;
import io.github.jdubois.bootui.quarkus.javaagent.QuarkusAgentClaimLifecycle;
import io.github.jdubois.bootui.quarkus.logging.QuarkusLogTailCapture;
import io.github.jdubois.bootui.quarkus.mappings.MappingsRecorder;
import io.github.jdubois.bootui.quarkus.mappings.QuarkusMappingProvider;
import io.github.jdubois.bootui.quarkus.mappings.QuarkusMappings;
import io.github.jdubois.bootui.quarkus.mappings.RawMapping;
import io.github.jdubois.bootui.quarkus.mcp.BootUiMcpProducer;
import io.github.jdubois.bootui.quarkus.mcp.QuarkusMcpEnvelope;
import io.github.jdubois.bootui.quarkus.mcp.QuarkusMcpFailureReporter;
import io.github.jdubois.bootui.quarkus.mcp.QuarkusMcpTools;
import io.github.jdubois.bootui.quarkus.quarkusapp.QuarkusAppMetadataStore;
import io.github.jdubois.bootui.quarkus.scheduled.QuarkusScheduledTaskProvider;
import io.github.jdubois.bootui.quarkus.scheduled.QuarkusScheduledTasks;
import io.github.jdubois.bootui.quarkus.scheduled.RawScheduledTask;
import io.github.jdubois.bootui.quarkus.scheduled.ScheduledTasksRecorder;
import io.github.jdubois.bootui.quarkus.web.BootUiCliResource;
import io.github.jdubois.bootui.quarkus.web.ClaudeCodeResource;
import io.github.jdubois.bootui.quarkus.web.CodeInventoryResource;
import io.github.jdubois.bootui.quarkus.web.CodePathsResource;
import io.github.jdubois.bootui.quarkus.web.CopilotResource;
import io.github.jdubois.bootui.quarkus.web.DevServicesResource;
import io.github.jdubois.bootui.quarkus.web.ExceptionsResource;
import io.github.jdubois.bootui.quarkus.web.HttpExchangesResource;
import io.github.jdubois.bootui.quarkus.web.JavaAgentResource;
import io.github.jdubois.bootui.quarkus.web.LiveActivityResource;
import io.github.jdubois.bootui.quarkus.web.LiveServiceMapResource;
import io.github.jdubois.bootui.quarkus.web.McpBridgeResource;
import io.github.jdubois.bootui.quarkus.web.McpServerResource;
import io.github.jdubois.bootui.quarkus.web.QuarkusExceptionCaptureFilter;
import io.github.jdubois.bootui.quarkus.web.QuarkusHttpExchangeCaptureFilter;
import io.github.jdubois.bootui.quarkus.web.RuntimeInsightsResource;
import io.github.jdubois.bootui.quarkus.web.SecurityLogsResource;
import io.github.jdubois.bootui.quarkus.web.SideEffectsResource;
import io.github.jdubois.bootui.quarkus.web.SqlTraceResource;
import io.github.jdubois.bootui.quarkus.web.TransactionsResource;
import io.github.jdubois.bootui.quarkus.websocket.QuarkusWebSockets;
import io.github.jdubois.bootui.quarkus.websocket.RawWebSocketCallback;
import io.github.jdubois.bootui.quarkus.websocket.RawWebSocketEndpoint;
import io.github.jdubois.bootui.quarkus.websocket.WebSocketsRecorder;
import io.github.jdubois.bootui.spi.ErrorHandlerDescriptor;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.AnnotationsTransformerBuildItem;
import io.quarkus.arc.deployment.BeanArchiveIndexBuildItem;
import io.quarkus.arc.deployment.ExcludedTypeBuildItem;
import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.arc.deployment.ValidationPhaseBuildItem;
import io.quarkus.arc.processor.BeanInfo;
import io.quarkus.arc.processor.InjectionPointInfo;
import io.quarkus.bootstrap.classloading.QuarkusClassLoader;
import io.quarkus.builder.Version;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.Capability;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.ApplicationArchivesBuildItem;
import io.quarkus.deployment.builditem.ApplicationIndexBuildItem;
import io.quarkus.deployment.builditem.ApplicationInfoBuildItem;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.DevServicesResultBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.GeneratedResourceBuildItem;
import io.quarkus.deployment.builditem.IndexDependencyBuildItem;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.deployment.builditem.RunTimeConfigurationDefaultBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageProxyDefinitionBuildItem;
import io.quarkus.deployment.pkg.builditem.CurateOutcomeBuildItem;
import io.quarkus.maven.dependency.ResolvedDependency;
import io.quarkus.resteasy.reactive.server.deployment.ResteasyReactiveResourceMethodEntriesBuildItem;
import io.quarkus.resteasy.reactive.server.deployment.SetupEndpointsResultBuildItem;
import io.quarkus.resteasy.reactive.server.spi.PreExceptionMapperHandlerBuildItem;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.RuntimeValue;
import jakarta.inject.Singleton;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.ConfigValue;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.AnnotationTransformation;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.ParameterizedType;
import org.jboss.jandex.Type;

class BootUiQuarkusProcessor {

    private static final String FEATURE = "bootui";
    private static final DotName JAX_RS_HTTP_METHOD = DotName.createSimple("jakarta.ws.rs.HttpMethod");
    private static final Set<DotName> STANDARD_JAX_RS_HTTP_METHODS = Set.of(
            DotName.createSimple("jakarta.ws.rs.GET"),
            DotName.createSimple("jakarta.ws.rs.POST"),
            DotName.createSimple("jakarta.ws.rs.PUT"),
            DotName.createSimple("jakarta.ws.rs.DELETE"),
            DotName.createSimple("jakarta.ws.rs.PATCH"),
            DotName.createSimple("jakarta.ws.rs.HEAD"),
            DotName.createSimple("jakarta.ws.rs.OPTIONS"));

    private static final String BOOTUI_PACKAGE_PREFIX = "io.github.jdubois.bootui.quarkus";

    private static final int SYNTHETIC = 0x1000;

    private static final int BRIDGE = 0x0040;

    private static final String BOOTUI_PATH_PREFIX = "/bootui";

    private static final String OTEL_PRODUCER_CLASS = "io.github.jdubois.bootui.quarkus.BootUiOtelProducer";

    private static final String OTEL_TRACE_ID_PROVIDER_CLASS =
            "io.github.jdubois.bootui.quarkus.QuarkusOtelTraceIdSource";

    private static final String HEALTH_PRODUCER_CLASS = "io.github.jdubois.bootui.quarkus.BootUiHealthProducer";

    private static final String HIBERNATE_PRODUCER_CLASS = "io.github.jdubois.bootui.quarkus.BootUiHibernateProducer";

    private static final String CACHE_PRODUCER_CLASS = "io.github.jdubois.bootui.quarkus.BootUiCacheProducer";

    private static final String FLYWAY_PRODUCER_CLASS = "io.github.jdubois.bootui.quarkus.BootUiFlywayProducer";
    private static final String LIQUIBASE_PRODUCER_CLASS = "io.github.jdubois.bootui.quarkus.BootUiLiquibaseProducer";

    private static final String AGROAL_PRODUCER_CLASS = "io.github.jdubois.bootui.quarkus.BootUiAgroalProducer";

    private static final String SECURITY_CAPTURE_CLASS =
            "io.github.jdubois.bootui.quarkus.web.QuarkusSecurityEventCapture";

    private static final String KAFKA_PRODUCER_CAPTURE_CLASS =
            "io.github.jdubois.bootui.quarkus.kafka.QuarkusKafkaProducerCapture";
    private static final String KAFKA_CONSUMER_CAPTURE_CLASS =
            "io.github.jdubois.bootui.quarkus.kafka.QuarkusKafkaConsumerCapture";

    private static final String RABBIT_PRODUCER_CAPTURE_CLASS =
            "io.github.jdubois.bootui.quarkus.rabbit.QuarkusRabbitProducerCapture";
    private static final String RABBIT_CONSUMER_CAPTURE_CLASS =
            "io.github.jdubois.bootui.quarkus.rabbit.QuarkusRabbitConsumerCapture";

    private static final String WEBSOCKET_SESSION_PROVIDER_CLASS =
            "io.github.jdubois.bootui.quarkus.websocket.QuarkusWebSocketSessionProvider";
    private static final String WEBSOCKET_CONNECTION_CAPTURE_CLASS =
            "io.github.jdubois.bootui.quarkus.websocket.QuarkusWebSocketConnectionCapture";

    private static final String WEBSOCKET_CONNECTION_CLASS = "io.quarkus.websockets.next.WebSocketConnection";

    private static final String CHAT_MODEL_LISTENER_CLASS = "dev.langchain4j.model.chat.listener.ChatModelListener";

    private static final String AI_CALL_LISTENER_CLASS = "io.github.jdubois.bootui.quarkus.ai.QuarkusAiCallListener";

    private static final DotName WEBSOCKET_ANNOTATION = DotName.createSimple("io.quarkus.websockets.next.WebSocket");

    private static final List<DotName> WEBSOCKET_CALLBACK_ANNOTATIONS = List.of(
            DotName.createSimple("io.quarkus.websockets.next.OnOpen"),
            DotName.createSimple("io.quarkus.websockets.next.OnTextMessage"),
            DotName.createSimple("io.quarkus.websockets.next.OnBinaryMessage"),
            DotName.createSimple("io.quarkus.websockets.next.OnPingMessage"),
            DotName.createSimple("io.quarkus.websockets.next.OnPongMessage"),
            DotName.createSimple("io.quarkus.websockets.next.OnClose"),
            DotName.createSimple("io.quarkus.websockets.next.OnError"));

    private static final String ORM_SESSION_LISTENER_CLASS =
            "io.github.jdubois.bootui.quarkus.orm.BootUiHibernateSessionListener";

    private static final String WEBSOCKET_MESSAGE_INTERCEPTOR_CLASS =
            "io.github.jdubois.bootui.quarkus.websocket.QuarkusWebSocketMessageInterceptor";

    private static final DotName WEBSOCKET_MESSAGE_BINDING =
            DotName.createSimple("io.github.jdubois.bootui.quarkus.websocket.BootUiWebSocketMessage");

    private static final List<DotName> WEBSOCKET_MESSAGE_ANNOTATIONS = List.of(
            DotName.createSimple("io.quarkus.websockets.next.OnTextMessage"),
            DotName.createSimple("io.quarkus.websockets.next.OnBinaryMessage"));

    private static final String SCHEDULED_TASK_RUN_RECORDER_CLASS =
            "io.github.jdubois.bootui.quarkus.scheduled.QuarkusScheduledTaskRunRecorder";

    private static final String SCHEDULED_EXECUTION_INTERCEPTOR_CLASS =
            "io.github.jdubois.bootui.quarkus.scheduled.QuarkusScheduledExecutionInterceptor";

    private static final String OBSERVER_INTERCEPTOR_CLASS =
            "io.github.jdubois.bootui.quarkus.appevent.QuarkusObserverInterceptor";

    private static final DotName OBSERVED_EVENT_BINDING =
            DotName.createSimple("io.github.jdubois.bootui.quarkus.appevent.BootUiObservedEvent");

    private static final DotName OBSERVES = DotName.createSimple("jakarta.enterprise.event.Observes");

    private static final DotName OBSERVES_ASYNC = DotName.createSimple("jakarta.enterprise.event.ObservesAsync");

    private static final DotName SCHEDULED_EXECUTION_BINDING =
            DotName.createSimple("io.github.jdubois.bootui.quarkus.scheduled.BootUiScheduledExecution");

    private static final List<DotName> SCHEDULED_ANNOTATIONS = List.of(
            DotName.createSimple("io.quarkus.scheduler.Scheduled"),
            DotName.createSimple("io.quarkus.scheduler.Scheduled$Schedules"));

    private static final String EMAIL_CAPTURE_CLASS = "io.github.jdubois.bootui.quarkus.web.QuarkusEmailCapture";

    private static final String REACTIVE_MAILER_CLASS = "io.quarkus.mailer.reactive.ReactiveMailer";

    private static final String INCOMING_RABBITMQ_METADATA_CLASS =
            "io.smallrye.reactive.messaging.rabbitmq.IncomingRabbitMQMetadata";

    private static final String REST_CLIENT_TRACE_LISTENER_CLASS =
            "io.github.jdubois.bootui.quarkus.restclienttrace.QuarkusRestClientTraceListener";
    private static final String REST_CLIENT_LISTENER_SERVICE =
            "org.eclipse.microprofile.rest.client.spi.RestClientListener";

    private static final String SQL_TRACE_PRODUCER_CLASS =
            "io.github.jdubois.bootui.quarkus.sqltrace.BootUiSqlTraceProducer";

    private static final String SQL_TRACE_INSPECTOR_CLASS =
            "io.github.jdubois.bootui.quarkus.sqltrace.BootUiHibernateStatementInspector";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    void registerConsole(
            LaunchModeBuildItem launchMode,
            BuildProducer<IndexDependencyBuildItem> indexDependency,
            BuildProducer<AdditionalBeanBuildItem> additionalBeans) {
        if (launchMode.getLaunchMode() == LaunchMode.NORMAL) {
            return; // production: do not expose the console
        }
        indexDependency.produce(new IndexDependencyBuildItem("com.julien-dubois.bootui", "bootui-quarkus"));
        additionalBeans.produce(AdditionalBeanBuildItem.builder()
                .addBeanClasses(
                        BootUiEngineProducer.class,
                        BootUiTelemetryProducer.class,
                        QuarkusTelemetrySettings.class,
                        QuarkusExposurePolicy.class,
                        QuarkusMemoryRuntimeConfig.class,
                        QuarkusServerPortSupplier.class,
                        QuarkusApplicationInfo.class,
                        QuarkusRunStart.class,
                        QuarkusBasePackageProvider.class,
                        QuarkusDependencyProvider.class,
                        QuarkusConfigProvider.class,
                        QuarkusScheduledTaskProvider.class,
                        QuarkusFaultTolerancePolicyProvider.class,
                        QuarkusFaultToleranceCapture.class,
                        QuarkusMappingProvider.class,
                        QuarkusErrorContractProvider.class,
                        QuarkusDevServicesProvider.class,
                        DevServicesResource.class,
                        QuarkusPanelAvailability.class,
                        QuarkusLogTailCapture.class,
                        QuarkusExceptionCapture.class,
                        QuarkusExceptionCaptureFilter.class,
                        ExceptionsResource.class,
                        QuarkusHttpExchangeCaptureFilter.class,
                        HttpExchangesResource.class,
                        LiveActivityResource.class,
                        LiveServiceMapResource.class,
                        RuntimeInsightsResource.class,
                        JavaAgentResource.class,
                        CodeInventoryResource.class,
                        CodePathsResource.class,
                        SideEffectsResource.class,
                        QuarkusAgentClaimLifecycle.class,
                        QuarkusActivityCapture.class,
                        SecurityLogsResource.class,
                        SqlTraceResource.class,
                        TransactionsResource.class,
                        AgentSessionProducer.class,
                        CopilotResource.class,
                        ClaudeCodeResource.class,
                        McpServerResource.class,
                        McpBridgeResource.class,
                        BootUiMcpProducer.class,
                        BootUiCliProducer.class,
                        BootUiCliResource.class,
                        QuarkusMcpTools.class,
                        QuarkusMcpEnvelope.class,
                        QuarkusMcpFailureReporter.class,
                        BootUiQuarkusSafetyFilter.class,
                        BootUiPathRewriteFilter.class,
                        BootUiQuarkusAuthenticationFilter.class,
                        QuarkusPanelAccessFilter.class,
                        BootUiQuarkusStartupBanner.class)
                .setUnremovable()
                .build());
    }

    @BuildStep
    void registerExceptionPreMappingCapture(
            LaunchModeBuildItem launchMode,
            BuildProducer<PreExceptionMapperHandlerBuildItem> preExceptionMapperHandlers) {
        if (launchMode.getLaunchMode() == LaunchMode.NORMAL) {
            return; // production: console is dark, no capture
        }
        preExceptionMapperHandlers.produce(
                new PreExceptionMapperHandlerBuildItem(new QuarkusPreMappingExceptionCaptureHandler()));
    }

    @BuildStep
    AdditionalBeanBuildItem registerProdShellGuard() {
        return AdditionalBeanBuildItem.builder()
                .addBeanClasses(BootUiProdShellGuardFilter.class)
                .setUnremovable()
                .build();
    }

    @BuildStep
    RunTimeConfigurationDefaultBuildItem quarkusVersion() {
        return new RunTimeConfigurationDefaultBuildItem(
                QuarkusApplicationInfo.QUARKUS_VERSION_KEY, Version.getVersion());
    }

    @BuildStep
    void registerBasePackages(
            LaunchModeBuildItem launchMode,
            ApplicationIndexBuildItem applicationIndex,
            BuildProducer<RunTimeConfigurationDefaultBuildItem> runtimeDefaults) {
        if (launchMode.getLaunchMode() == LaunchMode.NORMAL) {
            return; // production: the console is dark, so nothing reads the key
        }
        Set<String> packageNames = new HashSet<>();
        for (ClassInfo classInfo : applicationIndex.getIndex().getKnownClasses()) {
            String fqn = classInfo.name().toString();
            int lastDot = fqn.lastIndexOf('.');
            packageNames.add(lastDot < 0 ? "" : fqn.substring(0, lastDot));
        }
        List<String> roots = BasePackageRoots.reduce(packageNames);
        if (!roots.isEmpty()) {
            runtimeDefaults.produce(new RunTimeConfigurationDefaultBuildItem(
                    QuarkusBasePackageProvider.BASE_PACKAGES_KEY, String.join(",", roots)));
        }
    }

    @BuildStep
    void registerRestApi(
            LaunchModeBuildItem launchMode,
            ApplicationIndexBuildItem applicationIndex,
            CombinedIndexBuildItem combinedIndex,
            BuildProducer<RunTimeConfigurationDefaultBuildItem> runtimeDefaults) {
        if (launchMode.getLaunchMode() == LaunchMode.NORMAL) {
            return;
        }
        IndexView index = applicationIndex.getIndex();
        boolean present = hasRestApiEndpoint(index, combinedIndex.getComputingIndex());
        runtimeDefaults.produce(
                new RunTimeConfigurationDefaultBuildItem(QuarkusPanelAvailability.REST_API_PRESENT_KEY, "" + present));
    }

    static boolean hasRestApiEndpoint(IndexView index) {
        return hasRestApiEndpoint(index, index);
    }

    static boolean hasRestApiEndpoint(IndexView applicationIndex, IndexView annotationIndex) {
        for (ClassInfo classInfo : applicationIndex.getKnownClasses()) {
            if (classInfo.declaredAnnotation(REGISTER_REST_CLIENT) != null) {
                continue;
            }
            for (MethodInfo method : classInfo.methods()) {
                for (AnnotationInstance annotation : method.annotations()) {
                    if (isJaxRsHttpMethod(annotationIndex, annotation.name())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isJaxRsHttpMethod(IndexView index, DotName annotationName) {
        if (STANDARD_JAX_RS_HTTP_METHODS.contains(annotationName)) {
            return true;
        }
        ClassInfo annotationType = index.getClassByName(annotationName);
        return annotationType != null && annotationType.declaredAnnotation(JAX_RS_HTTP_METHOD) != null;
    }

    @BuildStep
    void registerSecurityAnnotations(
            LaunchModeBuildItem launchMode,
            ApplicationIndexBuildItem applicationIndex,
            CombinedIndexBuildItem combinedIndex,
            io.quarkus.arc.deployment.ValidationPhaseBuildItem validation,
            List<io.quarkus.security.spi.AdditionalSecuredMethodsBuildItem> additionalSecurity,
            BuildProducer<io.quarkus.arc.deployment.ValidationPhaseBuildItem.ValidationErrorBuildItem> validationErrors,
            BuildProducer<RunTimeConfigurationDefaultBuildItem> runtimeDefaults) {
        if (launchMode.getLaunchMode() == LaunchMode.NORMAL) {
            return;
        }
        IndexView index = applicationIndex.getIndex();
        var capture = SecurityDeclarationCapture.capture(
                index,
                combinedIndex.getIndex(),
                SecurityDeclarationCapture.materializedMetadata(
                        index, validation.getContext().beans(), additionalSecurity));
        int secured = (int) capture.endpoints().stream()
                .filter(endpoint ->
                        endpoint.access() == io.github.jdubois.bootui.spi.QuarkusSecurityEndpoint.Access.RESTRICTED)
                .count();
        int permit = (int) capture.endpoints().stream()
                .filter(endpoint ->
                        endpoint.access() == io.github.jdubois.bootui.spi.QuarkusSecurityEndpoint.Access.PERMIT)
                .count();
        emit(runtimeDefaults, "bootui.internal.sec.roles-allowed", secured);
        emit(runtimeDefaults, "bootui.internal.sec.permit-all", permit);
        emit(runtimeDefaults, "bootui.internal.sec.deny-all", 0);
        emit(runtimeDefaults, "bootui.internal.sec.authenticated", 0);
        emit(runtimeDefaults, "bootui.internal.sec.quarkus-authz", 0);
        emit(
                runtimeDefaults,
                "bootui.internal.sec.endpoints",
                capture.endpoints().size());
        emit(runtimeDefaults, "bootui.internal.sec.secured-endpoints", secured);
        runtimeDefaults.produce(
                new RunTimeConfigurationDefaultBuildItem("bootui.internal.sec.endpoint-metadata", "true"));
        runtimeDefaults.produce(
                new RunTimeConfigurationDefaultBuildItem("bootui.internal.sec.incomplete", "" + capture.incomplete()));
        runtimeDefaults.produce(new RunTimeConfigurationDefaultBuildItem(
                "bootui.internal.sec.custom-authorization", "" + capture.customAuthorization()));
        runtimeDefaults.produce(new RunTimeConfigurationDefaultBuildItem(
                "bootui.internal.sec.custom-identity", "" + capture.customIdentity()));
        runtimeDefaults.produce(new RunTimeConfigurationDefaultBuildItem(
                "bootui.internal.sec.grpc-services", "" + capture.grpcServices()));
        runtimeDefaults.produce(
                new RunTimeConfigurationDefaultBuildItem("bootui.internal.sec.custom-tls", "" + capture.customTls()));
        runtimeDefaults.produce(new RunTimeConfigurationDefaultBuildItem(
                "bootui.internal.sec.custom-headers", "" + capture.customHeaders()));
        for (int i = 0; i < capture.endpoints().size(); i++) {
            var endpoint = capture.endpoints().get(i);
            String prefix = "bootui.internal.sec.endpoint." + i;
            runtimeDefaults.produce(new RunTimeConfigurationDefaultBuildItem(prefix + ".path", endpoint.path()));
            runtimeDefaults.produce(new RunTimeConfigurationDefaultBuildItem(prefix + ".method", endpoint.method()));
            runtimeDefaults.produce(new RunTimeConfigurationDefaultBuildItem(
                    prefix + ".access", endpoint.access().name()));
            runtimeDefaults.produce(
                    new RunTimeConfigurationDefaultBuildItem(prefix + ".document", "" + endpoint.document()));
        }
    }

    @BuildStep
    void registerCsrfDetection(
            LaunchModeBuildItem launchMode,
            List<FeatureBuildItem> features,
            BuildProducer<RunTimeConfigurationDefaultBuildItem> runtimeDefaults) {
        if (launchMode.getLaunchMode() == LaunchMode.NORMAL) {
            return;
        }
        boolean present = features.stream().anyMatch(f -> "rest-csrf".equals(f.getName()));
        runtimeDefaults.produce(
                new RunTimeConfigurationDefaultBuildItem("bootui.internal.sec.csrf-present", "" + present));
    }

    @BuildStep
    void registerQuarkusSpecificCapabilityFlags(
            LaunchModeBuildItem launchMode,
            Capabilities capabilities,
            List<FeatureBuildItem> features,
            BuildProducer<RunTimeConfigurationDefaultBuildItem> runtimeDefaults) {
        if (launchMode.getLaunchMode() == LaunchMode.NORMAL) {
            return;
        }
        runtimeDefaults.produce(new RunTimeConfigurationDefaultBuildItem(
                "bootui.internal.sec.grpc-present", "" + capabilities.isPresent(Capability.GRPC)));
        runtimeDefaults.produce(new RunTimeConfigurationDefaultBuildItem(
                "bootui.internal.sec.graphql-present", "" + capabilities.isPresent(Capability.SMALLRYE_GRAPHQL)));
        runtimeDefaults.produce(new RunTimeConfigurationDefaultBuildItem(
                "bootui.internal.sec.security-present", "" + capabilities.isPresent(Capability.SECURITY)));
        Map<String, String> securityFeatures = Map.of(
                "oidc",
                "oidc",
                "jwt",
                "smallrye-jwt",
                "jdbc",
                "security-jdbc",
                "properties",
                "security-properties-file",
                "kafka",
                "messaging-kafka",
                "openapi",
                "swagger-ui",
                "health",
                "smallrye-health");
        securityFeatures.forEach((flag, feature) -> runtimeDefaults.produce(new RunTimeConfigurationDefaultBuildItem(
                "bootui.internal.sec." + flag + "-present",
                ""
                        + features.stream()
                                .anyMatch(item -> feature.equals(item.getName())
                                        || ("messaging-kafka".equals(feature)
                                                && "smallrye-reactive-messaging-kafka".equals(item.getName()))))));
    }

    private static final List<String> QUARKUS_AUTHORIZATION_ANNOTATIONS =
            List.of("io.quarkus.security.PermissionsAllowed", "io.quarkus.vertx.http.security.AuthorizationPolicy");

    private static final List<String> ENDPOINT_SECURITY_ANNOTATIONS = List.of(
            "jakarta.annotation.security.RolesAllowed",
            "jakarta.annotation.security.PermitAll",
            "jakarta.annotation.security.DenyAll",
            "io.quarkus.security.Authenticated",
            "io.quarkus.security.PermissionsAllowed",
            "io.quarkus.vertx.http.security.AuthorizationPolicy");

    static int quarkusAuthorizationAnnotationCount(IndexView index) {
        return QUARKUS_AUTHORIZATION_ANNOTATIONS.stream()
                .mapToInt(annotation ->
                        index.getAnnotations(DotName.createSimple(annotation)).size())
                .sum();
    }

    static boolean isSecuredEndpoint(MethodInfo method) {
        for (String sec : ENDPOINT_SECURITY_ANNOTATIONS) {
            DotName name = DotName.createSimple(sec);
            if (method.hasAnnotation(name) || method.declaringClass().declaredAnnotation(name) != null) {
                return true;
            }
        }
        return false;
    }

    private static void emit(
            BuildProducer<RunTimeConfigurationDefaultBuildItem> runtimeDefaults, String key, int value) {
        runtimeDefaults.produce(new RunTimeConfigurationDefaultBuildItem(key, Integer.toString(value)));
    }

    static boolean hasJdbcDatasource(Capabilities capabilities) {
        return capabilities.isPresent(Capability.AGROAL);
    }

    private static final DotName PATH = DotName.createSimple("jakarta.ws.rs.Path");
    private static final DotName REGISTER_REST_CLIENT =
            DotName.createSimple("org.eclipse.microprofile.rest.client.inject.RegisterRestClient");

    private static final List<String> JAXRS_HTTP_METHODS = List.of(
            "jakarta.ws.rs.GET",
            "jakarta.ws.rs.POST",
            "jakarta.ws.rs.PUT",
            "jakarta.ws.rs.DELETE",
            "jakarta.ws.rs.PATCH",
            "jakarta.ws.rs.HEAD",
            "jakarta.ws.rs.OPTIONS");

    private static final DotName PRODUCES = DotName.createSimple("jakarta.ws.rs.Produces");

    private static final DotName EXCEPTION_MAPPER = DotName.createSimple("jakarta.ws.rs.ext.ExceptionMapper");

    private static final DotName SERVER_EXCEPTION_MAPPER =
            DotName.createSimple("org.jboss.resteasy.reactive.server.ServerExceptionMapper");

    private static final DotName PRIORITY = DotName.createSimple("jakarta.annotation.Priority");

    private static final DotName PROVIDER = DotName.createSimple("jakarta.ws.rs.ext.Provider");

    private static final DotName RESPONSE_STATUS = DotName.createSimple("org.jboss.resteasy.reactive.ResponseStatus");

    private static final DotName JAKARTA_RESPONSE = DotName.createSimple("jakarta.ws.rs.core.Response");
    private static final DotName REST_RESPONSE = DotName.createSimple("org.jboss.resteasy.reactive.RestResponse");

    private static final Set<DotName> ERROR_CONTRACT_ASYNC_WRAPPERS = Set.of(
            DotName.createSimple("io.smallrye.mutiny.Uni"),
            DotName.createSimple("java.util.concurrent.CompletionStage"),
            DotName.createSimple("java.util.concurrent.CompletableFuture"));

    private static final DotName CONSUMES = DotName.createSimple("jakarta.ws.rs.Consumes");

    @BuildStep
    void registerAppIdioms(
            LaunchModeBuildItem launchMode,
            BeanArchiveIndexBuildItem beanArchiveIndex,
            ApplicationIndexBuildItem applicationIndex,
            ValidationPhaseBuildItem validationPhase,
            Optional<ResteasyReactiveResourceMethodEntriesBuildItem> resourceMethods,
            Optional<SetupEndpointsResultBuildItem> endpoints,
            Capabilities capabilities,
            BuildProducer<GeneratedResourceBuildItem> generatedResources) {
        if (launchMode.getLaunchMode() == LaunchMode.NORMAL) {
            return;
        }
        var metadata = QuarkusAppMetadataCollector.collect(
                applicationIndex.getIndex(),
                beanArchiveIndex.getIndex(),
                validationPhase.getContext().beans(),
                validationPhase.getContext().getInjectionPoints(),
                resourceMethods,
                endpoints,
                capabilities);
        generatedResources.produce(new GeneratedResourceBuildItem(
                QuarkusAppMetadataStore.RESOURCE_NAME, QuarkusAppMetadataStore.encode(metadata)));
    }

    @BuildStep
    void registerDependencyInventory(
            LaunchModeBuildItem launchMode,
            CurateOutcomeBuildItem curateOutcome,
            BuildProducer<RunTimeConfigurationDefaultBuildItem> runtimeDefaults) {
        if (launchMode.getLaunchMode() == LaunchMode.NORMAL) {
            return; // production: the console is dark, so nothing reads the key
        }
        Set<String> coordinates = new LinkedHashSet<>();
        int skipped = 0;
        for (ResolvedDependency dependency : curateOutcome.getApplicationModel().getRuntimeDependencies()) {
            if (!"jar".equals(dependency.getType())) {
                continue;
            }
            String coordinate =
                    dependency.getGroupId() + ":" + dependency.getArtifactId() + ":" + dependency.getVersion();
            if (coordinate.indexOf(',') >= 0
                    || coordinate.indexOf('$') >= 0
                    || coordinate.chars().anyMatch(Character::isWhitespace)) {
                skipped++;
                continue;
            }
            coordinates.add(coordinate);
        }
        if (!coordinates.isEmpty()) {
            runtimeDefaults.produce(new RunTimeConfigurationDefaultBuildItem(
                    QuarkusDependencyProvider.DEPENDENCIES_KEY, String.join(",", coordinates)));
        }
        if (skipped > 0) {
            runtimeDefaults.produce(new RunTimeConfigurationDefaultBuildItem(
                    QuarkusDependencyProvider.SKIPPED_KEY, Integer.toString(skipped)));
        }
    }

    @BuildStep
    void registerOpenTelemetryCapture(
            LaunchModeBuildItem launchMode,
            Capabilities capabilities,
            BuildProducer<AdditionalBeanBuildItem> additionalBeans,
            BuildProducer<ExcludedTypeBuildItem> excludedTypes,
            BuildProducer<RunTimeConfigurationDefaultBuildItem> runtimeDefaults) {
        boolean enableCapture = launchMode.getLaunchMode() != LaunchMode.NORMAL
                && capabilities.isPresent(Capability.OPENTELEMETRY_TRACER);
        runtimeDefaults.produce(
                new RunTimeConfigurationDefaultBuildItem(QuarkusRunStart.OTEL_TRACER_PRESENT_KEY, "" + enableCapture));
        registerCapabilityGatedBeans(enableCapture, additionalBeans, excludedTypes, OTEL_PRODUCER_CLASS);
    }

    @BuildStep
    void registerOpenTelemetryCorrelation(
            LaunchModeBuildItem launchMode,
            Capabilities capabilities,
            BuildProducer<AdditionalBeanBuildItem> additionalBeans,
            BuildProducer<ExcludedTypeBuildItem> excludedTypes) {
        boolean enableCorrelation = launchMode.getLaunchMode() != LaunchMode.NORMAL
                && capabilities.isPresent(Capability.OPENTELEMETRY_TRACER);
        registerCapabilityGatedBeans(enableCorrelation, additionalBeans, excludedTypes, OTEL_TRACE_ID_PROVIDER_CLASS);
    }

    @BuildStep
    void registerSmallRyeHealthCapture(
            LaunchModeBuildItem launchMode,
            Capabilities capabilities,
            BuildProducer<AdditionalBeanBuildItem> additionalBeans,
            BuildProducer<ExcludedTypeBuildItem> excludedTypes) {
        boolean enableCapture =
                launchMode.getLaunchMode() != LaunchMode.NORMAL && capabilities.isPresent(Capability.SMALLRYE_HEALTH);
        registerCapabilityGatedBeans(enableCapture, additionalBeans, excludedTypes, HEALTH_PRODUCER_CLASS);
    }

    @BuildStep
    void registerAiCalls(
            LaunchModeBuildItem launchMode,
            BuildProducer<AdditionalBeanBuildItem> additionalBeans,
            BuildProducer<ExcludedTypeBuildItem> excludedTypes) {
        registerCapabilityGatedBeans(
                launchMode.getLaunchMode() != LaunchMode.NORMAL
                        && QuarkusClassLoader.isClassPresentAtRuntime(CHAT_MODEL_LISTENER_CLASS),
                additionalBeans,
                excludedTypes,
                AI_CALL_LISTENER_CLASS);
    }

    private static void registerCapabilityGatedBeans(
            boolean present,
            BuildProducer<AdditionalBeanBuildItem> additionalBeans,
            BuildProducer<ExcludedTypeBuildItem> excludedTypes,
            String... beanClasses) {
        if (present) {
            additionalBeans.produce(AdditionalBeanBuildItem.builder()
                    .addBeanClasses(beanClasses)
                    .setUnremovable()
                    .build());
        } else {
            for (String beanClass : beanClasses) {
                excludedTypes.produce(new ExcludedTypeBuildItem(beanClass));
            }
        }
    }

    private static void registerCapabilityGatedBeans(
            boolean present,
            BuildProducer<AdditionalBeanBuildItem> additionalBeans,
            BuildProducer<ExcludedTypeBuildItem> excludedTypes,
            BuildProducer<RunTimeConfigurationDefaultBuildItem> runtimeDefaults,
            String presentKey,
            String... beanClasses) {
        registerCapabilityGatedBeans(present, additionalBeans, excludedTypes, beanClasses);
        if (present) {
            runtimeDefaults.produce(new RunTimeConfigurationDefaultBuildItem(presentKey, "true"));
        }
    }

    @BuildStep
    void registerHibernateAdvisor(
            LaunchModeBuildItem launchMode,
            Capabilities capabilities,
            BuildProducer<AdditionalBeanBuildItem> additionalBeans,
            BuildProducer<ExcludedTypeBuildItem> excludedTypes,
            BuildProducer<RunTimeConfigurationDefaultBuildItem> runtimeDefaults) {
        boolean present =
                launchMode.getLaunchMode() != LaunchMode.NORMAL && capabilities.isPresent(Capability.HIBERNATE_ORM);
        registerCapabilityGatedBeans(
                present,
                additionalBeans,
                excludedTypes,
                runtimeDefaults,
                QuarkusPanelAvailability.HIBERNATE_PRESENT_KEY,
                HIBERNATE_PRODUCER_CLASS);
    }

    @BuildStep
    void registerHibernatePanacheFacts(
            LaunchModeBuildItem launchMode,
            Capabilities capabilities,
            CurateOutcomeBuildItem curateOutcome,
            BuildProducer<RunTimeConfigurationDefaultBuildItem> runtimeDefaults) {
        if (launchMode.getLaunchMode() == LaunchMode.NORMAL || !capabilities.isPresent(Capability.HIBERNATE_ORM))
            return;
        runtimeDefaults.produce(new RunTimeConfigurationDefaultBuildItem(
                "bootui.internal.hibernate-panache-enhancement",
                Boolean.toString(
                        hasPanacheExtension(curateOutcome.getApplicationModel().getRuntimeDependencies()))));
    }

    static boolean hasPanacheExtension(Iterable<ResolvedDependency> dependencies) {
        for (ResolvedDependency dependency : dependencies) {
            if (dependency.isRuntimeExtensionArtifact()
                    && "io.quarkus".equals(dependency.getGroupId())
                    && Set.of("quarkus-hibernate-orm-panache", "quarkus-hibernate-orm-panache-kotlin")
                            .contains(dependency.getArtifactId())) return true;
        }
        return false;
    }

    @BuildStep
    void registerHibernateSqlTrace(
            LaunchModeBuildItem launchMode,
            Capabilities capabilities,
            BuildProducer<AdditionalBeanBuildItem> additionalBeans,
            BuildProducer<ExcludedTypeBuildItem> excludedTypes) {
        boolean present =
                launchMode.getLaunchMode() != LaunchMode.NORMAL && capabilities.isPresent(Capability.HIBERNATE_ORM);
        registerCapabilityGatedBeans(present, additionalBeans, excludedTypes, SQL_TRACE_INSPECTOR_CLASS);
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    void registerScheduledTasks(
            LaunchModeBuildItem launchMode,
            Capabilities capabilities,
            BeanArchiveIndexBuildItem beanArchiveIndex,
            ScheduledTasksRecorder recorder,
            BuildProducer<SyntheticBeanBuildItem> syntheticBeans,
            BuildProducer<RunTimeConfigurationDefaultBuildItem> runtimeDefaults) {
        boolean present =
                launchMode.getLaunchMode() != LaunchMode.NORMAL && capabilities.isPresent(Capability.SCHEDULER);
        if (!present) {
            return; // no scheduler (or production): the panel stays unavailable (key defaults to false)
        }
        List<RawScheduledTask> tasks = scanScheduledTasks(beanArchiveIndex.getIndex());
        syntheticBeans.produce(SyntheticBeanBuildItem.configure(QuarkusScheduledTasks.class)
                .scope(Singleton.class)
                .runtimeValue(recorder.create(tasks))
                .unremovable()
                .done());
        runtimeDefaults.produce(
                new RunTimeConfigurationDefaultBuildItem(QuarkusPanelAvailability.SCHEDULED_PRESENT_KEY, "true"));
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    void claimJavaAgent(
            LaunchModeBuildItem launchMode,
            ApplicationInfoBuildItem applicationInfo,
            ApplicationArchivesBuildItem archives,
            BootUiAgentRecorder recorder,
            BuildProducer<SyntheticBeanBuildItem> syntheticBeans) {
        if (launchMode.getLaunchMode() == LaunchMode.NORMAL) {
            return; // production: BootUI is dark and never claims the agent
        }
        Config config = ConfigProvider.getConfig();
        String application = applicationInfo.getName();
        String mode = agentMode(
                config.getOptionalValue("bootui.agent.mode", String.class).orElse("auto"), launchMode.getLaunchMode());
        boolean enabled =
                config.getOptionalValue("bootui.agent.enabled", Boolean.class).orElse(true);
        AgentSensorSettings defaults = AgentSensorSettings.defaults();
        IndexView rootIndex = archives.getRootArchive().getIndex();
        List<String> packages = agentPackages(
                rootIndex,
                config.getOptionalValues("bootui.agent.packages", String.class).orElse(List.of()));
        RuntimeValue<QuarkusAgentClaim> claim = enabled
                ? recorder.claim(
                        application,
                        mode,
                        packages,
                        listOrDefaults(config, "bootui.agent.sensors", defaults.sensors()),
                        listOrDefaults(config, "bootui.agent.executors.skip-tasks", defaults.skipTasks()),
                        listOrDefaults(config, "bootui.agent.executors.skip-threads", defaults.skipThreads()),
                        config.getOptionalValue("bootui.agent.executors.max-handoff", Duration.class)
                                .orElse(defaults.maxHandoff())
                                .toMillis(),
                        config.getOptionalValue("bootui.agent.ring-capacity", Integer.class)
                                .orElse(defaults.ringCapacity()),
                        agentBeanClasses(rootIndex, packages))
                : recorder.release(application, mode);
        recorder.requestValues(enabled
                && config.getOptionalValue("bootui.agent.security-sinks.request-values", Boolean.class)
                        .orElse(false));
        syntheticBeans.produce(SyntheticBeanBuildItem.configure(QuarkusAgentClaim.class)
                .scope(Singleton.class)
                .runtimeValue(claim)
                .unremovable()
                .done());
    }

    static List<String> listOrDefaults(Config config, String name, List<String> defaults) {
        Optional<List<String>> values = config.getOptionalValues(name, String.class);
        if (values.isPresent()) {
            return values.get();
        }
        ConfigValue raw = config.getConfigValue(name);
        return raw != null && raw.getRawValue() != null ? List.of() : defaults;
    }

    static String agentMode(String configured, LaunchMode launchMode) {
        String mode = configured == null ? "auto" : configured.trim().toLowerCase(Locale.ROOT);
        if (mode.equals(AgentClaim.DEV) || mode.equals(AgentClaim.TEST)) {
            return mode;
        }
        return launchMode == LaunchMode.TEST ? AgentClaim.TEST : AgentClaim.DEV;
    }

    static final List<DotName> AGENT_BEAN_ANNOTATIONS = List.of(
            DotName.createSimple("jakarta.enterprise.context.ApplicationScoped"),
            DotName.createSimple("jakarta.enterprise.context.RequestScoped"),
            DotName.createSimple("jakarta.enterprise.context.SessionScoped"),
            DotName.createSimple("jakarta.enterprise.context.Dependent"),
            DotName.createSimple("jakarta.inject.Singleton"),
            DotName.createSimple("io.quarkus.runtime.Startup"),
            DotName.createSimple("jakarta.ws.rs.Path"),
            DotName.createSimple("jakarta.ws.rs.ext.Provider"));

    static List<String> agentBeanClasses(IndexView index, List<String> packages) {
        Set<String> names = new TreeSet<>();
        for (DotName annotation : AGENT_BEAN_ANNOTATIONS) {
            for (AnnotationInstance instance : index.getAnnotations(annotation)) {
                if (instance.target() == null || instance.target().kind() != AnnotationTarget.Kind.CLASS) {
                    continue;
                }
                ClassInfo type = instance.target().asClass();
                String name = type.name().toString();
                if (!type.isInterface() && !type.isAnnotation() && inAgentPackages(name, packages)) {
                    names.add(name);
                }
            }
        }
        return List.copyOf(names);
    }

    private static boolean inAgentPackages(String className, List<String> packages) {
        for (String name : packages) {
            if (className.startsWith(name + ".")) {
                return true;
            }
        }
        return false;
    }

    static List<String> agentPackages(IndexView index, List<String> extra) {
        Set<String> packages = new TreeSet<>();
        for (ClassInfo type : index.getKnownClasses()) {
            String name = AgentPackages.packageOf(type.name().toString());
            if (name != null) {
                packages.add(name);
            }
        }
        List<String> claimed = new ArrayList<>(AgentPackages.reduce(packages));
        for (String name : extra == null ? List.<String>of() : extra) {
            if (name != null && !name.isBlank() && !claimed.contains(name.trim())) {
                claimed.add(name.trim());
            }
        }
        return claimed;
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    void registerMappings(
            LaunchModeBuildItem launchMode,
            BeanArchiveIndexBuildItem beanArchiveIndex,
            MappingsRecorder recorder,
            BuildProducer<SyntheticBeanBuildItem> syntheticBeans) {
        if (launchMode.getLaunchMode() == LaunchMode.NORMAL) {
            return; // production: the BootUI API is not wired, so the captured mappings would be unused
        }
        List<RawMapping> mappings = scanMappings(beanArchiveIndex.getIndex());
        syntheticBeans.produce(SyntheticBeanBuildItem.configure(QuarkusMappings.class)
                .scope(Singleton.class)
                .runtimeValue(recorder.create(mappings))
                .unremovable()
                .done());
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    void registerErrorContract(
            LaunchModeBuildItem launchMode,
            BeanArchiveIndexBuildItem beanArchiveIndex,
            ErrorContractRecorder recorder,
            BuildProducer<SyntheticBeanBuildItem> syntheticBeans) {
        if (launchMode.getLaunchMode() == LaunchMode.NORMAL) {
            return; // production: the BootUI API is not wired, so the captured declarations would be unused
        }
        List<RawErrorHandler> handlers = scanErrorHandlers(beanArchiveIndex.getIndex());
        syntheticBeans.produce(SyntheticBeanBuildItem.configure(QuarkusErrorContract.class)
                .scope(Singleton.class)
                .runtimeValue(recorder.create(handlers))
                .unremovable()
                .done());
    }

    @BuildStep
    void captureBeanDependencies(
            LaunchModeBuildItem launchMode,
            ValidationPhaseBuildItem validationPhase,
            BuildProducer<GeneratedResourceBuildItem> generatedResources) {
        if (launchMode.getLaunchMode() == LaunchMode.NORMAL) {
            return;
        }
        Map<String, Set<String>> graph = new TreeMap<>();
        for (BeanInfo bean : validationPhase.getContext().beans()) {
            String source = arcBeanName(bean);
            if (source == null) {
                continue;
            }
            for (InjectionPointInfo injectionPoint : bean.getAllInjectionPoints()) {
                BeanInfo resolved = injectionPoint.getResolvedBean();
                String dependency = resolved == null ? null : arcBeanName(resolved);
                if (dependency != null && !dependency.equals(source)) {
                    graph.computeIfAbsent(source, ignored -> new LinkedHashSet<>())
                            .add(dependency);
                }
            }
        }
        generatedResources.produce(new GeneratedResourceBuildItem(
                QuarkusBeanDependencies.RESOURCE_NAME, QuarkusBeanDependencies.encode(graph)));
    }

    private static String arcBeanName(BeanInfo bean) {
        if (bean.getName() != null && !bean.getName().isBlank()) {
            return bean.getName();
        }
        if (bean.getBeanClass() == null) {
            return null;
        }
        String className = bean.getBeanClass().toString();
        int separator = Math.max(className.lastIndexOf('.'), className.lastIndexOf('$'));
        String simpleName = className.substring(separator + 1);
        if (simpleName.isEmpty()) {
            return null;
        }
        return simpleName.substring(0, 1).toLowerCase(Locale.ROOT) + simpleName.substring(1);
    }

    private static List<RawMapping> scanMappings(IndexView index) {
        List<RawMapping> mappings = new ArrayList<>();
        for (AnnotationInstance pathAnnotation : index.getAnnotations(PATH)) {
            if (pathAnnotation.target() == null || pathAnnotation.target().kind() != AnnotationTarget.Kind.CLASS) {
                continue; // method-level @Path is handled per-method against its declaring class below
            }
            ClassInfo resource = pathAnnotation.target().asClass();
            String resourceClass = resource.name().toString();
            if (resourceClass.equals(BOOTUI_PACKAGE_PREFIX) || resourceClass.startsWith(BOOTUI_PACKAGE_PREFIX + ".")) {
                continue; // BootUI's own resources, identified by their package (mirrors the Spring filter)
            }
            String classPath = annotationString(pathAnnotation);
            String classProduces = mediaTypes(resource.declaredAnnotation(PRODUCES));
            String classConsumes = mediaTypes(resource.declaredAnnotation(CONSUMES));
            for (MethodInfo method : resource.methods()) {
                String httpMethod = httpMethodOf(method);
                AnnotationInstance methodPath = method.annotation(PATH);
                if (httpMethod == null && methodPath == null) {
                    continue; // not a resource method (helper / lifecycle callback)
                }
                String pattern = joinPaths(classPath, methodPath == null ? null : annotationString(methodPath));
                if (pattern.equals(BOOTUI_PATH_PREFIX) || pattern.startsWith(BOOTUI_PATH_PREFIX + "/")) {
                    continue; // belt-and-suspenders: BootUI's own routes by path
                }
                String produces = firstNonNull(mediaTypes(method.annotation(PRODUCES)), classProduces);
                String consumes = firstNonNull(mediaTypes(method.annotation(CONSUMES)), classConsumes);
                String handler = resourceClass + "#" + method.name();
                mappings.add(
                        new RawMapping(httpMethod == null ? "ANY" : httpMethod, pattern, handler, produces, consumes));
            }
        }
        return mappings;
    }

    static List<RawErrorHandler> scanErrorHandlers(IndexView index) {
        List<RawErrorHandler> handlers = new ArrayList<>();
        for (ClassInfo mapper : index.getAllKnownImplementations(EXCEPTION_MAPPER)) {
            if (mapper.isInterface() || Modifier.isAbstract(mapper.flags()) || isBootUiClass(mapper)) {
                continue;
            }
            if (mapper.declaredAnnotation(PROVIDER) == null) {
                continue; // not registered as a provider, so it never participates in exception resolution
            }
            MethodInfo toResponse = exceptionMapperMethod(index, mapper);
            List<String> exceptionTypes = exceptionMapperTypes(index, mapper, toResponse);
            if (exceptionTypes.isEmpty()) {
                continue; // an ExceptionMapper whose handled type cannot be read is not reportable
            }
            Type returnType = declaredReturnType(toResponse);
            List<String> methodProduces =
                    toResponse == null ? List.of() : mediaTypeList(toResponse.annotation(PRODUCES));
            handlers.add(new RawErrorHandler(
                    ErrorHandlerDescriptor.JAKARTA_REST_EXCEPTION_MAPPER,
                    mapper.name().toString(),
                    toResponse == null ? "toResponse" : toResponse.name(),
                    exceptionTypes,
                    ErrorHandlerDescriptor.SCOPE_GLOBAL,
                    null,
                    priorityOf(mapper.declaredAnnotation(PRIORITY)),
                    null,
                    isDynamicStatus(returnType),
                    returnType == null ? null : returnType.name().toString(),
                    bodyTypeOf(returnType),
                    methodProduces.isEmpty() ? mediaTypeList(mapper.declaredAnnotation(PRODUCES)) : methodProduces));
        }
        for (AnnotationInstance annotation : index.getAnnotations(SERVER_EXCEPTION_MAPPER)) {
            if (annotation.target() == null || annotation.target().kind() != AnnotationTarget.Kind.METHOD) {
                continue;
            }
            MethodInfo method = annotation.target().asMethod();
            ClassInfo declaringClass = method.declaringClass();
            if (isBootUiClass(declaringClass)) {
                continue;
            }
            List<String> exceptionTypes = serverExceptionMapperTypes(index, annotation, method);
            if (exceptionTypes.isEmpty()) {
                continue; // no declared exception type is readable, so there is nothing honest to report
            }
            boolean resourceLocal = isJaxRsResourceClass(declaringClass);
            Type returnType = declaredReturnType(method);
            AnnotationInstance status = method.annotation(RESPONSE_STATUS);
            List<String> produces = mediaTypeList(method.annotation(PRODUCES));
            handlers.add(new RawErrorHandler(
                    ErrorHandlerDescriptor.QUARKUS_SERVER_EXCEPTION_MAPPER,
                    declaringClass.name().toString(),
                    method.name(),
                    exceptionTypes,
                    resourceLocal ? ErrorHandlerDescriptor.SCOPE_CONTROLLER : ErrorHandlerDescriptor.SCOPE_GLOBAL,
                    resourceLocal ? declaringClass.name().toString() : null,
                    declaredPriority(annotation),
                    statusOf(status),
                    isDynamicStatus(returnType),
                    returnType.name().toString(),
                    bodyTypeOf(returnType),
                    produces.isEmpty() ? mediaTypeList(declaringClass.declaredAnnotation(PRODUCES)) : produces));
        }
        return handlers;
    }

    private static boolean isJaxRsResourceClass(ClassInfo classInfo) {
        if (classInfo.declaredAnnotation(PATH) != null) {
            return true;
        }
        for (MethodInfo method : classInfo.methods()) {
            if (method.hasAnnotation(PATH) || httpMethodOf(method) != null) {
                return true;
            }
        }
        return false;
    }

    private static Type declaredReturnType(MethodInfo method) {
        if (method == null) {
            return null;
        }
        int count = method.parametersCount();
        if (count > 0
                && "kotlin.coroutines.Continuation"
                        .equals(method.parameterType(count - 1).name().toString())) {
            Type continuation = method.parameterType(count - 1);
            if (continuation.kind() == Type.Kind.PARAMETERIZED_TYPE) {
                List<Type> args = continuation.asParameterizedType().arguments();
                if (!args.isEmpty()) {
                    Type arg = args.get(0);
                    if (arg.kind() == Type.Kind.WILDCARD_TYPE) {
                        Type superBound = arg.asWildcardType().superBound();
                        return superBound != null
                                ? superBound
                                : arg.asWildcardType().extendsBound();
                    }
                    return arg;
                }
            }
        }
        return method.returnType();
    }

    private static boolean isBootUiClass(ClassInfo classInfo) {
        String name = classInfo.name().toString();
        return name.equals(BOOTUI_PACKAGE_PREFIX) || name.startsWith(BOOTUI_PACKAGE_PREFIX + ".");
    }

    private static MethodInfo exceptionMapperMethod(IndexView index, ClassInfo mapper) {
        MethodInfo fallback = null;
        for (ClassInfo current = mapper; current != null; current = superClass(index, current)) {
            for (MethodInfo method : current.methods()) {
                if (!"toResponse".equals(method.name()) || method.parametersCount() != 1) {
                    continue;
                }
                if (isBridgeMethod(method)) {
                    fallback = fallback == null ? method : fallback;
                    continue;
                }
                return method;
            }
        }
        return fallback;
    }

    private static ClassInfo superClass(IndexView index, ClassInfo classInfo) {
        DotName superName = classInfo.superName();
        if (superName == null || DotName.OBJECT_NAME.equals(superName)) {
            return null;
        }
        return index.getClassByName(superName);
    }

    private static boolean isBridgeMethod(MethodInfo method) {
        return (method.flags() & (SYNTHETIC | BRIDGE)) != 0;
    }

    private static List<String> exceptionMapperTypes(IndexView index, ClassInfo mapper, MethodInfo toResponse) {
        for (ClassInfo current = mapper; current != null; current = superClass(index, current)) {
            for (Type interfaceType : current.interfaceTypes()) {
                if (!interfaceType.name().equals(EXCEPTION_MAPPER)
                        || interfaceType.kind() != Type.Kind.PARAMETERIZED_TYPE) {
                    continue;
                }
                List<Type> arguments = interfaceType.asParameterizedType().arguments();
                if (!arguments.isEmpty() && arguments.get(0).kind() == Type.Kind.CLASS) {
                    return List.of(arguments.get(0).name().toString());
                }
            }
        }
        if (toResponse != null && !isBridgeMethod(toResponse)) {
            return List.of(toResponse.parameterType(0).name().toString());
        }
        return List.of();
    }

    private static List<String> serverExceptionMapperTypes(
            IndexView index, AnnotationInstance annotation, MethodInfo method) {
        AnnotationValue declared = annotation.value();
        if (declared != null) {
            List<String> types = Arrays.stream(declared.asClassArray())
                    .map(type -> type.name().toString())
                    .distinct()
                    .toList();
            if (!types.isEmpty()) {
                return types;
            }
        }
        List<String> fromParameters = new ArrayList<>();
        for (Type parameter : method.parameterTypes()) {
            if (isThrowable(index, parameter.name())
                    && !fromParameters.contains(parameter.name().toString())) {
                fromParameters.add(parameter.name().toString());
            }
        }
        return List.copyOf(fromParameters);
    }

    private static boolean isThrowable(IndexView index, DotName typeName) {
        DotName current = typeName;
        for (int depth = 0; depth < 20 && current != null; depth++) {
            String name = current.toString();
            if (name.equals("java.lang.Throwable")) {
                return true;
            }
            ClassInfo classInfo = index.getClassByName(current);
            if (classInfo == null) {
                return name.endsWith("Exception") || name.endsWith("Error") || name.endsWith("Throwable");
            }
            current = classInfo.superName();
        }
        return false;
    }

    private static Integer priorityOf(AnnotationInstance priority) {
        if (priority == null || priority.value() == null) {
            return null;
        }
        return priority.value().asInt();
    }

    private static Integer declaredPriority(AnnotationInstance serverExceptionMapper) {
        AnnotationValue priority = serverExceptionMapper.value("priority");
        return priority == null ? null : priority.asInt();
    }

    private static String statusOf(AnnotationInstance responseStatus) {
        if (responseStatus == null || responseStatus.value() == null) {
            return null;
        }
        return String.valueOf(responseStatus.value().asInt());
    }

    private static boolean isDynamicStatus(Type returnType) {
        Type payload = unwrapAsync(returnType);
        return payload != null
                && (payload.name().equals(JAKARTA_RESPONSE) || payload.name().equals(REST_RESPONSE));
    }

    private static String bodyTypeOf(Type returnType) {
        Type payload = unwrapAsync(returnType);
        if (payload == null
                || payload.kind() == Type.Kind.VOID
                || payload.name().equals(JAKARTA_RESPONSE)) {
            return null;
        }
        if (payload.name().equals(REST_RESPONSE)) {
            if (payload.kind() != Type.Kind.PARAMETERIZED_TYPE) {
                return null;
            }
            List<Type> arguments = payload.asParameterizedType().arguments();
            if (arguments.isEmpty()) {
                return null;
            }
            Type arg = arguments.get(0);
            if (arg.kind() == Type.Kind.WILDCARD_TYPE
                    && arg.asWildcardType().superBound() == null
                    && !DotName.OBJECT_NAME.equals(
                            arg.asWildcardType().extendsBound().name())) {
                return arg.asWildcardType().extendsBound().name().toString();
            }
            return arg.name().toString();
        }
        return payload.name().toString();
    }

    private static Type unwrapAsync(Type type) {
        Type current = type;
        for (int depth = 0; depth < 3; depth++) {
            if (current == null || current.kind() != Type.Kind.PARAMETERIZED_TYPE) {
                return current;
            }
            ParameterizedType parameterized = current.asParameterizedType();
            if (!ERROR_CONTRACT_ASYNC_WRAPPERS.contains(parameterized.name())
                    || parameterized.arguments().isEmpty()) {
                return current;
            }
            current = parameterized.arguments().get(0);
        }
        return current;
    }

    private static List<String> mediaTypeList(AnnotationInstance annotation) {
        if (annotation == null || annotation.value() == null) {
            return List.of();
        }
        return Arrays.stream(annotation.value().asStringArray())
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .sorted()
                .toList();
    }

    private static String httpMethodOf(MethodInfo method) {
        for (String httpMethod : JAXRS_HTTP_METHODS) {
            if (method.hasAnnotation(DotName.createSimple(httpMethod))) {
                return httpMethod.substring(httpMethod.lastIndexOf('.') + 1);
            }
        }
        return null;
    }

    private static String joinPaths(String classPath, String methodPath) {
        String combined = (classPath == null ? "" : classPath) + "/" + (methodPath == null ? "" : methodPath);
        combined = combined.replaceAll("/{2,}", "/");
        if (combined.length() > 1 && combined.endsWith("/")) {
            combined = combined.substring(0, combined.length() - 1);
        }
        if (!combined.startsWith("/")) {
            combined = "/" + combined;
        }
        return combined;
    }

    private static String mediaTypes(AnnotationInstance annotation) {
        if (annotation == null) {
            return null;
        }
        String[] values = stringArray(annotation.value());
        if (values.length == 0) {
            return null;
        }
        return Arrays.stream(values).sorted().collect(Collectors.joining(", "));
    }

    private static String annotationString(AnnotationInstance annotation) {
        AnnotationValue value = annotation.value();
        return value == null ? "" : value.asString();
    }

    private static String[] stringArray(AnnotationValue value) {
        if (value == null) {
            return new String[0];
        }
        return value.kind() == AnnotationValue.Kind.ARRAY ? value.asStringArray() : new String[] {value.asString()};
    }

    private static String firstNonNull(String first, String second) {
        return first != null ? first : second;
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    void captureDevServices(
            LaunchModeBuildItem launchMode,
            List<DevServicesResultBuildItem> devServices,
            DevServicesRecorder recorder) {
        if (launchMode.getLaunchMode() == LaunchMode.NORMAL) {
            return; // Dev Services never start in production: the panel stays unavailable
        }
        recorder.capture(devServices.stream()
                .map(item -> new RawDevService(
                        item.getName(), item.getDescription(), item.getContainerId(), item.getConfig()))
                .toList());
    }

    private static final String CIRCUIT_BREAKER_STATES_CLASS =
            "io.github.jdubois.bootui.quarkus.faulttolerance.SmallRyeCircuitBreakerStates";

    private static final DotName FT_CIRCUIT_BREAKER =
            DotName.createSimple("org.eclipse.microprofile.faulttolerance.CircuitBreaker");

    private static final DotName FT_RETRY = DotName.createSimple("org.eclipse.microprofile.faulttolerance.Retry");

    private static final DotName FT_TIMEOUT = DotName.createSimple("org.eclipse.microprofile.faulttolerance.Timeout");

    private static final DotName FT_BULKHEAD = DotName.createSimple("org.eclipse.microprofile.faulttolerance.Bulkhead");

    private static final DotName FT_FALLBACK = DotName.createSimple("org.eclipse.microprofile.faulttolerance.Fallback");

    private static final DotName FT_RATE_LIMIT = DotName.createSimple("io.smallrye.faulttolerance.api.RateLimit");

    private static final DotName FT_CIRCUIT_BREAKER_NAME =
            DotName.createSimple("io.smallrye.faulttolerance.api.CircuitBreakerName");

    @BuildStep
    void registerFaultToleranceBeans(
            LaunchModeBuildItem launchMode,
            Capabilities capabilities,
            BuildProducer<AdditionalBeanBuildItem> additionalBeans,
            BuildProducer<ExcludedTypeBuildItem> excludedTypes,
            BuildProducer<RunTimeConfigurationDefaultBuildItem> runtimeDefaults) {
        registerCapabilityGatedBeans(
                faultTolerancePresent(launchMode, capabilities),
                additionalBeans,
                excludedTypes,
                runtimeDefaults,
                QuarkusPanelAvailability.FAULT_TOLERANCE_PRESENT_KEY,
                CIRCUIT_BREAKER_STATES_CLASS);
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    void registerFaultTolerancePolicies(
            LaunchModeBuildItem launchMode,
            Capabilities capabilities,
            BeanArchiveIndexBuildItem beanArchiveIndex,
            FaultTolerancePoliciesRecorder recorder,
            BuildProducer<SyntheticBeanBuildItem> syntheticBeans) {
        if (!faultTolerancePresent(launchMode, capabilities)) {
            return;
        }
        List<RawFaultTolerancePolicy> policies = scanFaultTolerancePolicies(beanArchiveIndex.getIndex());
        syntheticBeans.produce(SyntheticBeanBuildItem.configure(QuarkusFaultTolerancePolicies.class)
                .scope(Singleton.class)
                .runtimeValue(recorder.create(policies))
                .unremovable()
                .done());
    }

    private static boolean faultTolerancePresent(LaunchModeBuildItem launchMode, Capabilities capabilities) {
        return launchMode.getLaunchMode() != LaunchMode.NORMAL
                && capabilities.isPresent(Capability.SMALLRYE_FAULT_TOLERANCE);
    }

    static List<RawFaultTolerancePolicy> scanFaultTolerancePolicies(IndexView index) {
        List<RawFaultTolerancePolicy> policies = new ArrayList<>();
        collectFaultTolerancePolicies(index, FT_CIRCUIT_BREAKER, "CIRCUIT_BREAKER", policies);
        collectFaultTolerancePolicies(index, FT_RETRY, "RETRY", policies);
        collectFaultTolerancePolicies(index, FT_RATE_LIMIT, "RATE_LIMITER", policies);
        collectFaultTolerancePolicies(index, FT_BULKHEAD, "BULKHEAD", policies);
        collectFaultTolerancePolicies(index, FT_TIMEOUT, "TIME_LIMITER", policies);
        collectFaultTolerancePolicies(index, FT_FALLBACK, "FALLBACK", policies);
        return policies;
    }

    private static void collectFaultTolerancePolicies(
            IndexView index, DotName annotationName, String type, List<RawFaultTolerancePolicy> policies) {
        for (AnnotationInstance annotation : index.getAnnotations(annotationName)) {
            AnnotationTarget target = annotation.target();
            if (target == null) {
                continue;
            }
            if (target.kind() == AnnotationTarget.Kind.METHOD) {
                MethodInfo method = target.asMethod();
                policies.add(toRawFaultTolerancePolicy(
                        annotation,
                        type,
                        method.declaringClass(),
                        method.name(),
                        circuitBreakerNameOf(method.annotation(FT_CIRCUIT_BREAKER_NAME))));
            } else if (target.kind() == AnnotationTarget.Kind.CLASS) {
                ClassInfo declaringClass = target.asClass();
                policies.add(toRawFaultTolerancePolicy(
                        annotation,
                        type,
                        declaringClass,
                        "",
                        circuitBreakerNameOf(declaringClass.declaredAnnotation(FT_CIRCUIT_BREAKER_NAME))));
            }
        }
    }

    private static String circuitBreakerNameOf(AnnotationInstance circuitBreakerName) {
        if (circuitBreakerName == null || circuitBreakerName.value() == null) {
            return "";
        }
        return circuitBreakerName.value().asString();
    }

    private static RawFaultTolerancePolicy toRawFaultTolerancePolicy(
            AnnotationInstance annotation,
            String type,
            ClassInfo declaringClass,
            String methodName,
            String circuitBreakerName) {
        String simpleAnnotation = annotation.name().withoutPackagePrefix();
        String className = declaringClass.name().toString();
        String simpleClass = declaringClass.simpleName();
        String label = methodName.isEmpty() ? simpleClass : simpleClass + "#" + methodName;
        String name = "CIRCUIT_BREAKER".equals(type) && !circuitBreakerName.isEmpty() ? circuitBreakerName : label;
        return new RawFaultTolerancePolicy(
                name,
                type,
                simpleAnnotation,
                className,
                methodName,
                circuitBreakerName,
                faultToleranceSettings(annotation, simpleAnnotation));
    }

    private static List<RawFaultToleranceSetting> faultToleranceSettings(
            AnnotationInstance annotation, String simpleName) {
        List<RawFaultToleranceSetting> settings = new ArrayList<>();
        switch (simpleName) {
            case "CircuitBreaker" -> {
                addFaultToleranceSetting(settings, annotation, "requestVolumeThreshold", "20");
                addFaultToleranceSetting(settings, annotation, "failureRatio", "0.5");
                addFaultToleranceSetting(settings, annotation, "successThreshold", "1");
                addFaultToleranceSetting(settings, annotation, "delay", "5000");
                addFaultToleranceSetting(settings, annotation, "delayUnit", "MILLIS");
            }
            case "Retry" -> {
                addFaultToleranceSetting(settings, annotation, "maxRetries", "3");
                addFaultToleranceSetting(settings, annotation, "delay", "0");
                addFaultToleranceSetting(settings, annotation, "delayUnit", "MILLIS");
                addFaultToleranceSetting(settings, annotation, "maxDuration", "180000");
                addFaultToleranceSetting(settings, annotation, "durationUnit", "MILLIS");
                addFaultToleranceSetting(settings, annotation, "jitter", "200");
                addFaultToleranceSetting(settings, annotation, "jitterDelayUnit", "MILLIS");
            }
            case "RateLimit" -> {
                addFaultToleranceSetting(settings, annotation, "value", "100");
                addFaultToleranceSetting(settings, annotation, "window", "1");
                addFaultToleranceSetting(settings, annotation, "windowUnit", "SECONDS");
                addFaultToleranceSetting(settings, annotation, "minSpacing", "0");
                addFaultToleranceSetting(settings, annotation, "minSpacingUnit", "SECONDS");
                addFaultToleranceSetting(settings, annotation, "type", "FIXED");
            }
            case "Bulkhead" -> {
                addFaultToleranceSetting(settings, annotation, "value", "10");
                addFaultToleranceSetting(settings, annotation, "waitingTaskQueue", "10");
            }
            case "Timeout" -> {
                addFaultToleranceSetting(settings, annotation, "value", "1000");
                addFaultToleranceSetting(settings, annotation, "unit", "MILLIS");
            }
            case "Fallback" -> addFaultToleranceSetting(settings, annotation, "fallbackMethod", "");
            default -> {}
        }
        return settings;
    }

    private static void addFaultToleranceSetting(
            List<RawFaultToleranceSetting> settings,
            AnnotationInstance annotation,
            String member,
            String specificationDefault) {
        AnnotationValue value = annotation.value(member);
        if (value == null) {
            if (!specificationDefault.isEmpty()) {
                settings.add(new RawFaultToleranceSetting(member, specificationDefault, "DEFAULT"));
            }
            return;
        }
        String rendered = renderAnnotationValue(value);
        if (!rendered.isEmpty()) {
            settings.add(new RawFaultToleranceSetting(member, rendered, "CONFIGURED"));
        }
    }

    private static String renderAnnotationValue(AnnotationValue value) {
        return switch (value.kind()) {
            case ENUM -> value.asEnum();
            case STRING -> value.asString();
            case BOOLEAN -> Boolean.toString(value.asBoolean());
            case BYTE -> Byte.toString(value.asByte());
            case SHORT -> Short.toString(value.asShort());
            case INTEGER -> Integer.toString(value.asInt());
            case LONG -> Long.toString(value.asLong());
            case FLOAT -> Float.toString(value.asFloat());
            case DOUBLE -> Double.toString(value.asDouble());
            case CHARACTER -> Character.toString(value.asChar());
            default -> "";
        };
    }

    private static final DotName SCHEDULED_ANNOTATION = DotName.createSimple("io.quarkus.scheduler.Scheduled");

    private static final DotName SCHEDULES_ANNOTATION =
            DotName.createSimple("io.quarkus.scheduler.Scheduled$Schedules");

    @BuildStep
    void registerWebSocketBeans(
            LaunchModeBuildItem launchMode,
            BuildProducer<AdditionalBeanBuildItem> additionalBeans,
            BuildProducer<ExcludedTypeBuildItem> excludedTypes) {
        registerCapabilityGatedBeans(
                webSocketsPresent(launchMode),
                additionalBeans,
                excludedTypes,
                WEBSOCKET_SESSION_PROVIDER_CLASS,
                WEBSOCKET_CONNECTION_CAPTURE_CLASS);
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    void registerWebSocketEndpoints(
            LaunchModeBuildItem launchMode,
            BeanArchiveIndexBuildItem beanArchiveIndex,
            WebSocketsRecorder recorder,
            BuildProducer<SyntheticBeanBuildItem> syntheticBeans,
            BuildProducer<RunTimeConfigurationDefaultBuildItem> runtimeDefaults) {
        if (!webSocketsPresent(launchMode)) {
            return; // no quarkus-websockets-next (or production): the panel stays unavailable
        }
        List<RawWebSocketEndpoint> endpoints = scanWebSocketEndpoints(beanArchiveIndex.getIndex());
        syntheticBeans.produce(SyntheticBeanBuildItem.configure(QuarkusWebSockets.class)
                .scope(Singleton.class)
                .runtimeValue(recorder.create(endpoints))
                .unremovable()
                .done());
        if (!endpoints.isEmpty()) {
            runtimeDefaults.produce(
                    new RunTimeConfigurationDefaultBuildItem(QuarkusPanelAvailability.WEBSOCKETS_PRESENT_KEY, "true"));
        }
    }

    @BuildStep
    void bindWebSocketMessageInterceptor(
            LaunchModeBuildItem launchMode,
            BuildProducer<AnnotationsTransformerBuildItem> transformers,
            BuildProducer<AdditionalBeanBuildItem> additionalBeans,
            BuildProducer<ExcludedTypeBuildItem> excludedTypes) {
        boolean present = webSocketsPresent(launchMode);
        registerCapabilityGatedBeans(present, additionalBeans, excludedTypes, WEBSOCKET_MESSAGE_INTERCEPTOR_CLASS);
        if (!present) {
            return;
        }
        transformers.produce(new AnnotationsTransformerBuildItem(AnnotationTransformation.forMethods()
                .when(context -> {
                    MethodInfo method = context.declaration().asMethod();
                    return method.declaringClass().hasDeclaredAnnotation(WEBSOCKET_ANNOTATION)
                            && WEBSOCKET_MESSAGE_ANNOTATIONS.stream().anyMatch(method::hasDeclaredAnnotation);
                })
                .transform(context -> context.add(AnnotationInstance.builder(WEBSOCKET_MESSAGE_BINDING)
                        .buildWithTarget(context.declaration())))));
    }

    @BuildStep
    void registerOrmSessionListener(
            LaunchModeBuildItem launchMode,
            Capabilities capabilities,
            BuildProducer<RunTimeConfigurationDefaultBuildItem> runtimeDefaults) {
        if (launchMode.getLaunchMode() == LaunchMode.NORMAL || !capabilities.isPresent(Capability.HIBERNATE_ORM)) {
            return;
        }
        runtimeDefaults.produce(new RunTimeConfigurationDefaultBuildItem(
                "quarkus.hibernate-orm.unsupported-properties.\"hibernate.session.events.auto\"",
                ORM_SESSION_LISTENER_CLASS));
        for (String unit : namedPersistenceUnits(
                org.eclipse.microprofile.config.ConfigProvider.getConfig().getPropertyNames())) {
            runtimeDefaults.produce(new RunTimeConfigurationDefaultBuildItem(
                    "quarkus.hibernate-orm.\"" + unit + "\".unsupported-properties.\"hibernate.session.events.auto\"",
                    ORM_SESSION_LISTENER_CLASS));
        }
    }

    static java.util.Set<String> namedPersistenceUnits(Iterable<String> propertyNames) {
        java.util.regex.Pattern named = java.util.regex.Pattern.compile(
                "^quarkus\\.hibernate-orm\\.(?:\"([^\"]+)\"|([^.\"]+))\\.(?:datasource|packages)$");
        java.util.Set<String> units = new TreeSet<>();
        for (String name : propertyNames) {
            java.util.regex.Matcher matcher = named.matcher(name);
            if (matcher.matches()) {
                String unit = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
                if (!"<default>".equals(unit)) {
                    units.add(unit);
                }
            }
        }
        return units;
    }

    private static boolean webSocketsPresent(LaunchModeBuildItem launchMode) {
        return launchMode.getLaunchMode() != LaunchMode.NORMAL
                && QuarkusClassLoader.isClassPresentAtRuntime(WEBSOCKET_CONNECTION_CLASS);
    }

    private static List<RawWebSocketEndpoint> scanWebSocketEndpoints(IndexView index) {
        List<RawWebSocketEndpoint> endpoints = new ArrayList<>();
        for (AnnotationInstance annotation : index.getAnnotations(WEBSOCKET_ANNOTATION)) {
            if (annotation.target() == null || annotation.target().kind() != AnnotationTarget.Kind.CLASS) {
                continue;
            }
            ClassInfo endpointClass = annotation.target().asClass();
            String handlerClass = endpointClass.name().toString();
            AnnotationValue endpointId = annotation.value("endpointId");
            AnnotationValue path = annotation.value("path");
            endpoints.add(new RawWebSocketEndpoint(
                    endpointId == null || endpointId.asString().isBlank() ? handlerClass : endpointId.asString(),
                    path == null ? null : path.asString(),
                    handlerClass,
                    enumMemberOrNull(annotation, "inboundProcessingMode"),
                    scanWebSocketCallbacks(endpointClass)));
        }
        endpoints.sort(Comparator.comparing(
                endpoint -> endpoint.path() == null ? "" : endpoint.path(), Comparator.naturalOrder()));
        return endpoints;
    }

    private static List<RawWebSocketCallback> scanWebSocketCallbacks(ClassInfo endpointClass) {
        List<RawWebSocketCallback> callbacks = new ArrayList<>();
        for (DotName callbackAnnotation : WEBSOCKET_CALLBACK_ANNOTATIONS) {
            for (MethodInfo method : endpointClass.methods()) {
                if (!method.hasAnnotation(callbackAnnotation)) {
                    continue;
                }
                callbacks.add(new RawWebSocketCallback(
                        callbackType(callbackAnnotation),
                        endpointClass.name().toString(),
                        method.name(),
                        method.parametersCount() == 0
                                ? null
                                : method.parameterType(0).name().toString()));
            }
        }
        return callbacks;
    }

    private static String callbackType(DotName annotation) {
        String name = annotation.toString();
        String simpleName = name.substring(name.lastIndexOf('.') + 1);
        StringBuilder rendered = new StringBuilder();
        for (int index = 0; index < simpleName.length(); index++) {
            char character = simpleName.charAt(index);
            if (index > 0 && Character.isUpperCase(character)) {
                rendered.append('_');
            }
            rendered.append(Character.toUpperCase(character));
        }
        return rendered.toString();
    }

    private static String enumMemberOrNull(AnnotationInstance annotation, String name) {
        AnnotationValue value = annotation.value(name);
        return value == null ? null : value.asEnum();
    }

    private static List<RawScheduledTask> scanScheduledTasks(IndexView index) {
        List<RawScheduledTask> tasks = new ArrayList<>();
        for (AnnotationInstance annotation : index.getAnnotations(SCHEDULED_ANNOTATION)) {
            if (annotation.target() != null && annotation.target().kind() == AnnotationTarget.Kind.METHOD) {
                tasks.add(toRawScheduledTask(annotation.target().asMethod(), annotation));
            }
        }
        for (AnnotationInstance container : index.getAnnotations(SCHEDULES_ANNOTATION)) {
            if (container.target() == null || container.target().kind() != AnnotationTarget.Kind.METHOD) {
                continue;
            }
            MethodInfo method = container.target().asMethod();
            AnnotationValue nested = container.value();
            if (nested != null) {
                for (AnnotationInstance scheduled : nested.asNestedArray()) {
                    tasks.add(toRawScheduledTask(method, scheduled));
                }
            }
        }
        return tasks;
    }

    private static RawScheduledTask toRawScheduledTask(MethodInfo method, AnnotationInstance annotation) {
        String methodDescription = method.declaringClass().name().toString() + "#" + method.name();
        return new RawScheduledTask(
                methodDescription,
                stringMember(annotation, "cron"),
                stringMember(annotation, "every"),
                stringMember(annotation, "delayed"),
                longMember(annotation, "delay"),
                enumMember(annotation, "delayUnit", "MINUTES"));
    }

    private static String stringMember(AnnotationInstance annotation, String name) {
        AnnotationValue value = annotation.value(name);
        return value == null ? "" : value.asString();
    }

    private static long longMember(AnnotationInstance annotation, String name) {
        AnnotationValue value = annotation.value(name);
        return value == null ? 0L : value.asLong();
    }

    private static String enumMember(AnnotationInstance annotation, String name, String defaultValue) {
        AnnotationValue value = annotation.value(name);
        return value == null ? defaultValue : value.asEnum();
    }

    @BuildStep
    void registerCacheAdvisor(
            LaunchModeBuildItem launchMode,
            Capabilities capabilities,
            BuildProducer<AdditionalBeanBuildItem> additionalBeans,
            BuildProducer<ExcludedTypeBuildItem> excludedTypes,
            BuildProducer<RunTimeConfigurationDefaultBuildItem> runtimeDefaults) {
        boolean present = launchMode.getLaunchMode() != LaunchMode.NORMAL && capabilities.isPresent(Capability.CACHE);
        registerCapabilityGatedBeans(
                present,
                additionalBeans,
                excludedTypes,
                runtimeDefaults,
                QuarkusPanelAvailability.CACHE_PRESENT_KEY,
                CACHE_PRODUCER_CLASS);
    }

    @BuildStep
    void registerKafkaCapture(
            LaunchModeBuildItem launchMode,
            Capabilities capabilities,
            BuildProducer<AdditionalBeanBuildItem> additionalBeans,
            BuildProducer<ExcludedTypeBuildItem> excludedTypes,
            BuildProducer<RunTimeConfigurationDefaultBuildItem> runtimeDefaults) {
        boolean present = launchMode.getLaunchMode() != LaunchMode.NORMAL && capabilities.isPresent(Capability.KAFKA);
        registerCapabilityGatedBeans(
                present,
                additionalBeans,
                excludedTypes,
                runtimeDefaults,
                QuarkusPanelAvailability.KAFKA_PRESENT_KEY,
                KAFKA_PRODUCER_CAPTURE_CLASS,
                KAFKA_CONSUMER_CAPTURE_CLASS);
    }

    @BuildStep
    void registerRabbitCapture(
            LaunchModeBuildItem launchMode,
            BuildProducer<AdditionalBeanBuildItem> additionalBeans,
            BuildProducer<ExcludedTypeBuildItem> excludedTypes,
            BuildProducer<RunTimeConfigurationDefaultBuildItem> runtimeDefaults) {
        boolean present = launchMode.getLaunchMode() != LaunchMode.NORMAL
                && QuarkusClassLoader.isClassPresentAtRuntime(INCOMING_RABBITMQ_METADATA_CLASS);
        registerCapabilityGatedBeans(
                present,
                additionalBeans,
                excludedTypes,
                runtimeDefaults,
                QuarkusPanelAvailability.RABBIT_PRESENT_KEY,
                RABBIT_PRODUCER_CAPTURE_CLASS,
                RABBIT_CONSUMER_CAPTURE_CLASS);
    }

    @BuildStep
    void registerSecurityLogs(
            LaunchModeBuildItem launchMode,
            Capabilities capabilities,
            BuildProducer<AdditionalBeanBuildItem> additionalBeans,
            BuildProducer<ExcludedTypeBuildItem> excludedTypes,
            BuildProducer<RunTimeConfigurationDefaultBuildItem> runtimeDefaults) {
        boolean present =
                launchMode.getLaunchMode() != LaunchMode.NORMAL && capabilities.isPresent(Capability.SECURITY);
        registerCapabilityGatedBeans(
                present,
                additionalBeans,
                excludedTypes,
                runtimeDefaults,
                QuarkusPanelAvailability.SECURITY_LOGS_PRESENT_KEY,
                SECURITY_CAPTURE_CLASS);
    }

    @BuildStep
    void registerScheduledTaskRunCapture(
            LaunchModeBuildItem launchMode,
            Capabilities capabilities,
            BuildProducer<AdditionalBeanBuildItem> additionalBeans,
            BuildProducer<ExcludedTypeBuildItem> excludedTypes) {
        boolean present =
                launchMode.getLaunchMode() != LaunchMode.NORMAL && capabilities.isPresent(Capability.SCHEDULER);
        registerCapabilityGatedBeans(
                present,
                additionalBeans,
                excludedTypes,
                SCHEDULED_TASK_RUN_RECORDER_CLASS,
                SCHEDULED_EXECUTION_INTERCEPTOR_CLASS);
    }

    @BuildStep
    void bindObserverInterceptor(
            LaunchModeBuildItem launchMode,
            ApplicationIndexBuildItem applicationIndex,
            BuildProducer<AnnotationsTransformerBuildItem> transformers,
            BuildProducer<AdditionalBeanBuildItem> additionalBeans,
            BuildProducer<ExcludedTypeBuildItem> excludedTypes) {
        boolean present = launchMode.getLaunchMode() != LaunchMode.NORMAL;
        registerCapabilityGatedBeans(present, additionalBeans, excludedTypes, OBSERVER_INTERCEPTOR_CLASS);
        if (!present) {
            return;
        }
        java.util.Set<DotName> applicationClasses = new java.util.HashSet<>();
        applicationIndex.getIndex().getKnownClasses().forEach(type -> applicationClasses.add(type.name()));
        transformers.produce(new AnnotationsTransformerBuildItem(AnnotationTransformation.forMethods()
                .when(context -> {
                    org.jboss.jandex.MethodInfo method = context.declaration().asMethod();
                    return applicationClasses.contains(method.declaringClass().name())
                            && (method.hasAnnotation(OBSERVES) || method.hasAnnotation(OBSERVES_ASYNC));
                })
                .transform(context -> context.add(
                        AnnotationInstance.builder(OBSERVED_EVENT_BINDING).buildWithTarget(context.declaration())))));
    }

    @BuildStep
    void bindScheduledExecutionInterceptor(
            LaunchModeBuildItem launchMode,
            Capabilities capabilities,
            BuildProducer<AnnotationsTransformerBuildItem> transformers) {
        if (launchMode.getLaunchMode() == LaunchMode.NORMAL || !capabilities.isPresent(Capability.SCHEDULER)) {
            return;
        }
        transformers.produce(new AnnotationsTransformerBuildItem(AnnotationTransformation.forMethods()
                .whenAnyMatch(SCHEDULED_ANNOTATIONS)
                .transform(context -> context.add(AnnotationInstance.builder(SCHEDULED_EXECUTION_BINDING)
                        .buildWithTarget(context.declaration())))));
    }

    @BuildStep
    void registerEmail(
            LaunchModeBuildItem launchMode,
            BuildProducer<AdditionalBeanBuildItem> additionalBeans,
            BuildProducer<ExcludedTypeBuildItem> excludedTypes,
            BuildProducer<RunTimeConfigurationDefaultBuildItem> runtimeDefaults) {
        boolean present = launchMode.getLaunchMode() != LaunchMode.NORMAL
                && QuarkusClassLoader.isClassPresentAtRuntime(REACTIVE_MAILER_CLASS);
        registerCapabilityGatedBeans(
                present,
                additionalBeans,
                excludedTypes,
                runtimeDefaults,
                QuarkusPanelAvailability.EMAIL_PRESENT_KEY,
                EMAIL_CAPTURE_CLASS);
    }

    @BuildStep
    void registerRestClientTrace(
            LaunchModeBuildItem launchMode,
            Capabilities capabilities,
            BuildProducer<GeneratedResourceBuildItem> generatedResources,
            BuildProducer<ExcludedTypeBuildItem> excludedTypes,
            BuildProducer<RunTimeConfigurationDefaultBuildItem> runtimeDefaults) {
        boolean present = launchMode.getLaunchMode() != LaunchMode.NORMAL
                && capabilities.isPresent(Capability.REST_CLIENT_REACTIVE);
        runtimeDefaults.produce(new RunTimeConfigurationDefaultBuildItem(
                QuarkusPanelAvailability.REST_CLIENT_TRACE_PRESENT_KEY, String.valueOf(present)));
        if (!present) {
            excludedTypes.produce(new ExcludedTypeBuildItem(REST_CLIENT_TRACE_LISTENER_CLASS));
            return;
        }
        byte[] descriptor = (REST_CLIENT_TRACE_LISTENER_CLASS + "\n").getBytes(StandardCharsets.UTF_8);
        String path = "META-INF/services/" + REST_CLIENT_LISTENER_SERVICE;
        try {
            generatedResources.produce((GeneratedResourceBuildItem) GeneratedResourceBuildItem.class
                    .getMethod("allowingMetaInfServices", String.class, byte[].class)
                    .invoke(null, path, descriptor));
        } catch (NoSuchMethodException e) {
            generatedResources.produce(new GeneratedResourceBuildItem(path, descriptor));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot register REST client trace listener", e);
        }
    }

    @BuildStep
    void registerFlyway(
            LaunchModeBuildItem launchMode,
            Capabilities capabilities,
            BuildProducer<AdditionalBeanBuildItem> additionalBeans,
            BuildProducer<ExcludedTypeBuildItem> excludedTypes,
            BuildProducer<RunTimeConfigurationDefaultBuildItem> runtimeDefaults) {
        boolean present = launchMode.getLaunchMode() != LaunchMode.NORMAL && capabilities.isPresent(Capability.FLYWAY);
        registerCapabilityGatedBeans(
                present,
                additionalBeans,
                excludedTypes,
                runtimeDefaults,
                QuarkusPanelAvailability.FLYWAY_PRESENT_KEY,
                FLYWAY_PRODUCER_CLASS);
    }

    @BuildStep
    void registerLiquibase(
            LaunchModeBuildItem launchMode,
            Capabilities capabilities,
            BuildProducer<AdditionalBeanBuildItem> additionalBeans,
            BuildProducer<ExcludedTypeBuildItem> excludedTypes,
            BuildProducer<RunTimeConfigurationDefaultBuildItem> runtimeDefaults) {
        boolean present =
                launchMode.getLaunchMode() != LaunchMode.NORMAL && capabilities.isPresent(Capability.LIQUIBASE);
        registerCapabilityGatedBeans(
                present,
                additionalBeans,
                excludedTypes,
                runtimeDefaults,
                QuarkusPanelAvailability.LIQUIBASE_PRESENT_KEY,
                LIQUIBASE_PRODUCER_CLASS);
    }

    @BuildStep
    void registerConnectionPools(
            LaunchModeBuildItem launchMode,
            Capabilities capabilities,
            BuildProducer<AdditionalBeanBuildItem> additionalBeans,
            BuildProducer<ExcludedTypeBuildItem> excludedTypes,
            BuildProducer<RunTimeConfigurationDefaultBuildItem> runtimeDefaults) {
        boolean present = launchMode.getLaunchMode() != LaunchMode.NORMAL && capabilities.isPresent(Capability.AGROAL);
        registerCapabilityGatedBeans(
                present,
                additionalBeans,
                excludedTypes,
                runtimeDefaults,
                QuarkusPanelAvailability.CONNECTION_POOLS_PRESENT_KEY,
                AGROAL_PRODUCER_CLASS,
                SQL_TRACE_PRODUCER_CLASS);
    }

    @BuildStep
    void registerSqlTraceProxies(
            LaunchModeBuildItem launchMode,
            Capabilities capabilities,
            BuildProducer<NativeImageProxyDefinitionBuildItem> proxies) {
        if (launchMode.getLaunchMode() == LaunchMode.NORMAL || !capabilities.isPresent(Capability.AGROAL)) {
            return;
        }
        proxies.produce(new NativeImageProxyDefinitionBuildItem(
                "javax.sql.DataSource",
                "java.lang.AutoCloseable",
                "io.github.jdubois.bootui.engine.sqltrace.SqlTracedDataSource",
                "io.agroal.api.AgroalDataSource"));
        proxies.produce(new NativeImageProxyDefinitionBuildItem("java.sql.Connection"));
        proxies.produce(new NativeImageProxyDefinitionBuildItem("java.sql.Statement"));
        proxies.produce(new NativeImageProxyDefinitionBuildItem("java.sql.PreparedStatement"));
        proxies.produce(new NativeImageProxyDefinitionBuildItem("java.sql.CallableStatement"));
        proxies.produce(new NativeImageProxyDefinitionBuildItem("java.sql.ResultSet"));
    }
}
