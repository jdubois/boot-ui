package io.github.jdubois.bootui.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.github.jdubois.bootui.autoconfigure.architecture.ArchitectureController;
import io.github.jdubois.bootui.autoconfigure.crac.CracController;
import io.github.jdubois.bootui.autoconfigure.graalvm.GraalVmController;
import io.github.jdubois.bootui.autoconfigure.memory.MemoryController;
import io.github.jdubois.bootui.autoconfigure.pentesting.PentestingController;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveBootUiExceptionHandler;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveBootUiIndexController;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveBootUiStaticResourceConfigurer;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveClaudeCodeController;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveCopilotController;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveExceptionsController;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveLocalhostOnlyFilter;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveLogTailController;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactivePanelAccessFilter;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveSecurityLogsController;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveSqlTraceController;
import io.github.jdubois.bootui.autoconfigure.restapi.RestApiController;
import io.github.jdubois.bootui.autoconfigure.spring.SpringController;
import io.github.jdubois.bootui.autoconfigure.web.AiController;
import io.github.jdubois.bootui.autoconfigure.web.BeansController;
import io.github.jdubois.bootui.autoconfigure.web.ClaudeCodeSessionStore;
import io.github.jdubois.bootui.autoconfigure.web.ConfigController;
import io.github.jdubois.bootui.autoconfigure.web.CopilotSessionStore;
import io.github.jdubois.bootui.autoconfigure.web.DevToolsController;
import io.github.jdubois.bootui.autoconfigure.web.DismissedRulesController;
import io.github.jdubois.bootui.autoconfigure.web.HealthController;
import io.github.jdubois.bootui.autoconfigure.web.HttpExchangesController;
import io.github.jdubois.bootui.autoconfigure.web.OtlpReceiverController;
import io.github.jdubois.bootui.autoconfigure.web.OverviewController;
import io.github.jdubois.bootui.autoconfigure.web.TracesController;
import io.github.jdubois.bootui.core.dto.PanelsReport;
import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.audit.AuditEventRepository;
import org.springframework.boot.actuate.web.exchanges.HttpExchangeRepository;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.webflux.actuate.web.exchanges.HttpExchangesWebFilter;
import org.springframework.boot.webflux.autoconfigure.HttpHandlerAutoConfiguration;
import org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.test.StepVerifier;

/**
 * Tests for {@link BootUiReactiveAutoConfiguration}: proves the reactive (WebFlux) BootUI entry point
 * activates under the exact same {@link BootUiActivationCondition} rules as the servlet
 * {@link BootUiAutoConfiguration} (the activation condition itself is framework-neutral and already
 * exhaustively tested by {@code BootUiAutoConfigurationTests}, so this suite focuses on the
 * reactive-specific wiring: the {@code @ConditionalOnWebApplication(REACTIVE)} gate, the safety filter
 * beans, and the static shell/assets serving over WebFlux).
 */
class BootUiReactiveAutoConfigurationTests {

