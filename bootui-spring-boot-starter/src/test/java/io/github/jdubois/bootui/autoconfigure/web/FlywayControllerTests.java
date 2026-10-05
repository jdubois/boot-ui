package io.github.jdubois.bootui.autoconfigure.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.core.dto.FlywayActionRequest;
import io.github.jdubois.bootui.core.dto.FlywayActionResult;
import io.github.jdubois.bootui.core.dto.FlywayReport;
import io.github.jdubois.bootui.engine.flyway.FlywayActionResponse;
import io.github.jdubois.bootui.engine.flyway.FlywayService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Thin HTTP-binding tests for {@link FlywayController}: they mock the engine {@link FlywayService} and assert
 * only that the controller delegates and maps the {@link FlywayActionResponse} status onto the
 * {@code ResponseEntity}. The Flyway-typed behaviour (discovery, mapping, Spring-Modulith) is covered by
 * {@code SpringFlywayProviderTests}; the neutral orchestration by {@code FlywayServiceTests} in the engine.
 */
class FlywayControllerTests {

    private final FlywayService flywayService = mock(FlywayService.class);
    private final MockMvc mvc =
            standaloneSetup(new FlywayController(flywayService)).build();

    @Test
    void migrationsDelegatesToTheEngine() throws Exception {
        when(flywayService.report()).thenReturn(new FlywayReport(true, 0, List.of()));

        mvc.perform(get("/bootui/api/flyway/migrations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flywayPresent").value(true))
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.databases").isEmpty());
    }

    @Test
    void migrateMapsBlockedStatusOntoResponseEntity() throws Exception {
        when(flywayService.migrate(any(FlywayActionRequest.class)))
                .thenReturn(new FlywayActionResponse(
                        400,
                        new FlywayActionResult(
                                "blocked",
                                "Action requires confirm=true because it mutates the application database.",
                                "flyway",
                                null,
                                List.of(),
                                List.of(),
                                null,
                                List.of())));

        mvc.perform(post("/bootui/api/flyway/migrate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"beanName\":\"flyway\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("blocked"))
                .andExpect(jsonPath("$.beanName").value("flyway"));
    }

    @Test
    void migrateMapsSuccessStatusOntoResponseEntity() throws Exception {
        when(flywayService.migrate(any(FlywayActionRequest.class)))
                .thenReturn(new FlywayActionResponse(
                        200,
                        new FlywayActionResult(
                                "success",
                                "Flyway applied 2 migration(s).",
                                "flyway",
                                2,
                                List.of(),
                                List.of(),
                                null,
                                List.of("w"))));

        mvc.perform(post("/bootui/api/flyway/migrate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"beanName\":\"flyway\",\"confirm\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.migrationsExecuted").value(2))
                .andExpect(jsonPath("$.warnings[0]").value("w"));
    }

    @Test
    void cleanMapsForbiddenStatusOntoResponseEntity() throws Exception {
        when(flywayService.clean(any(FlywayActionRequest.class)))
                .thenReturn(new FlywayActionResponse(
                        403,
                        new FlywayActionResult(
                                "blocked",
                                "Flyway clean is disabled by Flyway configuration.",
                                "flyway",
                                null,
                                List.of(),
                                List.of(),
                                null,
                                List.of())));

        mvc.perform(post("/bootui/api/flyway/clean")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"beanName\":\"flyway\",\"confirm\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value("blocked"));
    }

    @Test
    void cleanMapsSuccessStatusOntoResponseEntity() throws Exception {
        when(flywayService.clean(any(FlywayActionRequest.class)))
                .thenReturn(new FlywayActionResponse(
                        200,
                        new FlywayActionResult(
                                "success",
                                "Flyway cleaned schema(s) for flyway.",
                                "flyway",
                                null,
                                List.of("public"),
                                List.of("archive"),
                                null,
                                List.of())));

        mvc.perform(post("/bootui/api/flyway/clean")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"beanName\":\"flyway\",\"confirm\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.schemasCleaned[0]").value("public"))
                .andExpect(jsonPath("$.schemasDropped[0]").value("archive"));
    }
}
