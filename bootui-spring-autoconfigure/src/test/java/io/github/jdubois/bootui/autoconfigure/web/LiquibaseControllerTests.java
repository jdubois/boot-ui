package io.github.jdubois.bootui.autoconfigure.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.core.dto.LiquibaseActionResult;
import io.github.jdubois.bootui.core.dto.LiquibaseReport;
import io.github.jdubois.bootui.engine.liquibase.LiquibaseActionResponse;
import io.github.jdubois.bootui.engine.liquibase.LiquibaseService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP-level tests for the thin {@link LiquibaseController}.
 *
 * <p>The controller only maps the request/response transport onto the engine {@link LiquibaseService}; the
 * change-log assembly and update orchestration are covered by {@code LiquibaseServiceTests} and the
 * Spring-specific discovery by {@code SpringLiquibaseProviderTests}. These tests assert only the wiring.</p>
 */
class LiquibaseControllerTests {

    @Test
    void changeSetsDelegatesToTheEngineReport() throws Exception {
        LiquibaseService service = mock(LiquibaseService.class);
        when(service.report()).thenReturn(new LiquibaseReport(true, 0, List.of()));
        MockMvc mvc = standaloneSetup(new LiquibaseController(service)).build();

        mvc.perform(get("/bootui/api/liquibase/changesets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.liquibasePresent").value(true))
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.databases").isEmpty());
    }

    @Test
    void updateMapsTheEngineStatusOntoTheHttpResponse() throws Exception {
        LiquibaseService service = mock(LiquibaseService.class);
        when(service.update(any()))
                .thenReturn(new LiquibaseActionResponse(
                        403,
                        new LiquibaseActionResult(
                                "blocked",
                                "Liquibase update cannot run because this bean has no DataSource.",
                                "liquibase",
                                null,
                                null,
                                null,
                                List.of())));
        MockMvc mvc = standaloneSetup(new LiquibaseController(service)).build();

        mvc.perform(post("/bootui/api/liquibase/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"beanName\":\"liquibase\",\"confirm\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value("blocked"))
                .andExpect(jsonPath("$.beanName").value("liquibase"));
    }

    @Test
    void updateReturnsTheEngineSuccessBody() throws Exception {
        LiquibaseService service = mock(LiquibaseService.class);
        when(service.update(any()))
                .thenReturn(new LiquibaseActionResponse(
                        200,
                        new LiquibaseActionResult(
                                "success",
                                "Liquibase applied 2 change set(s).",
                                "inventoryLiquibase",
                                3,
                                1,
                                2,
                                List.of())));
        MockMvc mvc = standaloneSetup(new LiquibaseController(service)).build();

        mvc.perform(post("/bootui/api/liquibase/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"beanName\":\"inventoryLiquibase\",\"confirm\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.beanName").value("inventoryLiquibase"))
                .andExpect(jsonPath("$.changeSetsApplied").value(2));
    }
}
