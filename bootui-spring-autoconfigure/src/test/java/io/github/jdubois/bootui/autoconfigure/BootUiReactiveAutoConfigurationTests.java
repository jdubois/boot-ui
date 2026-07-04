package io.github.jdubois.bootui.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.architecture.ArchitectureController;
import io.github.jdubois.bootui.autoconfigure.crac.CracController;
import io.github.jdubois.bootui.autoconfigure.graalvm.GraalVmController;
import io.github.jdubois.bootui.autoconfigure.memory.MemoryController;
import io.github.jdubois.bootui.autoconfigure.pentesting.PentestingController;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveBootUiIndexController;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveBootUiStaticResourceConfigurer;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveLocalhostOnlyFilter;
import io.github.jdubois.bootui.autoconfigure.reactive.ReactivePanelAccessFilter;
import io.github.jdubois.bootui.autoconfigure.restapi.RestApiController;
import io.github.jdubois.bootui.autoconfigure.spring.SpringController;
import io.github.jdubois.bootui.autoconfigure.web.AiController;
import io.github.jdubois.bootui.autoconfigure.web.BeansController;
import io.github.jdubois.bootui.autoconfigure.web.ConfigController;
import io.github.jdubois.bootui.autoconfigure.web.DevToolsController;
import io.github.jdubois.bootui.autoconfigure.web.DismissedRulesController;
import io.github.jdubois.bootui.autoconfigure.web.HealthController;
import io.github.jdubois.bootui.autoconfigure.web.HttpExchangesController;
import io.github.jdubois.bootui.autoconfigure.web.OtlpReceiverController;
import io.github.jdubois.bootui.autoconfigure.web.OverviewController;
import io.github.jdubois.bootui.autoconfigure.web.TracesController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.web.exchanges.HttpExchangeRepository;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.webflux.actuate.web.exchanges.HttpExchangesWebFilter;
import org.springframework.boot.webflux.autoconfigure.HttpHandlerAutoConfiguration;
import org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

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

    private static ReactiveWebApplicationContextRunner webFluxRunner() {
        return new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        HttpHandlerAutoConfiguration.class,
                        WebFluxAutoConfiguration.class,
                        BootUiReactiveAutoConfiguration.class));
    }
}
