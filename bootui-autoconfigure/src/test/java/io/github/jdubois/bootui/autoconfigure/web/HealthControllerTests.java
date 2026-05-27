package io.github.jdubois.bootui.autoconfigure.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
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
 * <p>Covers the missing-actuator UNKNOWN root, a simple indicated UP health,
 * a DOWN health with details, and a composite health with child components.
 * {@link IndicatedHealthDescriptor} is {@code final} and uses
 * {@link MockMakers#INLINE}; {@link CompositeHealthDescriptor} is non-final
 * and uses the default mock maker.</p>
 */
class HealthControllerTests {

    @Test
    void healthReturnsUnknownWhenActuatorUnavailable() throws Exception {
        MockMvc mvc = standaloneSetup(new HealthController(emptyProvider())).build();

        mvc.perform(get("/bootui/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("application"))
                .andExpect(jsonPath("$.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.components").isArray())
                .andExpect(jsonPath("$.components").isEmpty());
    }

    @Test
    void healthReturnsSimpleUpStatus() throws Exception {
        IndicatedHealthDescriptor indicated = mock(IndicatedHealthDescriptor.class,
                withSettings().mockMaker(MockMakers.INLINE));
        when(indicated.getStatus()).thenReturn(Status.UP);
        when(indicated.getDetails()).thenReturn(Map.of("ping", "pong"));

        HealthEndpoint endpoint = mock(HealthEndpoint.class);
        when(endpoint.health()).thenReturn(indicated);

        MockMvc mvc = standaloneSetup(new HealthController(providerOf(endpoint))).build();

        mvc.perform(get("/bootui/api/health").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("application"))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").isArray())
                .andExpect(jsonPath("$.components").isEmpty());
    }

    @Test
    void healthReturnsDownStatus() throws Exception {
        IndicatedHealthDescriptor downDescriptor = mock(IndicatedHealthDescriptor.class,
                withSettings().mockMaker(MockMakers.INLINE));
        when(downDescriptor.getStatus()).thenReturn(Status.DOWN);
        when(downDescriptor.getDetails()).thenReturn(Map.of("error", "Connection refused"));

        HealthEndpoint endpoint = mock(HealthEndpoint.class);
        when(endpoint.health()).thenReturn(downDescriptor);

        MockMvc mvc = standaloneSetup(new HealthController(providerOf(endpoint))).build();

        mvc.perform(get("/bootui/api/health").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DOWN"));
    }

    @Test
    void healthReturnsCompositeWithComponents() throws Exception {
        IndicatedHealthDescriptor dbDescriptor = mock(IndicatedHealthDescriptor.class,
                withSettings().mockMaker(MockMakers.INLINE));
        when(dbDescriptor.getStatus()).thenReturn(Status.UP);
        when(dbDescriptor.getDetails()).thenReturn(Map.of("database", "H2"));

        IndicatedHealthDescriptor diskDescriptor = mock(IndicatedHealthDescriptor.class,
                withSettings().mockMaker(MockMakers.INLINE));
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

        MockMvc mvc = standaloneSetup(new HealthController(providerOf(endpoint))).build();

        mvc.perform(get("/bootui/api/health").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("application"))
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.components").isArray())
                .andExpect(jsonPath("$.components.length()").value(2))
                .andExpect(jsonPath("$.components[?(@.name=='db')].status").value("UP"))
                .andExpect(jsonPath("$.components[?(@.name=='diskSpace')].status").value("DOWN"));
    }

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
}
