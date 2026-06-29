package io.github.jdubois.bootui.autoconfigure.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.core.dto.MappingDto;
import io.github.jdubois.bootui.core.dto.MappingsReport;
import io.github.jdubois.bootui.engine.mappings.MappingsService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Thin MVC wiring tests for the neutral {@link MappingsController}: it delegates {@code GET
 * /bootui/api/mappings/flat} to the engine {@link MappingsService} (mocked here, house style) and
 * serializes the report. The flatten/self-filter logic is covered by {@code SpringMappingProviderTests}
 * and the sort/query/page logic by the engine {@code MappingsServiceTests}.
 */
class MappingsControllerTests {

    @Test
    void flatDelegatesQueryOffsetAndLimitToTheEngineService() throws Exception {
        MappingsService service = mock(MappingsService.class);
        MappingsReport report = new MappingsReport(
                1, List.of(new MappingDto("GET", "/sample", "com.example.SampleController#sample", null, null)));
        when(service.report(eq("sample"), eq(5), eq(10))).thenReturn(report);

        MockMvc mvc = standaloneSetup(new MappingsController(service)).build();

        mvc.perform(get("/bootui/api/mappings/flat")
                        .param("q", "sample")
                        .param("offset", "5")
                        .param("limit", "10")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.mappings.length()").value(1))
                .andExpect(jsonPath("$.mappings[0].pattern").value("/sample"));

        verify(service).report("sample", 5, 10);
    }

    @Test
    void flatPassesNullParamsThroughWhenAbsent() throws Exception {
        MappingsService service = mock(MappingsService.class);
        when(service.report(null, null, null)).thenReturn(new MappingsReport(0, List.of()));

        MockMvc mvc = standaloneSetup(new MappingsController(service)).build();

        mvc.perform(get("/bootui/api/mappings/flat").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.mappings.length()").value(0));

        verify(service).report(null, null, null);
    }
}
