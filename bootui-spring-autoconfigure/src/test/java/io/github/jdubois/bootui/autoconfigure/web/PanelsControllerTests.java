package io.github.jdubois.bootui.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.zaxxer.hikari.HikariDataSource;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.dto.PanelDto;
import io.github.jdubois.bootui.core.dto.PanelsReport;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.web.context.reactive.GenericReactiveWebApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

class PanelsControllerTests {

    private static final List<String> PANEL_IDS =
            BootUiPanels.all().stream().map(BootUiPanels.Panel::id).toList();

    @Test
    void panelsListsEverySidebarPanel() throws Exception {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.refresh();
            PanelsController controller =
                    new PanelsController(context, context.getEnvironment(), new BootUiProperties());
            MockMvc mvc = standaloneSetup(controller).build();

            assertThat(controller.panels().platform()).isEqualTo(PanelsReport.PLATFORM_SPRING_BOOT);
            assertThat(controller.panels().panels()).extracting(PanelDto::id).containsExactlyElementsOf(PANEL_IDS);

            mvc.perform(get("/bootui/api/panels"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.platform").value(PanelsReport.PLATFORM_SPRING_BOOT))
                    .andExpect(jsonPath("$.panels.length()").value(PANEL_IDS.size()));
        }
    }

    @Test
    void panelsMarksAlwaysAvailablePanelsAsAvailable() throws Exception {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.refresh();
            MockMvc mvc = standaloneSetup(
                            new PanelsController(context, context.getEnvironment(), new BootUiProperties()))
                    .build();

            mvc.perform(get("/bootui/api/panels"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath(panelPath(BootUiPanels.OVERVIEW) + ".available")
                            .value(true))
                    .andExpect(jsonPath(panelPath(BootUiPanels.LIVE_MEMORY) + ".available")
                            .value(true))
                    .andExpect(jsonPath(panelPath(BootUiPanels.JVM_TUNING) + ".available")
                            .value(true))
                    .andExpect(jsonPath(panelPath(BootUiPanels.HEAP_DUMP) + ".available")
                            .value(true))
                    .andExpect(jsonPath(panelPath(BootUiPanels.THREADS) + ".available")
                            .value(true))
                    .andExpect(jsonPath(panelPath(BootUiPanels.CONFIG) + ".available")
                            .value(true))
                    .andExpect(jsonPath(panelPath(BootUiPanels.PENTESTING) + ".available")
                            .value(true))
                    .andExpect(jsonPath(panelPath(BootUiPanels.TRACES) + ".available")
                            .value(true))
                    .andExpect(jsonPath(panelPath(BootUiPanels.HTTP_PROBE) + ".available")
                            .value(true))
                    .andExpect(jsonPath(panelPath(BootUiPanels.VULNERABILITIES) + ".available")
                            .value(true))
                    .andExpect(jsonPath(panelPath(BootUiPanels.ACTIVITY) + ".available")
                            .value(true));
        }
    }

    @Test
    void panelsMarksActuatorBackedPanelsUnavailableWhenEndpointsAreAbsent() throws Exception {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.refresh();
            MockMvc mvc = standaloneSetup(
                            new PanelsController(context, context.getEnvironment(), new BootUiProperties()))
                    .build();

            mvc.perform(get("/bootui/api/panels"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath(panelPath(BootUiPanels.HEALTH) + ".available")
                            .value(false))
                    .andExpect(jsonPath(panelPath(BootUiPanels.HTTP_SESSIONS) + ".available")
                            .value(false))
                    .andExpect(jsonPath(panelPath(BootUiPanels.HTTP_SESSIONS) + ".unavailableReason")
                            .value("HTTP Sessions require an embedded servlet web server"))
                    .andExpect(jsonPath(panelPath(BootUiPanels.METRICS) + ".available")
                            .value(false))
                    .andExpect(jsonPath(panelPath(BootUiPanels.STARTUP) + ".available")
                            .value(false))
                    .andExpect(jsonPath(panelPath(BootUiPanels.LOGGERS) + ".available")
                            .value(false))
                    .andExpect(jsonPath(panelPath(BootUiPanels.BEANS) + ".available")
                            .value(false))
                    .andExpect(jsonPath(panelPath(BootUiPanels.CONDITIONS) + ".available")
                            .value(false))
                    .andExpect(jsonPath(panelPath(BootUiPanels.MAPPINGS) + ".available")
                            .value(false))
                    .andExpect(jsonPath(panelPath(BootUiPanels.SECURITY_LOGS) + ".available")
                            .value(false))
                    .andExpect(jsonPath(panelPath(BootUiPanels.SECURITY_LOGS) + ".unavailableReason")
                            .value("No AuditEventRepository bean is available"))
                    .andExpect(jsonPath(panelPath(BootUiPanels.DATABASE_CONNECTION_POOLS) + ".available")
                            .value(false))
                    .andExpect(jsonPath(panelPath(BootUiPanels.DATABASE_CONNECTION_POOLS) + ".unavailableReason")
                            .value("No database connection pool beans are available"))
                    .andExpect(jsonPath(panelPath(BootUiPanels.HIBERNATE) + ".available")
                            .value(false))
                    .andExpect(jsonPath(panelPath(BootUiPanels.HIBERNATE) + ".unavailableReason")
                            .value("Hibernate ORM is not on the classpath"))
                    .andExpect(jsonPath(panelPath(BootUiPanels.HTTP_EXCHANGES) + ".available")
                            .value(false))
                    .andExpect(jsonPath(panelPath(BootUiPanels.HTTP_EXCHANGES) + ".unavailableReason")
                            .value("HTTP exchange repository not available"))
                    .andExpect(jsonPath(panelPath(BootUiPanels.FLYWAY) + ".available")
                            .value(false))
                    .andExpect(jsonPath(panelPath(BootUiPanels.FLYWAY) + ".unavailableReason")
                            .value("No Flyway beans are available"))
                    .andExpect(jsonPath(panelPath(BootUiPanels.LIQUIBASE) + ".available")
                            .value(false))
                    .andExpect(jsonPath(panelPath(BootUiPanels.LIQUIBASE) + ".unavailableReason")
                            .value("No Liquibase beans are available"));
        }
    }

    @Test
    void panelsMarksDatabaseConnectionPoolsAvailableForProxiedHikariDataSource() throws Exception {
        HikariDataSource target = mock(HikariDataSource.class);
        ProxyFactory proxyFactory = new ProxyFactory();
        proxyFactory.setInterfaces(DataSource.class);
        proxyFactory.setTarget(target);
        DataSource proxy = (DataSource) proxyFactory.getProxy();

        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean("dataSource", DataSource.class, () -> proxy);
            context.refresh();
            MockMvc mvc = standaloneSetup(
                            new PanelsController(context, context.getEnvironment(), new BootUiProperties()))
                    .build();

            mvc.perform(get("/bootui/api/panels"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath(panelPath(BootUiPanels.DATABASE_CONNECTION_POOLS) + ".available")
                            .value(true));
        }
    }

    @Test
    void panelsMarksTelemetryPanelsUnavailableWhenTelemetryIsDisabled() throws Exception {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.refresh();
            BootUiProperties properties = new BootUiProperties();
            properties.getTelemetry().setEnabled(false);
            MockMvc mvc = standaloneSetup(new PanelsController(context, context.getEnvironment(), properties))
                    .build();

            mvc.perform(get("/bootui/api/panels"))
                    .andExpect(status().isOk())
                    .andExpect(
                            jsonPath(panelPath(BootUiPanels.AI) + ".available").value(false))
                    .andExpect(jsonPath(panelPath(BootUiPanels.AI) + ".unavailableReason")
                            .value("Telemetry receiver is disabled"))
                    .andExpect(jsonPath(panelPath(BootUiPanels.TRACES) + ".available")
                            .value(false))
                    .andExpect(jsonPath(panelPath(BootUiPanels.TRACES) + ".unavailableReason")
                            .value("Telemetry receiver is disabled"));
        }
    }

