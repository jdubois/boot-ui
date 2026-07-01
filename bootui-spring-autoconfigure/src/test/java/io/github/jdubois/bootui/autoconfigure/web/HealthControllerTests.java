package io.github.jdubois.bootui.autoconfigure.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.core.dto.HealthNodeDto;
import io.github.jdubois.bootui.core.dto.HealthSetupStepDto;
import io.github.jdubois.bootui.engine.health.HealthService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Thin-controller wiring tests for {@link HealthController}.
 *
 * <p>The Actuator -&gt; DTO mapping is covered by {@code SpringHealthProviderTests} and the
 * DISABLED/guidance shaping by the engine {@code HealthServiceTests}; this test only proves the GET
 * endpoint delegates to the engine {@link HealthService} and serializes the node it returns.</p>
 */
class HealthControllerTests {

    private static MockMvc mvcReturning(HealthNodeDto node) {
        HealthService service = mock(HealthService.class);
        when(service.health()).thenReturn(node);
        return standaloneSetup(new HealthController(service)).build();
    }

    @Test
    void healthSerializesTheNodeFromTheEngineService() throws Exception {
        HealthNodeDto node = new HealthNodeDto(
                "application", "UP", Map.of("ping", "pong"), List.of(new HealthNodeDto("db", "UP", null, List.of())));

        mvcReturning(node)
                .perform(get("/bootui/api/health").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("application"))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.components[0].name").value("db"));
    }

    @Test
    void healthSerializesADisabledRoot() throws Exception {
        HealthNodeDto node = new HealthNodeDto(
                "application",
                "DISABLED",
                null,
                List.of(),
                false,
                "backend unavailable",
                null,
                List.of(new HealthSetupStepDto("Add a backend", "do it", List.of("dep:health"))));

        mvcReturning(node)
                .perform(get("/bootui/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"))
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.unavailableReason").value("backend unavailable"))
                .andExpect(jsonPath("$.setup[0].title").value("Add a backend"));
    }
}