    private final ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BootUiReactiveAutoConfiguration.class));

    @Test
    void doesNotActivateInAutoModeWithoutEnablingProfile() {
        runner.run(context -> assertThat(context)
                .doesNotHaveBean(BootUiReactiveAutoConfiguration.class)
                .doesNotHaveBean(ReactiveLocalhostOnlyFilter.class));
    }

    @Test
    void activatesWhenEnabledOn() {
        runner.withPropertyValues("bootui.enabled=ON")
                .run(context -> assertThat(context)
                        .hasSingleBean(BootUiReactiveAutoConfiguration.class)
                        .hasSingleBean(ReactiveLocalhostOnlyFilter.class)
                        .hasSingleBean(ReactivePanelAccessFilter.class)
                        .hasSingleBean(KafkaActivityRecorder.class)
                        .hasSingleBean(ReactiveBootUiIndexController.class)
                        .hasSingleBean(OverviewController.class)
                        .hasSingleBean(BootUiActivation.class));
    }

    @Test
    void activatesOnEnabledProfile() {
        runner.withPropertyValues("spring.profiles.active=dev")
                .run(context -> assertThat(context).hasSingleBean(BootUiReactiveAutoConfiguration.class));
    }

    @Test
    void registersStartupBannerListener() {
        // Reactive twin of BootUiAutoConfiguration's own "BootUI is available at ..." banner: proves
        // BootUiReactiveAutoConfiguration no longer silently omits it (the bug this test guards against).
        runner.withPropertyValues("bootui.enabled=ON")
                .run(context -> assertThat(context).hasBean("bootUiStartupBanner"));
    }

    @Test
    void startupBannerListenerRunsWithoutClassLoadingErrors() {
        // Regression test: registersStartupBannerListener above only proves the bean exists - it never
        // fires the listener, so it did not catch a real bug where the lambda threw
        // NoClassDefFoundError: jakarta/servlet/Filter at runtime. That happened because the lambda used
        // to call BootUiAutoConfiguration.buildStartupUrl(...) directly; merely referencing that servlet
        // class forced the JVM to load and verify it, which resolves the jakarta.servlet.Filter-typed
        // signatures of its other @Bean methods (e.g. FilterRegistrationBean<...>) - not present on a
        // pure WebFlux classpath. Actually publishing the event exercises the listener body for real.
        runner.withPropertyValues("bootui.enabled=ON").run(context -> {
            ApplicationReadyEvent event =
                    new ApplicationReadyEvent(new SpringApplication(), new String[0], context, Duration.ZERO);
            assertThatCode(() -> context.publishEvent(event)).doesNotThrowAnyException();
        });
    }

    @Test
    void disabledByProductionProfileEvenWithEnabledProfile() {
        runner.withPropertyValues("spring.profiles.active=prod,dev")
                .run(context -> assertThat(context).doesNotHaveBean(BootUiReactiveAutoConfiguration.class));
    }

    @Test
    void enabledOnOverridesProductionProfile() {
        runner.withPropertyValues("spring.profiles.active=prod", "bootui.enabled=ON")
                .run(context -> {
                    assertThat(context).hasSingleBean(BootUiReactiveAutoConfiguration.class);
                    BootUiActivation activation = context.getBean(BootUiActivation.class);
                    assertThat(activation.enabled()).isTrue();
                    assertThat(activation.warnings()).isNotEmpty();
                });
    }

    @Test
    void enabledOffForcesDisabledEvenWithDevProfile() {
        runner.withPropertyValues("spring.profiles.active=dev", "bootui.enabled=OFF")
                .run(context -> assertThat(context).doesNotHaveBean(BootUiReactiveAutoConfiguration.class));
    }

    @Test
    void invalidEnabledValueFailsClosed() {
        runner.withPropertyValues("spring.profiles.active=dev", "bootui.enabled=maybe")
                .run(context -> assertThat(context).doesNotHaveBean(BootUiReactiveAutoConfiguration.class));
    }

    @Test
    void servletAutoConfigurationNeverActivatesAlongsideReactiveOne() {
        // Documents the mutual-exclusivity invariant from BootUiReactiveAutoConfiguration's Javadoc:
        // even with BOTH auto-configurations on the candidate list, a REACTIVE web application context
        // only ever satisfies the reactive one's @ConditionalOnWebApplication(REACTIVE) gate.
        new ReactiveWebApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(BootUiAutoConfiguration.class, BootUiReactiveAutoConfiguration.class))
                .withPropertyValues("bootui.enabled=ON")
                .run(context -> assertThat(context)
                        .hasSingleBean(BootUiReactiveAutoConfiguration.class)
                        .doesNotHaveBean(BootUiAutoConfiguration.class));
    }

    @Test
    void registersBootUiStaticResourceConfigurerByDefault() {
        webFluxRunner()
                .withPropertyValues("bootui.enabled=ON")
                .run(context -> assertThat(context).hasSingleBean(ReactiveBootUiStaticResourceConfigurer.class));
    }

    @Test
    void doesNotRegisterStaticResourceConfigurerWhenInactive() {
        webFluxRunner()
                .run(context -> assertThat(context).doesNotHaveBean(ReactiveBootUiStaticResourceConfigurer.class));
    }

    @Test
    void servesBootUiAssetsFromClasspath() {
        // bootui.allow-non-localhost=true: WebTestClient.bindToApplicationContext(...) has no simulated
        // TCP peer (unlike servlet MockHttpServletRequest, which defaults remoteAddr to 127.0.0.1), so
        // ReactiveLocalhostOnlyFilter would otherwise fail-closed on a null remote address. That
        // adapter-level safety behavior has its own precise coverage in ReactiveLocalhostOnlyFilterTests;
        // this test is about resource-serving wiring, not the safety filter.
        webFluxRunner()
                .withPropertyValues("bootui.enabled=ON", "bootui.allow-non-localhost=true")
                .run(context -> {
                    WebTestClient client = WebTestClient.bindToApplicationContext(context.getSourceApplicationContext())
                            .build();
                    // Fixture-backed test resource at META-INF/resources/bootui/index.html (see
                    // BootUiStaticResourceConfigurerTests for the servlet-side twin of this fixture).
                    client.get()
                            .uri("/bootui/index.html")
                            .exchange()
                            .expectStatus()
                            .isOk()
                            .expectBody(String.class)
                            .value(body -> assertThat(body).contains("bootui-test-index"));
                });
    }

    @Test
    void servesBootUiAssetsEvenWhenDefaultResourceMappingsDisabled() {
        webFluxRunner()
                .withPropertyValues(
                        "bootui.enabled=ON",
                        "bootui.allow-non-localhost=true",
                        "spring.web.resources.add-mappings=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(ReactiveBootUiStaticResourceConfigurer.class);
                    WebTestClient client = WebTestClient.bindToApplicationContext(context.getSourceApplicationContext())
                            .build();
                    client.get()
                            .uri("/bootui/index.html")
                            .exchange()
                            .expectStatus()
                            .isOk()
                            .expectBody(String.class)
                            .value(body -> assertThat(body).contains("bootui-test-index"));
                });
    }

    @Test
    void serveSpaShellAtBootUiRoot() {
        webFluxRunner()
                .withPropertyValues("bootui.enabled=ON", "bootui.allow-non-localhost=true")
                .run(context -> {
                    WebTestClient client = WebTestClient.bindToApplicationContext(context.getSourceApplicationContext())
                            .build();
                    // BootUiIndexController.INDEX_LOCATION resolves to the same
                    // META-INF/resources/bootui/index.html test fixture the static-asset tests use, so this is a
                    // genuine end-to-end proof that DispatcherHandler routed the request to the reactive
                    // @Controller bean (via WebFluxAutoConfiguration's annotation mapping) and that it served the
                    // real production ClassPathResource constructor path, not just the stubbed test constructor
                    // exercised by ReactiveBootUiIndexControllerTests.
                    client.get()
                            .uri("/bootui")
                            .exchange()
                            .expectStatus()
                            .isOk()
                            .expectHeader()
                            .contentTypeCompatibleWith(org.springframework.http.MediaType.TEXT_HTML)
                            .expectBody(String.class)
                            .value(body -> assertThat(body)
                                    .contains("bootui-test-index")
                                    .contains("<base href=\"/bootui/\" />"));
                });
    }

    @Test
    void registersThePhase2ReadOnlyPanelControllers() {
        // Bulk-imported controllers that were already framework-neutral (no HttpServletRequest/Response,
        // no org.springframework.web.servlet.* types, no SseEmitter) and needed zero adaptation beyond
        // being added to @Import. Representative sample across the panel groups, not exhaustive.
        // HibernateController is deliberately excluded here: like on the servlet adapter, it carries its
        // own @ConditionalOnClass(EntityManagerFactory, SessionFactory) gate, and this module's test
        // classpath has the JPA API but not a Hibernate implementation, so it is correctly absent from
        // both stacks in this slice - see HibernatePanelAvailabilityTests-style coverage for the gate
        // itself, not a reactive-specific concern.
        runner.withPropertyValues("bootui.enabled=ON")
                .run(context -> assertThat(context)
                        .hasSingleBean(BeansController.class)
                        .hasSingleBean(ConfigController.class)
                        .hasSingleBean(HealthController.class)
                        .hasSingleBean(ArchitectureController.class)
                        .hasSingleBean(RestApiController.class)
                        .hasSingleBean(PentestingController.class)
                        .hasSingleBean(SpringController.class)
                        .hasSingleBean(GraalVmController.class)
                        .hasSingleBean(CracController.class)
                        .hasSingleBean(MemoryController.class)
                        .hasSingleBean(HttpExchangesController.class)
                        .hasSingleBean(HttpExchangesWebFilter.class)
                        .hasSingleBean(HttpExchangeRepository.class)
                        .hasSingleBean(TracesController.class)
                        .hasSingleBean(AiController.class)
                        .hasSingleBean(OtlpReceiverController.class)
                        .hasSingleBean(DevToolsController.class)
                        .hasSingleBean(DismissedRulesController.class));
    }

    @Test
    void servesReadOnlyPanelsOverHttp() {
        // End-to-end proof that DispatcherHandler actually routes to these beans under WebFlux, not
        // just that they exist in the context. Covers the newly-added reactive HTTP Exchanges wiring
        // (ReactiveHttpExchangeRepositoryConfiguration) and the re-wired TelemetryStore/OTel path (Traces).
        webFluxRunner()
                .withPropertyValues("bootui.enabled=ON", "bootui.allow-non-localhost=true")
                .run(context -> {
                    WebTestClient client = WebTestClient.bindToApplicationContext(context.getSourceApplicationContext())
                            .build();
                    client.get()
                            .uri("/bootui/api/overview")
                            .exchange()
                            .expectStatus()
                            .isOk();
                    client.get()
                            .uri("/bootui/api/health")
                            .exchange()
                            .expectStatus()
                            .isOk();
                    client.get()
                            .uri("/bootui/api/beans")
                            .exchange()
                            .expectStatus()
                            .isOk();
                    client.get()
                            .uri("/bootui/api/config")
                            .exchange()
                            .expectStatus()
                            .isOk();
                    client.get()
                            .uri("/bootui/api/http-exchanges")
                            .exchange()
                            .expectStatus()
                            .isOk();
                    client.get()
                            .uri("/bootui/api/traces")
                            .exchange()
                            .expectStatus()
                            .isOk();
                    client.get()
                            .uri("/bootui/api/ai/overview")
                            .exchange()
                            .expectStatus()
                            .isOk();
                    client.get()
                            .uri("/bootui/api/pentesting")
                            .exchange()
                            .expectStatus()
                            .isOk();
                });
    }

    @Test
    void servesTheLazyControllersThatNeededNewlyWiredBeans() {
        // Genuine HTTP proof (not just context-refresh hasSingleBean checks, which do not force
        // construction of these @Lazy-marked beans) that the beans added specifically to satisfy these
        // controllers' constructors actually work: DismissedRulesStore (Architecture, REST API, Spring,
        // Memory, Vulnerabilities, and DismissedRulesController itself), OtlpSpanDecoder
        // (OtlpReceiverController), and DevToolsBridge (DevToolsController).
        webFluxRunner()
                .withPropertyValues("bootui.enabled=ON", "bootui.allow-non-localhost=true")
                .run(context -> {
                    WebTestClient client = WebTestClient.bindToApplicationContext(context.getSourceApplicationContext())
                            .build();
                    client.get()
                            .uri("/bootui/api/architecture")
                            .exchange()
                            .expectStatus()
                            .isOk();
                    client.get()
                            .uri("/bootui/api/rest-api")
                            .exchange()
                            .expectStatus()
                            .isOk();
                    client.get()
                            .uri("/bootui/api/spring")
                            .exchange()
                            .expectStatus()
                            .isOk();
                    client.get()
                            .uri("/bootui/api/memory")
                            .exchange()
                            .expectStatus()
                            .isOk();
                    client.get()
                            .uri("/bootui/api/vulnerabilities")
                            .exchange()
                            .expectStatus()
                            .isOk();
                    client.get()
                            .uri("/bootui/api/dismissed-rules")
                            .exchange()
                            .expectStatus()
                            .isOk();
                    client.get()
                            .uri("/bootui/api/devtools")
                            .exchange()
                            .expectStatus()
                            .isOk();
                    // Empty body takes OtlpReceiverController's early-return branch (no protobuf decode
                    // needed), so this proves the bean wiring without hand-rolling a valid OTLP payload.
                    client.post()
                            .uri("/bootui/api/otlp/v1/traces")
                            .contentType(MediaType.valueOf("application/x-protobuf"))
                            .bodyValue(new byte[0])
                            .exchange()
                            .expectStatus()
                            .isOk();
                });
    }

    @Test
    void reactiveHttpExchangeRepositoryCapturesRequests() {
        // Genuinely new code (not a mechanical re-@Import): proves the reactive HttpExchangesWebFilter
        // records exchanges into the same framework-neutral HttpExchangeRepository that the reused
        // HttpExchangesController reads, exactly as the servlet HttpExchangesFilter does. Every request
        // in this test hits a /bootui/** path, so BootUiSelfDataFilter correctly hides it from the
        // visible list (self-exclusion) while still counting it as recorded - the same behavior the
        // servlet adapter has for its own self-traffic.
        webFluxRunner()
                .withPropertyValues("bootui.enabled=ON", "bootui.allow-non-localhost=true")
                .run(context -> {
                    WebTestClient client = WebTestClient.bindToApplicationContext(context.getSourceApplicationContext())
                            .build();
                    client.get()
                            .uri("/bootui/api/overview")
                            .exchange()
                            .expectStatus()
                            .isOk();
                    client.get()
                            .uri("/bootui/api/http-exchanges")
                            .exchange()
                            .expectStatus()
                            .isOk()
                            .expectBody(String.class)
                            .value(body ->
                                    assertThat(body).contains("\"recorded\":1").contains("\"hiddenSelf\":1"));
                });
    }

    @Test
    void pentestingScanGracefullyHandlesAbsentRequestMappingInfoHandlerMapping() {
        // WebFluxAutoConfiguration never registers RequestMappingInfoHandlerMapping (an MVC-only bean),
        // so SpringPentestingObservationCollector's ObjectProvider<RequestMappingInfoHandlerMapping> is
        // empty under a genuine reactive context, even though the class itself is still resolvable on
        // this module's combined test classpath. Proves the collector's empty-inventory fallback (see
        // BootUiReactiveAutoConfiguration's Javadoc) works end-to-end rather than throwing.
        webFluxRunner()
                .withPropertyValues("bootui.enabled=ON", "bootui.allow-non-localhost=true")
                .run(context -> {
                    WebTestClient client = WebTestClient.bindToApplicationContext(context.getSourceApplicationContext())
                            .build();
                    client.post()
                            .uri("/bootui/api/pentesting/scan")
                            .exchange()
                            .expectStatus()
                            .isOk();
                });
    }

    @Test
    void registersThePhase3SseAndExceptionCapturePanelControllers() {
        // The genuinely-new Phase 3 group: panels that needed a bespoke reactive streaming primitive
        // (ReactiveBootUiChangeStream or a custom Flux.create) instead of a mechanical @Import, plus
        // the reactive exception-capture path. Also asserts the supporting beans each one needed:
        // ExceptionStore/ReactiveBootUiExceptionHandler (Exceptions), SqlTraceRecorder (SQL Trace),
        // AuditEventRepository (Security Logs, via the duplicated fallback configuration), and the two
        // agent session stores (Copilot/Claude Code).
        runner.withPropertyValues("bootui.enabled=ON")
                .run(context -> assertThat(context)
                        .hasSingleBean(ReactiveExceptionsController.class)
                        .hasSingleBean(ReactiveBootUiExceptionHandler.class)
                        .hasSingleBean(ExceptionStore.class)
                        .hasSingleBean(ReactiveSqlTraceController.class)
                        .hasSingleBean(SqlTraceRecorder.class)
                        .hasSingleBean(ReactiveSecurityLogsController.class)
                        .hasSingleBean(AuditEventRepository.class)
                        .hasSingleBean(ReactiveLogTailController.class)
                        .hasSingleBean(ReactiveCopilotController.class)
                        .hasSingleBean(CopilotSessionStore.class)
                        .hasSingleBean(ReactiveClaudeCodeController.class)
                        .hasSingleBean(ClaudeCodeSessionStore.class));
    }

    @Test
    void servesThePhase3ReadEndpointsOverHttp() {
        // Genuine HTTP proof (not just hasSingleBean) that DispatcherHandler routes to every Phase 3
        // controller's read endpoint, including the ones behind a @Lazy-marked bean definition
        // (ReactiveExceptionsController, ReactiveSqlTraceController, ReactiveSecurityLogsController,
        // ReactiveCopilotController, ReactiveClaudeCodeController - see servesTheLazyControllersThat
        // NeededNewlyWiredBeans for why hasSingleBean alone cannot catch a missing constructor
        // dependency on a lazy bean).
        webFluxRunner()
                .withPropertyValues("bootui.enabled=ON", "bootui.allow-non-localhost=true")
                .run(context -> {
                    WebTestClient client = WebTestClient.bindToApplicationContext(context.getSourceApplicationContext())
                            .build();
                    client.get()
                            .uri("/bootui/api/sql-trace")
                            .exchange()
                            .expectStatus()
                            .isOk();
                    client.get()
                            .uri("/bootui/api/exceptions")
                            .exchange()
                            .expectStatus()
                            .isOk();
                    client.get()
                            .uri("/bootui/api/security-logs")
                            .exchange()
                            .expectStatus()
                            .isOk();
                    client.get()
                            .uri("/bootui/api/log-tail/recent")
                            .exchange()
                            .expectStatus()
                            .isOk();
                    client.get()
                            .uri("/bootui/api/copilot/sessions")
                            .exchange()
                            .expectStatus()
                            .isOk();
                    client.get()
                            .uri("/bootui/api/claude-code/sessions")
                            .exchange()
                            .expectStatus()
                            .isOk();
                });
    }

    @Test
    void sqlTraceStreamPushesAnUpdateEventWhenRecordingIsToggled() {
        // Proof that the real, Spring-constructed ReactiveSqlTraceController bean (built through the full
        // autoconfiguration, with its constructor's recorder.subscribe(changeStream::signal) wiring genuinely
        // executed) emits a coalesced "update" SSE event when the underlying recorder changes - not just that
        // ReactiveBootUiChangeStream behaves correctly in isolation (see ReactiveBootUiChangeStreamTests for that
        // unit-level coverage). The controller's stream() method is invoked directly rather than through
        // WebTestClient: bindToApplicationContext's mock connector materializes/buffers the exchange and does
        // not reliably support a never-completing SSE Flux that has not yet emitted at least one element, which
        // made the plain HTTP round trip hang; content-type negotiation for a Flux<ServerSentEvent<T>> return
        // type is generic Spring machinery already exercised by servesThePhase3ReadEndpointsOverHttp for this
        // same controller's non-streaming endpoint. SqlTraceRecorder#setRecording only notifies listeners when
        // the value actually changes, hence flipping the default (recording=true) to false.
        webFluxRunner()
                .withPropertyValues("bootui.enabled=ON", "bootui.allow-non-localhost=true")
                .run(context -> {
                    ReactiveSqlTraceController controller = context.getBean(ReactiveSqlTraceController.class);
                    SqlTraceRecorder recorder = context.getBean(SqlTraceRecorder.class);

                    StepVerifier.create(controller.stream())
                            .then(() -> recorder.setRecording(false))
                            .assertNext(event -> assertThat(event.event()).isEqualTo("update"))
                            .thenCancel()
                            .verify(Duration.ofSeconds(5));
                });
    }

    @Test
    void reactiveExceptionHandlerCapturesUnhandledControllerExceptionsIntoTheExceptionStore() {
        // Full-stack proof of the reactive exception-capture path: an exception thrown by a genuine
        // handler method, dispatched by the real DispatcherHandler, is observed by
        // ReactiveBootUiExceptionHandler (registered at Ordered.HIGHEST_PRECEDENCE), recorded into the
        // shared ExceptionStore, and then re-propagated so Spring Boot's own DefaultErrorWebExceptionHandler
        // still renders the error response - and the captured group is visible through the normal
        // ReactiveExceptionsController read endpoint afterwards.
        webFluxRunner()
                .withPropertyValues("bootui.enabled=ON", "bootui.allow-non-localhost=true")
                .withBean(ThrowingTestController.class)
                .run(context -> {
                    WebTestClient client = WebTestClient.bindToApplicationContext(context.getSourceApplicationContext())
                            .build();

                    client.get()
                            .uri("/bootui-test/boom")
                            .exchange()
                            .expectStatus()
                            .is5xxServerError();

                    client.get()
                            .uri("/bootui/api/exceptions")
                            .exchange()
                            .expectStatus()
                            .isOk()
                            .expectBody(String.class)
                            .value(body -> assertThat(body)
                                    .contains("IllegalStateException")
                                    .contains("synthetic failure for exception-capture test"));
                });
    }

    @RestController
    static class ThrowingTestController {

        @GetMapping("/bootui-test/boom")
        public String boom() {
            throw new IllegalStateException("synthetic failure for exception-capture test");
        }
    }

    @Test
    void panelsManifestReportsTheReactivePlatformOverHttp() {
        // Full-stack proof (real DispatcherHandler, real WebFluxAutoConfiguration) that the shared,
        // unmodified PanelsController correctly self-detects a genuine reactive ApplicationContext and
        // (a) reports the "spring-boot-reactive" platform discriminator and (b) marks the panels with no
        // faithful reactive equivalent (HTTP Sessions), or not yet ported (Spring Security advisor, MCP
        // Server - neither is wired into this autoconfiguration, see its class Javadoc), as unavailable
        // with a WebFlux-specific reason - complementing PanelsControllerTests' unit-level coverage of the
        // same behavior with an end-to-end HTTP round trip through the real reactive autoconfiguration
        // stack. Live Activity IS wired into this autoconfiguration (ReactiveLiveActivityController), so it
        // must report available here too.
        webFluxRunner()
                .withPropertyValues("bootui.enabled=ON", "bootui.allow-non-localhost=true")
                .run(context -> {
                    WebTestClient client = WebTestClient.bindToApplicationContext(context.getSourceApplicationContext())
                            .build();

                    client.get()
                            .uri("/bootui/api/panels")
                            .exchange()
                            .expectStatus()
                            .isOk()
                            .expectBody()
                            .jsonPath("$.platform")
                            .isEqualTo(PanelsReport.PLATFORM_SPRING_BOOT_REACTIVE)
                            .jsonPath("$.panels[?(@.id=='" + BootUiPanels.HTTP_SESSIONS + "')].available")
                            .isEqualTo(false)
                            .jsonPath("$.panels[?(@.id=='" + BootUiPanels.HTTP_SESSIONS + "')].unavailableReason")
                            .<List<String>>value(singleReasonStartingWith("Not applicable on Spring WebFlux"))
                            .jsonPath("$.panels[?(@.id=='" + BootUiPanels.SPRING_SECURITY + "')].available")
                            .isEqualTo(false)
                            .jsonPath("$.panels[?(@.id=='" + BootUiPanels.SPRING_SECURITY + "')].unavailableReason")
                            .<List<String>>value(singleReasonStartingWith("Not yet ported for Spring WebFlux"))
                            .jsonPath("$.panels[?(@.id=='" + BootUiPanels.MCP_SERVER + "')].available")
                            .isEqualTo(false)
                            .jsonPath("$.panels[?(@.id=='" + BootUiPanels.MCP_SERVER + "')].unavailableReason")
                            .<List<String>>value(singleReasonStartingWith("Not yet ported for Spring WebFlux"))
                            .jsonPath("$.panels[?(@.id=='" + BootUiPanels.ACTIVITY + "')].available")
                            .isEqualTo(true);
                });
    }

    private static ReactiveWebApplicationContextRunner webFluxRunner() {
        return new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        HttpHandlerAutoConfiguration.class,
                        WebFluxAutoConfiguration.class,
                        BootUiReactiveAutoConfiguration.class));
    }

    /**
     * Replaces the deprecated-for-removal {@code JsonPathAssertions.value(Matcher)} overload: asserts the
     * JsonPath filter projection matched exactly one panel and its {@code unavailableReason} starts with
     * {@code prefix}.
     */
    private static Consumer<List<String>> singleReasonStartingWith(String prefix) {
        return values -> {
            assertThat(values).hasSize(1);
            assertThat(values.get(0)).startsWith(prefix);
        };
    }
}