    @Test
    void panelsMarksAiUnavailableWithoutAiFramework() throws Exception {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.refresh();
            MockMvc mvc = standaloneSetup(
                            new PanelsController(context, context.getEnvironment(), new BootUiProperties()))
                    .build();

            mvc.perform(get("/bootui/api/panels"))
                    .andExpect(status().isOk())
                    .andExpect(
                            jsonPath(panelPath(BootUiPanels.AI) + ".available").value(false))
                    .andExpect(jsonPath(panelPath(BootUiPanels.AI) + ".unavailableReason")
                            .value("Spring AI or LangChain4j is not on the classpath"));
        }
    }

    @Test
    void panelsExposeEnabledAndReadOnlyState() throws Exception {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.refresh();
            BootUiProperties properties = new BootUiProperties();
            properties.panel("config").setEnabled(false);
            properties.panel("loggers").setReadOnly(true);
            properties.panel("pentesting").setReadOnly(true);
            MockMvc mvc = standaloneSetup(new PanelsController(context, context.getEnvironment(), properties))
                    .build();

            mvc.perform(get("/bootui/api/panels"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath(panelPath(BootUiPanels.CONFIG) + ".enabled")
                            .value(false))
                    .andExpect(jsonPath(panelPath(BootUiPanels.CONFIG) + ".available")
                            .value(true))
                    .andExpect(jsonPath(panelPath(BootUiPanels.CONFIG) + ".readOnly")
                            .value(false))
                    .andExpect(jsonPath(panelPath(BootUiPanels.LOGGERS) + ".enabled")
                            .value(true))
                    .andExpect(jsonPath(panelPath(BootUiPanels.LOGGERS) + ".readOnly")
                            .value(true))
                    .andExpect(jsonPath(panelPath(BootUiPanels.LOGGERS) + ".readOnlyReason")
                            .value("Panel is read-only via bootui.panels.loggers.read-only=true"))
                    .andExpect(jsonPath(panelPath(BootUiPanels.PENTESTING) + ".enabled")
                            .value(true))
                    .andExpect(jsonPath(panelPath(BootUiPanels.PENTESTING) + ".readOnly")
                            .value(true))
                    .andExpect(jsonPath(panelPath(BootUiPanels.PENTESTING) + ".readOnlyReason")
                            .value("Panel is read-only via bootui.panels.pentesting.read-only=true"));
        }
    }

    @Test
    void panelsApplyGlobalReadOnlyToEveryActionCapablePanel() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.refresh();
            BootUiProperties properties = new BootUiProperties();
            properties.setReadOnly(true);
            PanelsController controller = new PanelsController(context, context.getEnvironment(), properties);

            List<String> expectedReadOnlyPanelIds = BootUiPanels.all().stream()
                    .filter(BootUiPanels.Panel::actionCapable)
                    .map(BootUiPanels.Panel::id)
                    .toList();
            List<PanelDto> panels = controller.panels().panels();
            List<String> actualReadOnlyPanelIds =
                    panels.stream().filter(PanelDto::readOnly).map(PanelDto::id).toList();

            assertThat(actualReadOnlyPanelIds).containsExactlyElementsOf(expectedReadOnlyPanelIds);
            assertThat(panels)
                    .filteredOn(PanelDto::readOnly)
                    .extracting(PanelDto::readOnlyReason)
                    .containsOnly("BootUI is read-only via bootui.read-only=true");
        }
    }

    @Test
    void panelsMarksNativeImagePanelsUnavailableWhenRunningInNativeImage() throws Exception {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.refresh();
            PanelsController controller =
                    new PanelsController(context, context.getEnvironment(), new BootUiProperties()) {
                        @Override
                        boolean nativeImageDetected() {
                            return true;
                        }
                    };
            MockMvc mvc = standaloneSetup(controller).build();

            mvc.perform(get("/bootui/api/panels"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath(panelPath(BootUiPanels.JVM_TUNING) + ".available")
                            .value(false))
                    .andExpect(jsonPath(panelPath(BootUiPanels.JVM_TUNING) + ".unavailableReason")
                            .value("JVM Tuning is not applicable when running as a GraalVM native image"))
                    .andExpect(jsonPath(panelPath(BootUiPanels.GRAALVM) + ".available")
                            .value(false))
                    .andExpect(
                            jsonPath(panelPath(BootUiPanels.GRAALVM) + ".unavailableReason")
                                    .value(
                                            "GraalVM readiness advisor is not applicable when already running as a native image"))
                    .andExpect(jsonPath(panelPath(BootUiPanels.ARCHITECTURE) + ".available")
                            .value(false))
                    .andExpect(jsonPath(panelPath(BootUiPanels.ARCHITECTURE) + ".unavailableReason")
                            .value("Architecture advisor is not applicable when running as a GraalVM native image"))
                    .andExpect(jsonPath(panelPath(BootUiPanels.REST_API) + ".available")
                            .value(false))
                    .andExpect(jsonPath(panelPath(BootUiPanels.REST_API) + ".unavailableReason")
                            .value("REST API advisor is not applicable when running as a GraalVM native image"))
                    .andExpect(jsonPath(panelPath(BootUiPanels.CRAC) + ".available")
                            .value(false))
                    .andExpect(jsonPath(panelPath(BootUiPanels.CRAC) + ".unavailableReason")
                            .value("CRaC is not applicable when running as a GraalVM native image"));
        }
    }

    private static String panelPath(String id) {
        return "$.panels[" + PANEL_IDS.indexOf(id) + "]";
    }

    @Test
    void panelsReportsTheReactivePlatformAndDivergentAvailabilityUnderWebFlux() throws Exception {
        // GenericReactiveWebApplicationContext is the Spring Boot marker Spring uses for a genuine WebFlux
        // (reactive) ApplicationContext - the same shared PanelsController that BootUiReactiveAutoConfiguration
        // imports unmodified must detect it and (a) report the reactive platform discriminator and (b) mark the
        // panels that have no faithful reactive equivalent (HTTP Sessions) as unavailable with a
        // WebFlux-specific reason, instead of relying on incidental classpath presence. MCP Server and
        // Live Activity are ported reactively, so they stay available here too.
        // SPRING_SECURITY (the raw panel, not the advisor) is now ported to WebFlux via
        // ReactiveSpringSecurityController, so in a reactive context without a WebFilterChainProxy bean it
        // reports unavailable with the "no chains" reason (not the old "not yet ported" message), and in a
        // context where WebFilterChainProxy is registered it reports available:true (tested separately).
        try (GenericReactiveWebApplicationContext context = new GenericReactiveWebApplicationContext()) {
            context.refresh();
            PanelsController controller =
                    new PanelsController(context, context.getEnvironment(), new BootUiProperties());
            MockMvc mvc = standaloneSetup(controller).build();

            assertThat(controller.panels().platform()).isEqualTo(PanelsReport.PLATFORM_SPRING_BOOT_REACTIVE);

            mvc.perform(get("/bootui/api/panels"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.platform").value(PanelsReport.PLATFORM_SPRING_BOOT_REACTIVE))
                    .andExpect(jsonPath(panelPath(BootUiPanels.HTTP_SESSIONS) + ".available")
                            .value(false))
                    .andExpect(jsonPath(panelPath(BootUiPanels.HTTP_SESSIONS) + ".unavailableReason")
                            .value(startsWith("Not applicable on Spring WebFlux")))
                    .andExpect(jsonPath(panelPath(BootUiPanels.SPRING_SECURITY) + ".available")
                            .value(false))
                    .andExpect(jsonPath(panelPath(BootUiPanels.SPRING_SECURITY) + ".unavailableReason")
                            .value(startsWith("No reactive Spring Security filter chains are available")))
                    .andExpect(jsonPath(panelPath(BootUiPanels.MCP_SERVER) + ".available")
                            .value(true))
                    .andExpect(jsonPath(panelPath(BootUiPanels.REST_CLIENT_TRACE) + ".available")
                            .value(false))
                    .andExpect(jsonPath(panelPath(BootUiPanels.REST_CLIENT_TRACE) + ".unavailableReason")
                            .value("REST client tracing is not configured"))
                    .andExpect(jsonPath(panelPath(BootUiPanels.ACTIVITY) + ".available")
                            .value(true));
        }
    }

    @Test
    void springSecurityPanelIsAvailableUnderWebFluxWhenApplicationChainBeanPresent() throws Exception {
        // Verifies that the SPRING_SECURITY panel (the raw panel, not the Security advisor) reports
        // available:true on a reactive WebFlux application context when an application-owned
        // SecurityWebFilterChain bean is registered.
        try (GenericReactiveWebApplicationContext context = new GenericReactiveWebApplicationContext()) {
            context.registerBean(
                    "applicationSecurityWebFilterChain",
                    org.springframework.security.web.server.SecurityWebFilterChain.class,
                    () -> mock(org.springframework.security.web.server.SecurityWebFilterChain.class));
            context.refresh();
            PanelsController controller =
                    new PanelsController(context, context.getEnvironment(), new BootUiProperties());
            MockMvc mvc = standaloneSetup(controller).build();

            mvc.perform(get("/bootui/api/panels"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath(panelPath(BootUiPanels.SPRING_SECURITY) + ".available")
                            .value(true));
        }
    }

    @Test
    void springSecurityPanelIgnoresBootUiOwnReactiveChain() throws Exception {
        try (GenericReactiveWebApplicationContext context = new GenericReactiveWebApplicationContext()) {
            context.registerBean(
                    "bootUiReactiveSecurityWebFilterChain",
                    org.springframework.security.web.server.SecurityWebFilterChain.class,
                    () -> mock(org.springframework.security.web.server.SecurityWebFilterChain.class));
            context.refresh();
            PanelsController controller =
                    new PanelsController(context, context.getEnvironment(), new BootUiProperties());
            MockMvc mvc = standaloneSetup(controller).build();

            mvc.perform(get("/bootui/api/panels"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath(panelPath(BootUiPanels.SPRING_SECURITY) + ".available")
                            .value(false));
        }
    }

    @Test
    void restClientTraceStaysUnavailableUnderWebFluxWhenNoClientIsInstrumented() throws Exception {
        // RestClientTraceBackendConfiguration deliberately declares the RestClientTraceRecorder bean in shared
        // engine wiring (BootUiEngineConfiguration) so both the servlet BootUiAutoConfiguration and reactive
        // BootUiReactiveAutoConfiguration reuse the exact same instance for Live Activity capture — so on
        // WebFlux, beanPresent(RestClientTraceRecorder.class) alone is always true and cannot be used to gate
        // this panel's availability. Availability is driven by the recorder's own hasInstrumentedClient()
        // signal: this test proves the panel still reports unavailable when no WebClient has been customized
        // yet (matching the servlet adapter's pre-customizer state), so the panel does not light up an empty
        // buffer on WebFlux.
        try (GenericReactiveWebApplicationContext context = new GenericReactiveWebApplicationContext()) {
            context.registerBean(
                    "bootUiRestClientTraceRecorder",
                    RestClientTraceRecorder.class,
                    () -> new RestClientTraceRecorder(true, true, true, true, 100, 1000L, 200, 200, 5));
            context.refresh();
            PanelsController controller =
                    new PanelsController(context, context.getEnvironment(), new BootUiProperties());
            MockMvc mvc = standaloneSetup(controller).build();

            mvc.perform(get("/bootui/api/panels"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath(panelPath(BootUiPanels.REST_CLIENT_TRACE) + ".available")
                            .value(false))
                    .andExpect(jsonPath(panelPath(BootUiPanels.REST_CLIENT_TRACE) + ".unavailableReason")
                            .value("No RestClient, RestTemplate, or WebClient has been instrumented yet."));
        }
    }

    @Test
    void restClientTraceBecomesAvailableUnderWebFluxOnceWebClientIsInstrumented() throws Exception {
        // Proves the reactive PanelsController lights up REST_CLIENT_TRACE (same as servlet) once the
        // recorder's hasInstrumentedClient() flips to true — i.e. once a WebClient.Builder customizer fires.
        try (GenericReactiveWebApplicationContext context = new GenericReactiveWebApplicationContext()) {
            RestClientTraceRecorder recorder =
                    new RestClientTraceRecorder(true, true, true, true, 100, 1000L, 200, 200, 5);
            context.registerBean("bootUiRestClientTraceRecorder", RestClientTraceRecorder.class, () -> recorder);
            context.refresh();
            PanelsController controller =
                    new PanelsController(context, context.getEnvironment(), new BootUiProperties());
            MockMvc mvc = standaloneSetup(controller).build();

            // Before instrumentation: unavailable.
            mvc.perform(get("/bootui/api/panels"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath(panelPath(BootUiPanels.REST_CLIENT_TRACE) + ".available")
                            .value(false));

            // Simulate the WebClientCustomizer callback firing.
            recorder.registerClientCustomization("WebClient");

            // After instrumentation: available.
            mvc.perform(get("/bootui/api/panels"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath(panelPath(BootUiPanels.REST_CLIENT_TRACE) + ".available")
                            .value(true));
        }
    }

    @Test
    void restClientTraceUnavailableOnServletWhenRecorderBeanIsAbsent() throws Exception {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.refresh();
            PanelsController controller =
                    new PanelsController(context, context.getEnvironment(), new BootUiProperties());
            MockMvc mvc = standaloneSetup(controller).build();

            mvc.perform(get("/bootui/api/panels"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath(panelPath(BootUiPanels.REST_CLIENT_TRACE) + ".available")
                            .value(false))
                    .andExpect(jsonPath(panelPath(BootUiPanels.REST_CLIENT_TRACE) + ".unavailableReason")
                            .value("REST client tracing is not configured"));
        }
    }

    @Test
    void restClientTraceAvailableOnServletOnceAClientIsInstrumented() throws Exception {
        // RestClientTraceBackendConfiguration declares the RestClientTraceRecorder bean unconditionally
        // whenever BootUI is active, so mere bean presence can't signal "the application configured a REST
        // client" - this proves the panel instead tracks the recorder's own hasInstrumentedClient() signal
        // (the same one RestClientTraceController uses for its empty state), exactly like the
        // Kafka/Email/Cache panels track their own beans, and flips to available once a client customizer
        // actually fires.
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            RestClientTraceRecorder recorder =
                    new RestClientTraceRecorder(true, true, true, true, 100, 1000L, 200, 200, 5);
            context.registerBean("bootUiRestClientTraceRecorder", RestClientTraceRecorder.class, () -> recorder);
            context.refresh();
            PanelsController controller =
                    new PanelsController(context, context.getEnvironment(), new BootUiProperties());
            MockMvc mvc = standaloneSetup(controller).build();

            mvc.perform(get("/bootui/api/panels"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath(panelPath(BootUiPanels.REST_CLIENT_TRACE) + ".available")
                            .value(false))
                    .andExpect(jsonPath(panelPath(BootUiPanels.REST_CLIENT_TRACE) + ".unavailableReason")
                            .value("No RestClient, RestTemplate, or WebClient has been instrumented yet."));

            recorder.registerClientCustomization("RestClient");

            mvc.perform(get("/bootui/api/panels"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath(panelPath(BootUiPanels.REST_CLIENT_TRACE) + ".available")
                            .value(true));
        }
    }

    @Test
    void restClientTraceUnavailableOnServletWhenTracingIsDisabled() throws Exception {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            RestClientTraceRecorder recorder =
                    new RestClientTraceRecorder(false, true, true, true, 100, 1000L, 200, 200, 5);
            context.registerBean("bootUiRestClientTraceRecorder", RestClientTraceRecorder.class, () -> recorder);
            context.refresh();
            PanelsController controller =
                    new PanelsController(context, context.getEnvironment(), new BootUiProperties());
            MockMvc mvc = standaloneSetup(controller).build();

            mvc.perform(get("/bootui/api/panels"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath(panelPath(BootUiPanels.REST_CLIENT_TRACE) + ".available")
                            .value(false))
                    .andExpect(jsonPath(panelPath(BootUiPanels.REST_CLIENT_TRACE) + ".unavailableReason")
                            .value("REST client tracing is disabled (set bootui.rest-client-trace.enabled=true in a "
                                    + "trusted local profile)."));
        }
    }

    @Test
    void securityAdvisorStaysUnavailableUnderWebFluxEvenWithReactiveSpringSecurityConfigured() throws Exception {
        // The SECURITY advisor panel (distinct from the SPRING_SECURITY raw-config panel) has no reactive
        // ruleset and is not imported by BootUiReactiveAutoConfiguration.
        try (GenericReactiveWebApplicationContext context = new GenericReactiveWebApplicationContext()) {
            java.util.function.Supplier<org.springframework.security.web.server.WebFilterChainProxy> supplier =
                    () -> new org.springframework.security.web.server.WebFilterChainProxy(List.of());
            context.registerBean(
                    "springSecurityWebFilterChain",
                    org.springframework.security.web.server.WebFilterChainProxy.class,
                    supplier);
            context.refresh();
            PanelsController controller =
                    new PanelsController(context, context.getEnvironment(), new BootUiProperties());
            MockMvc mvc = standaloneSetup(controller).build();

            mvc.perform(get("/bootui/api/panels"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath(panelPath(BootUiPanels.SECURITY) + ".available")
                            .value(false))
                    .andExpect(jsonPath(panelPath(BootUiPanels.SECURITY) + ".unavailableReason")
                            .value("Security Advisor is only available on the Spring MVC (servlet) adapter"));
        }
    }
}
