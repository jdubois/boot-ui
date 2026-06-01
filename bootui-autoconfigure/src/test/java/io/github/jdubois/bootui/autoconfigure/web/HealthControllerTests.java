package io.github.jdubois.bootui.autoconfigure.web;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.actuate.endpoint.CompositeHealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.IndicatedHealthDescriptor;
import org.springframework.boot.health.contributor.Status;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Controller-level tests for {@link HealthController}.
 *
 * <p>Covers the missing-actuator disabled root, a simple indicated UP health,
 * a DOWN health with details, and a composite health with child components.
 * {@link IndicatedHealthDescriptor} is {@code final} and uses
 * {@link MockMakers#INLINE}; {@link CompositeHealthDescriptor} is non-final
 * and uses the default mock maker.</p>
 */
class HealthControllerTests {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<HealthEndpoint> providerOf(HealthEndpoint endpoint) {
        ObjectProvider<HealthEndpoint> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(endpoint);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<HealthEndpoint> emptyProvider() {
        ObjectProvider<HealthEndpoint> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    @Test
    void healthReturnsDisabledWhenActuatorUnavailable() throws Exception {
        MockMvc mvc = standaloneSetup(new HealthController(emptyProvider())).build();

        mvc.perform(get("/bootui/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("application"))
                .andExpect(jsonPath("$.status").value("DISABLED"))
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(
                        jsonPath("$.unavailableReason").value("Spring Boot Actuator health endpoint is not available"))
                .andExpect(jsonPath("$.setup[0].title").value("Add Spring Boot Actuator"))
                .andExpect(jsonPath("$.components").isArray())
                .andExpect(jsonPath("$.components").isEmpty());
    }

    @Test
    void healthReturnsSimpleUpStatus() throws Exception {
        IndicatedHealthDescriptor indicated =
                mock(IndicatedHealthDescriptor.class, withSettings().mockMaker(MockMakers.INLINE));
        when(indicated.getStatus()).thenReturn(Status.UP);
        when(indicated.getDetails()).thenReturn(Map.of("ping", "pong"));

        HealthEndpoint endpoint = mock(HealthEndpoint.class);
        when(endpoint.health()).thenReturn(indicated);

        MockMvc mvc =
                standaloneSetup(new HealthController(providerOf(endpoint))).build();

        mvc.perform(get("/bootui/api/health").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("application"))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.setup").isEmpty())
                .andExpect(jsonPath("$.components").isArray())
                .andExpect(jsonPath("$.components").isEmpty());
    }

    @Test
    void healthReturnsDisabledWhenOnlyDefaultIndicatorsArePresent() throws Exception {
        IndicatedHealthDescriptor liveness =
                mock(IndicatedHealthDescriptor.class, withSettings().mockMaker(MockMakers.INLINE));
        when(liveness.getStatus()).thenReturn(Status.UP);
        when(liveness.getDetails()).thenReturn(Map.of("livenessState", "CORRECT"));

        IndicatedHealthDescriptor readiness =
                mock(IndicatedHealthDescriptor.class, withSettings().mockMaker(MockMakers.INLINE));
        when(readiness.getStatus()).thenReturn(Status.UP);
        when(readiness.getDetails()).thenReturn(Map.of("readinessState", "ACCEPTING_TRAFFIC"));

        IndicatedHealthDescriptor ssl =
                mock(IndicatedHealthDescriptor.class, withSettings().mockMaker(MockMakers.INLINE));
        when(ssl.getStatus()).thenReturn(Status.UP);
        when(ssl.getDetails()).thenReturn(Map.of("validChains", List.of()));

        CompositeHealthDescriptor composite = mock(CompositeHealthDescriptor.class);
        when(composite.getStatus()).thenReturn(Status.UP);
        when(composite.getComponents())
                .thenReturn(Map.of(
                        "livenessState", liveness,
                        "readinessState", readiness,
                        "ssl", ssl));

        HealthEndpoint endpoint = mock(HealthEndpoint.class);
        when(endpoint.health()).thenReturn(composite);

        MockMvc mvc =
                standaloneSetup(new HealthController(providerOf(endpoint))).build();

        mvc.perform(get("/bootui/api/health").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"))
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.unavailableReason")
                        .value("Only Spring Boot default health indicators are available"))
                .andExpect(jsonPath("$.setup[0].title").value("Add application health contributors"))
                .andExpect(jsonPath("$.components.length()").value(3))
                .andExpect(jsonPath("$.components[?(@.name=='livenessState')].status")
                        .value("DISABLED"))
                .andExpect(jsonPath("$.components[?(@.name=='readinessState')].status")
                        .value("DISABLED"))
                .andExpect(jsonPath("$.components[?(@.name=='ssl')].status").value("DISABLED"));
    }

    @Test
    void healthKeepsDefaultIndicatorFailuresVisible() throws Exception {
        IndicatedHealthDescriptor disk =
                mock(IndicatedHealthDescriptor.class, withSettings().mockMaker(MockMakers.INLINE));
        when(disk.getStatus()).thenReturn(Status.DOWN);
        when(disk.getDetails()).thenReturn(Map.of("error", "Disk space below threshold"));

        CompositeHealthDescriptor composite = mock(CompositeHealthDescriptor.class);
        when(composite.getStatus()).thenReturn(Status.DOWN);
        when(composite.getComponents()).thenReturn(Map.of("diskSpace", disk));

        HealthEndpoint endpoint = mock(HealthEndpoint.class);
        when(endpoint.health()).thenReturn(composite);

        MockMvc mvc =
                standaloneSetup(new HealthController(providerOf(endpoint))).build();

        mvc.perform(get("/bootui/api/health").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(
                        jsonPath("$.components[?(@.name=='diskSpace')].status").value("DOWN"));
    }

    @Test
    void healthReturnsDownStatus() throws Exception {
        IndicatedHealthDescriptor downDescriptor =
                mock(IndicatedHealthDescriptor.class, withSettings().mockMaker(MockMakers.INLINE));
        when(downDescriptor.getStatus()).thenReturn(Status.DOWN);
        when(downDescriptor.getDetails()).thenReturn(Map.of("error", "Connection refused"));

        HealthEndpoint endpoint = mock(HealthEndpoint.class);
        when(endpoint.health()).thenReturn(downDescriptor);

        MockMvc mvc =
                standaloneSetup(new HealthController(providerOf(endpoint))).build();

        mvc.perform(get("/bootui/api/health").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DOWN"));
    }

    @Test
    void healthReturnsCompositeWithComponents() throws Exception {
        IndicatedHealthDescriptor dbDescriptor =
                mock(IndicatedHealthDescriptor.class, withSettings().mockMaker(MockMakers.INLINE));
        when(dbDescriptor.getStatus()).thenReturn(Status.UP);
        when(dbDescriptor.getDetails()).thenReturn(Map.of("database", "H2"));

        IndicatedHealthDescriptor diskDescriptor =
                mock(IndicatedHealthDescriptor.class, withSettings().mockMaker(MockMakers.INLINE));
        when(diskDescriptor.getStatus()).thenReturn(new Status("DOWN"));
        when(diskDescriptor.getDetails()).thenReturn(Map.of("free", 1024L));

        Map<String, HealthDescriptor> components = Map.of(
                "db", dbDescriptor,
                "diskSpace", diskDescriptor);

        CompositeHealthDescriptor composite = mock(CompositeHealthDescriptor.class);
        when(composite.getStatus()).thenReturn(new Status("DOWN"));
        when(composite.getComponents()).thenReturn(components);

        HealthEndpoint endpoint = mock(HealthEndpoint.class);
        when(endpoint.health()).thenReturn(composite);

        MockMvc mvc =
                standaloneSetup(new HealthController(providerOf(endpoint))).build();

        mvc.perform(get("/bootui/api/health").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("application"))
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.components").isArray())
                .andExpect(jsonPath("$.components.length()").value(2))
                .andExpect(jsonPath("$.components[?(@.name=='db')].status").value("UP"))
                .andExpect(
                        jsonPath("$.components[?(@.name=='diskSpace')].status").value("DOWN"));
    }
}
