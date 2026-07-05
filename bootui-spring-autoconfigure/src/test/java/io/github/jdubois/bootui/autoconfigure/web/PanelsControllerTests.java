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
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.web.context.reactive.GenericReactiveWebApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

class PanelsControllerTests {

    private static final List<String> PANEL_IDS = List.of(
            BootUiPanels.OVERVIEW,
            BootUiPanels.HEALTH,
            BootUiPanels.HTTP_SESSIONS,
            BootUiPanels.METRICS,
            BootUiPanels.LIVE_MEMORY,
            BootUiPanels.JVM_TUNING,
            BootUiPanels.HEAP_DUMP,
            BootUiPanels.THREADS,
            BootUiPanels.MEMORY,
            BootUiPanels.STARTUP,
            BootUiPanels.GRAALVM,
            BootUiPanels.CONFIG,
            BootUiPanels.PROFILE_DIFF,
            BootUiPanels.LOGGERS,
            BootUiPanels.BEANS,
            BootUiPanels.CONDITIONS,
            BootUiPanels.MAPPINGS,
            BootUiPanels.SPRING_SECURITY,
            BootUiPanels.SECURITY,
            BootUiPanels.SECURITY_LOGS,
            BootUiPanels.PENTESTING,
            BootUiPanels.SCHEDULED,
            BootUiPanels.DATABASE_CONNECTION_POOLS,
            BootUiPanels.DATA,
            BootUiPanels.HIBERNATE,
            BootUiPanels.CACHE,
            BootUiPanels.AI,
            BootUiPanels.TRACES,
            BootUiPanels.LOG_TAIL,
            BootUiPanels.EXCEPTIONS,
            BootUiPanels.HTTP_EXCHANGES,
            BootUiPanels.HTTP_PROBE,
            BootUiPanels.ARCHITECTURE,
            BootUiPanels.VULNERABILITIES,
            BootUiPanels.DEVTOOLS,
            BootUiPanels.DEV_SERVICES,
            BootUiPanels.COPILOT,
            BootUiPanels.CLAUDE_CODE,
            BootUiPanels.FLYWAY,
            BootUiPanels.LIQUIBASE,
            BootUiPanels.GITHUB,
            BootUiPanels.REST_API,
            BootUiPanels.SPRING,
            BootUiPanels.CRAC,
            BootUiPanels.SQL_TRACE,
            BootUiPanels.MCP_SERVER,
            BootUiPanels.ACTIVITY);

    @Test
    void panelsListsEverySidebarPanel() throws Exception {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.refresh();
            PanelsController controller =
                    new PanelsController(context, context.getEnvironment(), new BootUiProperties());
            MockMvc mvc = standaloneSetup(controller).build();

            assertThat(BootUiPanels.all()).extracting(BootUiPanels.Panel::id).containsExactlyElementsOf(PANEL_IDS);
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
        // panels that have no faithful reactive equivalent (HTTP Sessions) or are not yet ported (Spring
        // Security advisor, MCP Server, Live Activity) as unavailable with a WebFlux-specific reason, instead
        // of relying on incidental classpath presence.
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
                            .value(startsWith("Not yet ported for Spring WebFlux")))
                    .andExpect(jsonPath(panelPath(BootUiPanels.MCP_SERVER) + ".available")
                            .value(false))
                    .andExpect(jsonPath(panelPath(BootUiPanels.MCP_SERVER) + ".unavailableReason")
                            .value(startsWith("Not yet ported for Spring WebFlux")))
                    .andExpect(jsonPath(panelPath(BootUiPanels.ACTIVITY) + ".available")
                            .value(false))
                    .andExpect(jsonPath(panelPath(BootUiPanels.ACTIVITY) + ".unavailableReason")
                            .value(startsWith("Not yet ported for Spring WebFlux")));
        }
    }

    @Test
    void securityAdvisorStaysUnavailableUnderWebFluxEvenWithReactiveSpringSecurityConfigured() throws Exception {
        // The SECURITY advisor panel (distinct from the SPRING_SECURITY raw-config panel) is NOT in
        // BootUiReactiveAutoConfiguration's @Import list, so it must never report available:true on a
        // reactive app - unlike SPRING_SECURITY/HTTP_SESSIONS/MCP_SERVER/ACTIVITY, its availability check
        // (securityAvailable()) has no explicit !isReactive() guard; it instead relies on
        // org.springframework.security.web.FilterChainProxy (the servlet security filter bean, extends
        // GenericFilterBean) and org.springframework.security.web.server.WebFilterChainProxy (the reactive
        // security filter bean, implements WebFilter) being genuinely unrelated types in the same
        // spring-security-web jar. This test proves that holds even when a real reactive Spring Security
        // setup - a WebFilterChainProxy bean - is present: beanPresent(FilterChainProxy.class) must still
        // resolve false, so the panel does not falsely light up a dead link.
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
                            .value("No Spring Security filter chains are available"));
        }
    }
}
